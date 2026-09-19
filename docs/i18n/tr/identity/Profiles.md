# Profiles

**Dil:** [English](../../../identity/Profiles.md) | **Türkçe** | [简体中文](../../zh-CN/identity/Profiles.md) | [Español](../../es/identity/Profiles.md) | [Deutsch](../../de/identity/Profiles.md) | [Русский](../../ru/identity/Profiles.md) | [Bahasa Indonesia](../../id/identity/Profiles.md) | [हिन्दी](../../hi/identity/Profiles.md) | [العربية](../../ar/identity/Profiles.md)

Profiller optional ayar gruplarını tek validated işlemle uygular; core boot, Keystore ve RKP altyapı koruması bunlardan bağımsız olarak aktif kalır.

Daily Compatibility targeted scope ve keybox monitoring kullanır; Default muhafazakâr optional identity düzenidir (ayrıca Default profilin uygulanması veya WebUI üzerinden ortam sıfırlama `target.txt`, `identity_target.txt`, `drm_packages.txt`, `boot_props_mode` ve `security_patch.txt` dosyalarını varsayılan paket şablonlarına döndürür ve yapılandırılmış tüm uzak sunucuları önbelleğe alınmış keybox içeriğiyle birlikte kaldırır); Maximum Compatibility Global Mode, build identity, identity refresh ve telephony yollarını açıp DRM passthrough'u kapatır; Minimal optional identity ve scheduled keybox kontrollerini kapatır. Bu profillerin hiçbiri RKP altyapı korumasını değiştirmez.

Eski yapılandırmalar retired `rkp_passthrough` işaretini taşıyabilir; runtime generated-key davranışı artık bu değere bağlı değildir. Version two profilleri app assignment, template, doğrulanmış keybox, privacy, patch ve optional identity/DRM seçimlerini saklayabilir; legacy RKP alanı yalnız migration uyumluluğu için korunabilir ve WebUI'da canlı seçenek değildir.
