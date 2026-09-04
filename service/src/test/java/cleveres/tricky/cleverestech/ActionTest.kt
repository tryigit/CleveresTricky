package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

class ActionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: WebServer
    private lateinit var configDir: File
    private lateinit var originalSecureFileImpl: SecureFileOperations
    private lateinit var originalConfigRoot: File
    private val maxPollIntervalMs = 200L

    private val ecKey = TestKeyboxFixtures.ecPrivateKey
    private val testCertificate = TestKeyboxFixtures.certificate

    private val validXml =
        "<?xml version=\"1.0\"?>\n" +
            "<AndroidAttestation>\n" +
            "<NumberOfKeyboxes>1</NumberOfKeyboxes>\n" +
            "<Keybox>\n" +
            "<Key algorithm=\"ecdsa\">\n" +
            "<PrivateKey>\n" + ecKey + "\n</PrivateKey>\n" +
            "<CertificateChain>\n" +
            "<NumberOfCertificates>1</NumberOfCertificates>\n" +
            "<Certificate>\n" + testCertificate + "\n</Certificate>\n" +
            "</CertificateChain>\n" +
            "</Key>\n" +
            "</Keybox>\n" +
            "</AndroidAttestation>"

    @Before
    fun setUp() {
        Logger.setImpl(
            object : Logger.LogImpl {
                override fun d(
                    tag: String,
                    msg: String,
                ) {
                    // no-op
                }

                override fun e(
                    tag: String,
                    msg: String,
                ) {
                    // no-op
                }

                override fun e(
                    tag: String,
                    msg: String,
                    t: Throwable?,
                ) {
                    // no-op
                    // no-op
                }

                override fun i(
                    tag: String,
                    msg: String,
                ) {
                    // no-op
                }
            },
        )
        configDir = tempFolder.newFolder("config")
        originalConfigRoot = Config.getConfigRoot()
        Config.setRootForTesting(configDir)
        ManagedKeyboxParserOracle.install()

        originalSecureFileImpl = SecureFile.impl
        SecureFile.impl =
            object : SecureFileOperations {
                override fun writeText(
                    file: File,
                    content: String,
                ) {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
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
                    if (!file.exists()) file.createNewFile()
                }
            }

        server = WebServer(0, configDir, crlFetcher = { emptySet() })
        server.start()
        ManagedOpaqueKeyOracle.readFromXml(null)
    }

    @After
    fun tearDown() {
        server.stop()
        CronAutoIdentity.stop()
        PolicyState.resetForTesting()
        ManagedOpaqueKeyOracle.readFromXml(null)
        Config.reset()
        ManagedKeyboxParserOracle.reset()
        Config.setRootForTesting(originalConfigRoot)
        SecureFile.impl = originalSecureFileImpl
    }

    @Test
    fun testWebServerStartsAndServesConfig() {
        val port = server.listeningPort
        assertTrue(port > 0)
        val token = server.token

        val url = URL("http://localhost:$port/api/config?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        assertEquals(200, conn.responseCode)

        val content = conn.inputStream.bufferedReader().readText()
        // no-op

        val json = JSONObject(content)
        assertEquals(0, json.getInt("keybox_count"))
    }

    @Test
    fun testCertHackStatus() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/api/config?token=$token")

        ManagedOpaqueKeyOracle.readFromXml(StringReader(validXml))

        var conn = url.openConnection() as HttpURLConnection
        var content = conn.inputStream.bufferedReader().readText()
        var json = JSONObject(content)
        assertEquals(1, json.getInt("keybox_count"))

        val invalidXml = "<AndroidAttestation><NumberOfKeyboxes>1</NumberOfKeyboxes>INVALID</AndroidAttestation>"
        ManagedOpaqueKeyOracle.readFromXml(StringReader(invalidXml))

        conn = url.openConnection() as HttpURLConnection
        content = conn.inputStream.bufferedReader().readText()
        json = JSONObject(content)
        assertEquals(0, json.getInt("keybox_count"))
    }

    @Test
    fun testSaveFile() {
        val port = server.listeningPort
        val token = server.token
        val saveUrl = URL("http://localhost:$port/api/save?token=$token&filename=target.txt&content=TEST_CONTENT")

        val conn = saveUrl.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.outputStream.close()

        assertEquals(200, conn.responseCode)

        val savedFile = File(configDir, "target.txt")
        assertTrue("File should exist", savedFile.exists())
        assertEquals("File content mismatch", "TEST_CONTENT", savedFile.readText())
    }

    @Test
    fun testVerifyKeyboxesIncludesLegacyAndStoredFiles() {
        File(configDir, "keybox.xml").writeText(validXml)
        val keyboxesDir = File(configDir, "keyboxes").apply { mkdirs() }
        File(keyboxesDir, "stored.xml").writeText(validXml)

        val results = KeyboxVerifier.verify(configDir) { emptySet() }
        assertEquals(2, results.size)

        val resultsByFilename = results.associateBy { it.filename }

        assertEquals(KeyboxVerifier.Status.VALID, resultsByFilename.getValue("keybox.xml").status)
        assertEquals("Active keybox", resultsByFilename.getValue("keybox.xml").details)
        assertEquals(KeyboxVerifier.Status.VALID, resultsByFilename.getValue("stored.xml").status)
        assertEquals("Active keybox", resultsByFilename.getValue("stored.xml").details)
    }

    @Test
    fun testVerifyKeyboxesReportsRevokedStatus() {
        File(configDir, "keybox.xml").writeText(validXml)
        val revokedSerial = extractCertificateSerial(validXml)

        val result = KeyboxVerifier.verify(configDir) { setOf(revokedSerial) }.single()
        assertEquals("keybox.xml", result.filename)
        assertEquals(KeyboxVerifier.Status.REVOKED, result.status)
        assertTrue(result.details.contains(revokedSerial))
    }

    @Test
    fun `keybox upload reports Rust backend outage without saving input`() {
        KeyboxLoader.parserOverride = { _, _ -> throw RustBackendUnavailableException() }
        val (status, body) =
            postForm(
                "/api/upload_keybox",
                mapOf("filename" to "outage.xml", "content" to validXml),
            )

        assertEquals(503, status)
        assertTrue(body.contains("backend", ignoreCase = true))
        assertFalse(File(configDir, "keyboxes/outage.xml").exists())
    }

    @Test
    fun testUserCanUploadSwitchAndRemoveKeyboxesThroughWebUiFlow() {
        assertEquals(0, getConfig().getInt("keybox_count"))
        assertEquals(0, getKeyboxes().length())

        assertEquals(200, postForm("/api/upload_keybox", mapOf("filename" to "first.xml", "content" to validXml)).first)
        assertEquals(200, postForm("/api/upload_keybox", mapOf("filename" to "second.xml", "content" to validXml)).first)

        waitUntil("uploaded keyboxes to be listed") {
            val listed = getKeyboxes()
            listed.length() == 2 &&
                listed.getString(0) == "first.xml" &&
                listed.getString(1) == "second.xml"
        }
        waitUntil("global keybox count to reflect uploaded keyboxes") {
            getConfig().getInt("keybox_count") == 2
        }

        val firstRule =
            JSONArray().put(
                JSONObject()
                    .put("package", "com.example.target")
                    .put("template", "")
                    .put("keybox", "first.xml"),
            )
        assertEquals(200, postForm("/api/app_config_structured", mapOf("data" to firstRule.toString())).first)

        var savedRules = getStructuredAppConfig()
        assertEquals(1, savedRules.length())
        assertEquals("first.xml", savedRules.getJSONObject(0).getString("keybox"))

        val secondRule =
            JSONArray().put(
                JSONObject()
                    .put("package", "com.example.target")
                    .put("template", "")
                    .put("keybox", "second.xml"),
            )
        assertEquals(200, postForm("/api/app_config_structured", mapOf("data" to secondRule.toString())).first)

        savedRules = getStructuredAppConfig()
        assertEquals(1, savedRules.length())
        assertEquals("second.xml", savedRules.getJSONObject(0).getString("keybox"))
        val rawAppConfig = File(configDir, "app_config").readText()
        assertTrue(rawAppConfig.contains("com.example.target"))
        assertTrue(rawAppConfig.contains("second.xml"))

        assertEquals(200, postForm("/api/delete_keybox", mapOf("filename" to "first.xml")).first)
        waitUntil("deleted keybox to disappear from the WebUI list") {
            val listed = getKeyboxes()
            listed.length() == 1 && listed.getString(0) == "second.xml"
        }
        waitUntil("global keybox count to reflect deletion") {
            getConfig().getInt("keybox_count") == 1
        }
    }

    @Test
    fun testUserCanToggleFeaturesOffAndOnAndConfigReflectsState() {
        val settings =
            listOf(
                "spoof_enabled",
                "spoof_build_identity",
                "global_mode",
                "auto_keybox_check",
                "random_on_boot",
                "spoof_region_cn",
                "telephony",
                "drm_passthrough",
            )
        val removedSettings =
            listOf(
                "tee_broken_mode",
                "hide_sensitive_props",
                "rkp_passthrough",
                "rkp_bypass",
                "spoof_props",
            )

        settings.forEach { setting ->
            assertFalse(getConfig().getBoolean(setting))
            assertEquals(200, postForm("/api/toggle", mapOf("setting" to setting, "value" to "true")).first)
            assertTrue(getConfig().getBoolean(setting))
            assertTrue(File(configDir, setting).isFile)
        }

        var config = getConfig()
        removedSettings.forEach { setting ->
            assertEquals(400, postForm("/api/toggle", mapOf("setting" to setting, "value" to "true")).first)
            assertFalse(config.has(setting))
            assertFalse(File(configDir, setting).exists())
        }

        settings.forEach { setting ->
            assertEquals(200, postForm("/api/toggle", mapOf("setting" to setting, "value" to "false")).first)
            assertFalse(getConfig().getBoolean(setting))
            assertFalse(File(configDir, setting).exists())
        }

        config = getConfig()
        settings.forEach { setting -> assertFalse(config.getBoolean(setting)) }
        removedSettings.forEach { setting -> assertFalse(config.has(setting)) }
    }

    @Test
    fun testToggleRejectsSymbolicLinkMarker() {
        val outside = tempFolder.newFile("outside-marker").apply { writeText("unchanged") }
        Files.createSymbolicLink(File(configDir, "telephony").toPath(), outside.toPath())

        assertEquals(400, postForm("/api/toggle", mapOf("setting" to "telephony", "value" to "true")).first)
        assertFalse(getConfig().getBoolean("telephony"))
        assertEquals("unchanged", outside.readText())
    }

    @Test
    fun `bridge runtime markers use the service owner and synchronously refresh cron`() {
        PolicyState.installStateForTesting(
            """
            {"version":2,"features":{"buildIdentity":true,"attestationIdentity":false,"telephonyIdentity":false,"regionIdentity":false,"identityRefresh":false,"securityPatch":false},"securityPatch":{"automaticThresholdMonths":6,"system":{"mode":"automatic"},"vendor":{"mode":"automatic"},"boot":{"mode":"automatic"}},"profiles":[],"activeProfile":null}
            """.trimIndent(),
        )
        CronAutoIdentity.configureForTesting(configDir)

        assertFalse(getConfig().getBoolean("debug_logging"))
        assertFalse(getConfig().getBoolean(CronAutoIdentity.TOGGLE_FILE))

        assertEquals(200, postForm("/api/toggle", mapOf("setting" to "debug_logging", "value" to "true")).first)
        assertTrue(getConfig().getBoolean("debug_logging"))
        assertTrue(File(configDir, "debug_logging").isFile)

        assertEquals(
            200,
            postForm("/api/toggle", mapOf("setting" to CronAutoIdentity.TOGGLE_FILE, "value" to "true")).first,
        )
        assertTrue(getConfig().getBoolean(CronAutoIdentity.TOGGLE_FILE))
        assertTrue("Cron must be scheduled before the API reports success", CronAutoIdentity.isRunningForTesting())

        assertEquals(
            200,
            postForm("/api/toggle", mapOf("setting" to CronAutoIdentity.TOGGLE_FILE, "value" to "false")).first,
        )
        assertFalse(getConfig().getBoolean(CronAutoIdentity.TOGGLE_FILE))
        assertFalse("Cron must be stopped before the API reports success", CronAutoIdentity.isRunningForTesting())

        val outside = tempFolder.newFile("outside-debug-marker").apply { writeText("unchanged") }
        Files.delete(File(configDir, "debug_logging").toPath())
        Files.createSymbolicLink(File(configDir, "debug_logging").toPath(), outside.toPath())
        assertEquals(400, postForm("/api/toggle", mapOf("setting" to "debug_logging", "value" to "false")).first)
        assertEquals("unchanged", outside.readText())
    }

    @Test
    fun testKeyboxListIncludesXmlAndCboxOnly() {
        val keyboxDir = File(configDir, "keyboxes").apply { mkdirs() }
        File(keyboxDir, "first.xml").writeText("xml")
        File(keyboxDir, "encrypted.cbox").writeText("cbox")
        File(keyboxDir, "encrypted.cbox.cache").writeText("cache")
        File(keyboxDir, "notes.txt").writeText("notes")
        File(keyboxDir, "directory.xml").mkdir()

        val listed = getKeyboxes()
        assertEquals(2, listed.length())
        assertEquals("encrypted.cbox", listed.getString(0))
        assertEquals("first.xml", listed.getString(1))
    }

    @Test
    fun testProfilesDoNotCreateRemovedCompatibilityFlags() {
        val profiles = listOf("maximum", "daily", "minimal", "default")
        profiles.forEach { profile ->
            assertEquals(200, postForm("/api/apply_profile", mapOf("profile" to profile)).first)

            waitUntil("profile $profile to apply without removed flags") {
                !File(configDir, "apply_profile").exists() &&
                    !File(configDir, "rkp_passthrough").exists() &&
                    !File(configDir, "rkp_bypass").exists() &&
                    !File(configDir, "spoof_props").exists()
            }

            val config = getConfig()
            assertFalse(config.has("rkp_passthrough"))
            assertFalse(config.has("rkp_bypass"))
            assertFalse(config.has("spoof_props"))
        }
    }

    private fun post(path: String): Pair<Int, String> {
        val url = URL("http://localhost:${server.listeningPort}$path?token=${server.token}")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.outputStream.close()
        val responseCode = conn.responseCode
        val stream = if (responseCode >= 400) conn.errorStream else conn.inputStream
        val body = stream?.bufferedReader()?.readText().orEmpty()
        return responseCode to body
    }

    private fun postForm(
        path: String,
        params: Map<String, String>,
    ): Pair<Int, String> {
        val url = URL("http://localhost:${server.listeningPort}$path?token=${server.token}")
        val conn = url.openConnection() as HttpURLConnection
        val body =
            params.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
            }
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val responseCode = conn.responseCode
        val stream = if (responseCode >= 400) conn.errorStream else conn.inputStream
        val responseBody = stream?.bufferedReader()?.readText().orEmpty()
        return responseCode to responseBody
    }

    private fun getConfig(): JSONObject = JSONObject(getText("/api/config"))

    private fun getKeyboxes(): JSONArray = JSONArray(getText("/api/keyboxes"))

    private fun getStructuredAppConfig(): JSONArray = JSONArray(getText("/api/app_config_structured"))

    private fun getText(path: String): String {
        val separator = if (path.contains("?")) "&" else "?"
        val url = URL("http://localhost:${server.listeningPort}$path${separator}token=${server.token}")
        val conn = url.openConnection() as HttpURLConnection
        return conn.inputStream.bufferedReader().readText()
    }

    private fun extractCertificateSerial(xml: String): String {
        return (
            ManagedOpaqueKeyOracle.parse(StringReader(xml), "serial.xml")
                .first()
                .certificates()
                .first() as X509Certificate
        )
            .serialNumber
            .toString(16)
            .lowercase()
    }

    private fun waitUntil(
        conditionDescription: String,
        timeoutMs: Long = 2_000L,
        pollIntervalMs: Long = 50L,
        predicate: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var currentSleepMs = pollIntervalMs
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                if (predicate()) return
                lastFailure = null
            } catch (t: Throwable) {
                lastFailure = t
            }
            Thread.sleep(currentSleepMs)
            currentSleepMs = minOf(currentSleepMs * 2, maxPollIntervalMs)
        }
        val error = AssertionError("Timed out waiting for $conditionDescription")
        lastFailure?.let(error::initCause)
        throw error
    }
}
