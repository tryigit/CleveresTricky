package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class WebServerUploadTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: WebServer
    private lateinit var configDir: File
    private lateinit var originalSecureFileImpl: SecureFileOperations

    @Before
    fun setUp() {
        Logger.setImpl(
            object : Logger.LogImpl {
                override fun d(
                    tag: String,
                    msg: String,
                ) {}

                override fun e(
                    tag: String,
                    msg: String,
                ) {}

                override fun e(
                    tag: String,
                    msg: String,
                    t: Throwable?,
                ) {
                    // no-op
                }

                override fun i(
                    tag: String,
                    msg: String,
                ) {}
            },
        )
        configDir = tempFolder.newFolder("config")
        KeyboxLoader.resetForTesting()
        cleveres.tricky.cleverestech.keystore.CertHack.setKeyboxes(emptyList())
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

                override fun writeBytes(
                    file: File,
                    content: ByteArray,
                ) {
                    file.parentFile?.mkdirs()
                    file.writeBytes(content)
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
    }

    @After
    fun tearDown() {
        KeyboxLoader.resetForTesting()
        BackendRecovery.recoveryOverride = null
        cleveres.tricky.cleverestech.keystore.CertHack.setKeyboxes(emptyList())
        ManagedKeyboxParserOracle.reset()
        SecureFile.impl = originalSecureFileImpl
        server.stop()
    }

    private fun uploadKeyboxResponse(
        filename: String,
        content: String,
    ): Pair<Int, String> {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/api/upload_keybox?token=$token")

        val encodedFilename = java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8.name())
        val encodedContent = java.net.URLEncoder.encode(content, StandardCharsets.UTF_8.name())
        val postData = "filename=$encodedFilename&content=$encodedContent"
        val postDataBytes = postData.toByteArray(StandardCharsets.UTF_8)

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(postDataBytes) }
        val responseCode = conn.responseCode
        val stream = if (responseCode >= 400) conn.errorStream else conn.inputStream
        val responseBody = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return responseCode to responseBody
    }

    private fun uploadKeybox(
        filename: String,
        content: String,
    ): Int = uploadKeyboxResponse(filename, content).first

    private fun uploadMultipartKeybox(
        filename: String,
        content: ByteArray,
    ): Int {
        val boundary = "CleveresTrickyUploadBoundary"
        val output = ByteArrayOutputStream()

        fun write(value: String) = output.write(value.toByteArray(StandardCharsets.UTF_8))

        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"filename\"\r\n\r\n")
        write("$filename\r\n")
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n")
        write("Content-Type: application/octet-stream\r\n\r\n")
        output.write(content)
        write("\r\n--$boundary--\r\n")
        val body = output.toByteArray()

        val url = URL("http://localhost:${server.listeningPort}/api/upload_keybox?token=${server.token}")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(body.size)
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.outputStream.use { it.write(body) }
        return conn.responseCode.also { conn.disconnect() }
    }

    @Test
    fun testUploadKeyboxValidFilename() {
        val validXml = TestKeyboxFixtures.validEcKeyboxXml

        val responseCode = uploadKeybox("valid_keybox.xml", validXml)
        assertEquals(200, responseCode)

        val f = File(configDir, "keyboxes/valid_keybox.xml")
        assert(f.exists())
    }

    @Test
    fun testValidUploadDoesNotReportSuccessWhenBackendActivationFails() {
        KeyboxLoader.activeSetOverride = { true }
        assertEquals(200, uploadKeybox("active.xml", TestKeyboxFixtures.validEcKeyboxXml))
        val activeKeyboxCount = cleveres.tricky.cleverestech.keystore.CertHack.getPublishedKeyboxCountForTesting()

        KeyboxLoader.activeSetOverride = { false }
        BackendRecovery.recoveryOverride = { false }
        val responseCode = uploadKeybox("activation_failure.xml", TestKeyboxFixtures.validEcKeyboxXml)

        assertEquals(HttpURLConnection.HTTP_UNAVAILABLE, responseCode)
        assertEquals(
        activeKeyboxCount,
        cleveres.tricky.cleverestech.keystore.CertHack.getPublishedKeyboxCountForTesting(),
    )
        assert(File(configDir, "keyboxes/activation_failure.xml").exists())
    }

    @Test
    fun testMultipartXmlUploadIsValidatedAndStored() {
        val content = TestKeyboxFixtures.validEcKeyboxXml.toByteArray(StandardCharsets.UTF_8)

        assertEquals(200, uploadMultipartKeybox("multipart.xml", content))
        assertArrayEquals(content, File(configDir, "keyboxes/multipart.xml").readBytes())
    }

    @Test
    fun `multipart standalone Keybox is stored in AndroidAttestation wrapper`() {
        val standalone = TestKeyboxFixtures.validEcKeyboxXml
            .substringAfter("<Keybox")
            .substringBeforeLast("</Keybox>")
            .let { "<Keybox$it</Keybox>" }

        assertEquals(200, uploadMultipartKeybox("standalone.xml", standalone.toByteArray(StandardCharsets.UTF_8)))
        val stored = File(configDir, "keyboxes/standalone.xml").readText()
        assertTrue(stored.contains("<AndroidAttestation>"))
        assertTrue(stored.contains("<NumberOfKeyboxes>1</NumberOfKeyboxes>"))
        assertTrue(stored.contains("<Keybox"))
    }

    @Test
    fun `multipart upload accepts full CBOX wire bound`() {
        val content = ByteArray(CboxWireLimits.MAX_BYTES)
        ByteBuffer.wrap(content)
            .put("CBOX".toByteArray(StandardCharsets.US_ASCII))
            .putInt(2)

        assertEquals(200, uploadMultipartKeybox("full-size.cbox", content))
        assertEquals(content.size.toLong(), File(configDir, "keyboxes/full-size.cbox").length())
    }

    @Test
    fun testUploadKeyboxInvalidContent() {
        val responseCode = uploadKeybox("invalid_content.xml", "<xml>bad</xml>")
        assertEquals(400, responseCode)
    }

    @Test
    fun `android clone suffix is normalized before XML storage`() {
        val (responseCode, responseBody) = uploadKeyboxResponse("keybox (1).xml", TestKeyboxFixtures.validEcKeyboxXml)
        assertEquals(200, responseCode)
        assertEquals("keybox_1.xml", JSONObject(responseBody).getString("filename"))
        assertTrue(File(configDir, "keyboxes/keybox_1.xml").isFile)
        assertFalse(File(configDir, "keyboxes/keybox (1).xml").exists())
    }

    @Test
    fun `repeated android clone suffixes are normalized without retaining spaces`() {
        val responseCode = uploadKeybox("keybox (1) (2).xml", TestKeyboxFixtures.validEcKeyboxXml)
        assertEquals(200, responseCode)
        assertTrue(File(configDir, "keyboxes/keybox_1_2.xml").isFile)
    }

    @Test
    fun `android clone suffix is normalized before CBOX storage`() {
        val content = ByteArray(CboxWireLimits.MIN_BYTES)
        ByteBuffer.wrap(content)
            .put("CBOX".toByteArray(StandardCharsets.US_ASCII))
            .putInt(2)

        assertEquals(200, uploadMultipartKeybox("encrypted (1).cbox", content))
        assertTrue(File(configDir, "keyboxes/encrypted_1.cbox").isFile)
        assertFalse(File(configDir, "keyboxes/encrypted (1).cbox").exists())
    }

    @Test
    fun testUploadKeyboxInvalidFilenameSpace() {
        val responseCode = uploadKeybox("keybox space.xml", "<xml>bad</xml>")
        assertEquals(400, responseCode)
    }

    @Test
    fun testUploadKeyboxInvalidFilenameSpecialChar() {
        val responseCode = uploadKeybox("keybox!.xml", "<xml>bad</xml>")
        assertEquals(400, responseCode)
    }

    @Test
    fun testUploadKeyboxInvalidFilenameTraversal() {
        // Even if we URL encode it, the server sees the decoded param.
        // But here we send raw string in post body (x-www-form-urlencoded).
        // ".." is dots. "/" is slash.
        // If we send "filename=../foo.xml", regex matches "." but not "/".

        val responseCode = uploadKeybox("../foo.xml", "<xml>bad</xml>")
        assertEquals(400, responseCode)
    }

    private fun uploadKeyboxPost(
        params: Map<String, String>,
    ): Pair<Int, String> {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/api/upload_keybox?token=$token")

        val postData = params.entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, StandardCharsets.UTF_8.name()) + "=" +
                java.net.URLEncoder.encode(v, StandardCharsets.UTF_8.name())
        }
        val postDataBytes = postData.toByteArray(StandardCharsets.UTF_8)

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(postDataBytes) }
        val responseCode = conn.responseCode
        val stream = if (responseCode >= 400) conn.errorStream else conn.inputStream
        val responseBody = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return responseCode to responseBody
    }

    @Test
    fun testUploadRkpKeyboxWithoutWrapperOrCounts() {
        val rawRkpXml = """
            <Keybox>
              <Key algorithm="ecdsa">
                <PrivateKey>
${TestKeyboxFixtures.ecPrivateKey.prependIndent("                  ")}
                </PrivateKey>
                <CertificateChain>
                  <Certificate>
${TestKeyboxFixtures.certificate.prependIndent("                    ")}
                  </Certificate>
                </CertificateChain>
              </Key>
            </Keybox>
        """.trimIndent()

        val (responseCode, _) = uploadKeyboxResponse("rkp.xml", rawRkpXml)
        assertEquals(200, responseCode)
        val file = File(configDir, "keyboxes/rkp.xml")
        assertTrue(file.isFile)
        val saved = file.readText()
        assertTrue(saved.contains("<AndroidAttestation>"))
        assertTrue(saved.contains("<NumberOfKeyboxes>1</NumberOfKeyboxes>"))
    }

    @Test
    fun testUploadRkpKeyboxWithoutFilenameDefaultsToRkpXml() {
        val rawRkpXml = """
            <Keybox>
              <Key algorithm="ecdsa">
                <PrivateKey>
${TestKeyboxFixtures.ecPrivateKey.prependIndent("                  ")}
                </PrivateKey>
                <CertificateChain>
                  <Certificate>
${TestKeyboxFixtures.certificate.prependIndent("                    ")}
                  </Certificate>
                </CertificateChain>
              </Key>
            </Keybox>
        """.trimIndent()

        val (responseCode, _) = uploadKeyboxPost(mapOf("content" to "<!-- rkp -->\n$rawRkpXml"))
        assertEquals(200, responseCode)
        val file = File(configDir, "keyboxes/rkp.xml")
        assertTrue(file.isFile)
    }

    @Test
    fun `RKP filename cannot bypass unavailable revocation checks`() {
        val originalRoot = Config.getConfigRoot()
        try {
            Config.setRootForTesting(configDir)
            ManagedKeyboxParserOracle.install()
            KeyboxLoader.activeSetOverride = { true }
            File(configDir, "auto_keybox_check").createNewFile()
            server.stop()
            server = WebServer(0, configDir, crlFetcher = { null })
            server.start()

            val rkpXml = TestKeyboxFixtures.validEcKeyboxXml

            val (rkpCode, _) = uploadKeyboxResponse("rkp.xml", rkpXml)
            assertEquals(HttpURLConnection.HTTP_UNAVAILABLE, rkpCode)
            assertFalse(File(configDir, "keyboxes/rkp.xml").exists())
        } finally {
            Config.setRootForTesting(originalRoot)
            ManagedKeyboxParserOracle.install()
        }
    }
}
