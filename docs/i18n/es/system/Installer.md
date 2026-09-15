# Installer

**Idioma:** [English](../../../system/Installer.md) | [Türkçe](../../tr/system/Installer.md) | [简体中文](../../zh-CN/system/Installer.md) | **Español** | [Deutsch](../../de/system/Installer.md) | [Русский](../../ru/system/Installer.md) | [Bahasa Indonesia](../../id/system/Installer.md) | [हिन्दी](../../hi/system/Installer.md) | [العربية](../../ar/system/Installer.md)

Instala el módulo completo KernelSU/APatch/Magisk con service, native payload, scripts, policy, metadata e integrity records. Soporta Android 12-17, ARM64 y x86 64; KernelSU y APatch ofrecen soporte WebUI completo, mientras que Magisk funciona en modo headless sin interfaz (consulte [Soporte para Magisk](Magisk.md)). La instalación por recovery se rechaza.

Cada payload tiene SHA 256 y la verificación runtime rechaza symlinks, entradas no regulares o inesperadas. Los hashes internos no prueban quién creó el ZIP, por lo que releases oficiales publican `SHA256SUMS` y GitHub signed build provenance.
