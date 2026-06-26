# CleveresTricky Teknik Analiz ve İyileştirme Yol Haritası

## 1. Mevcut Mimari Analizi

Mevcut CleveresTricky projesinin mimarisi incelendiğinde, Android Keystore ve Remote Key Provisioning (RKP) süreçlerini manipüle etmek için Java/Kotlin ve C/C++ (Binder düzeyinde) dillerinin karma bir şekilde kullanıldığı görülmektedir.

*   **`binder_router.cpp` / C++ IPC Katmanı:** Binder işlemlerini (BR_TRANSACTION vb.) yakalayarak donanım IPC çağrılarını yazılım tabanlı implementasyona yönlendiriyor. Payload'ları filtreleme ve pass-through (örn. >256KB veya PING/DUMP çağrıları) optimizasyonları mevcut. Ancak sinyal bazlı bellek doğrulama yaklaşımları (`safe_memcpy`, `sigaction` vb. multi-thread sorunları) ve JNI geçişleri (C++ -> Rust -> Kotlin) gecikmelere sebep olabilmektedir.
*   **`KeystoreInterceptor.kt` & `RkpInterceptor.kt`:** Keystore2 ve IRemotelyProvisionedComponent HAL servislerini hook'layarak donanım tabanlı anahtar üretimini (generateKey) ve sertifika zinciri taleplerini araya girerek ele alıyor. RkpInterceptor, donanım kimliğini gizlemek için `CertHack` ile sahte cihaz bilgileri (DeviceInfo) üretiyor.
*   **`LocalRkpProxy.kt`:** RKP sunucusunu yerel olarak simüle eden otorite (Proxy). Kök secret (HMAC Key) yönetimi ve COSE_Mac0 doğrulaması yapıyor. Ancak secret'ın `/data/adb` altında düz metin (Hex) olarak tutulması ve 24 saatte bir rotasyona tabi tutulması, AOSP RKP süreçlerine göre "öngörülemezlik" ve "kalıcılık" açısından daha zayıf kalabiliyor.
*   **`CertHack.java` (Attestation):** X.509 sertifika zincirlerini `BouncyCastle` kütüphanesi kullanarak dinamik olarak manipüle ediyor (Spoofing). ASN.1 tag'lerini (örn. 704 RootOfTrust, 706 OS Patch Level, ID Attestation tag'leri) güncelliyor. JCA (Java Cryptography Architecture) kullanımı nesne tahsisini ve CPU yükünü artırmakta, bu da performansı etkilemektedir.
*   **`KeyboxVerifier.kt` & `XMLParser.java`:** İptal edilen sertifika (CRL) kontrolleri ve Keybox (XML) ayrıştırma işlemleri yapılıyor. `XMLParser` kendi özel PullParser mantığını kullanıyor ve XXE'ye karşı korumalı (DOCDECL kapalı) ancak oldukça manuel bir işlem ağacı var.

**Zayıf Noktalar:**
- **JNI ve IPC Overhead:** Binder (C/C++) -> JNI -> Kotlin (Interceptor) -> BouncyCastle (Java) geçişleri, özellikle TEE'nin çok hızlı cevap vermesi beklenen senaryolarda gecikmeye (latency) neden oluyor.
- **RKP ve RootOfTrust Uyumsuzluğu:** AOSP beklentilerinde RootOfTrust, cihazın donanımsal kilidini (bootloader state, vb_state=green) ve donanım key'lerini güçlü bir şekilde doğrulamalıdır. Mevcut `CertHack` yaklaşımı `BouncyCastle` ile ASN.1 yapısını statik olarak "hack"liyor ancak rakip çözümlere kıyasla cihazın RKP ve donanım sertifikasyon durumunu (VBMeta, vb_state) daha tutarsız (veya yavaş) sunabiliyor. Bu da bazı oyun/uygulamaların (örneğin donanım destekli DRM veya safety net / play integrity kontrolleri) bu durumu tespit etmesine (screenshot'taki hata) neden olabiliyor.

## 2. Rakip Modül (TEESimulator-RS) ile Teknik Karşılaştırma

| Özellik / Bileşen | CleveresTricky | TEESimulator-RS |
| :--- | :--- | :--- |
| **Dil & Performans** | Ağırlıklı Kotlin/Java, Binder C++. JNI yükü yüksek. | Tamamen veya ağırlıklı Rust. Yüksek bellek güvenliği ve sıfır-maliyet (zero-cost) soyutlama. |
| **RKP (Remote Key Provisioning)** | `LocalRkpProxy.kt` ile yerel HMAC simülasyonu. | Güçlü CBOR/COSE Rust kütüphaneleriyle yerel C++ bağlamında, işletim sistemi kernel düzeyine daha yakın RKP üretimi. |
| **Sertifika Zinciri İşleme** | `BouncyCastle` (Java) üzerinden karmaşık ASN.1 parse ve yeniden inşası. (Yavaş ve Garbage Collection tetikliyor) | Rust tabanlı ASN.1/DER parser (örn. `der`, `x509-cert` crateleri) ile heap allocation'sız ultra hızlı sertifika manipülasyonu. |
| **Binder Interception** | `binder_router.cpp` ile ioctl hook + JNI üzerinden Kotlin servislerine bildirim. | Doğrudan Rust ffi ile low-level IPC işleme (daha düşük gecikme). |
| **Sistem Uyumluluğu** | Geniş cihaz/ROM desteği, ancak RKP/Keybox değişimlerinde boot durumu ve RootOfTrust değerleri sistem servislerince (Keystore2) zaman tutarsız algılanıyor. | Donanım (TEE) zamanlamalarını ve hata kodlarını daha tutarlı simüle ediyor. (Status.cpp AOSP standartlarına daha uygun `EX_SERVICE_SPECIFIC` döndürümleri). |

**Sonuç:** CleveresTricky, Kotlin kullanımından dolayı geliştirme esnekliği sunsa da, sıcak (hot) path'lerde (Binder IPC, Attestation, CBOR encoding) performans ve tutarlılık açısından TEESimulator-RS'in Rust tabanlı saf donanım simülasyonuna kıyasla geride kalmaktadır.

## 3. Mimarinin İki Ayrı Çalışma Moduna Ayrılması (Öneriler)

Sistemin esnekliğini ve güvenilirliğini artırmak için modülü iki net moda ayırmalıyız:

### Mod 1: Gelişmiş RKP Uyumluluk Modu (Advanced RKP Mode)
*Bu mod, cihazın "Remote Key Provisioning" desteklediği ve sistemin donanım tabanlı sertifikasyon (STRONG integrity) beklediği durumlar içindir.*

*   **Mimari Değişiklikler:**
    *   `android.hardware.security.keymint.IRemotelyProvisionedComponent` HAL servisinin kusursuz simülasyonu.
    *   **Dinamik Cihaz Kimliği (DeviceInfo):** RKP cihaz bilgilerini statik "Google / Pixel" yerine, cihazın fiziksel özelliklerine ve Android 14+ / 15+ VBMeta yapılarına uygun (ör. `vb_state = green`, `bootloader_state = locked`, `vendor_patch_level` tutarlılığı) olarak dinamik şekilde Rust (Cbor/Cose) katmanından üretmek.
    *   **Kalıcı ve Güvenli HMAC Key:** `LocalRkpProxy` içindeki key rotasyonu, Android'in standart `keystore2` rotasyon zamanlarına ve kurallarına entegre edilmeli. Key'ler düz dosya yerine, şifrelenmiş veya yetkilendirilmiş (0600) güvenli alanlarda saklanmalıdır.
*   **Örnek Yaklaşım (Rust Katmanı):** Zaten `rust/cbor-cose/src/cose.rs` içinde `create_device_info_cbor` fonksiyonunda `vb_state`, `system_patch_level` gibi özellikler var. Bu değerlerin Java katmanındaki `CertHack` ile birebir senkronize çalışması, tutarsızlıkları önleyecektir.

### Mod 2: Güçlü Genel Simülasyon Modu (General Simulation Mode)
*İlk mod (RKP) devre dışı bırakıldığında veya cihazda donanımsal RKP bulunmadığında (eski cihazlar veya legacy keybox kullananlar) Keystore2'yi manipüle eden kararlı mod.*

*   **Mimari Değişiklikler:**
    *   Sadece `SecurityLevel.TRUSTED_ENVIRONMENT` (TEE) ve `STRONGBOX` seviyelerini hook'lar. RKP bypass tamamen kapanır.
    *   **Gelişmiş Keybox Fallback:** `KeyboxVerifier` ile XML tabanlı Keybox'lar doğrulandıktan sonra, `CertHack`'in RootOfTrust ve Boot durumu değerleri (OS Patch Level, Verified Boot Hash) cihazın güncel `ro.boot.vbmeta.digest` ve `ro.build.version.security_patch` değerleriyle birebir örtüşecek şekilde patch'lenir.
    *   **Fail-Open / Fail-Safe Durumları:** Orijinal donanım sertifika zincirinin bozulması durumunda (örneğin TEE çöktüğünde), Kotlin servisinin de çökmesini engellemek için hata fırlatmak (throw) yerine, IPC üzerinden uygun bir `ServiceSpecificException` dönerek (örneğin donanım hatası) Keystore2'nin kendi fallback mekanizmalarını (Software Keymaster) devreye sokmasına izin verilmelidir.

## 4. Rust Entegrasyonu (Kademeli Geçiş Roadmap)

Performans sorunlarını (latency ve GC takılmaları) çözmek için, `CertHack` (Java) ve Binder hook'larının ağırlıklı olarak Rust'a taşınması gerekmektedir.

**Aşama 1: Attestation ve CBOR/COSE'un Tamamen Rust'a Kaydırılması**
- `CertHack.java` içerisindeki `hackCertificateChain` metodu, ASN.1 ayrıştırması için çok fazla nesne (BouncyCastle) kullanıyor. Bu işlem, JNI üzerinden byte dizisi alınıp Rust'ta `x509-cert` veya `der` crate'leri kullanılarak sıfır-maliyetle (zero-allocation) yapılabilecek şekilde yeniden yazılmalıdır.
- Hali hazırda `rust/interceptor/src/lib.rs` içindeki `createProtectedDataNatively` RKP işlemlerini yapıyor. Bunun kapsamı genişletilerek `generateKeyPair` ve `hackCertificateChain` işlemleri Rust'a alınmalıdır.

**Aşama 2: Binder Router'ın Doğrudan Rust Interceptor'a Bağlanması**
- `binder_router.cpp`'de `BR_TRANSACTION` yakalandığında C++ -> JNI (Java) -> C++ döngüsü var. (Şu an Java `KeystoreInterceptor.kt` servis olarak çalışıyor).
- **Hedef:** Binder paketlerinin ayrıştırılması (`parcel_parser.rs`) Rust'ta yapılmalı. Kotlin/Java sadece "Arayüz (UI), Yapılandırma (Config), Uzaktan Cihaz Listesi (Keybox Fetcher)" olarak kalmalıdır.

**Aşama 3: Zygisk Native Payload'unun Genişletilmesi**
- `libcleverestricky.so` sadece bir bağlayıcı (wrapper) olmaktan çıkıp, tüm TEE simülasyonunu ve Attestation manipülasyonunu RAM üzerinde `no_std` veya düşük memory footprint prensipleriyle (ör. HashMap yerine `AHash`, String yerine `&str`/`Cow`) Rust ile yapmalıdır.

## 5. Genel İyileştirmeler: Kullanıcı Ayarları, Logging, Hata Yönetimi, Stabilite

*   **Logging:** Mevcut durumda Proprietary log dosyaları yerine Android `logcat` kullanılıyor. Bu iyi bir özellik (flash wear önleme). Ancak hata yönetimi sırasında logların gereksiz yere Exception Stacktrace basması (`Logger.e("...", t)`) engellenmelidir. Sadece kritik hatalarda stacktrace, diğerlerinde state değişikliği loglanmalıdır.
*   **Hata Yönetimi ve Thread Safety (Stabilite):**
    *   Çoklu thread ortamlarında (Zygisk/Daemon), `Volatile` kullanımları (`triedCount`, `injected`) yerine `AtomicInteger` ve `AtomicBoolean` kullanımları tutarlı bir şekilde yaygınlaştırılmalıdır (Özellikle `WebServer.kt` veya `Config.kt` içinde).
    *   IPC geçişlerinde speculatif veri okumaları (örneğin Binder paketinden data okurken) hata fırlatırsa orijinal pozisyona dönmesi (`data.setDataPosition(startPos)`) garanti altına alınmalıdır (şu an bu konuda potansiyel eksiklikler donanım fallback'ini bozuyor).
*   **Cihaz Uyumluluğu:**
    *   `binder_router.cpp` içindeki bellek doğrulama yaklaşımları (sinyal bazlı bellek probing), thread güvenliği (race conditions) sebebiyle `process_vm_readv` veya Pipe hilesi (`write()` ile `EFAULT` tespiti) ile değiştirilmelidir. Zaten projenin geçmiş notlarında bu belirtiliyor, bu eylem kesinlikle uygulanmalıdır.
    *   32-bit (armeabi-v7a) cihazlarda Rust derleme uyarıları / ABI uyuşmazlıkları için `cargo-ndk` cross-compile bayraklarının (`-t arm64-v8a -t armeabi-v7a -t x86_64 -t x86`) CI pipeline'ında hatasız yapılandırıldığından emin olunmalıdır.
    *   Android 14/15 AOSP güncellemeleri, VBMeta ve RKP HAL sürüm atlamaları içerebilir. Modül, `IRemotelyProvisionedComponent`'in v1, v2 ve v3 sürümlerini `data.enforceInterface` ve versiyon spesifik payload kontrolüyle esnek bir şekilde (şu anki gibi isV2 boolean bayrağı yerine interface adı / HAL versiyon stringi ile) yönetmelidir.
    *   **Kullanıcı Ayarları (UI):** WebUI içerisindeki yapılandırmalarda token doğrulaması için `window.history.replaceState` kullanılmaması gerektiği (Android back butonu uyumluluğu) ve Local Storage veya şifrelenmiş cookie yapılarının oturum tutarlılığı (Session Persistence) için kullanılması sağlanmalıdır.
