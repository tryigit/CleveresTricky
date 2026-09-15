# Attestation

**Dil:** [English](../../../security/Attestation.md) | **Türkçe** | [简体中文](../../zh-CN/security/Attestation.md) | [Español](../../es/security/Attestation.md) | [Deutsch](../../de/security/Attestation.md) | [Русский](../../ru/security/Attestation.md) | [Bahasa Indonesia](../../id/security/Attestation.md) | [हिन्दी](../../hi/security/Attestation.md) | [العربية](../../ar/security/Attestation.md)

Attestation katmanı, seçili uygulamalara kontrollü sertifika zinciri uyumluluğu sağlarken gerçek Android anahtar oluşturma ve sonraki kriptografik işlemleri korur.

RKP altyapı çağıranları her zaman Android'in gerçek provisioning yolunda kalır. Hedeflenen uygulama UID'lerinde başarılı `generateKey` yanıtları ile sonraki `getKeyEntry` sertifika okumaları aynı sertifika uyumluluk yolunu kullanır; böylece aynı alias iki farklı attestation leaf göstermez.

Private key işlemi istenen güvenlik seviyesinde Android KeyMint veya StrongBox tarafından yapılmaya devam eder. Etkinleştirmeden önce key/certificate eşleşmesi, algoritma, chain yapısı, geçerlilik, ambiguity ve revocation doğrulanır. Sertifika değiştirme fiziksel hardware root of trust oluşturmaz, bootloader'ı kilitlemez veya remote verdict garanti etmez.

## Donanımsal TEE Hazırlama ve OnePlus Kurtarma Notu

CleveresTricky, çalışan bir donanımsal KeyMint/TEE altyapısına ihtiyaç duyar; sahte bir yazılımsal TEE veya sahte kanıtlama (attestation) taklidi yapmaz. Donanımsal TEE iletişimi koptuğunda (`SECURE_HW_COMMUNICATION_FAILED` / hata kodu 10), sistem kilitlenmelerini önlemek amacıyla modül araya girmeyi durdurur.

Bazı kilitli olmayan (bootloader unlocked) cihazlarda, önyükleyici kilidinin açılması cihaz tarafındaki donanım kanıtlamasını veya RKP hazırlığını bozabilir:
- **OnePlus 13 / 15 Kilit Açık Cihaz Kanıtlama / TEE RKP Kurtarma**: [wuxianlin'in araştırmasında](https://wuxianlin.com/2025/11/12/oneplus-13-15-attestation-rkp-test/) belgelendiği üzere, test edilen OnePlus 13 ve 15 modellerinde önyükleyici kilidini açmak TEE Attestation ve RKP hazırlığını bozmaktadır.
- **Cihaz Tarafı Hazırlama Alternatifi**: İnceleme, `KmInstallKeybox` cihaz kimliği hazırlamasının (device-ID provisioning), sızdırılmış kanıtlama anahtarları enjekte etmeden orijinal TEE RKP'yi (ve test edilen modellerde Widevine L1 RKP'yi) geri getirebildiğini bildirmektedir. Bu bir Keystore aldatması veya taklidi değil, meşru bir cihaz tarafı TEE hazırlama/kurtarma alternatifidir.
- **Kapsam ve Kısıtlamalar**:
  - Bu yöntem çevrimdışı TEE Kanıtlama Anahtarını (offline TEE Attestation Key) evrensel olarak geri yüklemez.
  - Tamamen **cihaza ve aygıt yazılımına (firmware) özgüdür**. Genel bir çözüm olarak önerilmemeli ve tüm OnePlus modellerinde çalışacağı varsayılmamalıdır.
  - **Risk Uyarısı**: Cihaz tarafında `persist` veya TEE hazırlama değişiklikleri yapmak kalıcı veri/anahtar kaybı veya cihazın kullanılamaz hale gelmesi (brick) riskini taşır. TEE kurtarma işlemi denemeden önce daima `persist` ve ilgili bölümlerin yedeğini alın.
