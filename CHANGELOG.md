# Changelog

## V2.7.4

- **Keystore & Attestation:**
  - Preserved caller-selected AttestKey hardware signatures (`setAttestKeyAlias`) and leaf-only metadata, eliminating cross-sign certificate mismatches and multi-tier key graph verification anomalies.
  - Implemented capability-aware StrongBox fallback routing: automatically and cleanly rejects StrongBox key generation early with `HARDWARE_TYPE_UNAVAILABLE` when no dedicated StrongBox keybox is present, preventing hardware alias collisions and ensuring smooth TEE fallback.
  - Prioritized keybox security-level filtering before algorithm selection to ensure StrongBox keyboxes are properly selected when available.
  - Optimized `getKeyEntry` fast path using direct pre-encoded leaf/issuer DER caching to minimize keystore read latency and eliminate runtime X.509 parsing overhead.
- **WebUI & Interface:**
  - Added lightweight, mobile-responsive visual tags (`StrongBox` / `TEE`) next to keybox titles in the Keybox Hub inventory to clearly indicate hardware security level capabilities.
  - Localized remote server status API messages across all supported interface languages.
- **Installer & Compatibility:**
  - Automated detection and clean removal of conflicting attestation modules during installation to prevent runtime hook collisions.
- **General Improvements:**
  - Hardened module integrity validation checks against false positives during background file operations.

## V2.7.3

- **Module Security:** Implemented comprehensive runtime module integrity verification and tamper detection to safeguard critical binaries and components against unauthorized modifications.
- **Keybox & Boot Stability:** Enhanced offline keybox verification resilience during device startup, ensuring valid keyboxes activate immediately upon boot without delay.
- **Configuration & Recovery:** Improved default settings restoration to cleanly reset target scopes and configuration templates back to their initial state.
- **General Improvements:** Various minor optimizations, bug fixes, and reliability enhancements across the native runtime and service layer.
