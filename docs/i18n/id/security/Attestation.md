# Attestation

**Bahasa:** [English](../../../security/Attestation.md) | [Türkçe](../../tr/security/Attestation.md) | [简体中文](../../zh-CN/security/Attestation.md) | [Español](../../es/security/Attestation.md) | [Deutsch](../../de/security/Attestation.md) | [Русский](../../ru/security/Attestation.md) | **Bahasa Indonesia** | [हिन्दी](../../hi/security/Attestation.md) | [العربية](../../ar/security/Attestation.md)

Lapisan attestation memberikan kompatibilitas rantai sertifikat terkontrol untuk aplikasi terpilih sambil mempertahankan pembuatan kunci Android asli dan operasi kriptografi berikutnya.

Caller infrastruktur RKP selalu tetap di jalur provisioning Android asli. Untuk UID aplikasi target, respons `generateKey` yang berhasil dan pembacaan sertifikat `getKeyEntry` berikutnya memakai satu jalur kompatibilitas agar satu alias tidak menampilkan attestation leaf yang berbeda.

Operasi private key tetap dilakukan Android KeyMint atau StrongBox. Sebelum material aktif, kecocokan key/certificate, algoritma, chain, masa berlaku, ambiguity, dan revocation diperiksa. Substitusi sertifikat tidak menciptakan hardware root of trust, mengunci bootloader secara fisik, atau menjamin remote verdict.

## Provisi TEE Perangkat Keras & Catatan Pemulihan OnePlus

CleveresTricky memerlukan subsistem KeyMint/TEE perangkat keras yang berfungsi dan sengaja tidak menyimulasikan atau meniru TEE perangkat lunak palsu atau pengesahan palsu. Jika komunikasi TEE perangkat keras gagal (`SECURE_HW_COMMUNICATION_FAILED` / kode kesalahan 10), modul menghentikan intersepsi untuk mencegah kebuntuan (deadlock) pada framework.

Pada perangkat tertentu yang tidak terkunci, pembukaan bootloader dapat membatalkan atau merusak pengesahan perangkat keras atau provisi RKP di sisi perangkat:
- **Pengesahan OnePlus 13 / 15 Tidak Terkunci & Pemulihan TEE RKP**: Sebagaimana didokumentasikan dalam [investigasi wuxianlin](https://wuxianlin.com/2025/11/12/oneplus-13-15-attestation-rkp-test/), pembukaan bootloader pada perangkat OnePlus 13 dan 15 yang diuji merusak TEE Attestation dan provisi RKP.
- **Alternatif Provisi Sisi Perangkat**: Laporan tersebut mencatat bahwa provisi ID perangkat `KmInstallKeybox` dapat memulihkan TEE RKP asli (dan pada model yang diuji Widevine L1 RKP) tanpa menyuntikkan kunci atestasi yang bocor. Ini adalah alternatif pemulihan/provisi TEE sisi perangkat yang sah, bukan spoofing atau emulasi Keystore.
- **Cakupan & Batasan**:
  - Metode ini **TIDAK** memulihkan Kunci Pengesahan TEE offline (offline TEE Attestation Key) secara universal.
  - Sangat **spesifik untuk perangkat dan firmware**. Tidak boleh direkomendasikan secara umum atau dianggap berlaku untuk semua model OnePlus.
  - **Peringatan Risiko**: Memodifikasi partisi `persist` atau provisi TEE sisi perangkat memiliki risiko signifikan kerusakan permanen keystore atau brick pada perangkat. Selalu buat cadangan penuh partisi `persist` dan firmware Anda sebelum mencoba pemulihan provisi TEE.
