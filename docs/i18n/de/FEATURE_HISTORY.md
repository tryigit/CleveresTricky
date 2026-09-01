# Feature-Historie

> **Sprachen:** [English](../../../FEATURE_HISTORY.md) · [Türkçe](../tr/FEATURE_HISTORY.md) · [العربية](../ar/FEATURE_HISTORY.md) · **Deutsch** · [Español](../es/FEATURE_HISTORY.md) · [Русский](../ru/FEATURE_HISTORY.md) · [Bahasa Indonesia](../id/FEATURE_HISTORY.md) · [हिन्दी](../hi/FEATURE_HISTORY.md) · [中文](../zh/FEATURE_HISTORY.md)

Diese Seite dokumentiert die öffentliche Entwicklung wichtiger CleveresTricky-Funktionen mit direkten GitHub-Links.

## Geräteidentität und Attestation

**#79, 2026-02-01**

App-spezifische Konfiguration und `ATTESTATION_ID_*` Verarbeitung, einschließlich IMEI und Serial.

https://github.com/tryigit/CleveresTricky/pull/79

**#139, 2026-02-05**

Zufällige Geräteidentität, einschließlich IMEI und Serial, mit Erzeugung über die WebUI.

https://github.com/tryigit/CleveresTricky/pull/139

**#871, 2026-08-09**

App-bezogene Dual-SIM-Geräteidentitätskontrollen für IMEI, IMEI2, MEID, IMSI, ICCID, Telefonnummer und Serial sowie Profil- und Runtime-Lifecycle-Unterstützung.

https://github.com/tryigit/CleveresTricky/pull/871

## Keybox und Attestation

**#77, 2026-02-01**

Multi-Keybox-Laden, Rotation und WebUI-Verwaltung.

https://github.com/tryigit/CleveresTricky/pull/77

**#79, 2026-02-01**

Keybox-Verifizierung und app-spezifische Attestation-Identität.

https://github.com/tryigit/CleveresTricky/pull/79

## Profile, App-Scope und Runtime

**#376**

Profil- und Konfigurationsverwaltung über die WebUI.

https://github.com/tryigit/CleveresTricky/pull/376

**#908**

Granulare Policy-Zustände, unabhängige Security-Patch-Kontrollen, Profile und Effective State.

https://github.com/tryigit/CleveresTricky/pull/908

**#909**

Identity-Policy, App-Anpassung, Profilzuweisung, Runtime-Identity und WebUI-Arbeiten.

https://github.com/tryigit/CleveresTricky/pull/909

## Native- und Rust-Architektur

**#876, 2026-08-09**

Rust/Native-Injector und Binder-Interception-Migration, Runtime-Lifecycle und Native-Hardening.

https://github.com/tryigit/CleveresTricky/pull/876

## Identity, Privacy und Plattformintegration

**#476**

Early-Boot-Property-Verarbeitung, Play-Integrity-bezogene Build Identity, Profile und Randomisierung.

https://github.com/tryigit/CleveresTricky/pull/476

**#618**

Telephony-Interceptor-Performance und Identity-Verbesserungen.

https://github.com/tryigit/CleveresTricky/pull/618

**#910**

Auto Identity, Runtime-Diagnose, WebUI und TEE-Performance-Verbesserungen.

https://github.com/tryigit/CleveresTricky/pull/910

**#952**

Einzelne und gruppierte Identity-Randomisierung, Visible-SIM-Kontrollen, Camera Visibility und Identity-Runtime-Arbeiten.

https://github.com/tryigit/CleveresTricky/pull/952

## StrongBox

**#1132, 2026-08-30**

StrongBox-to-TEE-Weiterleitung und Harmonisierung des Attestation-Sicherheitslevels. Diese Änderung wurde später zurückgesetzt und ist nicht Bestandteil des aktuellen `master`.

https://github.com/tryigit/CleveresTricky/pull/1132

## Zeitlinie

| Datum | PR | Bereich |
|---|---:|---|
| 2026-02-01 | #77 | Multi-Keybox |
| 2026-02-01 | #79 | App-spezifische Attestation-Identity |
| 2026-02-05 | #139 | Randomisierte Geräteidentität |
| 2026-08-09 | #871 | Dual-SIM und App-facing Identity |
| 2026-08-09 | #876 | Rust/Native Runtime-Architektur |
| 2026-08-30 | #1132 | StrongBox und TEE-Weiterleitung |
