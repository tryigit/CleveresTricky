package cleveres.tricky.cleverestech

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.UUID

class WebServerTest {

    @Test
    fun testListKeyboxes() {
        // Setup temp directory
        val tempDir = File(System.getProperty("java.io.tmpdir"), "webserver_test_${UUID.randomUUID()}")
        tempDir.mkdirs()
        val keyboxDir = File(tempDir, "keyboxes")
        keyboxDir.mkdirs()

        // Create dummy keyboxes
        File(keyboxDir, "test1.xml").createNewFile()
        File(keyboxDir, "test2.xml").createNewFile()
        File(keyboxDir, "ignore.txt").createNewFile()

        // Init WebServer with no-op permission setter
        val server = WebServer(0, tempDir) { _, _ -> }

        // Create dummy session
        val session = object : IHTTPSession {
            override fun execute() {}
            override fun getCookies() = server.CookieHandler(HashMap())
            override fun getHeaders() = HashMap<String, String>()
            override fun getInputStream(): InputStream? = null
            override fun getMethod() = Method.GET
            override fun getParms(): Map<String, String> {
                val map = HashMap<String, String>()
                map["token"] = server.token // Auth token
                return map
            }
            override fun getQueryParameterString() = ""
            override fun getUri() = "/api/keyboxes"
            override fun parseBody(files: Map<String, String>?) {}
            override fun getRemoteIpAddress() = "127.0.0.1"
            override fun getRemoteHostName() = "localhost"
            override fun getParameters(): Map<String, List<String>> = HashMap()
        }

        val response = server.serve(session)
        val jsonStr = inputStreamToString(response.data)

        // Assertions
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertTrue("JSON should contain test1.xml", jsonStr.contains("test1.xml"))
        assertTrue("JSON should contain test2.xml", jsonStr.contains("test2.xml"))
        assertTrue("JSON should NOT contain ignore.txt", !jsonStr.contains("ignore.txt"))

        // Cleanup
        tempDir.deleteRecursively()
    }

    @Test
    fun testFrontendContainsKeyboxPicker() {
        // Setup temp directory
        val tempDir = File(System.getProperty("java.io.tmpdir"), "webserver_test_${UUID.randomUUID()}")
        tempDir.mkdirs()

        // Init WebServer
        val server = WebServer(0, tempDir) { _, _ -> }

        // Create dummy session
        val session = object : IHTTPSession {
            override fun execute() {}
            override fun getCookies() = server.CookieHandler(HashMap())
            override fun getHeaders() = HashMap<String, String>()
            override fun getInputStream(): InputStream? = null
            override fun getMethod() = Method.GET
            override fun getParms(): Map<String, String> {
                 val map = HashMap<String, String>()
                 map["token"] = server.token
                 return map
            }
            override fun getQueryParameterString() = ""
            override fun getUri() = "/"
            override fun parseBody(files: Map<String, String>?) {}
            override fun getRemoteIpAddress() = "127.0.0.1"
            override fun getRemoteHostName() = "localhost"
            override fun getParameters(): Map<String, List<String>> = HashMap()
        }

        val response = server.serve(session)
        val html = inputStreamToString(response.data)

        // Assertions
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        // Updated to reflect UI change: appKeybox is now an INPUT (text) to allow custom filenames
        assertTrue("HTML should contain <input type=\"text\" id=\"appKeybox\"", html.contains("<input type=\"text\" id=\"appKeybox\""))
        assertTrue("HTML should NOT contain <select id=\"appKeybox\">", !html.contains("<select id=\"appKeybox\">"))

        // Cleanup
        tempDir.deleteRecursively()
    }

    private fun inputStreamToString(inputStream: InputStream?): String {
        return inputStream?.bufferedReader()?.use { it.readText() } ?: ""
    }
}
