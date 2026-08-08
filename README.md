# CleveresTricky

[![Build](https://github.com/tryigit/CleveresTricky/actions/workflows/build.yml/badge.svg)](https://github.com/tryigit/CleveresTricky/actions/workflows/build.yml)

CleveresTricky is a KernelSU/APatch module for Android keystore and attestation compatibility. Its mobile WebUI puts the main switch, app scope, identity, patch-level, RKP, DRM, and keybox controls in one place.

## Features

| Feature | What it gives you |
|---|---|
| One master Spoof Engine switch | Start or park every Binder spoof path from the dashboard. A boot-disabled engine performs no native injection. |
| Targeted attestation handling | Apply certificate-chain substitution only to the apps you choose, or enable global mode when needed. |
| TEE and StrongBox support | Works with genuine Android KeyMint key creation and later cryptographic operations. |
| Multi-keybox manager | Load, verify, select, rotate, and monitor EC/RSA keyboxes from the WebUI. |
| Encrypted CBOX storage | Protect keybox files with AES-256-GCM containers and encrypted local caches. |
| Complete patch-level control | Configure OS, vendor, and boot patch levels globally or per app with live reload. |
| App-scoped identity manager | Manage dual-SIM IMEI, MEID, IMSI, ICCID, phone-number, and device-serial overrides from the WebUI. |
| Template fingerprint and Build identity | Persist a selected template and optionally expose its fingerprint and app-visible `android.os.Build` fields from early boot. |
| Boot-state property compatibility | Apply a bounded early-boot property view for common unlocked/debug indicators, with automatic conflict checks. |
| PIF-friendly coexistence | Automatic mode detects overlapping build-identity providers and leaves their fingerprint setup untouched. |
| RKP protection | Keep RKP service infrastructure outside substitution scope and optionally preserve generated-key responses end to end. |
| DRM app passthrough | Keep selected streaming/DRM apps on the genuine keystore certificate path. |
| Secure WebUI | Manage targets, keyboxes, profiles, logs, encrypted backups, and compatibility switches from the module Action button. |
| Hardened installer | Verifies every packaged payload, blocks unsupported installation paths, and keeps configuration root-only. |

## Quick start

1. Download the release ZIP.
2. Open KernelSU or APatch and choose **Install module**.
3. Select the ZIP and reboot.
4. Press CleveresTricky's **Action** button to open the WebUI.
5. Add an authorized keybox, select target apps, and choose a profile.
6. Leave **Spoof Engine** on, then enable only the optional identity and boot controls you need.

No usable keybox or private attestation key is bundled. Only use key material you own or are authorized to test.

## Requirements

| Component | Supported |
|---|---|
| Android | 12–16 (API 31–36) |
| Root manager | KernelSU or APatch |
| Architecture | `arm64-v8a` or `x86_64` |
| Install method | Root-manager app while Android is running |
| Magisk/recovery | Not supported; installation is stopped instead of leaving a partial module |

Results still depend on the real device state, certification, firmware, key material, Google Play services, and server-side policy. The module improves the working compatibility path but cannot promise a particular server verdict on every device.

## Recommended profiles

| Profile | Best for | Main behavior |
|---|---|---|
| Daily Compatibility | Fresh install / normal use | Targeted substitution, revocation checks, automatic boot-property policy, DRM and RKP passthrough enabled. |
| Default | Conservative setup | Targeted substitution and revocation checks; boot-property changes off, DRM/RKP passthrough on. |
| Maximum Compatibility | Focused testing | Global scope, identity refresh, telephony and boot-property compatibility; DRM/RKP passthrough disabled. |
| Minimal | Troubleshooting | Certificate substitution and active spoofing features off; DRM/RKP passthrough remains on. |

Start with **Daily Compatibility**. If a vendor feature regresses, switch to **Default** or set `boot_props_mode` to `disable`. Use **Maximum Compatibility** for short, controlled tests because it intentionally changes the widest scope.

## WebUI controls

The Action button opens a token-authenticated WebUI on the device loopback interface. It is not exposed to the local network.

The main controls are:

- **Spoof Engine:** master control for attestation, telephony, build identity, and boot-property spoofing. Turning it off unregisters Binder interceptors, parks injected native hooks, and stops the scheduled keybox worker. Rebooting with it off avoids injection and clears boot-time property views.
- **Global Mode:** targets every calling UID instead of only `target.txt` entries.
- **Disable Certificate Substitution:** immediate safe mode without uninstalling the module.
- **Auto Keybox Check:** periodically checks loaded keyboxes and revocation state.
- **Refresh Identity on Boot:** refreshes supported attestation/telephony identifiers.
- **Telephony Identifier Interception:** changes supported values returned to selected apps only after Android permits the original API call. The hook can start and stop at runtime; an app may need to be restarted if it cached an earlier value.
- **Template Build Identity:** applies the selected template fingerprint and app-visible Build fields before Zygote on the next boot.
- **Hide Sensitive Props:** applies the boot-property compatibility set on the next boot.
- **RKP Passthrough:** leaves generated-key replies untouched while the normal existing-key path remains available. Android and Google RKP service packages stay protected from substitution in every mode.
- **DRM App Passthrough:** excludes packages in `drm_packages.txt` from certificate substitution.

RKP and DRM passthrough are enabled on a fresh install. They are compatibility safeguards, not simulated RKP or DRM implementations.

Telephony overrides are app-facing. They do not write EFS/NV storage, change the modem or baseband, alter the physical SIM, or change the identity seen by a mobile network operator. If Android returns `null` or denies an identifier request, CleveresTricky preserves that result.

Android 8 and newer derive Android ID as a per-app, per-user SSAID inside SettingsProvider, so CleveresTricky does not offer a misleading global Android ID switch. The real kernel version reported by `uname` is also unchanged. `security_patch.txt` controls the genuine certificate fields for system, vendor, and boot/kernel patch levels.

Hide Sensitive Props changes the userspace property view used by apps. It does not relock the physical bootloader, repair verified boot, rewrite vbmeta, or change the TEE root of trust.

`boot_props_mode` controls the property layer: `auto` skips known overlapping/vendor-sensitive setups, `force` applies it whenever Hide Sensitive Props is enabled, and `disable` turns it off without deleting the toggle.

If another PIF-style module already owns the build fingerprint, keep `boot_props_mode=auto`. CleveresTricky detects common overlapping identity providers and skips its template Build properties while its attestation, patch-level, keybox, RKP, DRM, and targeted identity layers continue to work.

## Configuration

Configuration is stored in `/data/adb/cleverestricky` with root-only permissions. WebUI saves are validated and written atomically.

### Target apps

Edit `target.txt` from the WebUI:

```text
com.google.android.gms
io.github.vvb2060.keyattestation
com.example.*
```

Wildcards are supported. Global Mode ignores this list and targets every UID.

### Per-app identity and keybox

`app_config` accepts three columns:

```text
# package                      identity-template  keybox-file
com.example.attestation       pixel8pro          test.xml
com.example.second            null               second.cbox
```

Use `null` when no per-app override is required.

### Security patch levels

`security_patch.txt` can set all three attestation patch fields:

```ini
# Global defaults
system=YYYY-MM-05
vendor=device_default
boot=no

# Per-app override
[com.google.android.gms]
system=2025-10-01
```

Supported keys:

- `system`: OS patch level;
- `vendor`: vendor patch level;
- `boot`: boot/kernel patch level;
- `all`: set all three together.

Supported values include `YYYY-MM-DD`, `YYYYMMDD`, `YYYYMM`, `today`, date placeholders, `device_default`, `prop`, and `no`. `device_default` preserves the certificate's genuine field; `prop` reads the matching system, vendor, or boot-image security-patch property; `no` removes that field. Invalid or oversized files are rejected and the last valid configuration stays active.

The older `package.name=date` syntax remains supported for OS-patch overrides.

### Build and attestation identity

`spoof_build_vars` contains only values the module actually consumes:

```ini
TEMPLATE=pixel8pro
MANUFACTURER=Google
MODEL=Pixel 8 Pro
BRAND=google
PRODUCT=husky
DEVICE=husky
FINGERPRINT=google/husky/husky:14/AP1A.240405.002/11480754:user/release-keys
RELEASE=14
BUILD_ID=AP1A.240405.002
INCREMENTAL=11480754
TYPE=user
TAGS=release-keys
ATTESTATION_ID_SERIAL=ABC123XYZ789
ATTESTATION_ID_IMEI=356938035643809
ATTESTATION_ID_IMEI2=356938035643817
ATTESTATION_ID_IMSI=310260123456789
ATTESTATION_ID_ICCID=89014103211118510720
ATTESTATION_ID_MEID=A100000927F4E1
ATTESTATION_ID_PHONE_NUMBER=+12025550123
```

The WebUI writes the complete selected template to this file so the fingerprint is available during early boot. Enable **Template Build Identity** and reboot to apply it. Bootloader-related userspace properties are controlled by **Hide Sensitive Props**, not by placing arbitrary `ro.*` lines in this file. The file accepts only fields supported by the module.

The Identity tab is the recommended way to edit these values. It validates checksums and lengths, supports separate SIM 2 values, preserves unrelated settings, and applies each update atomically.

### DRM passthrough packages

Edit `drm_packages.txt` to choose which apps keep the genuine keystore path:

```text
com.netflix.mediaclient
com.amazon.avod.thirdpartyclient
com.example.video.*
```

The list has no effect while DRM App Passthrough is disabled.

## Keybox manager

CleveresTricky accepts:

- `/data/adb/cleverestricky/keybox.xml` for legacy setups;
- XML files under `/data/adb/cleverestricky/keyboxes/`;
- encrypted `.cbox` files uploaded from the WebUI;
- explicitly configured HTTPS sources.

Before a keybox becomes active, the module checks its private-key/certificate match, algorithm, chain structure, validity period, and revocation state. A broken or mixed pool is rejected as a whole. If the revocation list cannot be checked, newly loaded material stays inactive.

Prefer CBOX for storage and transfer. Plain XML contains root-readable private key material and should never be committed to Git.

## KernelSU package layout

KernelSU recognizes the module through `module.prop`. The boot scripts, policy, Action script, and other module files are included in the ZIP root as documented by KernelSU.

`service.apk`, `inject`, and `libcleverestricky.so` are build outputs. Gradle compiles them for each supported ABI, verifies them, and places them in the release ZIP with SHA-256 files. `module/template` contains the source-controlled scripts and configuration templates.

A recovery-style `META-INF/com/google/android/update-binary` is not required by KernelSU and is not included because recovery and legacy Magisk installation are unsupported. See the official [KernelSU module guide](https://kernelsu.org/guide/module.html).

## Troubleshooting

Use the WebUI **Logs** tab or run:

```bash
logcat -s CleveresTricky
```

Common checks:

- **No keybox active:** open Keyboxes, run verification, and check network access to the revocation list.
- **An app behaves differently after property changes:** disable Hide Sensitive Props and reboot.
- **Fingerprint did not change:** apply a template, enable Template Build Identity, verify `boot_props_mode` is not `disable`, and reboot. Automatic mode intentionally defers to another detected identity provider.
- **Key generation or provisioning regressed:** enable RKP Passthrough.
- **Native endpoint unavailable:** disable other ptrace/injection modules and inspect SELinux logs.
- **Tamper warning:** reinstall the complete ZIP; a payload or checksum is missing or changed.
- **Unsure which option caused a problem:** choose Minimal, reboot, then enable one option at a time.

See [LOG.md](LOG.md), [CONTRIBUTING.md](CONTRIBUTING.md), [LANGUAGES.md](LANGUAGES.md), and [THEME.md](THEME.md).

## Building

The build requires JDK 21, Android SDK 36, Android NDK `27.3.13750724`, Rust, `rustup`, `cargo-ndk`, and initialized Git submodules.

```bash
git submodule update --init --recursive
./gradlew testDebugUnitTest ktlintCheck zipDebug
```

Release ZIPs are created in `module/release/`. CI also runs Android lint, native builds for both ABIs, Rust formatting/Clippy/tests/audit, shell validation, module-structure checks, and ZIP assembly.

## Security

- Keep the WebUI on loopback and never expose it through a public proxy or tunnel.
- Keep recoverable backups before testing boot/property changes.
- Use only authorized key material.
- Report vulnerabilities privately before publishing exploit details.

Android attestation and Play Integrity can change independently of this project. The official [Android attestation documentation](https://source.android.com/docs/security/features/keystore/attestation), [Play Integrity verdict documentation](https://developer.android.com/google/play/integrity/verdicts), and [KernelSU module guide](https://kernelsu.org/guide/module.html) remain authoritative.
