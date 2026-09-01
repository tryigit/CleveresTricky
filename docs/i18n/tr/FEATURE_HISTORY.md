# Özellik Geçmişi

> **Diller:** [English](../../../FEATURE_HISTORY.md) · **Türkçe** · [العربية](../ar/FEATURE_HISTORY.md) · [Deutsch](../de/FEATURE_HISTORY.md) · [Español](../es/FEATURE_HISTORY.md) · [Русский](../ru/FEATURE_HISTORY.md) · [Bahasa Indonesia](../id/FEATURE_HISTORY.md) · [हिन्दी](../hi/FEATURE_HISTORY.md) · [中文](../zh/FEATURE_HISTORY.md)

Bu sayfa CleveresTricky'nin önemli özelliklerinin herkese açık geliştirme geçmişini ve doğrudan GitHub kayıtlarını içerir.

## Cihaz kimliği ve attestation

**#79, 2026-02-01**

Uygulamaya özel yapılandırma ve `ATTESTATION_ID_*` işlemleri. IMEI ve Serial dahil attestation kimliği alanları.

https://github.com/tryigit/CleveresTricky/pull/79

**#139, 2026-02-05**

IMEI ve Serial dahil rastgele cihaz kimliği üretimi ve WebUI üzerinden üretim.

https://github.com/tryigit/CleveresTricky/pull/139

**#871, 2026-08-09**

IMEI, IMEI2, MEID, IMSI, ICCID, telefon numarası ve Serial için uygulama kapsamlı dual-SIM cihaz kimliği kontrolleri. Profil ve runtime lifecycle desteği.

https://github.com/tryigit/CleveresTricky/pull/871

## Keybox ve attestation

**#77, 2026-02-01**

Çoklu keybox yükleme, rotasyon ve WebUI yönetimi.

https://github.com/tryigit/CleveresTricky/pull/77

**#79, 2026-02-01**

Keybox doğrulama ve uygulama özelinde attestation kimliği işlemleri.

https://github.com/tryigit/CleveresTricky/pull/79

## Profil, uygulama kapsamı ve runtime

**#376**

Profil ve yapılandırma yönetimi, WebUI üzerinden profil uygulama akışı.

https://github.com/tryigit/CleveresTricky/pull/376

**#908**

Ayrıntılı policy state, bağımsız security patch kontrolleri, profiller ve Effective State.

https://github.com/tryigit/CleveresTricky/pull/908

**#909**

Identity policy, uygulama özelleştirme, profil atama, runtime identity ve WebUI çalışmaları.

https://github.com/tryigit/CleveresTricky/pull/909

## Native ve Rust mimarisi

**#876, 2026-08-09**

Rust/native injector ve Binder interception dönüşümü, runtime lifecycle ve native hardening.

https://github.com/tryigit/CleveresTricky/pull/876

## Identity, privacy ve platform entegrasyonu

**#476**

Early boot property işlemleri, Play Integrity ile ilişkili Build Identity, profiller ve randomization.

https://github.com/tryigit/CleveresTricky/pull/476

**#618**

Telephony interceptor performans ve identity düzenlemeleri.

https://github.com/tryigit/CleveresTricky/pull/618

**#910**

Auto Identity, runtime diagnostics, WebUI ve TEE performans düzenlemeleri.

https://github.com/tryigit/CleveresTricky/pull/910

**#952**

Tekil ve gruplu identity randomization, visible SIM, camera visibility ve Identity runtime çalışmaları.

https://github.com/tryigit/CleveresTricky/pull/952

## StrongBox

**#1132, 2026-08-30**

StrongBox to TEE redirection ve attestation security-level harmonization. Bu çalışma daha sonra revert edildi ve mevcut `master` içinde bulunmuyor.

https://github.com/tryigit/CleveresTricky/pull/1132

## Zaman çizelgesi

| Tarih | PR | Alan |
|---|---:|---|
| 2026-02-01 | #77 | Multi-keybox |
| 2026-02-01 | #79 | App-specific attestation identity |
| 2026-02-05 | #139 | Randomized device identity |
| 2026-08-09 | #871 | Dual-SIM ve app-facing identity |
| 2026-08-09 | #876 | Rust/native runtime architecture |
| 2026-08-30 | #1132 | StrongBox ve TEE redirection |
