# Attestation

**Dil:** [English](../../../security/Attestation.md) | **Türkçe** | [简体中文](../../zh-CN/security/Attestation.md) | [Español](../../es/security/Attestation.md) | [Deutsch](../../de/security/Attestation.md) | [Русский](../../ru/security/Attestation.md) | [Bahasa Indonesia](../../id/security/Attestation.md) | [हिन्दी](../../hi/security/Attestation.md) | [العربية](../../ar/security/Attestation.md)

Attestation katmanı, seçili uygulamalara kontrollü sertifika zinciri uyumluluğu sağlarken gerçek Android anahtar oluşturma ve sonraki kriptografik işlemleri korur.

RKP altyapı çağıranları her zaman Android'in gerçek provisioning yolunda kalır. Hedeflenen uygulama UID'lerinde başarılı `generateKey` yanıtları ile sonraki `getKeyEntry` sertifika okumaları aynı sertifika uyumluluk yolunu kullanır; böylece aynı alias iki farklı attestation leaf göstermez.

Private key işlemi istenen güvenlik seviyesinde Android KeyMint veya StrongBox tarafından yapılmaya devam eder. Etkinleştirmeden önce key/certificate eşleşmesi, algoritma, chain yapısı, geçerlilik, ambiguity ve revocation doğrulanır. Sertifika değiştirme fiziksel hardware root of trust oluşturmaz, bootloader'ı kilitlemez veya remote verdict garanti etmez.

## Donanımsal TEE Hazırlama ve Kilit Açık Cihaz Kurtarma Notu

CleveresTricky, çalışan bir donanımsal KeyMint/TEE altyapısına ihtiyaç duyar; sahte bir yazılımsal TEE veya sahte kanıtlama (attestation) taklidi yapmaz. Donanımsal TEE iletişimi koptuğunda (`SECURE_HW_COMMUNICATION_FAILED` / hata kodu -49), sistem kilitlenmelerini önlemek amacıyla modül araya girmeyi durdurur.

Kilit açık (bootloader unlocked) Android cihazlarda, önyükleyici kilidinin açılması cihaz tarafındaki donanım kanıtlamasını veya Uzaktan Anahtar Hazırlamayı (RKP) bozabilir:
- **Cihaz Tarafı TEE RKP Kurtarma**: [wuxianlin'in araştırmasında](https://wuxianlin.com/2025/11/12/oneplus-13-15-attestation-rkp-test/) gösterildiği üzere (OnePlus 13/15 dahil modern cihazlarda değerlendirilmiştir), `KmInstallKeybox` cihaz kimliği hazırlaması, sızdırılmış anahtarlar enjekte etmeden orijinal TEE RKP'yi (ve Widevine L1 RKP'yi) geri getirebilmektedir. Bu bir Keystore taklidi veya yazılımsal emülasyon değil, kilit açık Android cihazlarda meşru bir cihaz tarafı TEE hazırlama/kurtarma mekanizması olarak genel geçerli bir çözümdür.
- **Kapsam ve Öneriler**:
  - Cihaz tarafı `KmInstallKeybox` hazırlaması, uzaktaki altyapıdan orijinal TEE RKP sertifikası alma yeteneğini geri yükler.
  - Eski çevrimdışı TEE Kanıtlama Anahtarını (offline TEE Attestation Key) evrensel olarak geri yüklemez.
  - **Risk Uyarısı**: Cihaz tarafında `persist` veya donanımsal TEE hazırlama değişiklikleri yapmak anahtar deposunun bozulması veya cihazın kullanılamaz hale gelmesi riskini taşır. TEE kurtarma işlemi denemeden önce daima `persist` ve ilgili bölümlerin tam yedeğini alın.
