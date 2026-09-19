package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class WebServerPostTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: WebServer
    private lateinit var configDir: File
    private lateinit var originalSecureFileImpl: SecureFileOperations

    @Before
    fun setUp() {
        configDir = tempFolder.newFolder("config")
        PolicyState.setRootForTesting(configDir)

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

        server = WebServer(0, configDir)
        server.start()
    }

    @After
    fun tearDown() {
        SecureFile.impl = originalSecureFileImpl
        server.stop()
        PolicyState.resetForTesting()
    }

    @Test
    fun testSaveFileWithBodyParams() {
        val port = server.listeningPort
        val token = server.token
        // Only token in URL
        val saveUrl = URL("http://localhost:$port/api/save?token=$token")

        val conn = saveUrl.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        val postData = "filename=target.txt&content=BODY_CONTENT"
        val postDataBytes = postData.toByteArray(StandardCharsets.UTF_8)

        conn.outputStream.use { it.write(postDataBytes) }

        val responseCode = conn.responseCode
        println("Response Code: $responseCode")

        if (responseCode == 200) {
            val response = conn.inputStream.bufferedReader().readText()
            println("Response: $response")
        } else {
            val error = conn.errorStream?.bufferedReader()?.readText()
            println("Error Response: $error")
        }

        assertEquals(200, responseCode)

        val savedFile = File(configDir, "target.txt")
        assertTrue("File should exist", savedFile.exists())
        assertEquals("File content mismatch", "BODY_CONTENT", savedFile.readText())
    }

    @Test
    fun `keybox priority order parses authenticated form body`() {
        val priorityUrl = URL("http://localhost:${server.listeningPort}/api/keybox_priority_order?token=${server.token}")
        val customOrder = KeyboxPriorityCategory.UI_ORDER.reversed().map { it.name }
        val payload = org.json.JSONObject()
            .put("mode", "custom")
            .put("customOrder", org.json.JSONArray(customOrder))
            .toString()
        val conn = priorityUrl.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val postData = "data=${URLEncoder.encode(payload, StandardCharsets.UTF_8)}"
        conn.outputStream.use { it.write(postData.toByteArray(StandardCharsets.UTF_8)) }

        assertEquals(200, conn.responseCode)
        val echoed = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
        assertEquals("custom", echoed.getString("mode"))
        assertEquals(customOrder, echoed.getJSONArray("customOrder").let { array -> List(array.length(), array::getString) })
    }

    @Test
    fun testSaveIdentityTargetFile() {
        val port = server.listeningPort
        val token = server.token
        val saveUrl = URL("http://localhost:$port/api/save?token=$token")

        val conn = saveUrl.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        val postData = "filename=identity_target.txt&content=com.android.vending%0Aio.github.vvb2060.keyattestation"
        val postDataBytes = postData.toByteArray(StandardCharsets.UTF_8)

        conn.outputStream.use { it.write(postDataBytes) }

        assertEquals(200, conn.responseCode)
        val savedFile = File(configDir, "identity_target.txt")
        assertTrue("identity_target.txt should exist", savedFile.exists())
        assertEquals("com.android.vending\nio.github.vvb2060.keyattestation", savedFile.readText())
    }

    @Test
    fun testSetGlobalIdentityMode() {
        val port = server.listeningPort
        val token = server.token
        val toggleUrl = URL("http://localhost:$port/api/toggle?token=$token")

        val conn = toggleUrl.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        val postData = "setting=global_identity_mode&value=true"
        conn.outputStream.use { it.write(postData.toByteArray(StandardCharsets.UTF_8)) }

        assertEquals(200, conn.responseCode)
        val savedFile = File(configDir, "global_identity_mode")
        assertTrue("global_identity_mode should exist", savedFile.exists())
    }
}
