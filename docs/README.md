# CleveresTricky Documentation

**Language:** **English** | [Türkçe](i18n/tr/README.md) | [简体中文](i18n/zh-CN/README.md) | [Español](i18n/es/README.md) | [Deutsch](i18n/de/README.md) | [Русский](i18n/ru/README.md) | [Bahasa Indonesia](i18n/id/README.md) | [हिन्दी](i18n/hi/README.md) | [العربية](i18n/ar/README.md)

Welcome to the CleveresTricky technical documentation. Documents are organized into three focused technical categories:

---

### 🛡️ Security & Attestation
Core cryptographic services, attestation integrity, keybox storage, and platform boundaries.

* [Attestation](security/Attestation.md) - Certificate replacement, chain validation, KeyMint/StrongBox dispatch.
* [Automatic Keybox Check](security/AutomaticKeyboxCheck.md) - Lifecycle observer and dynamic revocation checks.
* [Certificate Safe Mode](security/CertificateSafeMode.md) - Historical behavior and modern boundary scoping.
* [Encrypted Storage](security/EncryptedStorage.md) - Authenticated CBOX container format and key material security.
* [Keybox Manager](security/KeyboxManager.md) - Keybox loading, validation, and multi-key selection.
* [RKP Protection](security/RkpProtection.md) - Remote Key Provisioning caller protection and platform path preservation.
* [Security Model](security/SecurityModel.md) - Cryptographic architecture, threat boundaries, and guarantees.
* [Strong Integrity Guide](security/StrongIntegrityGuide.md) - Passing Google Play Integrity (MEETS_STRONG_INTEGRITY).

---

### 🆔 Identity & Rules
Device identity spoofing, application scoping, granular rules, and property control.

* [Application Rules](identity/ApplicationRules.md) - Granular per-app templates, keyboxes, and privacy modes.
* [Application Scope](identity/ApplicationScope.md) - Target selection between Global and targeted modes.
* [Boot Properties](identity/BootProperties.md) - Early boot property virtualization and bootloader indicator suppression.
* [Build Identity](identity/BuildIdentity.md) - Comprehensive device templates, fingerprints, and Auto Identity.
* [Identity Refresh](identity/IdentityRefresh.md) - Atomic property snapshot staging and reboot transitions.
* [Patch Levels](identity/PatchLevels.md) - Security patch date virtualization across system and vendor boundaries.
* [Profiles](identity/Profiles.md) - Reusable configuration presets and operational profiles.
* [Region Properties](identity/RegionProperties.md) - Virtualizing carrier and regulatory region attributes.
* [SpoofEngine](identity/SpoofEngine.md) - Core property substitution engine and safety controls.
* [Telephony Identity](identity/TelephonyIdentity.md) - IMEI, IMSI, ICCID, MEID, phone number, and SIM slot controls.

---

### ⚙️ System & Architecture
Runtime architecture, build requirements, diagnostics, platform coexistence, and module tools.

* [Backup & Restore](system/BackupRestore.md) - Encrypted CTSB backup archive export and import.
* [Building](system/Building.md) - Toolchain requirements, multi-target compilation, and verification.
* [Diagnostics](system/Diagnostics.md) - Troubleshooting, logs, sanitized debug summaries, and inspectors.
* [Logging & Diagnostics](LOG.md) - Diagnostic snapshots, WebUI debug logging, action report archive, and logcat.
* [DRM Passthrough & Privacy](system/DrmPassthrough.md) - Media application Keystore preservation and deviceUniqueId isolation.
* [Installer](system/Installer.md) - Module installation, environment detection, and package architecture.
* [Magisk Support](system/Magisk.md) - Headless manual configuration, directory layout, and root concealment advisories.
* [Native Architecture](system/NativeArchitecture.md) - Daemon supervision, IPC transport, and interceptors.
* [Performance & Memory](system/Performance.md) - Zero-overhead design, memory limits, and bounded operations.
* [Provider Coexistence](system/ProviderCoexistence.md) - Compatibility with other root and security modules.
* [Remote Sources](system/RemoteSources.md) - Downloading remote key material and security certificates safely.
* [Web Interface](system/WebInterface.md) - WebUI architecture, bridge protocol, and security controls.
* [Future Roadmap](system/Future.md) - Long-term platform roadmap and architectural evolution.
* [Rust Migration Baseline](system/RustMigrationBaseline.md) - Native migration verification baseline.
