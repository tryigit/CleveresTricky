package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.RestoreFileOperations
import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
        ManagedKeyboxParserOracle.install()

        // Mock SecureFile to use standard IO
        SecureFile.impl =
            object : SecureFileOperations, RestoreFileOperations {
                override fun writeText(
                    file: File,
                    content: String,
                ) {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                }

                override fun writeBytes(
                    file: File,
                    content: ByteArray,
                ) {
                    file.parentFile?.mkdirs()
                    file.writeBytes(content)
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

                override fun begin(
                    configDir: File,
                    token: String,
                    maxSnapshotBytes: Long,
                ) = Unit

                override fun snapshot(
                    configDir: File,
                    token: String,
                    target: File,
                ) = Unit

                override fun replace(
                    configDir: File,
                    token: String,
                    target: File,
                    content: ByteArray,
                ) {
                    target.parentFile?.mkdirs()
                    target.writeBytes(content)
                }

                override fun delete(
                    configDir: File,
                    token: String,
                    target: File,
                ) {
                    target.delete()
                }

                override fun commit(
                    configDir: File,
                    token: String,
                ) = Unit

                override fun rollback(
                    configDir: File,
                    token: String,
                ) = Unit

                override fun abort(
                    configDir: File,
                    token: String,
                ) = Unit

                override fun exportRecovery(
                    configDir: File,
                    token: String,
                ): String = ""
            }
    }

    @After
    fun tearDown() {
        ManagedKeyboxParserOracle.reset()
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
        val cboxBytes = ByteArray(CboxWireLimits.MAX_BYTES)
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

    @Test
    fun testRkpProvenanceBackupAndRestore() {
        val provenanceContent = """{"rkp_keyboxes":["kb1.xml"]}"""
        File(configDir, RkpProvenanceStore.PROVENANCE_FILE_NAME).writeText(provenanceContent)
        File(configDir, "target.txt").writeText("com.example.app")

        val zipBytes = WebServer.createBackupZip(configDir)
        assertTrue(zipBytes.isNotEmpty())

        configDir.deleteRecursively()
        configDir.mkdirs()

        WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zipBytes))
        val restored = File(configDir, RkpProvenanceStore.PROVENANCE_FILE_NAME)
        assertTrue(restored.exists())
        assertEquals(provenanceContent, restored.readText())
    }

    @Test
    fun testRestoreDropsUnverifiableRkpProvenanceBindings() {
        RkpProvenanceStore.addTrustedAnchorForTesting(TestKeyboxFixtures.rkpRootCert)
        try {
            val kbDir = File(configDir, "keyboxes").apply { mkdirs() }
            File(kbDir, "evil.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)
            File(kbDir, "good.xml").writeText(TestKeyboxFixtures.validRkpKeyboxXml)
            File(configDir, RkpProvenanceStore.PROVENANCE_FILE_NAME).writeText(
                """{"rkp_keyboxes":["evil.xml","good.xml","ghost.xml"]}""",
            )
            File(configDir, "target.txt").writeText("com.example.app")

            val zipBytes = WebServer.createBackupZip(configDir)
            configDir.deleteRecursively()
            configDir.mkdirs()
            WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zipBytes))

            // Content restores untouched; only the trust binding is sanitized.
            assertTrue(File(configDir, "keyboxes/evil.xml").isFile)
            assertTrue(File(configDir, "keyboxes/good.xml").isFile)
            assertFalse(
                "poisoned binding must not survive restore",
                RkpProvenanceStore.isRkp("evil.xml", configDir),
            )
            assertTrue(
                "genuine binding must survive restore",
                RkpProvenanceStore.isRkp("good.xml", configDir),
            )
            assertTrue(
                "entries outside this backup describe untouched device state",
                RkpProvenanceStore.isRkp("ghost.xml", configDir),
            )
        } finally {
            RkpProvenanceStore.resetForTesting(configDir)
        }
    }

    @Test
    fun testRkpProvenanceRecordFailsClosedPastEntryLimit() {
        try {
            repeat(256) { index -> RkpProvenanceStore.recordRkp("kb$index.xml", configDir) }
            assertThrows(IllegalArgumentException::class.java) {
                RkpProvenanceStore.recordRkp("kb256.xml", configDir)
            }
            assertFalse(RkpProvenanceStore.isRkp("kb256.xml", configDir))
        } finally {
            RkpProvenanceStore.resetForTesting(configDir)
        }
    }

    @Test
    fun testDisabledKeyboxesBackupRestoreRoundTrip() {
        val disabledContent = "keyboxes:keybox.xml\nroot:legacy.xml\n"
        File(configDir, "disabled_keyboxes").writeText(disabledContent)
        File(configDir, "target.txt").writeText("com.example.app")

        val zipBytes = WebServer.createBackupZip(configDir)
        assertTrue(zipBytes.isNotEmpty())
        configDir.deleteRecursively()
        configDir.mkdirs()

        WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zipBytes))
        val restored = File(configDir, "disabled_keyboxes")
        assertTrue(restored.exists())
        assertEquals(disabledContent, restored.readText())
    }

    @Test
    fun testRestoreRemovesDisabledKeyboxesMissingFromBackup() {
        File(configDir, "target.txt").writeText("com.example.app")
        val zipBytes = WebServer.createBackupZip(configDir)

        // Created after the backup, so the archive never contains this list.
        File(configDir, "disabled_keyboxes").writeText("keyboxes:stale.xml\n")
        WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zipBytes))

        assertFalse("a disabled list absent from the backup must be removed by restore", File(configDir, "disabled_keyboxes").exists())
    }

    @Test
    fun testServersConfigBackupRestoreRoundTrip() {
        val plaintext = """[{"id":"srv1","name":"Primary","url":"https://example.com/kb.xml","priority":0,"enabled":true,"authType":"NONE","authData":{},"autoRefresh":false,"refreshIntervalHours":24}]"""
        File(configDir, "servers.json").writeText(plaintext)
        File(configDir, "boot_key").writeText("a".repeat(64))
        File(configDir, "boot_hash").writeText("b".repeat(64))
        File(configDir, "lang.json").writeText("""{"Refresh":"Yenile"}""")

        val zipBytes = WebServer.createBackupZip(configDir)
        assertTrue(zipBytes.isNotEmpty())
        configDir.deleteRecursively()
        configDir.mkdirs()

        WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zipBytes))

        assertEquals(plaintext, File(configDir, "servers.json").readText())
        assertEquals("a".repeat(64), File(configDir, "boot_key").readText())
        assertEquals("b".repeat(64), File(configDir, "boot_hash").readText())
        assertEquals("""{"Refresh":"Yenile"}""", File(configDir, "lang.json").readText())
    }

    @Test
    fun testServerConfigMissingFromBackupIsNotDeleted() {
        // Older backups predate servers.json, so an absent entry must preserve
        // the device-specific encrypted server configuration.
        File(configDir, "target.txt").writeText("com.example.app")
        val zipBytes = WebServer.createBackupZip(configDir)

        File(configDir, "servers.json").writeText("""[{"id":"srv1"}]""")
        WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zipBytes))

        assertTrue("server configuration absent from the backup must survive restore", File(configDir, "servers.json").exists())
    }

    @Test
    fun testServersConfigValidation() {
        assertFalse(WebServer.validateContent("servers.json", "not a blob"))
    }

    @Test
    fun testDisabledKeyboxesValidation() {
        assertTrue(WebServer.validateContent("disabled_keyboxes", "keyboxes:keybox.xml\nroot:legacy.xml\n"))
        assertTrue(WebServer.validateContent("disabled_keyboxes", ""))
        assertTrue(WebServer.validateContent("disabled_keyboxes", "\n \n"))

        // Unknown scopes are rejected: they can never resolve at runtime.
        assertFalse(WebServer.validateContent("disabled_keyboxes", "managed:keybox.xml"))
        assertFalse(WebServer.validateContent("disabled_keyboxes", "keyboxes2:keybox.xml"))

        // Malformed identifiers are rejected instead of being persisted.
        assertFalse(WebServer.validateContent("disabled_keyboxes", "keyboxes:"))
        assertFalse(WebServer.validateContent("disabled_keyboxes", ":keybox.xml"))
        assertFalse(WebServer.validateContent("disabled_keyboxes", "noseparator.xml"))
        assertFalse(WebServer.validateContent("disabled_keyboxes", "keyboxes:../escape.xml"))
        assertFalse(WebServer.validateContent("disabled_keyboxes", "keyboxes:sub/dir.xml"))
        assertFalse(WebServer.validateContent("disabled_keyboxes", "keyboxes:not_a_keybox.txt"))
        assertFalse(WebServer.validateContent("disabled_keyboxes", "keyboxes:keybox.xml\nbroken-line\n"))

        // Entry-count bound matches the runtime inventory limit.
        val tooMany = (0 until 257).joinToString("") { index -> "keyboxes:kb$index.xml\n" }
        assertFalse(WebServer.validateContent("disabled_keyboxes", tooMany))

        // Size bound.
        val large = "keyboxes:" + "k".repeat(65 * 1024) + ".xml\n"
        assertFalse(WebServer.validateContent("disabled_keyboxes", large))
    }

    @Test
    fun testRkpProvenanceValidation() {
        assertTrue(WebServer.validateContent(RkpProvenanceStore.PROVENANCE_FILE_NAME, """{"rkp_keyboxes":["keybox.xml","test.cbox"]}"""))
        assertTrue(WebServer.validateContent(RkpProvenanceStore.PROVENANCE_FILE_NAME, """{"rkp_keyboxes":[]}"""))

        // Malformed / invalid JSON
        assertFalse(WebServer.validateContent(RkpProvenanceStore.PROVENANCE_FILE_NAME, "not json"))
        assertFalse(WebServer.validateContent(RkpProvenanceStore.PROVENANCE_FILE_NAME, ""))

        // Missing or extra root keys
        assertFalse(WebServer.validateContent(RkpProvenanceStore.PROVENANCE_FILE_NAME, """{"other":[]}"""))
        assertFalse(WebServer.validateContent(RkpProvenanceStore.PROVENANCE_FILE_NAME, """{"rkp_keyboxes":[],"extra":1}"""))

        // Invalid elements
        assertFalse(WebServer.validateContent(RkpProvenanceStore.PROVENANCE_FILE_NAME, """{"rkp_keyboxes":[""]}"""))
        assertFalse(WebServer.validateContent(RkpProvenanceStore.PROVENANCE_FILE_NAME, """{"rkp_keyboxes":["../invalid.xml"]}"""))
        assertFalse(WebServer.validateContent(RkpProvenanceStore.PROVENANCE_FILE_NAME, """{"rkp_keyboxes":["not_xml_or_cbox.txt"]}"""))
        assertFalse(WebServer.validateContent(RkpProvenanceStore.PROVENANCE_FILE_NAME, """{"rkp_keyboxes":["kb1.xml","kb1.xml"]}"""))

        // Max entries bound (>256)
        val tooMany = (0..257).joinToString(prefix = """{"rkp_keyboxes":[""", postfix = "]}", separator = ",") { """"kb$it.xml"""" }
        assertFalse(WebServer.validateContent(RkpProvenanceStore.PROVENANCE_FILE_NAME, tooMany))

        // Max size bound (>64KB)
        val large = """{"rkp_keyboxes":[""" + " ".repeat(65 * 1024) + """]}"""
        assertFalse(WebServer.validateContent(RkpProvenanceStore.PROVENANCE_FILE_NAME, large))
    }
}
