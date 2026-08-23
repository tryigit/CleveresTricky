package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class IdentityDefaultResetTest {
    private lateinit var root: File
    private lateinit var originalSystemPropertiesGet: (String, String?) -> String?

    @Before
    fun setUp() {
        Config.reset()
        originalSystemPropertiesGet = systemPropertiesGet
        root = Files.createTempDirectory("identity-default-reset-test").toFile()
        Config.setRootForTesting(root)
        PolicyState.setRootForTesting(root)
    }

    @After
    fun tearDown() {
        systemPropertiesGet = originalSystemPropertiesGet
        root.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `recommended defaults keep Identity children off while recommending stale primary patch`() {
        systemPropertiesGet = { key, default ->
            if (key == "ro.build.version.security_patch") "2020-01-01" else default
        }
        File(root, CronAutoIdentity.TOGGLE_FILE).writeText("")

        PolicyState.applyRecommendedDefaults()

        val features = PolicyState.stateJson().getJSONObject("features")
        listOf(
            PolicyState.Feature.BUILD_IDENTITY,
            PolicyState.Feature.ATTESTATION_IDENTITY,
            PolicyState.Feature.TELEPHONY_IDENTITY,
            PolicyState.Feature.REGION_IDENTITY,
            PolicyState.Feature.IDENTITY_REFRESH,
        ).forEach { feature ->
            assertFalse(feature.jsonName, features.getBoolean(feature.jsonName))
        }
        assertTrue(features.getBoolean(PolicyState.Feature.SECURITY_PATCH.jsonName))
        assertFalse(File(root, CronAutoIdentity.TOGGLE_FILE).exists())
    }

    @Test
    fun `recommended defaults do not enable security patch when primary patch is unknown`() {
        systemPropertiesGet = { _, default -> default }

        PolicyState.applyRecommendedDefaults()

        val features = PolicyState.stateJson().getJSONObject("features")
        assertFalse(features.getBoolean(PolicyState.Feature.SECURITY_PATCH.jsonName))
    }
}
