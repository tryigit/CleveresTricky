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
use sha2::{Digest, Sha256};
use std::env;
use std::ffi::OsString;
use std::fs;
use std::io::{self, Read};
use std::os::fd::{AsRawFd, RawFd};
use std::os::unix::net::{UnixListener, UnixStream};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{self, Child, Command, Stdio};
use std::sync::{mpsc, Arc};
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
const MAX_MANIFEST_BYTES: usize = 64 * 1024;
const MAX_PROC_STAT_BYTES: u64 = 16 * 1024;

fn parse_process_start_ticks(stat: &str) -> Option<u64> {
    let command_end = stat.rfind(')')?;
    stat.get(command_end + 1..)?
        .split_ascii_whitespace()
        .nth(19)?
        .parse()
        .ok()
}

fn process_identity_record(pid: u32) -> io::Result<String> {
    if pid == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "process id is zero",
        ));
    }
    let mut stat = String::new();
    fs::File::open(format!("/proc/{pid}/stat"))?
        .take(MAX_PROC_STAT_BYTES + 1)
        .read_to_string(&mut stat)?;
    if stat.len() as u64 > MAX_PROC_STAT_BYTES {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "process status exceeds its size limit",
        ));
    }
    let start_ticks = parse_process_start_ticks(&stat)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "process status is malformed"))?;
    Ok(format!("{pid} {start_ticks}\n"))
}

fn write_process_identity(root: &TrustedDir, filename: &str, pid: u32) {
    match process_identity_record(pid) {
        Ok(record) => {
            if let Err(error) = root.atomic_write(filename, record.as_bytes(), 0o600) {
                let _ = root.unlink_file(filename);
                eprintln!("cleverestrickyd: could not record {filename}: {error}");
            }
        }
        Err(error) => {
            let _ = root.unlink_file(filename);
            eprintln!("cleverestrickyd: could not identify pid {pid}: {error}");
        }
    }
}

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
    /// Packs an adapter lease into a 64-bit atomic state word.
    fn pack(lease: AdapterLease) -> u64 {
        ((lease.generation as u64) << 32) | u64::from(lease.pid)
    }

    /// Unpacks a 64-bit atomic state word into an adapter lease, if valid.
    fn unpack(state: u64) -> Option<AdapterLease> {
        let pid = state as u32;
        (pid != 0).then_some(AdapterLease {
            pid,
            generation: (state >> 32) as u32,
        })
    }

    /// Returns the current adapter lease, if any adapter is registered.
    fn current(&self) -> Option<AdapterLease> {
        Self::unpack(self.state.load(std::sync::atomic::Ordering::Acquire))
    }

    /// Publishes a new adapter lease with an incremented generation number.
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

    /// Invalidates the given adapter lease by incrementing its generation.
    fn invalidate(&self, lease: AdapterLease) {
        let invalid = (u64::from(lease.generation.wrapping_add(1))) << 32;
        let _ = self.state.compare_exchange(
            Self::pack(lease),
            invalid,
            std::sync::atomic::Ordering::AcqRel,
            std::sync::atomic::Ordering::Acquire,
        );
    }

    /// Checks if the given adapter lease is still the current one.
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

/// Daemon entry point. Runs the main supervisor loop and exits on error.
fn main() {
    if let Err(error) = run() {
        eprintln!("cleverestrickyd: {error}");
        process::exit(1);
    }
}

/// Initializes the daemon, spawns worker threads, and supervises the Android adapter.
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
    write_process_identity(&config_root, "daemon.pid", process::id());
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
                write_process_identity(&config_root, "adapter.pid", adapter.id());
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

/// Determines the module directory from the first argument or the executable's parent.
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

/// Validates that the module directory is not a symlink and contains required files.
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

/// Ensures the given path is a regular file and not a symlink.
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

/// Validates that a backend auth value is a 64-character lowercase hex string with at least one non-zero byte.
fn valid_backend_auth_value(value: &str) -> bool {
    value.len() == BACKEND_AUTH_HEX_BYTES
        && value
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
        && value.bytes().any(|byte| byte != b'0')
}

/// Retrieves and validates the backend authentication capability from the environment.
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

/// Hardens the daemon process with prctl to track parent death and disable debugging.
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

/// Spawns the Android adapter process using app_process with the service APK.
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

/// Spawns the backend process and returns the child handle along with the IPC socket pair.
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

/// Sets the close-on-exec flag for the given file descriptor.
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

/// Duplicates the source FD to the fixed backend broker FD slot and clears close-on-exec.
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

/// Spawns the keybox broker and reports transport failures back to the child-owning supervisor.
fn spawn_keybox_broker(
    broker: UnixStream,
    broker_root: Arc<TrustedDir>,
    failure_tx: mpsc::SyncSender<io::Error>,
) -> io::Result<thread::JoinHandle<()>> {
    thread::Builder::new()
        .name("ct-keybox-broker".to_string())
        .spawn(move || {
            if let Err(error) = keybox_file_broker::serve(broker, &broker_root) {
                let _ = failure_tx.send(error);
            }
        })
}

/// Runs a single backend instance, monitoring it until exit or adapter change.
fn run_backend_once(
    module_dir: &Path,
    lease: AdapterLease,
    adapter_identity: &AdapterIdentity,
    root: Arc<TrustedDir>,
) -> io::Result<BackendRunOutcome> {
    let (mut child, broker) = spawn_backend(module_dir, lease.pid)?;
    write_process_identity(&root, "backend.pid", child.id());
    let broker_root = Arc::clone(&root);
    let (broker_failure_tx, broker_failure_rx) = mpsc::sync_channel(1);
    let broker_thread = match spawn_keybox_broker(broker, broker_root, broker_failure_tx) {
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
            break Ok(BackendRunOutcome::AdapterChanged);
        }
        if let Ok(error) = broker_failure_rx.try_recv() {
            let _ = child.kill();
            let _ = child.wait();
            break Ok(BackendRunOutcome::Exited(format!(
                "keybox broker failed: {error}"
            )));
        }
        match child.try_wait() {
            Ok(Some(status)) => {
                break Ok(BackendRunOutcome::Exited(format!(
                    "backend exited with {status}"
                )))
            }
            Ok(None) => {}
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                break Err(error);
            }
        }
        thread::sleep(ADAPTER_POLL_INTERVAL);
    };
    let _ = root.unlink_file("backend.pid");
    broker_thread
        .join()
        .map_err(|_| io::Error::other("keybox broker thread panicked"))?;
    outcome
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct BackendRetryPlan {
    rapid_failures: u32,
    delay: Duration,
    circuit_open: bool,
}

/// Computes a retry plan for the backend with exponential backoff and circuit breaking.
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

/// Computes a retry plan for the adapter with exponential backoff and circuit breaking.
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

/// Supervises the backend process, restarting it with backoff on failures.
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

/// Spawns worker threads to handle file capability requests from the adapter.
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

/// Accepts and handles capability IPC requests from the adapter in a worker loop.
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

/// Handles a single capability request (ping or file write) from a client.
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

#[derive(Clone)]
struct CachedManifest {
    manifest: cleverestricky_integrity_core::IntegrityManifest,
    public_key_fingerprint: [u8; 32],
    allow_unsigned: bool,
}

/// Serves WebUI IPC requests, relaying them to the registered adapter and handling integrity checks.
fn serve_web(
    listener: UnixListener,
    adapter_identity: Arc<AdapterIdentity>,
    module_dir: Arc<PathBuf>,
) -> io::Result<()> {
    let mut adapter: Option<RegisteredAdapter> = None;
    let mut relay_buffer = vec![0u8; STREAM_COPY_BYTES];
    let cached_manifest: Arc<std::sync::RwLock<Option<CachedManifest>>> =
        Arc::new(std::sync::RwLock::new(None));
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
            OP_INTEGRITY_VERIFY_FULL
                if header.flags == 0
                    && (header.payload_len == 0
                        || header.payload_len == 32
                        || header.payload_len == 33) =>
            {
                let mut public_key = cleverestricky_integrity_core::TRUSTED_PUBLIC_KEY;
                let mut allow_unsigned = false;
                if header.payload_len >= 33 {
                    let mut buf = vec![0u8; header.payload_len as usize];
                    if client.read_exact(&mut buf).is_err() {
                        continue;
                    }
                    public_key.copy_from_slice(&buf[..32]);
                    allow_unsigned = buf[32] != 0;
                } else if header.payload_len == 32 && client.read_exact(&mut public_key).is_err() {
                    continue;
                }
                handle_integrity_verify_full(
                    &mut client,
                    &module_dir,
                    &cached_manifest,
                    &public_key,
                    allow_unsigned,
                );
            }
            OP_INTEGRITY_VERIFY_FILE
                if header.flags == 0
                    && header.payload_len > 0
                    && header.payload_len <= MAX_FRAME_BYTES =>
            {
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
            OP_INTEGRITY_DELETE_MODULE
                if integrity_delete_request_authorized(header, peer_lease) =>
            {
                handle_integrity_delete_module(&mut client, &module_dir);
            }
            _ => {
                let _ = reply_text_error(&mut client, header.opcode, "unsupported IPC operation");
            }
        }
    }
}

/// Handles a full integrity verification request by loading and verifying the manifest.
fn handle_integrity_verify_full(
    client: &mut UnixStream,
    module_dir: &Path,
    cached_manifest: &std::sync::RwLock<Option<CachedManifest>>,
    public_key: &[u8; 32],
    allow_unsigned: bool,
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
    let manifest_str = match read_manifest_bounded(fs::File::from(manifest_fd)) {
        Ok(manifest) => manifest,
        Err(e) => {
            let _ = reply_text_error(
                client,
                OP_INTEGRITY_VERIFY_FULL,
                &format!("failed to read manifest: {e}"),
            );
            return;
        }
    };

    let manifest =
        match cleverestricky_integrity_core::IntegrityManifest::parse_and_verify_with_policy(
            &manifest_str,
            public_key,
            allow_unsigned,
        ) {
            Ok(m) => m,
            Err(e) => {
                let _ = write_integrity_violation(
                    client,
                    OP_INTEGRITY_VERIFY_FULL,
                    &format!("signature invalid: {e}"),
                );
                return;
            }
        };

    let result = cleverestricky_integrity_core::verify_full(raw_dir_fd, &manifest);
    if result.is_pass() {
        if let Ok(mut lock) = cached_manifest.write() {
            *lock = Some(CachedManifest {
                manifest,
                public_key_fingerprint: public_key_fingerprint(public_key),
                allow_unsigned,
            });
        }
        let _ = write_frame(client, OP_INTEGRITY_VERIFY_FULL, 0, &[0]);
    } else {
        let mut msg = String::new();
        for v in result.violations() {
            msg.push_str(&v.to_string());
            msg.push('\n');
        }
        let _ = write_integrity_violation(client, OP_INTEGRITY_VERIFY_FULL, &msg);
    }
}

/// Handles a single-file integrity verification request using a cached or freshly loaded manifest.
fn handle_integrity_verify_file(
    client: &mut UnixStream,
    module_dir: &Path,
    cached_manifest: &std::sync::RwLock<Option<CachedManifest>>,
    payload: &[u8],
) {
    let (public_key, allow_unsigned, relative_path) = if payload.len() >= 34
        && (payload[32] == 0 || payload[32] == 1)
    {
        let key: &[u8; 32] = match payload[..32].try_into() {
            Ok(k) => k,
            Err(_) => {
                let _ = reply_text_error(client, OP_INTEGRITY_VERIFY_FILE, "invalid key length");
                return;
            }
        };
        let allow = payload[32] == 1;
        let path = match std::str::from_utf8(&payload[33..]) {
            Ok(p) => p,
            Err(_) => {
                let _ = reply_text_error(client, OP_INTEGRITY_VERIFY_FILE, "invalid utf-8 path");
                return;
            }
        };
        (key, allow, path)
    } else if payload.len() > 32 {
        let key: &[u8; 32] = match payload[..32].try_into() {
            Ok(k) => k,
            Err(_) => {
                let _ = reply_text_error(client, OP_INTEGRITY_VERIFY_FILE, "invalid key length");
                return;
            }
        };
        let path = match std::str::from_utf8(&payload[32..]) {
            Ok(p) => p,
            Err(_) => {
                let _ = reply_text_error(client, OP_INTEGRITY_VERIFY_FILE, "invalid utf-8 path");
                return;
            }
        };
        (key, false, path)
    } else {
        let path = match std::str::from_utf8(payload) {
            Ok(p) => p,
            Err(_) => {
                let _ = reply_text_error(client, OP_INTEGRITY_VERIFY_FILE, "invalid utf-8 path");
                return;
            }
        };
        (
            &cleverestricky_integrity_core::TRUSTED_PUBLIC_KEY,
            false,
            path,
        )
    };

    let manifest = match cached_manifest_for_key(cached_manifest, public_key, allow_unsigned) {
        Some(manifest) => manifest,
        None => {
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
            let manifest_str = match read_manifest_bounded(fs::File::from(manifest_fd)) {
                Ok(manifest) => manifest,
                Err(e) => {
                    let _ = reply_text_error(
                        client,
                        OP_INTEGRITY_VERIFY_FILE,
                        &format!("failed to read manifest: {e}"),
                    );
                    return;
                }
            };

            let m =
                match cleverestricky_integrity_core::IntegrityManifest::parse_and_verify_with_policy(
                    &manifest_str,
                    public_key,
                    allow_unsigned,
                ) {
                    Ok(m) => m,
                    Err(e) => {
                        let _ = write_integrity_violation(
                            client,
                            OP_INTEGRITY_VERIFY_FILE,
                            &format!("signature invalid: {e}"),
                        );
                        return;
                    }
                };
            if let Ok(mut lock) = cached_manifest.write() {
                *lock = Some(CachedManifest {
                    manifest: m.clone(),
                    public_key_fingerprint: public_key_fingerprint(public_key),
                    allow_unsigned,
                });
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
        let _ = write_integrity_violation(client, OP_INTEGRITY_VERIFY_FILE, &msg);
    }
}

/// Reads a manifest through a hard stream bound so a growing file cannot exhaust memory.
fn read_manifest_bounded<R: Read>(reader: R) -> io::Result<String> {
    let mut manifest = String::new();
    reader
        .take((MAX_MANIFEST_BYTES + 1) as u64)
        .read_to_string(&mut manifest)?;
    if manifest.len() > MAX_MANIFEST_BYTES {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "manifest exceeds size limit",
        ));
    }
    Ok(manifest)
}

/// Returns a stable, non-reversible cache identity for a public key.
fn public_key_fingerprint(public_key: &[u8; 32]) -> [u8; 32] {
    Sha256::digest(public_key).into()
}

/// Retrieves a cached manifest only when it was authenticated under the current verification policy.
fn cached_manifest_for_key(
    cached_manifest: &std::sync::RwLock<Option<CachedManifest>>,
    public_key: &[u8; 32],
    allow_unsigned: bool,
) -> Option<cleverestricky_integrity_core::IntegrityManifest> {
    let fingerprint = public_key_fingerprint(public_key);
    cached_manifest.read().ok().and_then(|cached| {
        cached
            .as_ref()
            .filter(|entry| {
                entry.public_key_fingerprint == fingerprint
                    && entry.allow_unsigned == allow_unsigned
            })
            .map(|entry| entry.manifest.clone())
    })
}

/// Restricts destructive module deletion to the active adapter and an empty request frame.
fn integrity_delete_request_authorized(
    header: FrameHeader,
    peer_lease: Option<AdapterLease>,
) -> bool {
    peer_lease.is_some() && header.flags == 0 && header.payload_len == 0
}

/// Sends a confirmed integrity violation as a verdict rather than an operational error.
fn write_integrity_violation(
    client: &mut UnixStream,
    opcode: u16,
    message: &str,
) -> io::Result<()> {
    let message = message.as_bytes();
    let mut payload = Vec::with_capacity(1 + message.len().min(MAX_FRAME_BYTES - 1));
    payload.push(1);
    payload.extend_from_slice(&message[..message.len().min(MAX_FRAME_BYTES - 1)]);
    write_frame(client, opcode, 0, &payload)
}

/// Handles a module deletion request by wiping the module directory and rebooting.
fn handle_integrity_delete_module(client: &mut UnixStream, module_dir: &Path) {
    if let Err(error) = delete_dir_contents_safe(module_dir) {
        let _ = reply_error(client, OP_INTEGRITY_DELETE_MODULE, &error);
        return;
    }
    let _ = write_frame(client, OP_INTEGRITY_DELETE_MODULE, 0, &[0]);
    let _ = Command::new("/system/bin/reboot")
        .status()
        .or_else(|_| Command::new("reboot").status());
}

#[cfg(unix)]
fn delete_dir_descriptor_safe(dir_fd: RawFd) -> io::Result<()> {
    let entries = cleverestricky_integrity_core::safe_fd::list_directory_at(dir_fd)?;
    for (name, is_dir) in entries {
        let c_name = std::ffi::CString::new(name.as_bytes())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid filename"))?;
        if is_dir {
            let sub_fd = unsafe {
                libc::openat(
                    dir_fd,
                    c_name.as_ptr(),
                    libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                )
            };
            if sub_fd < 0 {
                let err = io::Error::last_os_error();
                if err.raw_os_error() == Some(libc::ELOOP) {
                    if unsafe { libc::unlinkat(dir_fd, c_name.as_ptr(), 0) } < 0 {
                        return Err(io::Error::last_os_error());
                    }
                    continue;
                }
                return Err(err);
            }
            let res = delete_dir_descriptor_safe(sub_fd);
            unsafe { libc::close(sub_fd) };
            res?;
            if unsafe { libc::unlinkat(dir_fd, c_name.as_ptr(), libc::AT_REMOVEDIR) } < 0 {
                return Err(io::Error::last_os_error());
            }
        } else if unsafe { libc::unlinkat(dir_fd, c_name.as_ptr(), 0) } < 0 {
            return Err(io::Error::last_os_error());
        }
    }
    Ok(())
}

#[cfg(unix)]
fn delete_dir_contents_safe(dir: &Path) -> io::Result<()> {
    let dir_str = dir
        .to_str()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid directory path"))?;
    let dir_fd = cleverestricky_integrity_core::safe_fd::open_dir_nofollow(dir_str)?;
    delete_dir_descriptor_safe(cleverestricky_integrity_core::safe_fd::get_raw_fd(&dir_fd))?;
    fs::remove_dir(dir)
}

#[cfg(not(unix))]
fn delete_dir_contents_safe(dir: &Path) -> io::Result<()> {
    for entry in fs::read_dir(dir)? {
        let path = entry?.path();
        let metadata = fs::symlink_metadata(&path)?;
        if metadata.is_dir() {
            delete_dir_contents_safe(&path)?;
        } else {
            fs::remove_file(&path)?;
        }
    }
    fs::remove_dir(dir)
}

/// Forwards a web request to the registered adapter and relays the response back to the client.
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

/// Replies to a request with an error frame derived from an IO error.
fn reply_error(stream: &mut UnixStream, opcode: u16, error: &io::Error) -> io::Result<()> {
    reply_text_error(stream, opcode, &error.to_string())
}

/// Replies to a request with an error frame containing the given message.
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
    use std::io::{Cursor, Read, Write};
    use std::sync::atomic::{AtomicU64, Ordering};

    struct TestRoot {
        path: PathBuf,
    }

    impl TestRoot {
        /// Creates a new temporary test root directory.
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

        /// Opens the test root as a TrustedDir.
        fn trusted(&self) -> TrustedDir {
            TrustedDir::open(&self.path).unwrap()
        }
    }

    impl Drop for TestRoot {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.path);
        }
    }

    /// Constructs a configuration file write payload with path and body.
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

    /// Reads a frame header and payload from the stream.
    fn read_payload(stream: &mut UnixStream, max: usize) -> (FrameHeader, Vec<u8>) {
        let header = read_header_bounded(stream, max).unwrap();
        let mut body = vec![0u8; header.payload_len];
        stream.read_exact(&mut body).unwrap();
        (header, body)
    }

    /// Tests that a web request can trigger a file write without deadlock.
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
    fn integrity_delete_requires_active_adapter_and_empty_unflagged_frame() {
        let lease = AdapterLease {
            pid: 123,
            generation: 1,
        };
        let valid = FrameHeader {
            opcode: OP_INTEGRITY_DELETE_MODULE,
            flags: 0,
            payload_len: 0,
        };
        assert!(integrity_delete_request_authorized(valid, Some(lease)));
        assert!(!integrity_delete_request_authorized(valid, None));
        assert!(!integrity_delete_request_authorized(
            FrameHeader { flags: 1, ..valid },
            Some(lease)
        ));
        assert!(!integrity_delete_request_authorized(
            FrameHeader {
                payload_len: 1,
                ..valid
            },
            Some(lease)
        ));
    }

    #[test]
    fn manifest_reader_accepts_limit_and_rejects_over_limit() {
        let exact = vec![b'a'; MAX_MANIFEST_BYTES];
        assert_eq!(
            read_manifest_bounded(Cursor::new(exact)).unwrap().len(),
            MAX_MANIFEST_BYTES
        );
        let oversized = vec![b'a'; MAX_MANIFEST_BYTES + 1];
        assert_eq!(
            read_manifest_bounded(Cursor::new(oversized))
                .unwrap_err()
                .kind(),
            io::ErrorKind::InvalidData
        );
    }

    #[test]
    fn manifest_cache_is_bound_to_verification_policy() {
        let first_key = [0x11; 32];
        let second_key = [0x22; 32];
        let manifest = cleverestricky_integrity_core::IntegrityManifest {
            version: 1,
            entries: Vec::new(),
        };
        let cache = std::sync::RwLock::new(Some(CachedManifest {
            manifest,
            public_key_fingerprint: public_key_fingerprint(&first_key),
            allow_unsigned: true,
        }));

        assert!(cached_manifest_for_key(&cache, &first_key, true).is_some());
        assert!(cached_manifest_for_key(&cache, &first_key, false).is_none());
        assert!(cached_manifest_for_key(&cache, &second_key, true).is_none());
    }

    #[test]
    fn recursive_module_delete_propagates_errors() {
        let test = TestRoot::new();
        let nested = test.path.join("nested");
        fs::create_dir(&nested).unwrap();
        fs::write(nested.join("payload"), b"data").unwrap();
        delete_dir_contents_safe(&test.path).unwrap();
        assert!(!test.path.exists());

        assert_eq!(
            delete_dir_contents_safe(&test.path).unwrap_err().kind(),
            io::ErrorKind::NotFound
        );
    }

    #[test]
    fn integrity_violation_uses_verdict_payload_without_error_flag() {
        let (mut client, mut server) = UnixStream::pair().unwrap();
        write_integrity_violation(&mut server, OP_INTEGRITY_VERIFY_FILE, "hash mismatch").unwrap();
        let (header, payload) = read_payload(&mut client, MAX_FRAME_BYTES);

        assert_eq!(header.flags, 0);
        assert_eq!(payload[0], 1);
        assert_eq!(&payload[1..], b"hash mismatch");
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
    fn keybox_broker_failure_is_reported_to_child_owner() {
        let test = TestRoot::new();
        let root = Arc::new(test.trusted());
        let (mut client, broker) = UnixStream::pair().unwrap();
        let (failure_tx, failure_rx) = mpsc::sync_channel(1);
        let handle = spawn_keybox_broker(broker, root, failure_tx).unwrap();

        write_header(
            &mut client,
            FrameHeader {
                opcode: keybox_file_broker::OP_KEYBOX_BROKER_OPEN,
                flags: 0,
                payload_len: keybox_file_broker::MAX_REQUEST_BYTES + 1,
            },
        )
        .unwrap();

        let error = failure_rx
            .recv_timeout(Duration::from_secs(1))
            .expect("broker failure should reach the child-owning supervisor");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        handle.join().unwrap();
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

    #[test]
    fn process_identity_records_bind_pid_to_linux_start_ticks() {
        let mut fields = vec!["0"; 20];
        fields[0] = "S";
        fields[19] = "987654";
        let stat = format!("42 (daemon worker) {}", fields.join(" "));
        assert_eq!(parse_process_start_ticks(&stat), Some(987654));
        assert_eq!(parse_process_start_ticks("42 malformed"), None);

        let pid = process::id();
        let record = process_identity_record(pid).expect("current process identity");
        let values: Vec<&str> = record.split_ascii_whitespace().collect();
        assert_eq!(values.len(), 2);
        assert_eq!(values[0], pid.to_string());
        assert!(values[1].parse::<u64>().is_ok_and(|ticks| ticks > 0));
    }
}
