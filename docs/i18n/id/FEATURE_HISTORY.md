# Riwayat Fitur

> **Bahasa:** [English](../../../FEATURE_HISTORY.md) · [Türkçe](../tr/FEATURE_HISTORY.md) · [العربية](../ar/FEATURE_HISTORY.md) · [Deutsch](../de/FEATURE_HISTORY.md) · [Español](../es/FEATURE_HISTORY.md) · [Русский](../ru/FEATURE_HISTORY.md) · **Bahasa Indonesia** · [हिन्दी](../hi/FEATURE_HISTORY.md) · [中文](../zh/FEATURE_HISTORY.md)

Halaman ini mencatat riwayat publik pengembangan fitur utama CleveresTricky dengan tautan langsung ke catatan GitHub.

## Identitas perangkat dan attestation

**#79, 2026-02-01**

Konfigurasi khusus aplikasi dan penanganan `ATTESTATION_ID_*`, termasuk IMEI dan Serial.

https://github.com/tryigit/CleveresTricky/pull/79

**#139, 2026-02-05**

Pembuatan identitas perangkat acak, termasuk IMEI dan Serial, melalui WebUI.

https://github.com/tryigit/CleveresTricky/pull/139

**#871, 2026-08-09**

Kontrol identitas perangkat Dual-SIM untuk IMEI, IMEI2, MEID, IMSI, ICCID, nomor telepon, dan Serial, dengan cakupan aplikasi/profil dan runtime lifecycle.

https://github.com/tryigit/CleveresTricky/pull/871

## Keybox dan attestation

**#77, 2026-02-01**

Pemuatan, rotasi, dan pengelolaan multi-keybox melalui WebUI.

https://github.com/tryigit/CleveresTricky/pull/77

**#79, 2026-02-01**

Verifikasi Keybox dan penanganan identitas attestation khusus aplikasi.

https://github.com/tryigit/CleveresTricky/pull/79

## Profil, cakupan aplikasi, dan runtime

**#376**

Pengelolaan profil dan konfigurasi melalui WebUI.

https://github.com/tryigit/CleveresTricky/pull/376

**#908**

Granular policy state, kontrol Security Patch independen, profil, dan Effective State.

https://github.com/tryigit/CleveresTricky/pull/908

**#909**

Identity policy, kustomisasi aplikasi, penugasan profil, runtime identity, dan WebUI.

https://github.com/tryigit/CleveresTricky/pull/909

## Arsitektur Native dan Rust

**#876, 2026-08-09**

Migrasi injector dan Binder interception ke Rust/Native, runtime lifecycle, dan native hardening.

https://github.com/tryigit/CleveresTricky/pull/876

## Identity, privacy, dan integrasi platform

**#476**

Early boot properties, Build Identity terkait Play Integrity, profil, dan randomization.

https://github.com/tryigit/CleveresTricky/pull/476

**#618**

Peningkatan performa Telephony interceptor dan identity.

https://github.com/tryigit/CleveresTricky/pull/618

**#910**

Auto Identity, runtime diagnostics, WebUI, dan optimasi TEE.

https://github.com/tryigit/CleveresTricky/pull/910

**#952**

Randomization identity per nilai dan grup, Visible SIM, Camera Visibility, dan Identity runtime.

https://github.com/tryigit/CleveresTricky/pull/952

## StrongBox

**#1132, 2026-08-30**

Pengalihan StrongBox ke TEE dan harmonisasi security level attestation. Perubahan ini kemudian di-revert dan tidak termasuk dalam `master` saat ini.

https://github.com/tryigit/CleveresTricky/pull/1132

## Linimasa

| Tanggal | PR | Area |
|---|---:|---|
| 2026-02-01 | #77 | Multi-keybox |
| 2026-02-01 | #79 | App-specific attestation identity |
| 2026-02-05 | #139 | Randomized device identity |
| 2026-08-09 | #871 | Dual-SIM dan app-facing identity |
| 2026-08-09 | #876 | Rust/native runtime architecture |
| 2026-08-30 | #1132 | StrongBox dan TEE redirection |
