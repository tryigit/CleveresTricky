package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.DeviceKeyManager
import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ServerCredentialExposureTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: WebServer
    private lateinit var configDir: File
    private lateinit var originalSecureFileImpl: SecureFileOperations
    private lateinit var originalConfigRoot: File

    @Before
    fun setUp() {
        configDir = tempFolder.newFolder("config")
        originalConfigRoot = Config.getConfigRoot()
        Config.setRootForTesting(configDir)
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

                override fun writeStream(
                    file: File,
                    inputStream: java.io.InputStream,
                    limit: Long,
                ) {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { inputStream.copyTo(it) }
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
        DeviceKeyManager.initialize(configDir)
        ServerManager.initialize()
        server = WebServer(0, configDir, crlFetcher = { emptySet() }) { _, _ -> }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        SecureFile.impl = originalSecureFileImpl
        DeviceKeyManager.resetForTesting()
        Config.setRootForTesting(originalConfigRoot)
    }

    @Test
    fun testSanitizeAuthDataForApi() {
        val bearer = ServerManager.sanitizeAuthDataForApi("BEARER", JSONObject().put("token", "secret-token"))
        assertFalse(bearer.has("token"))
        assertTrue(bearer.optBoolean("hasToken"))

        val basic =
            ServerManager.sanitizeAuthDataForApi(
                "BASIC",
                JSONObject().put("username", "admin").put("password", "secret-pass"),
            )
        assertEquals("admin", basic.optString("username"))
        assertFalse(basic.has("password"))
        assertTrue(basic.optBoolean("hasPassword"))

        val apiKey =
            ServerManager.sanitizeAuthDataForApi(
                "API_KEY",
                JSONObject().put("headerName", "X-Custom-Key").put("key", "secret-key"),
            )
        assertEquals("X-Custom-Key", apiKey.optString("headerName"))
        assertFalse(apiKey.has("key"))
        assertTrue(apiKey.optBoolean("hasKey"))

        val custom =
            ServerManager.sanitizeAuthDataForApi(
                "CUSTOM",
                JSONObject().put("headers", JSONObject().put("X-Secret", "my-val")),
            )
        val headers = custom.optJSONObject("headers")
        assertNotNull(headers)
        assertTrue(headers!!.optBoolean("X-Secret"))
        assertFalse(headers.optString("X-Secret") == "my-val")
    }

    @Test
    fun testGetServersRedactsCredentialsForUntrustedCallers() {
        val serverId = "test-srv-1"
        val serverConfig =
            ServerManager.ServerConfig(
                id = serverId,
                name = "Test Server",
                url = "https://example.test/feed",
                priority = 10,
                enabled = true,
                authType = "BEARER",
                authData = JSONObject().put("token", "super-secret-token"),
                autoRefresh = true,
                refreshIntervalHours = 24,
                contentPassword = "super-secret-password",
                contentPublicKey = "my-content-pubkey",
            )
        ServerManager.addServer(serverConfig)

        val port = server.listeningPort
        val token = server.token
        val conn = URL("http://localhost:$port/api/servers?token=$token").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        assertEquals(200, conn.responseCode)
        val responseBody = conn.inputStream.bufferedReader().readText()
        val jsonArray = JSONArray(responseBody)
        assertEquals(1, jsonArray.length())
        val returnedServer = jsonArray.getJSONObject(0)

        // Secrets must NOT be exposed
        assertEquals("", returnedServer.optString("contentPassword"))
        assertTrue(returnedServer.optBoolean("hasContentPassword"))
        assertEquals("my-content-pubkey", returnedServer.optString("contentPublicKey"))

        val authData = returnedServer.getJSONObject("authData")
        assertFalse(authData.has("token"))
        assertTrue(authData.optBoolean("hasToken"))
    }

    @Test
    fun testUpdateServerPreservesExistingCredentialsWhenBlank() {
        val serverId = "test-srv-preserve"
        val initialServer =
            ServerManager.ServerConfig(
                id = serverId,
                name = "Preserve Server",
                url = "https://example.test/feed",
                priority = 1,
                enabled = true,
                authType = "BEARER",
                authData = JSONObject().put("token", "preserved-token"),
                autoRefresh = true,
                refreshIntervalHours = 12,
                contentPassword = "preserved-password",
                contentPublicKey = "preserved-pubkey",
            )
        ServerManager.addServer(initialServer)

        // Simulate WebUI edit saving: sends empty/blank contentPassword and authData with empty token
        val updatePayload =
            JSONObject().apply {
                put("id", serverId)
                put("name", "Preserve Server Updated")
                put("url", "https://example.test/feed")
                put("priority", 5)
                put("enabled", true)
                put("authType", "BEARER")
                put("authData", JSONObject().put("token", ""))
                put("autoRefresh", true)
                put("refreshIntervalHours", 48)
                put("contentPassword", "")
                put("contentPublicKey", "")
            }

        val port = server.listeningPort
        val token = server.token
        val boundary = "----Boundary" + UUID.randomUUID().toString().replace("-", "")
        val conn = URL("http://localhost:$port/api/server/add?token=$token").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        conn.outputStream.use { out ->
            val body =
                buildString {
                    append("--$boundary\r\n")
                    append("Content-Disposition: form-data; name=\"data\"\r\n\r\n")
                    append(updatePayload.toString())
                    append("\r\n--$boundary--\r\n")
                }
            out.write(body.toByteArray(Charsets.UTF_8))
        }

        assertEquals(200, conn.responseCode)

        // Verify that existing credentials remained preserved in ServerManager
        val updated = ServerManager.findServer(serverId)
        assertNotNull(updated)
        assertEquals("Preserve Server Updated", updated!!.name)
        assertEquals(5, updated.priority)
        assertEquals(48, updated.refreshIntervalHours)
        assertEquals("preserved-password", updated.contentPassword)
        assertEquals("preserved-pubkey", updated.contentPublicKey)
        assertEquals("preserved-token", updated.authData.optString("token"))
    }

    @Test
    fun testUpdateServerPreservesCustomAuthHeadersWithBooleanMarkers() {
        val serverId = "test-srv-custom"
        val initialServer =
            ServerManager.ServerConfig(
                id = serverId,
                name = "Custom Auth Server",
                url = "https://example.test/feed",
                priority = 1,
                enabled = true,
                authType = "CUSTOM",
                authData =
                    JSONObject().put(
                        "headers",
                        JSONObject()
                            .put("X-Secret-1", "secret-val-1")
                            .put("X-Secret-2", "secret-val-2"),
                    ),
                autoRefresh = true,
                refreshIntervalHours = 12,
            )
        ServerManager.addServer(initialServer)

        // Simulate client sending back sanitized authData (where X-Secret-1 is Boolean true, X-Secret-2 is omitted, X-Secret-3 is a new string)
        val updatePayload =
            JSONObject().apply {
                put("id", serverId)
                put("name", "Custom Auth Server Updated")
                put("url", "https://example.test/feed")
                put("priority", 2)
                put("enabled", true)
                put("authType", "CUSTOM")
                put(
                    "authData",
                    JSONObject().put(
                        "headers",
                        JSONObject()
                            .put("X-Secret-1", true) // boolean marker from sanitizeAuthDataForApi
                            .put("X-Secret-3", "new-secret-val-3"),
                    ),
                )
                put("autoRefresh", true)
                put("refreshIntervalHours", 12)
            }

        val port = server.listeningPort
        val token = server.token
        val boundary = "----Boundary" + UUID.randomUUID().toString().replace("-", "")
        val conn = URL("http://localhost:$port/api/server/add?token=$token").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        conn.outputStream.use { out ->
            val body =
                buildString {
                    append("--$boundary\r\n")
                    append("Content-Disposition: form-data; name=\"data\"\r\n\r\n")
                    append(updatePayload.toString())
                    append("\r\n--$boundary--\r\n")
                }
            out.write(body.toByteArray(Charsets.UTF_8))
        }

        assertEquals(200, conn.responseCode)

        val updated = ServerManager.findServer(serverId)
        assertNotNull(updated)
        val updatedHeaders = updated!!.authData.getJSONObject("headers")
        assertEquals("secret-val-1", updatedHeaders.getString("X-Secret-1"))
        assertEquals("secret-val-2", updatedHeaders.getString("X-Secret-2"))
        assertEquals("new-secret-val-3", updatedHeaders.getString("X-Secret-3"))
    }
}
