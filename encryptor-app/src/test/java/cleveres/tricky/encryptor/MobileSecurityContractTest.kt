package cleveres.tricky.encryptor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileSecurityContractTest {
    @Test
    fun `mobile app keeps the same hardened trust boundaries as native services`() {
        val root = locateRoot()
        val manifest = File(root, "encryptor-app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(manifest.contains("android:localeConfig=\"@xml/locales_config\""))
        assertTrue(manifest.contains("android:name=\".SecureMainActivity\""))
        assertFalse(manifest.contains("android:name=\".MainActivity\""))
        assertFalse(manifest.contains("android.permission.INTERNET"))

        for (path in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val rules = File(root, "encryptor-app/src/main/res/xml/$path").readText()
            assertFalse("Backup rules must never include app data", rules.contains("<include"))
            assertTrue("Backup rules must explicitly exclude data", rules.contains("<exclude"))
        }

        val activity = File(root, "encryptor-app/src/main/java/cleveres/tricky/encryptor/SecureMainActivity.kt").readText()
        assertTrue(activity.contains("WindowManager.LayoutParams.FLAG_SECURE"))
        assertTrue(activity.contains("CleveresVaultColors"))
        assertTrue(activity.contains("LanguagePicker()"))
        assertTrue(activity.contains("context.noBackupFilesDir"))
        assertTrue(activity.contains("enableEdgeToEdge()"))
        assertTrue(activity.contains("KeyboxImportReader.read(input, selectedName, NativeCrypto::validateKeyboxXml)"))
        assertTrue(activity.contains("\"application/zip\""))
        assertTrue(activity.contains("\"text/xml\""))
        assertTrue(activity.contains("\"application/xml\""))
        assertTrue(activity.contains("R.string.signing_public_key"))
        assertTrue(activity.contains("readOnly = true"))
        assertTrue(activity.contains("MobileCrypto.encryptAndSaveBatch"))
        assertTrue(activity.contains("VaultStore.allocateBatchFilenames"))
        assertTrue(activity.contains(".imePadding()"))
        assertFalse(activity.contains("getExternalFilesDir"))

        val zipReader = File(root, "encryptor-app/src/main/java/cleveres/tricky/encryptor/KeyboxZipReader.kt").readText()
        assertTrue(zipReader.contains("KeyboxImportReader"))
        assertTrue(zipReader.contains("ZipInputStream"))
        assertTrue(zipReader.contains("MAX_KEYBOX_FILES = 64"))
        assertTrue(zipReader.contains("MAX_TOTAL_XML_BYTES = 48 * 1024 * 1024"))
        assertTrue(zipReader.contains("MAX_ARCHIVE_BYTES = 64 * 1024 * 1024"))
        assertTrue(zipReader.contains("MAX_XML_BYTES = 10 * 1024 * 1024"))
        assertTrue(zipReader.contains("readBoundedBytes"))
        assertTrue(zipReader.contains("ZeroizingByteAccumulator"))
        assertTrue(zipReader.contains("selected.forEach { it.bytes.fill(0) }"))
        assertFalse(zipReader.contains("FileOutputStream"))

        val experience = File(root, "encryptor-app/src/main/java/cleveres/tricky/encryptor/VaultExperience.kt").readText()
        assertTrue(experience.contains("Modifier.size(48.dp)"))

        val mobileCrypto = File(root, "encryptor-app/src/main/java/cleveres/tricky/encryptor/MobileCrypto.kt").readText()
        assertTrue(mobileCrypto.contains("AndroidKeyStore"))
        assertTrue(mobileCrypto.contains("setKeySize(3072)"))
        assertTrue(mobileCrypto.contains("setIsStrongBoxBacked(true)"))
        assertTrue(mobileCrypto.contains("NativeCrypto.encryptAndSave"))
        assertTrue(mobileCrypto.contains("encryptAndSaveBatch"))
        assertTrue(mobileCrypto.contains("rollbackBatch"))
        assertTrue(mobileCrypto.contains("EncryptResult"))
        assertFalse(mobileCrypto.contains("PBKDF2"))
        assertFalse(mobileCrypto.contains("AES/GCM"))

        val vault = File(root, "encryptor-app/src/main/java/cleveres/tricky/encryptor/VaultStore.kt").readText()
        assertTrue(vault.contains("NativeCrypto.ensureVault"))
        assertTrue(vault.contains("NativeCrypto.readEncrypted"))
        assertTrue(vault.contains("NativeCrypto.deleteEncrypted"))
        assertTrue(vault.contains("NativeCrypto.storeEncrypted"))
        assertTrue(vault.contains("allocateBatchFilenames"))
        assertTrue(vault.contains("lowercase(Locale.ROOT)"))

        val native = File(root, "rust/encryptor-native/src/lib.rs").readText()
        assertTrue(native.contains("#![deny(unsafe_code)]"))
        assertTrue(native.contains("parse_keybox_xml_bytes"))
        assertTrue(native.contains("TrustedDir::open"))
        assertTrue(native.contains("atomic_write"))
        assertTrue(native.contains("read_bounded"))
        assertTrue(native.contains("unlink_file"))
        assertTrue(native.contains("0o700"))
        assertTrue(native.contains("0o600"))
        assertTrue(native.contains("EnvUnowned"))
        assertTrue(native.contains(".with_env("))
        assertTrue(native.contains("Outcome::Panic(_)"))
        assertFalse("Mobile Rust code must not contain unsafe blocks", native.contains("unsafe {"))
        assertEquals(
            "Unsafe-code allowances must remain confined to the six JNI symbol exports",
            6,
            Regex("#\\[allow\\(unsafe_code\\)]\\s*#\\[unsafe\\(no_mangle\\)]")
                .findAll(native)
                .count(),
        )
        assertEquals(
            "Every JNI export must use the explicit Rust 2024 unsafe attribute",
            6,
            Regex("#\\[unsafe\\(no_mangle\\)]").findAll(native).count(),
        )

        assertFalse(File(root, "encryptor-app/src/main/java/cleveres/tricky/encryptor/CryptoUtils.kt").exists())
        assertFalse(File(root, "encryptor-app/src/main/java/cleveres/tricky/encryptor/MainActivity.kt").exists())
    }

    @Test
    fun `mobile and module versions come from the same 2_6_0 root source`() {
        val root = locateRoot()
        val rootBuild = File(root, "build.gradle.kts").readText()
        val appBuild = File(root, "encryptor-app/build.gradle.kts").readText()
        assertTrue(rootBuild.contains("val verName = \"V2.6.0\""))
        assertTrue(appBuild.contains("versionCode = moduleVersionCode"))
        assertTrue(appBuild.contains("versionName = moduleVersionName"))
        assertTrue(appBuild.contains("rootProject.extra[\"verCode\"]"))
        assertTrue(appBuild.contains("rootProject.extra[\"verName\"]"))
        assertFalse(appBuild.contains("versionCode = 1"))
        assertFalse(appBuild.contains("versionName = \"1.0\""))
    }

    private fun locateRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir")) { "user.dir is unavailable" }
        var current = File(userDir).canonicalFile
        repeat(5) {
            if (File(current, "encryptor-app").isDirectory && File(current, "rust").isDirectory) {
                return current
            }
            current = current.parentFile ?: return@repeat
        }
        error("Repository root not found")
    }
}
