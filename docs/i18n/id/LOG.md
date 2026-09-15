# Pencatatan Log dan Diagnostik (Logging and Diagnostics)

**Bahasa:** [English](../../../LOG.md) | [Türkçe](../tr/LOG.md) | [简体中文](../zh-CN/LOG.md) | [Español](../es/LOG.md) | [Deutsch](../de/LOG.md) | [Русский](../ru/LOG.md) | **Bahasa Indonesia** | [हिन्दी](../hi/LOG.md) | [العربية](../ar/LOG.md)

CleveresTricky menyediakan sejumlah mekanisme bawaan untuk mengambil ringkasan diagnostik, log runtime, dan arsip laporan bug darurat. Saat melaporkan masalah (issue) atau perilaku yang tidak terduga, melampirkan data diagnostik dan log sangat penting untuk menemukan akar masalah.

---

## Metode 1: Antarmuka Manajemen WebUI (Direkomendasikan)

WebUI adalah cara tercepat dan termudah untuk mengumpulkan diagnostik langsung dari perangkat tanpa memerlukan ADB atau PC.

### A. Ringkasan Diagnostik Dukungan (Support Diagnostics Snapshot)
1. Buka CleveresTricky WebUI melalui browser atau pengelola root Anda.
2. Buka tab **Info & Sumber Daya (Info & Resources)**.
3. Gulir ke bawah ke bagian **Diagnostik Dukungan (Support Diagnostics)**.
4. Klik tombol **Salin Diagnostik (Copy Diagnostics)**.
5. Tempel teks yang telah disalin langsung ke template laporan GitHub pada bagian **Support Diagnostics Snapshot**.

> [!NOTE]
> Ringkasan diagnostik ini dirancang khusus untuk melindungi privasi Anda. Laporan hanya berisi status modul, versi runtime, jenis lingkungan root, penggunaan RAM/CPU, dan status sakelar fitur. Kunci privat, kredensial, sertifikat keybox, nama paket, atau nilai identitas tidak akan pernah disertakan.

### B. Log Langsung & Debug Logging di WebUI
1. Di WebUI, buka tab **Log (Logs)**.
2. Alihkan sakelar **Debug Logging** ke posisi **AKTIF** (sakelar berubah menjadi hijau). Ini mengaktifkan pelacakan runtime mendalam tanpa harus memasang APK debug.
3. Ulangi langkah yang memicu masalah atau jalankan uji Play Integrity pada perangkat Anda.
4. Kembali ke tab **Logs** di WebUI lalu klik **Salin (Copy)**.
5. Tempel log ke dalam laporan bug Anda.
6. Matikan kembali sakelar **Debug Logging** setelah selesai untuk menghemat daya dan sumber daya sistem.

---

## Metode 2: Arsip Laporan Bug Darurat Satu Klik (`action.sh`)

CleveresTricky memiliki alat laporan otomatis yang mengemas log sistem, status modul, dan data lingkungan root ke dalam arsip terkompresi (`.tar.gz`).

### Melalui Pengelola Root:
- Di **KernelSU** atau **APatch**, ketuk tombol **Tindakan (Action)** di sebelah kartu modul CleveresTricky.

### Melalui Terminal (Termux / Shell Root):
```bash
su -c /data/adb/modules/cleverestricky/action.sh
```

### Lokasi Berkas Laporan:
- Arsip yang dibuat akan tersimpan di:
  ```
  /data/adb/cleverestricky/bugreports/CleveresTricky_Bugreport_<stempel_waktu>.tar.gz
  ```
  (dan otomatis disalin ke folder `Download/` perangkat jika memiliki izin akses).
- Anda dapat langsung melampirkan arsip ini ke issue GitHub Anda.

> [!TIP]
> Berkas XML/CBOX keybox dan sertifikat rahasia sengaja dikecualikan dari arsip ini untuk menjamin keamanan Anda.

---

## Metode 3: Log Runtime Daemon Asli (`native_runtime.log`)

Layanan latar belakang native secara berkala mencatat status inisialisasi, kait (hooks), dan status operasinya ke:
```
/data/adb/cleverestricky/native_runtime.log
```

Untuk melihat baris log terbaru melalui terminal:
```bash
su -c "tail -n 100 /data/adb/cleverestricky/native_runtime.log"
```

---

## Metode 4: Android Logcat (ADB atau Terminal)

Jika WebUI tidak dapat terbuka atau modul gagal berjalan saat booting, logcat memberikan informasi langsung mengenai proses awal dan transaksi Binder.

### Pemantauan langsung melalui ADB:
```bash
adb logcat -s cleverestricky CleveresTricky
```

### Melalui Termux atau shell root:
```bash
su -c "logcat -d -s cleverestricky CleveresTricky"
```

### Perekaman bersih saat Keystore dimulai ulang:
```bash
adb logcat -c
adb shell su -c 'setprop ctl.restart keystore2'
adb logcat -d -s cleverestricky CleveresTricky
```

### Penanda penting saat booting:
- `Welcome to Service!`
- `Web server on port ...`
- `libbinder ioctl hook installed successfully`
- `Keystore Binder interceptor registered`
- `TEE SecurityLevel interceptor registered`

### Penanda Kegagalan Perangkat Keras KeyMint / TEE:
Jika Anda melihat baris berikut pada log:
```text
[WARN] Platform KeyMint HAL unreachable or TEE broken (SECURE_HW_COMMUNICATION_FAILED).
[INFO] CleveresTricky requires a functional hardware KeyMint; aborting injection to prevent framework deadlock.
[INFO] Please consult documentation (docs/security/Attestation.md or docs/LOG.md) for TEE recovery guidance.
```
Ini menunjukkan bahwa perangkat keras TEE atau vendor KeyMint HAL gagal berkomunikasi (kode kesalahan -49 / SECURE_HW_COMMUNICATION_FAILED). CleveresTricky sengaja menghentikan intersepsi untuk mencegah kebuntuan (deadlock) sistem. Lihat [Attestation.md](security/Attestation.md#provisi-tee-perangkat-keras--catatan-pemulihan-perangkat-tidak-terkunci) untuk opsi pemulihan provisi TEE sisi perangkat.

> [!WARNING]
> Tinjau kembali isi log sebelum mengunggahnya ke publik. Meskipun token WebUI dan kredensial rahasia tidak pernah dicatat, nama model perangkat, PID, dan nama paket aplikasi mungkin tetap terlihat.

---

## Informasi Wajib untuk Laporan Masalah

Saat membuat laporan di GitHub, harap sertakan:
1. **Ringkasan Diagnostik Dukungan** (dari tab Info di WebUI)
2. **Log** (dari tab Logs WebUI dengan Debug Logging aktif, arsip `action.sh`, atau logcat)
3. Model perangkat, versi Android, dan metode Root (KernelSU / APatch / Magisk)
