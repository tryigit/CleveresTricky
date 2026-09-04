// Additional GPLv3 section 7(b) attribution term for tryigit-owned material: see ../../NOTICE.
use std::env;
use std::fs;
use std::io::{self, Read};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};

const MODE_ENV: &str = "CLEVERES_TRICKY_PIDFD_MODE";
const PID_ENV: &str = "CLEVERES_TRICKY_PIDFD_PID";
const START_ENV: &str = "CLEVERES_TRICKY_PIDFD_START";
const SIGNAL_ENV: &str = "CLEVERES_TRICKY_PIDFD_SIGNAL";
const EXE_ENV: &str = "CLEVERES_TRICKY_PIDFD_EXE";
const COMM_ENV: &str = "CLEVERES_TRICKY_PIDFD_COMM";
const ARG_ENV: &str = "CLEVERES_TRICKY_PIDFD_ARG";
const MAX_PROC_BYTES: u64 = 16 * 1024;
const MAX_CMDLINE_BYTES: u64 = 4096;

const EXIT_OK: i32 = 0;
const EXIT_ERROR: i32 = 2;
const EXIT_STALE: i32 = 3;

pub(super) fn run_env_request_if_present() -> Option<i32> {
    let mode = env::var(MODE_ENV).ok()?;
    let result = match mode.as_str() {
        "support" => support_probe(),
        "signal" => signal_owned_process(),
        _ => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid pidfd helper mode",
        )),
    };
    Some(match result {
        Ok(true) => EXIT_OK,
        Ok(false) => EXIT_STALE,
        Err(error) => {
            eprintln!("cleverestrickyd: pidfd helper failed: {error}");
            EXIT_ERROR
        }
    })
}

fn support_probe() -> io::Result<bool> {
    let pid = std::process::id();
    let pidfd = open_pidfd(pid)?;
    send_signal(&pidfd, 0)?;
    Ok(true)
}

fn signal_owned_process() -> io::Result<bool> {
    let pid = parse_pid(&required_env(PID_ENV)?)?;
    let signal = parse_signal(&required_env(SIGNAL_ENV)?)?;
    let expected_start = optional_env(START_ENV);
    let expected_exe = optional_env(EXE_ENV);
    let expected_comm = optional_env(COMM_ENV);
    let expected_arg = optional_env(ARG_ENV);
    if expected_start.is_none()
        && expected_exe.is_none()
        && expected_comm.is_none()
        && expected_arg.is_none()
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "pidfd helper requires an identity predicate",
        ));
    }
    if signal != 0 && expected_start.is_none() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "destructive pidfd signal requires process start time",
        ));
    }

    let pidfd = match open_pidfd(pid) {
        Ok(fd) => fd,
        Err(error) if is_stale_error(&error) => return Ok(false),
        Err(error) => return Err(error),
    };

    if let Some(expected) = expected_start.as_deref() {
        if process_start_ticks(pid)?.as_deref() != Some(expected) {
            return Ok(false);
        }
    }
    if let Some(expected) = expected_exe.as_deref() {
        let actual = match fs::read_link(format!("/proc/{pid}/exe")) {
            Ok(path) => path.to_string_lossy().into_owned(),
            Err(error) if is_stale_error(&error) => return Ok(false),
            Err(error) => return Err(error),
        };
        if actual != expected && actual != format!("{expected} (deleted)") {
            return Ok(false);
        }
    }
    if let Some(expected) = expected_comm.as_deref() {
        let actual = match read_bounded(format!("/proc/{pid}/comm"), 128) {
            Ok(bytes) => String::from_utf8(bytes).map_err(|_| {
                io::Error::new(io::ErrorKind::InvalidData, "process comm is not UTF-8")
            })?,
            Err(error) if is_stale_error(&error) => return Ok(false),
            Err(error) => return Err(error),
        };
        if actual.trim_end_matches(['\n', '\r']) != expected {
            return Ok(false);
        }
    }
    if let Some(expected) = expected_arg.as_deref() {
        let bytes = match read_bounded(format!("/proc/{pid}/cmdline"), MAX_CMDLINE_BYTES) {
            Ok(bytes) => bytes,
            Err(error) if is_stale_error(&error) => return Ok(false),
            Err(error) => return Err(error),
        };
        if !bytes
            .split(|byte| *byte == 0)
            .any(|arg| arg == expected.as_bytes())
        {
            return Ok(false);
        }
    }

    match send_signal(&pidfd, signal) {
        Ok(()) => Ok(true),
        Err(error) if is_stale_error(&error) => Ok(false),
        Err(error) => Err(error),
    }
}

fn required_env(name: &str) -> io::Result<String> {
    env::var(name)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, format!("missing {name}")))
}

fn optional_env(name: &str) -> Option<String> {
    env::var(name)
        .ok()
        .filter(|value| !value.is_empty() && value != "-")
}

fn parse_pid(value: &str) -> io::Result<u32> {
    let pid = value
        .parse::<u32>()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid pid"))?;
    if pid <= 1 || pid > i32::MAX as u32 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "unsafe pid"));
    }
    Ok(pid)
}

fn parse_signal(value: &str) -> io::Result<i32> {
    let signal = value
        .parse::<i32>()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid signal"))?;
    if !matches!(signal, 0 | libc::SIGTERM | libc::SIGKILL) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "unsupported signal",
        ));
    }
    Ok(signal)
}

fn open_pidfd(pid: u32) -> io::Result<OwnedFd> {
    // SAFETY: pidfd_open takes only scalar arguments and returns a new owned file descriptor.
    let raw = unsafe { libc::syscall(libc::SYS_pidfd_open, pid as libc::pid_t, 0) } as i32;
    if raw < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: successful pidfd_open returned a fresh descriptor owned by this process.
    Ok(unsafe { OwnedFd::from_raw_fd(raw) })
}

fn send_signal(pidfd: &OwnedFd, signal: i32) -> io::Result<()> {
    // SAFETY: pidfd_send_signal receives a live pidfd, a validated signal, null siginfo, and zero flags.
    let result = unsafe {
        libc::syscall(
            libc::SYS_pidfd_send_signal,
            pidfd.as_raw_fd(),
            signal,
            std::ptr::null::<libc::siginfo_t>(),
            0,
        )
    };
    if result < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(())
    }
}

fn process_start_ticks(pid: u32) -> io::Result<Option<String>> {
    let bytes = match read_bounded(format!("/proc/{pid}/stat"), MAX_PROC_BYTES) {
        Ok(bytes) => bytes,
        Err(error) if is_stale_error(&error) => return Ok(None),
        Err(error) => return Err(error),
    };
    let stat = String::from_utf8(bytes)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "process stat is not UTF-8"))?;
    Ok(parse_start_ticks(&stat).map(str::to_owned))
}

fn parse_start_ticks(stat: &str) -> Option<&str> {
    let command_end = stat.rfind(')')?;
    stat.get(command_end + 1..)?
        .split_ascii_whitespace()
        .nth(19)
}

fn read_bounded(path: String, limit: u64) -> io::Result<Vec<u8>> {
    let mut bytes = Vec::new();
    fs::File::open(path)?
        .take(limit + 1)
        .read_to_end(&mut bytes)?;
    if bytes.len() as u64 > limit {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "proc file exceeds size limit",
        ));
    }
    Ok(bytes)
}

fn is_stale_error(error: &io::Error) -> bool {
    matches!(error.raw_os_error(), Some(libc::ENOENT) | Some(libc::ESRCH))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stat_parser_handles_spaces_and_parentheses_in_comm() {
        let mut fields = vec!["0"; 20];
        fields[0] = "S";
        fields[19] = "987654";
        let stat = format!("42 (worker ) name) {}", fields.join(" "));
        assert_eq!(parse_start_ticks(&stat), Some("987654"));
        assert_eq!(parse_start_ticks("42 malformed"), None);
    }

    #[test]
    fn pidfd_can_pin_current_process_when_supported() {
        match open_pidfd(std::process::id()) {
            Ok(fd) => send_signal(&fd, 0).expect("signal-zero through current-process pidfd"),
            Err(error) if error.raw_os_error() == Some(libc::ENOSYS) => (),
            Err(error) => panic!("pidfd_open current process failed: {error}"),
        }
    }
}
