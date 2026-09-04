// Additional GPLv3 section 7(b) attribution term for tryigit-owned material: see ../../NOTICE.
#[path = "pidfd_signal.rs"]
mod pidfd_signal;

use cleverestricky_service_core::secure_fs::TrustedDir;
use std::io::{self, Read};
use std::path::Path;

pub const MAX_FILE_BYTES: usize = 20 * 1024 * 1024;
pub const MAX_RELATIVE_PATH_BYTES: usize = 511;
const REQUEST_PREFIX_BYTES: usize = 1 + 2 + 4;
const WRITE_COMMIT_BYTES: usize = 1;
pub const MAX_REQUEST_BYTES: usize =
    REQUEST_PREFIX_BYTES + MAX_RELATIVE_PATH_BYTES + MAX_FILE_BYTES + WRITE_COMMIT_BYTES;

const CONFIG_PARENT: &str = "/data/adb";
const CONFIG_ROOT_NAME: &str = "cleverestricky";
const KEYBOX_DIRECTORY: &str = "keyboxes";
const WEBUI_DIRECTORY: &str = "webui_bridge";
const WEBUI_STAGING_DIRECTORY: &str = "staging";
const WEBUI_STAGING_PATH: &str = "webui_bridge/staging";
const WEBUI_DOWNLOAD_SUFFIX: &str = ".download";
const ACTION_WRITE: u8 = 0;
const ACTION_MKDIR: u8 = 1;
const ACTION_TOUCH: u8 = 2;
const ACTION_ROOT_VALIDATE: u8 = 3;
const ACTION_STAGE_CREATE: u8 = 4;
const ACTION_STAGE_APPEND: u8 = 5;
const WRITE_COMMIT_MARKER: u8 = 0xa5;
const FILE_MODE: u32 = 0o600;
const DIRECTORY_MODE: u32 = 0o700;

pub fn prepare_root() -> io::Result<TrustedDir> {
    if let Some(exit_code) = pidfd_signal::run_env_request_if_present() {
        std::process::exit(exit_code);
    }
    let parent = TrustedDir::open(Path::new(CONFIG_PARENT))?;
    prepare_root_from(&parent)
}

fn prepare_root_from(parent: &TrustedDir) -> io::Result<TrustedDir> {
    parent.mkdir_child(CONFIG_ROOT_NAME, DIRECTORY_MODE)
}

pub(crate) fn handle_stream_from<R: Read>(
    root: &TrustedDir,
    reader: &mut R,
    payload_len: usize,
    scratch: &mut [u8],
) -> io::Result<()> {
    if !(REQUEST_PREFIX_BYTES..=MAX_REQUEST_BYTES).contains(&payload_len) {
        return Err(invalid("invalid config file request size"));
    }
    let mut prefix = [0u8; REQUEST_PREFIX_BYTES];
    reader.read_exact(&mut prefix)?;
    let action = prefix[0];
    let path_len = u16::from_be_bytes([prefix[1], prefix[2]]) as usize;
    let declared_body_len =
        u32::from_be_bytes([prefix[3], prefix[4], prefix[5], prefix[6]]) as usize;
    if path_len > MAX_RELATIVE_PATH_BYTES
        || REQUEST_PREFIX_BYTES.saturating_add(path_len) > payload_len
    {
        return Err(invalid("invalid config file path length"));
    }
    if declared_body_len > MAX_FILE_BYTES {
        return Err(invalid("config file body exceeds bound"));
    }
    if action != ACTION_ROOT_VALIDATE && path_len == 0 {
        return Err(invalid("config file path is empty"));
    }

    let has_committed_body = matches!(action, ACTION_WRITE | ACTION_STAGE_APPEND);
    let expected_payload = if has_committed_body {
        REQUEST_PREFIX_BYTES
            .checked_add(path_len)
            .and_then(|value| value.checked_add(declared_body_len))
            .and_then(|value| value.checked_add(WRITE_COMMIT_BYTES))
            .ok_or_else(|| invalid("config file request length overflow"))?
    } else {
        if declared_body_len != 0 {
            return Err(invalid("non-write config request contains a body"));
        }
        REQUEST_PREFIX_BYTES
            .checked_add(path_len)
            .ok_or_else(|| invalid("config file request length overflow"))?
    };
    if payload_len != expected_payload {
        return Err(invalid("config file declared length does not match frame"));
    }

    let mut path_storage = [0u8; MAX_RELATIVE_PATH_BYTES];
    reader.read_exact(&mut path_storage[..path_len])?;
    let result = (|| {
        let path = std::str::from_utf8(&path_storage[..path_len])
            .map_err(|_| invalid("config file path is not valid UTF-8"))?;
        match action {
            ACTION_WRITE => {
                atomic_write_relative_from(root, path, reader, declared_body_len, scratch)
            }
            ACTION_MKDIR => mkdir_allowed(root, path),
            ACTION_TOUCH => touch_root_file(root, path),
            ACTION_ROOT_VALIDATE => {
                if path_len != 0 {
                    return Err(invalid("config root capability request rejected"));
                }
                root.sync()
            }
            ACTION_STAGE_CREATE => stage_create(root, path),
            ACTION_STAGE_APPEND => stage_append(root, path, reader, declared_body_len, scratch),
            _ => Err(invalid("unsupported config file action")),
        }
    })();
    path_storage.fill(0);
    scratch.fill(0);
    result
}

#[cfg(test)]
fn handle_from(root: &TrustedDir, request: &[u8]) -> io::Result<()> {
    let mut reader = io::Cursor::new(request);
    let mut scratch = [0u8; 64];
    handle_stream_from(root, &mut reader, request.len(), &mut scratch)
}

fn mkdir_allowed(root: &TrustedDir, path: &str) -> io::Result<()> {
    match path {
        KEYBOX_DIRECTORY => root
            .mkdir_child(KEYBOX_DIRECTORY, DIRECTORY_MODE)
            .map(|_| ()),
        WEBUI_DIRECTORY => root
            .mkdir_child(WEBUI_DIRECTORY, DIRECTORY_MODE)
            .map(|_| ()),
        WEBUI_STAGING_PATH => {
            let bridge = root.open_child(WEBUI_DIRECTORY)?;
            bridge
                .mkdir_child(WEBUI_STAGING_DIRECTORY, DIRECTORY_MODE)
                .map(|_| ())
        }
        _ => Err(invalid("config directory request rejected")),
    }
}

fn touch_root_file(root: &TrustedDir, path: &str) -> io::Result<()> {
    if path.contains('/') {
        return Err(invalid("config touch request rejected"));
    }
    validate_component(path)?;
    match root.create_new_file(path, FILE_MODE) {
        Ok(file) => {
            drop(file);
            root.sync()
        }
        Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {
            let (_, size) = root.open_file_bounded(path, 0)?;
            if size != 0 {
                return Err(invalid("config flag is not empty"));
            }
            Ok(())
        }
        Err(error) => Err(error),
    }
}

fn open_webui_staging<'a>(root: &TrustedDir, path: &'a str) -> io::Result<(TrustedDir, &'a str)> {
    let prefix = "webui_bridge/staging/";
    let name = path
        .strip_prefix(prefix)
        .ok_or_else(|| invalid("WebUI staging path rejected"))?;
    if name.contains('/') {
        return Err(invalid("WebUI staging path rejected"));
    }
    validate_webui_download_name(name)?;
    let bridge = root.open_child(WEBUI_DIRECTORY)?;
    let staging = bridge.open_child(WEBUI_STAGING_DIRECTORY)?;
    Ok((staging, name))
}

fn stage_create(root: &TrustedDir, path: &str) -> io::Result<()> {
    let (staging, name) = open_webui_staging(root, path)?;
    let file = staging.create_new_file(name, FILE_MODE)?;
    drop(file);
    staging.sync()
}

fn stage_append<R: Read>(
    root: &TrustedDir,
    path: &str,
    reader: &mut R,
    body_len: usize,
    scratch: &mut [u8],
) -> io::Result<()> {
    if body_len == 0 || body_len > scratch.len() {
        return Err(invalid("WebUI staging chunk exceeds broker scratch bound"));
    }
    let read_result = reader.read_exact(&mut scratch[..body_len]);
    if let Err(error) = read_result {
        scratch[..body_len].fill(0);
        return Err(error);
    }
    let mut marker = [0u8; 1];
    if let Err(error) = reader.read_exact(&mut marker) {
        scratch[..body_len].fill(0);
        return Err(error);
    }
    if marker[0] != WRITE_COMMIT_MARKER {
        scratch[..body_len].fill(0);
        return Err(invalid("WebUI staging chunk commit marker rejected"));
    }

    let (staging, name) = open_webui_staging(root, path)?;
    let result = staging
        .append_bounded(name, &scratch[..body_len], MAX_FILE_BYTES)
        .map(|_| ());
    scratch[..body_len].fill(0);
    result
}

fn atomic_write_relative_from<R: Read>(
    root: &TrustedDir,
    path: &str,
    reader: &mut R,
    body_len: usize,
    scratch: &mut [u8],
) -> io::Result<()> {
    let confirm = |source: &mut R| {
        let mut marker = [0u8; 1];
        source.read_exact(&mut marker)?;
        if marker[0] != WRITE_COMMIT_MARKER {
            return Err(invalid("config file commit marker rejected"));
        }
        Ok(())
    };

    let mut components = path.split('/');
    let first = components.next().unwrap_or_default();
    let second = components.next();
    let third = components.next();
    if components.next().is_some() {
        return Err(invalid("config file path depth exceeds bound"));
    }

    match (second, third) {
        (None, None) => {
            validate_component(first)?;
            root.atomic_write_from_confirmed(first, reader, body_len, FILE_MODE, scratch, confirm)
        }
        (Some(name), None) if first == KEYBOX_DIRECTORY => {
            validate_component(name)?;
            let keyboxes = root.open_child(KEYBOX_DIRECTORY)?;
            keyboxes
                .atomic_write_from_confirmed(name, reader, body_len, FILE_MODE, scratch, confirm)
        }
        (Some(directory), Some(name))
            if first == WEBUI_DIRECTORY && directory == WEBUI_STAGING_DIRECTORY =>
        {
            validate_webui_download_name(name)?;
            let bridge = root.open_child(WEBUI_DIRECTORY)?;
            let staging = bridge.open_child(WEBUI_STAGING_DIRECTORY)?;
            staging.atomic_write_from_confirmed(name, reader, body_len, FILE_MODE, scratch, confirm)
        }
        _ => Err(invalid("config file path depth exceeds bound")),
    }
}

fn validate_webui_download_name(value: &str) -> io::Result<()> {
    let id = value
        .strip_suffix(WEBUI_DOWNLOAD_SUFFIX)
        .ok_or_else(|| invalid("WebUI staging filename rejected"))?;
    if id.len() != 32
        || !id
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
    {
        return Err(invalid("WebUI staging filename rejected"));
    }
    Ok(())
}

fn validate_component(value: &str) -> io::Result<()> {
    if value.is_empty()
        || value == "."
        || value == ".."
        || value.len() > 255
        || value.contains('/')
        || value.contains('\0')
    {
        Err(invalid("invalid config file path component"))
    } else {
        Ok(())
    }
}

fn invalid(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, message)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::os::unix::fs::{symlink, PermissionsExt};
    use std::sync::atomic::{AtomicU64, Ordering};

    struct TestRoot {
        path: std::path::PathBuf,
    }

    impl TestRoot {
        fn new() -> Self {
            static COUNTER: AtomicU64 = AtomicU64::new(1);
            let path = std::env::temp_dir().join(format!(
                "ct-config-broker-{}-{}",
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

    fn request(action: u8, path: &str, body: &[u8]) -> Vec<u8> {
        request_with_declared(action, path, body.len(), body, WRITE_COMMIT_MARKER)
    }

    fn request_with_declared(
        action: u8,
        path: &str,
        declared_body_len: usize,
        body: &[u8],
        marker: u8,
    ) -> Vec<u8> {
        let path = path.as_bytes();
        let write_marker = usize::from(matches!(action, ACTION_WRITE | ACTION_STAGE_APPEND));
        let mut output =
            Vec::with_capacity(REQUEST_PREFIX_BYTES + path.len() + body.len() + write_marker);
        output.push(action);
        output.extend_from_slice(&(path.len() as u16).to_be_bytes());
        output.extend_from_slice(&(declared_body_len as u32).to_be_bytes());
        output.extend_from_slice(path);
        output.extend_from_slice(body);
        if write_marker != 0 {
            output.push(marker);
        }
        output
    }

    #[test]
    fn initializes_exact_root_capability_and_keeps_children_descriptor_relative() {
        let parent = TestRoot::new();
        let parent_capability = parent.trusted();
        let root = prepare_root_from(&parent_capability).unwrap();
        let root_path = parent.path.join(CONFIG_ROOT_NAME);
        assert!(root_path.is_dir());
        assert_eq!(
            fs::metadata(&root_path).unwrap().permissions().mode() & 0o777,
            DIRECTORY_MODE
        );
        handle_from(&root, &request(ACTION_ROOT_VALIDATE, "", b"")).unwrap();

        let moved_root = parent.path.join("moved-root");
        fs::rename(&root_path, &moved_root).unwrap();
        let outside = parent.path.join("outside");
        fs::create_dir(&outside).unwrap();
        symlink(&outside, &root_path).unwrap();

        handle_from(&root, &request(ACTION_WRITE, "settings.json", b"inside")).unwrap();
        assert_eq!(
            fs::read(moved_root.join("settings.json")).unwrap(),
            b"inside"
        );
        assert!(!outside.join("settings.json").exists());
        assert!(prepare_root_from(&parent_capability).is_err());
    }

    #[test]
    fn root_capability_action_rejects_paths_and_payloads() {
        let test = TestRoot::new();
        let root = test.trusted();
        assert!(handle_from(&root, &request(ACTION_ROOT_VALIDATE, "child", b"")).is_err());
        let invalid = request_with_declared(ACTION_ROOT_VALIDATE, "", 1, b"x", 0);
        assert!(handle_from(&root, &invalid).is_err());
    }

    #[test]
    fn writes_root_and_keybox_files_atomically_with_private_modes() {
        let test = TestRoot::new();
        let root = test.trusted();
        handle_from(&root, &request(ACTION_WRITE, "target.txt", b"one\n")).unwrap();
        handle_from(&root, &request(ACTION_MKDIR, KEYBOX_DIRECTORY, b"")).unwrap();
        handle_from(
            &root,
            &request(ACTION_WRITE, "keyboxes/device.xml", b"<xml/>"),
        )
        .unwrap();

        assert_eq!(fs::read(test.path.join("target.txt")).unwrap(), b"one\n");
        assert_eq!(
            fs::read(test.path.join(KEYBOX_DIRECTORY).join("device.xml")).unwrap(),
            b"<xml/>"
        );
        assert_eq!(
            fs::metadata(test.path.join("target.txt"))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            FILE_MODE
        );
        assert_eq!(
            fs::metadata(test.path.join(KEYBOX_DIRECTORY))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            DIRECTORY_MODE
        );
    }

    #[test]
    fn webui_staging_paths_are_whitelisted_but_adjacent_paths_are_rejected() {
        let test = TestRoot::new();
        let root = test.trusted();
        handle_from(&root, &request(ACTION_MKDIR, WEBUI_DIRECTORY, b"")).unwrap();
        handle_from(&root, &request(ACTION_MKDIR, WEBUI_STAGING_PATH, b"")).unwrap();

        let name = "0123456789abcdef0123456789abcdef.download";
        let path = format!("{WEBUI_STAGING_PATH}/{name}");
        handle_from(&root, &request(ACTION_WRITE, &path, b"response")).unwrap();
        assert_eq!(
            fs::read(
                test.path
                    .join(WEBUI_DIRECTORY)
                    .join(WEBUI_STAGING_DIRECTORY)
                    .join(name)
            )
            .unwrap(),
            b"response"
        );

        for rejected in [
            "webui_bridge/other",
            "webui_bridge/staging/not-a-stage.download",
            "webui_bridge/staging/0123456789abcdef0123456789abcdef.upload",
            "webui_bridge/staging/0123456789abcdef0123456789abcdef.download/extra",
        ] {
            assert!(handle_from(&root, &request(ACTION_WRITE, rejected, b"x")).is_err());
        }
        assert!(handle_from(&root, &request(ACTION_MKDIR, "webui_bridge/other", b"")).is_err());
    }

    #[test]
    fn webui_download_streaming_is_chunked_bounded_and_fail_closed() {
        let test = TestRoot::new();
        let root = test.trusted();
        handle_from(&root, &request(ACTION_MKDIR, WEBUI_DIRECTORY, b"")).unwrap();
        handle_from(&root, &request(ACTION_MKDIR, WEBUI_STAGING_PATH, b"")).unwrap();
        let path = "webui_bridge/staging/0123456789abcdef0123456789abcdef.download";
        handle_from(&root, &request(ACTION_STAGE_CREATE, path, b"")).unwrap();
        handle_from(&root, &request(ACTION_STAGE_APPEND, path, b"first-")).unwrap();
        handle_from(&root, &request(ACTION_STAGE_APPEND, path, b"second")).unwrap();

        let staged = test
            .path
            .join(WEBUI_DIRECTORY)
            .join(WEBUI_STAGING_DIRECTORY)
            .join("0123456789abcdef0123456789abcdef.download");
        assert_eq!(fs::read(&staged).unwrap(), b"first-second");

        let bad_marker = request_with_declared(ACTION_STAGE_APPEND, path, 3, b"bad", 0);
        assert!(handle_from(&root, &bad_marker).is_err());
        assert_eq!(fs::read(&staged).unwrap(), b"first-second");

        let too_large_for_worker_scratch = vec![0x41; 65];
        assert!(handle_from(
            &root,
            &request(ACTION_STAGE_APPEND, path, &too_large_for_worker_scratch)
        )
        .is_err());
        assert_eq!(fs::read(&staged).unwrap(), b"first-second");
    }

    #[test]
    fn streamed_write_covers_required_sizes_with_fixed_scratch() {
        for size in [0usize, 1, 1024 * 1024, 10 * 1024 * 1024, MAX_FILE_BYTES] {
            let test = TestRoot::new();
            let root = test.trusted();
            let body = vec![0xa5; size];
            let payload = request(ACTION_WRITE, "large.bin", &body);
            let mut reader = io::Cursor::new(payload.as_slice());
            let mut scratch = [0u8; 4096];
            handle_stream_from(&root, &mut reader, payload.len(), &mut scratch).unwrap();
            assert_eq!(
                fs::metadata(test.path.join("large.bin")).unwrap().len(),
                size as u64
            );
            assert!(scratch.iter().all(|byte| *byte == 0));
        }
    }

    #[test]
    fn early_eof_declared_mismatch_and_bad_commit_preserve_destination() {
        let test = TestRoot::new();
        let root = test.trusted();
        fs::write(test.path.join("state.bin"), b"old").unwrap();

        let mut early =
            request_with_declared(ACTION_WRITE, "state.bin", 4, b"abc", WRITE_COMMIT_MARKER);
        early.pop();
        let declared_frame_len = REQUEST_PREFIX_BYTES + "state.bin".len() + 4 + WRITE_COMMIT_BYTES;
        let mut reader = io::Cursor::new(early.as_slice());
        let mut scratch = [0u8; 8];
        assert!(handle_stream_from(&root, &mut reader, declared_frame_len, &mut scratch).is_err());
        assert_eq!(fs::read(test.path.join("state.bin")).unwrap(), b"old");

        let bad_marker = request_with_declared(ACTION_WRITE, "state.bin", 3, b"new", 0x00);
        assert!(handle_from(&root, &bad_marker).is_err());
        assert_eq!(fs::read(test.path.join("state.bin")).unwrap(), b"old");

        let mismatch =
            request_with_declared(ACTION_WRITE, "state.bin", 2, b"new", WRITE_COMMIT_MARKER);
        assert!(handle_from(&root, &mismatch).is_err());
        assert_eq!(fs::read(test.path.join("state.bin")).unwrap(), b"old");
        assert!(scratch.iter().all(|byte| *byte == 0));
    }

    #[test]
    fn touch_is_root_only_empty_and_idempotent() {
        let test = TestRoot::new();
        let root = test.trusted();
        let payload = request(ACTION_TOUCH, "spoof_enabled", b"");
        handle_from(&root, &payload).unwrap();
        handle_from(&root, &payload).unwrap();
        assert_eq!(
            fs::metadata(test.path.join("spoof_enabled")).unwrap().len(),
            0
        );
        assert!(handle_from(&root, &request(ACTION_TOUCH, "keyboxes/flag", b"")).is_err());
    }

    #[test]
    fn traversal_depth_symlink_and_unbounded_requests_fail_closed() {
        let test = TestRoot::new();
        fs::create_dir(test.path.join("real-keyboxes")).unwrap();
        symlink(
            test.path.join("real-keyboxes"),
            test.path.join(KEYBOX_DIRECTORY),
        )
        .unwrap();
        let root = test.trusted();

        for path in ["../outside", ".", "keyboxes/../outside", "keyboxes/a/b"] {
            assert!(handle_from(&root, &request(ACTION_WRITE, path, b"x")).is_err());
        }
        assert!(handle_from(&root, &request(ACTION_WRITE, "keyboxes/device.xml", b"x")).is_err());

        let oversized = vec![0u8; MAX_FILE_BYTES + 1];
        assert!(handle_from(&root, &request(ACTION_WRITE, "large.bin", &oversized)).is_err());
    }

    #[test]
    fn replacing_destination_with_symlink_never_writes_through_target() {
        let test = TestRoot::new();
        let outside = test.path.with_extension("outside");
        fs::write(&outside, b"outside").unwrap();
        symlink(&outside, test.path.join("target.txt")).unwrap();
        let root = test.trusted();

        handle_from(&root, &request(ACTION_WRITE, "target.txt", b"inside")).unwrap();
        assert_eq!(fs::read(&outside).unwrap(), b"outside");
        assert_eq!(fs::read(test.path.join("target.txt")).unwrap(), b"inside");
        let _ = fs::remove_file(outside);
    }
}
