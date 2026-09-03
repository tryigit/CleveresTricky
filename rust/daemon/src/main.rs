// Additional GPLv3 section 7(b) attribution term for tryigit-owned material: see ../../NOTICE.
mod config_file_broker;
mod keybox_file_broker;

use cleverestricky_service_core::backend_auth::{BACKEND_AUTH_ENV, BACKEND_AUTH_HEX_BYTES};
use cleverestricky_service_core::ipc::{
    read_header, read_header_bounded, relay_exact, write_frame, write_header, FrameHeader,
    FLAG_ERROR, MAX_FRAME_BYTES, OP_ADAPTER_REGISTER, OP_FILE_WRITE, OP_INTEGRITY_DELETE_MODULE,
    OP_INTEGRITY_VERIFY_FILE, OP_INTEGRITY_VERIFY_FULL, OP_PING, OP_WEB_REQUEST, STREAM_COPY_BYTES,
};
use cleverestricky_service_core::secure_fs::TrustedDir;
use cleverestricky_service_core::unix_socket::{
    bind_abstract, connect_abstract, peer_credentials, DAEMON_SOCKET_NAME,
};
use std::env;
use std::ffi::OsString;
use std::fs;
use std::io::{self, Read};
use std::os::fd::{AsRawFd, RawFd};
use std::os::unix::net::{UnixListener, UnixStream};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{self, Child, Command, Stdio};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

const CLIENT_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_ERROR_BYTES: usize = 512;
const BACKEND_CIRCUIT_FAILURES: u32 = 5;
const BACKEND_STABLE_INTERVAL: Duration = Duration::from_secs(5 * 60);
const BACKEND_MAX_BACKOFF: Duration = Duration::from_secs(30);
const BACKEND_CIRCUIT_COOLDOWN: Duration = Duration::from_secs(60);
const ADAPTER_STABLE_INTERVAL: Duration = Duration::from_secs(5 * 60);
const ADAPTER_MAX_BACKOFF: Duration = Duration::from_secs(30);
const ADAPTER_CIRCUIT_FAILURES: u32 = 10;
const ADAPTER_CIRCUIT_COOLDOWN: Duration = Duration::from_secs(120);
const ADAPTER_POLL_INTERVAL: Duration = Duration::from_millis(100);
const BACKEND_BROKER_FD: RawFd = 9;
const FILE_SOCKET_NAME: &[u8] = b"cleverestrickyd.files.v1";
const CAPABILITY_WORKERS: usize = 2;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct AdapterLease {
    pid: u32,
    generation: u32,
}

#[derive(Debug, Default)]
struct AdapterIdentity {
    state: std::sync::atomic::AtomicU64,
}

impl AdapterIdentity {
    fn pack(lease: AdapterLease) -> u64 {
        ((lease.generation as u64) << 32) | u64::from(lease.pid)
    }

    fn unpack(state: u64) -> Option<AdapterLease> {
        let pid = state as u32;
        (pid != 0).then_some(AdapterLease {
            pid,
            generation: (state >> 32) as u32,
        })
    }

    fn current(&self) -> Option<AdapterLease> {
        Self::unpack(self.state.load(std::sync::atomic::Ordering::Acquire))
    }

    fn publish(&self, pid: u32) -> AdapterLease {
        assert_ne!(pid, 0);
        let mut current = self.state.load(std::sync::atomic::Ordering::Acquire);
        loop {
            let next_generation = (current >> 32).wrapping_add(1) as u32;
            let lease = AdapterLease {
                pid,
                generation: next_generation,
            };
            match self.state.compare_exchange_weak(
                current,
                Self::pack(lease),
                std::sync::atomic::Ordering::AcqRel,
                std::sync::atomic::Ordering::Acquire,
            ) {
                Ok(_) => return lease,
                Err(new) => current = new,
            }
        }
    }

    fn invalidate(&self, lease: AdapterLease) {
        let invalid = (u64::from(lease.generation.wrapping_add(1))) << 32;
        let _ = self.state.compare_exchange(
            Self::pack(lease),
            invalid,
            std::sync::atomic::Ordering::AcqRel,
            std::sync::atomic::Ordering::Acquire,
        );
    }

    fn matches(&self, lease: AdapterLease) -> bool {
        self.current() == Some(lease)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct AdapterRetryPlan {
    rapid_failures: u32,
    delay: Duration,
    circuit_open: bool,
}

fn main() {
    if let Err(error) = run() {
        eprintln!("cleverestrickyd: {error}");
        process::exit(1);
    }
}

fn run() -> io::Result<()> {
    harden_process()?;
    let module_dir = Arc::new(module_directory()?);
    validate_module_directory(&module_dir)?;

    let config_root = Arc::new(config_file_broker::prepare_root()?);
    let web_listener = match bind_abstract(DAEMON_SOCKET_NAME) {
        Ok(listener) => listener,
        Err(error) if error.kind() == io::ErrorKind::AddrInUse => {
            if let Ok(mut stream) = connect_abstract(DAEMON_SOCKET_NAME) {
                let _ = stream.set_read_timeout(Some(Duration::from_secs(1)));
                let _ = stream.set_write_timeout(Some(Duration::from_secs(1)));
                if let Ok(creds) = peer_credentials(&stream) {
                    if write_frame(&mut stream, OP_PING, 0, &[]).is_ok() {
                        if let Ok(header) = read_header(&mut stream) {
                            if header.opcode == OP_PING && header.flags == 0 {
                                eprintln!(
                                    "cleverestrickyd: another active daemon is already serving requests (PID {}); exiting cleanly",
                                    creds.pid
                                );
                                return Ok(());
                            }
                        }
                    }
                }
            }
            return Err(error);
        }
        Err(error) => return Err(error),
    };
    let file_listener = bind_abstract(FILE_SOCKET_NAME)?;
    let _ = config_root.atomic_write("daemon.pid", process::id().to_string().as_bytes(), 0o600);
    let adapter_identity = Arc::new(AdapterIdentity::default());

    let web_identity = Arc::clone(&adapter_identity);
    let web_module_dir = Arc::clone(&module_dir);
    thread::Builder::new()
        .name("ct-web-ipc".to_string())
        .spawn(move || {
            if let Err(error) = serve_web(web_listener, web_identity, web_module_dir) {
                eprintln!("cleverestrickyd: WebUI IPC service failed: {error}");
                process::exit(1);
            }
        })?;

    spawn_capability_workers(
        file_listener,
        Arc::clone(&adapter_identity),
        Arc::clone(&config_root),
    )?;

    let backend_dir = (*module_dir).clone();
    let backend_root = Arc::clone(&config_root);
    let backend_identity = Arc::clone(&adapter_identity);
    thread::Builder::new()
        .name("ct-backend".to_string())
        .spawn(move || supervise_backend(backend_dir, backend_identity, backend_root))?;

    let mut rapid_failures = 0u32;
    loop {
        let started = Instant::now();
        match spawn_android_adapter(&module_dir) {
            Ok(mut adapter) => {
                let lease = adapter_identity.publish(adapter.id());
                let _ = config_root.atomic_write(
                    "adapter.pid",
                    adapter.id().to_string().as_bytes(),
                    0o600,
                );
                eprintln!(
                    "cleverestrickyd: Android adapter generation {} started as pid {}",
                    lease.generation, lease.pid
                );
                match adapter.wait() {
                    Ok(status) => eprintln!(
                        "cleverestrickyd: Android adapter generation {} exited with {status}",
                        lease.generation
                    ),
                    Err(error) => eprintln!(
                        "cleverestrickyd: Android adapter generation {} wait failed: {error}",
                        lease.generation
                    ),
                }
                let _ = config_root.unlink_file("adapter.pid");
                adapter_identity.invalidate(lease);
            }
            Err(error) => eprintln!("cleverestrickyd: Android adapter launch failed: {error}"),
        }

        let plan = adapter_retry_plan(rapid_failures, started.elapsed());
        rapid_failures = plan.rapid_failures;
        if plan.circuit_open {
            eprintln!(
                "cleverestrickyd: adapter circuit open after {ADAPTER_CIRCUIT_FAILURES} rapid failures; retrying after {}s",
                plan.delay.as_secs()
            );
        } else {
            eprintln!(
                "cleverestrickyd: restarting Android adapter after {}s",
                plan.delay.as_secs()
            );
        }
        thread::sleep(plan.delay);
    }
}

fn module_directory() -> io::Result<PathBuf> {
    if let Some(argument) = env::args_os().nth(1) {
        return Ok(PathBuf::from(argument));
    }
    let executable = env::current_exe()?;
    executable
        .parent()
        .map(Path::to_path_buf)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "daemon has no module parent"))
}

fn validate_module_directory(module_dir: &Path) -> io::Result<()> {
    let metadata = fs::symlink_metadata(module_dir)?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "module directory is unsafe",
        ));
    }
    require_regular_file(&module_dir.join("service.apk"), "service.apk")
}

fn require_regular_file(path: &Path, name: &str) -> io::Result<()> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("{name} is unsafe"),
        ));
    }
    Ok(())
}

fn valid_backend_auth_value(value: &str) -> bool {
    value.len() == BACKEND_AUTH_HEX_BYTES
        && value
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
        && value.bytes().any(|byte| byte != b'0')
}

fn backend_auth_env() -> io::Result<OsString> {
    let value = env::var_os(BACKEND_AUTH_ENV)
        .ok_or_else(|| io::Error::other("backend capability is unavailable"))?;
    let encoded = value
        .to_str()
        .ok_or_else(|| io::Error::other("backend capability is invalid"))?;
    if !valid_backend_auth_value(encoded) {
        return Err(io::Error::other("backend capability is invalid"));
    }
    Ok(value)
}

fn harden_process() -> io::Result<()> {
    let parent_pid = unsafe { libc::getppid() };
    if parent_pid <= 1 {
        return Err(io::Error::new(
            io::ErrorKind::BrokenPipe,
            "shell supervisor is unavailable",
        ));
    }
    // SAFETY: `prctl(PR_SET_PDEATHSIG, SIGTERM)` has no pointer arguments. If the shell supervisor
    // dies, the daemon must also terminate to avoid orphaning and socket port conflicts.
    if unsafe { libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM, 0, 0, 0) } != 0 {
        return Err(io::Error::last_os_error());
    }
    if unsafe { libc::getppid() } != parent_pid {
        return Err(io::Error::new(
            io::ErrorKind::BrokenPipe,
            "shell supervisor changed during hardening",
        ));
    }
    // SAFETY: `umask` takes a value argument only, has process-global semantics intended for this
    // single-purpose daemon, and retains no pointers or references.
    unsafe { libc::umask(0o077) };
    // SAFETY: `prctl(PR_SET_DUMPABLE, 0)` has no pointer arguments. This daemon intentionally makes
    // itself non-dumpable before it accepts privileged IPC or starts the Android adapter.
    if unsafe { libc::prctl(libc::PR_SET_DUMPABLE, 0, 0, 0, 0) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn spawn_android_adapter(module_dir: &Path) -> io::Result<Child> {
    let classpath = module_dir.join("service.apk");
    let backend_auth = backend_auth_env()?;
    let mut command = Command::new("/system/bin/app_process");
    command
        .arg("/")
        .arg("--nice-name=CleveresTricky")
        .arg("cleveres.tricky.cleverestech.MainKt")
        .env("CLASSPATH", classpath)
        .env(BACKEND_AUTH_ENV, backend_auth)
        .stdin(Stdio::null())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit());

    // SAFETY: the pre-exec closure uses only async-signal-safe Linux syscalls (`prctl`, `getppid`)
    // and constructs no shared Rust state after fork. It runs in the child immediately before exec.
    // PR_SET_PDEATHSIG prevents an adapter orphan if the Rust supervisor is terminated, while the
    // parent-PID check closes the race where the parent exits between fork and `prctl`.
    unsafe {
        command.pre_exec(|| {
            if libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM, 0, 0, 0) != 0 {
                return Err(io::Error::last_os_error());
            }
            if libc::getppid() == 1 {
                libc::_exit(125);
            }
            Ok(())
        });
    }
    command.spawn()
}

fn spawn_backend(module_dir: &Path, adapter_pid: u32) -> io::Result<(Child, UnixStream)> {
    let path = module_dir.join("cleverestricky_backend");
    require_regular_file(&path, "cleverestricky_backend")?;
    let backend_auth = backend_auth_env()?;
    let (daemon_broker, child_broker) = UnixStream::pair()?;
    set_cloexec(daemon_broker.as_raw_fd())?;
    set_cloexec(child_broker.as_raw_fd())?;
    let child_broker_fd = child_broker.as_raw_fd();

    let mut command = Command::new(path);
    command
        .arg(adapter_pid.to_string())
        .env_clear()
        .env(BACKEND_AUTH_ENV, backend_auth)
        .stdin(Stdio::null())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit());
    // SAFETY: the closure performs only async-signal-safe descriptor syscalls. `child_broker_fd` is
    // live in the forked child and the target descriptor is a fixed value below RLIMIT_NOFILE.
    unsafe {
        command.pre_exec(move || inherit_broker_fd(child_broker_fd));
    }
    let child = command.spawn()?;
    drop(child_broker);
    Ok((child, daemon_broker))
}

fn set_cloexec(fd: RawFd) -> io::Result<()> {
    // SAFETY: F_GETFD/F_SETFD are scalar descriptor operations and retain no pointers.
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFD) };
    if flags < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: fd remains live for this call; the flags value came from F_GETFD above.
    if unsafe { libc::fcntl(fd, libc::F_SETFD, flags | libc::FD_CLOEXEC) } < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn inherit_broker_fd(source: RawFd) -> io::Result<()> {
    if source != BACKEND_BROKER_FD {
        // SAFETY: both descriptors are scalar values. dup2 atomically replaces the target and
        // clears close-on-exec on the inherited copy. The source is closed only after success.
        if unsafe { libc::dup2(source, BACKEND_BROKER_FD) } < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: source remains a distinct live descriptor after successful dup2.
        let _ = unsafe { libc::close(source) };
        return Ok(());
    }

    // SAFETY: when source already equals the fixed target we only clear FD_CLOEXEC so exec keeps it.
    let flags = unsafe { libc::fcntl(source, libc::F_GETFD) };
    if flags < 0 || unsafe { libc::fcntl(source, libc::F_SETFD, flags & !libc::FD_CLOEXEC) } < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

#[derive(Clone, Debug, Eq, PartialEq)]
enum BackendRunOutcome {
    Exited(String),
    AdapterChanged,
}

fn run_backend_once(
    module_dir: &Path,
    lease: AdapterLease,
    adapter_identity: &AdapterIdentity,
    root: Arc<TrustedDir>,
) -> io::Result<BackendRunOutcome> {
    let (mut child, broker) = spawn_backend(module_dir, lease.pid)?;
    let backend_pid = child.id();
    let _ = root.atomic_write("backend.pid", backend_pid.to_string().as_bytes(), 0o600);
    let broker_root = Arc::clone(&root);
    let broker_thread = match thread::Builder::new()
        .name("ct-keybox-broker".to_string())
        .spawn(move || {
            if let Err(error) = keybox_file_broker::serve(broker, &broker_root) {
                eprintln!("cleverestrickyd: keybox broker failed: {error}");
                let _ = unsafe { libc::kill(backend_pid as libc::pid_t, libc::SIGTERM) };
            }
        }) {
        Ok(handle) => handle,
        Err(error) => {
            let _ = root.unlink_file("backend.pid");
            let _ = child.kill();
            let _ = child.wait();
            return Err(error);
        }
    };

    let outcome = loop {
        if !adapter_identity.matches(lease) {
            let _ = child.kill();
            let _ = child.wait();
            break BackendRunOutcome::AdapterChanged;
        }
        if let Some(status) = child.try_wait()? {
            break BackendRunOutcome::Exited(format!("backend exited with {status}"));
        }
        thread::sleep(ADAPTER_POLL_INTERVAL);
    };
    let _ = root.unlink_file("backend.pid");
    broker_thread
        .join()
        .map_err(|_| io::Error::other("keybox broker thread panicked"))?;
    Ok(outcome)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct BackendRetryPlan {
    rapid_failures: u32,
    delay: Duration,
    circuit_open: bool,
}

fn backend_retry_plan(previous_rapid_failures: u32, runtime: Duration) -> BackendRetryPlan {
    if runtime >= BACKEND_STABLE_INTERVAL {
        return BackendRetryPlan {
            rapid_failures: 0,
            delay: Duration::from_secs(1),
            circuit_open: false,
        };
    }

    let rapid_failures = previous_rapid_failures.saturating_add(1);
    if rapid_failures >= BACKEND_CIRCUIT_FAILURES {
        return BackendRetryPlan {
            rapid_failures: 0,
            delay: BACKEND_CIRCUIT_COOLDOWN,
            circuit_open: true,
        };
    }

    let backoff_seconds = 1u64 << rapid_failures.min(5);
    BackendRetryPlan {
        rapid_failures,
        delay: Duration::from_secs(backoff_seconds).min(BACKEND_MAX_BACKOFF),
        circuit_open: false,
    }
}

fn adapter_retry_plan(previous_rapid_failures: u32, runtime: Duration) -> AdapterRetryPlan {
    if runtime >= ADAPTER_STABLE_INTERVAL {
        return AdapterRetryPlan {
            rapid_failures: 0,
            delay: Duration::from_secs(1),
            circuit_open: false,
        };
    }
    let rapid_failures = previous_rapid_failures.saturating_add(1);
    if rapid_failures >= ADAPTER_CIRCUIT_FAILURES {
        return AdapterRetryPlan {
            rapid_failures: 0,
            delay: ADAPTER_CIRCUIT_COOLDOWN,
            circuit_open: true,
        };
    }
    let backoff_seconds = 1u64 << rapid_failures.min(5);
    AdapterRetryPlan {
        rapid_failures,
        delay: Duration::from_secs(backoff_seconds).min(ADAPTER_MAX_BACKOFF),
        circuit_open: false,
    }
}

fn supervise_backend(
    module_dir: PathBuf,
    adapter_identity: Arc<AdapterIdentity>,
    root: Arc<TrustedDir>,
) {
    let mut rapid_failures = 0u32;
    loop {
        let Some(lease) = adapter_identity.current() else {
            thread::sleep(ADAPTER_POLL_INTERVAL);
            continue;
        };
        let started = Instant::now();
        let outcome = run_backend_once(&module_dir, lease, &adapter_identity, Arc::clone(&root));
        match outcome {
            Ok(BackendRunOutcome::AdapterChanged) => {
                rapid_failures = 0;
                continue;
            }
            Ok(BackendRunOutcome::Exited(message)) => eprintln!("cleverestrickyd: {message}"),
            Err(error) => eprintln!("cleverestrickyd: backend launch/wait failed: {error}"),
        }

        let plan = backend_retry_plan(rapid_failures, started.elapsed());
        rapid_failures = plan.rapid_failures;
        if plan.circuit_open {
            eprintln!(
                "cleverestrickyd: backend circuit open after {BACKEND_CIRCUIT_FAILURES} rapid failures; retrying after {}s",
                plan.delay.as_secs()
            );
        }
        thread::sleep(plan.delay);
    }
}

fn spawn_capability_workers(
    listener: UnixListener,
    adapter_identity: Arc<AdapterIdentity>,
    root: Arc<TrustedDir>,
) -> io::Result<()> {
    for index in 0..CAPABILITY_WORKERS {
        let worker_listener = listener.try_clone()?;
        let worker_root = Arc::clone(&root);
        let worker_identity = Arc::clone(&adapter_identity);
        thread::Builder::new()
            .name(format!("ct-file-ipc-{index}"))
            .spawn(move || {
                if let Err(error) =
                    serve_capability_worker(worker_listener, worker_identity, worker_root)
                {
                    eprintln!("cleverestrickyd: file IPC worker failed: {error}");
                    process::exit(1);
                }
            })?;
    }
    Ok(())
}

fn serve_capability_worker(
    listener: UnixListener,
    adapter_identity: Arc<AdapterIdentity>,
    root: Arc<TrustedDir>,
) -> io::Result<()> {
    let mut scratch = vec![0u8; STREAM_COPY_BYTES];
    loop {
        let (mut client, _) = match listener.accept() {
            Ok(value) => value,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error)
                if matches!(
                    error.raw_os_error(),
                    Some(libc::EMFILE) | Some(libc::ENFILE) | Some(libc::ENOBUFS)
                ) =>
            {
                thread::sleep(Duration::from_millis(50));
                continue;
            }
            Err(error) => return Err(error),
        };
        let credentials = match peer_credentials(&client) {
            Ok(value) if value.uid == 0 => value,
            Ok(_) => continue,
            Err(_) => continue,
        };
        let _ = client.set_read_timeout(Some(CLIENT_TIMEOUT));
        let _ = client.set_write_timeout(Some(CLIENT_TIMEOUT));
        let peer_pid = u32::try_from(credentials.pid).ok();
        let peer_is_adapter = adapter_identity
            .current()
            .is_some_and(|lease| peer_pid == Some(lease.pid));
        if let Err(error) =
            handle_capability_request(&mut client, peer_is_adapter, &root, &mut scratch)
        {
            if !matches!(
                error.kind(),
                io::ErrorKind::UnexpectedEof
                    | io::ErrorKind::ConnectionReset
                    | io::ErrorKind::BrokenPipe
                    | io::ErrorKind::TimedOut
                    | io::ErrorKind::WouldBlock
            ) {
                eprintln!("cleverestrickyd: capability request transport failed: {error}");
            }
        }
    }
}

fn handle_capability_request(
    client: &mut UnixStream,
    peer_is_adapter: bool,
    root: &TrustedDir,
    scratch: &mut [u8],
) -> io::Result<()> {
    let header = read_header_bounded(client, config_file_broker::MAX_REQUEST_BYTES)?;
    match header.opcode {
        OP_PING if header.flags == 0 && header.payload_len == 0 => {
            write_frame(client, OP_PING, 0, b"pong")
        }
        OP_FILE_WRITE if peer_is_adapter && header.flags == 0 => {
            match config_file_broker::handle_stream_from(root, client, header.payload_len, scratch)
            {
                Ok(()) => write_frame(client, OP_FILE_WRITE, 0, b"ok"),
                Err(_) => reply_text_error(client, OP_FILE_WRITE, "file operation rejected"),
            }
        }
        _ => reply_text_error(client, header.opcode, "unsupported capability operation"),
    }
}

struct RegisteredAdapter {
    stream: UnixStream,
    lease: AdapterLease,
}

fn serve_web(
    listener: UnixListener,
    adapter_identity: Arc<AdapterIdentity>,
    module_dir: Arc<PathBuf>,
) -> io::Result<()> {
    let mut adapter: Option<RegisteredAdapter> = None;
    let mut relay_buffer = vec![0u8; STREAM_COPY_BYTES];
    let cached_manifest: Arc<
        std::sync::RwLock<Option<cleverestricky_integrity_core::IntegrityManifest>>,
    > = Arc::new(std::sync::RwLock::new(None));
    loop {
        let (mut client, _) = match listener.accept() {
            Ok(value) => value,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error)
                if matches!(
                    error.raw_os_error(),
                    Some(libc::EMFILE) | Some(libc::ENFILE) | Some(libc::ENOBUFS)
                ) =>
            {
                thread::sleep(Duration::from_millis(50));
                continue;
            }
            Err(error) => return Err(error),
        };
        let credentials = match peer_credentials(&client) {
            Ok(value) if value.uid == 0 => value,
            Ok(_) => continue,
            Err(_) => continue,
        };
        let _ = client.set_read_timeout(Some(CLIENT_TIMEOUT));
        let _ = client.set_write_timeout(Some(CLIENT_TIMEOUT));
        let header = match read_header_bounded(&mut client, MAX_FRAME_BYTES) {
            Ok(value) => value,
            Err(error) => {
                let _ = reply_error(&mut client, OP_PING, &error);
                continue;
            }
        };
        if adapter
            .as_ref()
            .is_some_and(|registered| !adapter_identity.matches(registered.lease))
        {
            adapter = None;
        }
        let peer_pid = u32::try_from(credentials.pid).ok();
        let peer_lease = adapter_identity
            .current()
            .filter(|lease| peer_pid == Some(lease.pid));

        match header.opcode {
            OP_ADAPTER_REGISTER => {
                let Some(lease) = peer_lease else {
                    let _ = reply_text_error(
                        &mut client,
                        OP_ADAPTER_REGISTER,
                        "invalid adapter registration",
                    );
                    continue;
                };
                if header.flags != 0 || header.payload_len != 0 {
                    let _ = reply_text_error(
                        &mut client,
                        OP_ADAPTER_REGISTER,
                        "invalid adapter registration",
                    );
                    continue;
                }
                if write_frame(&mut client, OP_ADAPTER_REGISTER, 0, b"ok").is_err() {
                    continue;
                }
                adapter = Some(RegisteredAdapter {
                    stream: client,
                    lease,
                });
            }
            OP_PING if header.flags == 0 && header.payload_len == 0 => {
                let _ = write_frame(&mut client, OP_PING, 0, b"pong");
            }
            OP_WEB_REQUEST if header.flags == 0 && header.payload_len <= MAX_FRAME_BYTES => {
                if let Err(error) = forward_web_request_with_timeout(
                    &mut client,
                    header,
                    &mut adapter,
                    &mut relay_buffer,
                    CLIENT_TIMEOUT,
                ) {
                    adapter = None;
                    let _ = reply_error(&mut client, OP_WEB_REQUEST, &error);
                }
            }
            OP_INTEGRITY_VERIFY_FULL if header.flags == 0 && header.payload_len == 32 => {
                let mut hmac_key = [0u8; 32];
                if client.read_exact(&mut hmac_key).is_ok() {
                    handle_integrity_verify_full(
                        &mut client,
                        &module_dir,
                        &cached_manifest,
                        &hmac_key,
                    );
                }
            }
            OP_INTEGRITY_VERIFY_FILE if header.flags == 0 && header.payload_len > 32 => {
                let mut payload = vec![0u8; header.payload_len as usize];
                if client.read_exact(&mut payload).is_ok() {
                    handle_integrity_verify_file(
                        &mut client,
                        &module_dir,
                        &cached_manifest,
                        &payload,
                    );
                }
            }
            OP_INTEGRITY_DELETE_MODULE if header.flags == 0 => {
                handle_integrity_delete_module(&mut client, &module_dir);
            }
            _ => {
                let _ = reply_text_error(&mut client, header.opcode, "unsupported IPC operation");
            }
        }
    }
}

fn handle_integrity_verify_full(
    client: &mut UnixStream,
    module_dir: &Path,
    cached_manifest: &std::sync::RwLock<Option<cleverestricky_integrity_core::IntegrityManifest>>,
    hmac_key: &[u8; 32],
) {
    let module_dir_str = match module_dir.to_str() {
        Some(s) => s,
        None => {
            let _ = reply_text_error(client, OP_INTEGRITY_VERIFY_FULL, "invalid module dir path");
            return;
        }
    };
    let dir_fd = match cleverestricky_integrity_core::safe_fd::open_dir_nofollow(module_dir_str) {
        Ok(fd) => fd,
        Err(e) => {
            let _ = reply_text_error(
                client,
                OP_INTEGRITY_VERIFY_FULL,
                &format!("failed to open module dir: {e}"),
            );
            return;
        }
    };
    let raw_dir_fd = cleverestricky_integrity_core::safe_fd::get_raw_fd(&dir_fd);
    let manifest_fd = match cleverestricky_integrity_core::safe_fd::open_file_nofollow(
        raw_dir_fd,
        "integrity_manifest.json",
    ) {
        Ok(fd) => fd,
        Err(e) => {
            let _ = reply_text_error(
                client,
                OP_INTEGRITY_VERIFY_FULL,
                &format!("manifest missing: {e}"),
            );
            return;
        }
    };
    let mut manifest_file = fs::File::from(manifest_fd);
    let mut manifest_str = String::new();
    if let Err(e) = manifest_file.read_to_string(&mut manifest_str) {
        let _ = reply_text_error(
            client,
            OP_INTEGRITY_VERIFY_FULL,
            &format!("failed to read manifest: {e}"),
        );
        return;
    }

    let manifest = match cleverestricky_integrity_core::IntegrityManifest::parse_and_verify(
        &manifest_str,
        hmac_key,
    ) {
        Ok(m) => m,
        Err(e) => {
            let _ = write_frame(
                client,
                OP_INTEGRITY_VERIFY_FULL,
                FLAG_ERROR,
                format!("signature invalid: {e}").as_bytes(),
            );
            return;
        }
    };

    let result = cleverestricky_integrity_core::verify_full(raw_dir_fd, &manifest);
    if result.is_pass() {
        if let Ok(mut lock) = cached_manifest.write() {
            *lock = Some(manifest);
        }
        let _ = write_frame(client, OP_INTEGRITY_VERIFY_FULL, 0, &[0]);
    } else {
        let mut msg = String::new();
        for v in result.violations() {
            msg.push_str(&v.to_string());
            msg.push('\n');
        }
        let _ = write_frame(client, OP_INTEGRITY_VERIFY_FULL, FLAG_ERROR, msg.as_bytes());
    }
}

fn handle_integrity_verify_file(
    client: &mut UnixStream,
    module_dir: &Path,
    cached_manifest: &std::sync::RwLock<Option<cleverestricky_integrity_core::IntegrityManifest>>,
    payload: &[u8],
) {
    let hmac_key = &payload[..32];
    let relative_path = match std::str::from_utf8(&payload[32..]) {
        Ok(p) => p,
        Err(_) => {
            let _ = reply_text_error(client, OP_INTEGRITY_VERIFY_FILE, "invalid utf-8 path");
            return;
        }
    };

    let manifest_guard = cached_manifest.read().unwrap();
    let manifest = match manifest_guard.as_ref() {
        Some(m) => m.clone(),
        None => {
            drop(manifest_guard);
            let module_dir_str = match module_dir.to_str() {
                Some(s) => s,
                None => {
                    let _ = reply_text_error(
                        client,
                        OP_INTEGRITY_VERIFY_FILE,
                        "invalid module dir path",
                    );
                    return;
                }
            };
            let dir_fd =
                match cleverestricky_integrity_core::safe_fd::open_dir_nofollow(module_dir_str) {
                    Ok(fd) => fd,
                    Err(e) => {
                        let _ = reply_text_error(
                            client,
                            OP_INTEGRITY_VERIFY_FILE,
                            &format!("failed to open module dir: {e}"),
                        );
                        return;
                    }
                };
            let raw_dir_fd = cleverestricky_integrity_core::safe_fd::get_raw_fd(&dir_fd);
            let manifest_fd = match cleverestricky_integrity_core::safe_fd::open_file_nofollow(
                raw_dir_fd,
                "integrity_manifest.json",
            ) {
                Ok(fd) => fd,
                Err(e) => {
                    let _ = reply_text_error(
                        client,
                        OP_INTEGRITY_VERIFY_FILE,
                        &format!("manifest missing: {e}"),
                    );
                    return;
                }
            };
            let mut manifest_file = fs::File::from(manifest_fd);
            let mut manifest_str = String::new();
            if let Err(e) = manifest_file.read_to_string(&mut manifest_str) {
                let _ = reply_text_error(
                    client,
                    OP_INTEGRITY_VERIFY_FILE,
                    &format!("failed to read manifest: {e}"),
                );
                return;
            }

            let m = match cleverestricky_integrity_core::IntegrityManifest::parse_and_verify(
                &manifest_str,
                hmac_key,
            ) {
                Ok(m) => m,
                Err(e) => {
                    let _ = write_frame(
                        client,
                        OP_INTEGRITY_VERIFY_FILE,
                        FLAG_ERROR,
                        format!("signature invalid: {e}").as_bytes(),
                    );
                    return;
                }
            };
            if let Ok(mut lock) = cached_manifest.write() {
                *lock = Some(m.clone());
            }
            m
        }
    };

    let module_dir_str = match module_dir.to_str() {
        Some(s) => s,
        None => {
            let _ = reply_text_error(client, OP_INTEGRITY_VERIFY_FILE, "invalid module dir path");
            return;
        }
    };
    let dir_fd = match cleverestricky_integrity_core::safe_fd::open_dir_nofollow(module_dir_str) {
        Ok(fd) => fd,
        Err(e) => {
            let _ = reply_text_error(
                client,
                OP_INTEGRITY_VERIFY_FILE,
                &format!("failed to open module dir: {e}"),
            );
            return;
        }
    };
    let raw_dir_fd = cleverestricky_integrity_core::safe_fd::get_raw_fd(&dir_fd);

    let result = cleverestricky_integrity_core::verify_file(raw_dir_fd, &manifest, relative_path);
    if result.is_pass() {
        let _ = write_frame(client, OP_INTEGRITY_VERIFY_FILE, 0, &[0]);
    } else {
        let mut msg = String::new();
        for v in result.violations() {
            msg.push_str(&v.to_string());
            msg.push('\n');
        }
        let _ = write_frame(client, OP_INTEGRITY_VERIFY_FILE, FLAG_ERROR, msg.as_bytes());
    }
}

fn handle_integrity_delete_module(client: &mut UnixStream, module_dir: &Path) {
    let _ = delete_dir_contents_safe(module_dir);
    let _ = write_frame(client, OP_INTEGRITY_DELETE_MODULE, 0, &[0]);
    let _ = Command::new("/system/bin/reboot")
        .status()
        .or_else(|_| Command::new("reboot").status());
}

fn delete_dir_contents_safe(dir: &Path) -> io::Result<()> {
    if let Ok(entries) = fs::read_dir(dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if let Ok(meta) = fs::symlink_metadata(&path) {
                if meta.file_type().is_symlink() || meta.is_file() {
                    let _ = fs::remove_file(&path);
                } else if meta.is_dir() {
                    let _ = delete_dir_contents_safe(&path);
                    let _ = fs::remove_dir(&path);
                }
            }
        }
    }
    let _ = fs::remove_dir(dir);
    Ok(())
}

fn forward_web_request_with_timeout(
    client: &mut UnixStream,
    request: FrameHeader,
    adapter: &mut Option<RegisteredAdapter>,
    scratch: &mut [u8],
    timeout: Duration,
) -> io::Result<()> {
    let target = &mut adapter
        .as_mut()
        .ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::NotConnected,
                "Android adapter is unavailable",
            )
        })?
        .stream;
    target.set_read_timeout(Some(timeout))?;
    target.set_write_timeout(Some(timeout))?;
    write_header(target, request)?;
    relay_exact(client, target, request.payload_len, scratch)?;

    let response = read_header_bounded(target, MAX_FRAME_BYTES)?;
    if response.opcode != OP_WEB_REQUEST || response.flags != 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "adapter returned an invalid response header",
        ));
    }
    write_header(client, response)?;
    relay_exact(target, client, response.payload_len, scratch)
}

fn reply_error(stream: &mut UnixStream, opcode: u16, error: &io::Error) -> io::Result<()> {
    reply_text_error(stream, opcode, &error.to_string())
}

fn reply_text_error(stream: &mut UnixStream, opcode: u16, message: &str) -> io::Result<()> {
    let bytes = message.as_bytes();
    write_frame(
        stream,
        opcode.max(1),
        FLAG_ERROR,
        &bytes[..bytes.len().min(MAX_ERROR_BYTES)],
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::io::{Read, Write};
    use std::sync::atomic::{AtomicU64, Ordering};

    struct TestRoot {
        path: PathBuf,
    }

    impl TestRoot {
        fn new() -> Self {
            static COUNTER: AtomicU64 = AtomicU64::new(1);
            let path = std::env::temp_dir().join(format!(
                "ct-daemon-lanes-{}-{}",
                std::process::id(),
                COUNTER.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&path).unwrap();
            Self { path }
        }

        fn trusted(&self) -> TrustedDir {
            TrustedDir::open(&self.path).unwrap()
        }
    }

    impl Drop for TestRoot {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.path);
        }
    }

    fn config_payload(path: &str, body: &[u8]) -> Vec<u8> {
        let path = path.as_bytes();
        let body_len = u32::try_from(body.len()).unwrap();
        let mut payload = Vec::with_capacity(1 + 2 + 4 + path.len() + body.len() + 1);
        payload.push(0);
        payload.extend_from_slice(&(path.len() as u16).to_be_bytes());
        payload.extend_from_slice(&body_len.to_be_bytes());
        payload.extend_from_slice(path);
        payload.extend_from_slice(body);
        payload.push(0xa5);
        payload
    }

    fn read_payload(stream: &mut UnixStream, max: usize) -> (FrameHeader, Vec<u8>) {
        let header = read_header_bounded(stream, max).unwrap();
        let mut body = vec![0u8; header.payload_len];
        stream.read_exact(&mut body).unwrap();
        (header, body)
    }

    fn exercise_reentrant_web_write(path: &str, body: Vec<u8>) {
        let test = TestRoot::new();
        let root = Arc::new(test.trusted());
        if path.starts_with("keyboxes/") {
            root.mkdir_child("keyboxes", 0o700).unwrap();
        }

        let (mut bridge, mut daemon_web) = UnixStream::pair().unwrap();
        let (daemon_adapter, mut adapter) = UnixStream::pair().unwrap();
        let (mut file_client, mut file_server) = UnixStream::pair().unwrap();

        let file_root = Arc::clone(&root);
        let file_thread = thread::spawn(move || {
            let mut scratch = vec![0u8; STREAM_COPY_BYTES];
            handle_capability_request(&mut file_server, true, &file_root, &mut scratch).unwrap();
        });

        let path_owned = path.to_string();
        let adapter_thread = thread::spawn(move || {
            let (request, request_body) = read_payload(&mut adapter, MAX_FRAME_BYTES);
            assert_eq!(request.opcode, OP_WEB_REQUEST);
            assert_eq!(request_body, b"request");

            let payload = config_payload(&path_owned, &body);
            write_header(
                &mut file_client,
                FrameHeader {
                    opcode: OP_FILE_WRITE,
                    flags: 0,
                    payload_len: payload.len(),
                },
            )
            .unwrap();
            file_client.write_all(&payload).unwrap();
            let (file_response, file_body) = read_payload(&mut file_client, 512);
            assert_eq!(file_response.opcode, OP_FILE_WRITE);
            assert_eq!(file_response.flags, 0);
            assert_eq!(file_body, b"ok");

            write_frame(&mut adapter, OP_WEB_REQUEST, 0, b"ok").unwrap();
        });

        write_frame(&mut bridge, OP_WEB_REQUEST, 0, b"request").unwrap();
        let request = read_header_bounded(&mut daemon_web, MAX_FRAME_BYTES).unwrap();
        let mut adapter_slot = Some(RegisteredAdapter {
            stream: daemon_adapter,
            lease: AdapterLease {
                pid: 1,
                generation: 1,
            },
        });
        let mut relay_scratch = vec![0u8; STREAM_COPY_BYTES];
        forward_web_request_with_timeout(
            &mut daemon_web,
            request,
            &mut adapter_slot,
            &mut relay_scratch,
            Duration::from_secs(2),
        )
        .unwrap();
        let (response, response_body) = read_payload(&mut bridge, MAX_FRAME_BYTES);
        assert_eq!(response.opcode, OP_WEB_REQUEST);
        assert_eq!(response_body, b"ok");

        adapter_thread.join().unwrap();
        file_thread.join().unwrap();
        assert!(test.path.join(path).is_file());
    }

    #[test]
    fn backend_capability_encoding_is_exact_and_canonical() {
        assert!(valid_backend_auth_value(&"5a".repeat(32)));
        assert!(!valid_backend_auth_value(""));
        assert!(!valid_backend_auth_value(&"5a".repeat(31)));
        assert!(!valid_backend_auth_value(&"5a".repeat(33)));
        assert!(!valid_backend_auth_value(&"5A".repeat(32)));
        assert!(!valid_backend_auth_value(&"00".repeat(32)));
        assert!(!valid_backend_auth_value(&format!("{}gg", "5a".repeat(31))));
    }

    #[test]
    fn webui_request_can_write_configuration_without_reentrancy_deadlock() {
        exercise_reentrant_web_write("settings.json", b"{\"enabled\":true}".to_vec());
    }

    #[test]
    fn upload_import_can_write_keybox_without_reentrancy_deadlock() {
        exercise_reentrant_web_write("keyboxes/import.xml", b"<AndroidAttestation/>".to_vec());
    }

    #[test]
    fn large_webui_staged_output_uses_independent_file_lane() {
        exercise_reentrant_web_write("webui-stage.bin", vec![0xa5; 512 * 1024]);
    }

    #[test]
    fn ping_file_and_web_operations_progress_concurrently() {
        let test = TestRoot::new();
        let root = Arc::new(test.trusted());
        let (mut file_client, mut file_server) = UnixStream::pair().unwrap();
        let (mut ping_client, mut ping_server) = UnixStream::pair().unwrap();
        let file_root = Arc::clone(&root);
        let ping_root = Arc::clone(&root);

        let file_worker = thread::spawn(move || {
            let mut scratch = vec![0u8; STREAM_COPY_BYTES];
            handle_capability_request(&mut file_server, true, &file_root, &mut scratch).unwrap();
        });
        let ping_worker = thread::spawn(move || {
            let mut scratch = vec![0u8; STREAM_COPY_BYTES];
            handle_capability_request(&mut ping_server, false, &ping_root, &mut scratch).unwrap();
        });

        let file_payload = config_payload("concurrent.bin", &vec![0x5a; 256 * 1024]);
        let file_client_thread = thread::spawn(move || {
            write_header(
                &mut file_client,
                FrameHeader {
                    opcode: OP_FILE_WRITE,
                    flags: 0,
                    payload_len: file_payload.len(),
                },
            )
            .unwrap();
            file_client.write_all(&file_payload).unwrap();
            let (header, body) = read_payload(&mut file_client, 512);
            assert_eq!(header.flags, 0);
            assert_eq!(body, b"ok");
        });

        write_frame(&mut ping_client, OP_PING, 0, b"").unwrap();
        let (ping_header, ping_body) = read_payload(&mut ping_client, 512);
        assert_eq!(ping_header.opcode, OP_PING);
        assert_eq!(ping_body, b"pong");

        let (mut bridge, mut daemon_web) = UnixStream::pair().unwrap();
        let (daemon_adapter, mut adapter) = UnixStream::pair().unwrap();
        let adapter_thread = thread::spawn(move || {
            let (_, body) = read_payload(&mut adapter, MAX_FRAME_BYTES);
            assert_eq!(body, b"parallel");
            write_frame(&mut adapter, OP_WEB_REQUEST, 0, b"web-ok").unwrap();
        });
        write_frame(&mut bridge, OP_WEB_REQUEST, 0, b"parallel").unwrap();
        let request = read_header_bounded(&mut daemon_web, MAX_FRAME_BYTES).unwrap();
        let mut slot = Some(RegisteredAdapter {
            stream: daemon_adapter,
            lease: AdapterLease {
                pid: 1,
                generation: 1,
            },
        });
        let mut relay_scratch = vec![0u8; STREAM_COPY_BYTES];
        forward_web_request_with_timeout(
            &mut daemon_web,
            request,
            &mut slot,
            &mut relay_scratch,
            Duration::from_secs(2),
        )
        .unwrap();
        let (_, web_body) = read_payload(&mut bridge, MAX_FRAME_BYTES);
        assert_eq!(web_body, b"web-ok");

        file_client_thread.join().unwrap();
        file_worker.join().unwrap();
        ping_worker.join().unwrap();
        adapter_thread.join().unwrap();
        assert_eq!(
            fs::metadata(test.path.join("concurrent.bin"))
                .unwrap()
                .len(),
            256 * 1024
        );
    }

    #[test]
    fn rejected_file_request_has_exactly_one_error_frame() {
        let test = TestRoot::new();
        let root = test.trusted();
        let (mut client, mut server) = UnixStream::pair().unwrap();
        let payload = config_payload("../outside", b"x");
        write_header(
            &mut client,
            FrameHeader {
                opcode: OP_FILE_WRITE,
                flags: 0,
                payload_len: payload.len(),
            },
        )
        .unwrap();
        client.write_all(&payload).unwrap();
        let mut scratch = vec![0u8; STREAM_COPY_BYTES];
        handle_capability_request(&mut server, true, &root, &mut scratch).unwrap();
        drop(server);

        let (header, body) = read_payload(&mut client, MAX_ERROR_BYTES);
        assert_eq!(header.opcode, OP_FILE_WRITE);
        assert_eq!(header.flags, FLAG_ERROR);
        assert_eq!(body, b"file operation rejected");
        assert!(read_header_bounded(&mut client, MAX_ERROR_BYTES).is_err());
    }

    #[test]
    fn backend_circuit_breaker_recovers_after_cooldown() {
        let mut failures = 0;
        for attempt in 1..=BACKEND_CIRCUIT_FAILURES {
            let plan = backend_retry_plan(failures, Duration::from_millis(1));
            if attempt < BACKEND_CIRCUIT_FAILURES {
                assert!(!plan.circuit_open);
                assert!(plan.delay <= BACKEND_MAX_BACKOFF);
                failures = plan.rapid_failures;
            } else {
                assert!(plan.circuit_open);
                assert_eq!(plan.delay, BACKEND_CIRCUIT_COOLDOWN);
                assert_eq!(plan.rapid_failures, 0);
                failures = plan.rapid_failures;
            }
        }

        let recovered = backend_retry_plan(failures, Duration::from_millis(1));
        assert!(!recovered.circuit_open);
        assert_eq!(recovered.rapid_failures, 1);
    }

    #[test]
    fn stable_backend_run_resets_rapid_failure_state() {
        let plan = backend_retry_plan(BACKEND_CIRCUIT_FAILURES - 1, BACKEND_STABLE_INTERVAL);
        assert_eq!(plan.rapid_failures, 0);
        assert!(!plan.circuit_open);
        assert_eq!(plan.delay, Duration::from_secs(1));
    }

    #[test]
    fn web_relay_disconnect_and_timeout_fail_closed() {
        let (mut bridge, mut daemon_web) = UnixStream::pair().unwrap();
        let (daemon_adapter, adapter) = UnixStream::pair().unwrap();
        drop(adapter);
        write_frame(&mut bridge, OP_WEB_REQUEST, 0, b"disconnect").unwrap();
        let request = read_header_bounded(&mut daemon_web, MAX_FRAME_BYTES).unwrap();
        let mut slot = Some(RegisteredAdapter {
            stream: daemon_adapter,
            lease: AdapterLease {
                pid: 1,
                generation: 1,
            },
        });
        let mut scratch = vec![0u8; STREAM_COPY_BYTES];
        assert!(forward_web_request_with_timeout(
            &mut daemon_web,
            request,
            &mut slot,
            &mut scratch,
            Duration::from_millis(25),
        )
        .is_err());

        let (mut bridge, mut daemon_web) = UnixStream::pair().unwrap();
        let (daemon_adapter, _adapter) = UnixStream::pair().unwrap();
        write_frame(&mut bridge, OP_WEB_REQUEST, 0, b"timeout").unwrap();
        let request = read_header_bounded(&mut daemon_web, MAX_FRAME_BYTES).unwrap();
        let mut slot = Some(RegisteredAdapter {
            stream: daemon_adapter,
            lease: AdapterLease {
                pid: 1,
                generation: 1,
            },
        });
        assert!(forward_web_request_with_timeout(
            &mut daemon_web,
            request,
            &mut slot,
            &mut scratch,
            Duration::from_millis(25),
        )
        .is_err());
    }

    #[test]
    fn adapter_identity_rejects_stale_generation() {
        let identity = AdapterIdentity::default();
        let first = identity.publish(101);
        assert!(identity.matches(first));
        identity.invalidate(first);
        assert!(!identity.matches(first));
        let second = identity.publish(101);
        assert_ne!(first.generation, second.generation);
        assert!(!identity.matches(first));
        assert!(identity.matches(second));
    }

    #[test]
    fn adapter_restart_backoff_is_bounded_and_resets_after_stability() {
        let mut failures = 0;
        let mut circuit_opened = false;
        for _ in 0..20 {
            let plan = adapter_retry_plan(failures, Duration::from_secs(1));
            if plan.circuit_open {
                circuit_opened = true;
                assert_eq!(plan.delay, ADAPTER_CIRCUIT_COOLDOWN);
                assert_eq!(plan.rapid_failures, 0);
            } else {
                assert!(plan.delay <= ADAPTER_MAX_BACKOFF);
            }
            failures = plan.rapid_failures;
        }
        assert!(circuit_opened, "circuit breaker should have opened");
        let stable = adapter_retry_plan(failures, ADAPTER_STABLE_INTERVAL);
        assert_eq!(stable.rapid_failures, 0);
        assert_eq!(stable.delay, Duration::from_secs(1));
        assert!(!stable.circuit_open);
    }
}
