# Dokumentasi CleveresTricky

**Bahasa:** [English](../../README.md) | [Türkçe](tr.md) | [简体中文](zh-CN.md) | [Español](es.md) | [Deutsch](de.md) | [Русский](ru.md) | **Bahasa Indonesia** | [हिन्दी](hi.md) | [العربية](ar.md)

[README Bahasa Indonesia](../../README.id.md)

> Referensi ini melokalkan dokumentasi Markdown yang ditujukan kepada pengguna. Jika ada perbedaan teknis, dokumentasi bahasa Inggris dan source code adalah referensi kanonik.

<a id="application-rules"></a>
## Application Rules

Menetapkan template, keybox lokal terverifikasi, atau privacy policy ke aplikasi yang memenuhi syarat. Rule valid sudah menjadi target eksplisit. `inherit` mempertahankan policy global, `isolate` menghasilkan IMEI/IMSI/ICCID/MEID/phone/serial/attestation identifiers dan pseudonim DRM `deviceUniqueId` yang stabil per aplikasi, sedangkan `redact` mengosongkan identifier yang didukung sambil mempertahankan permission failure Android.

Attestation Identity memerlukan keybox aktif yang valid. DRM isolation independen dari DRM Keystore Passthrough. Shared UID diselesaikan secara deterministik lewat Package Manager dan nama paket dari request tidak dipercaya. State baru dipublikasikan atomik dan cache terkait dibersihkan.

<a id="application-scope"></a>
## Application Scope

Menentukan UID aplikasi mana yang menerima compatibility certificate/identity. Targeted Mode memakai package exact atau wildcard terbatas di `target.txt`, diselesaikan melalui Package Manager ke caller sebenarnya. Paket dengan shared UID berbagi identitas Binder.

Global Mode tidak memerlukan target entry namun tetap mengecualikan system identity dan protected infrastructure. Unknown package fail closed. Rules dan short decision cache diganti bersama.

<a id="attestation"></a>
## Attestation

Lapisan attestation memberikan kompatibilitas rantai sertifikat terkontrol untuk aplikasi terpilih sambil mempertahankan pembuatan kunci Android asli dan operasi kriptografi berikutnya.

Caller infrastruktur RKP selalu tetap di jalur provisioning Android asli. Untuk UID aplikasi target, respons `generateKey` yang berhasil dan pembacaan sertifikat `getKeyEntry` berikutnya memakai satu jalur kompatibilitas agar satu alias tidak menampilkan attestation leaf yang berbeda.

Operasi private key tetap dilakukan Android KeyMint atau StrongBox. Sebelum material aktif, kecocokan key/certificate, algoritma, chain, masa berlaku, ambiguity, dan revocation diperiksa. Substitusi sertifikat tidak menciptakan hardware root of trust, mengunci bootloader secara fisik, atau menjamin remote verdict.

<a id="automatic-keybox-check"></a>
## Automatic Keybox Check

Menjaga keybox/revocation tetap terbaru tanpa continuous storage scan. File observer menangani perubahan normal dan low-frequency fallback digunakan pada filesystem tertentu.

Setiap refresh mengulang verifikasi key, chain, algorithm, validity, ambiguity dan revocation. Material baru tidak aktif bila revocation tidak dapat ditentukan. Cache dibatasi jumlah dan ukuran file.

<a id="backup-restore"></a>
## Backup and Restore

Memindahkan config dan authorized key material dalam authenticated encrypted archive. Export membutuhkan password minimal 12 karakter dan hanya mengambil file allowlist, menolak symlink, path tidak dikenal dan ukuran berlebihan.

Import hanya menerima encrypted CTSB dan membatasi upload, entry, keybox serta expanded size. Traversal, duplicate, directory, symlink destination, malformed setting dan invalid keybox ditolak sebelum write. Policy v2 divalidasi dan dipublikasikan sebagai satu snapshot.

<a id="boot-properties"></a>
## Boot Properties

Core userspace property view mengurangi exposure indikator unlocked/debug/warranty/verified boot/recovery. Set property fixed diterapkan sebelum Zygote dan tetap aktif independen dari optional identity.

`boot_props_mode` hanya mengontrol optional Build Identity compatibility (`auto`, `force`, `disable`), bukan core protection. Fitur ini tidak relock bootloader, memperbaiki verified boot atau mengubah TEE root of trust.

<a id="build-identity"></a>
## Build Identity

Menerapkan device template lengkap ke fingerprint dan supported app-visible Build fields. Optional, membutuhkan Spoof Engine dan reboot. Arbitrary Android properties ditolak.

Auto Identity dapat mengambil Pixel beta/canary dari metadata publik Google dan menyimpannya tanpa menyalakan engine otomatis. Build Identity, Security Patch, Region, Telephony dan Attestation Identity independen.

<a id="building"></a>
## Building

Memerlukan Java 21, SDK API 36, NDK 27.3.13750724, CMake 3.22.1, stable Rust, target Android ARM64/x86 64, Cargo NDK dan submodule. Kotlin/Android checks, Rust fmt/clippy/tests dan unit tests harus lulus.

CI memvalidasi shell, SELinux, template, Kotlin/Java/Rust, dua architecture, release/debug ZIP dan Encryptor. First-party C dilarang; `binder_interceptor.cpp` satu-satunya C++ ABI boundary. Release: `./gradlew zipRelease`.

<a id="certificate-safe-mode"></a>
## Certificate Safe Mode

Konsep legacy. WebUI saat ini tidak memiliki switch untuk mematikan core Keystore/TEE compatibility. Scope diatur Global Mode/Application Rules, Spoof Engine hanya identity.

`tee_broken_mode` dapat dibaca untuk migration tetapi tidak menentukan core targeting. Diagnosis dilakukan dengan mempersempit scope, memakai passthrough, atau menghapus key material secara terkontrol.

<a id="diagnostics"></a>
## Diagnostics

Periksa Dashboard untuk version, Engine, profile, keybox count, target size, RKP, DRM dan native features, lalu cari error pertama di Logs. Jika WebUI gagal, periksa logcat, daemon, `webroot`, architecture-specific `webui_bridge` dan manager state.

Untuk isolasi gunakan Minimal + reboot, verifikasi genuine path, lalu aktifkan fitur satu per satu. Effective State menampilkan rule/profile, scope, template, keybox ref, privacy, features, patches, RKP/DRM, KeyMint/StrongBox, provider coexistence dan reboot requirement tanpa private keys.

<a id="drm-passthrough"></a>
## DRM Keystore Passthrough and Identifier Privacy

Passthrough menjaga media apps tertentu pada genuine Android Keystore certificate path. Identifier Privacy hanya mengganti supported stable-AIDL `deviceUniqueId` untuk `privacy=isolate` dengan pseudonim stabil per aplikasi, tanpa genuine DRM ID sebagai input derivation.

`drm_packages.txt` mendukung exact package/wildcard terbatas. Hook hanya `IDrmFactory` / `IDrmPlugin.getPropertyByteArray("deviceUniqueId")`; HIDL, security level, license, provisioning, content key, session, HDCP dan string property tidak berubah. Unexpected ABI fail open.

<a id="encrypted-storage"></a>
## Encrypted Storage

CBOX memakai authenticated AES-256-GCM dan mengikat metadata ke ciphertext. Password container memakai bounded key derivation; local protected cache key ada di private config.

Unlock hanya lewat native WebUI dan tetap menjalankan keybox verification. Key/certificate/chain/date/algorithm/revocation diperiksa. Hostile root dapat membaca data setelah unlock.

<a id="identity-refresh"></a>
## Identity Refresh

Menyiapkan validated identity untuk boot berikutnya tanpa mengubah current snapshot. Early boot memvalidasi staged file dan atomically promotes sehingga Build Properties dan service menggunakan state sama.

IMEI/ICCID checksum dan length dibatasi. Manual edit membuang staged snapshot lama; Engine/Refresh off sebelum boot mencegah unwanted promotion.

<a id="installer"></a>
## Installer

Menginstal full KernelSU/APatch module untuk Android 12-17 ARM64/x86 64. Magisk/recovery dihentikan sebelum partial install.

Setiap payload memiliki SHA 256 dan runtime menolak symlink/non-regular/unexpected files. Internal hash bukan bukti publisher, sehingga release resmi memiliki `SHA256SUMS` dan GitHub signed build provenance.

<a id="keybox-manager"></a>
## Keybox Manager

Memuat, memverifikasi, memilih dan memonitor authorized attestation material dalam legacy/XML/CBOX. Application Rule dapat memilih file; remote material untrusted sampai lulus verifikasi lokal.

Private key harus cocok dengan leaf certificate; algorithm, chain, date, duplicate/ambiguity, revocation diperiksa. Revocation tidak jelas berarti material baru tidak aktif; broken pool ditolak penuh.

<a id="native-architecture"></a>
## Native Architecture

Portable native logic ada di Rust. Tidak ada first-party C; `binder_interceptor.cpp` satu-satunya C++ exception karena private Android libbinder object ABI. Rust Core memvalidasi Binder layout/stream, FD dan kernel-validated copies.

Rust Injector mengelola file, SELinux socket, FD transfer, maps/symbols, ptrace, registers, remote memory, loader dan cleanup. Temporary stack writes dipulihkan dari bounded journal. Exception C++ tidak boleh berkembang.

<a id="patch-levels"></a>
## Patch Levels

`security_patch.txt` menyediakan System/Vendor/Boot rules global/per-app. Mendukung date, `today`, `device_default`, `prop`, `no`; policy v2 punya Device, Property, Manual, Automatic, Omit independen.

Parsing bounded dan invalid input tidak menerapkan partial state. Automatic memakai calendar arithmetic. Fitur ini tidak memasang security update nyata, mengubah firmware atau menjamin remote verdict.

<a id="performance"></a>
## Performance and Memory

Core Keystore interception tetap aktif; Spoof Engine off memarkir optional identity/DRM/build/region/telephony work. Automatic Keybox Check punya control sendiri.

Binder parser memakai fixed arrays dan descriptor cache 64 slot. Controller/cache dibatasi dan menghindari busy poll. Rust release memakai LTO, size optimization dan hardened linking.

<a id="profiles"></a>
## Profiles

Profiles menerapkan kelompok pengaturan opsional dalam satu transaksi tervalidasi; perlindungan inti boot, Keystore, dan infrastruktur RKP tetap aktif secara independen.

Daily Compatibility memakai targeted scope dan keybox monitoring; Default adalah konfigurasi konservatif; Maximum Compatibility mengaktifkan Global Mode, build identity, identity refresh, dan telephony lalu mematikan DRM passthrough; Minimal mematikan identity opsional dan pemeriksaan keybox terjadwal. Tidak satu pun preset mengubah perlindungan infrastruktur RKP.

Konfigurasi lama dapat tetap memiliki marker `rkp_passthrough` yang sudah retired, tetapi perilaku generated-key tidak lagi bergantung padanya. Profile version two dapat menyimpan assignment aplikasi, template, keybox tervalidasi, privacy, patch, serta pilihan identity/DRM; field RKP lama hanya dipertahankan untuk kompatibilitas migrasi dan bukan opsi WebUI aktif.

<a id="provider-coexistence"></a>
## Provider Coexistence

Automatic Build Identity mendeteksi fingerprint/property provider lain seperti PIF, `autopif`/`auto_pif`, PlayCurl dan tidak overwrite.

Saat conflict, optional Build properties tetap genuine sementara fungsi lain dapat bekerja. Force sengaja bypass detection; Automatic direkomendasikan.

<a id="region-properties"></a>
## Region Properties

Memberi optional fixed China-region view melalui hardware/SIM/operator country, hardware level dan radio marker. Arbitrary property tidak diterima.

Diterapkan sebelum Zygote dengan Spoof Engine. Tidak mengubah SIM country asli, radio registration, modem firmware, secure sales region atau carrier account.

<a id="remote-sources"></a>
## Remote Sources

Mengambil authorized keybox hanya dari HTTPS eksplisit. Host/port/path/timeout/refresh/auth/header/size dibatasi dan secret tidak muncul di status.

Signature dapat diwajibkan. Sebelum signature, XML/CBOX, size, keybox, certificate dan revocation validation selesai, data tidak aktif. Failed refresh tidak mengganti verified material.

<a id="rkp-protection"></a>
## RKP Protection

Perlindungan Remote Key Provisioning menjaga infrastruktur provisioning Android pada jalur platform asli. Paket RKP Android/Google dan Remote Provisioner legacy selalu di luar scope substitusi; UID sistem dan resolusi package yang tidak diketahui juga fail closed.

Caller infrastruktur RKP tidak pernah dimodifikasi. Untuk UID aplikasi target, `generateKey` dan respons sertifikat `getKeyEntry` berikutnya memakai jalur kompatibilitas terpadu sehingga satu alias tidak menghasilkan dua attestation leaf berbeda.

Switch lama `rkp_passthrough` sudah retired. Marker dapat tetap ada di config atau backup lama, tetapi tidak lagi mengontrol generated-key dan tidak diekspos sebagai runtime toggle WebUI. Built-in Profiles tidak mengubah perilaku RKP; perlindungan infrastrukturnya selalu aktif.

CleveresTricky tidak mensimulasikan server RKP, membuat provisioning credential, atau mengubah hardware provisioning root.

<a id="security-model"></a>
## Security Model

Root service, OS, KernelSU/APatch, module files dan authorized key material trusted. Apps, Binder, uploads, remote response, config, archive, rules, templates, path dan network metadata untrusted.

Config root-owned, sensitive root-only, symlink rejected, writes atomic. Binder ABI/kernel validated copy diperiksa. Injector membatasi symbol/process/library, WebUI tidak membuka TCP dan memakai strict native bridge. Hostile root di luar perlindungan penuh.

<a id="spoof-engine"></a>
## Spoof Engine

Optional app-facing identity controller. Core Keystore/TEE, certificate compatibility, root of trust dan boot protection tetap berjalan saat off.

Saat on, optional Attestation/Telephony/Build/Region/Refresh mengikuti controls masing-masing. Off tidak menghapus saved values. App cache mungkin perlu restart, Build Identity perlu reboot.

<a id="telephony-identity"></a>
## Telephony Identity

Dapat menampilkan IMEI, MEID, IMSI, ICCID dan phone untuk dua SIM lewat supported Binder APIs. Checksum, length, syntax, slot dan input size divalidasi.

Genuine Android response diambil dulu; permission denial/error/null dipertahankan. Modem, baseband, EFS, physical SIM dan carrier identity tidak berubah.

<a id="web-interface"></a>
## Web Interface

Fixed ownership: `index.html` markup/base CSS, `bridge.js` native bridge/intents, `policy.js` policy/state UI, `ux.js` presentation/localization/guide/community. Tidak ada standalone runtime CSS atau feature-specific JS bundles.

Mobile bottom navigation, touch controls, responsive panels, password visibility, progress dan accessibility tersedia. Tidak ada TCP listener; native manager API, bounded Rust bridge, root-only queues dan strict validation digunakan.

<a id="changelog"></a>
## CHANGELOG

V2.5.3 menambah granular identity/security patch controls, profiles, Effective State; memperkuat Attestation/KeyMint/StrongBox/DRM privacy/upgrades/Android 17; mengonsolidasikan WebUI dan translations; menambah KeyboxHub external browser helper; memperbaiki diagnostics, cache/timing, dependency security, regression dan artifact validation.

<a id="contributing"></a>
## Contributing

Pertahankan fail-closed model, Android 12-17, KernelSU/APatch dan jangan membuat klaim hardware integrity yang tidak dapat diverifikasi. Jalankan Kotlin/Android/Rust checks; portable native additions di Rust, first-party C dilarang, `binder_interceptor.cpp` satu-satunya C++ exception.

Binder/XML/ZIP/CBOX/HTTP/path/PID untrusted dan membutuhkan bounds/failure tests. Jangan commit private key, keybox, token, secret, generated APK/ZIP. Update docs untuk perubahan user-visible.

<a id="donate"></a>
## Development Support

Dukungan dapat diberikan melalui opsi dalam `DONATE.md`: USDT TRC20, XMR, USDT/USDC ERC20/BEP20, Binance User ID, PayPal, BuyMeACoffee dan situs developer. Selalu cek alamat terbaru di file English canonical sebelum transfer.

<a id="languages"></a>
## Language Support

WebUI memiliki English, Türkçe, 简体中文, Español, Deutsch, Русский, Bahasa Indonesia, हिन्दी dan العربية. Runtime catalog hanya di `ux.js`, tanpa locale-specific JS/CSS. User docs menyediakan README dan reference dalam sembilan bahasa yang sama.

Perubahan user-facing Markdown harus menyinkronkan English canonical dan localized section terkait.

<a id="logging"></a>
## Logging and Diagnostics

Diagnostics ditulis ke Android logcat, tidak ada separate plaintext log. Perintah utama `adb logcat -s cleverestricky CleveresTricky`. Marker service/bridge/Binder/TEE membantu startup diagnosis.

`TAMPER DETECTED`, Binder ABI failure, rejected keybox, injector timeout perlu diperiksa. Review log sebelum publikasi karena filename/package/property/PID bisa sensitif.

<a id="theme"></a>
## UI Theme

UI memakai minimal monochrome Nothing OS/iOS hybrid: charcoal background, light gray text, silver accent, dark panel, green success, red danger. System sans, monospace technical data, Dynamic Island, rounded buttons, iOS toggles dan mobile-first layout.

Touch target sekitar 44px+, vertical flow diprioritaskan dan UI dioptimalkan untuk penggunaan di KernelSU/APatch pada ponsel.
