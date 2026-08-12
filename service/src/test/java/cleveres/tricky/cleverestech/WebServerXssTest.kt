@file:Suppress("ktlint:standard:max-line-length")

package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

class WebServerXssTest {
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
    fun testAppConfigXssInjection() {
        // This payload contains characters < > / = ( ) which are dangerous for XSS
        val xssPayload = "<svg/onload=alert(1)>"
        val jsonPayload = "[{\"package\": \"$xssPayload\", \"template\": \"null\", \"keybox\": \"null\"}]"

        val session =
            object : NanoHTTPD.IHTTPSession {
                override fun execute() {}

                override fun getCookies() = null

                @Deprecated("Deprecated by NanoHTTPD")
                override fun getHeaders() = mapOf("content-length" to jsonPayload.length.toString(), "host" to "localhost")

                override fun getInputStream(): InputStream? = null

                override fun getMethod() = NanoHTTPD.Method.POST

                @Deprecated("Use getParameters")

                override fun getParms() = mapOf("token" to webServer.token, "data" to jsonPayload)

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

        // Assert that the server rejects the request with 400 Bad Request
        assertEquals("Should return BAD_REQUEST", NanoHTTPD.Response.Status.BAD_REQUEST, response.status)

        // We verify the body message too
        val responseBody = response.data.bufferedReader().use { it.readText() }
        assertEquals("Invalid input: invalid characters", responseBody)
    }

    @Test
    fun testValidAppConfig() {
        // Valid package name with dots, underscores, and alphanumeric
        val validPkg = "com.example.app_123"
        // Also verify wildcard is allowed as per discussion
        val wildcardPkg = "com.example.*"

        val jsonPayload = "[{\"package\": \"$validPkg\", \"template\": \"pixel8pro\", \"keybox\": \"null\"}, {\"package\": \"$wildcardPkg\", \"template\": \"null\", \"keybox\": \"null\", \"privacy\": \"isolate\"}]"

        val session =
            object : NanoHTTPD.IHTTPSession {
                override fun execute() {}

                override fun getCookies() = null

                @Deprecated("Deprecated by NanoHTTPD")
                override fun getHeaders() = mapOf("content-length" to jsonPayload.length.toString(), "host" to "localhost")

                override fun getInputStream(): InputStream? = null

                override fun getMethod() = NanoHTTPD.Method.POST

                @Deprecated("Use getParameters")

                override fun getParms() = mapOf("token" to webServer.token, "data" to jsonPayload)

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

        assertEquals("Should return OK", NanoHTTPD.Response.Status.OK, response.status)
    }
}
