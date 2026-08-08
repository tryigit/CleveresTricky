# CleveresTricky

CleveresTricky is a KernelSU/APatch module for controlled Android keystore-attestation compatibility testing. It injects a native Binder interceptor into `keystore2`, keeps key creation and cryptographic operations on Android's genuine KeyMint path, and can substitute an attestation certificate chain for explicitly selected application UIDs.

The module does not bundle a keybox, manufacture hardware-backed keys, replace Remote Key Provisioning (RKP), clear Device Recall, or guarantee a Google Play Integrity verdict.

## Supported environment

| Component | Supported |
|---|---|
| Android | 12–16 (API 31–36) |
| Root managers | KernelSU and APatch |
| Architectures | `arm64-v8a`, `x86_64` |
| Keystore | `keystore2` with KeyMint/StrongBox services exposed by the device |
| Magisk | Not supported; the installer stops instead of installing a partially working module |

The module must be installed from the KernelSU or APatch manager while Android is running. Recovery installation is not supported.

## About `MEETS_STRONG_INTEGRITY`

`MEETS_STRONG_INTEGRITY` is a server-issued Play Integrity verdict, not a local setting. On Android 13 and later, Google requires the device to satisfy `MEETS_DEVICE_INTEGRITY` and recent security-update requirements. Device integrity relies on genuine hardware-backed evidence, device certification, and a locked bootloader. See Google's [verdict documentation](https://developer.android.com/google/play/integrity/verdicts) and AOSP's [key-attestation documentation](https://source.android.com/docs/security/features/keystore/attestation).

Consequently:

- This module cannot guarantee `MEETS_STRONG_INTEGRITY`.
- A userspace module cannot turn an unlocked or uncertified device into genuine locked-bootloader hardware evidence.
- If installing KernelSU/APatch changes verified boot or requires unlocking the bootloader, hardware verdicts can remain unavailable regardless of certificate substitution.
- Security patch values must describe a real, coherent device state. Invalid values are rejected; they are not replaced with a fabricated fallback date.
- [Device Recall](https://developer.android.com/google/play/integrity/device-recall) is server-side state. Changing local identifiers does not erase or bypass it.

For the strongest legitimate result, start with a certified device whose bootloader is locked, verified boot is intact, firmware and Google Play services are current, and the app is installed through its expected distribution channel. CleveresTricky can improve compatibility around the attestation chain, but Google makes the final verdict.

## How it works

1. `service.apk` starts as a root `app_process` service.
2. The architecture-specific `inject` binary validates the target PID and library path, attaches with `ptrace`, and loads `libcleverestricky.so` into the intended process.
3. The native library installs a bounded Binder hook. Rust validates Binder command streams and offsets before native write-back; unknown layouts fail closed.
4. Kotlin interceptors register only known positive transaction codes and reject oversized or malformed parcels.
5. A successful genuine KeyMint key-generation or keystore lookup response is inspected. Only when an authorized, non-revoked keybox is active is the returned certificate chain rewritten.
6. The generated private key and all later signing/decryption operations remain owned by the platform security level.

RKP traffic is left on Android's genuine hardware path. The module contains no local RKP proxy or synthetic COSE proof generator.

## Installation

1. Download or build the module ZIP.
2. In KernelSU or APatch, choose **Install module** and select the ZIP.
3. Reboot.
4. Use the module's **Action** button. It opens the token-authenticated WebUI on `127.0.0.1:5623`.
5. Add only key material that you own or are authorized to test, choose target packages, then reload the configuration.

The installer verifies every extracted payload against its packaged SHA-256 file, refuses symlinked configuration paths, creates `/data/adb/cleverestricky` as root-only (`0700`), and stores configuration files as `0600`. The module ZIP follows the standard [KernelSU module format](https://kernelsu.org/guide/module.html); no recovery-style `META-INF` installer is included.

## Keybox handling

No private key or usable keybox is distributed in this repository.

Supported sources are:

- legacy `/data/adb/cleverestricky/keybox.xml`;
- bounded XML files in `/data/adb/cleverestricky/keyboxes/`;
- encrypted `.cbox` files uploaded through the WebUI;
- explicitly configured HTTPS servers.

Before activation, every parsed keybox is checked for certificate/private-key correspondence, supported algorithms, chain structure, certificate validity, and revocation status using Google's Android attestation status list. If the revocation list is unavailable, the module activates no newly loaded keybox. A mixed payload containing one invalid or revoked entry is rejected as a unit.

Plain XML keyboxes are root-readable secrets. Prefer CBOX for storage and transport, and never commit key material to Git.

### CBOX v2

The companion Encryptor app writes CBOX v2 containers with:

- PBKDF2-HMAC-SHA256, 250,000 iterations;
- a 256-bit AES key;
- AES-GCM with a random 16-byte salt and 12-byte IV;
- authenticated magic/version/KDF header fields;
- a domain-separated, length-prefixed payload signature.

The service reads v1 only for migration and writes/produces v2. Successfully unlocked CBOX data is cached encrypted with AndroidKeyStore when available. The root-only random device-secret fallback preserves confidentiality at rest but is weaker than hardware-backed AndroidKeyStore storage.

### Remote sources

Remote key servers are optional and disabled until configured. They must use HTTPS, redirects are refused, response and archive sizes are bounded, unsafe ZIP paths are rejected, credentials are stored encrypted, and downloaded keyboxes still pass the same full validation and revocation checks. HTTP is accepted only by test-only loopback hooks.

## Configuration

All configuration lives in `/data/adb/cleverestricky`. The WebUI editor validates the same supported formats before atomically replacing a file.

### `target.txt`

One package rule per line:

```text
com.google.android.gms
io.github.vvb2060.keyattestation
com.example.*
```

`*` is supported by the package trie. A trailing `!` from older releases is accepted for migration but now means the same certificate-substitution path; software key generation was removed. In `global_mode`, all calling UIDs are targeted and `target.txt` is ignored—it is not an exclusion list.

### `app_config`

Exactly three whitespace-separated columns are accepted:

```text
# package                      identity-template  keybox-filename
com.example.attestation       pixel8pro          test.xml
com.example.second            null               second.cbox
```

Use `null` for no per-app override. Unknown fourth columns and unsafe names are rejected. A per-app keybox rule fails closed if that file is unavailable or does not support the requested EC/RSA algorithm.

### `security_patch.txt`

The first form sets a default attestation OS patch level:

```text
2026-08-01
```

Package-specific rules are also supported:

```text
com.example.attestation=2026-08
com.example.*=today
```

Accepted values are exact `YYYY-MM-DD`, `YYYY-MM`, `YYYYMMDD`, `YYYYMM`, `today`, or the documented `YYYY`/`MM`/`DD` placeholders. Android's attestation OS-patch tag is normalized to `YYYYMM`. Invalid dates, impossible calendar values, oversized files, and excessive rule counts leave the previous valid configuration active.

### `spoof_build_vars`

Despite the legacy filename, this file is not a general Android system-property spoofer. Only values consumed by the attestation/telephony code are accepted.

```ini
TEMPLATE=pixel8pro
MANUFACTURER=Google
MODEL=Pixel 8 Pro
BRAND=google
PRODUCT=husky
DEVICE=husky
ATTESTATION_ID_IMEI=490154203237518
ATTESTATION_ID_SERIAL=ABC123XYZ789
ATTESTATION_ID_IMSI=310260123456789
ATTESTATION_ID_ICCID=89011202000000000007
```

Supported keys are:

- template identity fields: `TEMPLATE`, `BRAND`, `DEVICE`, `PRODUCT`, `MANUFACTURER`, `MODEL`;
- attestation fields: `SERIAL`, `IMEI`, `MEID`, and `ATTESTATION_ID_{BRAND,DEVICE,PRODUCT,SERIAL,IMEI,MEID,MANUFACTURER,MODEL}`;
- optional telephony fields: `ATTESTATION_ID_{IMEI2,IMSI,ICCID,MEID,PHONE_NUMBER}`;
- exact 32-byte digest override: `MODULE_HASH` as 64 hexadecimal characters.

IMEI/IMEI2 and ICCID values must pass Luhn validation. Unsupported no-op keys such as `FINGERPRINT`, `SDK_INT`, arbitrary `ro.*` properties, location, MAC, or DRM identifiers are rejected instead of being silently accepted.

Built-in templates provide only the five attestation identity fields that KeyMint defines here: brand, device, product, manufacturer, and model. The fingerprint shown in the WebUI is reference metadata and is not applied to Android properties.

### Feature markers

| Marker | Behavior | Apply time |
|---|---|---|
| `global_mode` | Target all calling UIDs | Live reload |
| `tee_broken_mode` | Safe mode: disable certificate substitution | Live reload |
| `auto_keybox_check` | Enable periodic revocation checks/cleanup | Live/periodic |
| `random_on_boot` | Refresh the supported template and identifier fields | Next boot |
| `hide_sensitive_props` | Apply a bounded `resetprop` compatibility set; does not change hardware evidence | Next boot |
| `spoof_region_cn` | Apply the documented CN region compatibility properties | Next boot |
| `telephony` | Intercept selected `iphonesubinfo` identifier replies for targeted UIDs | Reboot recommended for enable/disable |

Profiles are deterministic:

- **Maximum Compatibility:** global targeting, boot identity refresh, property compatibility, revocation checks, and telephony interception.
- **Daily Compatibility:** targeted mode, property compatibility, and revocation checks.
- **Minimal:** certificate substitution and optional compatibility features off.
- **Default:** targeted certificate substitution with revocation checks; other optional features off.

## WebUI and backups

The WebUI binds only to IPv4 loopback. The action script reads a root-only port/token file, validates both values, and opens the exact URL. Every API request requires a high-entropy token; Host and Origin checks, request/rate limits, path validation, secure response headers, and atomic file writes are enforced.

Configuration exports use CTSB v2: PBKDF2-HMAC-SHA256 (250,000 iterations) plus AES-256-GCM with the full header authenticated as AAD. Passwords must contain 12–1024 characters. Legacy v1 backups can be read for migration. Restore accepts only encrypted `.ctsb` files and enforces entry, path, count, and uncompressed-size limits.

The per-file SHA-256 consistency check catches accidental or incomplete module modification and disables interceptors on mismatch. It is not a cryptographic trust root against an attacker who already has unrestricted root and can replace both code and checksums.

## Build and verification

Requirements:

- JDK 21;
- Android SDK/build tools 36;
- Android NDK `27.3.13750724`;
- Rust, `rustup`, and `cargo-ndk`;
- Rust targets `aarch64-linux-android` and `x86_64-linux-android`;
- Git submodules initialized recursively.

```bash
git submodule update --init --recursive

./gradlew ktlintCheck
./gradlew :service:lintDebug :stub:lintDebug :encryptor-app:lintDebug
./gradlew testDebugUnitTest
./gradlew zipDebug

cd rust
cargo fmt -- --check
cargo clippy -- -D warnings
cargo test
```

Release and debug ZIPs are produced under `module/release/`. CI runs shell validation, Kotlin formatting, Android lint/unit tests, Rust formatting/Clippy/tests/audit, native builds, and module packaging before artifacts are uploaded. Releases are published only from a successful protected `master` push.

## Troubleshooting

Use the WebUI **Logs** tab or:

```bash
logcat -s CleveresTricky
```

Common failure states:

- **No keyboxes active:** inspect CRL connectivity and keybox validation results. The module intentionally fails closed.
- **Native endpoint unavailable:** another ptrace/injection module, SELinux policy, unsupported Binder layout, or a process restart may be blocking registration.
- **Tamper warning:** reinstall a complete ZIP; a packaged file or checksum is missing/mismatched.
- **Need a clean diagnostic path:** select **Minimal**, reboot, then enable one feature at a time.

See [LOG.md](LOG.md), [CONTRIBUTING.md](CONTRIBUTING.md), [LANGUAGES.md](LANGUAGES.md), and [THEME.md](THEME.md).

## Security and responsible use

- Use only certificates and private keys you own or are explicitly authorized to test.
- Do not use this project to impersonate another device, bypass an application's access controls, evade fraud controls, or misrepresent device security.
- Keep the WebUI on loopback; do not expose it through ADB reverse, a proxy, or a public tunnel.
- Root, native injection, and certificate substitution inherently increase device risk. Keep recoverable backups and test on non-production devices first.
- Report vulnerabilities privately before publishing exploit details.

Android attestation and Play Integrity can change independently of this project. Treat the official [AOSP keystore documentation](https://source.android.com/docs/security/features/keystore/features), [Play Integrity verdict documentation](https://developer.android.com/google/play/integrity/verdicts), and [KernelSU module guide](https://kernelsu.org/guide/module.html) as authoritative.
