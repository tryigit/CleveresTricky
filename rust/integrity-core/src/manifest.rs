use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use std::fmt;

type HmacSha256 = Hmac<Sha256>;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum FileType {
    Regular,
    Executable,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ManifestEntryRaw {
    pub path: String,
    pub sha256: String,
    #[serde(rename = "type")]
    pub file_type: FileType,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ManifestEntry {
    pub path: String,
    pub expected_sha256: [u8; 32],
    pub file_type: FileType,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct IntegrityManifestRaw {
    pub version: u32,
    pub files: Vec<ManifestEntryRaw>,
    pub signature: String,
}

#[derive(Debug, Clone)]
pub struct IntegrityManifest {
    pub version: u32,
    pub entries: Vec<ManifestEntry>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ManifestError {
    ParseError(String),
    UnsupportedVersion,
    InvalidSignature,
    InvalidHex,
    InvalidPath,
    DuplicatePath,
}

impl fmt::Display for ManifestError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ManifestError::ParseError(msg) => write!(f, "Parse error: {}", msg),
            ManifestError::UnsupportedVersion => write!(f, "Unsupported manifest version"),
            ManifestError::InvalidSignature => write!(f, "Invalid HMAC signature"),
            ManifestError::InvalidHex => write!(f, "Invalid SHA256 hex string"),
            ManifestError::InvalidPath => write!(f, "Invalid path in manifest"),
            ManifestError::DuplicatePath => write!(f, "Duplicate path in manifest"),
        }
    }
}

fn parse_hex_sha256(hex_str: &str) -> Result<[u8; 32], ManifestError> {
    if hex_str.len() != 64 {
        return Err(ManifestError::InvalidHex);
    }
    let mut bytes = [0u8; 32];
    for i in 0..32 {
        let byte_str = &hex_str[i * 2..i * 2 + 2];
        bytes[i] = u8::from_str_radix(byte_str, 16).map_err(|_| ManifestError::InvalidHex)?;
    }
    Ok(bytes)
}

fn is_valid_path(path: &str) -> bool {
    if path.is_empty()
        || path.starts_with('/')
        || path.contains("..")
        || path.contains('\0')
        || path.contains('\\')
    {
        return false;
    }
    if path.len() > 512 {
        return false;
    }
    for component in path.split('/') {
        if component.len() > 255 {
            return false;
        }
        if component.is_empty() {
            // Consecutive slashes or trailing slash
            return false;
        }
    }
    true
}

pub fn compute_canonical_data(version: u32, files: &[ManifestEntryRaw]) -> Vec<u8> {
    let mut sorted = files.to_vec();
    sorted.sort_by(|a, b| a.path.cmp(&b.path));
    let mut data = Vec::new();
    data.extend_from_slice(format!("{}\n", version).as_bytes());
    for entry in &sorted {
        data.extend_from_slice(entry.path.as_bytes());
        data.push(b'\n');
        data.extend_from_slice(entry.sha256.to_ascii_lowercase().as_bytes());
        data.push(b'\n');
        data.extend_from_slice(match entry.file_type {
            FileType::Regular => b"regular\n",
            FileType::Executable => b"executable\n",
        });
    }
    data
}

impl IntegrityManifest {
    pub fn parse_and_verify(json_str: &str, hmac_key: &[u8]) -> Result<Self, ManifestError> {
        let raw: IntegrityManifestRaw =
            serde_json::from_str(json_str).map_err(|e| ManifestError::ParseError(e.to_string()))?;

        if raw.version != 1 {
            return Err(ManifestError::UnsupportedVersion);
        }

        // Validate signature using canonical data serialization
        let canonical_bytes = compute_canonical_data(raw.version, &raw.files);
        let mut mac =
            HmacSha256::new_from_slice(hmac_key).map_err(|_| ManifestError::InvalidSignature)?;
        mac.update(&canonical_bytes);

        let expected_sig = match parse_hex_sha256(&raw.signature) {
            Ok(s) => s,
            Err(_) => return Err(ManifestError::InvalidSignature), // Return invalid sig for bad format
        };

        if mac.verify_slice(&expected_sig).is_err() {
            return Err(ManifestError::InvalidSignature);
        }

        let mut entries = Vec::with_capacity(raw.files.len());
        let mut paths = std::collections::HashSet::new();

        for raw_entry in raw.files {
            if !is_valid_path(&raw_entry.path) {
                return Err(ManifestError::InvalidPath);
            }
            if !paths.insert(raw_entry.path.clone()) {
                return Err(ManifestError::DuplicatePath);
            }

            let expected_sha256 = parse_hex_sha256(&raw_entry.sha256)?;
            entries.push(ManifestEntry {
                path: raw_entry.path,
                expected_sha256,
                file_type: raw_entry.file_type,
            });
        }

        Ok(IntegrityManifest {
            version: raw.version,
            entries,
        })
    }
}

pub fn sign_manifest(
    version: u32,
    files: Vec<ManifestEntryRaw>,
    hmac_key: &[u8],
) -> Result<String, String> {
    let canonical_bytes = compute_canonical_data(version, &files);
    let mut mac = HmacSha256::new_from_slice(hmac_key).map_err(|e| e.to_string())?;
    mac.update(&canonical_bytes);
    let result = mac.finalize();
    let sig_bytes = result.into_bytes();

    let mut sig_hex = String::with_capacity(64);
    for byte in sig_bytes {
        sig_hex.push_str(&format!("{:02x}", byte));
    }

    let manifest = IntegrityManifestRaw {
        version,
        files,
        signature: sig_hex,
    };

    serde_json::to_string_pretty(&manifest).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    const TEST_KEY: &[u8] = b"super_secret_key_for_testing";

    fn create_test_manifest_raw() -> Vec<ManifestEntryRaw> {
        vec![
            ManifestEntryRaw {
                path: "libcleverestricky.so".to_string(),
                sha256: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                    .to_string(),
                file_type: FileType::Executable,
            },
            ManifestEntryRaw {
                path: "webroot/index.html".to_string(),
                sha256: "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
                    .to_string(),
                file_type: FileType::Regular,
            },
        ]
    }

    #[test]
    fn test_valid_manifest_roundtrip() {
        let files = create_test_manifest_raw();
        let json = sign_manifest(1, files, TEST_KEY).unwrap();
        let manifest = IntegrityManifest::parse_and_verify(&json, TEST_KEY).unwrap();
        assert_eq!(manifest.version, 1);
        assert_eq!(manifest.entries.len(), 2);
        assert_eq!(manifest.entries[0].path, "libcleverestricky.so");
    }

    #[test]
    fn test_wrong_hmac_key() {
        let files = create_test_manifest_raw();
        let json = sign_manifest(1, files, TEST_KEY).unwrap();
        let err = IntegrityManifest::parse_and_verify(&json, b"wrong_key").unwrap_err();
        assert_eq!(err, ManifestError::InvalidSignature);
    }

    #[test]
    fn test_path_validation() {
        let bad_paths: Vec<String> = vec![
            "".to_string(),
            "/absolute/path".to_string(),
            "../parent".to_string(),
            "dir/../file".to_string(),
            "null\0byte".to_string(),
            "back\\slash".to_string(),
            "double//slash".to_string(),
            "trailing/slash/".to_string(),
            "a".repeat(513),
            format!("{}/file", "a".repeat(256)),
        ];

        for path in bad_paths {
            let mut files = create_test_manifest_raw();
            files[0].path = path.clone();
            let json = sign_manifest(1, files, TEST_KEY).unwrap();
            let res = IntegrityManifest::parse_and_verify(&json, TEST_KEY);
            assert_eq!(
                res.unwrap_err(),
                ManifestError::InvalidPath,
                "Failed on path: {}",
                path
            );
        }
    }

    #[test]
    fn test_duplicate_path() {
        let mut files = create_test_manifest_raw();
        files[1].path = files[0].path.clone();
        let json = sign_manifest(1, files, TEST_KEY).unwrap();
        let res = IntegrityManifest::parse_and_verify(&json, TEST_KEY);
        assert_eq!(res.unwrap_err(), ManifestError::DuplicatePath);
    }

    #[test]
    fn test_invalid_hex() {
        let mut files = create_test_manifest_raw();
        files[0].sha256 = "invalid_hex".to_string();
        let json = sign_manifest(1, files, TEST_KEY).unwrap();
        let res = IntegrityManifest::parse_and_verify(&json, TEST_KEY);
        assert_eq!(res.unwrap_err(), ManifestError::InvalidHex);
    }

    #[test]
    fn test_empty_files() {
        let json = sign_manifest(1, vec![], TEST_KEY).unwrap();
        let manifest = IntegrityManifest::parse_and_verify(&json, TEST_KEY).unwrap();
        assert!(manifest.entries.is_empty());
    }
}
