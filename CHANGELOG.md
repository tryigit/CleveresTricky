# Changelog

## V2.5.0

### Fixed

- Restored the real native `keystore2` injector and Binder hook, pinned LSPlt as a submodule, and added both arm64 and x86_64 builds.
- Kept key generation and all private-key operations on genuine KeyMint/StrongBox paths; certificate responses are changed only after a successful platform operation.
- Corrected certificate/private-key matching, PKCS#8 handling, patch-level parsing, Luhn generation, per-app keybox selection, Binder parcel bounds, and file-cache invalidation.
- Added independent OS/vendor/boot attestation patch policies with global and per-package rules, live reload, explicit keep/omit behavior, and date-template validation.
- Fixed telephony interception so the native bridge continues into the response phase and now parses dual-SIM indices through the AIDL interface header instead of treating the raw Parcel as a string.
- Fixed fail-open keybox activation: invalid/revoked mixed payloads and unavailable CRL data now leave keyboxes inactive.
- Fixed WebUI Host/Origin validation, stored XSS paths, malformed-body handling, unsupported toggles, editable-file allowlisting, path traversal, ZIP/CBOX bounds, and encrypted restore validation.
- Fixed module consistency verification so missing targets, malformed checksums, symbolic links, and non-regular files are rejected.
- Fixed WebUI setting synchronization, Android WebView drag and drop, multipart XML uploads, editable templates, boot-property controls, and encrypted backup coverage for local XML/CBOX files.
- Fixed native injection retry state so failed attempts recover without repeatedly registering healthy hooks; telephony responses now change only when a validated identifier is configured.

### Security and performance

- Added root-only atomic configuration writes, symlink defenses, input/count/size limits, constant-time token comparison, loopback-only WebUI binding, security headers, and bounded rate/UID caches.
- Upgraded CBOX and CTSB output to v2 AES-256-GCM envelopes with PBKDF2-HMAC-SHA256 (250,000 iterations) and authenticated headers; v1 remains read-only for migration.
- Encrypted server credentials and unlocked keybox caches, disabled redirects, required HTTPS for remote sources, and validated every remote/local key before activation.
- Added a bounded Rust Binder stream parser and fail-closed native layout validation; native code builds with warnings as errors and hardened visibility/linker settings.
- Reduced repeated certificate parsing, PackageManager IPC, template parsing, and keybox reload work with bounded, state-consistent caches; UID targeting decisions now expire to prevent stale package reuse.
- Reduced retained WebUI and certificate-cache memory, bounded certificate and template inputs, hardened keybox verification against symbolic links, and protected remote-cache deletion.

### Removed or changed

- Retired deprecated synthetic provisioning paths, obsolete utilities, bundled test key material, and unsupported legacy options.
- Added safe coexistence with process-scoped PIF modules by leaving external fingerprint configuration untouched.
- Remote Key Provisioning now always remains on Android's genuine implementation.
- Added user-controlled RKP generated-key passthrough and package-scoped DRM passthrough instead of restoring synthetic RKP/DRM implementations.
- `spoof_build_vars` now accepts only fields consumed by attestation or optional telephony interception; arbitrary `ro.*` entries are rejected.
- `app_config` now has exactly three columns: package, identity template, and keybox filename.
- The legacy trailing `!` target syntax is migrated to normal certificate substitution; it no longer requests software key generation.
- Minimal profile now enables certificate safe mode; Default restores targeted mode with revocation checks.
- Replaced unverifiable server-verdict and Device Recall guarantees with the constraints documented by Google and AOSP.

### Build and maintenance

- Updated Android 12–16/API 31–36 configuration, Java/Rust/native CI, artifact boundaries, least-privilege workflow permissions, documentation, contribution rules, and regression tests.
- Removed repository-root experiments, generated binaries/screenshots, obsolete audits, duplicated implementations, and ignored tests for deleted features.

Earlier release history is available on [GitHub Releases](https://github.com/tryigit/CleveresTricky/releases).
