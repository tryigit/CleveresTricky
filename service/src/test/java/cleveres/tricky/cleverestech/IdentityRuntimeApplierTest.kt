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

class IdentityRuntimeApplierTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        Config.reset()
        root = Files.createTempDirectory("identity-runtime-applier-test").toFile()
        Config.setRootForTesting(root)
        File(root, "spoof_build_vars").writeText(
            "FINGERPRINT=google/test/device:16/TEST/1:user/release-keys\n",
        )
        Config.updateBuildVars(File(root, "spoof_build_vars")).getOrThrow()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `disabled Build Identity never touches the live property path`() {
        PolicyState.installStateForTesting(state(buildIdentity = false).toString())

        val result = IdentityRuntimeApplier.apply(root)

        assertFalse(result.applied)
        assertFalse(result.rebootRequired)
    }

    @Test
    fun `active profile Build Identity never authorizes device-wide live apply`() {
        val activeProfile =
            JSONObject()
                .put("name", "Active")
                .put("enabled", true)
                .put("applications", JSONArray())
                .put("template", JSONObject.NULL)
                .put("keybox", JSONObject.NULL)
                .put("privacy", "inherit")
                .put("features", JSONObject().put("buildIdentity", true))
                .put("securityPatch", JSONObject())
                .put("rkpPassthrough", JSONObject.NULL)
                .put("drmPassthrough", JSONObject.NULL)
        PolicyState.installStateForTesting(
            state(buildIdentity = false)
                .put("profiles", JSONArray().put(activeProfile))
                .put("activeProfile", "Active")
                .toString(),
        )

        val result = IdentityRuntimeApplier.apply(root)

        assertFalse(result.applied)
        assertFalse(result.rebootRequired)
        assertEquals("disabled", result.reason)
    }

    @Test
    fun `missing production shell path reports reboot instead of pretending live success`() {
        PolicyState.installStateForTesting(state(buildIdentity = true).toString())

        val result = IdentityRuntimeApplier.apply(root)

        assertFalse(result.applied)
        assertTrue(result.rebootRequired)
    }

    private fun state(buildIdentity: Boolean): JSONObject =
        JSONObject()
            .put("version", PolicyState.SCHEMA_VERSION)
            .put(
                "features",
                JSONObject()
                    .put("buildIdentity", buildIdentity)
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
            .put("profiles", JSONArray())
            .put("activeProfile", JSONObject.NULL)
}
