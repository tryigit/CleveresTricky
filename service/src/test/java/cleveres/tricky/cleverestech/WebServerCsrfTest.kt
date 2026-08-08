package cleveres.tricky.cleverestech

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.UUID

class WebServerCsrfTest {
    private fun createSession(
        server: WebServer,
        host: String,
        origin: String,
    ): NanoHTTPD.IHTTPSession {
        return object : NanoHTTPD.IHTTPSession {
            override fun execute() {}

            override fun getCookies() = null

            @Deprecated("NanoHTTPD deprecated this, ignore warning")
            override fun getHeaders() =
                mapOf(
                    "host" to host,
                    "origin" to origin,
                    "content-length" to "0",
                )

            override fun getInputStream() = null

            override fun getMethod() = NanoHTTPD.Method.POST

            override fun getParms() = mapOf("token" to server.token)

            @Deprecated("NanoHTTPD deprecated this, ignore warning")
            override fun getQueryParameterString() = ""

            override fun getUri() = "/api/config"

            override fun parseBody(files: Map<String, String>?) {}

            @Deprecated("NanoHTTPD deprecated this, ignore warning")
            override fun getRemoteIpAddress() = "127.0.0.1"

            @Deprecated("NanoHTTPD deprecated this, ignore warning")
            override fun getRemoteHostName() = "localhost"

            override fun getParameters(): Map<String, List<String>> = mapOf("token" to listOf("testtoken"))
        }
    }

    @Test
    fun testCsrfWeakness() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "csrf_test_${UUID.randomUUID()}")
        tempDir.mkdirs()
        val server = WebServer(0, tempDir)

        // Case: Host: localhost, Origin: http://localhost.attacker.com
        // This simulates an attacker using a domain that contains "localhost" substring.
        // Current logic: origin.contains(host) -> TRUE.
        // This allows the request to proceed (Response OK because token is valid).
        // We WANT it to be FORBIDDEN.

        val session = createSession(server, "localhost", "http://localhost.attacker.com")
        val response = server.serve(session)

        // Asserting FORBIDDEN will fail on current code
        assertEquals("Should block partial match", NanoHTTPD.Response.Status.FORBIDDEN, response.status)

        tempDir.deleteRecursively()
    }

    @Test
    fun testCsrfValid() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "csrf_test_valid_${UUID.randomUUID()}")
        tempDir.mkdirs()
        val server = WebServer(0, tempDir)

        // Case: Host: localhost:8080, Origin: http://localhost:8080
        val session = createSession(server, "localhost:8080", "http://localhost:8080")
        val response = server.serve(session)

        // Should NOT be FORBIDDEN.
        // Passed CSRF, valid token, but POST /api/config is not handled -> NOT_FOUND
        assertEquals("Should allow exact match", NanoHTTPD.Response.Status.NOT_FOUND, response.status)

        tempDir.deleteRecursively()
    }
}
