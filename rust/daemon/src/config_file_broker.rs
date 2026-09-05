// Additional GPLv3 section 7(b) attribution term for tryigit-owned material: see ../../NOTICE.
#[path = "pidfd_signal.rs"]
mod pidfd_signal;

use cleverestricky_service_core::secure_fs::TrustedDir;
use std::collections::HashMap;
use std::io::{self, Read};
use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

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
const ACTION_RESTORE_BEGIN: u8 = 6;
const ACTION_RESTORE_SNAPSHOT: u8 = 7;
const ACTION_RESTORE_ROLLBACK: u8 = 8;
const ACTION_RESTORE_COMMIT: u8 = 9;
const ACTION_RESTORE_ABORT: u8 = 10;
const ACTION_DELETE: u8 = 11;
const ACTION_RESTORE_EXPORT: u8 = 12;
const WRITE_COMMIT_MARKER: u8 = 0xa5;
const FILE_MODE: u32 = 0o600;
const DIRECTORY_MODE: u32 = 0o700;
const RESTORE_TOKEN_BYTES: usize = 32;
const MAX_RESTORE_SNAPSHOT_BYTES: usize = 32 * 1024 * 1024;
const MAX_GLOBAL_RESTORE_SNAPSHOT_BYTES: usize = 64 * 1024 * 1024;
const MAX_ACTIVE_RESTORE_TRANSACTIONS: usize = 4;
const MAX_RESTORE_TARGETS: usize = 512;
const RESTORE_TRANSACTION_TTL: Duration = Duration::from_secs(15 * 60);

struct RestoreOriginal {
    path: String,
    bytes: Option<Vec<u8>>,
}

impl Drop for RestoreOriginal {
    fn drop(&mut self) {
        if let Some(bytes) = self.bytes.as_mut() {
            bytes.fill(0);
        }
    }
}

struct RestoreTransaction {
    keyboxes: Option<Arc<TrustedDir>>,
    mutation_in_progress: bool,
    max_snapshot_bytes: usize,
    snapshot_bytes: usize,
    originals: Vec<RestoreOriginal>,
    touched: Instant,
}

static RESTORE_TRANSACTIONS: OnceLock<Mutex<HashMap<String, RestoreTransaction>>> = OnceLock::new();

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
            ACTION_RESTORE_BEGIN => restore_begin(root, path),
            ACTION_RESTORE_SNAPSHOT => restore_snapshot(root, path),
            ACTION_RESTORE_ROLLBACK => restore_rollback(root, path),
            ACTION_RESTORE_COMMIT => restore_commit(path),
            ACTION_RESTORE_ABORT => restore_abort(path),
            ACTION_DELETE => delete_allowed(root, path),
            ACTION_RESTORE_EXPORT => restore_export(root, path),
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
    if path.contains('\0') {
        return restore_write_from(root, path, reader, body_len, scratch);
    }
    {
        let mut transactions = restore_transactions()
            .lock()
            .map_err(|_| io::Error::other("restore transaction state poisoned"))?;
        prune_stale_restore_transactions(root, &mut transactions);
        if transactions.values().any(|transaction| {
            transaction
                .originals
                .iter()
                .any(|original| original.path == path)
        }) {
            return Err(invalid(
                "active restore target requires a transaction-scoped write",
            ));
        }
    }
    atomic_write_target_from(root, path, reader, body_len, scratch)
}

fn atomic_write_target_from<R: Read>(
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

enum RestoreTarget<'a> {
    Root(&'a str),
    Keybox(&'a str),
}

fn parse_restore_target(path: &str) -> io::Result<RestoreTarget<'_>> {
    let mut components = path.split('/');
    let first = components.next().unwrap_or_default();
    let second = components.next();
    if components.next().is_some() {
        return Err(invalid("restore target path depth exceeds bound"));
    }
    match second {
        None => {
            validate_component(first)?;
            Ok(RestoreTarget::Root(first))
        }
        Some(name) if first == KEYBOX_DIRECTORY => {
            validate_component(name)?;
            Ok(RestoreTarget::Keybox(name))
        }
        _ => Err(invalid(
            "restore target is outside an allowed capability subtree",
        )),
    }
}

fn read_optional(dir: &TrustedDir, name: &str, max_bytes: usize) -> io::Result<Option<Vec<u8>>> {
    match dir.read_bounded(name, max_bytes) {
        Ok(bytes) => Ok(Some(bytes)),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error),
    }
}

fn read_transaction_restore_target(
    root: &TrustedDir,
    transaction: &RestoreTransaction,
    path: &str,
    max_bytes: usize,
) -> io::Result<Option<Vec<u8>>> {
    match parse_restore_target(path)? {
        RestoreTarget::Root(name) => read_optional(root, name, max_bytes),
        RestoreTarget::Keybox(name) => match transaction.keyboxes.as_ref() {
            Some(keyboxes) => read_optional(keyboxes, name, max_bytes),
            None => Ok(None),
        },
    }
}

fn restore_target(root: &TrustedDir, path: &str, bytes: Option<&[u8]>) -> io::Result<()> {
    match (parse_restore_target(path)?, bytes) {
        (RestoreTarget::Root(name), Some(bytes)) => root.atomic_write(name, bytes, FILE_MODE),
        (RestoreTarget::Root(name), None) => root.unlink_file(name).and_then(|_| root.sync()),
        (RestoreTarget::Keybox(name), Some(bytes)) => {
            let keyboxes = root.mkdir_child(KEYBOX_DIRECTORY, DIRECTORY_MODE)?;
            keyboxes.atomic_write(name, bytes, FILE_MODE)
        }
        (RestoreTarget::Keybox(name), None) => match root.open_child(KEYBOX_DIRECTORY) {
            Ok(keyboxes) => keyboxes.unlink_file(name).and_then(|_| keyboxes.sync()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(error),
        },
    }
}

fn restore_transaction_target(
    root: &TrustedDir,
    transaction: &RestoreTransaction,
    path: &str,
    bytes: Option<&[u8]>,
) -> io::Result<()> {
    match (parse_restore_target(path)?, bytes) {
        (RestoreTarget::Root(name), Some(bytes)) => root.atomic_write(name, bytes, FILE_MODE),
        (RestoreTarget::Root(name), None) => root.unlink_file(name).and_then(|_| root.sync()),
        (RestoreTarget::Keybox(name), Some(bytes)) => {
            let keyboxes = transaction.keyboxes.as_ref().ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::NotFound,
                    "pinned keybox restore directory is unavailable",
                )
            })?;
            keyboxes.atomic_write(name, bytes, FILE_MODE)
        }
        (RestoreTarget::Keybox(name), None) => match transaction.keyboxes.as_ref() {
            Some(keyboxes) => keyboxes.unlink_file(name).and_then(|_| keyboxes.sync()),
            None => Ok(()),
        },
    }
}

fn atomic_write_transaction_target_from<R: Read>(
    root: &TrustedDir,
    keyboxes: Option<&TrustedDir>,
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

    match parse_restore_target(path)? {
        RestoreTarget::Root(name) => {
            root.atomic_write_from_confirmed(name, reader, body_len, FILE_MODE, scratch, confirm)
        }
        RestoreTarget::Keybox(name) => {
            let keyboxes = keyboxes.ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::NotFound,
                    "pinned keybox restore directory is unavailable",
                )
            })?;
            keyboxes
                .atomic_write_from_confirmed(name, reader, body_len, FILE_MODE, scratch, confirm)
        }
    }
}

fn delete_allowed(root: &TrustedDir, path: &str) -> io::Result<()> {
    if path.contains('\0') {
        return restore_delete(root, path);
    }
    let mut transactions = restore_transactions()
        .lock()
        .map_err(|_| io::Error::other("restore transaction state poisoned"))?;
    prune_stale_restore_transactions(root, &mut transactions);
    if transactions.values().any(|transaction| {
        transaction
            .originals
            .iter()
            .any(|original| original.path == path)
    }) {
        return Err(invalid(
            "active restore target requires a transaction-scoped delete",
        ));
    }
    restore_target(root, path, None)
}

fn restore_transactions() -> &'static Mutex<HashMap<String, RestoreTransaction>> {
    RESTORE_TRANSACTIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn parse_restore_token(value: &str) -> io::Result<&str> {
    if value.len() != RESTORE_TOKEN_BYTES
        || !value
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
    {
        return Err(invalid("restore transaction token rejected"));
    }
    Ok(value)
}

fn parse_restore_pair(value: &str) -> io::Result<(&str, &str)> {
    let (token, argument) = value
        .split_once('\0')
        .ok_or_else(|| invalid("restore transaction request is malformed"))?;
    parse_restore_token(token)?;
    if argument.is_empty() {
        return Err(invalid("restore transaction argument is empty"));
    }
    Ok((token, argument))
}

fn transaction_for_snapshotted_target<'a>(
    transactions: &'a mut HashMap<String, RestoreTransaction>,
    token: &str,
    path: &str,
) -> io::Result<&'a mut RestoreTransaction> {
    parse_restore_target(path)?;
    let transaction = transactions
        .get_mut(token)
        .ok_or_else(|| invalid("restore transaction is not active"))?;
    ensure_transaction_idle(transaction)?;
    if !transaction
        .originals
        .iter()
        .any(|original| original.path == path)
    {
        return Err(invalid("restore mutation target was not snapshotted"));
    }
    transaction.touched = Instant::now();
    Ok(transaction)
}

fn begin_streaming_restore_mutation(
    transactions: &mut HashMap<String, RestoreTransaction>,
    token: &str,
    path: &str,
) -> io::Result<Option<Arc<TrustedDir>>> {
    let target = parse_restore_target(path)?;
    let transaction = transaction_for_snapshotted_target(transactions, token, path)?;
    let keyboxes = match target {
        RestoreTarget::Root(_) => None,
        RestoreTarget::Keybox(_) => {
            Some(transaction.keyboxes.as_ref().cloned().ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::NotFound,
                    "pinned keybox restore directory is unavailable",
                )
            })?)
        }
    };
    transaction.mutation_in_progress = true;
    Ok(keyboxes)
}

fn finish_streaming_restore_mutation(token: &str) -> io::Result<()> {
    let mut transactions = restore_transactions()
        .lock()
        .map_err(|_| io::Error::other("restore transaction state poisoned"))?;
    let transaction = transactions
        .get_mut(token)
        .ok_or_else(|| invalid("restore transaction is not active"))?;
    if !transaction.mutation_in_progress {
        return Err(io::Error::other(
            "restore transaction mutation lease was not active",
        ));
    }
    transaction.mutation_in_progress = false;
    transaction.touched = Instant::now();
    Ok(())
}

fn ensure_transaction_idle(transaction: &RestoreTransaction) -> io::Result<()> {
    if transaction.mutation_in_progress {
        Err(io::Error::new(
            io::ErrorKind::WouldBlock,
            "restore transaction mutation is in progress",
        ))
    } else {
        Ok(())
    }
}

fn restore_write_from<R: Read>(
    root: &TrustedDir,
    request: &str,
    reader: &mut R,
    body_len: usize,
    scratch: &mut [u8],
) -> io::Result<()> {
    let (token, path) = parse_restore_pair(request)?;
    let keyboxes = {
        let mut transactions = restore_transactions()
            .lock()
            .map_err(|_| io::Error::other("restore transaction state poisoned"))?;
        prune_stale_restore_transactions(root, &mut transactions);
        begin_streaming_restore_mutation(&mut transactions, token, path)?
    };

    let write_result = atomic_write_transaction_target_from(
        root,
        keyboxes.as_deref(),
        path,
        reader,
        body_len,
        scratch,
    );
    let finish_result = finish_streaming_restore_mutation(token);
    match (write_result, finish_result) {
        (Ok(()), Ok(())) => Ok(()),
        (Err(error), Ok(())) => Err(error),
        (Ok(()), Err(error)) => Err(error),
        (Err(error), Err(finish_error)) => Err(io::Error::new(
            error.kind(),
            format!(
                "{error}; additionally failed to release restore mutation lease: {finish_error}"
            ),
        )),
    }
}

fn restore_delete(root: &TrustedDir, request: &str) -> io::Result<()> {
    let (token, path) = parse_restore_pair(request)?;
    let mut transactions = restore_transactions()
        .lock()
        .map_err(|_| io::Error::other("restore transaction state poisoned"))?;
    prune_stale_restore_transactions(root, &mut transactions);
    let transaction = transaction_for_snapshotted_target(&mut transactions, token, path)?;
    restore_transaction_target(root, transaction, path, None)
}

fn encode_hex(value: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(value.len() * 2);
    for byte in value {
        output.push(HEX[(byte >> 4) as usize] as char);
        output.push(HEX[(byte & 0x0f) as usize] as char);
    }
    output
}

fn export_transaction_to_root(
    root: &TrustedDir,
    token: &str,
    transaction: &RestoreTransaction,
) -> io::Result<()> {
    let mut created: Vec<String> = Vec::new();
    let mut manifest = String::new();
    for (index, original) in transaction.originals.iter().enumerate() {
        let encoded_path = encode_hex(original.path.as_bytes());
        if let Some(bytes) = original.bytes.as_deref() {
            let name = format!(".restore-recovery-{token}-{index:04}.bak");
            if let Err(error) = root.atomic_write(&name, bytes, FILE_MODE) {
                for created_name in &created {
                    let _ = root.unlink_file(created_name);
                }
                return Err(error);
            }
            created.push(name);
            manifest.push_str(&format!("{index:04}\tpresent\t{encoded_path}\n"));
        } else {
            manifest.push_str(&format!("{index:04}\tabsent\t{encoded_path}\n"));
        }
    }
    let manifest_name = format!(".restore-recovery-{token}.manifest");
    if let Err(error) = root.atomic_write(&manifest_name, manifest.as_bytes(), FILE_MODE) {
        for created_name in &created {
            let _ = root.unlink_file(created_name);
        }
        return Err(error);
    }
    root.sync()
}

fn prune_stale_restore_transactions(
    root: &TrustedDir,
    transactions: &mut HashMap<String, RestoreTransaction>,
) {
    let now = Instant::now();
    let stale: Vec<String> = transactions
        .iter()
        .filter_map(|(token, transaction)| {
            if transaction.mutation_in_progress {
                return None;
            }
            now.checked_duration_since(transaction.touched)
                .filter(|age| *age >= RESTORE_TRANSACTION_TTL)
                .map(|_| token.clone())
        })
        .collect();
    for token in stale {
        if let Some(transaction) = transactions.get(&token) {
            let _ = export_transaction_to_root(root, &token, transaction);
        }
        // The TTL is a hard capacity bound. Best-effort recovery export must never let an expired
        // transaction permanently consume one of the limited active slots.
        transactions.remove(&token);
    }
}

fn restore_begin(root: &TrustedDir, request: &str) -> io::Result<()> {
    let (token, max_snapshot) = parse_restore_pair(request)?;
    let max_snapshot_bytes = max_snapshot
        .parse::<usize>()
        .map_err(|_| invalid("restore snapshot limit rejected"))?;
    if max_snapshot_bytes > MAX_RESTORE_SNAPSHOT_BYTES {
        return Err(invalid("restore snapshot limit exceeds broker bound"));
    }
    let mut transactions = restore_transactions()
        .lock()
        .map_err(|_| io::Error::other("restore transaction state poisoned"))?;
    prune_stale_restore_transactions(root, &mut transactions);
    if transactions.contains_key(token) {
        return Err(invalid("restore transaction token already active"));
    }
    if transactions.len() >= MAX_ACTIVE_RESTORE_TRANSACTIONS {
        return Err(io::Error::other("restore transaction capacity exhausted"));
    }
    let keyboxes = match root.open_child(KEYBOX_DIRECTORY) {
        Ok(keyboxes) => Some(Arc::new(keyboxes)),
        Err(error) if error.kind() == io::ErrorKind::NotFound => None,
        Err(error) => return Err(error),
    };
    transactions.insert(
        token.to_string(),
        RestoreTransaction {
            keyboxes,
            mutation_in_progress: false,
            max_snapshot_bytes,
            snapshot_bytes: 0,
            originals: Vec::new(),
            touched: Instant::now(),
        },
    );
    Ok(())
}

fn restore_snapshot(root: &TrustedDir, request: &str) -> io::Result<()> {
    let (token, path) = parse_restore_pair(request)?;
    parse_restore_target(path)?;
    let mut transactions = restore_transactions()
        .lock()
        .map_err(|_| io::Error::other("restore transaction state poisoned"))?;
    prune_stale_restore_transactions(root, &mut transactions);
    let global_used: usize = transactions
        .values()
        .map(|transaction| transaction.snapshot_bytes)
        .sum();
    let transaction = transactions
        .get_mut(token)
        .ok_or_else(|| invalid("restore transaction is not active"))?;
    ensure_transaction_idle(transaction)?;
    if transaction.originals.len() >= MAX_RESTORE_TARGETS {
        return Err(invalid("restore transaction target count exceeds bound"));
    }
    if transaction
        .originals
        .iter()
        .any(|original| original.path == path)
    {
        return Err(invalid(
            "restore transaction target was already snapshotted",
        ));
    }
    let own_remaining = transaction
        .max_snapshot_bytes
        .checked_sub(transaction.snapshot_bytes)
        .ok_or_else(|| invalid("restore snapshot accounting underflow"))?;
    let global_remaining = MAX_GLOBAL_RESTORE_SNAPSHOT_BYTES
        .checked_sub(global_used)
        .ok_or_else(|| invalid("global restore snapshot accounting overflow"))?;
    let bytes = read_transaction_restore_target(
        root,
        transaction,
        path,
        own_remaining.min(global_remaining),
    )?;
    let added = bytes.as_ref().map_or(0, Vec::len);
    transaction.snapshot_bytes = transaction
        .snapshot_bytes
        .checked_add(added)
        .ok_or_else(|| invalid("restore snapshot accounting overflow"))?;
    transaction.originals.push(RestoreOriginal {
        path: path.to_string(),
        bytes,
    });
    transaction.touched = Instant::now();
    Ok(())
}

fn restore_rollback(root: &TrustedDir, token: &str) -> io::Result<()> {
    parse_restore_token(token)?;
    let mut transactions = restore_transactions()
        .lock()
        .map_err(|_| io::Error::other("restore transaction state poisoned"))?;
    prune_stale_restore_transactions(root, &mut transactions);
    let transaction = transactions
        .get_mut(token)
        .ok_or_else(|| invalid("restore transaction is not active"))?;
    ensure_transaction_idle(transaction)?;
    transaction.touched = Instant::now();
    let mut first_error = None;
    let mut failure_count = 0usize;
    for original in transaction.originals.iter().rev() {
        if let Err(error) =
            restore_transaction_target(root, transaction, &original.path, original.bytes.as_deref())
        {
            failure_count += 1;
            if first_error.is_none() {
                first_error = Some(error);
            }
        }
    }
    if let Some(error) = first_error {
        return Err(io::Error::new(
            error.kind(),
            format!("restore rollback failed for {failure_count} target(s): {error}"),
        ));
    }
    transactions.remove(token);
    Ok(())
}

fn restore_commit(token: &str) -> io::Result<()> {
    parse_restore_token(token)?;
    let mut transactions = restore_transactions()
        .lock()
        .map_err(|_| io::Error::other("restore transaction state poisoned"))?;
    if let Some(transaction) = transactions.get(token) {
        ensure_transaction_idle(transaction)?;
    }
    transactions.remove(token);
    Ok(())
}

fn restore_abort(token: &str) -> io::Result<()> {
    restore_commit(token)
}

fn restore_export(root: &TrustedDir, token: &str) -> io::Result<()> {
    parse_restore_token(token)?;
    let mut transactions = restore_transactions()
        .lock()
        .map_err(|_| io::Error::other("restore transaction state poisoned"))?;
    let transaction = transactions
        .get(token)
        .ok_or_else(|| invalid("restore transaction is not active"))?;
    ensure_transaction_idle(transaction)?;
    export_transaction_to_root(root, token, transaction)?;
    transactions.remove(token);
    Ok(())
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

    fn restore_pair(action: u8, token: &str, argument: &str) -> Vec<u8> {
        request(action, &format!("{token}\0{argument}"), b"")
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

    #[test]
    fn restore_transaction_mutations_require_matching_snapshot_token() {
        let test = TestRoot::new();
        let root = test.trusted();
        fs::write(test.path.join("state.txt"), b"old").unwrap();
        fs::write(test.path.join("other.txt"), b"other").unwrap();
        let token = "05000000000000000000000000000005";
        let wrong_token = "06000000000000000000000000000006";
        handle_from(&root, &restore_pair(ACTION_RESTORE_BEGIN, token, "4096")).unwrap();
        handle_from(
            &root,
            &restore_pair(ACTION_RESTORE_SNAPSHOT, token, "state.txt"),
        )
        .unwrap();

        assert!(handle_from(&root, &request(ACTION_WRITE, "state.txt", b"unscoped")).is_err());
        assert_eq!(fs::read(test.path.join("state.txt")).unwrap(), b"old");
        assert!(handle_from(
            &root,
            &request(
                ACTION_WRITE,
                &format!("{wrong_token}\0state.txt"),
                b"wrong-token",
            ),
        )
        .is_err());
        assert!(handle_from(
            &root,
            &request(
                ACTION_WRITE,
                &format!("{token}\0other.txt"),
                b"not-snapshotted"
            ),
        )
        .is_err());
        handle_from(
            &root,
            &request(ACTION_WRITE, &format!("{token}\0state.txt"), b"new"),
        )
        .unwrap();
        assert_eq!(fs::read(test.path.join("state.txt")).unwrap(), b"new");

        assert!(handle_from(&root, &request(ACTION_DELETE, "state.txt", b"")).is_err());
        handle_from(
            &root,
            &request(ACTION_DELETE, &format!("{token}\0state.txt"), b""),
        )
        .unwrap();
        assert!(!test.path.join("state.txt").exists());

        handle_from(&root, &request(ACTION_RESTORE_ROLLBACK, token, b"")).unwrap();
        assert_eq!(fs::read(test.path.join("state.txt")).unwrap(), b"old");
        assert_eq!(fs::read(test.path.join("other.txt")).unwrap(), b"other");
    }

    #[test]
    fn streamed_restore_write_releases_registry_lock_and_blocks_commit() {
        struct LockObservingReader {
            inner: io::Cursor<Vec<u8>>,
            token: &'static str,
            observed: bool,
        }

        impl std::io::Read for LockObservingReader {
            fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
                if !self.observed {
                    self.observed = true;
                    let deadline = Instant::now() + Duration::from_secs(2);
                    loop {
                        match restore_transactions().try_lock() {
                            Ok(guard) => {
                                drop(guard);
                                break;
                            }
                            Err(std::sync::TryLockError::WouldBlock)
                                if Instant::now() < deadline =>
                            {
                                std::thread::sleep(Duration::from_millis(1));
                            }
                            Err(std::sync::TryLockError::WouldBlock) => {
                                panic!("restore registry lock remained held while streaming");
                            }
                            Err(std::sync::TryLockError::Poisoned(_)) => {
                                panic!("restore registry lock is poisoned");
                            }
                        }
                    }
                    assert!(restore_commit(self.token).is_err());
                    let transactions = restore_transactions().lock().unwrap();
                    assert!(
                        transactions
                            .get(self.token)
                            .expect("transaction must stay active")
                            .mutation_in_progress
                    );
                }
                std::io::Read::read(&mut self.inner, output)
            }
        }

        let test = TestRoot::new();
        let root = test.trusted();
        fs::write(test.path.join("state.txt"), b"old").unwrap();
        let token = "07000000000000000000000000000007";
        handle_from(&root, &restore_pair(ACTION_RESTORE_BEGIN, token, "4096")).unwrap();
        handle_from(
            &root,
            &restore_pair(ACTION_RESTORE_SNAPSHOT, token, "state.txt"),
        )
        .unwrap();

        let mut reader = LockObservingReader {
            inner: io::Cursor::new(vec![b'n', b'e', b'w', WRITE_COMMIT_MARKER]),
            token,
            observed: false,
        };
        let mut scratch = [0u8; 8];
        restore_write_from(
            &root,
            &format!("{token}\0state.txt"),
            &mut reader,
            3,
            &mut scratch,
        )
        .unwrap();
        assert!(reader.observed);
        assert_eq!(fs::read(test.path.join("state.txt")).unwrap(), b"new");
        {
            let transactions = restore_transactions().lock().unwrap();
            assert!(
                !transactions
                    .get(token)
                    .expect("transaction must stay active")
                    .mutation_in_progress
            );
        }
        handle_from(&root, &request(ACTION_RESTORE_ROLLBACK, token, b"")).unwrap();
        assert_eq!(fs::read(test.path.join("state.txt")).unwrap(), b"old");
    }

    #[test]
    fn restore_transaction_rolls_back_existing_and_created_targets() {
        let test = TestRoot::new();
        let root = test.trusted();
        fs::write(test.path.join("first.txt"), b"old-first").unwrap();
        fs::create_dir(test.path.join(KEYBOX_DIRECTORY)).unwrap();
        fs::write(
            test.path.join(KEYBOX_DIRECTORY).join("device.xml"),
            b"old-keybox",
        )
        .unwrap();
        let token = "10000000000000000000000000000001";

        handle_from(&root, &restore_pair(ACTION_RESTORE_BEGIN, token, "4096")).unwrap();
        handle_from(
            &root,
            &restore_pair(ACTION_RESTORE_SNAPSHOT, token, "first.txt"),
        )
        .unwrap();
        handle_from(
            &root,
            &restore_pair(ACTION_RESTORE_SNAPSHOT, token, "keyboxes/device.xml"),
        )
        .unwrap();
        handle_from(
            &root,
            &restore_pair(ACTION_RESTORE_SNAPSHOT, token, "created.txt"),
        )
        .unwrap();
        fs::write(test.path.join("first.txt"), b"new-first").unwrap();
        fs::write(
            test.path.join(KEYBOX_DIRECTORY).join("device.xml"),
            b"new-keybox",
        )
        .unwrap();
        fs::write(test.path.join("created.txt"), b"created").unwrap();

        handle_from(&root, &request(ACTION_RESTORE_ROLLBACK, token, b"")).unwrap();
        assert_eq!(fs::read(test.path.join("first.txt")).unwrap(), b"old-first");
        assert_eq!(
            fs::read(test.path.join(KEYBOX_DIRECTORY).join("device.xml")).unwrap(),
            b"old-keybox"
        );
        assert!(!test.path.join("created.txt").exists());
    }

    #[test]
    fn restore_rollback_continues_after_an_independent_target_failure() {
        let test = TestRoot::new();
        fs::write(test.path.join("state.txt"), b"old-state").unwrap();
        fs::write(test.path.join("blocked.txt"), b"old-blocked").unwrap();
        let root = test.trusted();
        let token = "15000000000000000000000000000005";
        handle_from(&root, &restore_pair(ACTION_RESTORE_BEGIN, token, "4096")).unwrap();
        handle_from(
            &root,
            &restore_pair(ACTION_RESTORE_SNAPSHOT, token, "state.txt"),
        )
        .unwrap();
        handle_from(
            &root,
            &restore_pair(ACTION_RESTORE_SNAPSHOT, token, "blocked.txt"),
        )
        .unwrap();

        fs::write(test.path.join("state.txt"), b"new-state").unwrap();
        fs::remove_file(test.path.join("blocked.txt")).unwrap();
        fs::create_dir(test.path.join("blocked.txt")).unwrap();

        assert!(handle_from(&root, &request(ACTION_RESTORE_ROLLBACK, token, b"")).is_err());
        assert_eq!(fs::read(test.path.join("state.txt")).unwrap(), b"old-state");
        assert!(test.path.join("blocked.txt").is_dir());
        handle_from(&root, &request(ACTION_RESTORE_ABORT, token, b"")).unwrap();
    }

    #[test]
    fn restore_rollback_stays_bound_to_root_after_pathname_swap() {
        let test = TestRoot::new();
        fs::write(test.path.join("state.txt"), b"old").unwrap();
        let root = test.trusted();
        let token = "20000000000000000000000000000002";
        handle_from(&root, &restore_pair(ACTION_RESTORE_BEGIN, token, "4096")).unwrap();
        handle_from(
            &root,
            &restore_pair(ACTION_RESTORE_SNAPSHOT, token, "state.txt"),
        )
        .unwrap();

        let moved = test.path.with_extension("moved-root");
        let outside = test.path.with_extension("outside-root");
        fs::rename(&test.path, &moved).unwrap();
        fs::create_dir(&outside).unwrap();
        fs::write(outside.join("state.txt"), b"outside").unwrap();
        symlink(&outside, &test.path).unwrap();
        fs::write(moved.join("state.txt"), b"new").unwrap();

        handle_from(&root, &request(ACTION_RESTORE_ROLLBACK, token, b"")).unwrap();
        assert_eq!(fs::read(moved.join("state.txt")).unwrap(), b"old");
        assert_eq!(fs::read(outside.join("state.txt")).unwrap(), b"outside");

        let _ = fs::remove_file(&test.path);
        let _ = fs::remove_dir_all(&outside);
        let _ = fs::remove_dir_all(&moved);
    }

    #[test]
    fn keybox_parent_symlink_swap_stays_bound_to_pinned_directory() {
        let test = TestRoot::new();
        let keyboxes = test.path.join(KEYBOX_DIRECTORY);
        fs::create_dir(&keyboxes).unwrap();
        fs::write(keyboxes.join("device.xml"), b"old").unwrap();
        let root = test.trusted();
        let token = "30000000000000000000000000000003";
        handle_from(&root, &restore_pair(ACTION_RESTORE_BEGIN, token, "4096")).unwrap();
        handle_from(
            &root,
            &restore_pair(ACTION_RESTORE_SNAPSHOT, token, "keyboxes/device.xml"),
        )
        .unwrap();

        let moved = test.path.join("moved-keyboxes");
        let outside = test.path.join("outside-keyboxes");
        fs::rename(&keyboxes, &moved).unwrap();
        fs::create_dir(&outside).unwrap();
        fs::write(outside.join("device.xml"), b"outside").unwrap();
        symlink(&outside, &keyboxes).unwrap();

        handle_from(
            &root,
            &request(
                ACTION_WRITE,
                &format!("{token}\0keyboxes/device.xml"),
                b"new",
            ),
        )
        .unwrap();
        assert_eq!(fs::read(moved.join("device.xml")).unwrap(), b"new");
        assert_eq!(fs::read(outside.join("device.xml")).unwrap(), b"outside");

        handle_from(&root, &request(ACTION_RESTORE_ROLLBACK, token, b"")).unwrap();
        assert_eq!(fs::read(moved.join("device.xml")).unwrap(), b"old");
        assert_eq!(fs::read(outside.join("device.xml")).unwrap(), b"outside");
    }

    #[test]
    fn keybox_parent_real_directory_swap_cannot_redirect_transaction() {
        let test = TestRoot::new();
        let keyboxes = test.path.join(KEYBOX_DIRECTORY);
        fs::create_dir(&keyboxes).unwrap();
        fs::write(keyboxes.join("device.xml"), b"old").unwrap();
        let root = test.trusted();
        let token = "35000000000000000000000000000005";
        handle_from(&root, &restore_pair(ACTION_RESTORE_BEGIN, token, "4096")).unwrap();
        handle_from(
            &root,
            &restore_pair(ACTION_RESTORE_SNAPSHOT, token, "keyboxes/device.xml"),
        )
        .unwrap();

        let moved = test.path.join("moved-keyboxes");
        fs::rename(&keyboxes, &moved).unwrap();
        fs::create_dir(&keyboxes).unwrap();
        fs::write(keyboxes.join("device.xml"), b"replacement").unwrap();

        handle_from(
            &root,
            &request(
                ACTION_WRITE,
                &format!("{token}\0keyboxes/device.xml"),
                b"new",
            ),
        )
        .unwrap();
        assert_eq!(fs::read(moved.join("device.xml")).unwrap(), b"new");
        assert_eq!(
            fs::read(keyboxes.join("device.xml")).unwrap(),
            b"replacement"
        );

        handle_from(&root, &request(ACTION_RESTORE_ROLLBACK, token, b"")).unwrap();
        assert_eq!(fs::read(moved.join("device.xml")).unwrap(), b"old");
        assert_eq!(
            fs::read(keyboxes.join("device.xml")).unwrap(),
            b"replacement"
        );
    }

    #[test]
    fn stale_transaction_is_evicted_even_when_recovery_export_fails() {
        let test = TestRoot::new();
        let root = test.trusted();
        let token = "40000000000000000000000000000004";
        fs::create_dir(
            test.path
                .join(format!(".restore-recovery-{token}-0000.bak")),
        )
        .unwrap();
        let mut transactions = HashMap::new();
        transactions.insert(
            token.to_string(),
            RestoreTransaction {
                keyboxes: None,
                mutation_in_progress: false,
                max_snapshot_bytes: 4096,
                snapshot_bytes: 3,
                originals: vec![RestoreOriginal {
                    path: "state.txt".to_string(),
                    bytes: Some(b"old".to_vec()),
                }],
                touched: Instant::now()
                    .checked_sub(RESTORE_TRANSACTION_TTL + Duration::from_secs(1))
                    .unwrap(),
            },
        );

        prune_stale_restore_transactions(&root, &mut transactions);
        assert!(transactions.is_empty());
    }

    #[test]
    fn descriptor_relative_delete_never_follows_final_symlink() {
        let test = TestRoot::new();
        let outside = test.path.with_extension("delete-outside");
        fs::write(&outside, b"outside").unwrap();
        symlink(&outside, test.path.join("victim")).unwrap();
        let root = test.trusted();

        handle_from(&root, &request(ACTION_DELETE, "victim", b"")).unwrap();
        assert!(!test.path.join("victim").exists());
        assert_eq!(fs::read(&outside).unwrap(), b"outside");
        assert!(handle_from(&root, &request(ACTION_DELETE, "../outside", b"")).is_err());
        let _ = fs::remove_file(outside);
    }
}
