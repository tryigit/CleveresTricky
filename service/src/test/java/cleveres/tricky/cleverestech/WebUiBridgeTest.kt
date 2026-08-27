package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch

class WebUiBridgeTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var configDir: File
    private lateinit var bridge: WebUiBridge

    @Before
    fun setUp() {
        SecureFile.impl = MockSecureFileOperations()
        configDir = tempFolder.newFolder("config")
        File(configDir, "webui_bridge/staging").mkdirs()
    }

    @After
    fun tearDown() {
        if (::bridge.isInitialized) bridge.stop()
        SecureFile.impl = SecureFile.DefaultSecureFileOperations()
    }

    @Test
    fun `native request reaches api without network authentication`() {
        bridge = WebUiBridge(WebServer(0, configDir), configDir)
        val response = submit("/api/config")

        assertEquals(200, response.getInt("status"))
        val body = decodeBody(response)
        assertTrue(JSONObject(body).has("files"))
        assertFalse(File(configDir, "webui_bridge/requests").exists())
        assertFalse(File(configDir, "webui_bridge/responses").exists())
    }

    @Test
    fun `native request waits for bounded startup readiness and recovers`() {
        val startupReady = CountDownLatch(1)
        bridge = WebUiBridge(WebServer(0, configDir), configDir, startupReady, 100)
        val startedAt = System.nanoTime()

        val unavailable = submit("/api/config")

        assertEquals(503, unavailable.getInt("status"))
        assertTrue(System.nanoTime() - startedAt < 1_000_000_000L)

        startupReady.countDown()
        val ready = submit("/api/config")
        assertEquals(200, ready.getInt("status"))
    }

    @Test
    fun `tamper lockdown and unknown fields fail closed`() {
        bridge = WebUiBridge(WebServer(0, configDir, true), configDir)
        val blocked = submit("/api/config")
        assertEquals(403, blocked.getInt("status"))

        val invalid =
            JSONObject()
                .put("version", 1)
                .put("method", "GET")
                .put("path", "/api/config")
                .put("parameters", JSONObject())
                .put("unexpected", true)
        val rejected = submit(invalid)
        assertEquals(400, rejected.getInt("status"))
    }

    @Test
    fun `malformed and oversized request envelopes fail closed`() {
        bridge = WebUiBridge(WebServer(0, configDir), configDir)
        val malformed = bridge.processRequestBytes("not-json".toByteArray())
        assertEquals(400, JSONObject(String(malformed)).getInt("status"))

        val invalidUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        try {
            val rejected = bridge.processRequestBytes(invalidUtf8)
            assertEquals(400, JSONObject(String(rejected, Charsets.UTF_8)).getInt("status"))
        } finally {
            invalidUtf8.fill(0)
        }

        val oversized = ByteArray(1024 * 1024 + 1)
        try {
            bridge.processRequestBytes(oversized)
            throw AssertionError("oversized request was accepted")
        } catch (_: IllegalArgumentException) {
            // The UDS frame parser rejects this size before JSON parsing in production.
        } finally {
            oversized.fill(0)
        }
    }

    @Test
    fun `large responses spill to bounded staging`() {
        bridge = WebUiBridge(WebServer(0, configDir), configDir)
        val body = ByteArray(300 * 1024) { index -> (index and 0xff).toByte() }
        val response =
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/octet-stream",
                ByteArrayInputStream(body),
                body.size.toLong(),
            )
        val envelope = JSONObject(String(bridge.encodeResponse(response), Charsets.UTF_8))
        val downloadId = envelope.getString("downloadId")

        assertTrue(Regex("[0-9a-f]{32}").matches(downloadId))
        assertEquals(body.size, envelope.getInt("size"))
        assertFalse(envelope.has("body"))
        assertArrayEquals(body, File(configDir, "webui_bridge/staging/$downloadId.download").readBytes())
    }

    @Test
    fun `rejected upload request removes its staging file`() {
        bridge = WebUiBridge(WebServer(0, configDir), configDir)
        val uploadId = "11111111111111111111111111111111"
        val upload = File(configDir, "webui_bridge/staging/$uploadId.upload")
        upload.writeText("payload")
        val request =
            JSONObject()
                .put("version", 1)
                .put("method", "POST")
                .put("path", "/api/upload_keybox")
                .put("parameters", JSONObject())
                .put("uploadId", uploadId)
                .put("uploadField", 7)

        val response = submit(request)

        assertEquals(400, response.getInt("status"))
        assertFalse(upload.exists())
    }

    private fun submit(path: String): JSONObject =
        submit(
            JSONObject()
                .put("version", 1)
                .put("method", "GET")
                .put("path", path)
                .put("parameters", JSONObject()),
        )

    private fun submit(request: JSONObject): JSONObject {
        val requestBytes = request.toString().toByteArray(Charsets.UTF_8)
        return try {
            JSONObject(String(bridge.processRequestBytes(requestBytes), Charsets.UTF_8))
        } finally {
            requestBytes.fill(0)
        }
    }

    private fun decodeBody(response: JSONObject): String {
        val encoded = response.getString("body")
        val padding = "=".repeat((4 - encoded.length % 4) % 4)
        return String(Base64.getUrlDecoder().decode(encoded + padding), Charsets.UTF_8)
    }
}
