package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class WebServerBackupTest {
    private lateinit var testDir: File
    private lateinit var configDir: File
    private lateinit var originalSecureFileImpl: SecureFileOperations
    private lateinit var originalConfigRoot: File

    @Before
    fun setUp() {
        testDir = File(System.getProperty("java.io.tmpdir"), "cleverestricky_test_${System.currentTimeMillis()}")
        testDir.mkdirs()
        configDir = File(testDir, "config")
        configDir.mkdirs()
        originalSecureFileImpl = SecureFile.impl
        originalConfigRoot = Config.getConfigRoot()
        Config.setRootForTesting(configDir)

        // Mock SecureFile to use standard IO
        SecureFile.impl =
            object : SecureFileOperations {
                override fun writeText(
                    file: File,
                    content: String,
                ) {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                }

                override fun writeStream(
                    file: File,
                    inputStream: java.io.InputStream,
                    limit: Long,
                ) {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        var totalBytes = 0L
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            if (limit > 0 && totalBytes + bytesRead > limit) {
                                throw java.io.IOException("File size exceeds limit of $limit bytes")
                            }
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                        }
                    }
                }

                override fun mkdirs(
                    file: File,
                    mode: Int,
                ) {
                    file.mkdirs()
                }

                override fun touch(
                    file: File,
                    mode: Int,
                ) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                }
            }
    }

    @After
    fun tearDown() {
        Config.updateAppConfigs(null).getOrThrow()
        Config.setRootForTesting(originalConfigRoot)
        SecureFile.impl = originalSecureFileImpl
        testDir.deleteRecursively()
    }

    @Test
    fun testBackupAndRestore() {
        // Setup initial state
        File(configDir, "target.txt").writeText("com.example.app")
        File(configDir, "spoof_build_vars").writeText("MODEL=Pixel 8")
        File(configDir, "app_config").writeText("com.example.app null null isolate")
        File(configDir, "keybox.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)
        File(configDir, "module_hash").writeText("ab".repeat(32))
        val policyState =
            """{"version":2,"features":{"buildIdentity":true,"attestationIdentity":false,"telephonyIdentity":false,"regionIdentity":false,"identityRefresh":false,"securityPatch":true},"securityPatch":{"automaticThresholdMonths":6,"system":{"mode":"manual","value":"2026-07-05"},"vendor":{"mode":"device_default"},"boot":{"mode":"no"}},"profiles":[],"activeProfile":null}"""
        File(configDir, PolicyState.STATE_FILE).writeText(policyState)
        File(configDir, "ignored_file.txt").writeText("should not be backed up")
        Config.updateAppConfigs(File(configDir, "app_config")).getOrThrow()
        val privacySeed = File(configDir, "privacy_seed")
        assertFalse("Loading isolate rules should not eagerly create a privacy seed", privacySeed.exists())
        WebServer.createBackupZip(configDir).fill(0)
        assertTrue("Backup should materialize the privacy seed", privacySeed.exists())
        val originalPrivacySeed = privacySeed.readBytes()
        assertTrue(privacySeed.delete())

        val kbDir = File(configDir, "keyboxes")
        kbDir.mkdirs()
        File(kbDir, "kb1.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)
        val cboxBytes = ByteArray(1024 * 1024 + 1)
        ByteBuffer.wrap(cboxBytes)
            .put("CBOX".toByteArray(StandardCharsets.US_ASCII))
            .putInt(2)
        File(kbDir, "encrypted.cbox").writeBytes(cboxBytes)
        File(kbDir, "invalid.txt").writeText("ignore me")

        // Create Backup
        val zipBytes = WebServer.createBackupZip(configDir)
        assertTrue("Zip should not be empty", zipBytes.isNotEmpty())

        // Clear config dir to simulate fresh install or data loss
        configDir.deleteRecursively()
        configDir.mkdirs()

        // Restore
        WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zipBytes))

        // Verify
        assertTrue(File(configDir, "target.txt").exists())
        assertEquals("com.example.app", File(configDir, "target.txt").readText())

        assertTrue(File(configDir, "spoof_build_vars").exists())
        assertEquals("MODEL=Pixel 8", File(configDir, "spoof_build_vars").readText())
        assertArrayEquals(originalPrivacySeed, File(configDir, "privacy_seed").readBytes())
        assertEquals(TestKeyboxFixtures.validEcKeyboxXml, File(configDir, "keybox.xml").readText())
        assertEquals("ab".repeat(32), File(configDir, "module_hash").readText())
        assertEquals(policyState, File(configDir, PolicyState.STATE_FILE).readText())

        assertTrue(File(configDir, "keyboxes/kb1.xml").exists())
        assertEquals(TestKeyboxFixtures.validEcKeyboxXml, File(configDir, "keyboxes/kb1.xml").readText())
        assertArrayEquals(cboxBytes, File(configDir, "keyboxes/encrypted.cbox").readBytes())

        // Verify ignored files are NOT restored
        assertTrue("Ignored file should not be restored", !File(configDir, "ignored_file.txt").exists())
        assertTrue("Ignored keybox file should not be restored", !File(configDir, "keyboxes/invalid.txt").exists())
    }

    @Test
    fun restoreRemovesConfigurationAndKeyboxesMissingFromBackup() {
        File(configDir, "target.txt").writeText("com.example.app")
        val zipBytes = WebServer.createBackupZip(configDir)
        File(configDir, "global_mode").createNewFile()
        File(configDir, "keybox.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)
        val keyboxDir = File(configDir, "keyboxes").apply { mkdirs() }
        File(keyboxDir, "stale.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)

        WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zipBytes))

        assertFalse(File(configDir, "global_mode").exists())
        assertFalse(File(configDir, "keybox.xml").exists())
        assertFalse(File(keyboxDir, "stale.xml").exists())
        assertEquals("com.example.app", File(configDir, "target.txt").readText())
    }
}
