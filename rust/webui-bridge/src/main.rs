use cleverestricky_service_core::ipc::{
    read_header, write_header, FrameHeader, FLAG_ERROR, MAX_FRAME_BYTES, OP_WEB_REQUEST,
};
use cleverestricky_service_core::secure_fs::TrustedDir;
use cleverestricky_service_core::unix_socket::{connect_abstract, DAEMON_SOCKET_NAME};
use std::env;
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process;
use std::time::{Duration, SystemTime};

const CONFIG_DIR: &str = "/data/adb/cleverestricky";
const STAGING_DIR: &str = "/data/adb/cleverestricky/webui_bridge/staging";
const MAX_REQUEST_BYTES: usize = MAX_FRAME_BYTES;
const MAX_UPLOAD_BYTES: usize = 20 * 1024 * 1024;
const MAX_DOWNLOAD_BYTES: usize = 20 * 1024 * 1024;
const MAX_RESPONSE_ENVELOPE_BYTES: usize = 512 * 1024;
const MAX_CHUNK_BYTES: usize = 64 * 1024;
const STALE_AGE: Duration = Duration::from_secs(10 * 60);

fn main() {
    if let Err(error) = run() {
        eprintln!("{error}");
        process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let staging = ensure_layout()?;
    cleanup_stale(&staging);
    let args: Vec<String> = env::args().skip(1).collect();
    let command = args.first().map(String::as_str).ok_or("Missing command")?;
    match command {
        "call" if args.len() == 3 => {
            let request = decode_base64url(&args[1], MAX_REQUEST_BYTES)?;
            call(request, parse_timeout(&args[2])?)
        }
        "call-file" if args.len() == 3 => {
            let name = stage_name(&args[1], "request")?;
            let request_result = staging
                .read_bounded(&name, MAX_REQUEST_BYTES)
                .map_err(|error| format!("Could not read staged request: {error}"));
            let _ = staging.unlink_file(&name);
            let request = request_result?;
            call(request, parse_timeout(&args[2])?)
        }
        "stage-create" if args.len() == 2 => stage_create(&staging, &args[1]),
        "stage-append" if args.len() == 4 => stage_append(&staging, &args[1], &args[2], &args[3]),
        "stage-read" if args.len() == 5 => {
            stage_read(&staging, &args[1], &args[2], &args[3], &args[4])
        }
        "stage-drop" if args.len() == 3 => stage_drop(&staging, &args[1], &args[2]),
        "export" if args.len() == 4 => export_file(&staging, &args[1], &args[2], &args[3]),
        _ => Err("Invalid command".to_string()),
    }
}

fn ensure_layout() -> Result<TrustedDir, String> {
    let config = TrustedDir::open(Path::new(CONFIG_DIR))
        .map_err(|error| format!("Configuration directory is unavailable: {error}"))?;
    let bridge = config
        .mkdir_child("webui_bridge", 0o700)
        .map_err(|error| format!("Could not secure WebUI bridge directory: {error}"))?;
    bridge
        .mkdir_child("staging", 0o700)
        .map_err(|error| format!("Could not secure WebUI staging directory: {error}"))
}

fn parse_timeout(value: &str) -> Result<Duration, String> {
    let milliseconds = value.parse::<u64>().map_err(|_| "Invalid timeout")?;
    if !(1000..=120_000).contains(&milliseconds) {
        return Err("Invalid timeout".to_string());
    }
    Ok(Duration::from_millis(milliseconds))
}

fn random_id() -> Result<String, String> {
    let mut random = [0u8; 16];
    File::open("/dev/urandom")
        .and_then(|mut file| file.read_exact(&mut random))
        .map_err(|error| format!("Secure randomness is unavailable: {error}"))?;
    let alphabet = b"0123456789abcdef";
    let mut output = String::with_capacity(32);
    for byte in random {
        output.push(alphabet[(byte >> 4) as usize] as char);
        output.push(alphabet[(byte & 0x0f) as usize] as char);
    }
    Ok(output)
}

fn validate_id(value: &str) -> Result<&str, String> {
    if value.len() == 32
        && value
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
    {
        Ok(value)
    } else {
        Err("Invalid staging identifier".to_string())
    }
}

fn validate_kind(value: &str) -> Result<(&str, usize), String> {
    match value {
        "request" => Ok(("request", MAX_REQUEST_BYTES)),
        "upload" => Ok(("upload", MAX_UPLOAD_BYTES)),
        "download" => Ok(("download", MAX_DOWNLOAD_BYTES)),
        "export" => Ok(("export", MAX_DOWNLOAD_BYTES)),
        _ => Err("Invalid staging kind".to_string()),
    }
}

fn stage_name(id: &str, kind: &str) -> Result<String, String> {
    validate_id(id)?;
    let (extension, _) = validate_kind(kind)?;
    Ok(format!("{id}.{extension}"))
}

fn call(mut request: Vec<u8>, timeout: Duration) -> Result<(), String> {
    if request.is_empty() || request.len() > MAX_REQUEST_BYTES {
        request.fill(0);
        return Err("Request size is outside the supported range".to_string());
    }
    {
        let request_text = std::str::from_utf8(&request)
            .map_err(|_| "Request is not valid UTF-8")?
            .trim();
        if !request_text.starts_with('{') || !request_text.ends_with('}') {
            request.fill(0);
            return Err("Request envelope is invalid".to_string());
        }
    }

    let result = (|| -> Result<(), String> {
        let mut stream = connect_abstract(DAEMON_SOCKET_NAME)
            .map_err(|error| format!("Native WebUI service is unavailable: {error}"))?;
        stream
            .set_read_timeout(Some(timeout))
            .map_err(|error| format!("Could not set WebUI read timeout: {error}"))?;
        stream
            .set_write_timeout(Some(timeout))
            .map_err(|error| format!("Could not set WebUI write timeout: {error}"))?;
        write_header(
            &mut stream,
            FrameHeader {
                opcode: OP_WEB_REQUEST,
                flags: 0,
                payload_len: request.len(),
            },
        )
        .map_err(|error| format!("Could not send WebUI request header: {error}"))?;
        stream
            .write_all(&request)
            .map_err(|error| format!("Could not send WebUI request: {error}"))?;
        stream
            .flush()
            .map_err(|error| format!("Could not flush WebUI request: {error}"))?;

        let response = read_header(&mut stream)
            .map_err(|error| format!("Could not read WebUI response header: {error}"))?;
        if response.opcode != OP_WEB_REQUEST {
            return Err("Native WebUI service returned an unexpected opcode".to_string());
        }
        if response.payload_len > MAX_RESPONSE_ENVELOPE_BYTES {
            return Err("Native WebUI response envelope is too large".to_string());
        }
        let mut response_bytes = vec![0u8; response.payload_len];
        let read_result = stream
            .read_exact(&mut response_bytes)
            .map_err(|error| format!("Could not read WebUI response: {error}"));
        if let Err(error) = read_result {
            response_bytes.fill(0);
            return Err(error);
        }
        let response_result = if response.flags == FLAG_ERROR {
            Err(format!(
                "Native WebUI service rejected the request: {}",
                String::from_utf8_lossy(&response_bytes)
            ))
        } else if response.flags != 0 {
            Err("Native WebUI service returned unsupported response flags".to_string())
        } else {
            let text = std::str::from_utf8(&response_bytes)
                .map_err(|_| "Bridge response is not valid UTF-8")?;
            print!("{text}");
            Ok(())
        };
        response_bytes.fill(0);
        response_result
    })();
    request.fill(0);
    result
}

fn stage_create(staging: &TrustedDir, kind: &str) -> Result<(), String> {
    let (extension, _) = validate_kind(kind)?;
    if extension == "download" {
        return Err("Download stages are service-owned".to_string());
    }
    for _ in 0..8 {
        let id = random_id()?;
        let name = stage_name(&id, extension)?;
        match staging.create_new_file(&name, 0o600) {
            Ok(file) => {
                drop(file);
                println!("{id}");
                return Ok(());
            }
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(format!("Could not create staging file: {error}")),
        }
    }
    Err("Could not allocate staging identifier".to_string())
}

fn stage_append(staging: &TrustedDir, kind: &str, id: &str, encoded: &str) -> Result<(), String> {
    let (_, limit) = validate_kind(kind)?;
    if kind == "download" {
        return Err("Download stages are service-owned".to_string());
    }
    let mut chunk = decode_base64url(encoded, MAX_CHUNK_BYTES)?;
    if chunk.is_empty() {
        return Err("Empty staging chunk".to_string());
    }
    let name = stage_name(validate_id(id)?, kind)?;
    let result = staging
        .append_bounded(&name, &chunk, limit)
        .map(|_| ())
        .map_err(|error| format!("Could not append staging data: {error}"));
    chunk.fill(0);
    result
}

fn stage_read(
    staging: &TrustedDir,
    kind: &str,
    id: &str,
    offset_value: &str,
    length_value: &str,
) -> Result<(), String> {
    if kind != "download" {
        return Err("Only download stages can be read".to_string());
    }
    let offset = offset_value
        .parse::<u64>()
        .map_err(|_| "Invalid read offset")?;
    let length = length_value
        .parse::<usize>()
        .map_err(|_| "Invalid read length")?;
    if length == 0 || length > MAX_CHUNK_BYTES {
        return Err("Invalid read length".to_string());
    }
    let name = stage_name(validate_id(id)?, kind)?;
    let (mut bytes, _) = staging
        .read_range_bounded(&name, offset, length, MAX_DOWNLOAD_BYTES)
        .map_err(|error| format!("Could not read staged response: {error}"))?;
    let encoded = encode_base64url(&bytes);
    bytes.fill(0);
    print!("{encoded}");
    Ok(())
}

fn stage_drop(staging: &TrustedDir, kind: &str, id: &str) -> Result<(), String> {
    validate_kind(kind)?;
    let name = stage_name(validate_id(id)?, kind)?;
    staging
        .unlink_file(&name)
        .map(|_| ())
        .map_err(|error| format!("Could not remove staging file: {error}"))
}

fn export_file(
    staging: &TrustedDir,
    kind: &str,
    id: &str,
    encoded_name: &str,
) -> Result<(), String> {
    let (_, limit) = validate_kind(kind)?;
    if kind == "request" {
        return Err("Request stages cannot be exported".to_string());
    }
    let source_name = stage_name(validate_id(id)?, kind)?;
    let (mut source, source_size) = staging
        .open_file_bounded(&source_name, limit)
        .map_err(|error| format!("Could not open export source: {error}"))?;
    if source_size == 0 {
        return Err("Export source size is outside the supported range".to_string());
    }
    let filename_bytes = decode_base64url(encoded_name, 256)?;
    let filename =
        String::from_utf8(filename_bytes).map_err(|_| "Download filename is not valid UTF-8")?;
    validate_filename(&filename)?;
    let (download_path, download_dir) = select_download_dir()?;
    let (destination_name, mut output) = create_export_destination(&download_dir, &filename)?;
    let destination = download_path.join(&destination_name);
    let created_metadata = output
        .metadata()
        .map_err(|error| format!("Could not inspect download descriptor: {error}"))?;
    let export_result = (|| -> Result<(), String> {
        copy_bounded(&mut source, &mut output, limit as u64)?;
        output
            .set_permissions(fs::Permissions::from_mode(0o644))
            .map_err(|error| format!("Could not set download permissions: {error}"))?;
        output
            .sync_all()
            .map_err(|error| format!("Could not persist download: {error}"))?;
        let path_metadata = safe_file_metadata(&destination)?;
        if created_metadata.dev() != path_metadata.dev()
            || created_metadata.ino() != path_metadata.ino()
        {
            return Err("Download destination changed during export".to_string());
        }
        Ok(())
    })();
    if let Err(error) = export_result {
        drop(output);
        remove_if_same_file(&download_dir, &destination_name, &created_metadata);
        return Err(error);
    }
    drop(output);
    let _ = staging.unlink_file(&source_name);
    println!("{}", destination.display());
    Ok(())
}

fn select_download_dir() -> Result<(PathBuf, TrustedDir), String> {
    let candidate = PathBuf::from("/storage/emulated/0/Download");
    let directory = TrustedDir::open(&candidate)
        .map_err(|error| format!("Android Download directory is unavailable: {error}"))?;
    Ok((candidate, directory))
}

fn validate_filename(filename: &str) -> Result<(), String> {
    if filename.is_empty()
        || filename.len() > 128
        || filename.starts_with('.')
        || filename.chars().any(char::is_control)
    {
        return Err("Invalid download filename".to_string());
    }
    let path = Path::new(filename);
    if path.file_name().and_then(|value| value.to_str()) != Some(filename)
        || filename == "."
        || filename == ".."
    {
        return Err("Invalid download filename".to_string());
    }
    Ok(())
}

fn create_export_destination(
    directory: &TrustedDir,
    filename: &str,
) -> Result<(String, File), String> {
    for attempt in 0..16 {
        let name = if attempt == 0 {
            filename.to_string()
        } else {
            suffixed_filename(filename, &random_id()?[..8])
        };
        match directory.create_new_file(&name, 0o600) {
            Ok(file) => return Ok((name, file)),
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(format!("Could not create download: {error}")),
        }
    }
    Err("Could not allocate a unique download filename".to_string())
}

fn suffixed_filename(filename: &str, suffix: &str) -> String {
    let path = Path::new(filename);
    let stem = path
        .file_stem()
        .and_then(|value| value.to_str())
        .unwrap_or("download");
    match path.extension().and_then(|value| value.to_str()) {
        Some(extension) if !extension.is_empty() => format!("{stem}_{suffix}.{extension}"),
        _ => format!("{stem}_{suffix}"),
    }
}

fn copy_bounded(input: &mut File, output: &mut File, limit: u64) -> Result<(), String> {
    let copied = io::copy(&mut Read::by_ref(input).take(limit + 1), output)
        .map_err(|error| format!("Could not export download: {error}"))?;
    if copied > limit {
        return Err("Export exceeded its size limit".to_string());
    }
    Ok(())
}

fn safe_file_metadata(path: &Path) -> Result<fs::Metadata, String> {
    let metadata = fs::symlink_metadata(path)
        .map_err(|error| format!("Could not inspect staging file: {error}"))?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err("Staging path is unsafe".to_string());
    }
    Ok(metadata)
}

fn remove_if_same_file(directory: &TrustedDir, name: &str, expected: &fs::Metadata) {
    let Ok((file, _)) = directory.open_file_bounded(name, MAX_DOWNLOAD_BYTES) else {
        return;
    };
    let Ok(metadata) = file.metadata() else {
        return;
    };
    if metadata.dev() == expected.dev() && metadata.ino() == expected.ino() {
        let _ = directory.unlink_file(name);
    }
}

fn cleanup_stale(staging: &TrustedDir) {
    let Ok(entries) = fs::read_dir(STAGING_DIR) else {
        return;
    };
    for entry in entries.flatten().take(1024) {
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        let Some((id, kind)) = name.split_once('.') else {
            continue;
        };
        if name.matches('.').count() != 1
            || validate_id(id).is_err()
            || validate_kind(kind).is_err()
        {
            continue;
        }
        let Ok(metadata) = fs::symlink_metadata(entry.path()) else {
            continue;
        };
        if metadata.file_type().is_symlink() || !metadata.is_file() {
            continue;
        }
        let stale = metadata
            .modified()
            .ok()
            .and_then(|modified| SystemTime::now().duration_since(modified).ok())
            .is_some_and(|age| age > STALE_AGE);
        if stale {
            let _ = staging.unlink_file(&name);
        }
    }
}

fn decode_base64url(value: &str, limit: usize) -> Result<Vec<u8>, String> {
    if value.len() > limit.saturating_mul(4).saturating_add(2) / 3 + 4 || value.len() % 4 == 1 {
        return Err("Encoded payload exceeds its size limit".to_string());
    }
    let mut output = Vec::with_capacity(value.len().saturating_mul(3) / 4);
    let mut buffer = 0u32;
    let mut bits = 0u32;
    for byte in value.bytes() {
        let decoded = match byte {
            b'A'..=b'Z' => byte - b'A',
            b'a'..=b'z' => byte - b'a' + 26,
            b'0'..=b'9' => byte - b'0' + 52,
            b'-' => 62,
            b'_' => 63,
            _ => return Err("Invalid base64url payload".to_string()),
        };
        buffer = (buffer << 6) | decoded as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            output.push(((buffer >> bits) & 0xff) as u8);
            buffer = if bits == 0 {
                0
            } else {
                buffer & ((1u32 << bits) - 1)
            };
            if output.len() > limit {
                output.fill(0);
                return Err("Decoded payload exceeds its size limit".to_string());
            }
        }
    }
    if buffer != 0 {
        output.fill(0);
        return Err("Invalid base64url padding bits".to_string());
    }
    Ok(output)
}

fn encode_base64url(bytes: &[u8]) -> String {
    let alphabet = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut output = String::with_capacity((bytes.len() * 4).div_ceil(3));
    let mut index = 0;
    while index + 3 <= bytes.len() {
        let value = ((bytes[index] as u32) << 16)
            | ((bytes[index + 1] as u32) << 8)
            | bytes[index + 2] as u32;
        output.push(alphabet[((value >> 18) & 63) as usize] as char);
        output.push(alphabet[((value >> 12) & 63) as usize] as char);
        output.push(alphabet[((value >> 6) & 63) as usize] as char);
        output.push(alphabet[(value & 63) as usize] as char);
        index += 3;
    }
    let remaining = bytes.len() - index;
    if remaining == 1 {
        let value = (bytes[index] as u32) << 16;
        output.push(alphabet[((value >> 18) & 63) as usize] as char);
        output.push(alphabet[((value >> 12) & 63) as usize] as char);
    } else if remaining == 2 {
        let value = ((bytes[index] as u32) << 16) | ((bytes[index + 1] as u32) << 8);
        output.push(alphabet[((value >> 18) & 63) as usize] as char);
        output.push(alphabet[((value >> 12) & 63) as usize] as char);
        output.push(alphabet[((value >> 6) & 63) as usize] as char);
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::symlink;
    use std::sync::atomic::{AtomicU64, Ordering};

    #[test]
    fn base64url_round_trip() {
        for value in [
            b"".as_slice(),
            b"f".as_slice(),
            b"fo".as_slice(),
            b"foo".as_slice(),
            b"\0\xffnative bridge".as_slice(),
        ] {
            let encoded = encode_base64url(value);
            assert_eq!(decode_base64url(&encoded, value.len()).unwrap(), value);
        }
    }

    #[test]
    fn base64url_rejects_noncanonical_input() {
        assert!(decode_base64url("A", 32).is_err());
        assert!(decode_base64url("AB", 32).is_err());
        assert!(decode_base64url("Zm9v=", 32).is_err());
        assert!(decode_base64url("Zm9v+", 32).is_err());
    }

    #[test]
    fn identifiers_and_filenames_are_bounded() {
        assert!(validate_id("0123456789abcdef0123456789abcdef").is_ok());
        assert!(validate_id("0123456789ABCDEF0123456789ABCDEF").is_err());
        assert!(validate_id("../../etc/passwd").is_err());
        assert_eq!(
            stage_name("0123456789abcdef0123456789abcdef", "request").unwrap(),
            "0123456789abcdef0123456789abcdef.request"
        );
        assert!(validate_filename("CleveresTricky-backup.zip").is_ok());
        assert!(validate_filename("../backup.zip").is_err());
        assert!(validate_filename(".hidden").is_err());
        assert_eq!(suffixed_filename("backup.zip", "1234"), "backup_1234.zip");
    }

    #[test]
    fn export_creation_stays_with_preopened_download_directory() {
        static COUNTER: AtomicU64 = AtomicU64::new(1);
        let base = env::temp_dir().join(format!(
            "ct-webui-export-{}-{}",
            process::id(),
            COUNTER.fetch_add(1, Ordering::Relaxed)
        ));
        let download = base.join("Download");
        let pinned = base.join("Download.pinned");
        let outside = base.join("outside");
        fs::create_dir_all(&download).unwrap();
        fs::create_dir_all(&outside).unwrap();
        let directory = TrustedDir::open(&download).unwrap();

        fs::rename(&download, &pinned).unwrap();
        symlink(&outside, &download).unwrap();

        let (name, mut file) = create_export_destination(&directory, "report.txt").unwrap();
        file.write_all(b"pinned-directory").unwrap();
        file.sync_all().unwrap();
        drop(file);

        assert_eq!(fs::read(pinned.join(&name)).unwrap(), b"pinned-directory");
        assert!(!outside.join(&name).exists());

        fs::remove_file(&download).unwrap();
        fs::remove_dir_all(&base).unwrap();
    }

    #[test]
    fn socket_frame_preserves_full_legacy_request_bound() {
        assert_eq!(MAX_FRAME_BYTES, MAX_REQUEST_BYTES);
    }
}
