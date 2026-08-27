package cleveres.tricky.cleverestech

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun `rapid disable and reenable reuses one scheduler`() {
        val executorField = CronAutoIdentity::class.java.getDeclaredField("executor").apply { isAccessible = true }
        File(root, CronAutoIdentity.TOGGLE_FILE).writeText("")
        PolicyState.installStateForTesting(state(buildIdentity = true).toString())

        try {
            CronAutoIdentity.refreshEnabled()
            val first = executorField.get(CronAutoIdentity)

            Files.delete(File(root, CronAutoIdentity.TOGGLE_FILE).toPath())
            CronAutoIdentity.refreshEnabled()
            assertFalse(CronAutoIdentity.isRunningForTesting())

            File(root, CronAutoIdentity.TOGGLE_FILE).writeText("")
            CronAutoIdentity.refreshEnabled()
            val second = executorField.get(CronAutoIdentity)
            assertSame(first, second)
        } finally {
            CronAutoIdentity.stop()
        }
    }

    @Test
    fun `global worker exists only while marker and Build Identity are both enabled`() {
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

    @Test
    fun `profile Auto Identity starts worker without global cron marker`() {
        val profile =
            JSONObject()
                .put("name", "Banking")
                .put("enabled", true)
                .put("applications", JSONArray().put("com.example.bank"))
                .put("template", JSONObject.NULL)
                .put("keybox", JSONObject.NULL)
                .put("privacy", "inherit")
                .put(
                    "features",
                    JSONObject()
                        .put("buildIdentity", true)
                        .put("identityRefresh", true),
                )
                .put("securityPatch", JSONObject())
                .put("rkpPassthrough", JSONObject.NULL)
                .put("drmPassthrough", JSONObject.NULL)
        PolicyState.installStateForTesting(state(buildIdentity = false, profiles = listOf(profile)).toString())

        CronAutoIdentity.refreshEnabled()
        assertTrue(CronAutoIdentity.isRunningForTesting())
    }

    @Test
    fun `disabled profile Auto Identity cannot keep worker alive`() {
        val profile =
            JSONObject()
                .put("name", "Disabled")
                .put("enabled", false)
                .put("applications", JSONArray().put("com.example.disabled"))
                .put("template", JSONObject.NULL)
                .put("keybox", JSONObject.NULL)
                .put("privacy", "inherit")
                .put(
                    "features",
                    JSONObject()
                        .put("buildIdentity", true)
                        .put("identityRefresh", true),
                )
                .put("securityPatch", JSONObject())
                .put("rkpPassthrough", JSONObject.NULL)
                .put("drmPassthrough", JSONObject.NULL)
        PolicyState.installStateForTesting(state(buildIdentity = false, profiles = listOf(profile)).toString())

        CronAutoIdentity.refreshEnabled()
        assertFalse(CronAutoIdentity.isRunningForTesting())
    }

    private fun state(
        buildIdentity: Boolean,
        profiles: List<JSONObject> = emptyList(),
    ): JSONObject =
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
            .put("profiles", JSONArray().also { array -> profiles.forEach(array::put) })
            .put("activeProfile", JSONObject.NULL)
}
