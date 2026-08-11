# Changelog

## Unreleased

### Fixed

- Preserved stable isolated identities across backup and restore by materializing and synchronizing the privacy seed before export.
- Restored keyboxes, module hashes, settings, and runtime configuration as one exact state without retaining stale files or caches.
- Corrected KeyMint 4 module hash placement, preserved authorization lists, and allowed valid EC and RSA cross-algorithm certificate signing.
- Added Android 17 installation and Binder runtime support, bounded large Binder response parsing, and expanded the interception queue capacity.
- Added a privacy-only modern stable-AIDL DRM hook that pseudonymizes `deviceUniqueId` for `privacy=isolate` applications without changing DRM security level, licenses, provisioning, content keys, or Keystore DRM passthrough policy.

## V2.5.0

### Fixed

- Restored the real `keystore2` Binder hook, replaced the injector with a Rust executable, pinned LSPlt as a submodule, and added both arm64 and x86_64 builds.
- Kept key generation and all private-key operations on genuine KeyMint/StrongBox paths; certificate responses are changed only after a successful platform operation.
- Corrected certificate/private-key matching, PKCS#8 handling, patch-level parsing, Luhn generation, per-app keybox selection, Binder parcel bounds, and file-cache invalidation.
- Added independent OS/vendor/boot attestation patch policies with global and per-package rules, live reload, explicit keep/omit behavior, and date-template validation.
- Fixed telephony interception so the native bridge continues into the response phase and now parses dual-SIM indices through the AIDL interface header instead of treating the raw Parcel as a string.
- Fixed fail-open keybox activation: invalid/revoked mixed payloads and unavailable CRL data now leave keyboxes inactive.
- Fixed WebUI Host/Origin validation, stored XSS paths, malformed-body handling, unsupported toggles, editable-file allowlisting, path traversal, ZIP/CBOX bounds, and encrypted restore validation.
- Fixed module consistency verification so missing targets, malformed checksums, symbolic links, and non-regular files are rejected.
- Fixed WebUI setting synchronization, Android WebView drag and drop, multipart XML uploads, editable templates, boot-property controls, and encrypted backup coverage for local XML/CBOX files.
- Fixed native injection retry state so failed attempts recover without repeatedly registering healthy hooks; telephony responses now change only when a validated identifier is configured.
- Fixed next boot identity refresh so Build fields, fingerprint, attestation identifiers, and telephony values activate from one synchronized snapshot.
- Protected current `rkpdapp` and legacy Remote Provisioner package names, and expanded identity provider coexistence matching to `auto_pif` layouts.

### Security and performance

- Added root-only atomic configuration writes, symlink defenses, input/count/size limits, constant-time token comparison, loopback-only WebUI binding, security headers, and bounded rate/UID caches.
- Upgraded CBOX and CTSB output to v2 AES-256-GCM envelopes with PBKDF2-HMAC-SHA256 (250,000 iterations) and authenticated headers; v1 remains read-only for migration.
- Encrypted server credentials and unlocked keybox caches, disabled redirects, required HTTPS for remote sources, and validated every remote/local key before activation.
- Added a bounded Rust Binder stream parser, fail-closed native layout validation, Rust ptrace and process-memory orchestration, and a source policy that prohibits first-party C and limits first-party C++ to the required Android Binder bridge.
- Added root-authorized atomic Binder cleanup, retryable parked state, a coalesced target stack journal with a guarded call window, executable platform symbol validation, exact descriptor transfer checks, rejected-library cleanup, and chunked kernel memory copies.
- Moved Binder descriptor classification into Rust with fixed storage and descriptor reuse checks, and copied Binder response streams through a bounded kernel-validated path before parsing.
- Reduced repeated certificate parsing, PackageManager IPC, template parsing, and keybox reload work with bounded, state-consistent caches; UID targeting decisions now expire to prevent stale package reuse.
- Reduced retained WebUI and certificate-cache memory, bounded certificate and template inputs, hardened keybox verification against symbolic links, protected remote-cache deletion, and removed allocation-heavy CPU parsing from the resource view.

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

- Updated Android 12 to 16 and API 31 to 36 configuration, Java, Rust, native CI, artifact boundaries, least privilege workflow permissions, documentation, contribution rules, and regression tests.
- Removed repository-root experiments, generated binaries/screenshots, obsolete audits, duplicated implementations, and ignored tests for deleted features.

Earlier release history is available on [GitHub Releases](https://github.com/tryigit/CleveresTricky/releases).

## Granular policy state

* Added independent Device and Build, Attestation, Telephony, Region, Identity Refresh, and Security Patch controls.
* Added independent System, Vendor, and Boot patch policies with genuine, property, manual, automatic, and omit resolution.
* Preserved genuine patch authorizations during unrelated certificate modification and retained authorization list placement.
* Added named profiles, effective state inspection, runtime component status, atomic policy activation, and last known good recovery.
* Parked optional runtime work when its resolved feature is disabled and removed redundant keybox polling.
* Extended tests for patch preservation, automatic calendar resolution, feature independence, profile resolution, shared UID behavior, malformed input, recovery, and safe file handling.
