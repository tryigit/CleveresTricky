package cleveres.tricky.cleverestech

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
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class AppConfigInjectionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: WebServer
    private lateinit var configDir: File
    private lateinit var originalSecureFileImpl: SecureFileOperations

    @Before
    fun setUp() {
        configDir = tempFolder.newFolder("config")

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
    }

    @Test
    fun testAppConfigInjectionRejected() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/api/app_config_structured?token=$token")

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        // Malicious payload: Injecting a newline
        val maliciousPkg = "com.valid.app\ncom.injected.app"

        val jsonArray = JSONArray()
        val obj = JSONObject()
        obj.put("package", maliciousPkg)
        obj.put("template", "valid_template")
        obj.put("keybox", "valid_keybox")
        jsonArray.put(obj)

        val postData = "data=" + java.net.URLEncoder.encode(jsonArray.toString(), "UTF-8")
        val postDataBytes = postData.toByteArray(StandardCharsets.UTF_8)

        conn.outputStream.use { it.write(postDataBytes) }

        val responseCode = conn.responseCode
        // Expect BAD_REQUEST (400) or similar due to validation failure
        assertTrue("Should reject injection", responseCode >= 400)

        val appConfigFile = File(configDir, "app_config")
        // File should NOT exist if it was the first request and failed
        assertFalse(appConfigFile.exists())
    }

    @Test
    fun testAppConfigValidInput() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/api/app_config_structured?token=$token")

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        val pkg = "com.valid.app"
        val jsonArray = JSONArray()
        val obj = JSONObject()
        obj.put("package", pkg)
        obj.put("template", "valid_template")
        obj.put("keybox", "valid_keybox")
        jsonArray.put(obj)

        val postData = "data=" + java.net.URLEncoder.encode(jsonArray.toString(), "UTF-8")
        val postDataBytes = postData.toByteArray(StandardCharsets.UTF_8)

        conn.outputStream.use { it.write(postDataBytes) }

        val responseCode = conn.responseCode
        assertEquals(200, responseCode)

        val appConfigFile = File(configDir, "app_config")
        assertTrue(appConfigFile.exists())

        val content = appConfigFile.readText()
        assertTrue(content.contains("com.valid.app valid_template valid_keybox"))
    }
}
