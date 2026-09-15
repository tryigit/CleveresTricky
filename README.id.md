# CleveresTricky

**Bahasa:** [English](README.md) | [Türkçe](README.tr.md) | [简体中文](README.zh-CN.md) | [Español](README.es.md) | [Deutsch](README.de.md) | [Русский](README.ru.md) | **Bahasa Indonesia** | [हिन्दी](README.hi.md) | [العربية](README.ar.md)

[![Release](https://img.shields.io/github/v/release/tryigit/CleveresTricky?display_name=tag&sort=semver&label=Release)](https://github.com/tryigit/CleveresTricky/releases/latest)
[![Unduhan](https://img.shields.io/github/downloads/tryigit/CleveresTricky/total?color=0A84FF&label=Unduhan)](https://github.com/tryigit/CleveresTricky/releases)
![Android](https://img.shields.io/badge/Android-12--17-3DDC84?logo=android&logoColor=white)
![Module](https://img.shields.io/badge/Module-KernelSU%20%7C%20APatch-6f42c1)

CleveresTricky adalah modul KernelSU dan APatch untuk Android 12-17. Modul ini menyatukan kompatibilitas Android Keystore dan attestation, pengelolaan Keybox/CBOX, target aplikasi, kontrol identitas opsional, pengaturan patch level, dan fitur privasi dalam satu WebUI mobile.

Mulailah dengan pengaturan bawaan dan aktifkan hanya fitur yang benar-benar Anda perlukan.

## Yang dapat Anda lakukan

- Mengelola, memverifikasi, memilih, dan mengganti file **Keybox/CBOX**.
- Menggunakan Global Mode atau menerapkan aturan ke aplikasi tertentu.
- Mengatur tampilan device/build, attestation, telephony, region, dan security patch secara opsional.
- Melindungi alur Remote Key Provisioning dan mengurangi paparan identifier DRM yang didukung tanpa mengklaim DRM bypass.
- Mencadangkan pengaturan, melihat effective state, dan mengumpulkan diagnostik melalui WebUI atau Action modul.

## Mulai cepat

1. Unduh ZIP terbaru dari halaman resmi [Releases](https://github.com/tryigit/CleveresTricky/releases/latest).
2. Pasang ZIP melalui KernelSU atau APatch saat Android sedang berjalan.
3. Buka WebUI CleveresTricky dari pengelola modul Anda.
4. Tambahkan hanya **Keybox atau CBOX** yang Anda miliki atau Anda memiliki izin untuk mengujinya.
5. Gunakan konfigurasi bawaan terlebih dahulu, lalu aktifkan identitas, aturan aplikasi, atau opsi privasi hanya saat diperlukan.

Proyek ini tidak menyertakan Keybox siap pakai atau private attestation key.

## Lingkungan yang didukung

- Android **12-17** / API **31-37**
- **ARM64** dan **x86-64**
- **KernelSU** dan **APatch** (direkomendasikan, dukungan WebUI penuh)
- **Magisk** (mode headless / konfigurasi manual melalui `/data/adb/cleverestricky/`, [tidak direkomendasikan](https://tryigit.dev/advanced-android-root-architecture-concealment/))

Instalasi melalui recovery tidak didukung.

## Hal penting

CleveresTricky meningkatkan jalur kompatibilitas lokal, tetapi hasil remote tetap bergantung pada perangkat asli, firmware, status sertifikasi, Google Play services, kebijakan server, dan data yang Anda konfigurasi. Modul ini tidak dapat menjamin hasil Play Integrity atau attestation tertentu.

Modul ini tidak mengunci ulang bootloader secara fisik, tidak menulis ulang pengukuran Verified Boot, tidak mengubah hardware Root of Trust, modem/baseband, dan tidak mengubah fitur privasi DRM menjadi DRM bypass.

Gunakan hanya konfigurasi dan kredensial yang memang Anda berhak gunakan.

## Pelajari lebih lanjut

- [Panduan Strong Integrity](docs/i18n/id/security/StrongIntegrityGuide.md) - panduan cepat untuk memenuhi Google Play Integrity (MEETS_STRONG_INTEGRITY) di ROM Resmi, AOSP, dan Custom.
- [Panduan Dukungan Magisk](docs/i18n/id/system/Magisk.md) - konfigurasi manual headless dan panduan penyembunyian root untuk pengguna Magisk.
- [Keybox Manager](docs/i18n/id/security/KeyboxManager.md) - pemuatan, verifikasi, pemilihan, dan pemeriksaan pencabutan Keybox/CBOX.
- [Application Scope](docs/i18n/id/identity/ApplicationScope.md) dan [Application Rules](docs/i18n/id/identity/ApplicationRules.md) - pilih aplikasi tempat fitur diterapkan.
- [Build Identity](docs/i18n/id/identity/BuildIdentity.md), [Telephony Identity](docs/i18n/id/identity/TelephonyIdentity.md), dan [Patch Levels](docs/i18n/id/identity/PatchLevels.md) - kontrol identitas opsional.
- [RKP Protection](docs/i18n/id/security/RkpProtection.md) dan [DRM Privacy](docs/i18n/id/system/DrmPassthrough.md) - kompatibilitas platform dan perilaku privasi.
- [Backup and Restore](docs/i18n/id/system/BackupRestore.md) - backup dan pemulihan konfigurasi terenkripsi.
- [Security Model](docs/i18n/id/security/SecurityModel.md) dan [Installer](docs/i18n/id/system/Installer.md) - batas kepercayaan dan detail instalasi.

## Perlu bantuan?

Gunakan halaman **Logs** di WebUI atau **Action** modul untuk membuat laporan diagnostik darurat. Periksa arsip sebelum membagikannya karena diagnostik dapat berisi informasi perangkat dan sistem.

Lihat [Diagnostics](docs/i18n/id/system/Diagnostics.md) untuk masalah umum dan langkah pemecahan masalah.

## Proyek

[Changelog](CHANGELOG.md) · [Kontribusi](docs/i18n/id/CONTRIBUTING.md) · [Bahasa](docs/i18n/id/LANGUAGES.md) · [Lisensi](LICENSING.md) · [Donasi](docs/i18n/id/DONATE.md) · [Telegram](https://t.me/cleverestech)
