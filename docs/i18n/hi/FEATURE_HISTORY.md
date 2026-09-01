# फीचर इतिहास

> **भाषाएँ:** [English](../../../FEATURE_HISTORY.md) · [Türkçe](../tr/FEATURE_HISTORY.md) · [العربية](../ar/FEATURE_HISTORY.md) · [Deutsch](../de/FEATURE_HISTORY.md) · [Español](../es/FEATURE_HISTORY.md) · [Русский](../ru/FEATURE_HISTORY.md) · [Bahasa Indonesia](../id/FEATURE_HISTORY.md) · **हिन्दी** · [中文](../zh/FEATURE_HISTORY.md)

यह पेज CleveresTricky की प्रमुख सुविधाओं के सार्वजनिक विकास इतिहास और सीधे GitHub रिकॉर्ड दर्ज करता है।

## Device identity और attestation

**#79, 2026-02-01**

App-specific configuration और `ATTESTATION_ID_*` handling, जिसमें IMEI और Serial शामिल हैं।

https://github.com/tryigit/CleveresTricky/pull/79

**#139, 2026-02-05**

Randomized device identity, जिसमें IMEI और Serial तथा WebUI generation शामिल है।

https://github.com/tryigit/CleveresTricky/pull/139

**#871, 2026-08-09**

App-facing dual-SIM/device identity controls for IMEI, IMEI2, MEID, IMSI, ICCID, phone number और Serial, साथ में application/profile scope और runtime lifecycle।

https://github.com/tryigit/CleveresTricky/pull/871

## Keybox और attestation

**#77, 2026-02-01**

Multi-keybox loading, rotation और WebUI management।

https://github.com/tryigit/CleveresTricky/pull/77

**#79, 2026-02-01**

Keybox verification और application-specific attestation identity handling।

https://github.com/tryigit/CleveresTricky/pull/79

## Profiles, application scope और runtime

**#376**

WebUI के माध्यम से profile और configuration management।

https://github.com/tryigit/CleveresTricky/pull/376

**#908**

Granular policy state, independent Security Patch controls, profiles और Effective State।

https://github.com/tryigit/CleveresTricky/pull/908

**#909**

Identity policy, application customization, profile assignment, runtime identity और WebUI work।

https://github.com/tryigit/CleveresTricky/pull/909

## Native और Rust architecture

**#876, 2026-08-09**

Rust/native injector और Binder interception migration, runtime lifecycle और native hardening।

https://github.com/tryigit/CleveresTricky/pull/876

## Identity, privacy और platform integration

**#476**

Early boot properties, Play Integrity related Build Identity, profiles और randomization।

https://github.com/tryigit/CleveresTricky/pull/476

**#618**

Telephony interceptor performance और identity refinements।

https://github.com/tryigit/CleveresTricky/pull/618

**#910**

Auto Identity, runtime diagnostics, WebUI और TEE performance refinements।

https://github.com/tryigit/CleveresTricky/pull/910

**#952**

Per-value और grouped identity randomization, Visible SIM controls, Camera Visibility और Identity runtime work।

https://github.com/tryigit/CleveresTricky/pull/952

## StrongBox

**#1132, 2026-08-30**

StrongBox to TEE redirection और attestation security-level harmonization। यह बदलाव बाद में revert किया गया और वर्तमान `master` में शामिल नहीं है।

https://github.com/tryigit/CleveresTricky/pull/1132

## समयरेखा

| तारीख | PR | क्षेत्र |
|---|---:|---|
| 2026-02-01 | #77 | Multi-keybox |
| 2026-02-01 | #79 | App-specific attestation identity |
| 2026-02-05 | #139 | Randomized device identity |
| 2026-08-09 | #871 | Dual-SIM और app-facing identity |
| 2026-08-09 | #876 | Rust/native runtime architecture |
| 2026-08-30 | #1132 | StrongBox और TEE redirection |
