package cleveres.tricky.cleverestech

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class AppConfigKeyboxValidationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: WebServer
    private lateinit var configDir: File

    @Before
    fun setUp() {
        configDir = tempFolder.newFolder("config")
        server = WebServer(0, configDir)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun testKeyboxWithSpecialCharactersShouldBeRejected() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/api/app_config_structured?token=$token")

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        // Malicious payload: Keybox with special characters that should be rejected
        // per the memory description: "^[a-zA-Z0-9_.-]+$"
        val maliciousKeybox = "foo<script>bar.xml"

        val jsonArray = JSONArray()
        val obj = JSONObject()
        obj.put("package", "com.valid.app")
        obj.put("template", "valid_template")
        obj.put("keybox", maliciousKeybox)
        jsonArray.put(obj)

        val postData = "data=" + java.net.URLEncoder.encode(jsonArray.toString(), "UTF-8")
        val postDataBytes = postData.toByteArray(StandardCharsets.UTF_8)

        conn.outputStream.use { it.write(postDataBytes) }

        val responseCode = conn.responseCode

        // Currently this fails because the code accepts it (returns 200)
        // We want it to be >= 400
        val msg = if (responseCode == 200) "Accepted invalid keybox: $maliciousKeybox" else "Rejected"
        println("Response: $responseCode - $msg")

        assertTrue("Should reject keybox with special characters", responseCode >= 400)
    }

    @Test
    fun testKeyboxWithPathTraversalShouldBeRejected() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/api/app_config_structured?token=$token")

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        val maliciousKeybox = "../../etc/passwd"

        val jsonArray = JSONArray()
        val obj = JSONObject()
        obj.put("package", "com.valid.app")
        obj.put("template", "valid_template")
        obj.put("keybox", maliciousKeybox)
        jsonArray.put(obj)

        val postData = "data=" + java.net.URLEncoder.encode(jsonArray.toString(), "UTF-8")
        val postDataBytes = postData.toByteArray(StandardCharsets.UTF_8)

        conn.outputStream.use { it.write(postDataBytes) }

        val responseCode = conn.responseCode
        assertTrue("Should reject keybox with path traversal", responseCode >= 400)
    }
}
