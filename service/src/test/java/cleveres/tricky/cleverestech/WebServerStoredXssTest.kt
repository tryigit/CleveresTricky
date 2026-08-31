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

        val session = MockIHTTPSession(
            uri = "/api/app_config_structured",
            method = NanoHTTPD.Method.GET,
            parameters = mapOf("token" to listOf(webServer.token))
        )

        val response = webServer.serve(session)

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        val jsonStr = response.data.bufferedReader().use { it.readText() }

        // The server should filter out the malicious entry entirely
        val jsonArray = JSONArray(jsonStr)
        assertEquals("Should contain no entries", 0, jsonArray.length())
    }
}
