package cleveres.tricky.cleverestech

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File
import java.io.InputStream
import java.util.UUID

class WebServerXssTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var webServer: WebServer
    private lateinit var configDir: File

    @Before
    fun setUp() {
        configDir = tempFolder.newFolder("config")
        File(configDir, "app_config").createNewFile()
        webServer = WebServer(8080, configDir)
    }

    @Test
    fun testAppConfigXssInjection() {
        // Malicious payload that bypasses whitespace check but contains HTML tags
        val xssPayload = "<svg/onload=alert(1)>"
        val jsonPayload = "[{\"package\": \"$xssPayload\", \"template\": \"null\", \"keybox\": \"null\"}]"

        val session = object : NanoHTTPD.IHTTPSession {
            override fun execute() {}
            override fun getCookies() = null
            override fun getHeaders() = mapOf("content-length" to jsonPayload.length.toString())
            override fun getInputStream(): InputStream? = null
            override fun getMethod() = NanoHTTPD.Method.POST
            override fun getParms() = mapOf("token" to webServer.token, "data" to jsonPayload)
            override fun getParameters() = emptyMap<String, List<String>>()
            override fun getQueryParameterString() = ""
            override fun getUri() = "/api/app_config_structured"
            override fun parseBody(files: MutableMap<String, String>?) {}
            override fun getRemoteIpAddress() = "127.0.0.1"
            override fun getRemoteHostName() = "localhost"
        }

        val response = webServer.serve(session)

        // Consume response stream
        val responseText = response.data.bufferedReader().use { it.readText() }

        // We expect this to be rejected with BAD_REQUEST
        assertEquals("Should reject malicious package name", NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        assertEquals("Should return error message", "Invalid input: invalid characters", responseText)
    }

    @Test
    fun testAppConfigValidInput() {
        val jsonPayload = "[{\"package\": \"com.example.app\", \"template\": \"pixel8pro\", \"keybox\": \"null\"}]"

        val session = object : NanoHTTPD.IHTTPSession {
            override fun execute() {}
            override fun getCookies() = null
            override fun getHeaders() = mapOf("content-length" to jsonPayload.length.toString())
            override fun getInputStream(): InputStream? = null
            override fun getMethod() = NanoHTTPD.Method.POST
            override fun getParms() = mapOf("token" to webServer.token, "data" to jsonPayload)
            override fun getParameters() = emptyMap<String, List<String>>()
            override fun getQueryParameterString() = ""
            override fun getUri() = "/api/app_config_structured"
            override fun parseBody(files: MutableMap<String, String>?) {}
            override fun getRemoteIpAddress() = "127.0.0.1"
            override fun getRemoteHostName() = "localhost"
        }

        val response = webServer.serve(session)
        val responseText = response.data.bufferedReader().use { it.readText() }

        assertEquals("Should accept valid input", NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("Saved", responseText)
    }
}
