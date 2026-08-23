package cleveres.tricky.cleverestech

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProfileEnablementTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        Config.reset()
        root = Files.createTempDirectory("profile-enablement-test").toFile()
        Config.setRootForTesting(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `disabled profile remains persisted but cannot affect matching app`() {
        val profile =
            profile("Banking", enabled = false)
                .put("applications", JSONArray().put("com.example.bank"))
                .put("privacy", "isolate")
                .put("features", JSONObject().put("telephonyIdentity", true))
        PolicyState.installStateForTesting(state(profile).toString())
        Config.setPackagesForTesting(20_001, arrayOf("com.example.bank"))

        assertFalse(PolicyState.isFeatureEnabled(PolicyState.Feature.TELEPHONY_IDENTITY, 20_001))
        assertFalse(PolicyState.hasTelephonyProfileWork())
        assertFalse(PolicyState.hasDrmProfileWork())

        val effective = PolicyState.effectiveStateJson("com.example.bank")
        assertTrue(effective.isNull("matchedProfile"))
        assertEquals("unmatched", effective.getString("scope"))

        val persisted = PolicyState.stateJson().getJSONArray("profiles").getJSONObject(0)
        assertFalse(persisted.getBoolean("enabled"))
    }

    @Test
    fun `legacy profile without enabled field stays enabled for backward compatibility`() {
        val profile =
            profile("Legacy", enabled = null)
                .put("applications", JSONArray().put("com.example.legacy"))
                .put("features", JSONObject().put("telephonyIdentity", true))
        PolicyState.installStateForTesting(state(profile).toString())
        Config.setPackagesForTesting(20_002, arrayOf("com.example.legacy"))

        assertTrue(PolicyState.isFeatureEnabled(PolicyState.Feature.TELEPHONY_IDENTITY, 20_002))
        assertEquals("Legacy", PolicyState.effectiveStateJson("com.example.legacy").getString("matchedProfile"))
        assertTrue(PolicyState.stateJson().getJSONArray("profiles").getJSONObject(0).getBoolean("enabled"))
    }

    @Test
    fun `profile Auto Identity overrides a fixed template only while Build Identity is effective`() {
        val dynamic =
            profile("Dynamic", enabled = true)
                .put("applications", JSONArray().put("com.example.dynamic"))
                .put("template", "fixed_pixel")
                .put(
                    "features",
                    JSONObject()
                        .put("buildIdentity", true)
                        .put("identityRefresh", true),
                )
        PolicyState.installStateForTesting(state(dynamic).toString())
        Config.setPackagesForTesting(20_003, arrayOf("com.example.dynamic"))

        assertTrue(PolicyState.isProfileAutoIdentityEnabled(20_003))
        assertNull(PolicyState.resolveAppConfig(20_003, null))
        val dynamicEffective = PolicyState.effectiveStateJson("com.example.dynamic")
        assertTrue(dynamicEffective.isNull("identityTemplate"))
        assertEquals("auto_identity", dynamicEffective.getString("identitySource"))

        val inactive =
            profile("Inactive", enabled = true)
                .put("applications", JSONArray().put("com.example.inactive"))
                .put("template", "fixed_pixel")
                .put(
                    "features",
                    JSONObject()
                        .put("buildIdentity", false)
                        .put("identityRefresh", true),
                )
        PolicyState.installStateForTesting(state(inactive).toString())
        Config.setPackagesForTesting(20_004, arrayOf("com.example.inactive"))

        assertFalse(PolicyState.isProfileAutoIdentityEnabled(20_004))
        assertEquals("fixed_pixel", PolicyState.resolveAppConfig(20_004, null)?.template)
        val inactiveEffective = PolicyState.effectiveStateJson("com.example.inactive")
        assertEquals("fixed_pixel", inactiveEffective.getString("identityTemplate"))
        assertEquals("template", inactiveEffective.getString("identitySource"))
    }

    private fun profile(
        name: String,
        enabled: Boolean?,
    ): JSONObject =
        JSONObject()
            .put("name", name)
            .apply { if (enabled != null) put("enabled", enabled) }
            .put("applications", JSONArray())
            .put("template", JSONObject.NULL)
            .put("keybox", JSONObject.NULL)
            .put("privacy", "inherit")
            .put("features", JSONObject())
            .put("securityPatch", JSONObject())
            .put("rkpPassthrough", JSONObject.NULL)
            .put("drmPassthrough", JSONObject.NULL)

    private fun state(profile: JSONObject): JSONObject =
        JSONObject()
            .put("version", PolicyState.SCHEMA_VERSION)
            .put(
                "features",
                JSONObject()
                    .put("buildIdentity", false)
                    .put("attestationIdentity", false)
                    .put("telephonyIdentity", false)
                    .put("regionIdentity", false)
                    .put("identityRefresh", false)
                    .put("securityPatch", false),
            )
            .put(
                "securityPatch",
                JSONObject()
                    .put("automaticThresholdMonths", 6)
                    .put("system", JSONObject().put("mode", "automatic"))
                    .put("vendor", JSONObject().put("mode", "automatic"))
                    .put("boot", JSONObject().put("mode", "automatic")),
            )
            .put("profiles", JSONArray().put(profile))
            .put("activeProfile", JSONObject.NULL)
}
