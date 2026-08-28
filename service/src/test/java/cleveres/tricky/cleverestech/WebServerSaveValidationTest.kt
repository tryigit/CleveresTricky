package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
        return MockIHTTPSession(
            uri = "/api/save",
            method = NanoHTTPD.Method.POST,
            headers = mapOf("content-length" to "100", "host" to "localhost"),
            parms = mapOf(
                "token" to webServer.token,
                "filename" to filename,
                "content" to content,
            )
        )
    }

    @Test
    fun testAppConfigValid() {
        val content = "com.example.app pixel8pro keybox.xml\n# Comment\ncom.foo.bar null null isolate\n"
        val response = webServer.serve(mockSession("app_config", content))
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertTrue(File(configDir, "app_config").exists())

        val threeColumnContent =
            "com.example.app template1 keybox1.xml\n" +
                "com.test.pkg null null redact\n" +
                "com.another.one template-2 keybox-2.xml"
        assertEquals(NanoHTTPD.Response.Status.OK, webServer.serve(mockSession("app_config", threeColumnContent)).status)

        val privacyContent = "com.private.app null null isolate\ncom.redacted.app pixel8pro null redact"
        assertEquals(NanoHTTPD.Response.Status.OK, webServer.serve(mockSession("app_config", privacyContent)).status)
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
        val unsupportedKeyboxExtension = "com.example.app template1 keybox.txt"
        val unsupportedFourthColumn = "com.example.app template1 keybox1.xml CONTACTS"
        val fifthColumn = "com.example.app template1 keybox1.xml isolate extra"

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("app_config", invalidPackage)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("app_config", invalidTemplate)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("app_config", invalidKeybox)).status)
        assertEquals(
            NanoHTTPD.Response.Status.BAD_REQUEST,
            webServer.serve(mockSession("app_config", unsupportedKeyboxExtension)).status,
        )
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("app_config", unsupportedFourthColumn)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("app_config", fifthColumn)).status)
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
        val content =
            """
            all=YYYY-MM-05
            vendor=device_default
            boot=no
            [com.google.android.gms]
            system=20240101
            """.trimIndent()
        val response = webServer.serve(mockSession("security_patch.txt", content))
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun testSecurityPatchInvalid() {
        val content = "2024-01-01\nINJECTED <script>"
        val response = webServer.serve(mockSession("security_patch.txt", content))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)

        val legacyRuleInsideSection =
            """
            [com.example.app]
            com.example.other=2024-01-01
            """.trimIndent()
        assertEquals(
            NanoHTTPD.Response.Status.BAD_REQUEST,
            webServer.serve(mockSession("security_patch.txt", legacyRuleInsideSection)).status,
        )
    }

    @Test
    fun testLegacySecurityPatchWildcardValid() {
        val content = "system=2025-09-01\ncom.example.*=2024-01-01"
        val response = webServer.serve(mockSession("security_patch.txt", content))
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun testDrmPackagesValidation() {
        val valid = "com.netflix.mediaclient\ncom.example.*\n# comment"
        assertEquals(NanoHTTPD.Response.Status.OK, webServer.serve(mockSession("drm_packages.txt", valid)).status)

        val invalid = "com.example.valid\ncom.example;reboot"
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("drm_packages.txt", invalid)).status)
    }

    @Test
    fun testBootPropsModeValidation() {
        assertEquals(NanoHTTPD.Response.Status.OK, webServer.serve(mockSession("boot_props_mode", "auto\n")).status)
        assertEquals(NanoHTTPD.Response.Status.OK, webServer.serve(mockSession("boot_props_mode", "force")).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("boot_props_mode", "always")).status)
    }

    @Test
    fun testUnknownConfigFileIsRejected() {
        val response = webServer.serve(mockSession("private_state", "value"))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        assertFalse(File(configDir, "private_state").exists())
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
    fun testResetEnvironmentRejectsOversizedExistingConfigBeforeRewrite() {
        val spoofFile = File(configDir, "spoof_build_vars")
        val oversized = "MODEL=" + "x".repeat(1024 * 1024)
        spoofFile.writeText(oversized)
        val originalLength = spoofFile.length()

        val response = webServer.serve(
            MockIHTTPSession(
                uri = "/api/reset_environment",
                method = NanoHTTPD.Method.POST,
                headers = mapOf("content-length" to "0", "host" to "localhost"),
                parms = mapOf("token" to webServer.token),
            )
        )

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        assertEquals(originalLength, spoofFile.length())
        assertEquals(oversized, spoofFile.readText())
    }

    @Test
    fun testDegeneratePrivacySeedValidation() {
        listOf("00", "ff").forEach { byteHex ->
            assertFalse(WebServer.validateContent("privacy_seed", byteHex.repeat(32)))
        }

        val valid = "0123456789abcdef".repeat(4)
        assertTrue(WebServer.validateContent("privacy_seed", valid))
    }

    @Test
    fun testTemplatesJsonValidation() {
        val valid =
            """
            [{
              "id":"test",
              "manufacturer":"Example",
              "model":"Test",
              "fingerprint":"example/test/test:14/BUILD/1:user/release-keys",
              "brand":"example",
              "product":"test",
              "device":"test",
              "release":"14",
              "buildId":"BUILD",
              "incremental":"1",
              "securityPatch":"2024-01-01"
            }]
            """.trimIndent()
        assertEquals(NanoHTTPD.Response.Status.OK, webServer.serve(mockSession("templates.json", valid)).status)

        val invalid1 = "NOT JSON"
        val invalid2 = "[{\"id\":\"test\",\"model\":\"missing required fields\"}]"
        val invalid3 = "[}"
        val invalidObject = "{\"id\":\"test\"}"

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("templates.json", invalid1)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("templates.json", invalid2)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("templates.json", invalid3)).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, webServer.serve(mockSession("templates.json", invalidObject)).status)
    }
}
