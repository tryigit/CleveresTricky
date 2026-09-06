# 功能历史

> **语言:** [English](../../FEATURE_HISTORY.md) · [Türkçe](../tr/FEATURE_HISTORY.md) · [العربية](../ar/FEATURE_HISTORY.md) · [Deutsch](../de/FEATURE_HISTORY.md) · [Español](../es/FEATURE_HISTORY.md) · [Русский](../ru/FEATURE_HISTORY.md) · [Bahasa Indonesia](../id/FEATURE_HISTORY.md) · [हिन्दी](../hi/FEATURE_HISTORY.md) · **中文**

本页面记录 CleveresTricky 主要功能的公开开发历史，并提供直接的 GitHub 记录链接。

## 设备身份与 Attestation

**#79, 2026-02-01**

应用级配置与 `ATTESTATION_ID_*` 处理，包括 IMEI 和 Serial。

https://github.com/tryigit/CleveresTricky/pull/79

**#139, 2026-02-05**

随机设备身份生成，包括 IMEI 和 Serial，以及 WebUI 触发的生成流程。

https://github.com/tryigit/CleveresTricky/pull/139

**#871, 2026-08-09**

应用级 Dual-SIM 设备身份控制，包括 IMEI、IMEI2、MEID、IMSI、ICCID、电话号码和 Serial，以及应用/配置文件范围和运行时生命周期。

https://github.com/tryigit/CleveresTricky/pull/871

## Keybox 与 Attestation

**#77, 2026-02-01**

多 Keybox 加载、轮换和 WebUI 管理。

https://github.com/tryigit/CleveresTricky/pull/77

**#79, 2026-02-01**

Keybox 验证和应用级 Attestation 身份处理。

https://github.com/tryigit/CleveresTricky/pull/79

**#1199, 2026-09-06**

支持功能感知的 StrongBox 回退路由、保留 App AttestKey 硬件签名、按安全级别筛选候选密钥库以及 WebUI 硬件级别标签。

https://github.com/tryigit/CleveresTricky/pull/1199

## Profile、应用范围与 Runtime

**#376**

通过 WebUI 管理 Profile 和配置。

https://github.com/tryigit/CleveresTricky/pull/376

**#908**

细粒度 Policy State、独立 Security Patch 控制、Profiles 和 Effective State。

https://github.com/tryigit/CleveresTricky/pull/908

**#909**

Identity Policy、应用自定义、Profile 分配、Runtime Identity 和 WebUI 工作。

https://github.com/tryigit/CleveresTricky/pull/909

## Native 与 Rust 架构

**#876, 2026-08-09**

Rust/Native injector 和 Binder interception 迁移、Runtime lifecycle 以及 native hardening。

https://github.com/tryigit/CleveresTricky/pull/876

## Identity、Privacy 与平台集成

**#476**

Early boot properties、与 Play Integrity 相关的 Build Identity、Profiles 和 Randomization。

https://github.com/tryigit/CleveresTricky/pull/476

**#618**

Telephony interceptor 性能和 Identity 优化。

https://github.com/tryigit/CleveresTricky/pull/618

**#910**

Auto Identity、Runtime Diagnostics、WebUI 和 TEE 性能优化。

https://github.com/tryigit/CleveresTricky/pull/910

**#952**

按字段和分组的 Identity Randomization、Visible SIM、Camera Visibility 和 Identity Runtime 工作。

https://github.com/tryigit/CleveresTricky/pull/952

## StrongBox

**#1132, 2026-08-30**

StrongBox 到 TEE 的重定向以及 Attestation 安全级别统一。此更改后来被 revert，因此当前 `master` 不包含它。

https://github.com/tryigit/CleveresTricky/pull/1132

**#1199, 2026-09-06**

功能感知的 StrongBox 回退路由：在缺少 StrongBox 密钥库时，于原生 KeyMint 生成前提前拒绝，防止别名冲突并确保平稳回退至 TEE。

https://github.com/tryigit/CleveresTricky/pull/1199

## 时间线

| 日期 | PR | 领域 |
|---|---:|---|
| 2026-02-01 | #77 | Multi-keybox |
| 2026-02-01 | #79 | App-specific attestation identity |
| 2026-02-05 | #139 | Randomized device identity |
| 2026-08-09 | #871 | Dual-SIM 与 app-facing identity |
| 2026-08-09 | #876 | Rust/native runtime architecture |
| 2026-08-30 | #1132 | StrongBox 与 TEE redirection |
| 2026-09-06 | #1199 | StrongBox 路由与 AttestKey 兼容性 |
