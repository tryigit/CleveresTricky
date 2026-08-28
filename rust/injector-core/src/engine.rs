use crate::abi::{
    close, getpid, nice, sendmsg, socket, AndroidDlExtInfo, InjectorSymbols, IoVector,
    MessageHeader, SockAddrUn,
};
use crate::logging;
use crate::ptrace_session::RemoteSession;
use crate::symbol_resolver::resolve_injector_symbols;
use cleverestricky_native_core::injector_support::{
    extract_scm_rights_fd, generate_magic, is_safe_library_metadata, parse_injector_request,
    wipe_bytes, CmsgHeader,
};
use std::ffi::{c_int, c_void, CStr, CString, OsString};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::mem;
use std::os::fd::AsRawFd;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::MetadataExt;
use std::path::PathBuf;

const LOG_DEBUG: c_int = 3;
const LOG_INFO: c_int = 4;
const LOG_WARN: c_int = 5;
const LOG_ERROR: c_int = 6;

const AF_UNIX: c_int = 1;
const SOCK_DGRAM: c_int = 2;
const SOCK_NONBLOCK: c_int = 0x800;
const SOCK_CLOEXEC: c_int = 0x80000;
const SOL_SOCKET: c_int = 1;
const SCM_RIGHTS: c_int = 1;
const MSG_CTRUNC: c_int = 0x8;

const PROT_READ: usize = 1;
const PROT_WRITE: usize = 2;
const MAP_PRIVATE: usize = 2;
const MAP_ANONYMOUS: usize = 0x20;
const RTLD_NOW: usize = 2;
const ANDROID_DLEXT_USE_LIBRARY_FD: u64 = 0x10;
const CONTROL_BUFFER_SIZE: usize = 4_096;
const MAXIMUM_REMOTE_ERROR_BYTES: usize = 1_024;
const MAXIMUM_SELINUX_CONTEXT_BYTES: usize = 255;

type EngineResult<T> = Result<T, String>;

pub(crate) struct EngineOutcome {
    pub(crate) code: i32,
    pub(crate) failure: &'static str,
}

fn log(priority: c_int, message: impl AsRef<str>) {
    logging::write(priority, message);
}

struct OwnedFd(c_int);

impl OwnedFd {
    fn new(descriptor: c_int) -> EngineResult<Self> {
        if descriptor < 0 {
            Err("local descriptor creation failed".into())
        } else {
            Ok(Self(descriptor))
        }
    }

    fn raw(&self) -> c_int {
        self.0
    }
}

impl Drop for OwnedFd {
    fn drop(&mut self) {
        if self.0 >= 0 {
            unsafe { close(self.0) };
            self.0 = -1;
        }
    }
}

struct TransferResources {
    remote_socket: Option<i32>,
    remote_control: Option<usize>,
}

impl TransferResources {
    fn cleanup(&mut self, session: &mut RemoteSession, symbols: &InjectorSymbols) {
        if let Some(address) = self.remote_control.take() {
            if session
                .call(
                    symbols.munmap,
                    symbols.libc_return,
                    &[address, CONTROL_BUFFER_SIZE],
                )
                .ok()
                != Some(0)
            {
                log(LOG_WARN, "could not release the remote control buffer");
            }
        }
        if let Some(descriptor) = self.remote_socket.take() {
            close_remote_fd(session, symbols, descriptor);
        }
    }
}

pub fn run(arguments: &[OsString]) -> EngineOutcome {
    match parse_and_run(arguments) {
        Ok(()) => {
            log(LOG_INFO, "injection process completed successfully");
            EngineOutcome {
                code: 0,
                failure: "none",
            }
        }
        Err(error) => {
            log(LOG_ERROR, format!("injection process failed: {error}"));
            EngineOutcome {
                code: 1,
                failure: classify_failure(&error),
            }
        }
    }
}

fn classify_failure(error: &str) -> &'static str {
    if error.starts_with("attach stage:") {
        "target_attach"
    } else if error.starts_with("symbol stage:") {
        "symbol_resolution"
    } else if error.starts_with("descriptor stage:") {
        "descriptor_transfer"
    } else if error.starts_with("loader stage:") {
        "library_load"
    } else if error.starts_with("entry stage:") {
        "entry_activation"
    } else if error.starts_with("detach stage:") {
        "target_detach"
    } else if error.starts_with("expected a process")
        || error.starts_with("invalid injector")
        || error.starts_with("invalid library")
        || error.starts_with("could not resolve the library")
        || error.starts_with("resolved library")
        || error.starts_with("entry name")
        || error.starts_with("could not open the injection library")
        || error.starts_with("could not inspect the injection library")
        || error.starts_with("refusing an unsafe injection library")
    {
        "request_validation"
    } else {
        "unknown"
    }
}

fn parse_and_run(arguments: &[OsString]) -> EngineResult<()> {
    if !(4..=5).contains(&arguments.len()) {
        return Err(
            "expected a process, library path, entry name, and optional activation context".into(),
        );
    }
    let pid_value = arguments[1].as_os_str().as_bytes();
    let path_value = arguments[2].as_os_str().as_bytes();
    let entry_value = arguments[3].as_os_str().as_bytes();
    let activation_value = arguments.get(4).map(|value| value.as_os_str().as_bytes());
    if let Some(value) = activation_value {
        if value.len() > 256
            || value.contains(&0)
            || value.iter().any(|byte| *byte < 0x20 || *byte > 0x7e)
        {
            return Err("invalid activation context".into());
        }
    }
    let activation_c_string = activation_value
        .map(CString::new)
        .transpose()
        .map_err(|_| "activation context contains a null byte".to_string())?;
    let current_pid = unsafe { getpid() };
    let pid = parse_injector_request(pid_value, current_pid, entry_value)
        .ok_or_else(|| "invalid injector arguments".to_string())?;

    if path_value.is_empty() || path_value.len() > 4_095 {
        return Err("invalid library path length".into());
    }
    let requested_path = PathBuf::from(std::ffi::OsStr::from_bytes(path_value));
    let canonical_path = fs::canonicalize(&requested_path)
        .map_err(|error| format!("could not resolve the library path: {error}"))?;
    let canonical_bytes = canonical_path.as_os_str().as_bytes();
    if canonical_bytes.is_empty() || canonical_bytes.len() > 4_095 || canonical_bytes.contains(&0) {
        return Err("resolved library path is invalid".into());
    }
    let canonical_c_string = CString::new(canonical_bytes)
        .map_err(|_| "resolved library path contains a null byte".to_string())?;
    let entry_c_string =
        CString::new(entry_value).map_err(|_| "entry name contains a null byte".to_string())?;

    let library = File::open(&canonical_path)
        .map_err(|error| format!("could not open the injection library: {error}"))?;
    let metadata = library
        .metadata()
        .map_err(|error| format!("could not inspect the injection library: {error}"))?;
    if !is_safe_library_metadata(metadata.mode(), metadata.uid()) {
        return Err("refusing an unsafe injection library".into());
    }

    unsafe {
        let _ = nice(-20);
    }
    inject_library(
        pid,
        &library,
        &canonical_c_string,
        &entry_c_string,
        activation_c_string.as_deref(),
    )
}

fn inject_library(
    pid: i32,
    library: &File,
    library_path: &CStr,
    entry_name: &CStr,
    activation_context: Option<&CStr>,
) -> EngineResult<()> {
    log(
        LOG_INFO,
        format!("starting validated injection for process {pid}"),
    );
    let mut session =
        RemoteSession::attach(pid).map_err(|error| format!("attach stage: {error}"))?;
    let symbols =
        resolve_injector_symbols(pid).map_err(|error| format!("symbol stage: {error}"))?;

    let local_socket = create_local_socket_for_target(pid)
        .map_err(|error| format!("descriptor stage: {error}"))?;
    let mut socket_name = generate_magic(16)
        .map_err(|error| format!("could not generate the local socket name: {error}"))?;
    let remote_library_fd_result = transfer_library_fd(
        &mut session,
        &symbols,
        &local_socket,
        library.as_raw_fd(),
        &socket_name,
    );
    wipe_bytes(&mut socket_name);
    let remote_library_fd =
        remote_library_fd_result.map_err(|error| format!("descriptor stage: {error}"))?;

    let remote_handle_result =
        open_remote_library(&mut session, &symbols, remote_library_fd, library_path);
    close_remote_fd(&mut session, &symbols, remote_library_fd);
    let remote_handle = remote_handle_result.map_err(|error| format!("loader stage: {error}"))?;

    let activation_result: EngineResult<()> = (|| {
        let remote_entry_name = session.push_c_string(entry_name)?;
        let remote_entry = session.call(
            symbols.dlsym,
            symbols.libc_return,
            &[remote_handle, remote_entry_name],
        )?;
        if remote_entry == 0 {
            return Err("the target library does not export the required entry".into());
        }

        let remote_activation_context = match activation_context {
            Some(context) => session.push_c_string(context)?,
            None => 0,
        };
        let entry_result = session.call(
            remote_entry,
            symbols.libc_return,
            &[remote_activation_context],
        )?;
        if entry_result != 1 {
            return Err("the remote entry rejected initialization".into());
        }
        Ok(())
    })();
    if activation_result.is_err()
        && session
            .call(symbols.dlclose, symbols.libc_return, &[remote_handle])
            .ok()
            != Some(0)
    {
        log(LOG_WARN, "could not release the rejected remote library");
    }
    activation_result.map_err(|error| format!("entry stage: {error}"))?;
    session
        .finish()
        .map_err(|error| format!("detach stage: {error}"))?;
    Ok(())
}

fn create_local_socket_for_target(pid: i32) -> EngineResult<OwnedFd> {
    let context_path = format!("/proc/{pid}/attr/current");
    if let Ok(file) = File::open(context_path) {
        if let Some(mut context) = read_context_bounded(file) {
            while matches!(context.last(), Some(b'\n' | 0)) {
                context.pop();
            }
            if !context.is_empty() {
                if let Err(error) = set_socket_creation_context(&context) {
                    log(
                        LOG_WARN,
                        format!("could not set the target socket context: {error}"),
                    );
                }
            }
            wipe_bytes(&mut context);
        }
    }
    let descriptor = unsafe { socket(AF_UNIX, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0) };
    if let Err(error) = set_socket_creation_context(&[]) {
        log(
            LOG_WARN,
            format!("could not reset the socket creation context: {error}"),
        );
    }
    OwnedFd::new(descriptor)
}

fn read_context_bounded(mut input: impl Read) -> Option<Vec<u8>> {
    let mut context = Vec::with_capacity(MAXIMUM_SELINUX_CONTEXT_BYTES);
    let result = input
        .by_ref()
        .take((MAXIMUM_SELINUX_CONTEXT_BYTES + 1) as u64)
        .read_to_end(&mut context);
    if result.is_ok() && context.len() <= MAXIMUM_SELINUX_CONTEXT_BYTES {
        Some(context)
    } else {
        wipe_bytes(&mut context);
        None
    }
}

fn set_socket_creation_context(context: &[u8]) -> std::io::Result<()> {
    if context.len() > 255 || context.contains(&0) {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            "invalid SELinux context",
        ));
    }
    let mut file = OpenOptions::new()
        .write(true)
        .open("/proc/thread-self/attr/sockcreate")?;
    file.write_all(context)?;
    file.write_all(&[0])
}

fn transfer_library_fd(
    session: &mut RemoteSession,
    symbols: &InjectorSymbols,
    local_socket: &OwnedFd,
    local_library_fd: i32,
    socket_name: &[u8],
) -> EngineResult<i32> {
    let mut resources = TransferResources {
        remote_socket: None,
        remote_control: None,
    };
    let result = (|| {
        let remote_socket_result = session.call(
            symbols.socket,
            symbols.libc_return,
            &[
                AF_UNIX as usize,
                (SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC) as usize,
                0,
            ],
        )?;
        if is_remote_integer_error(remote_socket_result) || remote_socket_result > i32::MAX as usize
        {
            return Err(format!(
                "remote socket creation failed with error {}",
                read_remote_errno(session, symbols).unwrap_or(-1)
            ));
        }
        let remote_socket = remote_socket_result as i32;
        resources.remote_socket = Some(remote_socket);

        if socket_name.is_empty() || socket_name.len() >= 108 {
            return Err("invalid abstract socket name".into());
        }
        let mut address = SockAddrUn {
            family: AF_UNIX as u16,
            ..SockAddrUn::default()
        };
        address.path[1..1 + socket_name.len()].copy_from_slice(socket_name);
        let address_length = mem::size_of::<u16>() + 1 + socket_name.len();
        let remote_address = session.push_value(&address)?;
        let bind_result = session.call(
            symbols.bind,
            symbols.libc_return,
            &[remote_socket as usize, remote_address, address_length],
        )?;
        if bind_result != 0 {
            return Err(format!(
                "remote socket bind failed with error {}",
                read_remote_errno(session, symbols).unwrap_or(-1)
            ));
        }

        let remote_control = session.call(
            symbols.mmap,
            symbols.libc_return,
            &[
                0,
                CONTROL_BUFFER_SIZE,
                PROT_READ | PROT_WRITE,
                MAP_PRIVATE | MAP_ANONYMOUS,
                usize::MAX,
                0,
            ],
        )?;
        if remote_control == 0 || remote_control == usize::MAX {
            return Err("remote control buffer allocation failed".into());
        }
        resources.remote_control = Some(remote_control);

        let remote_dummy = session.push_bytes(&[0])?;
        let remote_vector = IoVector {
            base: remote_dummy as *mut c_void,
            length: 1,
        };
        let remote_vector_address = session.push_value(&remote_vector)?;
        let remote_header = MessageHeader {
            vectors: remote_vector_address as *mut IoVector,
            vector_count: 1,
            control: remote_control as *mut c_void,
            control_length: CONTROL_BUFFER_SIZE,
            ..MessageHeader::default()
        };
        let remote_header_address = session.push_value(&remote_header)?;

        send_local_fd(
            local_socket.raw(),
            local_library_fd,
            &mut address,
            address_length as u32,
        )?;
        let received = session.call(
            symbols.recvmsg,
            symbols.libc_return,
            &[remote_socket as usize, remote_header_address, 0],
        )?;
        if received != 1 {
            return Err(format!(
                "remote descriptor receive failed with error {}",
                read_remote_errno(session, symbols).unwrap_or(-1)
            ));
        }

        let received_header: MessageHeader = session.read_value(remote_header_address)?;
        if received_header.flags & MSG_CTRUNC != 0
            || received_header.control != remote_control as *mut c_void
            || received_header.vectors != remote_vector_address as *mut IoVector
            || received_header.vector_count != 1
            || received_header.control_length < cmsg_data_offset() + mem::size_of::<i32>()
            || received_header.control_length > CONTROL_BUFFER_SIZE
        {
            return Err("remote ancillary message failed structural validation".into());
        }

        let mut control = vec![0u8; received_header.control_length];
        session.read_bytes(remote_control, &mut control)?;
        let descriptor = extract_scm_rights_fd(&control);
        wipe_bytes(&mut control);
        descriptor.ok_or_else(|| "remote ancillary message did not contain a descriptor".into())
    })();
    resources.cleanup(session, symbols);
    result
}

fn send_local_fd(
    socket_descriptor: i32,
    library_descriptor: i32,
    address: &mut SockAddrUn,
    address_length: u32,
) -> EngineResult<()> {
    let mut dummy = 0u8;
    let mut vector = IoVector {
        base: (&mut dummy as *mut u8).cast(),
        length: 1,
    };
    let data_offset = cmsg_data_offset();
    let control_length = cmsg_space(mem::size_of::<i32>());
    let mut control = vec![0u8; control_length];
    let header = CmsgHeader {
        length: data_offset + mem::size_of::<i32>(),
        level: SOL_SOCKET,
        kind: SCM_RIGHTS,
    };
    unsafe {
        control
            .as_mut_ptr()
            .cast::<CmsgHeader>()
            .write_unaligned(header);
        control
            .as_mut_ptr()
            .add(data_offset)
            .cast::<i32>()
            .write_unaligned(library_descriptor);
    }

    let header = MessageHeader {
        name: (address as *mut SockAddrUn).cast(),
        name_length: address_length,
        vectors: &mut vector,
        vector_count: 1,
        control: control.as_mut_ptr().cast(),
        control_length: control.len(),
        flags: 0,
    };
    loop {
        let sent = unsafe { sendmsg(socket_descriptor, &header, 0) };
        if sent == 1 {
            wipe_bytes(&mut control);
            return Ok(());
        }
        if sent < 0 && std::io::Error::last_os_error().kind() == std::io::ErrorKind::Interrupted {
            continue;
        }
        wipe_bytes(&mut control);
        return Err("could not send the library descriptor".into());
    }
}

fn open_remote_library(
    session: &mut RemoteSession,
    symbols: &InjectorSymbols,
    remote_library_fd: i32,
    library_path: &CStr,
) -> EngineResult<usize> {
    let extension = AndroidDlExtInfo {
        flags: ANDROID_DLEXT_USE_LIBRARY_FD,
        library_fd: remote_library_fd,
        ..AndroidDlExtInfo::default()
    };
    let remote_extension = session.push_value(&extension)?;
    let remote_path = session.push_c_string(library_path)?;
    let handle = session.call(
        symbols.android_dlopen_ext,
        symbols.libc_return,
        &[remote_path, RTLD_NOW, remote_extension],
    )?;
    if handle == 0 {
        log_remote_loader_error(session, symbols);
        Err("remote dynamic loader returned a null handle".into())
    } else {
        log(LOG_DEBUG, "remote library loaded");
        Ok(handle)
    }
}

fn log_remote_loader_error(session: &mut RemoteSession, symbols: &InjectorSymbols) {
    if symbols.dlerror == 0 || symbols.strlen == 0 {
        return;
    }
    let Ok(pointer) = session.call(symbols.dlerror, symbols.libc_return, &[]) else {
        return;
    };
    if pointer == 0 {
        return;
    }
    let Ok(length) = session.call(symbols.strlen, symbols.libc_return, &[pointer]) else {
        return;
    };
    if length == 0 || length >= MAXIMUM_REMOTE_ERROR_BYTES {
        return;
    }
    let mut message = vec![0u8; length];
    if session.read_bytes(pointer, &mut message).is_ok() {
        log(
            LOG_ERROR,
            format!("remote loader error: {}", String::from_utf8_lossy(&message)),
        );
    }
    wipe_bytes(&mut message);
}

fn read_remote_errno(session: &mut RemoteSession, symbols: &InjectorSymbols) -> Option<i32> {
    if symbols.errno_location == 0 {
        return None;
    }
    let pointer = session
        .call(symbols.errno_location, symbols.libc_return, &[])
        .ok()?;
    if pointer == 0 || pointer == usize::MAX {
        return None;
    }
    session.read_value(pointer).ok()
}

fn close_remote_fd(session: &mut RemoteSession, symbols: &InjectorSymbols, descriptor: i32) {
    if descriptor < 0 {
        return;
    }
    if session
        .call(symbols.close, symbols.libc_return, &[descriptor as usize])
        .ok()
        != Some(0)
    {
        log(
            LOG_WARN,
            format!("could not close remote descriptor {descriptor}"),
        );
    }
}

fn is_remote_integer_error(value: usize) -> bool {
    value == usize::MAX || value == u32::MAX as usize
}

const fn cmsg_align(length: usize) -> usize {
    let alignment = mem::size_of::<usize>();
    (length + alignment - 1) & !(alignment - 1)
}

const fn cmsg_data_offset() -> usize {
    cmsg_align(mem::size_of::<CmsgHeader>())
}

const fn cmsg_space(length: usize) -> usize {
    cmsg_data_offset() + cmsg_align(length)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn bounded_context_reader_preserves_valid_context() {
        let context = b"u:r:su:s0\n\0";
        assert_eq!(
            read_context_bounded(Cursor::new(context)),
            Some(context.to_vec())
        );
    }

    #[test]
    fn bounded_context_reader_rejects_oversized_context_without_unbounded_read() {
        let oversized = vec![b'x'; MAXIMUM_SELINUX_CONTEXT_BYTES + 1_024];
        assert!(read_context_bounded(Cursor::new(oversized)).is_none());
    }
}
