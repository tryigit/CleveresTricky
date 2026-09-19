# Backup and Restore

**Language:** **English** | [Türkçe](../i18n/tr/system/BackupRestore.md) | [简体中文](../i18n/zh-CN/system/BackupRestore.md) | [Español](../i18n/es/system/BackupRestore.md) | [Deutsch](../i18n/de/system/BackupRestore.md) | [Русский](../i18n/ru/system/BackupRestore.md) | [Bahasa Indonesia](../i18n/id/system/BackupRestore.md) | [हिन्दी](../i18n/hi/system/BackupRestore.md) | [العربية](../i18n/ar/system/BackupRestore.md)

## Purpose

Backup and Restore moves configuration and authorized key material between installations through one authenticated encrypted archive.

## Export

Export requires a password of at least twelve characters. The service creates a bounded archive from an allowlist of known configuration files and regular keybox files. It then encrypts the archive with authenticated encryption before sending it to the browser.

Symbolic links, unknown paths, excessive file counts, oversized files, and an excessive total size are excluded or rejected. Plain temporary archives are not written to persistent storage.

## Import

Import accepts only the encrypted CTSB format and enforces upload size before decryption. The decrypted archive has limits for entry count, keybox count, individual size, and total expanded size.

Every path must match the configuration allowlist or one direct keybox child. Duplicate names, directories, traversal paths, symbolic link destinations, malformed text, invalid settings, and invalid keybox content are rejected before any staged value is written.

Sensitive staged byte arrays are cleared after the operation. Successful restore reloads configuration and reports that a reboot may be needed for early identity or property changes.

## Policy state

Backups include the validated version two policy state, including optional feature controls, independent System, Vendor, and Boot patch policies, and named profile configuration. Profile keybox entries remain references to validated keybox files rather than embedded private key material. Restore validates the policy state before publishing it and reloads one complete snapshot.

## Remote servers

Backups also include the Remote Server configuration, WebUI language file, debug logging marker, and the boot identity digests. The server configuration stays device-encrypted and is restored only when it decrypts and validates on the restoring device, so a backup can move Remote Server settings between installs on the same device but not to a device that holds a different encryption key. A backup without server settings preserves the server configuration already present instead of deleting it. Applying the Default profile removes all Remote Servers and their cached keybox content.

## Recovery guidance

Keep the password separate from the archive. Test an export before removing the original installation. If restore fails, review Logs and correct the archive or password rather than repeatedly changing unrelated settings.

[Return to the project overview](../README.md)
