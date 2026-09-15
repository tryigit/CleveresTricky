# Installer

**语言:** [English](../../../system/Installer.md) | [Türkçe](../../tr/system/Installer.md) | **简体中文** | [Español](../../es/system/Installer.md) | [Deutsch](../../de/system/Installer.md) | [Русский](../../ru/system/Installer.md) | [Bahasa Indonesia](../../id/system/Installer.md) | [हिन्दी](../../hi/system/Installer.md) | [العربية](../../ar/system/Installer.md)

Installer 安装完整 KernelSU/APatch/Magisk 模块，包括 service、native payload、scripts、policy、metadata 与 integrity records。支持 Android 12-17、ARM64、x86 64；KernelSU 和 APatch 提供完整 WebUI 支持，Magisk 则以无界面的 headless 模式运行（详见 [Magisk 支持](Magisk.md)）。Recovery 安装途径会被拒绝。

每个 packaged payload 都有 SHA 256 记录，安装和 runtime 都检查 regular file、symlink 与 unexpected payload。Archive 内部 hash 不是发布者身份凭证，因此官方 Release 另有 `SHA256SUMS` 与 GitHub signed build provenance。
