#![forbid(unsafe_op_in_unsafe_fn)]

pub mod manifest;
pub mod safe_fd;
pub mod verifier;

pub use manifest::{FileType, IntegrityManifest, ManifestEntry, TRUSTED_PUBLIC_KEY};
pub use verifier::{verify_file, verify_full, VerifyResult, Violation};
