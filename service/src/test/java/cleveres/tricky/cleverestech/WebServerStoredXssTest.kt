package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

class WebServerStoredXssTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var webServer: WebServer
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
        webServer = WebServer(8080, configDir)
    }

    @org.junit.After
    fun tearDown() {
        SecureFile.impl = originalSecureFileImpl
    }

    @Test
    fun testStoredXssInTemplateField() {
        // Simulating a malicious file content created via /api/save or shell
        // "package_name template_name keybox_name"
        // Here package is valid, but template contains XSS
        val maliciousContent = "com.example <svg/onload=alert(1)> null"
        File(configDir, "app_config").writeText(maliciousContent)

        val session =
            object : NanoHTTPD.IHTTPSession {
                override fun execute() {}

                override fun getCookies() = null

                @Deprecated("Deprecated by NanoHTTPD")
                override fun getHeaders() = mapOf("host" to "localhost")

                override fun getInputStream(): InputStream? = null

                override fun getMethod() = NanoHTTPD.Method.GET

                @Deprecated("Use getParameters")

                override fun getParms() = mapOf("token" to webServer.token)

                override fun getParameters(): Map<String, List<String>> = emptyMap<String, List<String>>()

                @Deprecated("Deprecated by NanoHTTPD")
                override fun getQueryParameterString() = ""

                override fun getUri() = "/api/app_config_structured"

                override fun parseBody(files: MutableMap<String, String>?) {}

                @Deprecated("Deprecated by NanoHTTPD")
                override fun getRemoteIpAddress() = "127.0.0.1"

                @Deprecated("Deprecated by NanoHTTPD")
                override fun getRemoteHostName() = "localhost"
            }

        val response = webServer.serve(session)

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val jsonStr = response.data.bufferedReader().use { it.readText() }

        // The server should filter out the malicious entry entirely
        val jsonArray = JSONArray(jsonStr)
        assertEquals("Should contain no entries", 0, jsonArray.length())
    }
}
