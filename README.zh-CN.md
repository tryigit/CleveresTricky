# CleveresTricky

**语言：** [English](README.md) | [Türkçe](README.tr.md) | **简体中文** | [Español](README.es.md) | [Deutsch](README.de.md) | [Русский](README.ru.md) | [Bahasa Indonesia](README.id.md) | [हिन्दी](README.hi.md) | [العربية](README.ar.md)

[![Release](https://img.shields.io/github/v/release/tryigit/CleveresTricky?display_name=tag&sort=semver&label=Release)](https://github.com/tryigit/CleveresTricky/releases/latest)
[![下载量](https://img.shields.io/github/downloads/tryigit/CleveresTricky/total?color=0A84FF&label=%E4%B8%8B%E8%BD%BD%E9%87%8F)](https://github.com/tryigit/CleveresTricky/releases)
![Android](https://img.shields.io/badge/Android-12--17-3DDC84?logo=android&logoColor=white)
![Module](https://img.shields.io/badge/Module-KernelSU%20%7C%20APatch-6f42c1)

CleveresTricky 是面向 Android 12-17 的 KernelSU / APatch 模块。它把 Android Keystore 与证明兼容、Keybox/CBOX 管理、应用范围控制、可选身份设置、补丁级别控制和隐私工具整合到一个移动端 WebUI 中。

建议先使用默认设置，只开启自己真正需要的功能。

## 你可以做什么

- 管理、验证、选择和轮换 **Keybox/CBOX** 文件。
- 使用 Global Mode，或通过独立规则只作用于指定应用。
- 按需配置设备/build、证明、电话、地区和安全补丁展示。
- 保护 Remote Key Provisioning 流程，并在不声称绕过 DRM 的前提下降低受支持 DRM 标识符的暴露。
- 备份设置、查看实际生效状态，并通过 WebUI 或模块 Action 收集诊断信息。

## 快速开始

1. 从官方 [Releases](https://github.com/tryigit/CleveresTricky/releases/latest) 页面下载最新 ZIP。
2. 在 Android 正常运行时，通过 KernelSU 或 APatch 安装 ZIP。
3. 从模块管理器打开 CleveresTricky WebUI。
4. 只添加你拥有或被授权用于测试的 **Keybox 或 CBOX**。
5. 先保持默认配置，只在需要时启用身份、应用规则或隐私选项。

项目不会附带可直接使用的 Keybox 或私有证明密钥。

## 支持环境

- Android **12-17** / API **31-37**
- **ARM64** 和 **x86-64**
- **KernelSU** 和 **APatch**（推荐，提供完整 WebUI 支持）
- **Magisk**（无界面 headless 模式 / 通过 `/data/adb/cleverestricky/` 手动配置，[不推荐](https://tryigit.dev/advanced-android-root-architecture-concealment/)）

不支持 Recovery 安装。

## 需要了解

CleveresTricky 改善的是本地兼容路径。最终远程结果仍取决于真实设备、固件、认证状态、Google Play 服务、服务器策略以及你的配置，因此无法保证特定的 Play Integrity 或证明结果。

它不会真正重新锁定 Bootloader、改写 Verified Boot 测量值、改变硬件 Root of Trust、修改基带/调制解调器，也不会把 DRM 隐私功能变成 DRM 绕过工具。

请只使用你有权使用的配置和凭据。

## 了解更多

- [Strong Integrity 指南](docs/i18n/zh-CN/security/StrongIntegrityGuide.md) - 面向官方系统、AOSP 与第三方 ROM 的 Google Play Integrity (MEETS_STRONG_INTEGRITY) 快速配置指南。
- [Magisk 支持指南](docs/i18n/zh-CN/system/Magisk.md) - 面向 Magisk 用户的无界面手动配置与 root 隐藏指南。
- [Keybox Manager](docs/i18n/zh-CN/security/KeyboxManager.md) - Keybox/CBOX 加载、验证、选择和撤销检查。
- [Application Scope](docs/i18n/zh-CN/identity/ApplicationScope.md) 与 [Application Rules](docs/i18n/zh-CN/identity/ApplicationRules.md) - 决定功能作用于哪些应用。
- [Build Identity](docs/i18n/zh-CN/identity/BuildIdentity.md)、[Telephony Identity](docs/i18n/zh-CN/identity/TelephonyIdentity.md) 与 [Patch Levels](docs/i18n/zh-CN/identity/PatchLevels.md) - 可选身份控制。
- [RKP Protection](docs/i18n/zh-CN/security/RkpProtection.md) 与 [DRM Privacy](docs/i18n/zh-CN/system/DrmPassthrough.md) - 平台兼容和隐私行为。
- [Backup and Restore](docs/i18n/zh-CN/system/BackupRestore.md) - 加密配置备份与恢复。
- [Security Model](docs/i18n/zh-CN/security/SecurityModel.md) 与 [Installer](docs/i18n/zh-CN/system/Installer.md) - 信任边界和安装细节。

## 需要帮助？

可以使用 WebUI 的 **Logs** 页面或模块 **Action** 生成紧急诊断报告。诊断压缩包可能包含设备和系统信息，分享前请先检查内容。

常见问题和排查步骤请查看 [Diagnostics](docs/i18n/zh-CN/system/Diagnostics.md)。

## 项目

[更新记录](CHANGELOG.md) · [参与贡献](docs/i18n/zh-CN/CONTRIBUTING.md) · [语言](docs/i18n/zh-CN/LANGUAGES.md) · [许可证](LICENSING.md) · [捐赠](docs/i18n/zh-CN/DONATE.md) · [Telegram](https://t.me/cleverestech)
