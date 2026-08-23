package cleveres.tricky.cleverestech

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CronAutoIdentityTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        Config.reset()
        root = Files.createTempDirectory("cron-auto-identity-test").toFile()
        Config.setRootForTesting(root)
        CronAutoIdentity.configureForTesting(root)
    }

    @After
    fun tearDown() {
        CronAutoIdentity.stop()
        root.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `worker exists only while marker and Build Identity are both enabled`() {
        File(root, CronAutoIdentity.TOGGLE_FILE).writeText("")
        PolicyState.installStateForTesting(state(buildIdentity = false).toString())

        CronAutoIdentity.refreshEnabled()
        assertFalse(CronAutoIdentity.isRunningForTesting())

        PolicyState.installStateForTesting(state(buildIdentity = true).toString())
        CronAutoIdentity.refreshEnabled()
        assertTrue(CronAutoIdentity.isRunningForTesting())

        Files.delete(File(root, CronAutoIdentity.TOGGLE_FILE).toPath())
        CronAutoIdentity.refreshEnabled()
        assertFalse(CronAutoIdentity.isRunningForTesting())
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
