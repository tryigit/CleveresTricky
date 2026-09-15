# CleveresTricky

**Language:** **English** | [Türkçe](README.tr.md) | [简体中文](README.zh-CN.md) | [Español](README.es.md) | [Deutsch](README.de.md) | [Русский](README.ru.md) | [Bahasa Indonesia](README.id.md) | [हिन्दी](README.hi.md) | [العربية](README.ar.md)

[![Release](https://img.shields.io/github/v/release/tryigit/CleveresTricky?display_name=tag&sort=semver&label=Release)](https://github.com/tryigit/CleveresTricky/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/tryigit/CleveresTricky/total?color=0A84FF&label=Downloads)](https://github.com/tryigit/CleveresTricky/releases)
![Android](https://img.shields.io/badge/Android-12--17-3DDC84?logo=android&logoColor=white)
![Module](https://img.shields.io/badge/Module-KernelSU%20%7C%20APatch-6f42c1)

CleveresTricky is a KernelSU and APatch module for Android 12-17. It brings Android keystore and attestation compatibility, Keybox/CBOX management, application targeting, optional identity controls, patch-level controls, and privacy tools into one mobile WebUI.

Start with the defaults and enable only the features you actually need.

## What you can do

- Manage, verify, select, and rotate **Keybox/CBOX** files.
- Use Global Mode or target individual applications with per-app rules.
- Configure optional device/build, attestation, telephony, region, and security patch presentation.
- Protect Remote Key Provisioning flows and reduce supported DRM identifier exposure without pretending to bypass DRM.
- Back up settings, inspect effective state, and collect diagnostics from the WebUI or module Action.

## Quick start

1. Download the latest release ZIP from the official [Releases](https://github.com/tryigit/CleveresTricky/releases/latest) page.
2. Install the ZIP from KernelSU or APatch while Android is running.
3. Open the CleveresTricky WebUI from your module manager.
4. Add only a **Keybox or CBOX** that you own or are authorized to test.
5. Keep the default setup first, then enable identity, application rules, or privacy options only when needed.

No usable Keybox or private attestation key is bundled with the project.

## Supported environment

- Android **12-17** / API **31-37**
- **ARM64** and **x86-64**
- **KernelSU** and **APatch** (recommended, full WebUI support)
- **Magisk** (headless / manual configuration via `/data/adb/cleverestricky/`, [not recommended](https://tryigit.dev/advanced-android-root-architecture-concealment/))

Recovery installation is not supported.

## Important to know

CleveresTricky improves the local compatibility path, but remote results still depend on the real device, firmware, certification state, Google Play services, server policy, and the data you configure. It cannot guarantee a particular Play Integrity or attestation verdict.

It does not physically relock the bootloader, rewrite verified boot measurements, change the hardware root of trust, modify the modem/baseband, or turn DRM privacy controls into a DRM bypass.

Use only configuration and credentials that you are authorized to use.

## Learn more

- [Strong Integrity Guide](docs/security/StrongIntegrityGuide.md) - quick-start guide to passing Google Play Integrity (MEETS_STRONG_INTEGRITY) across Official, AOSP, and Custom ROMs.
- [Magisk Support Guide](docs/system/Magisk.md) - headless manual configuration and root concealment guidance for Magisk users.
- [Keybox Manager](docs/security/KeyboxManager.md) - Keybox/CBOX loading, verification, selection, and revocation checks.
- [Application Scope](docs/identity/ApplicationScope.md) and [Application Rules](docs/identity/ApplicationRules.md) - choose where features apply.
- [Build Identity](docs/identity/BuildIdentity.md), [Telephony Identity](docs/identity/TelephonyIdentity.md), and [Patch Levels](docs/identity/PatchLevels.md) - optional identity controls.
- [RKP Protection](docs/security/RkpProtection.md) and [DRM Privacy](docs/system/DrmPassthrough.md) - platform compatibility and privacy behavior.
- [Backup and Restore](docs/system/BackupRestore.md) - encrypted configuration backup and recovery.
- [Security Model](docs/security/SecurityModel.md) and [Installer](docs/system/Installer.md) - trust boundaries and installation details.

## Need help?

Use the **Logs** page in the WebUI or the module **Action** to create an emergency diagnostic report. Review the archive before sharing it because diagnostics can contain device and system information.

See [Diagnostics](docs/system/Diagnostics.md) for common problems and troubleshooting steps.

## Project

[Changelog](CHANGELOG.md) · [Contributing](CONTRIBUTING.md) · [Languages](LANGUAGES.md) · [Licensing](LICENSING.md) · [Donate](DONATE.md) · [Telegram](https://t.me/cleverestech)
