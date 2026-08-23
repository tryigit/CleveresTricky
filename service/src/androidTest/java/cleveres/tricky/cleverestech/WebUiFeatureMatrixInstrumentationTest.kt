package cleveres.tricky.cleverestech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64

/**
 * Android-platform contract for every exported WebUI backend route.
 *
 * The native KernelSU/APatch WebUI owns the HTML/JS. The Android service owns the
 * bounded UDS bridge and API implementation. This suite therefore exercises the
 * real WebUiBridge -> WebServer/PolicyApi path instead of the retired loopback
 * HTML server path.
 */
@RunWith(AndroidJUnit4::class)
class WebUiFeatureMatrixInstrumentationTest {
    private lateinit var root: File
    private lateinit var bridge: WebUiBridge
    private lateinit var originalSecureFileImpl: SecureFileOperations

    @Before
    fun setUp() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        root = Files.createTempDirectory(cache.toPath(), "webui-feature-matrix").toFile().canonicalFile
        originalSecureFileImpl = SecureFile.impl
        SecureFile.impl = SecureFile.DefaultSecureFileOperations()
        Config.reset()
        KeyboxLoader.activeSetOverride = { true }
        Config.setRootForTesting(root)
        Config.initialize()
        KernelIdentityManager.initialize(root)
        bridge =
            WebUiBridge(
                WebServer(
                    0,
                    root,
                    crlFetcher = { emptySet() },
                    autoIdentityFetcher = { deterministicAutoIdentity() },
                ),
                root,
            )
    }

    @After
    fun tearDown() {
        Config.reset()
        SecureFile.impl = originalSecureFileImpl
        root.deleteRecursively()
    }

    @Test
    fun `every exported WebUI feature route is reachable on Android 17`() {
        FEATURE_CASES.forEach { case ->
            val response = request(case.method, case.path, case.parameters)
            assertTrue(
                "${case.method} ${case.path} returned unexpected ${response.status}: ${response.text}",
                response.status in case.allowedStatuses,
            )
            if (404 !in case.allowedStatuses) {
                assertNotEquals(
                    "${case.method} ${case.path} must never fall through to an unhandled route",
                    404,
                    response.status,
                )
            }
            assertNotEquals(
                "${case.method} ${case.path} must not crash the Android service",
                500,
                response.status,
            )
        }
    }

    @Test
    fun `safe mutable feature surfaces round trip through production bridge`() {
        assertEquals(200, request("POST", "/api/toggle", mapOf("setting" to "telephony", "value" to "true")).status)
        assertTrue(File(root, "telephony").isFile)
        assertTrue(JSONObject(request("GET", "/api/config").text).getBoolean("telephony"))

        val identity =
            JSONObject()
                .put("serial", "CTEMU17SERIAL")
                .put("imei", "490154203237518")
                .put("visible_sim_count", "2")
                .put("visible_camera_count", "3")
        assertEquals(200, request("POST", "/api/identity", mapOf("data" to identity.toString())).status)
        val identityReadback = JSONObject(request("GET", "/api/identity").text)
        assertEquals("CTEMU17SERIAL", identityReadback.getString("serial"))
        assertEquals("490154203237518", identityReadback.getString("imei"))
        assertEquals("2", identityReadback.getString("visible_sim_count"))
        assertEquals("3", identityReadback.getString("visible_camera_count"))

        val installedPackages = JSONArray(request("GET", "/api/packages").text)
        val targetPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        assertTrue("Android 17 package enumeration must not collapse to an empty list", installedPackages.length() > 0)
        assertTrue(
            "Android 17 package enumeration must include the target app $targetPackage",
            (0 until installedPackages.length()).any { index -> installedPackages.optString(index) == targetPackage },
        )

        val rules =
            JSONArray().put(
                JSONObject()
                    .put("package", "com.example.matrix")
                    .put("template", "")
                    .put("keybox", "")
                    .put("privacy", "redact"),
            )
        assertEquals(200, request("POST", "/api/app_config_structured", mapOf("data" to rules.toString())).status)
        val ruleReadback = JSONArray(request("GET", "/api/app_config_structured").text)
        assertEquals(1, ruleReadback.length())
        assertEquals("com.example.matrix", ruleReadback.getJSONObject(0).getString("package"))
        assertEquals("redact", ruleReadback.getJSONObject(0).getString("privacy"))

        val targetText = "com.example.matrix\ncom.example.second!\n"
        assertEquals(
            200,
            request("POST", "/api/save", mapOf("filename" to "target.txt", "content" to targetText)).status,
        )
        assertEquals(targetText, request("GET", "/api/file", mapOf("filename" to "target.txt")).text)

        val profile =
            JSONObject()
                .put("name", "API37 Matrix")
                .put("applications", JSONArray().put("com.example.matrix"))
                .put("privacy", "redact")
                .put("features", JSONObject().put("telephonyIdentity", true))
        assertEquals(
            200,
            request(
                "POST",
                "/api/profile_v2",
                mapOf("action" to "create", "data" to JSONObject().put("profile", profile).toString()),
            ).status,
        )
        assertEquals(
            200,
            request(
                "POST",
                "/api/profile_v2",
                mapOf("action" to "activate", "data" to JSONObject().put("name", "API37 Matrix").toString()),
            ).status,
        )
        val effective = JSONObject(request("GET", "/api/effective_state", mapOf("package" to "com.example.matrix")).text)
        assertEquals("API37 Matrix", effective.getString("matchedProfile"))
        assertTrue(effective.getBoolean("telephonyIdentity"))
        assertEquals("redact", effective.getString("privacy"))

        val engineMarker = File(root, LegacyIdentityMarkers.ENGINE)
        assertTrue(engineMarker.isFile)
        assertTrue("stale marker injection failed", engineMarker.delete())
        assertFalse(engineMarker.exists())
        val reconciled = JSONObject(request("GET", "/api/policy_state").text)
        assertEquals("ok", reconciled.getString("compatibilitySync"))
        assertTrue("canonical policy read must heal stale compatibility markers", engineMarker.isFile)

        val buildIdentityMarker = File(root, "spoof_build_identity")
        val buildIdentityBefore =
            JSONObject(request("GET", "/api/policy_state").text)
                .getJSONObject("features")
                .getBoolean("buildIdentity")
        assertFalse(buildIdentityBefore)
        assertFalse(buildIdentityMarker.exists())

        val auto = request("POST", "/api/auto_identity")
        assertEquals(200, auto.status)
        val autoJson = JSONObject(auto.text)
        assertEquals("Pixel API37", autoJson.getString("model"))
        assertEquals("google/api37/api37:17/CT37/1234567:user/release-keys", autoJson.getString("fingerprint"))
        assertFalse("manual Auto Identity must not enable Build Identity", buildIdentityMarker.exists())
        val buildIdentityAfter =
            JSONObject(request("GET", "/api/policy_state").text)
                .getJSONObject("features")
                .getBoolean("buildIdentity")
        assertEquals(buildIdentityBefore, buildIdentityAfter)
        assertEquals("Pixel API37", readBuildVar("MODEL"))
        assertEquals("google/api37/api37:17/CT37/1234567:user/release-keys", readBuildVar("FINGERPRINT"))
    }

    @Test
    fun `native crypto outage fails closed without mutating configuration`() {
        val before = "com.example.before\n"
        assertEquals(
            200,
            request("POST", "/api/save", mapOf("filename" to "target.txt", "content" to before)).status,
        )

        val backup = request("POST", "/api/backup", mapOf("pw" to BACKUP_PASSWORD))
        assertEquals("backup outage response: ${backup.text}", 500, backup.status)
        assertEquals("Encrypted backup failed", backup.text)
        assertEquals(before, request("GET", "/api/file", mapOf("filename" to "target.txt")).text)

        val after = "com.example.after\n"
        assertEquals(
            200,
            request("POST", "/api/save", mapOf("filename" to "target.txt", "content" to after)).status,
        )

        val uploadId = "0123456789abcdef0123456789abcdef"
        val staging = File(root, "webui_bridge/staging")
        assertTrue(staging.mkdirs() || staging.isDirectory)
        val staged = File(staging, "$uploadId.upload")
        val encrypted = Base64.getDecoder().decode(CTSB_V2)
        try {
            staged.writeBytes(encrypted)
        } finally {
            encrypted.fill(0)
        }
        val restored =
            request(
                "POST",
                "/api/restore",
                mapOf("pw" to BACKUP_PASSWORD),
                uploadId = uploadId,
                uploadField = "file",
            )
        assertEquals("restore outage response: ${restored.text}", 503, restored.status)
        assertEquals("Rust backend unavailable; restore not applied", restored.text)
        assertEquals(after, request("GET", "/api/file", mapOf("filename" to "target.txt")).text)
        assertFalse("bridge must clean staged restore payloads", staged.exists())
    }

    @Test
    fun `bridge rejects non API and malformed requests before dispatch`() {
        val nonApi = rawRequest("GET", "/index.html", emptyMap())
        assertEquals(400, nonApi.status)

        val invalidVersion =
            JSONObject()
                .put("version", 999)
                .put("method", "GET")
                .put("path", "/api/config")
                .put("parameters", JSONObject())
        val response = decodeEnvelope(bridge.processRequestBytes(invalidVersion.toString().toByteArray(StandardCharsets.UTF_8)))
        assertEquals(400, response.status)
    }

    private fun readBuildVar(key: String): String {
        val file = File(root, "spoof_build_vars")
        assertTrue("spoof_build_vars must exist", file.isFile)
        return file.readLines()
            .firstOrNull { it.substringBefore('=', "").trim() == key }
            ?.substringAfter('=', "")
            ?.trim()
            .orEmpty()
    }

    private fun request(
        method: String,
        path: String,
        parameters: Map<String, String> = emptyMap(),
        uploadId: String? = null,
        uploadField: String? = null,
    ): BridgeResponse = rawRequest(method, path, parameters, uploadId, uploadField)

    private fun rawRequest(
        method: String,
        path: String,
        parameters: Map<String, String>,
        uploadId: String? = null,
        uploadField: String? = null,
    ): BridgeResponse {
        val parameterJson = JSONObject()
        parameters.forEach { (key, value) -> parameterJson.put(key, JSONArray().put(value)) }
        val envelope =
            JSONObject()
                .put("version", 1)
                .put("method", method)
                .put("path", path)
                .put("parameters", parameterJson)
        if (uploadId != null) envelope.put("uploadId", uploadId)
        if (uploadField != null) envelope.put("uploadField", uploadField)
        return decodeEnvelope(bridge.processRequestBytes(envelope.toString().toByteArray(StandardCharsets.UTF_8)))
    }

    private fun decodeEnvelope(bytes: ByteArray): BridgeResponse {
        val envelope = JSONObject(String(bytes, StandardCharsets.UTF_8))
        val body =
            envelope.optString("body")
                .takeIf { it.isNotEmpty() }
                ?.let { Base64.getUrlDecoder().decode(it) }
                ?: ByteArray(0)
        return BridgeResponse(
            status = envelope.getInt("status"),
            mimeType = envelope.optString("mimeType"),
            body = body,
        )
    }

    private data class BridgeResponse(
        val status: Int,
        val mimeType: String,
        val body: ByteArray,
    ) {
        val text: String
            get() = String(body, StandardCharsets.UTF_8)
    }

    private data class FeatureCase(
        val method: String,
        val path: String,
        val parameters: Map<String, String> = emptyMap(),
        val allowedStatuses: Set<Int> = setOf(200, 400, 403, 503),
    )

    companion object {
        private const val BACKUP_PASSWORD = "correct horse battery staple"
        private const val CTSB_V2 =
            "Q1RTQgAAAAIAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobQrPBYdDdFyqlYeaU/mul01QMGsRn7g0MjLdOskpN97GWZ5fNXsQE5H+FldOlDg4HvENUIQC5rexM7K0B5tNer0Cjko6vCq2Z"

        private val VALID_POLICY =
            JSONObject()
                .put("version", 2)
                .put(
                    "features",
                    JSONObject()
                        .put("buildIdentity", false)
                        .put("attestationIdentity", false)
                        .put("telephonyIdentity", false)
                        .put("regionIdentity", false)
                        .put("identityRefresh", false)
                        .put("securityPatch", false),
                ).put(
                    "securityPatch",
                    JSONObject()
                        .put("automaticThresholdMonths", 6)
                        .put("system", JSONObject().put("mode", "device_default"))
                        .put("vendor", JSONObject().put("mode", "device_default"))
                        .put("boot", JSONObject().put("mode", "device_default")),
                ).put("profiles", JSONArray())
                .put("activeProfile", JSONObject.NULL)
                .toString()

        // Keep one executable Android-platform case for every exported route.
        // module/webui-tests/api37_feature_matrix_coverage.test.js fails CI if
        // production adds an API route that is absent here.
        private val FEATURE_CASES =
            listOf(
                FeatureCase("GET", "/api/policy_state"),
                FeatureCase("POST", "/api/policy_state", mapOf("data" to VALID_POLICY)),
                FeatureCase("GET", "/api/effective_state", mapOf("package" to "com.example.matrix")),
                FeatureCase("POST", "/api/profile_v2", mapOf("action" to "deactivate", "data" to "{}")),
                FeatureCase("GET", "/api/config"),
                FeatureCase("GET", "/api/keyboxes"),
                FeatureCase("GET", "/api/keybox_inventory"),
                FeatureCase("GET", "/api/cbox_status"),
                FeatureCase("POST", "/api/unlock_cbox"),
                FeatureCase("GET", "/api/servers"),
                FeatureCase("POST", "/api/server/add"),
                FeatureCase("POST", "/api/server/delete"),
                FeatureCase("POST", "/api/server/refresh"),
                FeatureCase("GET", "/api/kernel_identity"),
                FeatureCase("POST", "/api/kernel_identity"),
                FeatureCase("GET", "/api/templates"),
                FeatureCase("GET", "/api/identity"),
                FeatureCase("POST", "/api/identity"),
                FeatureCase("GET", "/api/random_identity", mapOf("field" to "serial")),
                FeatureCase("POST", "/api/auto_identity"),
                FeatureCase("GET", "/api/packages"),
                FeatureCase("GET", "/api/app_config_structured"),
                FeatureCase("POST", "/api/app_config_structured"),
                FeatureCase("GET", "/api/file", mapOf("filename" to "target.txt")),
                FeatureCase("POST", "/api/save"),
                FeatureCase("POST", "/api/upload_keybox"),
                FeatureCase("POST", "/api/delete_keybox"),
                FeatureCase("POST", "/api/delete_keyboxes"),
                FeatureCase("POST", "/api/verify_keyboxes"),
                FeatureCase("POST", "/api/apply_profile"),
                FeatureCase("POST", "/api/toggle"),
                FeatureCase("POST", "/api/reset_environment"),
                FeatureCase("POST", "/api/reload"),
                FeatureCase("GET", "/api/logs"),
                FeatureCase("POST", "/api/backup"),
                FeatureCase("GET", "/api/language", allowedStatuses = setOf(200, 404)),
                FeatureCase("GET", "/api/resource_usage"),
                FeatureCase("POST", "/api/restore"),
            )

        private fun deterministicAutoIdentity(): AutoIdentityManager.Result =
            AutoIdentityManager.Result(
                model = "Pixel API37",
                product = "api37",
                device = "api37",
                fingerprint = "google/api37/api37:17/CT37/1234567:user/release-keys",
                buildId = "CT37",
                incremental = "1234567",
                release = "17",
                securityPatch = "2026-08-05",
                securityPatchEstimated = false,
            )
    }
}
