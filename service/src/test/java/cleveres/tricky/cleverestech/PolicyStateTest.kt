package cleveres.tricky.cleverestech

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

class PolicyStateTest {
    private lateinit var root: File
    private lateinit var originalSystemPropertiesGet: (String, String?) -> String?

    @Before
    fun setUp() {
        Config.reset()
        originalSystemPropertiesGet = systemPropertiesGet
        root = Files.createTempDirectory("policy-state-test").toFile()
        Config.setRootForTesting(root)
        PolicyState.currentDateSource = { LocalDate.of(2026, 8, 12) }
        systemPropertiesGet = { _, default -> default }
    }

    @After
    fun tearDown() {
        systemPropertiesGet = originalSystemPropertiesGet
        PolicyState.currentDateSource = { LocalDate.now() }
        root.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `security patch disabled preserves captured values`() {
        install(features = featureJson(build = true, patch = false))
        cachePackages(10_001, arrayOf("com.example.app"))

        val levels = PolicyState.resolveAttestationPatchLevels(10_001, 202601, 20260105, 20260105)

        assertEquals(Config.PatchDisposition.KEEP, levels.system.disposition)
        assertEquals(Config.PatchDisposition.KEEP, levels.vendor.disposition)
        assertEquals(Config.PatchDisposition.KEEP, levels.boot.disposition)
        assertTrue(PolicyState.isFeatureEnabled(PolicyState.Feature.BUILD_IDENTITY, 10_001))
        assertFalse(PolicyState.isFeatureEnabled(PolicyState.Feature.SECURITY_PATCH, 10_001))
    }

    @Test
    fun `security patch can run while build identity is disabled`() {
        install(
            features = featureJson(build = false, patch = true),
            system = patch("manual", "2026-07-05"),
        )
        cachePackages(10_002, arrayOf("com.example.app"))

        val levels = PolicyState.resolveAttestationPatchLevels(10_002, 202601, 20260105, 20260105)

        assertFalse(PolicyState.isFeatureEnabled(PolicyState.Feature.BUILD_IDENTITY, 10_002))
        assertTrue(PolicyState.isFeatureEnabled(PolicyState.Feature.SECURITY_PATCH, 10_002))
        assertEquals(Config.PatchDisposition.REPLACE, levels.system.disposition)
        assertEquals(202607, levels.system.value)
        assertEquals(Config.PatchDisposition.KEEP, levels.vendor.disposition)
        assertEquals(Config.PatchDisposition.KEEP, levels.boot.disposition)
    }

    @Test
    fun `manual device default and omit are independent`() {
        install(
            system = patch("manual", "2025-11-05"),
            vendor = patch("device_default"),
            boot = patch("no"),
        )
        cachePackages(10_003, arrayOf("com.example.app"))

        val levels = PolicyState.resolveAttestationPatchLevels(10_003, 202501, 20250105, 20250105)

        assertEquals(Config.PatchDisposition.REPLACE, levels.system.disposition)
        assertEquals(202511, levels.system.value)
        assertEquals(Config.PatchDisposition.KEEP, levels.vendor.disposition)
        assertEquals(Config.PatchDisposition.OMIT, levels.boot.disposition)
    }

    @Test
    fun `property mode reads each partition independently`() {
        install(
            system = patch("prop"),
            vendor = patch("prop"),
            boot = patch("prop"),
        )
        cachePackages(10_004, arrayOf("com.example.app"))
        systemPropertiesGet = { key, default ->
            when (key) {
                "ro.build.version.security_patch" -> "2026-04-05"
                "ro.vendor.build.security_patch" -> "2026-05-05"
                "ro.bootimage.build.version.security_patch" -> "2026-06-05"
                else -> default
            }
        }

        val levels = PolicyState.resolveAttestationPatchLevels(10_004, null, null, null)

        assertEquals(202604, levels.system.value)
        assertEquals(20260505, levels.vendor.value)
        assertEquals(20260605, levels.boot.value)
    }

    @Test
    fun `malformed and missing properties preserve captured values`() {
        install(
            system = patch("prop"),
            vendor = patch("prop"),
            boot = patch("prop"),
        )
        cachePackages(10_005, arrayOf("com.example.app"))
        systemPropertiesGet = { key, default ->
            when (key) {
                "ro.build.version.security_patch" -> "2026-99-99"
                "ro.vendor.build.security_patch" -> "not-a-date"
                else -> default
            }
        }

        val levels = PolicyState.resolveAttestationPatchLevels(10_005, 202601, 20260105, 20260105)

        assertEquals(Config.PatchDisposition.KEEP, levels.system.disposition)
        assertEquals(Config.PatchDisposition.KEEP, levels.vendor.disposition)
        assertEquals(Config.PatchDisposition.KEEP, levels.boot.disposition)
    }

    @Test
    fun `automatic mode advances stale captured patch to previous month day five`() {
        install(
            system = patch("automatic"),
            vendor = patch("automatic"),
            boot = patch("automatic"),
        )
        cachePackages(10_006, arrayOf("com.example.app"))

        val levels = PolicyState.resolveAttestationPatchLevels(10_006, 202501, 20250105, 20260605)

        assertEquals(Config.PatchDisposition.REPLACE, levels.system.disposition)
        assertEquals(202607, levels.system.value)
        assertEquals(Config.PatchDisposition.REPLACE, levels.vendor.disposition)
        assertEquals(20260705, levels.vendor.value)
        assertEquals(Config.PatchDisposition.KEEP, levels.boot.disposition)
    }

    @Test
    fun `automatic mode handles january rollover`() {
        PolicyState.currentDateSource = { LocalDate.of(2026, 1, 10) }
        install(
            system = patch("automatic"),
            vendor = patch("automatic"),
            boot = patch("device_default"),
        )
        cachePackages(10_007, arrayOf("com.example.app"))

        val levels = PolicyState.resolveAttestationPatchLevels(10_007, 202401, 20240105, 20240105)

        assertEquals(202512, levels.system.value)
        assertEquals(20251205, levels.vendor.value)
    }

    @Test
    fun `automatic mode uses leap year calendar arithmetic`() {
        PolicyState.currentDateSource = { LocalDate.of(2024, 3, 1) }
        install(
            thresholdMonths = 1,
            system = patch("device_default"),
            vendor = patch("automatic"),
            boot = patch("device_default"),
        )
        cachePackages(10_008, arrayOf("com.example.app"))

        val levels = PolicyState.resolveAttestationPatchLevels(10_008, 202401, 20230105, 20240105)

        assertEquals(20240205, levels.vendor.value)
    }

    @Test
    fun `automatic mode keeps recent captured patch`() {
        install(
            system = patch("automatic"),
            vendor = patch("automatic"),
            boot = patch("automatic"),
        )
        cachePackages(10_009, arrayOf("com.example.app"))

        val levels = PolicyState.resolveAttestationPatchLevels(10_009, 202607, 20260705, 20260705)

        assertEquals(Config.PatchDisposition.KEEP, levels.system.disposition)
        assertEquals(Config.PatchDisposition.KEEP, levels.vendor.disposition)
        assertEquals(Config.PatchDisposition.KEEP, levels.boot.disposition)
    }

    @Test
    fun `automatic mode can use matching property when captured value is absent`() {
        install(
            system = patch("device_default"),
            vendor = patch("automatic"),
            boot = patch("device_default"),
        )
        cachePackages(10_010, arrayOf("com.example.app"))
        systemPropertiesGet = { key, default ->
            if (key == "ro.vendor.build.security_patch") "2024-02-05" else default
        }

        val levels = PolicyState.resolveAttestationPatchLevels(10_010, null, null, null)

        assertEquals(Config.PatchDisposition.REPLACE, levels.vendor.disposition)
        assertEquals(20260705, levels.vendor.value)
    }

    @Test
    fun `manual patch validation rejects invalid dates`() {
        val invalid = stateJson(system = patch("manual", "2026-02-30"))

        assertTrue(PolicyState.validateStateJson(invalid.toString(), false).isFailure)
    }

    @Test
    fun `unknown patch mode is rejected`() {
        val invalid = stateJson(system = patch("future"))

        assertTrue(PolicyState.validateStateJson(invalid.toString(), false).isFailure)
    }

    @Test
    fun `profile assignment overrides only configured feature and patch`() {
        val profile =
            JSONObject()
                .put("name", "Banking Test")
                .put("applications", JSONArray().put("com.example.bank"))
                .put("privacy", "inherit")
                .put("features", JSONObject().put("buildIdentity", false).put("securityPatch", true))
                .put("securityPatch", JSONObject().put("vendor", patch("manual", "2026-06-05")))
        install(features = featureJson(build = true, patch = false), profiles = JSONArray().put(profile))
        cachePackages(10_011, arrayOf("com.example.bank"))

        val levels = PolicyState.resolveAttestationPatchLevels(10_011, 202601, 20260105, 20260105)

        assertFalse(PolicyState.isFeatureEnabled(PolicyState.Feature.BUILD_IDENTITY, 10_011))
        assertTrue(PolicyState.isFeatureEnabled(PolicyState.Feature.SECURITY_PATCH, 10_011))
        assertEquals(Config.PatchDisposition.KEEP, levels.system.disposition)
        assertEquals(20260605, levels.vendor.value)
        assertEquals(Config.PatchDisposition.KEEP, levels.boot.disposition)
    }

    @Test
    fun `shared uid profile resolution is deterministic`() {
        val first =
            JSONObject()
                .put("name", "Alpha")
                .put("applications", JSONArray().put("com.alpha.app"))
                .put("privacy", "inherit")
                .put("features", JSONObject().put("telephonyIdentity", false))
        val second =
            JSONObject()
                .put("name", "Zulu")
                .put("applications", JSONArray().put("com.zulu.app"))
                .put("privacy", "inherit")
                .put("features", JSONObject().put("telephonyIdentity", true))
        install(
            features = featureJson(telephony = true),
            profiles = JSONArray().put(second).put(first),
        )
        cachePackages(10_012, arrayOf("com.zulu.app", "com.alpha.app"))

        assertFalse(PolicyState.isFeatureEnabled(PolicyState.Feature.TELEPHONY_IDENTITY, 10_012))
        cachePackages(10_012, arrayOf("com.alpha.app", "com.zulu.app"))
        PolicyState.invalidateUid(10_012)
        assertFalse(PolicyState.isFeatureEnabled(PolicyState.Feature.TELEPHONY_IDENTITY, 10_012))
    }

    @Test
    fun `invalid transaction does not replace active snapshot`() {
        install(features = featureJson(build = true, patch = false))
        val before = PolicyState.stateJson().getJSONObject("features").getBoolean("buildIdentity")
        val invalid = stateJson(features = featureJson(build = false, patch = false)).put("unexpected", true)

        val result = PolicyState.replaceFromJson(invalid.toString())

        assertTrue(result.isFailure)
        assertEquals(before, PolicyState.stateJson().getJSONObject("features").getBoolean("buildIdentity"))
    }

    @Test
    fun `last known good snapshot is retained and recovered`() {
        val first = stateJson(features = featureJson(build = true, patch = false))
        val second = stateJson(features = featureJson(build = false, patch = true))
        assertTrue(PolicyState.replaceFromJson(first.toString()).isSuccess)
        assertTrue(PolicyState.replaceFromJson(second.toString()).isSuccess)
        File(root, PolicyState.STATE_FILE).writeText("{")

        assertTrue(PolicyState.reload().isSuccess)
        val recovered = PolicyState.stateJson()
        assertEquals("last_known_good", recovered.getString("recovery"))
        assertTrue(recovered.getJSONObject("features").getBoolean("buildIdentity"))
        assertFalse(recovered.getJSONObject("features").getBoolean("securityPatch"))
    }

    @Test
    fun `symlink policy input is not followed`() {
        val outside = File(root.parentFile, "policy-outside-${System.nanoTime()}.json")
        outside.writeText(stateJson().toString())
        val link = File(root, PolicyState.STATE_FILE)
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (_: UnsupportedOperationException) {
            outside.delete()
            return
        }

        val result = PolicyState.reload()

        assertTrue(result.isSuccess)
        assertEquals("legacy", PolicyState.stateJson().getString("source"))
        outside.delete()
    }

    @Test
    fun `oversized and unknown fields are rejected`() {
        val unknown = stateJson().put("unknown", true)
        val oversizedName = "x".repeat(70)
        val profile =
            JSONObject()
                .put("name", oversizedName)
                .put("applications", JSONArray())
                .put("privacy", "inherit")
                .put("features", JSONObject())
                .put("securityPatch", JSONObject())
        val oversized = stateJson(profiles = JSONArray().put(profile))

        assertTrue(PolicyState.validateStateJson(unknown.toString(), false).isFailure)
        assertTrue(PolicyState.validateStateJson(oversized.toString(), false).isFailure)
    }

    private fun install(
        features: JSONObject = featureJson(),
        thresholdMonths: Int = 6,
        system: JSONObject = patch("device_default"),
        vendor: JSONObject = patch("device_default"),
        boot: JSONObject = patch("device_default"),
        profiles: JSONArray = JSONArray(),
    ) {
        PolicyState.installStateForTesting(
            stateJson(features, thresholdMonths, system, vendor, boot, profiles).toString(),
        )
    }

    private fun stateJson(
        features: JSONObject = featureJson(),
        thresholdMonths: Int = 6,
        system: JSONObject = patch("device_default"),
        vendor: JSONObject = patch("device_default"),
        boot: JSONObject = patch("device_default"),
        profiles: JSONArray = JSONArray(),
    ): JSONObject =
        JSONObject()
            .put("version", PolicyState.SCHEMA_VERSION)
            .put("features", features)
            .put(
                "securityPatch",
                JSONObject()
                    .put("automaticThresholdMonths", thresholdMonths)
                    .put("system", system)
                    .put("vendor", vendor)
                    .put("boot", boot),
            )
            .put("profiles", profiles)
            .put("activeProfile", JSONObject.NULL)

    private fun featureJson(
        build: Boolean = false,
        attestation: Boolean = false,
        telephony: Boolean = false,
        region: Boolean = false,
        refresh: Boolean = false,
        patch: Boolean = true,
    ): JSONObject =
        JSONObject()
            .put("buildIdentity", build)
            .put("attestationIdentity", attestation)
            .put("telephonyIdentity", telephony)
            .put("regionIdentity", region)
            .put("identityRefresh", refresh)
            .put("securityPatch", patch)

    private fun patch(
        mode: String,
        value: String? = null,
    ): JSONObject =
        JSONObject().put("mode", mode).also { objectValue ->
            if (value != null) objectValue.put("value", value)
        }

    private fun cachePackages(
        uid: Int,
        packages: Array<String>,
    ) {
        val cachedPackageClass = Class.forName("cleveres.tricky.cleverestech.Config\$CachedPackage")
        val constructor =
            cachedPackageClass.getDeclaredConstructor(Array<String>::class.java, Long::class.javaPrimitiveType)
        constructor.isAccessible = true
        val cached = constructor.newInstance(packages, System.currentTimeMillis())
        val field = Config::class.java.getDeclaredField("packageCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(Config) as ConcurrentHashMap<Int, Any>
        cache[uid] = cached
    }
}