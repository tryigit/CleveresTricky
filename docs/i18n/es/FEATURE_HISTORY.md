# Historial de funciones

> **Idiomas:** [English](../../../FEATURE_HISTORY.md) · [Türkçe](../tr/FEATURE_HISTORY.md) · [العربية](../ar/FEATURE_HISTORY.md) · [Deutsch](../de/FEATURE_HISTORY.md) · **Español** · [Русский](../ru/FEATURE_HISTORY.md) · [Bahasa Indonesia](../id/FEATURE_HISTORY.md) · [हिन्दी](../hi/FEATURE_HISTORY.md) · [中文](../zh/FEATURE_HISTORY.md)

Esta página registra el historial público de desarrollo de las funciones principales de CleveresTricky con enlaces directos a GitHub.

## Identidad del dispositivo y attestation

**#79, 2026-02-01**

Configuración por aplicación y gestión de `ATTESTATION_ID_*`, incluidos IMEI y Serial.

https://github.com/tryigit/CleveresTricky/pull/79

**#139, 2026-02-05**

Generación de identidad de dispositivo aleatoria, incluidos IMEI y Serial, mediante WebUI.

https://github.com/tryigit/CleveresTricky/pull/139

**#871, 2026-08-09**

Controles de identidad de dispositivo Dual-SIM orientados a aplicaciones para IMEI, IMEI2, MEID, IMSI, ICCID, número de teléfono y Serial, junto con perfiles y ciclo de vida en runtime.

https://github.com/tryigit/CleveresTricky/pull/871

## Keybox y attestation

**#77, 2026-02-01**

Carga, rotación y gestión WebUI de múltiples keyboxes.

https://github.com/tryigit/CleveresTricky/pull/77

**#79, 2026-02-01**

Verificación de Keybox y gestión de identidad de attestation por aplicación.

https://github.com/tryigit/CleveresTricky/pull/79

## Perfiles, alcance de aplicación y runtime

**#376**

Gestión de perfiles y configuración mediante WebUI.

https://github.com/tryigit/CleveresTricky/pull/376

**#908**

Estado de policy granular, controles independientes de Security Patch, perfiles y Effective State.

https://github.com/tryigit/CleveresTricky/pull/908

**#909**

Políticas de Identity, personalización de aplicaciones, asignación de perfiles, Identity en runtime y trabajo de WebUI.

https://github.com/tryigit/CleveresTricky/pull/909

## Arquitectura Native y Rust

**#876, 2026-08-09**

Migración del injector y Binder interception a Rust/Native, ciclo de vida en runtime y hardening nativo.

https://github.com/tryigit/CleveresTricky/pull/876

## Identity, privacidad e integración con la plataforma

**#476**

Propiedades de early boot, Build Identity relacionada con Play Integrity, perfiles y randomización.

https://github.com/tryigit/CleveresTricky/pull/476

**#618**

Rendimiento del Telephony interceptor y mejoras de identity.

https://github.com/tryigit/CleveresTricky/pull/618

**#910**

Auto Identity, diagnósticos de runtime, WebUI y mejoras de rendimiento TEE.

https://github.com/tryigit/CleveresTricky/pull/910

**#952**

Randomización de identity individual y agrupada, controles Visible SIM, Camera Visibility y trabajo de Identity runtime.

https://github.com/tryigit/CleveresTricky/pull/952

## StrongBox

**#1132, 2026-08-30**

Redirección StrongBox a TEE y armonización del nivel de seguridad de attestation. Este cambio fue revertido posteriormente y no forma parte del `master` actual.

https://github.com/tryigit/CleveresTricky/pull/1132

## Línea temporal

| Fecha | PR | Área |
|---|---:|---|
| 2026-02-01 | #77 | Multi-keybox |
| 2026-02-01 | #79 | App-specific attestation identity |
| 2026-02-05 | #139 | Randomized device identity |
| 2026-08-09 | #871 | Dual-SIM y app-facing identity |
| 2026-08-09 | #876 | Rust/native runtime architecture |
| 2026-08-30 | #1132 | StrongBox y TEE redirection |
