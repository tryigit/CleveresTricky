# CleveresTricky

**Dil:** [English](README.md) | **Türkçe** | [简体中文](README.zh-CN.md) | [Español](README.es.md) | [Deutsch](README.de.md) | [Русский](README.ru.md) | [Bahasa Indonesia](README.id.md) | [हिन्दी](README.hi.md) | [العربية](README.ar.md)

[![Release](https://img.shields.io/github/v/release/tryigit/CleveresTricky?display_name=tag&sort=semver&label=Release)](https://github.com/tryigit/CleveresTricky/releases/latest)
[![İndirmeler](https://img.shields.io/github/downloads/tryigit/CleveresTricky/total?color=0A84FF&label=%C4%B0ndirmeler)](https://github.com/tryigit/CleveresTricky/releases)
![Android](https://img.shields.io/badge/Android-12--17-3DDC84?logo=android&logoColor=white)
![Module](https://img.shields.io/badge/Module-KernelSU%20%7C%20APatch-6f42c1)

CleveresTricky, Android 12-17 için bir KernelSU ve APatch modülüdür. Android Keystore ve attestation uyumluluğunu, Keybox/CBOX yönetimini, uygulama hedeflemeyi, isteğe bağlı kimlik kontrollerini, patch seviyesi ayarlarını ve gizlilik araçlarını tek bir mobil WebUI içinde toplar.

Önce varsayılan ayarlarla başlayın; yalnızca gerçekten ihtiyaç duyduğunuz özellikleri açın.

## Neler yapabilirsiniz?

- **Keybox/CBOX** dosyalarını yönetin, doğrulayın, seçin ve değiştirin.
- Global Mode kullanın veya uygulamaları ayrı ayrı kurallarla hedefleyin.
- Cihaz/build, attestation, telephony, bölge ve security patch sunumunu isteğe bağlı olarak yapılandırın.
- Remote Key Provisioning akışlarını koruyun ve DRM bypass iddiası olmadan desteklenen DRM tanımlayıcılarının görünürlüğünü azaltın.
- Ayarları yedekleyin, etkin durumu inceleyin ve WebUI ya da modül Action üzerinden tanılama raporu oluşturun.

## Hızlı başlangıç

1. En güncel ZIP dosyasını resmi [Releases](https://github.com/tryigit/CleveresTricky/releases/latest) sayfasından indirin.
2. Android çalışırken ZIP dosyasını KernelSU veya APatch üzerinden kurun.
3. Modül yöneticinizden CleveresTricky WebUI'yi açın.
4. Yalnızca sahibi olduğunuz veya test etmek için yetkiniz bulunan bir **Keybox veya CBOX** ekleyin.
5. Önce varsayılan kurulumu kullanın; kimlik, uygulama kuralları veya gizlilik seçeneklerini yalnızca gerektiğinde etkinleştirin.

Projeyle birlikte kullanılabilir bir Keybox veya özel attestation anahtarı verilmez.

## Desteklenen ortam

- Android **12-17** / API **31-37**
- **ARM64** ve **x86-64**
- **KernelSU** ve **APatch** (önerilen, tam WebUI desteği)
- **Magisk** (headless / `/data/adb/cleverestricky/` üzerinden manuel yapılandırma, [önerilmez](https://tryigit.dev/advanced-android-root-architecture-concealment/))

Recovery üzerinden kurulum desteklenmez.

## Bilmeniz gerekenler

CleveresTricky yerel uyumluluk yolunu iyileştirir; ancak uzak sonuçlar gerçek cihaz, firmware, sertifikasyon durumu, Google Play services, sunucu politikası ve kullandığınız verilere bağlıdır. Belirli bir Play Integrity veya attestation sonucu garanti edemez.

Bootloader'ı fiziksel olarak yeniden kilitlemez, verified boot ölçümlerini yeniden yazmaz, donanımsal root of trust'ı değiştirmez, modem/baseband üzerinde değişiklik yapmaz ve DRM gizlilik kontrollerini bir DRM bypass'a dönüştürmez.

Yalnızca kullanmaya yetkili olduğunuz yapılandırma ve kimlik bilgilerini kullanın.

## Daha fazla bilgi

- [Strong Integrity Rehberi](docs/i18n/tr/security/StrongIntegrityGuide.md) - Orijinal, AOSP ve Custom ROM'lar için Google Play Integrity (MEETS_STRONG_INTEGRITY) hızlı başlangıç kılavuzu.
- [Magisk Desteği Rehberi](docs/i18n/tr/system/Magisk.md) - Magisk kullanıcıları için headless manuel yapılandırma ve root gizleme kılavuzu.
- [Keybox Manager](docs/i18n/tr/security/KeyboxManager.md) - Keybox/CBOX yükleme, doğrulama, seçim ve iptal kontrolleri.
- [Application Scope](docs/i18n/tr/identity/ApplicationScope.md) ve [Application Rules](docs/i18n/tr/identity/ApplicationRules.md) - özelliklerin hangi uygulamalara uygulanacağını seçin.
- [Build Identity](docs/i18n/tr/identity/BuildIdentity.md), [Telephony Identity](docs/i18n/tr/identity/TelephonyIdentity.md) ve [Patch Levels](docs/i18n/tr/identity/PatchLevels.md) - isteğe bağlı kimlik kontrolleri.
- [RKP Protection](docs/i18n/tr/security/RkpProtection.md) ve [DRM Privacy](docs/i18n/tr/system/DrmPassthrough.md) - platform uyumluluğu ve gizlilik davranışı.
- [Backup and Restore](docs/i18n/tr/system/BackupRestore.md) - şifreli yapılandırma yedeği ve geri yükleme.
- [Security Model](docs/i18n/tr/security/SecurityModel.md) ve [Installer](docs/i18n/tr/system/Installer.md) - güven sınırları ve kurulum ayrıntıları.

## Yardım mı gerekiyor?

WebUI içindeki **Logs** sayfasını veya modül **Action** seçeneğini kullanarak acil tanılama raporu oluşturabilirsiniz. Tanılama arşivi cihaz ve sistem bilgileri içerebileceği için paylaşmadan önce kontrol edin.

Yaygın sorunlar ve çözüm adımları için [Diagnostics](docs/i18n/tr/system/Diagnostics.md) sayfasına bakın.

## Proje

[Değişiklikler](CHANGELOG.md) · [Katkıda bulunma](docs/i18n/tr/CONTRIBUTING.md) · [Diller](docs/i18n/tr/LANGUAGES.md) · [Lisans](LICENSING.md) · [Bağış](docs/i18n/tr/DONATE.md) · [Telegram](https://t.me/cleverestech)
