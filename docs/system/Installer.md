# Installer

**Language:** **English** | [Türkçe](../i18n/tr/system/Installer.md) | [简体中文](../i18n/zh-CN/system/Installer.md) | [Español](../i18n/es/system/Installer.md) | [Deutsch](../i18n/de/system/Installer.md) | [Русский](../i18n/ru/system/Installer.md) | [Bahasa Indonesia](../i18n/id/system/Installer.md) | [हिन्दी](../i18n/hi/system/Installer.md) | [العربية](../i18n/ar/system/Installer.md)

## Purpose

The installer creates a complete KernelSU or APatch module with the service, native payload, scripts, policy, metadata, and integrity records required at runtime.

## Supported path

Installation must run from KernelSU, APatch, or Magisk while Android is active. Android 12 through Android 17 are supported on ARM64 and x86 64. KernelSU and APatch provide full WebUI support; Magisk runs in headless daemon mode configured via `/data/adb/cleverestricky/` (see [Magisk Support](Magisk.md)). Recovery installation paths stop with an explanation before a partial module is left behind.

The installer selects the architecture specific Rust `inject` and `webui_bridge` executables and the `libcleverestricky.so` library. It also installs the daemon, service APK, module metadata, installer script, early boot script, service script, native `webroot`, and SELinux policy required by their lifecycle stage.

Binary and script payloads are intentional parts of the module template. KernelSU and APatch use the metadata and boot scripts to recognize and start the module. The build process verifies that required files and both native architecture outputs exist before creating a ZIP.

## Integrity and permissions

The build adds a SHA 256 record for every packaged payload. Installation verifies each file it extracts against that record. Runtime verification then walks the installed module with bounded input, rejects symbolic links and non regular entries, requires integrity records for normal payloads, rejects unexpected unchecked payloads including `system.prop`, and enters tamper lockdown when a required file is missing or changed.

Manager owned state files named `disable`, `remove`, `update`, and the module tamper marker may exist without payload checksums because they are control state rather than executable or property payloads.

Runtime scripts restore conservative file modes and SELinux labels without recursively following unexpected links or crossing mounts.

## Official release authenticity

Archive internal SHA 256 records prove consistency of the installed payload but they are not by themselves proof of who created an archive. A person who can replace every file inside a ZIP can also replace checksum records and the installer script inside that same ZIP.

Official release builds therefore publish a separate `SHA256SUMS` file for the release ZIP, debug ZIP, and Encryptor APK. The release workflow also creates GitHub signed build provenance for those digests through the repository Actions identity. This external provenance is the trust anchor that distinguishes an official build from a repack that merely recalculates archive internal hashes.

When authenticity matters, download from the official project release page and verify the release digest and GitHub attestation before installation. A modified archive cannot retain the official digest or provenance for its changed bytes.

## Installation sequence

1. Download the release ZIP from the official project release page.

2. Verify its entry in `SHA256SUMS` and GitHub build provenance when source authenticity is required.

3. Install it through KernelSU, APatch, or Magisk.

4. Confirm that the manager reports success.

5. Reboot Android.

6. **KernelSU / APatch**: Open the module WebUI and review Dashboard and Logs. **Magisk (headless)**: Check runtime status via `su -c "cat /data/adb/cleverestricky/native_runtime.log"`, process inspection (`su -c "ps -A | grep cleverestech"`), or `action.sh` (see [Magisk Support](Magisk.md)).

Do not extract or delete template binaries manually. An incomplete payload will fail verification or prevent native runtime activation.

[Return to the project overview](../README.md)
