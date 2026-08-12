# Backup and Restore

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

## Recovery guidance

Keep the password separate from the archive. Test an export before removing the original installation. If restore fails, review Logs and correct the archive or password rather than repeatedly changing unrelated settings.

[Return to the project overview](../README.md)
