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

class AutoIdentityPolicyTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        Config.reset()
        root = Files.createTempDirectory("auto-identity-policy-test").toFile()
        Config.setRootForTesting(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `assigned profile can opt into Auto Identity without global cron`() {
        install(
            profile(
                name = "Banking",
                enabled = true,
                applications = arrayOf("com.example.bank"),
                buildIdentity = true,
                identityRefresh = true,
            ),
        )

        val decision = AutoIdentityPolicy.evaluate(globalCronEnabled = false)
        assertTrue(decision.shouldRun)
        assertTrue(decision.profileScoped)
        assertFalse(decision.globalLiveApply)
    }

    @Test
    fun `disabled or unassigned profiles do not schedule Auto Identity`() {
        install(
            profile(
                name = "Disabled",
                enabled = false,
                applications = arrayOf("com.example.disabled"),
                buildIdentity = true,
                identityRefresh = true,
            ),
            profile(
                name = "Unused",
                enabled = true,
                applications = emptyArray(),
                buildIdentity = true,
                identityRefresh = true,
            ),
        )

        val decision = AutoIdentityPolicy.evaluate(globalCronEnabled = false)
        assertFalse(decision.shouldRun)
        assertFalse(decision.profileScoped)
    }

    @Test
    fun `active profile can opt into Auto Identity without app assignments`() {
        val active =
            profile(
                name = "Active",
                enabled = true,
                applications = emptyArray(),
                buildIdentity = true,
                identityRefresh = true,
            )
        install(active, activeProfile = "Active")

        assertTrue(AutoIdentityPolicy.evaluate(globalCronEnabled = false).profileScoped)
    }

    @Test
    fun `global cron never promotes active profile Build Identity to global live apply`() {
        val active =
            profile(
                name = "Active",
                enabled = true,
                applications = emptyArray(),
                buildIdentity = true,
                identityRefresh = null,
            )
        install(active, globalBuildIdentity = false, activeProfile = "Active")

        val decision = AutoIdentityPolicy.evaluate(globalCronEnabled = true)
        assertFalse(decision.shouldRun)
        assertFalse(decision.globalLiveApply)
        assertFalse(decision.profileScoped)
    }

    @Test
    fun `assigned profile can inherit active Auto Identity and re-enable Build Identity`() {
        val active =
            profile(
                name = "Active",
                enabled = true,
                applications = emptyArray(),
                buildIdentity = false,
                identityRefresh = true,
            )
        val banking =
            profile(
                name = "Banking",
                enabled = true,
                applications = arrayOf("com.example.bank"),
                buildIdentity = true,
                identityRefresh = null,
            )
        install(active, banking, activeProfile = "Active")
        Config.setPackagesForTesting(20_101, arrayOf("com.example.bank"))

        val decision = AutoIdentityPolicy.evaluate(globalCronEnabled = false)
        assertTrue(decision.shouldRun)
        assertTrue(decision.profileScoped)
        assertFalse(decision.globalLiveApply)
        assertTrue(PolicyState.isProfileAutoIdentityEnabled(20_101))
    }

    @Test
    fun `selected profile can explicitly disable active Auto Identity inheritance`() {
        val active =
            profile(
                name = "Active",
                enabled = true,
                applications = emptyArray(),
                buildIdentity = true,
                identityRefresh = true,
            )
        val banking =
            profile(
                name = "Banking",
                enabled = true,
                applications = arrayOf("com.example.bank"),
                buildIdentity = null,
                identityRefresh = false,
            )
        install(active, banking, activeProfile = "Active")
        Config.setPackagesForTesting(20_102, arrayOf("com.example.bank"))

        assertFalse(PolicyState.isProfileAutoIdentityEnabled(20_102))
    }

    @Test
    fun `global boot Identity Refresh never becomes profile Auto Identity`() {
        install(
            profile(
                name = "Banking",
                enabled = true,
                applications = arrayOf("com.example.bank"),
                buildIdentity = true,
                identityRefresh = null,
            ),
            globalIdentityRefresh = true,
        )
        Config.setPackagesForTesting(20_103, arrayOf("com.example.bank"))

        val decision = AutoIdentityPolicy.evaluate(globalCronEnabled = false)
        assertFalse(decision.shouldRun)
        assertFalse(decision.profileScoped)
        assertFalse(PolicyState.isProfileAutoIdentityEnabled(20_103))
    }

    @Test
    fun `profile Auto Identity requires explicit refresh opt in and effective Build Identity`() {
        install(
            profile(
                name = "NoRefresh",
                enabled = true,
                applications = arrayOf("com.example.no_refresh"),
                buildIdentity = true,
                identityRefresh = null,
            ),
            profile(
                name = "NoBuild",
                enabled = true,
                applications = arrayOf("com.example.no_build"),
                buildIdentity = false,
                identityRefresh = true,
            ),
        )

        assertFalse(AutoIdentityPolicy.evaluate(globalCronEnabled = false).profileScoped)
    }

    @Test
    fun `profile can inherit global Build Identity while opting into Auto Identity`() {
        install(
            profile(
                name = "InheritedBuild",
                enabled = true,
                applications = arrayOf("com.example.inherited"),
                buildIdentity = null,
                identityRefresh = true,
            ),
            globalBuildIdentity = true,
        )

        val decision = AutoIdentityPolicy.evaluate(globalCronEnabled = false)
        assertTrue(decision.shouldRun)
        assertTrue(decision.profileScoped)
        assertFalse(decision.globalLiveApply)
    }

    @Test
    fun `global cron keeps global live apply separate from profile work`() {
        install(globalBuildIdentity = true)

        val decision = AutoIdentityPolicy.evaluate(globalCronEnabled = true)
        assertTrue(decision.shouldRun)
        assertTrue(decision.globalLiveApply)
        assertFalse(decision.profileScoped)
    }

    private fun install(
        vararg profiles: JSONObject,
        globalBuildIdentity: Boolean = false,
        globalIdentityRefresh: Boolean = false,
        activeProfile: String? = null,
    ) {
        PolicyState.installStateForTesting(
            state(
                profiles = profiles.toList(),
                globalBuildIdentity = globalBuildIdentity,
                globalIdentityRefresh = globalIdentityRefresh,
                activeProfile = activeProfile,
            ).toString(),
        )
    }

    private fun profile(
        name: String,
        enabled: Boolean,
        applications: Array<String>,
        buildIdentity: Boolean?,
        identityRefresh: Boolean?,
    ): JSONObject {
        val features = JSONObject()
        buildIdentity?.let { features.put("buildIdentity", it) }
        identityRefresh?.let { features.put("identityRefresh", it) }
        return JSONObject()
            .put("name", name)
            .put("enabled", enabled)
            .put("applications", JSONArray(applications))
            .put("template", JSONObject.NULL)
            .put("keybox", JSONObject.NULL)
            .put("privacy", "inherit")
            .put("features", features)
            .put("securityPatch", JSONObject())
            .put("rkpPassthrough", JSONObject.NULL)
            .put("drmPassthrough", JSONObject.NULL)
    }

    private fun state(
        profiles: List<JSONObject> = emptyList(),
        globalBuildIdentity: Boolean = false,
        globalIdentityRefresh: Boolean = false,
        activeProfile: String? = null,
    ): JSONObject =
        JSONObject()
            .put("version", PolicyState.SCHEMA_VERSION)
            .put(
                "features",
                JSONObject()
                    .put("buildIdentity", globalBuildIdentity)
                    .put("attestationIdentity", false)
                    .put("telephonyIdentity", false)
                    .put("regionIdentity", false)
                    .put("identityRefresh", globalIdentityRefresh)
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
            .put("activeProfile", activeProfile ?: JSONObject.NULL)
}
