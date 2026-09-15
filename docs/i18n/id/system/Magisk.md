# Dukungan Magisk

**Bahasa:** [English](../../../system/Magisk.md) | [Türkçe](../../tr/system/Magisk.md) | [简体中文](../../zh-CN/system/Magisk.md) | [Español](../../es/system/Magisk.md) | [Deutsch](../../de/system/Magisk.md) | [Русский](../../ru/system/Magisk.md) | **Bahasa Indonesia** | [हिन्दी](../../hi/system/Magisk.md) | [العربية](../../ar/system/Magisk.md)

## Peringatan Penting

> [!CAUTION]
> **Magisk TIDAK direkomendasikan untuk CleveresTricky.**
>
> Framework deteksi aplikasi modern dan Google Play Integrity secara aktif memeriksa namespace mount di userspace, biner root, dan injeksi Zygote. Arsitektur userspace milik Magisk meninggalkan jejak deteksi yang membuat penyembunyian root jangka panjang menjadi sangat sulit.
>
> Untuk perbandingan arsitektur mendalam antara solusi root berbasis kernel dan userspace, baca:
> 👉 **[Advanced Android Root Guide: KernelSU, APatch & Concealment | Yiğit - tryigit.dev](https://tryigit.dev/advanced-android-root-architecture-concealment/)**
>
> **Jika memungkinkan, gunakan [KernelSU](https://kernelsu.org) atau [APatch](https://apatch.dev).**

---

## Arsitektur Tanpa WebUI (Headless)

KernelSU dan APatch menyediakan lingkungan ekstensi WebUI modul bawaan di dalam aplikasi manajer mereka. Magisk tidak menerapkan standar antarmuka ini.

Oleh karena itu, saat dipasang di Magisk, **CleveresTricky berjalan dalam mode daemon latar belakang tanpa antarmuka WebUI**. Daemon native (`cleverestrickyd`), backend, dan pencegat Keystore berfungsi penuh, namun seluruh konfigurasi harus dikelola secara manual dengan mengedit file di `/data/adb/cleverestricky/`.

---

## Panduan Konfigurasi Manual

Semua file pengaturan dan kebijakan berada di direktori `/data/adb/cleverestricky/`.

### 1. Cakupan & Target Aplikasi

* **`target.txt`**: Daftar nama paket (satu per baris) yang menjadi target spoofing atestasi Keystore:
  ```text
  com.google.android.gms
  com.google.android.gms.unstable
  com.android.vending
  ```
* **`global_mode`**: File penanda kosong.
  * **Ada**: Mode Global aktif (mencegat semua aplikasi di luar sistem dan root).
  * **Tidak ada**: Hanya aplikasi yang tertera di `target.txt` yang dicegat.
  * *Perintah:* `touch /data/adb/cleverestricky/global_mode` (aktifkan) atau `rm -f /data/adb/cleverestricky/global_mode` (nonaktifkan).

* **`identity_target.txt`**: Paket target untuk pemalsuan identitas perangkat.
* **`global_identity_mode`**: File penanda untuk menerapkan pemalsuan identitas secara global.

---

### 2. Identitas Perangkat & Properti Build

* **`spoof_build_vars`**: Properti perangkat yang dipalsukan dalam format `KUNCI=NILAI`:
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
* **`security_patch.txt`**: Tanggal patch keamanan (misal `2026-03-05`). Kosongkan atau hapus file untuk penyelarasan otomatis dengan sistem.
* **`boot_props_mode`**: Mengontrol mode properti bootloader (`auto`, `force`, atau `disable`).

---

### 3. Keybox Perangkat Keras

* **`keybox.xml`**: Letakkan file XML keybox atestasi perangkat keras langsung di `/data/adb/cleverestricky/keybox.xml`. Pastikan hak akses dibatasi (`chmod 600`).
* **`keyboxes/`**: Direktori untuk menyimpan beberapa file keybox.

---

### 4. DRM & Pengecualian Privasi

* **`drm_packages.txt`**: Aplikasi streaming media yang dikecualikan dari pencegatan Keystore agar Widevine L1 tetap berfungsi:
  ```text
  com.netflix.mediaclient
  com.amazon.avod.thirdpartyclient
  com.disney.disneyplus
  ```

---

### 5. Penanda Fitur (Marker Files)

Aktifkan fitur dengan membuat file (`touch <file>`), atau matikan dengan menghapusnya (`rm -f <file>`):

| File Penanda | Fungsi Saat Ada |
| :--- | :--- |
| `spoof_enabled` | Mengaktifkan mesin pemalsuan identitas (Spoof Engine). |
| `spoof_build_identity` | Mengaktifkan pemalsuan properti build. |
| `auto_keybox_check` | Memvalidasi keybox dan memeriksa status pencabutan secara berkala. |
| `drm_passthrough` | Mengaktifkan perlindungan DRM passthrough untuk paket di `drm_packages.txt`. |
| `hide_sensitive_props` | Menyembunyikan properti sensitif root, debug, dan status bootloader. |
| `tee_broken_mode` | Status migrasi/kompatibilitas warisan. Jika ada, layanan mempertahankan penanganan migrasi warisan; perlindungan inti tidak berubah dan circuit breaker fail-closed diaktifkan secara terpisah saat kegagalan komunikasi TEE perangkat keras. |
| `debug_logging` | Mengaktifkan pencatatan log detail di `native_runtime.log`. |

---

## Menerapkan Perubahan & Verifikasi

### Menerapkan Konfigurasi
Karena tidak ada WebUI di Magisk, disarankan untuk memuat ulang perangkat setelah mengubah file konfigurasi:
```sh
su -c "reboot"
```

### Memeriksa Log Runtime
```sh
su -c "cat /data/adb/cleverestricky/native_runtime.log"
```

### Memeriksa Proses yang Berjalan
```sh
su -c "ps -A | grep -E 'cleverestrickyd|cleverestricky_backend'"
```

### Mengumpulkan Laporan Diagnostik
```sh
su -c "/data/adb/modules/cleverestricky/action.sh"
```
Arsip diagnostik akan disimpan di `/data/adb/cleverestricky/bugreports/`.
