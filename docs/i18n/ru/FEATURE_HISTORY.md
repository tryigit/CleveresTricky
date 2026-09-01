# История функций

> **Языки:** [English](../../../FEATURE_HISTORY.md) · [Türkçe](../tr/FEATURE_HISTORY.md) · [العربية](../ar/FEATURE_HISTORY.md) · [Deutsch](../de/FEATURE_HISTORY.md) · [Español](../es/FEATURE_HISTORY.md) · **Русский** · [Bahasa Indonesia](../id/FEATURE_HISTORY.md) · [हिन्दी](../hi/FEATURE_HISTORY.md) · [中文](../zh/FEATURE_HISTORY.md)

Эта страница содержит публичную историю разработки основных функций CleveresTricky и прямые ссылки на записи GitHub.

## Идентичность устройства и attestation

**#79, 2026-02-01**

Конфигурация для приложений и обработка `ATTESTATION_ID_*`, включая IMEI и Serial.

https://github.com/tryigit/CleveresTricky/pull/79

**#139, 2026-02-05**

Случайная генерация идентичности устройства, включая IMEI и Serial, через WebUI.

https://github.com/tryigit/CleveresTricky/pull/139

**#871, 2026-08-09**

App-facing Dual-SIM управление IMEI, IMEI2, MEID, IMSI, ICCID, номером телефона и Serial, а также профили, app scope и runtime lifecycle.

https://github.com/tryigit/CleveresTricky/pull/871

## Keybox и attestation

**#77, 2026-02-01**

Загрузка, ротация и управление несколькими keybox через WebUI.

https://github.com/tryigit/CleveresTricky/pull/77

**#79, 2026-02-01**

Проверка Keybox и app-specific обработка attestation identity.

https://github.com/tryigit/CleveresTricky/pull/79

## Профили, app scope и runtime

**#376**

Управление профилями и конфигурацией через WebUI.

https://github.com/tryigit/CleveresTricky/pull/376

**#908**

Granular policy state, независимые Security Patch controls, профили и Effective State.

https://github.com/tryigit/CleveresTricky/pull/908

**#909**

Identity policy, настройка приложений, назначение профилей, runtime identity и WebUI.

https://github.com/tryigit/CleveresTricky/pull/909

## Native и Rust архитектура

**#876, 2026-08-09**

Миграция injector и Binder interception на Rust/Native, runtime lifecycle и native hardening.

https://github.com/tryigit/CleveresTricky/pull/876

## Identity, privacy и интеграция с платформой

**#476**

Early boot properties, Build Identity для Play Integrity, профили и randomization.

https://github.com/tryigit/CleveresTricky/pull/476

**#618**

Оптимизация Telephony interceptor и улучшения identity.

https://github.com/tryigit/CleveresTricky/pull/618

**#910**

Auto Identity, runtime diagnostics, WebUI и оптимизация TEE performance.

https://github.com/tryigit/CleveresTricky/pull/910

**#952**

Индивидуальная и групповая randomization identity, Visible SIM, Camera Visibility и Identity runtime.

https://github.com/tryigit/CleveresTricky/pull/952

## StrongBox

**#1132, 2026-08-30**

Перенаправление StrongBox в TEE и согласование security level attestation. Изменение было позже отменено и не входит в текущий `master`.

https://github.com/tryigit/CleveresTricky/pull/1132

## Временная шкала

| Дата | PR | Область |
|---|---:|---|
| 2026-02-01 | #77 | Multi-keybox |
| 2026-02-01 | #79 | App-specific attestation identity |
| 2026-02-05 | #139 | Randomized device identity |
| 2026-08-09 | #871 | Dual-SIM и app-facing identity |
| 2026-08-09 | #876 | Rust/native runtime architecture |
| 2026-08-30 | #1132 | StrongBox и TEE redirection |
