# Backup and Restore

**Sprache:** [English](../../../system/BackupRestore.md) | [Türkçe](../../tr/system/BackupRestore.md) | [简体中文](../../zh-CN/system/BackupRestore.md) | [Español](../../es/system/BackupRestore.md) | **Deutsch** | [Русский](../../ru/system/BackupRestore.md) | [Bahasa Indonesia](../../id/system/BackupRestore.md) | [हिन्दी](../../hi/system/BackupRestore.md) | [العربية](../../ar/system/BackupRestore.md)

Verschiebt Konfiguration und autorisiertes Schlüsselmaterial in einem authentifiziert verschlüsselten Archiv. Export verlangt mindestens 12 Zeichen Passwort und verwendet eine Allowlist; Symlinks, unbekannte Pfade und Größenüberschreitungen werden verworfen.

Import akzeptiert nur CTSB und begrenzt Upload, Entries, Keyboxes sowie Einzel- und Gesamtgröße. Traversal, Duplikate, Verzeichnisse, Symlink-Ziele, fehlerhafte Settings und Keyboxes werden vor dem Schreiben abgewiesen. Policy v2 wird als vollständiger Snapshot validiert. Remote-Server-Einstellungen werden ebenfalls gerätegebunden verschlüsselt eingeschlossen; sie werden nur wiederhergestellt, wenn sie auf demselben Gerät entschlüsselt und validiert werden, und ohne Eintrag bleiben vorhandene Server-Einstellungen erhalten. Das Default-Profil entfernt alle Remote-Server samt zwischengespeichertem Keybox-Inhalt.
