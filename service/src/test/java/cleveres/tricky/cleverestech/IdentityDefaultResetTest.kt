package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertFalse
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
    fun `recommended defaults keep every Identity child off even on stale ROM patch`() {
        systemPropertiesGet = { key, default ->
            if (key == "ro.build.version.security_patch") "2020-01-01" else default
        }
        File(root, CronAutoIdentity.TOGGLE_FILE).writeText("")

        PolicyState.applyRecommendedDefaults()

        val features = PolicyState.stateJson().getJSONObject("features")
        PolicyState.Feature.entries.forEach { feature ->
            assertFalse(feature.jsonName, features.getBoolean(feature.jsonName))
        }
        assertFalse(File(root, CronAutoIdentity.TOGGLE_FILE).exists())
    }
}
