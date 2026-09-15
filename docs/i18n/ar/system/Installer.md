# Installer

**اللغة:** [English](../../../system/Installer.md) | [Türkçe](../../tr/system/Installer.md) | [简体中文](../../zh-CN/system/Installer.md) | [Español](../../es/system/Installer.md) | [Deutsch](../../de/system/Installer.md) | [Русский](../../ru/system/Installer.md) | [Bahasa Indonesia](../../id/system/Installer.md) | [हिन्दी](../../hi/system/Installer.md) | **العربية**

يثبت full KernelSU/APatch/Magisk module على Android 12-17 ARM64/x86 64. يدعم KernelSU وAPatch واجهة WebUI بالكامل، بينما يعمل Magisk بدون واجهة مستخدم (راجع [دعم Magisk](Magisk.md)). يتم رفض مسارات التثبيت عبر recovery.

كل payload لديه SHA 256 وruntime يرفض symlink/non-regular/unexpected files. Internal hash ليس دليلا على الناشر، لذلك release الرسمي ينشر `SHA256SUMS` وGitHub signed build provenance.
