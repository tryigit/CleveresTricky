# Installer

**Язык:** [English](../../../system/Installer.md) | [Türkçe](../../tr/system/Installer.md) | [简体中文](../../zh-CN/system/Installer.md) | [Español](../../es/system/Installer.md) | [Deutsch](../../de/system/Installer.md) | **Русский** | [Bahasa Indonesia](../../id/system/Installer.md) | [हिन्दी](../../hi/system/Installer.md) | [العربية](../../ar/system/Installer.md)

Ставит полный KernelSU/APatch/Magisk module на Android 12-17 ARM64/x86 64. KernelSU и APatch обеспечивают полную поддержку WebUI; Magisk работает в режиме headless (см. [Поддержка Magisk](Magisk.md)). Установка через recovery отклоняется.

Каждый payload имеет SHA 256; runtime отклоняет symlink/non-regular/unexpected files. Internal hashes не доказывают автора, поэтому официальный Release публикует `SHA256SUMS` и GitHub signed build provenance.
