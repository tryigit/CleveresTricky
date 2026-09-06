# Feature History

> **Languages:** [English](FEATURE_HISTORY.md) · [Türkçe](docs/i18n/tr/FEATURE_HISTORY.md) · [العربية](docs/i18n/ar/FEATURE_HISTORY.md) · [Deutsch](docs/i18n/de/FEATURE_HISTORY.md) · [Español](docs/i18n/es/FEATURE_HISTORY.md) · [Русский](docs/i18n/ru/FEATURE_HISTORY.md) · [Bahasa Indonesia](docs/i18n/id/FEATURE_HISTORY.md) · [हिन्दी](docs/i18n/hi/FEATURE_HISTORY.md) · [中文](docs/i18n/zh/FEATURE_HISTORY.md)

This page records the public development history of major CleveresTricky features, with direct links to the relevant GitHub records.

## Device identity and attestation

- **#79, 2026-02-01**
  App-specific configuration and `ATTESTATION_ID_*` handling, including IMEI and Serial.
  https://github.com/tryigit/CleveresTricky/pull/79

- **#139, 2026-02-05**
  Randomized device identity, including IMEI and Serial, with WebUI-triggered generation.
  https://github.com/tryigit/CleveresTricky/pull/139

- **#871, 2026-08-09**
  App-facing dual-SIM/device identity controls for IMEI, IMEI2, MEID, IMSI, ICCID, phone number and Serial, together with application/profile scope and runtime lifecycle work.
  https://github.com/tryigit/CleveresTricky/pull/871

## Keybox and attestation

- **#77, 2026-02-01**
  Multi-keybox loading, rotation and WebUI management.
  https://github.com/tryigit/CleveresTricky/pull/77

- **#79, 2026-02-01**
  Keybox verification and application-specific attestation identity handling.
  https://github.com/tryigit/CleveresTricky/pull/79

- **#1199, 2026-09-06**
  Capability-aware StrongBox fallback routing, caller-selected App AttestKey signature preservation, security-level candidate filtering, and WebUI hardware badges.
  https://github.com/tryigit/CleveresTricky/pull/1199

## Profiles, application scope and runtime controls

- **#376**
  Profile and configuration management through the WebUI.
  https://github.com/tryigit/CleveresTricky/pull/376

- **#908**
  Granular policy state, independent security patch controls, profiles and effective-state handling.
  https://github.com/tryigit/CleveresTricky/pull/908

- **#909**
  Identity policies, app customization, profile assignment, runtime identity handling and related WebUI work.
  https://github.com/tryigit/CleveresTricky/pull/909

## Native and Rust architecture

- **#876, 2026-08-09**
  Rust/native injector and Binder interception migration, runtime lifecycle handling and native hardening.
  https://github.com/tryigit/CleveresTricky/pull/876

## Identity, privacy and platform integration

- **#476**
  Early boot property handling, Play Integrity related build identity work, profiles and randomization.
  https://github.com/tryigit/CleveresTricky/pull/476

- **#618**
  Telephony interceptor performance and identity handling refinements.
  https://github.com/tryigit/CleveresTricky/pull/618

- **#910**
  Auto Identity, runtime diagnostics, WebUI and TEE performance refinements.
  https://github.com/tryigit/CleveresTricky/pull/910

- **#952**
  Per-value and grouped identity randomization, visible SIM controls, camera visibility and related Identity runtime work.
  https://github.com/tryigit/CleveresTricky/pull/952

## StrongBox

- **#1132, 2026-08-30**
  StrongBox to TEE redirection and attestation security-level harmonization. This change was later reverted and is not part of the current `master` state.
  https://github.com/tryigit/CleveresTricky/pull/1132

- **#1199, 2026-09-06**
  Capability-aware StrongBox fallback routing: early rejection before native KeyMint generation when no StrongBox keybox is present, preventing hardware alias conflicts and ensuring smooth TEE fallback.
  https://github.com/tryigit/CleveresTricky/pull/1199

## Feature timeline

| Date | PR | Area |
|---|---:|---|
| 2026-02-01 | #77 | Multi-keybox management and rotation |
| 2026-02-01 | #79 | App-specific attestation identity |
| 2026-02-05 | #139 | Randomized device identity |
| 2026-08-09 | #871 | Dual-SIM and app-facing identity |
| 2026-08-09 | #876 | Rust/native runtime architecture |
| 2026-08-30 | #1132 | StrongBox and TEE redirection |
| 2026-09-06 | #1199 | Capability-aware StrongBox routing & AttestKey parity |

All links above point directly to the project's public GitHub development records.
