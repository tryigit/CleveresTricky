# Installer

**Dil:** [English](../../../system/Installer.md) | **Türkçe** | [简体中文](../../zh-CN/system/Installer.md) | [Español](../../es/system/Installer.md) | [Deutsch](../../de/system/Installer.md) | [Русский](../../ru/system/Installer.md) | [Bahasa Indonesia](../../id/system/Installer.md) | [हिन्दी](../../hi/system/Installer.md) | [العربية](../../ar/system/Installer.md)

Installer, service, native payload, scripts, policy, metadata ve integrity kayıtlarından oluşan tam KernelSU/APatch/Magisk modülünü kurar. Android 12-17, ARM64 ve x86 64 desteklenir. KernelSU ve APatch tam WebUI desteği sunarken Magisk [Magisk Desteği](Magisk.md) dökümantasyonunda belirtildiği üzere headless modda çalışır. Recovery yolu durdurulur.

Build her payload için SHA 256 kaydı üretir, installer extraction sırasında doğrular ve runtime doğrulaması symlink/non-regular/unexpected payload'ları reddeder. Archive içi hash tek başına üretici kimliği kanıtı değildir; resmi release ayrıca `SHA256SUMS` ve GitHub signed build provenance yayımlar. Resmi ZIP'i indirip digest/provenance kontrolünden sonra KernelSU/APatch/Magisk ile kurup reboot etmek önerilir.
