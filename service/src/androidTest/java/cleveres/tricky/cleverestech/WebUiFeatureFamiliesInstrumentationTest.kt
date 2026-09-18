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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64

/** Executes every shared WebUI feature family against the real Android bridge owner. */
@RunWith(AndroidJUnit4::class)
class WebUiFeatureFamiliesInstrumentationTest {
    private lateinit var root: File
    private lateinit var bridge: WebUiBridge
    private lateinit var originalSecureFileImpl: SecureFileOperations

    @Before
    fun setUp() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        root = Files.createTempDirectory(cache.toPath(), "webui-feature-families").toFile().canonicalFile
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
    fun `every editable config validates persists and reads back on Android 17`() {
        EDITABLE_CONFIG_FILES.forEach { filename ->
            val content = editableContent(filename)
            val saved = request("POST", "/api/save", mapOf("filename" to filename, "content" to content))
            assertEquals("save editable config $filename: ${saved.text}", 200, saved.status)
            val readback = request("GET", "/api/file", mapOf("filename" to filename))
            assertEquals("read editable config $filename", 200, readback.status)
            assertEquals("editable config $filename must round trip exactly", content, readback.text)
        }
    }

    @Test
    fun `every WebUI toggle persists and reads back on Android 17`() {
        TOGGLE_SETTINGS.forEach { setting ->
            assertEquals(
                "enable $setting",
                200,
                request("POST", "/api/toggle", mapOf("setting" to setting, "value" to "true")).status,
            )
            assertTrue("$setting marker must exist", File(root, setting).isFile)
            assertTrue("$setting must read back enabled", config().getBoolean(setting))

            assertEquals(
                "disable $setting",
                200,
                request("POST", "/api/toggle", mapOf("setting" to setting, "value" to "false")).status,
            )
            assertFalse("$setting marker must be removed", File(root, setting).exists())
            assertFalse("$setting must read back disabled", config().getBoolean(setting))
        }
    }

    @Test
    fun `every built in profile produces its documented marker state`() {
        PROFILE_MARKERS.forEach { (profile, expected) ->
            assertEquals(
                "apply profile $profile",
                200,
                request("POST", "/api/apply_profile", mapOf("profile" to profile)).status,
            )
            val state = config()
            TOGGLE_SETTINGS.forEach { setting ->
                assertEquals(
                    "$profile must publish expected $setting state",
                    expected.contains(setting),
                    state.getBoolean(setting),
                )
            }
        }
    }

    @Test
    fun `every random identity selector executes on Android 17`() {
        RANDOM_IDENTITY_SELECTORS.forEach { selector ->
            val response = request("GET", "/api/random_identity", mapOf("field" to selector))
            assertEquals("random identity selector $selector: ${response.text}", 200, response.status)
            val json = JSONObject(response.text)
            assertTrue("random identity selector $selector must return data", json.length() > 0)
        }
    }

    @Test
    fun `every V2 policy feature persists reads back and synchronizes compatibility markers`() {
        POLICY_FEATURES.forEach { feature ->
            val state = policy(feature)
            val saved = request("POST", "/api/policy_state", mapOf("data" to state.toString()))
            assertEquals("save policy feature $feature: ${saved.text}", 200, saved.status)
            val savedJson = JSONObject(saved.text)
            assertTrue("saved response must contain enabled $feature", savedJson.getJSONObject("features").getBoolean(feature))

            val readback = request("GET", "/api/policy_state")
            assertEquals("read policy feature $feature", 200, readback.status)
            val readbackJson = JSONObject(readback.text)
            assertTrue("readback must retain enabled $feature", readbackJson.getJSONObject("features").getBoolean(feature))
            assertEquals("compatibility state for $feature", "ok", readbackJson.getString("compatibilitySync"))

            val expected = expectedCompatibilityMarkers(feature)
            COMPATIBILITY_MARKERS.forEach { marker ->
                assertEquals(
                    "$feature compatibility marker $marker",
                    marker in expected,
                    File(root, marker).isFile,
                )
            }
        }
    }

    private fun editableContent(filename: String): String =
        when (filename) {
            "target.txt" -> "com.example.editor\n"
            "identity_target.txt" -> "com.example.identity\n"
            "security_patch.txt" -> "system=2026-08-05\n"
            "spoof_build_vars" -> "MODEL=Pixel API37 Editor\n"
            "app_config" -> "com.example.editor null null redact\n"
            "templates.json" -> "[]"
            "drm_packages.txt" -> "com.example.drm\n"
            "boot_props_mode" -> "auto\n"
            PolicyState.STATE_FILE -> policy("securityPatch").toString()
            else -> error("Missing editable config fixture for $filename")
        }

    private fun config(): JSONObject {
        val response = request("GET", "/api/config")
        assertEquals(200, response.status)
        return JSONObject(response.text)
    }

    private fun policy(enabledFeature: String): JSONObject {
        val features = JSONObject()
        POLICY_FEATURES.forEach { feature -> features.put(feature, feature == enabledFeature) }
        return JSONObject()
            .put("version", 2)
            .put("features", features)
            .put(
                "securityPatch",
                JSONObject()
                    .put("automaticThresholdMonths", 6)
                    .put("system", JSONObject().put("mode", "device_default"))
                    .put("vendor", JSONObject().put("mode", "device_default"))
                    .put("boot", JSONObject().put("mode", "device_default")),
            )
            .put("profiles", JSONArray())
            .put("activeProfile", JSONObject.NULL)
    }

    private fun expectedCompatibilityMarkers(feature: String): Set<String> =
        when (feature) {
            "buildIdentity" -> setOf(LegacyIdentityMarkers.ENGINE, LegacyIdentityMarkers.BUILD)
            "attestationIdentity" -> setOf(LegacyIdentityMarkers.ENGINE)
            "telephonyIdentity" -> setOf(LegacyIdentityMarkers.ENGINE, LegacyIdentityMarkers.TELEPHONY)
            "regionIdentity" -> setOf(LegacyIdentityMarkers.ENGINE, LegacyIdentityMarkers.REGION)
            "identityRefresh" -> setOf(LegacyIdentityMarkers.ENGINE, LegacyIdentityMarkers.REFRESH)
            "securityPatch" -> emptySet()
            else -> error("Unknown policy feature $feature")
        }

    private fun request(
        method: String,
        path: String,
        parameters: Map<String, String> = emptyMap(),
    ): Response {
        val parameterJson = JSONObject()
        parameters.forEach { (key, value) -> parameterJson.put(key, JSONArray().put(value)) }
        val envelope =
            JSONObject()
                .put("version", 1)
                .put("method", method)
                .put("path", path)
                .put("parameters", parameterJson)
        val response =
            JSONObject(
                String(
                    bridge.processRequestBytes(envelope.toString().toByteArray(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8,
                ),
            )
        val bytes =
            response.optString("body")
                .takeIf { it.isNotEmpty() }
                ?.let { Base64.getUrlDecoder().decode(it) }
                ?: ByteArray(0)
        return Response(response.getInt("status"), String(bytes, StandardCharsets.UTF_8))
    }

    private data class Response(val status: Int, val text: String)

    companion object {
        // Kept in lockstep with WebServer.EDITABLE_CONFIG_FILES by the Node coverage guard.
        private val EDITABLE_CONFIG_FILES =
            listOf(
                "target.txt",
                "identity_target.txt",
                "security_patch.txt",
                "spoof_build_vars",
                "app_config",
                "templates.json",
                "drm_packages.txt",
                "boot_props_mode",
                "policy_state_v2.json",
            )

        // Kept in lockstep with WebServer.WEB_UI_SETTINGS by the Node coverage guard.
        private val TOGGLE_SETTINGS =
            listOf(
                "spoof_enabled",
                "spoof_build_identity",
                "global_mode",
                "auto_keybox_check",
                "random_on_boot",
                "spoof_region_cn",
                "telephony",
                "camera_visibility",
                "drm_passthrough",
                "global_identity_mode",
                "block_invalid_keyboxes",
            )

        private val PROFILE_MARKERS =
            linkedMapOf(
                "maximum" to setOf(
                    "spoof_enabled",
                    "spoof_build_identity",
                    "global_mode",
                    "auto_keybox_check",
                    "random_on_boot",
                    "telephony",
                    "global_identity_mode",
                    "block_invalid_keyboxes",
                ),
                "daily" to setOf(
                    "spoof_enabled",
                    "auto_keybox_check",
                    "drm_passthrough",
                    "block_invalid_keyboxes",
                ),
                "minimal" to setOf("drm_passthrough"),
                "default" to setOf("global_mode", "auto_keybox_check", "block_invalid_keyboxes"),
            )

        private val RANDOM_IDENTITY_SELECTORS =
            listOf(
                "all",
                "template",
                "sim1",
                "sim2",
                "telephony",
                "device",
                "hardware",
                "imei",
                "imei2",
                "imsi",
                "imsi2",
                "iccid",
                "iccid2",
                "meid",
                "meid2",
                "phone_number",
                "phone_number2",
                "serial",
                "visible_sim_count",
                "visible_camera_count",
            )

        private val POLICY_FEATURES =
            listOf(
                "buildIdentity",
                "attestationIdentity",
                "telephonyIdentity",
                "regionIdentity",
                "identityRefresh",
                "securityPatch",
            )

        private val COMPATIBILITY_MARKERS =
            setOf(
                LegacyIdentityMarkers.ENGINE,
                LegacyIdentityMarkers.BUILD,
                LegacyIdentityMarkers.TELEPHONY,
                LegacyIdentityMarkers.REGION,
                LegacyIdentityMarkers.REFRESH,
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
