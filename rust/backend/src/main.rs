// Additional GPLv3 section 7(b) attribution term for tryigit-owned material: see ../../NOTICE.
mod attest_key_store;
mod backend_instance;
mod certificate_wire;
mod crl_wire;
mod keybox_fd;
mod keybox_wire;

use cleverestricky_cbox_recovery_core::{
    decrypt_cbox_with_recovery_key, derive_recovery_key, RECOVERY_KEY_BYTES,
};
use cleverestricky_crypto_core::{
    decrypt_backup, decrypt_cbox, encrypt_backup_owned, verify_cbox_signature, CboxPayload,
};
use cleverestricky_service_core::fd_transport::receive_one_fd;
use cleverestricky_service_core::ipc::{
    read_header_bounded, write_frame_bounded, FLAG_ERROR, OP_CRYPTO_BACKUP_DECRYPT,
    OP_CRYPTO_BACKUP_ENCRYPT, OP_CRYPTO_CBOX_OPEN, OP_KEYBOX_PARSE,
};
use cleverestricky_service_core::unix_socket::{bind_abstract, peer_credentials};
use std::env;
use std::io::{self, Read, Write};
use std::os::fd::{AsRawFd, FromRawFd, RawFd};
use std::os::unix::net::{UnixListener, UnixStream};
use std::process;
use std::time::Duration;
use zeroize::{Zeroize, Zeroizing};

const BACKEND_SOCKET_NAME: &[u8] = b"cleverestricky-backend.v1";
const ANDROID_AID_NOBODY: libc::uid_t = 9999;
const ANDROID_GID_NOBODY: libc::gid_t = 9999;
const CLIENT_TIMEOUT: Duration = Duration::from_secs(60);
const MAX_PASSWORD_BYTES: usize = 4 * 1024;
const MAX_PUBLIC_KEY_BYTES: usize = 16 * 1024;
const MAX_CBOX_BYTES: usize = 10 * 1024 * 1024 + 36;
const MAX_BACKUP_BYTES: usize = 32 * 1024 * 1024;
const MAX_KEYBOX_REQUEST_BYTES: usize = keybox_wire::MAX_KEYBOX_XML_BYTES;
const MAX_KEYBOX_FILE_REQUEST_BYTES: usize = 1 + 255;
const BACKUP_ENCRYPTION_RESERVE_BYTES: usize = 64;
const MAX_CBOX_REQUEST_BYTES: usize =
    2 + MAX_PASSWORD_BYTES + 2 + MAX_PUBLIC_KEY_BYTES + MAX_CBOX_BYTES;
const MAX_CBOX_RECOVERY_REQUEST_BYTES: usize =
    2 + MAX_PUBLIC_KEY_BYTES + RECOVERY_KEY_BYTES + MAX_CBOX_BYTES;
const MAX_BACKUP_REQUEST_BYTES: usize = 2 + MAX_PASSWORD_BYTES + MAX_BACKUP_BYTES + 64;
const MAX_BACKEND_FRAME_BYTES: usize = MAX_BACKUP_REQUEST_BYTES;
const MAX_BACKUP_RESPONSE_BYTES: usize = MAX_BACKUP_BYTES + 64;
const MAX_KEYBOX_RESPONSE_BYTES: usize = keybox_wire::MAX_KEYBOX_RESPONSE_BYTES;
const MAX_CBOX_AUTHOR_UTF16_UNITS: usize = 1024;
const MAX_CBOX_AUTHOR_BYTES: usize = 4 * MAX_CBOX_AUTHOR_UTF16_UNITS;
const CBOX_RESPONSE_PREFIX_BYTES: usize = 7;
const MAX_CBOX_RESPONSE_BYTES: usize =
    CBOX_RESPONSE_PREFIX_BYTES + MAX_CBOX_AUTHOR_BYTES + MAX_KEYBOX_RESPONSE_BYTES;
const MAX_CBOX_UNLOCK_RESPONSE_BYTES: usize = RECOVERY_KEY_BYTES + MAX_CBOX_RESPONSE_BYTES;
const MAX_ERROR_BYTES: usize = 256;
const BACKEND_STATUS_REJECTED: u8 = 1;
const BACKEND_STATUS_UNKNOWN_KEY_ID: u8 = 2;
const BACKEND_BROKER_FD: RawFd = 9;
const OP_KEYBOX_FILE_PARSE: u16 = 24;
const OP_CERTIFICATE_INSPECT: u16 = 25;
const OP_CERTIFICATE_REWRITE: u16 = 26;
const OP_CRL_CHECK_BATCH: u16 = 27;
const OP_CBOX_UNLOCK: u16 = 29;
const OP_KEYBOX_BROKER_OPEN: u16 = 30;
const OP_CBOX_RECOVER: u16 = 31;
const OP_ATTEST_KEY_REWRITE: u16 = 32;
const OP_CHILD_KEY_REWRITE: u16 = 33;
const OP_ATTEST_KEY_CLEAR: u16 = 34;
const ATTEST_KEY_CLEAR_REQUEST_BYTES: usize = 1;
const ATTEST_KEY_CLEAR_RESPONSE_BYTES: usize = 1;
const OP_ATTEST_KEY_TOUCH: u16 = 35;
const ATTEST_KEY_TOUCH_REQUEST_BYTES: usize = 37;
const ATTEST_KEY_TOUCH_RESPONSE_BYTES: usize = 1;
const OP_ATTEST_KEY_REMOVE: u16 = 36;
const ATTEST_KEY_REMOVE_REQUEST_BYTES: usize = 37;
const ATTEST_KEY_REMOVE_RESPONSE_BYTES: usize = 1;
const OP_ATTEST_KEY_ALIAS: u16 = 37;
const ATTEST_KEY_ALIAS_REQUEST_BYTES: usize = 69;
const ATTEST_KEY_ALIAS_RESPONSE_BYTES: usize = 1;
const SCOPE_CONFIG_ROOT: u8 = 0;
const SCOPE_KEYBOX_DIRECTORY: u8 = 1;

fn main() {
    if let Err(error) = run() {
        eprintln!("cleverestricky_backend: {error}");
        process::exit(1);
    }
}

fn run() -> io::Result<()> {
    let adapter_pid = parse_adapter_pid()?;
    // Authenticate the inherited privileged broker before permanently dropping
    // credentials and exposing the backend listener to Android clients.
    let mut broker = take_broker_stream()?;
    harden_process()?;
    backend_instance::initialize()?;
    let listener = bind_abstract(BACKEND_SOCKET_NAME)?;
    serve(listener, adapter_pid, &mut broker)
}

fn parse_adapter_pid() -> io::Result<u32> {
    let mut arguments = env::args_os();
    let _program = arguments.next();
    let pid = arguments
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing adapter PID"))?;
    if arguments.next().is_some() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "unexpected backend argument",
        ));
    }
    pid.to_str()
        .and_then(|value| value.parse::<u32>().ok())
        .filter(|value| *value > 1 && *value <= i32::MAX as u32)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid adapter PID"))
}

fn take_broker_stream() -> io::Result<UnixStream> {
    // SAFETY: F_GETFD is a scalar validity check for the fixed inherited descriptor.
    if unsafe { libc::fcntl(BACKEND_BROKER_FD, libc::F_GETFD) } < 0 {
        return Err(io::Error::new(
            io::ErrorKind::NotConnected,
            "missing inherited keybox broker descriptor",
        ));
    }
    // SAFETY: the daemon transfers unique ownership of descriptor 9 across exec. This process has
    // not wrapped or closed it yet, so UnixStream becomes its sole Rust owner.
    let stream = unsafe { UnixStream::from_raw_fd(BACKEND_BROKER_FD) };
    let credentials = peer_credentials(&stream)?;
    // SAFETY: getppid is pointer-free and used only to authenticate the private socketpair peer.
    let parent_pid = unsafe { libc::getppid() };
    if parent_pid <= 1
        || credentials.uid != 0
        || credentials.gid != 0
        || credentials.pid != parent_pid
    {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "unexpected keybox broker peer",
        ));
    }
    Ok(stream)
}

fn harden_process() -> io::Result<()> {
    // SAFETY: `getppid` has no pointer arguments and retains no state. Capturing the original parent
    // before credential changes lets us close the PR_SET_PDEATHSIG race below.
    let parent_pid = unsafe { libc::getppid() };
    if parent_pid <= 1 {
        return Err(io::Error::new(
            io::ErrorKind::BrokenPipe,
            "backend supervisor is unavailable",
        ));
    }

    // SAFETY: `umask` takes a value only, retains no pointers, and the backend intentionally applies
    // this process-wide mask before any future file creation could occur.
    unsafe { libc::umask(0o077) };

    // SAFETY: a zero-length supplementary-group list permits a null pointer. The backend begins as a
    // privileged child of the supervisor and discards all supplementary groups before setgid/setuid.
    if unsafe { libc::setgroups(0, std::ptr::null()) } != 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: `setgid` receives a constant Android nobody GID and retains no pointers. It is called
    // exactly once before dropping UID privileges.
    if unsafe { libc::setgid(ANDROID_GID_NOBODY) } != 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: `setuid` receives a constant Android nobody UID and retains no pointers. Linux clears
    // normal root capabilities as part of this permanent credential drop.
    if unsafe { libc::setuid(ANDROID_AID_NOBODY) } != 0 {
        return Err(io::Error::last_os_error());
    }

    // SAFETY: the credential getters have no pointer arguments and are used only to verify that the
    // permanent drop above actually took effect.
    let credentials_dropped = unsafe {
        libc::getuid() == ANDROID_AID_NOBODY
            && libc::geteuid() == ANDROID_AID_NOBODY
            && libc::getgid() == ANDROID_GID_NOBODY
            && libc::getegid() == ANDROID_GID_NOBODY
    };
    if !credentials_dropped {
        return Err(io::Error::other(
            "backend privilege drop did not take effect",
        ));
    }

    // SAFETY: PR_SET_NO_NEW_PRIVS takes scalar arguments only and makes the privilege drop sticky
    // across any accidental future exec. No pointer, lifetime, alignment, aliasing, or ownership
    // assumptions are involved.
    if unsafe { libc::prctl(libc::PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) } != 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: setuid may reset dumpability; applying PR_SET_DUMPABLE after the credential drop keeps
    // keybox and backup plaintext out of core/debug dumps. All arguments are scalar.
    if unsafe { libc::prctl(libc::PR_SET_DUMPABLE, 0, 0, 0, 0) } != 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: credential changes clear an inherited parent-death signal, so the unprivileged process
    // installs it again after setuid. PR_SET_PDEATHSIG retains no pointers.
    if unsafe { libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM, 0, 0, 0) } != 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: `getppid` is pointer-free. If the parent changed before PR_SET_PDEATHSIG was installed,
    // the signal may have been missed; fail closed instead of leaving an orphan parser process.
    if unsafe { libc::getppid() } != parent_pid {
        return Err(io::Error::new(
            io::ErrorKind::BrokenPipe,
            "backend supervisor changed during hardening",
        ));
    }

    set_limit(libc::RLIMIT_CORE as libc::c_int, 0)?;
    set_limit(libc::RLIMIT_NOFILE as libc::c_int, 32)?;
    env::set_current_dir("/")?;
    Ok(())
}

fn set_limit(resource: libc::c_int, value: libc::rlim_t) -> io::Result<()> {
    let limit = libc::rlimit {
        rlim_cur: value,
        rlim_max: value,
    };
    // SAFETY: `limit` is a fully initialized, aligned `rlimit` that remains live for the duration of
    // the call. `setrlimit` copies the structure and retains no pointer or ownership.
    if unsafe { libc::setrlimit(resource as _, &limit) } != 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(())
    }
}

fn serve(listener: UnixListener, adapter_pid: u32, broker: &mut UnixStream) -> io::Result<()> {
    let listener_fd = listener.as_raw_fd();
    let broker_fd = broker.as_raw_fd();

    loop {
        let mut poll_fds = [
            libc::pollfd {
                fd: listener_fd,
                events: libc::POLLIN,
                revents: 0,
            },
            libc::pollfd {
                fd: broker_fd,
                events: libc::POLLIN | libc::POLLHUP | libc::POLLERR,
                revents: 0,
            },
        ];

        // SAFETY: poll receives a pointer to a live 2-element pollfd array.
        let poll_res = unsafe { libc::poll(poll_fds.as_mut_ptr(), 2, -1) };
        if poll_res < 0 {
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(err);
        }

        // If the supervisor broker channel hung up or encountered an error, exit cleanly immediately.
        if (poll_fds[1].revents & (libc::POLLHUP | libc::POLLERR)) != 0 {
            return Ok(());
        }
        if (poll_fds[1].revents & libc::POLLIN) != 0 {
            let mut test_buf = [0u8; 1];
            // SAFETY: MSG_PEEK | MSG_DONTWAIT checks for EOF without draining data from the broker.
            let peek_res = unsafe {
                libc::recv(
                    broker_fd,
                    test_buf.as_mut_ptr().cast(),
                    1,
                    libc::MSG_PEEK | libc::MSG_DONTWAIT,
                )
            };
            if peek_res == 0 {
                return Ok(());
            }
        }

        if (poll_fds[0].revents & libc::POLLIN) == 0 {
            continue;
        }

        let (mut stream, _) = match listener.accept() {
            Ok(value) => value,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(error),
        };
        let credentials = match peer_credentials(&stream) {
            Ok(credentials)
                if credentials.uid == 0
                    && u32::try_from(credentials.pid).ok() == Some(adapter_pid) =>
            {
                credentials
            }
            _ => continue,
        };
        debug_assert_eq!(credentials.uid, 0);
        stream.set_read_timeout(Some(CLIENT_TIMEOUT))?;
        stream.set_write_timeout(Some(CLIENT_TIMEOUT))?;
        if let Err(error) = serve_connection(&mut stream, broker) {
            if error.kind() == io::ErrorKind::NotConnected {
                return Err(error);
            }
            if !matches!(
                error.kind(),
                io::ErrorKind::UnexpectedEof
                    | io::ErrorKind::ConnectionReset
                    | io::ErrorKind::BrokenPipe
                    | io::ErrorKind::TimedOut
                    | io::ErrorKind::WouldBlock
            ) {
                eprintln!("cleverestricky_backend: adapter connection rejected: {error}");
            }
        }
    }
}

fn serve_connection(stream: &mut UnixStream, broker: &mut UnixStream) -> io::Result<()> {
    loop {
        let header = read_header_bounded(stream, MAX_BACKEND_FRAME_BYTES)?;
        if header.flags != 0 {
            reply_error(stream, header.opcode, "invalid backend flags")?;
            return Ok(());
        }
        let Some(request_limit) = opcode_request_limit(header.opcode) else {
            reply_error(stream, header.opcode, "unsupported backend operation")?;
            return Ok(());
        };
        if header.payload_len == 0 || header.payload_len > request_limit {
            reply_error(
                stream,
                header.opcode,
                "backend request exceeds operation bound",
            )?;
            return Ok(());
        }

        let reserve = if header.opcode == OP_CRYPTO_BACKUP_ENCRYPT {
            BACKUP_ENCRYPTION_RESERVE_BYTES
        } else {
            0
        };
        let request_capacity = header.payload_len.checked_add(reserve).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidData, "backend request size overflow")
        })?;
        let mut request = Vec::new();
        request
            .try_reserve_exact(request_capacity)
            .map_err(|_| io::Error::other("backend request allocation failed"))?;
        request.resize(header.payload_len, 0);
        if let Err(error) = stream.read_exact(&mut request) {
            request.zeroize();
            return Err(error);
        }
        let result = if header.opcode == OP_KEYBOX_FILE_PARSE {
            parse_keybox_file(broker, request)?
        } else {
            handle_request(header.opcode, request)
        };
        match result {
            Ok(mut response) => {
                let response_limit = opcode_response_limit(header.opcode).expect("known opcode");
                let write_result =
                    write_frame_bounded(stream, header.opcode, 0, &response, response_limit)
                        .and_then(|()| stream.flush());
                response.zeroize();
                write_result?;
            }
            Err(message) => reply_error(stream, header.opcode, message)?,
        }
    }
}

fn opcode_request_limit(opcode: u16) -> Option<usize> {
    match opcode {
        OP_CRYPTO_CBOX_OPEN | OP_CBOX_UNLOCK => Some(MAX_CBOX_REQUEST_BYTES),
        OP_CBOX_RECOVER => Some(MAX_CBOX_RECOVERY_REQUEST_BYTES),
        OP_CRYPTO_BACKUP_ENCRYPT | OP_CRYPTO_BACKUP_DECRYPT => Some(MAX_BACKUP_REQUEST_BYTES),
        OP_KEYBOX_PARSE => Some(MAX_KEYBOX_REQUEST_BYTES),
        OP_KEYBOX_FILE_PARSE => Some(MAX_KEYBOX_FILE_REQUEST_BYTES),
        OP_CERTIFICATE_INSPECT => Some(certificate_wire::MAX_INSPECT_REQUEST_BYTES),
        OP_CERTIFICATE_REWRITE => Some(certificate_wire::MAX_REWRITE_REQUEST_BYTES),
        OP_ATTEST_KEY_REWRITE => Some(certificate_wire::MAX_ATTEST_KEY_REWRITE_REQUEST_BYTES),
        OP_CHILD_KEY_REWRITE => Some(certificate_wire::MAX_CHILD_KEY_REWRITE_REQUEST_BYTES),
        OP_ATTEST_KEY_CLEAR => Some(ATTEST_KEY_CLEAR_REQUEST_BYTES),
        OP_ATTEST_KEY_TOUCH => Some(ATTEST_KEY_TOUCH_REQUEST_BYTES),
        OP_ATTEST_KEY_REMOVE => Some(ATTEST_KEY_REMOVE_REQUEST_BYTES),
        OP_ATTEST_KEY_ALIAS => Some(ATTEST_KEY_ALIAS_REQUEST_BYTES),
        OP_CRL_CHECK_BATCH => Some(crl_wire::MAX_REQUEST_BYTES),
        backend_instance::OP_BACKEND_PING => Some(backend_instance::REQUEST_BYTES),
        _ => None,
    }
}

fn opcode_response_limit(opcode: u16) -> Option<usize> {
    match opcode {
        OP_CRYPTO_CBOX_OPEN | OP_CBOX_RECOVER => Some(MAX_CBOX_RESPONSE_BYTES),
        OP_CBOX_UNLOCK => Some(MAX_CBOX_UNLOCK_RESPONSE_BYTES),
        OP_CRYPTO_BACKUP_ENCRYPT | OP_CRYPTO_BACKUP_DECRYPT => Some(MAX_BACKUP_RESPONSE_BYTES),
        OP_KEYBOX_PARSE | OP_KEYBOX_FILE_PARSE => Some(MAX_KEYBOX_RESPONSE_BYTES),
        OP_CERTIFICATE_INSPECT => Some(certificate_wire::INSPECT_RESPONSE_BYTES),
        OP_CERTIFICATE_REWRITE | OP_ATTEST_KEY_REWRITE | OP_CHILD_KEY_REWRITE => {
            Some(certificate_wire::MAX_REWRITE_RESPONSE_BYTES)
        }
        OP_ATTEST_KEY_CLEAR => Some(ATTEST_KEY_CLEAR_RESPONSE_BYTES),
        OP_ATTEST_KEY_TOUCH => Some(ATTEST_KEY_TOUCH_RESPONSE_BYTES),
        OP_ATTEST_KEY_REMOVE => Some(ATTEST_KEY_REMOVE_RESPONSE_BYTES),
        OP_ATTEST_KEY_ALIAS => Some(ATTEST_KEY_ALIAS_RESPONSE_BYTES),
        OP_CRL_CHECK_BATCH => Some(crl_wire::MAX_RESPONSE_BYTES),
        backend_instance::OP_BACKEND_PING => Some(backend_instance::RESPONSE_BYTES),
        _ => None,
    }
}

fn parse_keybox_file(
    broker: &mut UnixStream,
    mut request: Vec<u8>,
) -> io::Result<Result<Vec<u8>, &'static str>> {
    if !keybox_file_request_is_valid(&request) {
        request.zeroize();
        return Ok(Err("keybox file request rejected"));
    }
    let send_result = write_frame_bounded(
        broker,
        OP_KEYBOX_BROKER_OPEN,
        0,
        &request,
        MAX_KEYBOX_FILE_REQUEST_BYTES,
    )
    .and_then(|()| broker.flush());
    request.zeroize();
    send_result.map_err(broker_io)?;

    let header = read_header_bounded(broker, MAX_ERROR_BYTES).map_err(broker_io)?;
    if header.opcode != OP_KEYBOX_BROKER_OPEN {
        return Err(broker_protocol("unexpected keybox broker opcode"));
    }
    if header.flags == FLAG_ERROR {
        let mut discard = [0u8; MAX_ERROR_BYTES];
        broker
            .read_exact(&mut discard[..header.payload_len])
            .map_err(broker_io)?;
        discard.zeroize();
        return Ok(Err("keybox file rejected"));
    }
    if header.flags != 0 || header.payload_len != 0 {
        return Err(broker_protocol("invalid keybox broker response"));
    }
    let fd = receive_one_fd(broker).map_err(broker_io)?;
    Ok(keybox_fd::parse_received_fd(fd))
}

fn keybox_file_request_is_valid(request: &[u8]) -> bool {
    if request.len() < 2 || request.len() > MAX_KEYBOX_FILE_REQUEST_BYTES {
        return false;
    }
    let scope = request[0];
    let Ok(name) = std::str::from_utf8(&request[1..]) else {
        return false;
    };
    if name.is_empty() || name == "." || name == ".." || name.contains('/') || name.contains('\0') {
        return false;
    }
    match scope {
        SCOPE_CONFIG_ROOT => name == "keybox.xml",
        SCOPE_KEYBOX_DIRECTORY => name
            .get(name.len().saturating_sub(4)..)
            .is_some_and(|suffix| suffix.eq_ignore_ascii_case(".xml")),
        _ => false,
    }
}

fn broker_io(error: io::Error) -> io::Error {
    io::Error::new(
        io::ErrorKind::NotConnected,
        format!("keybox broker unavailable: {error}"),
    )
}

fn broker_protocol(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::NotConnected, message)
}

fn handle_request(opcode: u16, mut request: Vec<u8>) -> Result<Vec<u8>, &'static str> {
    match opcode {
        OP_CRYPTO_CBOX_OPEN => open_cbox(request),
        OP_CBOX_UNLOCK => unlock_cbox(request),
        OP_CBOX_RECOVER => recover_cbox(request),
        OP_CRYPTO_BACKUP_ENCRYPT => transform_backup(request, true),
        OP_CRYPTO_BACKUP_DECRYPT => transform_backup(request, false),
        OP_KEYBOX_PARSE => keybox_wire::parse_and_encode(request),
        OP_CERTIFICATE_INSPECT => certificate_wire::inspect_and_encode(request),
        OP_CERTIFICATE_REWRITE => certificate_wire::rewrite_and_encode(request),
        OP_ATTEST_KEY_REWRITE => certificate_wire::rewrite_attest_key_and_encode(request),
        OP_CHILD_KEY_REWRITE => certificate_wire::rewrite_child_key_and_encode(request),
        OP_ATTEST_KEY_CLEAR => {
            if request.len() != ATTEST_KEY_CLEAR_REQUEST_BYTES || request[0] != 1 {
                request.zeroize();
                return Err("invalid attest key clear request");
            }
            attest_key_store::clear();
            request.zeroize();
            Ok(vec![0])
        }
        OP_ATTEST_KEY_TOUCH => {
            if request.len() != ATTEST_KEY_TOUCH_REQUEST_BYTES
                || request[0] != certificate_wire::REWRITE_WIRE_VERSION
            {
                request.zeroize();
                return Err("invalid attest key touch request");
            }
            let calling_uid = u32::from_be_bytes(request[1..5].try_into().unwrap());
            let key_id: [u8; 32] = request[5..37].try_into().unwrap();
            let touched = attest_key_store::touch_attest_key(calling_uid, &key_id);
            request.zeroize();
            Ok(vec![if touched { 1 } else { 0 }])
        }
        OP_ATTEST_KEY_REMOVE => {
            if request.len() != ATTEST_KEY_REMOVE_REQUEST_BYTES
                || request[0] != certificate_wire::REWRITE_WIRE_VERSION
            {
                request.zeroize();
                return Err("invalid attest key remove request");
            }
            let calling_uid = u32::from_be_bytes(request[1..5].try_into().unwrap());
            let key_id: [u8; 32] = request[5..37].try_into().unwrap();
            let removed = attest_key_store::remove_attest_key(calling_uid, &key_id);
            request.zeroize();
            Ok(vec![if removed { 1 } else { 0 }])
        }
        OP_ATTEST_KEY_ALIAS => {
            if request.len() != ATTEST_KEY_ALIAS_REQUEST_BYTES
                || request[0] != certificate_wire::REWRITE_WIRE_VERSION
            {
                request.zeroize();
                return Err("invalid attest key alias request");
            }
            let calling_uid = u32::from_be_bytes(request[1..5].try_into().unwrap());
            let primary_key_id: [u8; 32] = request[5..37].try_into().unwrap();
            let alias_key_id: [u8; 32] = request[37..69].try_into().unwrap();
            let aliased =
                attest_key_store::alias_attest_key(calling_uid, &primary_key_id, alias_key_id);
            request.zeroize();
            Ok(vec![if aliased { 1 } else { 0 }])
        }
        OP_CRL_CHECK_BATCH => crl_wire::handle(request),
        backend_instance::OP_BACKEND_PING => backend_instance::handle(request),
        _ => {
            request.zeroize();
            Err("unsupported backend operation")
        }
    }
}

fn open_cbox(mut request: Vec<u8>) -> Result<Vec<u8>, &'static str> {
    let parsed = match parse_cbox_prefix(&request) {
        Ok(parsed) => parsed,
        Err(error) => {
            request.zeroize();
            return Err(error);
        }
    };
    let mut password = parsed.password.to_owned();
    let public_key = parsed.public_key.to_owned();
    let data_offset = parsed.data_offset;
    request.copy_within(data_offset.., 0);
    request.truncate(request.len() - data_offset);

    let decrypted = decrypt_cbox(request, &password);
    password.zeroize();
    let payload = match decrypted {
        Ok(payload) => payload,
        Err(_) => return Err("CBOX rejected"),
    };
    fuse_cbox_payload(payload, &public_key)
}

fn unlock_cbox(mut request: Vec<u8>) -> Result<Vec<u8>, &'static str> {
    let parsed = match parse_cbox_prefix(&request) {
        Ok(parsed) => parsed,
        Err(error) => {
            request.zeroize();
            return Err(error);
        }
    };
    let public_key = parsed.public_key.to_owned();
    let data_offset = parsed.data_offset;
    let recovery_key = match derive_recovery_key(&request[data_offset..], parsed.password) {
        Ok(key) => key,
        Err(_) => {
            request.zeroize();
            return Err("CBOX rejected");
        }
    };
    request.copy_within(data_offset.., 0);
    request.truncate(request.len() - data_offset);
    let payload = match decrypt_cbox_with_recovery_key(request, recovery_key.as_slice()) {
        Ok(payload) => payload,
        Err(_) => return Err("CBOX rejected"),
    };
    let mut metadata = fuse_cbox_payload(payload, &public_key)?;
    let mut response = Vec::new();
    response
        .try_reserve_exact(RECOVERY_KEY_BYTES + metadata.len())
        .map_err(|_| "CBOX response allocation failed")?;
    response.extend_from_slice(recovery_key.as_slice());
    response.append(&mut metadata);
    Ok(response)
}

fn recover_cbox(mut request: Vec<u8>) -> Result<Vec<u8>, &'static str> {
    let parsed = match parse_cbox_recovery_prefix(&request) {
        Ok(parsed) => parsed,
        Err(error) => {
            request.zeroize();
            return Err(error);
        }
    };
    let public_key = parsed.public_key.to_owned();
    let recovery_key = Zeroizing::new(request[parsed.key_start..parsed.data_offset].to_vec());
    let data_offset = parsed.data_offset;
    request.copy_within(data_offset.., 0);
    request.truncate(request.len() - data_offset);
    let payload = decrypt_cbox_with_recovery_key(request, recovery_key.as_slice())
        .map_err(|_| "CBOX rejected")?;
    fuse_cbox_payload(payload, &public_key)
}

fn fuse_cbox_payload(mut payload: CboxPayload, public_key: &str) -> Result<Vec<u8>, &'static str> {
    if !cbox_signature_policy_accepts(&payload, public_key) {
        payload.author.zeroize();
        payload.xml_content.zeroize();
        payload.signature_base64.zeroize();
        return Err("CBOX rejected");
    }
    let signature_present = !payload.signature_base64.is_empty();
    payload.signature_base64.zeroize();
    let mut author = payload.author;
    let xml = payload.xml_content.into_bytes();
    let mut keybox_wire = match keybox_wire::parse_and_encode(xml) {
        Ok(wire) => wire,
        Err(error) => {
            author.zeroize();
            return Err(error);
        }
    };
    let response = encode_cbox_response(author.as_bytes(), signature_present, &keybox_wire);
    author.zeroize();
    keybox_wire.zeroize();
    response
}

fn cbox_signature_policy_accepts(payload: &CboxPayload, public_key: &str) -> bool {
    if public_key.is_empty() {
        true
    } else {
        !payload.signature_base64.is_empty() && verify_cbox_signature(payload, public_key)
    }
}

fn transform_backup(mut request: Vec<u8>, encrypt: bool) -> Result<Vec<u8>, &'static str> {
    let parsed = match parse_backup_prefix(&request) {
        Ok(parsed) => parsed,
        Err(error) => {
            request.zeroize();
            return Err(error);
        }
    };
    let mut password = parsed.password.to_owned();
    let data_offset = parsed.data_offset;
    request.copy_within(data_offset.., 0);
    request.truncate(request.len() - data_offset);

    let result = if encrypt {
        encrypt_backup_owned(request, &password)
    } else {
        decrypt_backup(request, &password)
    };
    password.zeroize();
    result.map_err(|_| "backup rejected")
}

struct ParsedCboxPrefix<'a> {
    password: &'a str,
    public_key: &'a str,
    data_offset: usize,
}

fn parse_cbox_prefix(request: &[u8]) -> Result<ParsedCboxPrefix<'_>, &'static str> {
    let password_len = read_u16(request, 0)?;
    if password_len > MAX_PASSWORD_BYTES {
        return Err("invalid CBOX password field");
    }
    let password_start = 2usize;
    let password_end = password_start
        .checked_add(password_len)
        .ok_or("invalid CBOX password field")?;
    let public_key_len = read_u16(request, password_end)?;
    if public_key_len > MAX_PUBLIC_KEY_BYTES {
        return Err("invalid CBOX public key field");
    }
    let public_key_start = password_end
        .checked_add(2)
        .ok_or("invalid CBOX public key field")?;
    let data_offset = public_key_start
        .checked_add(public_key_len)
        .ok_or("invalid CBOX public key field")?;
    let password = std::str::from_utf8(
        request
            .get(password_start..password_end)
            .ok_or("truncated CBOX password field")?,
    )
    .map_err(|_| "invalid CBOX password encoding")?;
    let public_key = std::str::from_utf8(
        request
            .get(public_key_start..data_offset)
            .ok_or("truncated CBOX public key field")?,
    )
    .map_err(|_| "invalid CBOX public key encoding")?;
    let cbox_len = request.len().saturating_sub(data_offset);
    if cbox_len == 0 || cbox_len > MAX_CBOX_BYTES {
        return Err("invalid CBOX payload size");
    }
    Ok(ParsedCboxPrefix {
        password,
        public_key,
        data_offset,
    })
}

struct ParsedCboxRecoveryPrefix<'a> {
    public_key: &'a str,
    key_start: usize,
    data_offset: usize,
}

fn parse_cbox_recovery_prefix(
    request: &[u8],
) -> Result<ParsedCboxRecoveryPrefix<'_>, &'static str> {
    let public_key_len = read_u16(request, 0)?;
    if public_key_len > MAX_PUBLIC_KEY_BYTES {
        return Err("invalid CBOX public key field");
    }
    let public_key_start = 2usize;
    let key_start = public_key_start
        .checked_add(public_key_len)
        .ok_or("invalid CBOX public key field")?;
    let data_offset = key_start
        .checked_add(RECOVERY_KEY_BYTES)
        .ok_or("invalid CBOX recovery field")?;
    let public_key = std::str::from_utf8(
        request
            .get(public_key_start..key_start)
            .ok_or("truncated CBOX public key field")?,
    )
    .map_err(|_| "invalid CBOX public key encoding")?;
    let key = request
        .get(key_start..data_offset)
        .ok_or("truncated CBOX recovery field")?;
    if key.iter().all(|byte| *byte == 0) {
        return Err("invalid CBOX recovery field");
    }
    let cbox_len = request.len().saturating_sub(data_offset);
    if cbox_len == 0 || cbox_len > MAX_CBOX_BYTES {
        return Err("invalid CBOX payload size");
    }
    Ok(ParsedCboxRecoveryPrefix {
        public_key,
        key_start,
        data_offset,
    })
}

struct ParsedBackupPrefix<'a> {
    password: &'a str,
    data_offset: usize,
}

fn parse_backup_prefix(request: &[u8]) -> Result<ParsedBackupPrefix<'_>, &'static str> {
    let password_len = read_u16(request, 0)?;
    if password_len > MAX_PASSWORD_BYTES {
        return Err("invalid backup password field");
    }
    let data_offset = 2usize
        .checked_add(password_len)
        .ok_or("invalid backup password field")?;
    let password = std::str::from_utf8(
        request
            .get(2..data_offset)
            .ok_or("truncated backup password field")?,
    )
    .map_err(|_| "invalid backup password encoding")?;
    let data_len = request.len().saturating_sub(data_offset);
    if data_len > MAX_BACKUP_BYTES + 64 {
        return Err("invalid backup payload size");
    }
    Ok(ParsedBackupPrefix {
        password,
        data_offset,
    })
}

fn encode_cbox_response(
    author: &[u8],
    signature_present: bool,
    keybox_wire: &[u8],
) -> Result<Vec<u8>, &'static str> {
    if author.len() > MAX_CBOX_AUTHOR_BYTES || keybox_wire.is_empty() {
        return Err("CBOX response fields exceed wire bound");
    }
    let author_len = u16::try_from(author.len()).map_err(|_| "CBOX author exceeds wire bound")?;
    let keybox_len =
        u32::try_from(keybox_wire.len()).map_err(|_| "CBOX keybox metadata exceeds wire bound")?;
    let total = CBOX_RESPONSE_PREFIX_BYTES
        .checked_add(author.len())
        .and_then(|value| value.checked_add(keybox_wire.len()))
        .ok_or("CBOX response size overflow")?;
    if total > MAX_CBOX_RESPONSE_BYTES {
        return Err("CBOX response exceeds wire bound");
    }
    let mut output = Vec::with_capacity(total);
    output.extend_from_slice(&author_len.to_be_bytes());
    output.extend_from_slice(&keybox_len.to_be_bytes());
    output.push(u8::from(signature_present));
    output.extend_from_slice(author);
    output.extend_from_slice(keybox_wire);
    Ok(output)
}

fn read_u16(input: &[u8], offset: usize) -> Result<usize, &'static str> {
    let end = offset.checked_add(2).ok_or("wire length overflow")?;
    let bytes: [u8; 2] = input
        .get(offset..end)
        .ok_or("truncated wire length")?
        .try_into()
        .map_err(|_| "truncated wire length")?;
    Ok(u16::from_be_bytes(bytes) as usize)
}

fn reply_error(stream: &mut UnixStream, opcode: u16, message: &str) -> io::Result<()> {
    let status_byte = if message.contains("attest issuer not found in managed store") {
        BACKEND_STATUS_UNKNOWN_KEY_ID
    } else {
        BACKEND_STATUS_REJECTED
    };
    let status = [status_byte];
    write_frame_bounded(stream, opcode.max(1), FLAG_ERROR, &status, status.len())?;
    stream.flush()
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine as _;

    fn test_password() -> String {
        std::fs::read_to_string(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../testdata/crypto-golden-password.txt"
        ))
        .expect("crypto golden password fixture")
    }

    const CTSB_V2: &str = "Q1RTQgAAAAIAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobQrPBYdDdFyqlYeaU/mul01QMGsRn7g0MjLdOskpN97GWZ5fNXsQE5H+FldOlDg4HvENUIQC5rexM7K0B5tNer0Cjko6vCq2Z";
    const CBOX_V2: &str = "Q0JPWAAAAAIAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobQrPWcdbGETfpef7mviy130oMGrIv/EwTlOVOuFIH5qfAaY+XUMc2qXWTgNu7FkkT/w9lEwrpv/iFQNyu/EsamoACXPaOVKKg+oGNsVLwNRNN4Gth46JQOziUU1/B3Fen+4BvKg9VtB9H4xnPi4AX+qMZHYhaW8ysgOQaSFcJy59C9IckzAalbsWXcjdsX8r1kr/KBOEALbqYmlPfNbKQEZdEZacWRvO3";
    // Deterministic CBOX v2 containing the shared valid EC keybox fixture.
    const BACKEND_VALID_CBOX_V2: &str = "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";
    const VALID_EC: &[u8] =
        include_bytes!("../../../service/src/test/resources/keybox/valid_ec.xml");

    fn encode_backup_request(password: &str, body: &[u8]) -> Vec<u8> {
        let password = password.as_bytes();
        let mut output = Vec::with_capacity(2 + password.len() + body.len());
        output.extend_from_slice(&(password.len() as u16).to_be_bytes());
        output.extend_from_slice(password);
        output.extend_from_slice(body);
        output
    }

    fn encode_cbox_request(password: &str, public_key: &str, body: &[u8]) -> Vec<u8> {
        let password = password.as_bytes();
        let public_key = public_key.as_bytes();
        let mut output = Vec::with_capacity(4 + password.len() + public_key.len() + body.len());
        output.extend_from_slice(&(password.len() as u16).to_be_bytes());
        output.extend_from_slice(password);
        output.extend_from_slice(&(public_key.len() as u16).to_be_bytes());
        output.extend_from_slice(public_key);
        output.extend_from_slice(body);
        output
    }

    fn encode_cbox_recovery_request(public_key: &str, key: &[u8], body: &[u8]) -> Vec<u8> {
        let public_key = public_key.as_bytes();
        let mut output = Vec::with_capacity(2 + public_key.len() + key.len() + body.len());
        output.extend_from_slice(&(public_key.len() as u16).to_be_bytes());
        output.extend_from_slice(public_key);
        output.extend_from_slice(key);
        output.extend_from_slice(body);
        output
    }

    fn decode(value: &str) -> Vec<u8> {
        base64::engine::general_purpose::STANDARD
            .decode(value)
            .unwrap()
    }

    #[test]
    fn backup_decrypt_operation_matches_golden() {
        let request = encode_backup_request(test_password().as_str(), &decode(CTSB_V2));
        let plaintext = handle_request(OP_CRYPTO_BACKUP_DECRYPT, request).unwrap();
        assert_eq!(
            plaintext,
            b"{\"version\":1,\"files\":{\"target.txt\":\"com.example.app\\n\"}}"
        );
    }

    #[test]
    fn backup_encrypt_operation_round_trips_without_json_or_base64_wire_format() {
        let plaintext = b"bounded backup payload";
        let request = encode_backup_request(test_password().as_str(), plaintext);
        let encrypted = handle_request(OP_CRYPTO_BACKUP_ENCRYPT, request).unwrap();
        let decrypted = handle_request(
            OP_CRYPTO_BACKUP_DECRYPT,
            encode_backup_request(test_password().as_str(), &encrypted),
        )
        .unwrap();
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn backup_encrypt_accepts_empty_password_and_plaintext() {
        let empty_password = String::new();
        let encrypted = handle_request(
            OP_CRYPTO_BACKUP_ENCRYPT,
            encode_backup_request(&empty_password, b""),
        )
        .unwrap();
        let decrypted = handle_request(
            OP_CRYPTO_BACKUP_DECRYPT,
            encode_backup_request(&empty_password, &encrypted),
        )
        .unwrap();
        assert!(decrypted.is_empty());
    }

    #[test]
    fn cbox_prefix_accepts_empty_password_like_managed_oracle() {
        let empty = String::new();
        let request = encode_cbox_request(&empty, &empty, b"body");
        let parsed = parse_cbox_prefix(&request).unwrap();
        assert_eq!(parsed.password, "");
        assert_eq!(parsed.public_key, "");
        assert_eq!(&request[parsed.data_offset..], b"body");
    }

    #[test]
    fn cbox_unlock_and_recovery_paths_return_identical_public_metadata() {
        let _sequence = keybox_wire::isolate_store_sequence();
        keybox_wire::key_store::reset_for_testing();
        let encrypted = decode(BACKEND_VALID_CBOX_V2);
        let unlock = handle_request(
            OP_CBOX_UNLOCK,
            encode_cbox_request(test_password().as_str(), "", &encrypted),
        )
        .unwrap();
        assert!(unlock.len() > RECOVERY_KEY_BYTES);
        let key = &unlock[..RECOVERY_KEY_BYTES];
        assert!(key.iter().any(|byte| *byte != 0));
        let recovered = handle_request(
            OP_CBOX_RECOVER,
            encode_cbox_recovery_request("", key, &encrypted),
        )
        .unwrap();
        assert_eq!(&unlock[RECOVERY_KEY_BYTES..], recovered.as_slice());
    }

    #[test]
    fn cbox_recovery_rejects_wrong_key() {
        let encrypted = decode(CBOX_V2);
        let key = [0x55u8; RECOVERY_KEY_BYTES];
        assert!(handle_request(
            OP_CBOX_RECOVER,
            encode_cbox_recovery_request("", &key, &encrypted),
        )
        .is_err());
    }

    #[test]
    fn fused_cbox_payload_returns_only_public_keybox_metadata() {
        let _sequence = keybox_wire::isolate_store_sequence();
        keybox_wire::key_store::reset_for_testing();
        let payload = CboxPayload {
            author: "fixture author".to_string(),
            xml_content: String::from_utf8(VALID_EC.to_vec()).unwrap(),
            signature_base64: String::new(),
            signature_version: 2,
        };
        let response = fuse_cbox_payload(payload, "").unwrap();
        let author_len = u16::from_be_bytes(response[0..2].try_into().unwrap()) as usize;
        let wire_len = u32::from_be_bytes(response[2..6].try_into().unwrap()) as usize;
        assert_eq!(response[6], 0);
        assert_eq!(&response[7..7 + author_len], b"fixture author");
        let wire = &response[7 + author_len..];
        assert_eq!(wire.len(), wire_len);
        assert_eq!(wire.first().copied(), Some(keybox_wire::WIRE_VERSION));
        assert!(!wire.windows(16).any(|window| window == b"PRIVATE KEY-----"));
        assert!(!wire
            .windows(19)
            .any(|window| window == b"AndroidAttestation"));
    }

    #[test]
    fn cbox_response_accepts_multibyte_author_within_producer_contract() {
        let author = "é".repeat(MAX_CBOX_AUTHOR_UTF16_UNITS);
        assert_eq!(author.encode_utf16().count(), MAX_CBOX_AUTHOR_UTF16_UNITS);
        assert!(author.len() > 1024);

        let response = encode_cbox_response(author.as_bytes(), false, b"wire").unwrap();
        let author_len = u16::from_be_bytes(response[0..2].try_into().unwrap()) as usize;

        assert_eq!(author_len, author.len());
        assert_eq!(&response[7..7 + author_len], author.as_bytes());
    }

    #[test]
    fn cbox_response_rejects_author_over_byte_bound() {
        let author = vec![b'a'; MAX_CBOX_AUTHOR_BYTES + 1];

        assert!(encode_cbox_response(&author, false, b"wire").is_err());
    }

    #[test]
    fn cbox_signature_presence_policy_matches_managed_server_behavior() {
        let unsigned = CboxPayload {
            author: "author".to_string(),
            xml_content: "<xml/>".to_string(),
            signature_base64: String::new(),
            signature_version: 2,
        };
        assert!(cbox_signature_policy_accepts(&unsigned, ""));
        assert!(!cbox_signature_policy_accepts(&unsigned, "not-a-key"));

        let signed = CboxPayload {
            signature_base64: "AA==".to_string(),
            ..unsigned
        };
        assert!(cbox_signature_policy_accepts(&signed, ""));
        assert!(!cbox_signature_policy_accepts(&signed, "not-a-key"));
    }

    #[test]
    fn keybox_parse_operation_uses_legacy_loader_bound_and_public_wire() {
        let _sequence = keybox_wire::isolate_store_sequence();
        keybox_wire::key_store::reset_for_testing();
        assert_eq!(
            opcode_request_limit(OP_KEYBOX_PARSE),
            Some(10 * 1024 * 1024)
        );
        assert_eq!(
            opcode_response_limit(OP_KEYBOX_PARSE),
            Some(MAX_KEYBOX_RESPONSE_BYTES)
        );
        let response = handle_request(OP_KEYBOX_PARSE, VALID_EC.to_vec()).unwrap();
        assert_eq!(response.first().copied(), Some(keybox_wire::WIRE_VERSION));
        assert!(!response
            .windows(16)
            .any(|window| window == b"PRIVATE KEY-----"));
        assert!(response.len() <= MAX_KEYBOX_RESPONSE_BYTES);
    }

    #[test]
    fn certificate_operations_use_dedicated_bounds() {
        assert_eq!(
            opcode_request_limit(OP_CERTIFICATE_INSPECT),
            Some(certificate_wire::MAX_INSPECT_REQUEST_BYTES)
        );
        assert_eq!(
            opcode_response_limit(OP_CERTIFICATE_INSPECT),
            Some(certificate_wire::INSPECT_RESPONSE_BYTES)
        );
        assert_eq!(
            opcode_request_limit(OP_CERTIFICATE_REWRITE),
            Some(certificate_wire::MAX_REWRITE_REQUEST_BYTES)
        );
        assert_eq!(
            opcode_response_limit(OP_CERTIFICATE_REWRITE),
            Some(certificate_wire::MAX_REWRITE_RESPONSE_BYTES)
        );
        assert_eq!(
            opcode_request_limit(OP_ATTEST_KEY_REWRITE),
            Some(certificate_wire::MAX_ATTEST_KEY_REWRITE_REQUEST_BYTES)
        );
        assert_eq!(
            opcode_response_limit(OP_ATTEST_KEY_REWRITE),
            Some(certificate_wire::MAX_REWRITE_RESPONSE_BYTES)
        );
        assert_eq!(
            opcode_request_limit(OP_CHILD_KEY_REWRITE),
            Some(certificate_wire::MAX_CHILD_KEY_REWRITE_REQUEST_BYTES)
        );
        assert_eq!(
            opcode_response_limit(OP_CHILD_KEY_REWRITE),
            Some(certificate_wire::MAX_REWRITE_RESPONSE_BYTES)
        );
        assert_eq!(
            opcode_request_limit(OP_ATTEST_KEY_CLEAR),
            Some(ATTEST_KEY_CLEAR_REQUEST_BYTES)
        );
        assert_eq!(
            opcode_response_limit(OP_ATTEST_KEY_CLEAR),
            Some(ATTEST_KEY_CLEAR_RESPONSE_BYTES)
        );
        assert_eq!(
            opcode_request_limit(OP_ATTEST_KEY_TOUCH),
            Some(ATTEST_KEY_TOUCH_REQUEST_BYTES)
        );
        assert_eq!(
            opcode_response_limit(OP_ATTEST_KEY_TOUCH),
            Some(ATTEST_KEY_TOUCH_RESPONSE_BYTES)
        );
        assert!(handle_request(OP_CERTIFICATE_INSPECT, vec![1]).is_err());
        assert!(handle_request(OP_CERTIFICATE_REWRITE, vec![1]).is_err());
        assert!(handle_request(OP_ATTEST_KEY_REWRITE, vec![1]).is_err());
        assert!(handle_request(OP_CHILD_KEY_REWRITE, vec![1]).is_err());
        assert!(handle_request(OP_ATTEST_KEY_CLEAR, vec![0]).is_err());
        assert!(handle_request(OP_ATTEST_KEY_TOUCH, vec![1]).is_err());
    }

    #[test]
    fn crl_operation_uses_stateful_bounded_wire() {
        assert_eq!(
            opcode_request_limit(OP_CRL_CHECK_BATCH),
            Some(crl_wire::MAX_REQUEST_BYTES)
        );
        assert_eq!(
            opcode_response_limit(OP_CRL_CHECK_BATCH),
            Some(crl_wire::MAX_RESPONSE_BYTES)
        );
        assert!(handle_request(OP_CRL_CHECK_BATCH, vec![1]).is_err());
    }

    #[test]
    fn backend_ping_uses_fixed_request_and_response_bounds() {
        let auth = std::array::from_fn(|index| (index as u8).wrapping_add(1));
        backend_instance::initialize_for_test(auth).unwrap();
        assert_eq!(
            opcode_request_limit(backend_instance::OP_BACKEND_PING),
            Some(backend_instance::REQUEST_BYTES)
        );
        assert_eq!(
            opcode_response_limit(backend_instance::OP_BACKEND_PING),
            Some(backend_instance::RESPONSE_BYTES)
        );
        let mut request = Vec::with_capacity(backend_instance::REQUEST_BYTES);
        request.push(backend_instance::HANDSHAKE_VERSION);
        request.extend_from_slice(&auth);
        let response = handle_request(backend_instance::OP_BACKEND_PING, request).unwrap();
        assert_eq!(response.len(), backend_instance::RESPONSE_BYTES);
    }

    #[test]
    fn cbox_recovery_operations_use_dedicated_bounds() {
        assert_eq!(
            opcode_request_limit(OP_CBOX_UNLOCK),
            Some(MAX_CBOX_REQUEST_BYTES)
        );
        assert_eq!(
            opcode_response_limit(OP_CBOX_UNLOCK),
            Some(MAX_CBOX_UNLOCK_RESPONSE_BYTES)
        );
        assert_eq!(
            opcode_request_limit(OP_CBOX_RECOVER),
            Some(MAX_CBOX_RECOVERY_REQUEST_BYTES)
        );
        assert_eq!(
            opcode_response_limit(OP_CBOX_RECOVER),
            Some(MAX_CBOX_RESPONSE_BYTES)
        );
    }

    #[test]
    fn keybox_file_request_policy_is_scope_bounded_and_component_only() {
        let mut legacy = vec![SCOPE_CONFIG_ROOT];
        legacy.extend_from_slice(b"keybox.xml");
        assert!(keybox_file_request_is_valid(&legacy));

        let mut directory = vec![SCOPE_KEYBOX_DIRECTORY];
        directory.extend_from_slice("A B.XML".as_bytes());
        assert!(keybox_file_request_is_valid(&directory));

        for name in ["../evil.xml", "sub/evil.xml", "not-xml.txt"] {
            let mut request = vec![SCOPE_KEYBOX_DIRECTORY];
            request.extend_from_slice(name.as_bytes());
            assert!(!keybox_file_request_is_valid(&request));
        }
        let mut wrong_root = vec![SCOPE_CONFIG_ROOT];
        wrong_root.extend_from_slice(b"other.xml");
        assert!(!keybox_file_request_is_valid(&wrong_root));
        assert!(!keybox_file_request_is_valid(&[9, b'x']));
    }

    #[test]
    fn malformed_truncated_and_oversized_prefixes_fail_closed() {
        assert!(handle_request(OP_CRYPTO_CBOX_OPEN, vec![0]).is_err());
        assert!(handle_request(OP_CBOX_UNLOCK, vec![0]).is_err());
        assert!(handle_request(OP_CBOX_RECOVER, vec![0]).is_err());
        assert!(handle_request(OP_CRYPTO_BACKUP_DECRYPT, vec![0, 3, b'a']).is_err());

        let mut oversized = vec![0, 1, b'p'];
        oversized.resize(3 + MAX_BACKUP_BYTES + 65, 0);
        assert!(handle_request(OP_CRYPTO_BACKUP_DECRYPT, oversized).is_err());
    }

    #[test]
    fn unknown_operations_are_rejected() {
        assert!(handle_request(0xffff, vec![1]).is_err());
    }
}
