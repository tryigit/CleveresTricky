# Backup and Restore

**Bahasa:** [English](../../../system/BackupRestore.md) | [Türkçe](../../tr/system/BackupRestore.md) | [简体中文](../../zh-CN/system/BackupRestore.md) | [Español](../../es/system/BackupRestore.md) | [Deutsch](../../de/system/BackupRestore.md) | [Русский](../../ru/system/BackupRestore.md) | **Bahasa Indonesia** | [हिन्दी](../../hi/system/BackupRestore.md) | [العربية](../../ar/system/BackupRestore.md)

Memindahkan config dan authorized key material dalam authenticated encrypted archive. Export membutuhkan password minimal 12 karakter dan hanya mengambil file allowlist, menolak symlink, path tidak dikenal dan ukuran berlebihan.

Import hanya menerima encrypted CTSB dan membatasi upload, entry, keybox serta expanded size. Traversal, duplicate, directory, symlink destination, malformed setting dan invalid keybox ditolak sebelum write. Policy v2 divalidasi dan dipublikasikan sebagai satu snapshot. Pengaturan server jarak jauh juga disertakan terenkripsi dan terikat perangkat; hanya dipulihkan jika dapat didekripsi dan divalidasi di perangkat yang sama, dan tanpa entri pengaturan server yang ada tetap dipertahankan. Menerapkan profil Default menghapus semua server jarak jauh dan konten keybox yang di-cache.
