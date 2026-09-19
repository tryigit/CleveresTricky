# Profiles

**语言:** [English](../../../identity/Profiles.md) | [Türkçe](../../tr/identity/Profiles.md) | **简体中文** | [Español](../../es/identity/Profiles.md) | [Deutsch](../../de/identity/Profiles.md) | [Русский](../../ru/identity/Profiles.md) | [Bahasa Indonesia](../../id/identity/Profiles.md) | [हिन्दी](../../hi/identity/Profiles.md) | [العربية](../../ar/identity/Profiles.md)

Profiles 以一次经过验证的事务应用一组可选设置；核心 boot、Keystore 与 RKP 基础设施保护始终独立保持启用。

Daily Compatibility 使用定向范围和 keybox 监控；Default 是保守的可选身份配置（应用 Default 预设或在 WebUI 中重置环境会将 `target.txt`、`identity_target.txt`、`drm_packages.txt`、`boot_props_mode` 与 `security_patch.txt` 恢复为初始模板默认值，并移除所有已配置的远程服务器及其缓存的 keybox 内容）；Maximum Compatibility 启用 Global Mode、build identity、identity refresh 与 telephony，并关闭 DRM passthrough；Minimal 关闭可选身份和计划 keybox 检查。这些预设都不会改变 RKP 基础设施保护。

旧配置可能仍包含已退役的 `rkp_passthrough` 标记，但运行时的 generated-key 行为不再依赖它。Version two profile 可保存应用分配、template、已验证 keybox、privacy、patch 以及可选 identity/DRM 设置；旧 RKP 字段仅用于迁移兼容，不再作为 WebUI 的实时选项。
