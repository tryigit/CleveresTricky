use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use serde::{Deserialize, Serialize};
use std::fmt;

pub const TRUSTED_PUBLIC_KEY: [u8; 32] = [
    0x9f, 0x9f, 0x8b, 0x00, 0xa8, 0xc5, 0xe3, 0xc9, 0x84, 0x9e, 0xed, 0x6c, 0x46, 0x5b, 0x1d, 0x1f,
    0x46, 0x74, 0x7d, 0x3a, 0xcb, 0xd7, 0x4a, 0xfb, 0x91, 0x29, 0x0e, 0xbc, 0x40, 0xc1, 0x87, 0x3c,
];

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
            ManifestError::InvalidSignature => write!(f, "Invalid digital signature"),
            ManifestError::InvalidHex => write!(f, "Invalid SHA256 hex string"),
            ManifestError::InvalidPath => write!(f, "Invalid path in manifest"),
            ManifestError::DuplicatePath => write!(f, "Duplicate path in manifest"),
        }
    }
}

fn parse_hex_byte(hi: u8, lo: u8) -> Result<u8, ManifestError> {
    let h = match hi {
        b'0'..=b'9' => hi - b'0',
        b'a'..=b'f' => hi - b'a' + 10,
        b'A'..=b'F' => hi - b'A' + 10,
        _ => return Err(ManifestError::InvalidHex),
    };
    let l = match lo {
        b'0'..=b'9' => lo - b'0',
        b'a'..=b'f' => lo - b'a' + 10,
        b'A'..=b'F' => lo - b'A' + 10,
        _ => return Err(ManifestError::InvalidHex),
    };
    Ok((h << 4) | l)
}

fn parse_hex_sha256(hex_str: &str) -> Result<[u8; 32], ManifestError> {
    let bytes = hex_str.as_bytes();
    if bytes.len() != 64 {
        return Err(ManifestError::InvalidHex);
    }
    let mut out = [0u8; 32];
    for i in 0..32 {
        out[i] = parse_hex_byte(bytes[i * 2], bytes[i * 2 + 1])?;
    }
    Ok(out)
}

fn parse_hex_signature(hex_str: &str) -> Result<[u8; 64], ManifestError> {
    let bytes = hex_str.as_bytes();
    if bytes.len() != 128 {
        return Err(ManifestError::InvalidSignature);
    }
    let mut out = [0u8; 64];
    for i in 0..64 {
        out[i] = parse_hex_byte(bytes[i * 2], bytes[i * 2 + 1])
            .map_err(|_| ManifestError::InvalidSignature)?;
    }
    Ok(out)
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
    pub fn parse_and_verify(json_str: &str, public_key: &[u8; 32]) -> Result<Self, ManifestError> {
        let raw: IntegrityManifestRaw =
            serde_json::from_str(json_str).map_err(|e| ManifestError::ParseError(e.to_string()))?;

        if raw.version != 1 {
            return Err(ManifestError::UnsupportedVersion);
        }

        let canonical_bytes = compute_canonical_data(raw.version, &raw.files);
        let sig_bytes = parse_hex_signature(&raw.signature)?;

        let verifying_key =
            VerifyingKey::from_bytes(public_key).map_err(|_| ManifestError::InvalidSignature)?;
        let signature = Signature::from_bytes(&sig_bytes);

        if verifying_key
            .verify_strict(&canonical_bytes, &signature)
            .is_err()
        {
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
    signing_key_seed: &[u8; 32],
) -> Result<String, String> {
    let canonical_bytes = compute_canonical_data(version, &files);
    let signing_key = SigningKey::from_bytes(signing_key_seed);
    let signature = signing_key.sign(&canonical_bytes);
    let sig_bytes = signature.to_bytes();

    let mut sig_hex = String::with_capacity(128);
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

    const TEST_DEV_SIGNING_KEY: [u8; 32] = [
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff,
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee,
        0xff, 0x00,
    ];

    fn test_verifying_key() -> [u8; 32] {
        let sk = SigningKey::from_bytes(&TEST_DEV_SIGNING_KEY);
        sk.verifying_key().to_bytes()
    }

    #[test]
    fn test_valid_manifest_roundtrip() {
        let files = create_test_manifest_raw();
        let json = sign_manifest(1, files, &TEST_DEV_SIGNING_KEY).unwrap();
        let manifest = IntegrityManifest::parse_and_verify(&json, &test_verifying_key()).unwrap();
        assert_eq!(manifest.version, 1);
        assert_eq!(manifest.entries.len(), 2);
        assert_eq!(manifest.entries[0].path, "libcleverestricky.so");
    }

    #[test]
    fn test_wrong_ed25519_key() {
        let files = create_test_manifest_raw();
        let json = sign_manifest(1, files, &TEST_DEV_SIGNING_KEY).unwrap();
        let wrong_key = [0x42u8; 32];
        let err = IntegrityManifest::parse_and_verify(&json, &wrong_key).unwrap_err();
        assert_eq!(err, ManifestError::InvalidSignature);
    }

    #[test]
    fn test_non_ascii_hex_does_not_panic() {
        assert_eq!(
            parse_hex_signature(&"ü".repeat(64)).unwrap_err(),
            ManifestError::InvalidSignature
        );
        assert_eq!(
            parse_hex_sha256(&"ö".repeat(32)).unwrap_err(),
            ManifestError::InvalidHex
        );

        let mut files = create_test_manifest_raw();
        files[0].sha256 = "ö".repeat(32);
        let json = sign_manifest(1, files, &TEST_DEV_SIGNING_KEY).unwrap();
        let res = IntegrityManifest::parse_and_verify(&json, &test_verifying_key());
        assert_eq!(res.unwrap_err(), ManifestError::InvalidHex);
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
            let json = sign_manifest(1, files, &TEST_DEV_SIGNING_KEY).unwrap();
            let res = IntegrityManifest::parse_and_verify(&json, &test_verifying_key());
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
        let json = sign_manifest(1, files, &TEST_DEV_SIGNING_KEY).unwrap();
        let res = IntegrityManifest::parse_and_verify(&json, &test_verifying_key());
        assert_eq!(res.unwrap_err(), ManifestError::DuplicatePath);
    }

    #[test]
    fn test_invalid_hex() {
        let mut files = create_test_manifest_raw();
        files[0].sha256 = "invalid_hex".to_string();
        let json = sign_manifest(1, files, &TEST_DEV_SIGNING_KEY).unwrap();
        let res = IntegrityManifest::parse_and_verify(&json, &test_verifying_key());
        assert_eq!(res.unwrap_err(), ManifestError::InvalidHex);
    }

    #[test]
    fn test_empty_files() {
        let json = sign_manifest(1, vec![], &TEST_DEV_SIGNING_KEY).unwrap();
        let manifest = IntegrityManifest::parse_and_verify(&json, &test_verifying_key()).unwrap();
        assert!(manifest.entries.is_empty());
    }
}
