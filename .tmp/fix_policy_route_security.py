from pathlib import Path

web_server = Path("service/src/main/java/cleveres/tricky/cleverestech/WebServer.kt")
text = web_server.read_text()
needle = '''        if (uri == "/api/config" && method == Method.GET) {\n'''
replacement = '''        if (uri == "/api/policy_state" || uri == "/api/effective_state" || uri == "/api/profile_v2") {\n            if (method == Method.POST) {\n                val files = HashMap<String, String>()\n                try {\n                    session.parseBody(files)\n                } catch (error: Exception) {\n                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")\n                }\n            }\n            PolicyApi.serve(session)?.let { response ->\n                addSecurityHeaders(response)\n                return response\n            }\n        }\n\n        if (uri == "/api/config" && method == Method.GET) {\n'''
if needle not in text:
    raise SystemExit("WebServer policy insertion point not found")
web_server.write_text(text.replace(needle, replacement, 1))

bridge = Path("service/src/main/java/cleveres/tricky/cleverestech/WebUiBridge.kt")
text = bridge.read_text()
old = '            val response = PolicyApi.serve(parsed.session) ?: server.serveBridge(parsed.session)\n'
new = '            val response = server.serveBridge(parsed.session)\n'
if old not in text:
    raise SystemExit("WebUiBridge policy bypass not found")
bridge.write_text(text.replace(old, new, 1))

api = Path("service/src/main/java/cleveres/tricky/cleverestech/PolicyApi.kt")
text = api.read_text()
text = text.replace("object PolicyApi {", "internal object PolicyApi {", 1)
text = text.replace('        NanoHTTPD.newFixedLengthResponse(status, "application/json", value.toString()).also(::secure)\n', '        NanoHTTPD.newFixedLengthResponse(status, "application/json", value.toString())\n', 1)
text = text.replace('        NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, value).also(::secure)\n\n    private fun secure(response: NanoHTTPD.Response) {\n        response.addHeader("X-Content-Type-Options", "nosniff")\n        response.addHeader("Cache-Control", "no-store")\n    }\n', '        NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, value)\n', 1)
api.write_text(text)

security_test = Path("service/src/test/java/cleveres/tricky/cleverestech/WebServerPolicySecurityTest.kt")
security_test.write_text('''package cleveres.tricky.cleverestech

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class WebServerPolicySecurityTest {
    private fun session(
        method: NanoHTTPD.Method,
        uri: String,
        contentLength: String = "0",
    ): NanoHTTPD.IHTTPSession =
        object : NanoHTTPD.IHTTPSession {
            override fun execute() {}

            override fun getCookies() = null

            @Deprecated("Deprecated by NanoHTTPD")
            override fun getHeaders() = mapOf("host" to "localhost", "content-length" to contentLength)

            override fun getInputStream(): InputStream? = null

            override fun getMethod() = method

            @Deprecated("Use getParameters")
            override fun getParms(): Map<String, String> = emptyMap()

            override fun getParameters(): Map<String, List<String>> = emptyMap()

            @Deprecated("Deprecated by NanoHTTPD")
            override fun getQueryParameterString() = ""

            override fun getUri() = uri

            override fun parseBody(files: MutableMap<String, String>?) {}

            @Deprecated("Deprecated by NanoHTTPD")
            override fun getRemoteIpAddress() = "127.0.0.1"

            @Deprecated("Deprecated by NanoHTTPD")
            override fun getRemoteHostName() = "localhost"
        }

    @Test
    fun `policy route retains common tamper protection on native bridge`() {
        val root = Files.createTempDirectory("policy-route-tamper").toFile()
        try {
            val server = WebServer(0, root, isTampered = true)
            val response = server.serveBridge(session(NanoHTTPD.Method.GET, "/api/policy_state"))
            assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, response.status)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `policy route retains common native payload limit`() {
        val root = Files.createTempDirectory("policy-route-size").toFile()
        try {
            val server = WebServer(0, root)
            val response = server.serveBridge(
                session(NanoHTTPD.Method.POST, "/api/policy_state", Long.MAX_VALUE.toString()),
            )
            assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        } finally {
            root.deleteRecursively()
        }
    }
}
''')
