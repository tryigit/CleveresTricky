use crate::manifest::{IntegrityManifest, ManifestEntry};
use crate::safe_fd;
use sha2::{Digest, Sha256};
use std::fmt;
use std::fs::File;
use std::io::{self, Read};

#[cfg(unix)]
use std::os::unix::io::RawFd;

#[cfg(not(unix))]
use std::os::windows::io::RawHandle as RawFd;

use zeroize::Zeroize;

const CHUNK_SIZE: usize = 8192;

const IGNORED_FILES: &[&str] = &[
    "disable",
    "remove",
    "update",
    "tampered",
    "*.sha256",
    "supervisor.pid",
    "daemon.pid",
    "adapter.pid",
    "backend.pid",
    "module.prop",
    "sepolicy.rule",
    "integrity_manifest.json",
    "skip_mount",
    ".replace",
    "boot_props_mode",
    "drm_packages.txt",
    "identity_target.txt",
    "target.txt",
    "security_patch.txt",
    "policy_state_v2.json",
    "spoof_build_vars",
];

/// Checks if a file name should be ignored during verification.
fn is_ignored(name: &str) -> bool {
    for ignored in IGNORED_FILES {
        if ignored.ends_with(".sha256") {
            if name.ends_with(".sha256") {
                return true;
            }
        } else if name == *ignored {
            return true;
        }
    }
    false
}

#[derive(Debug)]
pub enum Violation {
    ManifestSignatureInvalid,
    ManifestMalformed(String),
    FileMissing {
        path: String,
    },
    FileModified {
        path: String,
        expected: [u8; 32],
        actual: [u8; 32],
    },
    FileUnexpected {
        path: String,
    },
    FileIsSymlink {
        path: String,
    },
    FileWrongType {
        path: String,
    },
    PathTraversal {
        path: String,
    },
    FileSizeChanged {
        path: String,
    },
    TocTouDetected {
        path: String,
    },
    IoError {
        path: String,
        detail: String,
    },
}

impl fmt::Display for Violation {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Violation::ManifestSignatureInvalid => write!(f, "Manifest signature is invalid"),
            Violation::ManifestMalformed(e) => write!(f, "Manifest is malformed: {}", e),
            Violation::FileMissing { path } => write!(f, "File missing: {}", path),
            Violation::FileModified {
                path,
                expected,
                actual,
            } => {
                write!(
                    f,
                    "File modified: {} (expected: {:?}, actual: {:?})",
                    path, expected, actual
                )
            }
            Violation::FileUnexpected { path } => write!(f, "Unexpected file found: {}", path),
            Violation::FileIsSymlink { path } => write!(f, "File is a symlink: {}", path),
            Violation::FileWrongType { path } => write!(f, "File has wrong type: {}", path),
            Violation::PathTraversal { path } => write!(f, "Path traversal attempt: {}", path),
            Violation::FileSizeChanged { path } => {
                write!(f, "File size changed during verification: {}", path)
            }
            Violation::TocTouDetected { path } => {
                write!(f, "TOCTOU vulnerability detected: {}", path)
            }
            Violation::IoError { path, detail } => write!(f, "IO error on {}: {}", path, detail),
        }
    }
}

pub enum VerifyResult {
    Pass,
    Fail(Vec<Violation>),
}

impl VerifyResult {
    /// Returns true if verification passed with no violations.
    pub fn is_pass(&self) -> bool {
        matches!(self, VerifyResult::Pass)
    }

    /// Returns the list of violations, or an empty slice if verification passed.
    pub fn violations(&self) -> &[Violation] {
        match self {
            VerifyResult::Pass => &[],
            VerifyResult::Fail(v) => v,
        }
    }
}

/// Computes the SHA256 hash of a file, verifying size matches and zeroizing buffers.
fn hash_file_safe(fd: RawFd, expected_size: u64, path: &str) -> Result<[u8; 32], Violation> {
    let dup_fd = safe_fd::duplicate_fd(fd).map_err(|e| Violation::IoError {
        path: path.to_string(),
        detail: e.to_string(),
    })?;

    let mut file = File::from(dup_fd);
    let mut hasher = Sha256::new();
    let mut buffer = [0u8; CHUNK_SIZE];
    let mut bytes_read_total = 0u64;

    loop {
        match file.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => {
                bytes_read_total += n as u64;
                if bytes_read_total > expected_size {
                    buffer.zeroize();
                    return Err(Violation::FileSizeChanged {
                        path: path.to_string(),
                    });
                }
                hasher.update(&buffer[..n]);
            }
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => {
                buffer.zeroize();
                return Err(Violation::IoError {
                    path: path.to_string(),
                    detail: e.to_string(),
                });
            }
        }
    }

    buffer.zeroize();

    if bytes_read_total != expected_size {
        return Err(Violation::FileSizeChanged {
            path: path.to_string(),
        });
    }

    let mut result = [0u8; 32];
    result.copy_from_slice(&hasher.finalize());
    Ok(result)
}

/// Verifies a single manifest entry, checking existence, type, and hash with TOCTOU protection.
fn verify_single_entry(dir_fd: RawFd, entry: &ManifestEntry) -> Option<Violation> {
    let owned_fd = match safe_fd::open_file_nofollow(dir_fd, &entry.path) {
        Ok(fd) => fd,
        Err(e) => {
            if e.raw_os_error() == Some(libc::ELOOP) {
                return Some(Violation::FileIsSymlink {
                    path: entry.path.clone(),
                });
            } else if e.kind() == io::ErrorKind::NotFound {
                return Some(Violation::FileMissing {
                    path: entry.path.clone(),
                });
            } else {
                return Some(Violation::IoError {
                    path: entry.path.clone(),
                    detail: e.to_string(),
                });
            }
        }
    };

    let fd = safe_fd::get_raw_fd(&owned_fd);

    let pre_stat = match safe_fd::fstat_fd(fd) {
        Ok(s) => s,
        Err(e) => {
            return Some(Violation::IoError {
                path: entry.path.clone(),
                detail: e.to_string(),
            })
        }
    };

    if !safe_fd::is_regular_file(pre_stat.mode) {
        return Some(Violation::FileWrongType {
            path: entry.path.clone(),
        });
    }

    match entry.file_type {
        crate::manifest::FileType::Executable => {
            if !safe_fd::is_executable(pre_stat.mode) {
                return Some(Violation::FileWrongType {
                    path: entry.path.clone(),
                });
            }
        }
        crate::manifest::FileType::Regular => {
            // Do not reject regular files with execute bits.
            // Android overlayfs and zip extractors frequently set +x across all files.
        }
    }

    let actual_hash = match hash_file_safe(fd, pre_stat.size, &entry.path) {
        Ok(h) => h,
        Err(v) => return Some(v),
    };

    let post_stat = match safe_fd::fstat_fd(fd) {
        Ok(s) => s,
        Err(e) => {
            return Some(Violation::IoError {
                path: entry.path.clone(),
                detail: e.to_string(),
            })
        }
    };

    if pre_stat != post_stat {
        return Some(Violation::TocTouDetected {
            path: entry.path.clone(),
        });
    }

    if actual_hash != entry.expected_sha256 {
        return Some(Violation::FileModified {
            path: entry.path.clone(),
            expected: entry.expected_sha256,
            actual: actual_hash,
        });
    }

    None
}

/// Verifies a single file given its relative path against the manifest.
pub fn verify_file(
    dir_fd: RawFd,
    manifest: &IntegrityManifest,
    relative_path: &str,
) -> VerifyResult {
    let mut violations = Vec::new();

    match manifest.entries.iter().find(|e| e.path == relative_path) {
        Some(entry) => {
            if let Some(v) = verify_single_entry(dir_fd, entry) {
                violations.push(v);
            }
        }
        None => {
            let is_root = !relative_path.contains('/');
            let is_ignored_entry = if is_root {
                is_ignored(relative_path)
            } else {
                relative_path.ends_with(".sha256")
            };
            if !is_ignored_entry {
                violations.push(Violation::FileUnexpected {
                    path: relative_path.to_string(),
                });
            }
        }
    }

    if violations.is_empty() {
        VerifyResult::Pass
    } else {
        VerifyResult::Fail(violations)
    }
}

/// Performs full verification of all manifest entries and checks for unexpected files.
pub fn verify_full(dir_fd: RawFd, manifest: &IntegrityManifest) -> VerifyResult {
    let mut violations = Vec::new();

    for entry in &manifest.entries {
        if let Some(v) = verify_single_entry(dir_fd, entry) {
            violations.push(v);
        }
    }

    /// Recursively scans a directory tree for unexpected files not in the manifest.
    fn check_dir_recursive(
        dir_fd: RawFd,
        current_path: &str,
        manifest: &IntegrityManifest,
        violations: &mut Vec<Violation>,
    ) {
        let entries = match safe_fd::list_directory_at(dir_fd) {
            Ok(e) => e,
            Err(e) => {
                violations.push(Violation::IoError {
                    path: current_path.to_string(),
                    detail: e.to_string(),
                });
                return;
            }
        };

        for (name, is_dir) in entries {
            let relative_path = if current_path.is_empty() {
                name.clone()
            } else {
                format!("{}/{}", current_path, name)
            };

            if is_dir {
                // Skip user-managed / runtime directories
                if name == "keyboxes" || name == "logs" || name == "system" {
                    continue;
                }
                // Recursively scan. Note: this opens the dir to get a fd.
                match safe_fd::open_file_nofollow(dir_fd, &name) {
                    Ok(subdir_fd) => {
                        check_dir_recursive(
                            safe_fd::get_raw_fd(&subdir_fd),
                            &relative_path,
                            manifest,
                            violations,
                        );
                    }
                    Err(e) => {
                        violations.push(Violation::IoError {
                            path: relative_path,
                            detail: e.to_string(),
                        });
                    }
                }
            } else {
                let is_in_manifest = manifest.entries.iter().any(|e| e.path == relative_path);
                if !is_in_manifest {
                    let is_root = current_path.is_empty();
                    let is_ignored_entry = if is_root {
                        is_ignored(&name)
                    } else {
                        relative_path.ends_with(".sha256")
                            || current_path == "keyboxes"
                            || current_path == "logs"
                            || current_path == "system"
                    };
                    if !is_ignored_entry {
                        violations.push(Violation::FileUnexpected {
                            path: relative_path,
                        });
                    }
                }
            }
        }
    }

    check_dir_recursive(dir_fd, "", manifest, &mut violations);

    if violations.is_empty() {
        VerifyResult::Pass
    } else {
        VerifyResult::Fail(violations)
    }
}

#[cfg(test)]
#[cfg(unix)]
mod tests {
    use super::*;
    use crate::manifest::FileType;
    use std::fs;
    #[cfg(unix)]
    use std::os::unix::fs::symlink;
    use tempfile::tempdir;

    /// Creates an empty test manifest.
    fn dummy_manifest() -> IntegrityManifest {
        IntegrityManifest {
            version: 1,
            entries: vec![],
        }
    }

    #[test]
    fn test_empty_module() {
        let dir = tempdir().unwrap();
        let dir_str = dir.path().to_str().unwrap();
        let dir_fd = safe_fd::open_dir_nofollow(dir_str).unwrap();

        let manifest = dummy_manifest();
        let res = verify_full(safe_fd::get_raw_fd(&dir_fd), &manifest);
        assert!(res.is_pass());
    }

    #[test]
    fn test_valid_file() {
        let dir = tempdir().unwrap();
        let file_path = dir.path().join("test.txt");
        fs::write(&file_path, "hello").unwrap();

        let mut hasher = Sha256::new();
        hasher.update(b"hello");
        let mut expected = [0u8; 32];
        expected.copy_from_slice(&hasher.finalize());

        let mut manifest = dummy_manifest();
        manifest.entries.push(ManifestEntry {
            path: "test.txt".to_string(),
            expected_sha256: expected,
            file_type: FileType::Regular,
        });

        let dir_str = dir.path().to_str().unwrap();
        let dir_fd = safe_fd::open_dir_nofollow(dir_str).unwrap();

        let res = verify_full(safe_fd::get_raw_fd(&dir_fd), &manifest);
        assert!(res.is_pass());
    }

    #[test]
    fn test_modified_content() {
        let dir = tempdir().unwrap();
        let file_path = dir.path().join("test.txt");
        fs::write(&file_path, "world").unwrap(); // length 5, but wrong content

        let mut hasher = Sha256::new();
        hasher.update(b"hello");
        let mut expected = [0u8; 32];
        expected.copy_from_slice(&hasher.finalize());

        let mut manifest = dummy_manifest();
        manifest.entries.push(ManifestEntry {
            path: "test.txt".to_string(),
            expected_sha256: expected,
            file_type: FileType::Regular,
        });

        let dir_str = dir.path().to_str().unwrap();
        let dir_fd = safe_fd::open_dir_nofollow(dir_str).unwrap();

        let res = verify_full(safe_fd::get_raw_fd(&dir_fd), &manifest);
        assert!(!res.is_pass());
        let violations = res.violations();
        assert_eq!(violations.len(), 1);
        assert!(matches!(violations[0], Violation::FileModified { .. }));
    }

    #[test]
    fn test_missing_file() {
        let dir = tempdir().unwrap();

        let mut manifest = dummy_manifest();
        manifest.entries.push(ManifestEntry {
            path: "missing.txt".to_string(),
            expected_sha256: [0u8; 32],
            file_type: FileType::Regular,
        });

        let dir_str = dir.path().to_str().unwrap();
        let dir_fd = safe_fd::open_dir_nofollow(dir_str).unwrap();

        let res = verify_full(safe_fd::get_raw_fd(&dir_fd), &manifest);
        assert!(!res.is_pass());
        let violations = res.violations();
        assert_eq!(violations.len(), 1);
        assert!(matches!(violations[0], Violation::FileMissing { .. }));
    }

    #[test]
    fn test_unexpected_file() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("extra.txt"), "extra").unwrap();

        let manifest = dummy_manifest();
        let dir_str = dir.path().to_str().unwrap();
        let dir_fd = safe_fd::open_dir_nofollow(dir_str).unwrap();

        let res = verify_full(safe_fd::get_raw_fd(&dir_fd), &manifest);
        assert!(!res.is_pass());
        let violations = res.violations();
        assert_eq!(violations.len(), 1);
        assert!(matches!(violations[0], Violation::FileUnexpected { .. }));
    }

    #[test]
    #[cfg(unix)]
    fn test_symlink_rejected() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("real.txt"), "data").unwrap();
        symlink("real.txt", dir.path().join("link.txt")).unwrap();

        let mut manifest = dummy_manifest();
        manifest.entries.push(ManifestEntry {
            path: "link.txt".to_string(),
            expected_sha256: [0u8; 32],
            file_type: FileType::Regular,
        });

        let dir_str = dir.path().to_str().unwrap();
        let dir_fd = safe_fd::open_dir_nofollow(dir_str).unwrap();

        let res = verify_full(safe_fd::get_raw_fd(&dir_fd), &manifest);
        assert!(!res.is_pass());
        let violations = res.violations();
        assert!(violations
            .iter()
            .any(|v| matches!(v, Violation::FileIsSymlink { .. })));
    }

    #[test]
    fn test_ignored_files() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("module.prop"), "prop").unwrap();
        fs::write(dir.path().join("custom.sha256"), "hash").unwrap();

        let manifest = dummy_manifest();
        let dir_str = dir.path().to_str().unwrap();
        let dir_fd = safe_fd::open_dir_nofollow(dir_str).unwrap();

        let res = verify_full(safe_fd::get_raw_fd(&dir_fd), &manifest);
        assert!(res.is_pass()); // ignored files don't trigger unexpected
    }

    #[test]
    fn test_nested_control_file_not_ignored() {
        let dir = tempdir().unwrap();
        let sub = dir.path().join("sub");
        fs::create_dir(&sub).unwrap();
        fs::write(sub.join("module.prop"), "prop in sub").unwrap();

        let manifest = dummy_manifest();
        let dir_str = dir.path().to_str().unwrap();
        let dir_fd = safe_fd::open_dir_nofollow(dir_str).unwrap();

        let res = verify_full(safe_fd::get_raw_fd(&dir_fd), &manifest);
        assert!(!res.is_pass());
        let violations = res.violations();
        assert_eq!(violations.len(), 1);
        assert!(matches!(violations[0], Violation::FileUnexpected { .. }));
    }
}
