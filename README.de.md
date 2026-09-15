# CleveresTricky

**Sprache:** [English](README.md) | [Türkçe](README.tr.md) | [简体中文](README.zh-CN.md) | [Español](README.es.md) | **Deutsch** | [Русский](README.ru.md) | [Bahasa Indonesia](README.id.md) | [हिन्दी](README.hi.md) | [العربية](README.ar.md)

[![Release](https://img.shields.io/github/v/release/tryigit/CleveresTricky?display_name=tag&sort=semver&label=Release)](https://github.com/tryigit/CleveresTricky/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/tryigit/CleveresTricky/total?color=0A84FF&label=Downloads)](https://github.com/tryigit/CleveresTricky/releases)
![Android](https://img.shields.io/badge/Android-12--17-3DDC84?logo=android&logoColor=white)
![Module](https://img.shields.io/badge/Module-KernelSU%20%7C%20APatch-6f42c1)

CleveresTricky ist ein KernelSU- und APatch-Modul für Android 12-17. Es bündelt Android-Keystore- und Attestation-Kompatibilität, Keybox/CBOX-Verwaltung, App-Zielauswahl, optionale Identitätssteuerung, Patch-Level-Einstellungen und Datenschutzfunktionen in einer mobilen WebUI.

Beginne mit den Standardwerten und aktiviere nur die Funktionen, die du wirklich brauchst.

## Was du damit machen kannst

- **Keybox/CBOX**-Dateien verwalten, prüfen, auswählen und wechseln.
- Global Mode verwenden oder einzelne Apps mit eigenen Regeln auswählen.
- Geräte-/Build-, Attestation-, Telefonie-, Regions- und Security-Patch-Darstellung optional konfigurieren.
- Remote-Key-Provisioning-Abläufe schützen und die unterstützte DRM-Identifier-Exposition reduzieren, ohne einen DRM-Bypass vorzutäuschen.
- Einstellungen sichern, den effektiven Zustand prüfen und Diagnosen über WebUI oder die Modul-Action sammeln.

## Schnellstart

1. Lade die aktuelle ZIP von der offiziellen [Releases](https://github.com/tryigit/CleveresTricky/releases/latest)-Seite herunter.
2. Installiere die ZIP über KernelSU oder APatch, während Android läuft.
3. Öffne die CleveresTricky-WebUI über deinen Modulmanager.
4. Füge nur eine **Keybox oder CBOX** hinzu, die dir gehört oder die du testen darfst.
5. Verwende zuerst die Standardkonfiguration und aktiviere Identität, App-Regeln oder Datenschutzoptionen nur bei Bedarf.

Das Projekt enthält keine verwendbare Keybox und keinen privaten Attestation-Schlüssel.

## Unterstützte Umgebung

- Android **12-17** / API **31-37**
- **ARM64** und **x86-64**
- **KernelSU** und **APatch** (empfohlen, volle WebUI-Unterstützung)
- **Magisk** (headless / manuelle Konfiguration über `/data/adb/cleverestricky/`, [nicht empfohlen](https://tryigit.dev/advanced-android-root-architecture-concealment/))

Recovery-Installationen werden nicht unterstützt.

## Wichtig zu wissen

CleveresTricky verbessert den lokalen Kompatibilitätspfad. Remote-Ergebnisse hängen trotzdem vom echten Gerät, der Firmware, dem Zertifizierungsstatus, Google Play services, Serverrichtlinien und deiner Konfiguration ab. Ein bestimmtes Play-Integrity- oder Attestation-Ergebnis kann nicht garantiert werden.

Das Modul sperrt den Bootloader nicht physisch wieder, schreibt Verified-Boot-Messwerte nicht um, verändert den Hardware Root of Trust nicht, ändert weder Modem noch Baseband und macht aus DRM-Datenschutzfunktionen keinen DRM-Bypass.

Verwende nur Konfigurationen und Zugangsdaten, für deren Nutzung du berechtigt bist.

## Mehr erfahren

- [Strong Integrity Leitfaden](docs/i18n/de/security/StrongIntegrityGuide.md) - Schnellstartanleitung zum Bestehen von Google Play Integrity (MEETS_STRONG_INTEGRITY) für offizielle, AOSP- und Custom-ROMs.
- [Magisk-Unterstützung Leitfaden](docs/i18n/de/system/Magisk.md) - Headless-Handbuch und Root-Verschleierungshinweise für Magisk-Nutzer.
- [Keybox Manager](docs/i18n/de/security/KeyboxManager.md) - Laden, Prüfen, Auswählen und Revocation-Checks für Keybox/CBOX.
- [Application Scope](docs/i18n/de/identity/ApplicationScope.md) und [Application Rules](docs/i18n/de/identity/ApplicationRules.md) - festlegen, für welche Apps Funktionen gelten.
- [Build Identity](docs/i18n/de/identity/BuildIdentity.md), [Telephony Identity](docs/i18n/de/identity/TelephonyIdentity.md) und [Patch Levels](docs/i18n/de/identity/PatchLevels.md) - optionale Identitätssteuerung.
- [RKP Protection](docs/i18n/de/security/RkpProtection.md) und [DRM Privacy](docs/i18n/de/system/DrmPassthrough.md) - Plattformkompatibilität und Datenschutz.
- [Backup and Restore](docs/i18n/de/system/BackupRestore.md) - verschlüsselte Sicherung und Wiederherstellung der Konfiguration.
- [Security Model](docs/i18n/de/security/SecurityModel.md) und [Installer](docs/i18n/de/system/Installer.md) - Vertrauensgrenzen und Installationsdetails.

## Hilfe benötigt?

Nutze die **Logs**-Seite der WebUI oder die Modul-**Action**, um einen Notfall-Diagnosebericht zu erstellen. Prüfe das Archiv vor dem Teilen, da es Geräte- und Systeminformationen enthalten kann.

Unter [Diagnostics](docs/i18n/de/system/Diagnostics.md) findest du häufige Probleme und Schritte zur Fehlerbehebung.

## Projekt

[Changelog](CHANGELOG.md) · [Mitwirken](docs/i18n/de/CONTRIBUTING.md) · [Sprachen](docs/i18n/de/LANGUAGES.md) · [Lizenz](LICENSING.md) · [Spenden](docs/i18n/de/DONATE.md) · [Telegram](https://t.me/cleverestech)
