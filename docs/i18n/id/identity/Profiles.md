# Profiles

**Bahasa:** [English](../../../identity/Profiles.md) | [Türkçe](../../tr/identity/Profiles.md) | [简体中文](../../zh-CN/identity/Profiles.md) | [Español](../../es/identity/Profiles.md) | [Deutsch](../../de/identity/Profiles.md) | [Русский](../../ru/identity/Profiles.md) | **Bahasa Indonesia** | [हिन्दी](../../hi/identity/Profiles.md) | [العربية](../../ar/identity/Profiles.md)

Profiles menerapkan kelompok pengaturan opsional dalam satu transaksi tervalidasi; perlindungan inti boot, Keystore, dan infrastruktur RKP tetap aktif secara independen.

Daily Compatibility memakai targeted scope dan keybox monitoring; Default adalah konfigurasi konservatif (menerapkan profil Default atau mereset environment di WebUI mengembalikan `target.txt`, `identity_target.txt`, `drm_packages.txt`, `boot_props_mode`, dan `security_patch.txt` ke nilai default paket yang bersih dan menghapus setiap server jarak jauh yang dikonfigurasi beserta konten keybox yang di-cache); Maximum Compatibility mengaktifkan Global Mode, build identity, identity refresh, dan telephony lalu mematikan DRM passthrough; Minimal mematikan identity opsional dan pemeriksaan keybox terjadwal. Tidak satu pun preset mengubah perlindungan infrastruktur RKP.

Konfigurasi lama dapat tetap memiliki marker `rkp_passthrough` yang sudah retired, tetapi perilaku generated-key tidak lagi bergantung padanya. Profile version two dapat menyimpan assignment aplikasi, template, keybox tervalidasi, privacy, patch, serta pilihan identity/DRM; field RKP lama hanya dipertahankan untuk kompatibilitas migrasi dan bukan opsi WebUI aktif.
