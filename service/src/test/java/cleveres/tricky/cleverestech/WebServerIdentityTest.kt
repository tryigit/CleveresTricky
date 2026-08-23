package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.RandomUtils
import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class WebServerIdentityTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: WebServer
    private lateinit var configDir: File
    private lateinit var originalSecureFileImpl: SecureFileOperations

    @Before
    fun setUp() {
        Config.reset()
        configDir = tempFolder.newFolder("config")
        Config.setRootForTesting(configDir)
        DeviceTemplateManager.initialize(configDir)
        Config.updateCustomTemplates(null)
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
        server =
            WebServer(
                0,
                configDir,
                autoIdentityFetcher = {
                    AutoIdentityManager.Result(
                        model = "Pixel Test",
                        product = "test_beta",
                        device = "test",
                        fingerprint = "google/test_beta/test:CANARY/BP31.260801.001/12345678:user/release-keys",
                        buildId = "BP31.260801.001",
                        incremental = "12345678",
                        release = "17",
                        securityPatch = "2026-08-05",
                        securityPatchEstimated = false,
                    )
                },
            )
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        SecureFile.impl = originalSecureFileImpl
        Config.reset()
    }

    @Test
    fun `structured identity API validates and updates controlled keys atomically`() {
        val oldImei = RandomUtils.generateLuhn(15, "35")
        val file = File(configDir, "spoof_build_vars")
        file.writeText(
            """
            # Keep this comment
            MODEL=Keep Me
            ATTESTATION_ID_IMEI=$oldImei
            ATTESTATION_ID_IMEI=$oldImei
            ATTESTATION_ID_PHONE_NUMBER=+12025550000
            """.trimIndent()
                .plus("\n"),
        )
        Config.updateBuildVars(file)

        val imei = RandomUtils.generateLuhn(15, "35")
        val imei2 = RandomUtils.generateLuhn(15, "35")
        val imsi = RandomUtils.generateDigits(15, "310260")
        val imsi2 = RandomUtils.generateDigits(15, "310260")
        val iccid = RandomUtils.generateLuhn(20, "8901")
        val iccid2 = RandomUtils.generateLuhn(20, "8901")
        val request =
            JSONObject()
                .put("template", "")
                .put("imei", imei)
                .put("imei2", imei2)
                .put("imsi", imsi)
                .put("imsi2", imsi2)
                .put("iccid", iccid)
                .put("iccid2", iccid2)
                .put("meid", "a100000927f4e1")
                .put("meid2", "A100000927F4E2")
                .put("phone_number", "")
                .put("phone_number2", "+12025550124")
                .put("serial", "DEVICE_01")
                .put("visible_sim_count", "1")
                .put("visible_camera_count", "3")

        val response = postIdentity(request)
        assertEquals(200, response.first)
        val content = file.readText()
        assertTrue(content.contains("# Keep this comment"))
        assertTrue(content.contains("MODEL=Keep Me"))
        assertEquals(1, content.lineSequence().count { it.startsWith("ATTESTATION_ID_IMEI=") })
        assertTrue(content.contains("ATTESTATION_ID_IMEI=$imei"))
        assertTrue(content.contains("ATTESTATION_ID_IMEI2=$imei2"))
        assertTrue(content.contains("ATTESTATION_ID_MEID=A100000927F4E1"))
        assertFalse(content.contains("ATTESTATION_ID_PHONE_NUMBER="))

        val getResponse = request("GET", "/api/identity")
        assertEquals(200, getResponse.first)
        val saved = JSONObject(getResponse.second)
        assertEquals(imei2, saved.getString("imei2"))
        assertEquals(imsi2, saved.getString("imsi2"))
        assertEquals(iccid2, saved.getString("iccid2"))
        assertEquals("+12025550124", saved.getString("phone_number2"))
        assertEquals("1", saved.getString("visible_sim_count"))
        assertEquals(1, Config.getIdentityOverrides().visibleSimCount)
        assertEquals("3", saved.getString("visible_camera_count"))
        assertEquals(3, Config.getIdentityOverrides().visibleCameraCount)
    }

    @Test
    fun `invalid or unsupported identity input leaves the file unchanged`() {
        val file = File(configDir, "spoof_build_vars")
        file.writeText("MODEL=Keep Me\n")
        Config.updateBuildVars(file)
        val before = file.readText()

        assertEquals(400, postIdentity(JSONObject().put("imei", "123")).first)
        assertEquals(400, postIdentity(JSONObject().put("visible_sim_count", "9")).first)
        assertEquals(400, postIdentity(JSONObject().put("visible_sim_count", "-1")).first)
        assertEquals(400, postIdentity(JSONObject().put("visible_camera_count", "17")).first)
        assertEquals(400, postIdentity(JSONObject().put("visible_camera_count", "-1")).first)
        assertEquals(400, postIdentity(JSONObject().put("unknown", "value")).first)
        assertEquals(before, file.readText())
    }

    @Test
    fun `selected template persists a managed early boot fingerprint block`() {
        val file = File(configDir, "spoof_build_vars").apply { writeText("# User setting\nSERIAL=KEEP_ME\n") }
        Config.updateBuildVars(file)

        assertEquals(200, postIdentity(JSONObject().put("template", "pixel8pro")).first)
        val selected = file.readText()
        assertTrue(selected.contains("# BEGIN CLEVERESTRICKY BUILD IDENTITY"))
        assertTrue(selected.contains("TEMPLATE=pixel8pro"))
        assertTrue(selected.contains("FINGERPRINT=google/husky/husky:14/"))
        assertTrue(selected.contains("SERIAL=KEEP_ME"))

        assertEquals(200, postIdentity(JSONObject().put("template", "")).first)
        val cleared = file.readText()
        assertFalse(cleared.contains("CLEVERESTRICKY BUILD IDENTITY"))
        assertFalse(cleared.contains("TEMPLATE="))
        assertTrue(cleared.contains("SERIAL=KEEP_ME"))
    }

    @Test
    fun `random identity includes every attestation and telephony field`() {
        val response = request("GET", "/api/random_identity")
        assertEquals(200, response.first)
        val json = JSONObject(response.second)
        assertEquals(14, json.getString("meid").length)
        assertEquals(14, json.getString("meid2").length)
        assertTrue(json.getString("phone_number").startsWith("+1"))
        assertTrue(json.getString("phone_number2").startsWith("+1"))
        assertTrue(json.getString("imei").isNotBlank())
        assertTrue(json.getString("iccid2").isNotBlank())
        assertTrue(json.getInt("visible_sim_count") in 1..2)
        assertTrue(json.getInt("visible_camera_count") in 1..4)
    }

    @Test
    fun `auto identity persists build fields without enabling identity or overwriting telephony`() {
        val telephonyImei = RandomUtils.generateLuhn(15, "35")
        val file = File(configDir, "spoof_build_vars")
        file.writeText("ATTESTATION_ID_IMEI=$telephonyImei\n")
        Config.updateBuildVars(file).getOrThrow()

        val response = request("POST", "/api/auto_identity", "")
        assertEquals(200, response.first)
        val data = JSONObject(response.second)
        assertEquals("Pixel Test", data.getString("model"))
        val vars = file.readText()
        assertTrue(vars.contains("FINGERPRINT=google/test_beta/test:CANARY/BP31.260801.001/12345678:user/release-keys"))
        assertTrue(vars.contains("SECURITY_PATCH=2026-08-05"))
        assertTrue(vars.contains("ATTESTATION_ID_IMEI=$telephonyImei"))
        assertFalse(File(configDir, "spoof_build_identity").exists())
        assertFalse(File(configDir, "spoof_enabled").exists())
    }

    @Test
    fun `single random identity field returns only that validated value`() {
        val response = request("GET", "/api/random_identity?field=imei")
        assertEquals(200, response.first)
        val json = JSONObject(response.second)
        assertEquals(1, json.length())
        assertTrue(json.has("imei"))
        assertTrue(Config.isValidBuildVarEntry("ATTESTATION_ID_IMEI", json.getString("imei")))
    }

    @Test
    fun `random identity groups stay within their requested scope`() {
        val sim1Response = request("GET", "/api/random_identity?field=sim1")
        assertEquals(200, sim1Response.first)
        val sim1 = JSONObject(sim1Response.second)
        assertEquals(5, sim1.length())
        assertTrue(sim1.has("imei"))
        assertTrue(sim1.has("imsi"))
        assertTrue(sim1.has("iccid"))
        assertTrue(sim1.has("meid"))
        assertTrue(sim1.has("phone_number"))
        assertFalse(sim1.has("imei2"))
        assertFalse(sim1.has("serial"))

        val sim2Response = request("GET", "/api/random_identity?field=sim2")
        assertEquals(200, sim2Response.first)
        val sim2 = JSONObject(sim2Response.second)
        assertEquals(5, sim2.length())
        assertTrue(sim2.has("imei2"))
        assertTrue(sim2.has("imsi2"))
        assertTrue(sim2.has("iccid2"))
        assertTrue(sim2.has("meid2"))
        assertTrue(sim2.has("phone_number2"))
        assertFalse(sim2.has("imei"))
        assertFalse(sim2.has("serial"))

        val deviceResponse = request("GET", "/api/random_identity?field=device")
        assertEquals(200, deviceResponse.first)
        val device = JSONObject(deviceResponse.second)
        assertEquals(1, device.length())
        assertTrue(Config.isValidBuildVarEntry("ATTESTATION_ID_SERIAL", device.getString("serial")))
    }

    @Test
    fun `telephony random group includes visibility but not device serial`() {
        val response = request("GET", "/api/random_identity?field=telephony")
        assertEquals(200, response.first)
        val json = JSONObject(response.second)
        assertEquals(11, json.length())
        assertTrue(json.has("imei"))
        assertTrue(json.has("imei2"))
        assertTrue(json.getInt("visible_sim_count") in 1..2)
        assertFalse(json.has("serial"))
    }

    @Test
    fun `hardware random group contains only camera visibility`() {
        val response = request("GET", "/api/random_identity?field=hardware")
        assertEquals(200, response.first)
        val json = JSONObject(response.second)
        assertEquals(1, json.length())
        assertTrue(json.getInt("visible_camera_count") in 1..4)
    }

    @Test
    fun `random template returns a bounded known template view`() {
        val response = request("GET", "/api/random_identity?field=template")
        assertEquals(200, response.first)
        val json = JSONObject(response.second)
        assertEquals(5, json.length())
        assertTrue(json.getString("id").isNotBlank())
        assertTrue(json.getString("model").isNotBlank())
        assertTrue(json.getString("manufacturer").isNotBlank())
        assertTrue(json.getString("fingerprint").isNotBlank())
        assertTrue(json.getString("securityPatch").isNotBlank())
    }

    @Test
    fun `unsupported random identity field is rejected`() {
        val response = request("GET", "/api/random_identity?field=unknown")
        assertEquals(400, response.first)
    }

    @Test
    fun `identity API refuses a symbolic link destination`() {
        val destination = File(configDir, "outside.txt").apply { writeText("SAFE") }
        Files.createSymbolicLink(File(configDir, "spoof_build_vars").toPath(), destination.toPath())

        val response = postIdentity(JSONObject().put("serial", "DEVICE01"))
        assertEquals(400, response.first)
        assertEquals("SAFE", destination.readText())
    }

    private fun postIdentity(json: JSONObject): Pair<Int, String> {
        val encoded = URLEncoder.encode(json.toString(), StandardCharsets.UTF_8.name())
        return request("POST", "/api/identity", "data=$encoded")
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
    ): Pair<Int, String> {
        val connection = URL("http://localhost:${server.listeningPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("X-Auth-Token", server.token)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return code to text
    }
}
