use std::ffi::OsString;
use std::os::unix::ffi::OsStrExt;

#[cfg(target_os = "android")]
use std::fs::{self, OpenOptions};
#[cfg(target_os = "android")]
use std::io::{self, Write};
#[cfg(target_os = "android")]
use std::os::unix::fs::OpenOptionsExt;
#[cfg(target_os = "android")]
use std::path::Path;
#[cfg(target_os = "android")]
use std::time::{SystemTime, UNIX_EPOCH};

#[cfg(target_os = "android")]
const STATUS_DIRECTORY: &str = "/data/adb/cleverestricky";
#[cfg(target_os = "android")]
const STATUS_FILENAME: &str = "native_runtime_status";
const MAXIMUM_PID_BYTES: usize = 10;
const MAXIMUM_ENTRY_BYTES: usize = 32;
#[cfg(target_os = "android")]
const MAXIMUM_PROC_STAT_BYTES: u64 = 16 * 1024;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeRuntimeState {
    Starting,
    Active,
    Failed,
}

impl NativeRuntimeState {
    fn as_str(self) -> &'static str {
        match self {
            Self::Starting => "starting",
            Self::Active => "active",
            Self::Failed => "failed",
        }
    }
}

fn parse_target_pid(arguments: &[OsString]) -> Option<i32> {
    let raw = arguments.get(1)?.as_os_str().as_bytes();
    if raw.is_empty() || raw.len() > MAXIMUM_PID_BYTES || !raw.iter().all(u8::is_ascii_digit) {
        return None;
    }
    let value = raw.iter().try_fold(0i32, |current, digit| {
        current
            .checked_mul(10)?
            .checked_add(i32::from(digit - b'0'))
    })?;
    (value > 0).then_some(value)
}

fn sanitize_entry(arguments: &[OsString]) -> String {
    arguments
        .get(3)
        .map(|value| value.as_os_str().as_bytes())
        .filter(|value| !value.is_empty() && value.len() <= MAXIMUM_ENTRY_BYTES)
        .filter(|value| {
            value
                .iter()
                .all(|byte| byte.is_ascii_alphanumeric() || *byte == b'_')
        })
        .and_then(|value| std::str::from_utf8(value).ok())
        .unwrap_or("unknown")
        .to_owned()
}

fn parse_process_start_ticks(stat: &str) -> Option<u64> {
    let command_end = stat.rfind(')')?;
    let remainder = stat.get(command_end + 1..)?;
    remainder.split_ascii_whitespace().nth(19)?.parse().ok()
}

#[cfg(target_os = "android")]
fn read_process_start_ticks(pid: i32) -> Option<u64> {
    if pid <= 0 {
        return None;
    }
    let path = format!("/proc/{pid}/stat");
    let metadata = fs::metadata(&path).ok()?;
    if !metadata.is_file() || metadata.len() > MAXIMUM_PROC_STAT_BYTES {
        return None;
    }
    let stat = fs::read_to_string(path).ok()?;
    parse_process_start_ticks(&stat)
}

#[cfg(target_os = "android")]
fn timestamp_millis() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
}

fn encode_snapshot(
    state: NativeRuntimeState,
    pid: i32,
    start_ticks: u64,
    entry: &str,
    timestamp_ms: u128,
) -> String {
    format!(
        "version=1\nstate={}\npid={}\nstart_ticks={}\nentry={}\ntimestamp_ms={}\n",
        state.as_str(),
        pid,
        start_ticks,
        entry,
        timestamp_ms
    )
}

#[cfg(target_os = "android")]
fn write_snapshot(arguments: &[OsString], state: NativeRuntimeState) -> io::Result<()> {
    let directory = Path::new(STATUS_DIRECTORY);
    let directory_metadata = fs::symlink_metadata(directory)?;
    if directory_metadata.file_type().is_symlink() || !directory_metadata.is_dir() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "runtime status directory is not a regular directory",
        ));
    }

    let pid = parse_target_pid(arguments).unwrap_or(0);
    let start_ticks = read_process_start_ticks(pid).unwrap_or(0);
    let entry = sanitize_entry(arguments);
    let timestamp_ms = timestamp_millis();
    let content = encode_snapshot(state, pid, start_ticks, &entry, timestamp_ms);
    let temporary_name = format!(
        ".{STATUS_FILENAME}.{}.{}.tmp",
        std::process::id(),
        timestamp_ms
    );
    let temporary_path = directory.join(temporary_name);
    let final_path = directory.join(STATUS_FILENAME);

    let result = (|| {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&temporary_path)?;
        file.write_all(content.as_bytes())?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temporary_path, &final_path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary_path);
    }
    result
}

#[cfg(target_os = "android")]
pub(crate) fn record(arguments: &[OsString], state: NativeRuntimeState) {
    let _ = write_snapshot(arguments, state);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serializes_every_runtime_state() {
        assert_eq!(NativeRuntimeState::Starting.as_str(), "starting");
        assert_eq!(NativeRuntimeState::Active.as_str(), "active");
        assert_eq!(NativeRuntimeState::Failed.as_str(), "failed");
    }

    #[test]
    fn parses_process_start_time_after_parenthesized_command() {
        let mut fields = vec!["0"; 20];
        fields[0] = "S";
        fields[19] = "987654";
        let stat = format!("42 (keystore2 worker) {}", fields.join(" "));
        assert_eq!(parse_process_start_ticks(&stat), Some(987654));
        assert_eq!(parse_process_start_ticks("42 malformed"), None);
    }

    #[test]
    fn encodes_bounded_status_fields() {
        let arguments = vec![
            OsString::from("inject"),
            OsString::from("123"),
            OsString::from("/module/lib.so"),
            OsString::from("resume"),
        ];
        assert_eq!(parse_target_pid(&arguments), Some(123));
        assert_eq!(sanitize_entry(&arguments), "resume");
        let snapshot = encode_snapshot(NativeRuntimeState::Active, 123, 456, "resume", 789);
        assert!(snapshot.contains("state=active\n"));
        assert!(snapshot.contains("pid=123\n"));
        assert!(snapshot.contains("start_ticks=456\n"));
    }

    #[test]
    fn rejects_invalid_status_identity_inputs() {
        let invalid_pid = vec![
            OsString::from("inject"),
            OsString::from("12x"),
            OsString::from("/module/lib.so"),
            OsString::from("entry"),
        ];
        assert_eq!(parse_target_pid(&invalid_pid), None);

        let invalid_entry = vec![
            OsString::from("inject"),
            OsString::from("123"),
            OsString::from("/module/lib.so"),
            OsString::from("../entry"),
        ];
        assert_eq!(sanitize_entry(&invalid_entry), "unknown");

        let oversized_entry = vec![
            OsString::from("inject"),
            OsString::from("123"),
            OsString::from("/module/lib.so"),
            OsString::from("x".repeat(MAXIMUM_ENTRY_BYTES + 1)),
        ];
        assert_eq!(sanitize_entry(&oversized_entry), "unknown");
    }
}
