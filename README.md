# CleveresTricky

[![Build](https://github.com/tryigit/CleveresTricky/actions/workflows/build.yml/badge.svg)](https://github.com/tryigit/CleveresTricky/actions/workflows/build.yml)

CleveresTricky is a KernelSU and APatch module for Android keystore, attestation, identity, and application compatibility. It combines a controlled native runtime with a mobile WebUI so users can manage scope, key material, identity, patch levels, Remote Key Provisioning protection, and DRM compatibility from one place.

## Main capabilities

### Runtime control

The [Spoof Engine](docs/SpoofEngine.md) controls optional identity substitution. Core Keystore and TEE compatibility plus the boot property protection path remain active independently while the module service is healthy.

[Application Scope](docs/ApplicationScope.md) explains targeted mode, global mode, package rules, shared Android user identifiers, and live cache updates.

[Application Rules](docs/ApplicationRules.md) explains application specific templates, keybox selection, and stable privacy identities.

[Profiles](docs/Profiles.md) explains the Daily Compatibility, Default, Maximum Compatibility, and Minimal presets.

### Attestation and identity

[Attestation](docs/Attestation.md) explains certificate substitution, genuine KeyMint operations, StrongBox handling, and the limits of software based compatibility.

[Certificate Safe Mode](docs/CertificateSafeMode.md) explains the legacy configuration concept for preserving genuine certificate responses. Core targeting no longer depends on this legacy switch.

[Keybox Manager](docs/KeyboxManager.md) explains keybox loading, verification, selection, rotation, revocation checks, and automatic monitoring.

[Automatic Keybox Check](docs/AutomaticKeyboxCheck.md) explains the bounded maintenance worker and its lifecycle.

[Remote Sources](docs/RemoteSources.md) explains authenticated retrieval, signature checks, refresh policy, and failure behavior.

[Encrypted Storage](docs/EncryptedStorage.md) explains CBOX containers, local protected caches, and safe key material handling.

[Patch Levels](docs/PatchLevels.md) explains system, vendor, and boot patch fields with global and per application rules.

[Build Identity](docs/BuildIdentity.md) explains device templates, fingerprints, app visible Build fields, synchronized early boot activation, and the optional Pixel beta Auto Identity helper for Custom ROM users.

[Identity Refresh](docs/IdentityRefresh.md) explains next boot generation and snapshot consistency.

[Telephony Identity](docs/TelephonyIdentity.md) explains dual SIM values, permission preservation, supported Android APIs, and network operator limits.

### Platform compatibility

[Boot Properties](docs/BootProperties.md) explains the core userspace boot property view and the separate identity compatibility policy.

[Region Properties](docs/RegionProperties.md) explains the optional bounded country and hardware region view.

[Provider Coexistence](docs/ProviderCoexistence.md) explains how automatic mode avoids overriding another active fingerprint provider.

[RKP Protection](docs/RkpProtection.md) explains protected Android infrastructure and genuine generated key response passthrough.

[DRM Passthrough and Privacy](docs/DrmPassthrough.md) explains two deliberately separate controls: selected media applications can remain on Android's genuine Keystore certificate path, while an application configured with `privacy=isolate` can receive a stable application scoped pseudonym instead of the modern DRM HAL `deviceUniqueId` byte array property. This narrow privacy hook is intended to stop that raw device identifier from becoming another persistent application tracking value. For example, a streaming application such as Netflix cannot use the genuine `deviceUniqueId` returned through this supported path while isolation is active.

The DRM hook is intentionally not a Widevine or DRM bypass. It does not rewrite the reported security level, licenses, provisioning messages, content keys, sessions, HDCP state, or string properties. Building and maintaining a general DRM protection bypass would require substantially broader, vendor specific work and is not a primary project goal.

### Interface and operation

[Web Interface](docs/WebInterface.md) explains the native module manager transport, mobile navigation, live status, validation, and accessibility.

[Backup and Restore](docs/BackupRestore.md) explains encrypted exports, bounded imports, and safe recovery.

[Installer](docs/Installer.md) explains the KernelSU and APatch package layout, payload verification, supported devices, and installation flow.

[Diagnostics](docs/Diagnostics.md) explains logs, status checks, common failures, and a controlled troubleshooting sequence.

### Engineering references

[Security Model](docs/SecurityModel.md) documents trust boundaries, protected files, input validation, and capabilities the module does not claim.

[Performance](docs/Performance.md) documents hook lifecycle, bounded caches, background work, CPU behavior, and memory controls.

[Building](docs/Building.md) documents the toolchain, validation tasks, and generated artifacts.

[Native Architecture](docs/NativeArchitecture.md) documents the Rust injector, the Rust native core, the enforced language policy, and the single narrowly required Android C++ ABI boundary.

## Quick start

1. Download the current release ZIP from the official project release page.

2. For an official release, verify the published `SHA256SUMS` entry and GitHub build provenance when you need source authenticity in addition to the module's internal integrity checks.

3. Open KernelSU or APatch while Android is running.

4. Install the ZIP and reboot.

5. Open the CleveresTricky WebUI from the module manager.

6. Fresh installations start in Global Mode with optional identity spoofing off.

7. Add only key material you own or are authorized to test.

8. Configure identity options only when they are needed.

9. Reboot after changing template build identity values.

No usable keybox or private attestation key is bundled with the project.

## Supported environment

CleveresTricky supports Android 12 through Android 17, including API levels 31 through 37. Supported processor targets are ARM64 and x86 64. Installation is supported through KernelSU or APatch while Android is running.

Magisk and recovery installation are not supported. The installer stops unsupported paths instead of leaving a partial module.

## Important boundaries

Results depend on the real device state, firmware, certification, key material, Google Play services, and remote policy. CleveresTricky improves the local compatibility path but cannot promise a specific remote verdict for every device.

Telephony values are visible only through supported application APIs. They do not modify the modem, baseband, EFS storage, physical SIM, or identity seen by a mobile network operator.

Android ID on modern Android is scoped by application signing identity, user, and device inside SettingsProvider. CleveresTricky does not present a misleading global Android ID control.

The real kernel version returned by the operating system is unchanged. The core boot property view does not physically relock a bootloader, repair verified boot, rewrite vbmeta, or change the hardware root of trust.

An unlocked bootloader does not by itself mean that every DRM implementation is unusable. Actual DRM behavior depends on the device, vendor implementation, provisioning state, security level, service policy, and firmware. Because many devices can retain functional protected playback despite an unlocked bootloader, CleveresTricky treats DRM bypass as a separate vendor specific problem rather than a primary objective. The current DRM work is privacy oriented: it narrows exposure of `deviceUniqueId` on the supported modern stable AIDL path without pretending to upgrade or defeat the underlying DRM security state.

Internal SHA 256 records detect missing, changed, injected, linked, and unexpected installed payloads and place the service in tamper lockdown when verification fails. Those records are intentionally not described as proof of who produced a ZIP because a person who can replace every file in an archive can also replace archive internal checksum records. Official release authenticity is instead anchored by the separately published release digest and GitHub signed build provenance.

## Recommended first setup

Use the fresh installation defaults first. Global Mode selects eligible application UIDs while core boot and Keystore protection remain active. Optional identity spoofing stays off until you enable it from the Identity section.

If you use a Custom ROM and need a current build identity for Play Integrity testing, Auto Identity can retrieve a Pixel beta or canary identity from Google public metadata and save it locally. Enable Identity Spoof Engine and reboot only when you want those build identity values exposed.

For DRM identifier privacy, create an Application Rule for the media application and set its privacy mode to `isolate`. DRM Keystore Passthrough can remain enabled: the genuine Keystore certificate path and the pseudonymous DRM device ID path are intentionally independent.

## Help and project information

Use the WebUI Logs screen or Android logcat with the CleveresTricky tag when diagnosing a problem. Detailed guidance is available in [Diagnostics](docs/Diagnostics.md).

Project history is recorded in [CHANGELOG.md](CHANGELOG.md). Contribution guidance is in [CONTRIBUTING.md](CONTRIBUTING.md). Translation information is in [LANGUAGES.md](LANGUAGES.md). Theme information is in [THEME.md](THEME.md).

The official [Android attestation documentation](https://source.android.com/docs/security/features/keystore/attestation), [Play Integrity verdict documentation](https://developer.android.com/google/play/integrity/verdicts), and [KernelSU module guide](https://kernelsu.org/guide/module.html) remain authoritative for their platforms.

## Granular optional identity controls

CleveresTricky now resolves optional identity behavior through independent controls for Device and Build Identity, Attestation Identity, Telephony Identity, Region Identity, Identity Refresh, and Security Patch. Security Patch is independent from Device and Build Identity. A configuration can present a different supported patch level without enabling Build Identity, or present a different Build Identity while all patch levels remain genuine.

Core Keystore interception, genuine platform KeyMint and StrongBox private key operations, root of trust handling, Binder safety, and required boot compatibility remain independent from these optional controls. The interface describes captured device state, configured presentation state, and effective application visible state separately. It does not claim to change physical bootloader state, verified boot measurements, firmware, or a remote integrity verdict.

The Security Patch view exposes System, Vendor, and Boot as independent policies. Profiles can assign coherent optional settings to applications, and the Effective State inspector reports the resolver output that runtime decisions use.
