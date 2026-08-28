package cleveres.tricky.encryptor

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingBatchContractTest {
    @Test
    fun `create cbox streams large ZIP batches without retaining all XML`() {
        val root = locateRoot()
        val activity = File(root, "encryptor-app/src/main/java/cleveres/tricky/encryptor/SecureMainActivity.kt").readText()
        val reader = File(root, "encryptor-app/src/main/java/cleveres/tricky/encryptor/KeyboxZipReader.kt").readText()
        val crypto = File(root, "encryptor-app/src/main/java/cleveres/tricky/encryptor/MobileCrypto.kt").readText()
        val vault = File(root, "encryptor-app/src/main/java/cleveres/tricky/encryptor/VaultStore.kt").readText()

        assertTrue(activity.contains("var sourceUri by remember"))
        assertTrue(activity.contains("KeyboxImportReader.process("))
        assertTrue(activity.contains("MobileCrypto.encryptAndSaveStreaming"))
        assertTrue(activity.contains("VaultStore.newBatchNameAllocator"))
        assertTrue(activity.contains("R.string.encrypting"))
        assertFalse(activity.contains("List<SelectedKeybox>"))

        assertTrue(reader.contains("MAX_KEYBOX_FILES = 10_000"))
        assertTrue(reader.contains("MAX_XML_BYTES = 10 * 1024 * 1024"))
        assertTrue(reader.contains("onKeybox(safeDisplayName(entry.name), bytes)"))
        assertTrue(reader.contains("bytes.fill(0)"))
        assertFalse(reader.contains("MAX_TOTAL_XML_BYTES"))

        assertTrue(crypto.contains("encryptAndSaveStreaming"))
        assertTrue(crypto.contains("val signer = Signature.getInstance"))
        assertTrue(crypto.contains("rollbackBatch"))
        assertTrue(vault.contains("MAX_FILES = 10_000"))
        assertTrue(vault.contains("if (++scanned > MAX_FILES) throw IOException"))
        assertTrue(vault.contains("class BatchNameAllocator"))
    }

    @Test
    fun `all app locales expose streaming progress copy`() {
        val root = locateRoot()
        val localeDirs = listOf(
            "values",
            "values-tr",
            "values-zh-rCN",
            "values-es",
            "values-de",
            "values-ru",
            "values-in",
            "values-hi",
            "values-ar",
        )
        localeDirs.forEach { dir ->
            val strings = File(root, "encryptor-app/src/main/res/$dir/strings.xml").readText()
            assertTrue("$dir is missing encrypting progress copy", strings.contains("name=\"encrypting\""))
            assertTrue("$dir still documents the old 64-entry limit", !strings.contains("64 XML"))
            assertTrue("$dir still documents the old 48 MiB total limit", !strings.contains("48 MiB"))
        }
    }

    private fun locateRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir")) { "user.dir is unavailable" }
        var current = File(userDir).canonicalFile
        repeat(5) {
            if (File(current, "encryptor-app").isDirectory && File(current, "rust").isDirectory) return current
            current = current.parentFile ?: return@repeat
        }
        error("Repository root not found")
    }
}
