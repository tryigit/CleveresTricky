# Günlük Kaydı ve Tanılama (Logging and Diagnostics)

**Dil:** [English](../../../LOG.md) | **Türkçe** | [简体中文](../zh-CN/LOG.md) | [Español](../es/LOG.md) | [Deutsch](../de/LOG.md) | [Русский](../ru/LOG.md) | [Bahasa Indonesia](../id/LOG.md) | [हिन्दी](../hi/LOG.md) | [العربية](../ar/LOG.md)

CleveresTricky; tanılama anlık görüntüleri, çalışma zamanı günlükleri ve acil durum hata raporları almak için çok sayıda yerleşik mekanizma sunar. Bir sorun (issue) bildirirken veya beklenmeyen bir davranışla karşılaşıldığında, tanılama ve log verilerini iletmek sorunun kök nedenini tespit etmek için zorunludur.

---

## Yöntem 1: WebUI Yönetim Paneli (Önerilen)

WebUI, ADB veya bir bilgisayara ihtiyaç duymadan doğrudan telefon üzerinden tanılama toplamanın en kolay yoludur.

### A. Destek Tanılama Anlık Görüntüsü (Support Diagnostics Snapshot)
1. Tarayıcınızdan veya root yöneticinizden CleveresTricky WebUI'yi açın.
2. **Bilgi ve Kaynaklar (Info & Resources)** sekmesine gidin.
3. **Destek Tanılaması (Support Diagnostics)** bölümüne kaydırın.
4. **Tanılamayı Kopyala (Copy Diagnostics)** butonuna tıklayın.
5. Kopyalanan metni doğrudan GitHub issue şablonundaki **Support Diagnostics Snapshot** bölümüne yapıştırın.

> [!NOTE]
> Bu tanılama özeti kesinlikle gizlilik sınırlıdır. Yalnızca modül durumu, çalışma zamanı sürümü, root ortamı, bellek/işlemci kullanımı ve özellik anahtarlarını içerir. Özel anahtarlar, kimlik bilgileri, keybox sertifikaları, paket adları veya kimlik değerleri asla dahil edilmez.

### B. Canlı WebUI Günlükleri ve Hata Ayıklama (Debug Logging)
1. WebUI'de **Günlükler (Logs)** sekmesine gidin.
2. **Debug Logging** anahtarını **AÇIK** duruma getirin (anahtar yeşile döner). Bu, debug APK yüklemeden ayrıntılı çalışma zamanı takibini etkinleştirir.
3. Yaşadığınız sorunu veya Play Integrity testini cihazınızda tekrarlayın.
4. WebUI **Logs** sekmesine dönüp **Kopyala (Copy)** butonuna tıklayın.
5. Alınan logları hata raporunuza ekleyin.
6. İşlem tamamlandığında sistem kaynaklarını korumak için **Debug Logging** anahtarını kapatın.

---

## Yöntem 2: Tek Tıkla Acil Durum Hata Raporu Arşivi (`action.sh`)

CleveresTricky, sistem loglarını, modül durumunu ve root ortamı bilgilerini sıkıştırılmış bir arşiv (`.tar.gz`) haline getiren otomatik bir acil durum aracına sahiptir.

### Root Yöneticisi Üzerinden:
- **KernelSU** veya **APatch** arayüzünde, CleveresTricky modül kartının yanındaki **Eylem (Action)** butonuna dokunun.

### Terminal (Termux / Root Shell) Üzerinden:
```bash
su -c /data/adb/modules/cleverestricky/action.sh
```

### Rapor Arşivinin Yeri:
- Oluşturulan arşiv şu konuma kaydedilir:
  ```
  /data/adb/cleverestricky/bugreports/CleveresTricky_Bugreport_<zaman-damgası>.tar.gz
  ```
  (cihaz izin veriyorsa otomatik olarak `Download/` klasörüne de kopyalanır).
- Bu arşivi doğrudan GitHub issue'nuza dosya olarak ekleyebilirsiniz.

> [!TIP]
> Güvenliğinizi ve gizliliğinizi korumak için Keybox XML/CBOX dosyaları ve özel sertifikalar bu arşivden kasıtlı olarak hariç tutulur.

---

## Yöntem 3: Yerel Daemon Çalışma Zamanı Günlüğü (`native_runtime.log`)

Yerel arka plan servisi başlatma, kancalama ve operasyonel durumunu sürekli olarak şu dosyaya kaydeder:
```
/data/adb/cleverestricky/native_runtime.log
```

Terminal üzerinden son logları incelemek için:
```bash
su -c "tail -n 100 /data/adb/cleverestricky/native_runtime.log"
```

---

## Yöntem 4: Android Logcat (ADB veya Terminal)

WebUI açılmıyorsa veya modül servisleri başlamıyorsa, Android logcat Binder işlemleri ve ilk başlatma hakkında doğrudan bilgi verir.

### ADB ile Canlı İzleme:
```bash
adb logcat -s cleverestricky CleveresTricky
```

### Termux veya Root Kabuğunda:
```bash
su -c "logcat -d -s cleverestricky CleveresTricky"
```

### Temiz Başlatma Kaydı (Keystore'u yeniden başlatarak):
```bash
adb logcat -c
adb shell su -c 'setprop ctl.restart keystore2'
adb logcat -d -s cleverestricky CleveresTricky
```

### Önemli Başlangıç İmleri:
- `Welcome to Service!`
- `Web server on port ...`
- `libbinder ioctl hook installed successfully`
- `Keystore Binder interceptor registered`
- `TEE SecurityLevel interceptor registered`

### KeyMint / TEE Donanım Hatası İmi:
Eğer günlüklerde şunu görürseniz:
```text
[WARN] Platform KeyMint HAL unreachable or TEE broken (SECURE_HW_COMMUNICATION_FAILED).
[INFO] CleveresTricky requires a functional hardware KeyMint; aborting injection to prevent framework deadlock.
[INFO] Please consult documentation (docs/security/Attestation.md or docs/LOG.md) for TEE recovery guidance.
```
Bu durum, cihazın donanımsal TEE veya üretici KeyMint HAL iletişiminin koptuğunu (hata kodu -49 / 10) gösterir. CleveresTricky, framework'ün kilitlenmesini önlemek için koruma mekanizmasını devreye sokarak araya girmeyi durdurur. Cihaz tarafı TEE kurtarma seçenekleri (ör. OnePlus 13/15 kilit açık TEE RKP kurtarma) için [Attestation.md](security/Attestation.md#donan%C4%B1msal-tee-haz%C4%B1rlama-ve-oneplus-kurtarma-notu) belgesine başvurun.

> [!WARNING]
> Günlükleri herkese açık paylaşmadan önce inceleyin. Kimlik bilgileri ve WebUI token'ları asla kaydedilmese de, cihaz model adları, işlem kimlikleri (PID) ve paket adları günlüklerde yer alabilir.

---

## Sorun Bildirirken Gerekenler

GitHub'da hata bildirimi açarken lütfen şunları ekleyin:
1. **Destek Tanılama Anlık Görüntüsü** (WebUI Bilgi sekmesinden)
2. **Loglar** (WebUI Logs sekmesi, `action.sh` arşivi veya logcat çıktısı)
3. Cihaz modeli, Android sürümü ve Root yöntemi (KernelSU / APatch / Magisk)
