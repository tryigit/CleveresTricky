package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

class WebServerSaveValidationTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var webServer: WebServer
    private lateinit var configDir: File
    private lateinit var originalSecureFileImpl: SecureFileOperations

    @Before
    fun setUp() {
        configDir = tempFolder.newFolder("config")
        webServer = WebServer(8080, configDir)
        originalSecureFileImpl = SecureFile.impl

        // Mock SecureFile to avoid Android OS dependency
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
    }

    @After
    fun tearDown() {
        SecureFile.impl = originalSecureFileImpl
    }

    private fun mockSession(
        filename: String,
        content: String,
    ): NanoHTTPD.IHTTPSession {
        return object : NanoHTTPD.IHTTPSession {
            override fun execute() {}

            override fun getCookies() = null

            @Deprecated("NanoHTTPD deprecated this, ignore warning")
            override fun getHeaders() = mapOf("content-length" to "100", "host" to "localhost")

            override fun getInputStream(): InputStream? = null

            override fun getMethod() = NanoHTTPD.Method.POST

            override fun getParms() =
                mapOf(
                    "token" to webServer.token,
                    "filename" to filename,
                    "content" to content,
                )

            override fun getParameters(): Map<String, List<String>> = emptyMap<String, List<String>>()

            @Deprecated("NanoHTTPD deprecated this, ignore warning")
            override fun getQueryParameterString() = ""

            override fun getUri() = "/api/save"

            override fun parseBody(files: MutableMap<String, String>?) {}

            @Deprecated("NanoHTTPD deprecated this, ignore warning")
            override fun getRemoteIpAddress() = "127.0.0.1"

            @Deprecated("NanoHTTPD deprecated this, ignore warning")
            override fun getRemoteHostName() = "localhost"
        }
    }

    @Test
    fun testAppConfigValid() {
        val content = "com.example.app pixel8pro keybox.xml\n# Comment\ncom.foo.bar\n"
        val response = webServer.serve(mockSession("app_config", content))
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertTrue(File(configDir, "app_config").exists())

        val threeColumnContent =
            "com.example.app template1 keybox1.xml\n" +
                "com.test.pkg null null\n" +
                "com.another.one template-2 keybox-2.xml"
        assertEquals(NanoHTTPD.Response.Status.OK, webServer.serve(mockSession("app_config", threeColumnContent)).status)
    }

    @Test
    fun testAppConfigInvalid() {
        val content = "com.example.app pixel8pro keybox.xml\nINJECTED LINE!!!!\n"
        val response = webServer.serve(mockSession("app_config", content))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)

        // Invalid cases from optimization verification
        val invalidPackage = "com.ex@mple.app template1 keybox1.xml"
        val invalidTemplate = "com.example.app template*1 keybox1.xml"
        val invalidKeybox = "com.example.app template1 keybox/1.xml"
        val unsupportedFourthColumn = "com.example.app template1 keybox1.xml CONTACTS"

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("app_config", invalidPackage)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("app_config", invalidTemplate)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("app_config", invalidKeybox)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("app_config", unsupportedFourthColumn)).status)
    }

    @Test
    fun testTargetTxtValid() {
        val content = "com.example.app\ncom.foo.bar!\n# Comment"
        val response = webServer.serve(mockSession("target.txt", content))
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun testTargetTxtInvalid() {
        val content = "com.example.app\nINVALID PACK AGE\n"
        val response = webServer.serve(mockSession("target.txt", content))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
    }

    @Test
    fun testSpoofBuildVarsValid() {
        val content = "MANUFACTURER=Google\nMODEL=Pixel 8\nATTESTATION_ID_SERIAL=ABC123"
        val response = webServer.serve(mockSession("spoof_build_vars", content))
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun testSpoofBuildVarsRejectsUnsupportedNoOpKey() {
        val response = webServer.serve(mockSession("spoof_build_vars", "ro.product.model=Pixel 8"))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
    }

    @Test
    fun testSpoofBuildVarsInvalid() {
        val content = "MANUFACTURER=Google\nINVALID_LINE_NO_EQUALS\n"
        val response = webServer.serve(mockSession("spoof_build_vars", content))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
    }

    @Test
    fun testSecurityPatchValid() {
        val content = "2024-01-01\nsystem=20240101"
        val response = webServer.serve(mockSession("security_patch.txt", content))
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun testSecurityPatchInvalid() {
        val content = "2024-01-01\nINJECTED <script>"
        val response = webServer.serve(mockSession("security_patch.txt", content))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
    }

    @Test
    fun testSpoofBuildVarsSecurity() {
        // Valid content
        val validContent = "MANUFACTURER=Google\nMODEL=Pixel 8"
        assertEquals(NanoHTTPD.Response.Status.OK, webServer.serve(mockSession("spoof_build_vars", validContent)).status)

        // Invalid content with unsafe shell characters
        val bad1 = "MODEL=\$(rm -rf /)"
        val bad2 = "MODEL=value; rm -rf /"
        val bad3 = "MODEL=value & reboot"
        val bad4 = "MODEL=value | reboot"
        val bad5 = "MODEL=val > /tmp/x"
        val bad6 = "MODEL=val < /etc/passwd"
        val bad7 = "MODEL=`reboot`"

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("spoof_build_vars", bad1)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("spoof_build_vars", bad2)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("spoof_build_vars", bad3)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("spoof_build_vars", bad4)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("spoof_build_vars", bad5)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("spoof_build_vars", bad6)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("spoof_build_vars", bad7)).status)
    }

    @Test
    fun testTemplatesJsonValidation() {
        // Valid JSON
        val valid = "[{\"id\":\"test\",\"model\":\"Test\"}]"
        assertEquals(NanoHTTPD.Response.Status.OK, webServer.serve(mockSession("templates.json", valid)).status)

        // Invalid JSON
        val invalid1 = "NOT JSON"
        val invalid3 = "[}" // definitely invalid

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("templates.json", invalid1)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("templates.json", invalid3)).status)
    }
}
