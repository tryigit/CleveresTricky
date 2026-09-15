# Attestation

**Bahasa:** [English](../../../security/Attestation.md) | [Türkçe](../../tr/security/Attestation.md) | [简体中文](../../zh-CN/security/Attestation.md) | [Español](../../es/security/Attestation.md) | [Deutsch](../../de/security/Attestation.md) | [Русский](../../ru/security/Attestation.md) | **Bahasa Indonesia** | [हिन्दी](../../hi/security/Attestation.md) | [العربية](../../ar/security/Attestation.md)

Lapisan attestation memberikan kompatibilitas rantai sertifikat terkontrol untuk aplikasi terpilih sambil mempertahankan pembuatan kunci Android asli dan operasi kriptografi berikutnya.

Caller infrastruktur RKP selalu tetap di jalur provisioning Android asli. Untuk UID aplikasi target, respons `generateKey` yang berhasil dan pembacaan sertifikat `getKeyEntry` berikutnya memakai satu jalur kompatibilitas agar satu alias tidak menampilkan attestation leaf yang berbeda.

Operasi private key tetap dilakukan Android KeyMint atau StrongBox. Sebelum material aktif, kecocokan key/certificate, algoritma, chain, masa berlaku, ambiguity, dan revocation diperiksa. Substitusi sertifikat tidak menciptakan hardware root of trust, mengunci bootloader secara fisik, atau menjamin remote verdict.

## Provisi TEE Perangkat Keras & Catatan Pemulihan Perangkat Tidak Terkunci

CleveresTricky memerlukan subsistem KeyMint/TEE perangkat keras yang berfungsi dan sengaja tidak menyimulasikan atau meniru TEE perangkat lunak palsu atau pengesahan palsu. Jika komunikasi TEE perangkat keras gagal (`SECURE_HW_COMMUNICATION_FAILED` / kode kesalahan -49), modul menghentikan intersepsi untuk mencegah kebuntuan (deadlock) pada framework.

Pada perangkat Android yang bootloader-nya tidak terkunci, pembukaan kunci dapat merusak pengesahan perangkat keras atau Remote Key Provisioning (RKP):
- **Pemulihan TEE RKP Sisi Perangkat**: Seperti yang ditunjukkan dalam [investigasi wuxianlin](https://wuxianlin.com/2025/11/12/oneplus-13-15-attestation-rkp-test/) (dievaluasi pada perangkat modern termasuk OnePlus 13/15), provisi ID perangkat `KmInstallKeybox` dapat memulihkan TEE RKP asli (dan Widevine L1 RKP) tanpa menyuntikkan kunci yang bocor. Ini berfungsi sebagai mekanisme pemulihan provisi TEE sisi perangkat yang sah di seluruh perangkat Android yang tidak terkunci, bukan spoofing atau emulasi perangkat lunak Keystore.
- **Cakupan & Rekomendasi**:
  - Provisi `KmInstallKeybox` sisi perangkat memulihkan penerbitan sertifikat TEE RKP asli dari infrastruktur provisi jarak jauh.
  - Metode ini tidak memulihkan Kunci Pengesahan TEE offline bawaan pabrik secara universal.
  - **Peringatan Risiko**: Memodifikasi `persist` atau provisi TEE perangkat keras memiliki risiko kerusakan permanen atau brick. Selalu buat cadangan penuh partisi `persist` dan firmware Anda sebelum mencoba pemulihan TEE.
