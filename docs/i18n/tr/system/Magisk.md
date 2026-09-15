# Magisk Desteği

**Dil:** [English](../../../system/Magisk.md) | **Türkçe** | [简体中文](../../zh-CN/system/Magisk.md) | [Español](../../es/system/Magisk.md) | [Deutsch](../../de/system/Magisk.md) | [Русский](../../ru/system/Magisk.md) | [Bahasa Indonesia](../../id/system/Magisk.md) | [हिन्दी](../../hi/system/Magisk.md) | [العربية](../../ar/system/Magisk.md)

## Önemli Uyarı

> [!CAUTION]
> **Magisk, CleveresTricky için ÖNERİLMEZ.**
>
> Modern uygulama tespit çerçeveleri ve Google Play Integrity, userspace mount alanlarını, root ikili dosyalarını ve Zygote hook'larını doğrudan tespit edebilmektedir. Magisk'in userspace tabanlı mimarisi kalıcı ve kusursuz bir gizleme sağlamayı oldukça zorlaştırır.
>
> Çekirdek (kernel) tabanlı root çözümleri ile userspace root arasındaki mimari farklar ve gizleme detayları hakkında kapsamlı rehberi mutlaka inceleyin:
> 👉 **[Advanced Android Root Guide: KernelSU, APatch & Concealment | Yiğit - tryigit.dev](https://tryigit.dev/advanced-android-root-architecture-concealment/)**
>
> **Cihazınız destekliyorsa her zaman [KernelSU](https://kernelsu.org) veya [APatch](https://apatch.dev) tercih edin.**

---

## WebUI Olmadan Çalışma (Headless Mimari)

KernelSU ve APatch, yönetici uygulamaları içinde gömülü modül WebUI uzantı desteği sunar. Magisk'te bu arayüz standardı bulunmamaktadır.

Bu sebeple Magisk altında kurulduğunda **CleveresTricky WebUI olmadan arka plan daemon moduyla (headless) çalışır**. Rust daemon'ı (`cleverestrickyd`), backend ve Keystore kancaları tam fonksiyonel olarak çalışmaya devam eder; ancak tüm ayarlar `/data/adb/cleverestricky/` dizinindeki dosyalar düzenlenerek manuel olarak yönetilir.

---

## Manuel Yapılandırma Rehberi

Tüm ayar ve kural dosyaları `/data/adb/cleverestricky/` klasöründe yer alır.

### 1. Kapsam ve Hedef Belirleme

* **`target.txt`**: Keystore attestation taklidi uygulanacak paket isimleri (her satıra bir paket):
  ```text
  com.google.android.gms
  com.google.android.gms.unstable
  com.android.vending
  ```
* **`global_mode`**: Boş işaretçi dosyası.
  * **Mevcutsa**: Genel Mod (Global Mode) devrededir; sistem ve root dışındaki tüm uygulamalar kancalanır.
  * **Mevcut değilse**: Yalnızca `target.txt` içindeki paketler taklit edilir.
  * *Komut:* `touch /data/adb/cleverestricky/global_mode` (aç) veya `rm -f /data/adb/cleverestricky/global_mode` (kapat).

* **`identity_target.txt`**: Cihaz kimliği ve build özellikleri taklit edilecek hedef paketler.
* **`global_identity_mode`**: Boş işaretçi dosyası. Varsa kimlik taklidi tüm sistem dışı uygulamalara uygulanır.

---

### 2. Cihaz Kimliği ve Model Taklidi

* **`spoof_build_vars`**: Taklit edilecek cihaz özellikleri `ANAHTAR=DEĞER` formatında girilir:
  ```properties
  MANUFACTURER=Google
  MODEL=Pixel 8 Pro
  FINGERPRINT=google/husky/husky:14/UQ1A.240105.004/11269998:user/release-keys
  BRAND=google
  PRODUCT=husky
  DEVICE=husky
  RELEASE=14
  ID=UQ1A.240105.004
  INCREMENTAL=11269998
  TYPE=user
  TAGS=release-keys
  ```
* **`security_patch.txt`**: Güvenlik yaması tarihi (örn. `2026-03-05`). Otomatik sistem eşleşmesi için boş bırakın veya dosyayı kaldırın.
* **`boot_props_mode`**: Bootloader özellik simülasyonunu yönetir (`auto`, `manual` veya `disabled`).

---

### 3. Donanım Keybox (Attestation Anahtarı)

* **`keybox.xml`**: Geçerli donanım sertifikasyon anahtar kutunuzu doğrudan `/data/adb/cleverestricky/keybox.xml` yoluna yerleştirin. İzinlerin kısıtlı olduğundan emin olun (`chmod 600`).
* **`keyboxes/`**: Birden fazla keybox dosyası saklamak için kullanılan dizin.

---

### 4. DRM ve Gizlilik Kapsamı

* **`drm_packages.txt`**: Widevine L1 donanım korumasını kaybetmemek adına Keystore kancasından muaf tutulacak akış uygulamaları:
  ```text
  com.netflix.mediaclient
  com.amazon.avod.thirdpartyclient
  com.disney.disneyplus
  ```

---

### 5. Özellik Bayrakları (İşaretçi Dosyaları)

Bir özelliği açmak için dosyasını oluşturun (`touch <dosya>`), kapatmak için silin (`rm -f <dosya>`):

| İşaretçi Dosyası | Dosya Mevcut Olduğunda Görevi |
| :--- | :--- |
| `spoof_enabled` | Kimlik taklit motorunu (Spoof Engine) devreye sokar. |
| `spoof_build_identity` | Build özellikleri taklidini etkinleştirir. |
| `auto_keybox_check` | Keybox geçerliliğini ve iptal durumunu periyodik doğrular. |
| `drm_passthrough` | `drm_packages.txt` listesindeki paketlere DRM muafiyeti uygular. |
| `hide_sensitive_props` | Root ve bootloader durum göstergesi özelliklerini gizler. |
| `tee_broken_mode` | Donanımsal TEE arızalı cihazlarda yazılımsal attestation desteği açar. |
| `debug_logging` | `native_runtime.log` dosyasına ayrıntılı hata ayıklama günlüğü yazar. |

---

## Değişiklikleri Uygulama ve Doğrulama

### Ayarların Geçerli Olması
Magisk ortamında anlık WebUI tetikleyicisi bulunmadığından, yapılandırma veya bayrak dosyaları değiştirildikten sonra cihazı yeniden başlatmanız önerilir:
```sh
su -c "reboot"
```

### Günlükleri İnceleme
Servisin sorunsuz çalıştığını kontrol etmek için:
```sh
su -c "cat /data/adb/cleverestricky/native_runtime.log"
```

### Çalışan Süreçleri Doğrulama
```sh
su -c "ps -A | grep -E 'cleverestrickyd|cleverestricky_backend'"
```

### Hata Raporu (Bugreport) Oluşturma
Tam tanı ve günlük paketini çıkarmak için:
```sh
su -c "/data/adb/modules/cleverestricky/action.sh"
```
Oluşturulan arşiv `/data/adb/cleverestricky/bugreports/` dizinine kaydedilir.
