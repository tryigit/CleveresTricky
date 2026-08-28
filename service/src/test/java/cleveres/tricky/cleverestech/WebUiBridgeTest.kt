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
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

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
    fun `staging lock refuses symbolic link`() {
        bridge = WebUiBridge(WebServer(0, configDir), configDir)
        val staging = File(configDir, "webui_bridge/staging")
        val outside = tempFolder.newFile("outside-lock")
        Files.createSymbolicLink(File(staging, ".staging.lock").toPath(), outside.toPath())
        val body = ByteArray(300 * 1024) { 0x44 }
        val response =
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/octet-stream",
                ByteArrayInputStream(body),
                body.size.toLong(),
            )

        try {
            bridge.encodeResponse(response)
            throw AssertionError("symbolic-link staging lock was accepted")
        } catch (_: Exception) {
            // A staging lock must never follow a symbolic link outside the staging directory.
        }
        assertEquals(0L, outside.length())
        body.fill(0)
    }

    @Test
    fun `large response rejects when staging file quota is exhausted`() {
        bridge = WebUiBridge(WebServer(0, configDir), configDir)
        val staging = File(configDir, "webui_bridge/staging")
        repeat(32) { index ->
            val id = index.toString(16).padStart(32, '0')
            File(staging, "$id.upload").writeBytes(byteArrayOf(index.toByte()))
        }
        val body = ByteArray(300 * 1024) { 0x41 }
        val response =
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/octet-stream",
                ByteArrayInputStream(body),
                body.size.toLong(),
            )

        try {
            bridge.encodeResponse(response)
            throw AssertionError("staging file quota was not enforced")
        } catch (_: IllegalArgumentException) {
            // A bounded staging directory must fail closed before allocating another artifact.
        }
        assertEquals(32, staging.listFiles()?.count { it.isFile && it.name.endsWith(".upload") })

        body.fill(0)
    }

    @Test
    fun `concurrent bridge instances serialize staging writes`() {
        val first = WebUiBridge(WebServer(0, configDir), configDir)
        val second = WebUiBridge(WebServer(0, configDir), configDir)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                listOf(first, second).map { instance ->
                    executor.submit<JSONObject> {
                        val body = ByteArray(300 * 1024) { 0x43 }
                        val response =
                            NanoHTTPD.newFixedLengthResponse(
                                NanoHTTPD.Response.Status.OK,
                                "application/octet-stream",
                                ByteArrayInputStream(body),
                                body.size.toLong(),
                            )
                        try {
                            JSONObject(String(instance.encodeResponse(response), Charsets.UTF_8))
                        } finally {
                            body.fill(0)
                        }
                    }
                }
            futures.forEach { assertTrue(it.get().has("downloadId")) }
        } finally {
            executor.shutdownNow()
            first.stop()
            second.stop()
        }
        assertEquals(2, File(configDir, "webui_bridge/staging").listFiles()?.count { it.name.endsWith(".download") })
    }

    @Test
    fun `large response rejects when staging byte quota is exhausted`() {
        bridge = WebUiBridge(WebServer(0, configDir), configDir)
        val stagingFile = File(configDir, "webui_bridge/staging/00000000000000000000000000000000.upload")
        RandomAccessFile(stagingFile, "rw").use { it.setLength(64L * 1024 * 1024) }
        val body = ByteArray(300 * 1024) { 0x42 }
        val response =
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/octet-stream",
                ByteArrayInputStream(body),
                body.size.toLong(),
            )

        try {
            bridge.encodeResponse(response)
            throw AssertionError("staging byte quota was not enforced")
        } catch (_: IllegalArgumentException) {
            // A bounded staging directory must fail closed before writing another artifact.
        }
        assertEquals(64L * 1024 * 1024, stagingFile.length())
        body.fill(0)
    }

    @Test
    fun `large response rejects when staging scan bound is exceeded`() {
        bridge = WebUiBridge(WebServer(0, configDir), configDir)
        val staging = File(configDir, "webui_bridge/staging")
        repeat(1025) { index -> File(staging, "noise-$index").writeText("ignored") }
        val body = ByteArray(300 * 1024) { 0x45 }
        val response =
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/octet-stream",
                ByteArrayInputStream(body),
                body.size.toLong(),
            )

        try {
            bridge.encodeResponse(response)
            throw AssertionError("oversized staging scan was accepted")
        } catch (_: Exception) {
            // Staging inventory must fail closed before it allocates another artifact.
        }
        body.fill(0)
    }

    @Test
    fun `stale staging cleanup remains bounded when the directory is oversized`() {
        bridge = WebUiBridge(WebServer(0, configDir), configDir)
        val staging = File(configDir, "webui_bridge/staging")
        val staleAt = System.currentTimeMillis() - 11 * 60 * 1000L
        repeat(1025) { index ->
            val id = index.toString(16).padStart(32, '0')
            val file = File(staging, "$id.upload")
            file.writeText("stale")
            assertTrue(file.setLastModified(staleAt - index))
        }

        bridge.cleanupStale()

        val remaining = staging.listFiles()?.count { it.isFile } ?: 0
        assertEquals(1, remaining)
    }

    @Test
    fun `stale cleanup preserves the staging lock file`() {
        bridge = WebUiBridge(WebServer(0, configDir), configDir)
        val lockFile = File(configDir, "webui_bridge/staging/.staging.lock")
        lockFile.writeText("")
        assertTrue(lockFile.setLastModified(System.currentTimeMillis() - 11 * 60 * 1000L))

        bridge.cleanupStale()

        assertTrue("stale cleanup must not delete the coordination lock", lockFile.exists())
    }

    @Test
    fun `stale cleanup never follows symlinks or deletes directories`() {
        bridge = WebUiBridge(WebServer(0, configDir), configDir)
        val staging = File(configDir, "webui_bridge/staging")
        val staleAt = System.currentTimeMillis() - 11 * 60 * 1000L
        val outside = tempFolder.newFile("outside-payload")
        outside.writeText("keep")
        val link = File(staging, "11111111111111111111111111111111.upload")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val directory = File(staging, "22222222222222222222222222222222.upload")
        assertTrue(directory.mkdirs())
        assertTrue(directory.setLastModified(staleAt))
        val fresh = File(staging, "33333333333333333333333333333333.upload")
        fresh.writeText("fresh")

        bridge.cleanupStale()

        assertTrue("symlink must remain", Files.isSymbolicLink(link.toPath()))
        assertTrue("symlink target must remain", outside.exists())
        assertTrue("directory must remain", directory.isDirectory)
        assertTrue("fresh regular file must remain", fresh.exists())
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
