package cleveres.tricky.cleverestech

import java.nio.file.Files
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyStateHotPathTest {
    @Test
    fun currentDeviceKeepsAutomaticSecurityPatchDisabledByDefault() {
        val root = Files.createTempDirectory("ct-defaults-current").toFile()
        val oldProperties = systemPropertiesGet
        try {
            PolicyState.setRootForTesting(root)
            systemPropertiesGet = { key, default ->
                when (key) {
                    "ro.build.version.security_patch",
                    "ro.vendor.build.security_patch",
                    "ro.bootimage.build.version.security_patch" -> "2026-08-05"
                    else -> default
                }
            }
            PolicyState.currentDateSource = { LocalDate.of(2026, 8, 14) }
            PolicyState.applyRecommendedDefaults()
            val state = PolicyState.stateJson()
            val features = state.getJSONObject("features")
            assertFalse(features.getBoolean("buildIdentity"))
            assertFalse(features.getBoolean("attestationIdentity"))
            assertFalse(features.getBoolean("telephonyIdentity"))
            assertFalse(features.getBoolean("regionIdentity"))
            assertFalse(features.getBoolean("identityRefresh"))
            assertFalse(features.getBoolean("securityPatch"))
            val patch = state.getJSONObject("securityPatch")
            assertEquals(6L, patch.getLong("automaticThresholdMonths"))
            listOf("system", "vendor", "boot").forEach { component ->
                assertEquals("automatic", patch.getJSONObject(component).getString("mode"))
            }
        } finally {
            systemPropertiesGet = oldProperties
            PolicyState.resetForTesting()
            root.deleteRecursively()
        }
    }

    @Test
    fun staleDeviceEnablesAutomaticSecurityPatchByDefault() {
        val root = Files.createTempDirectory("ct-defaults-stale").toFile()
        val oldProperties = systemPropertiesGet
        try {
            PolicyState.setRootForTesting(root)
            systemPropertiesGet = { key, default ->
                when (key) {
                    "ro.build.version.security_patch" -> "2025-01-05"
                    "ro.vendor.build.security_patch",
                    "ro.bootimage.build.version.security_patch" -> "2026-08-05"
                    else -> default
                }
            }
            PolicyState.currentDateSource = { LocalDate.of(2026, 8, 14) }
            PolicyState.applyRecommendedDefaults()
            assertTrue(PolicyState.stateJson().getJSONObject("features").getBoolean("securityPatch"))
        } finally {
            systemPropertiesGet = oldProperties
            PolicyState.resetForTesting()
            root.deleteRecursively()
        }
    }

    @Test
    fun capturedAutomaticPatchSkipsPropertyReads() {
        val root = Files.createTempDirectory("ct-patch-hotpath").toFile()
        val oldProperties = systemPropertiesGet
        try {
            Config.setRootForTesting(root)
            Config.setPackagesForTesting(12345, arrayOf("com.example.test"))
            PolicyState.installStateForTesting(
                """
                {
                  "version": 2,
                  "features": {
                    "buildIdentity": false,
                    "attestationIdentity": false,
                    "telephonyIdentity": false,
                    "regionIdentity": false,
                    "identityRefresh": false,
                    "securityPatch": true
                  },
                  "securityPatch": {
                    "automaticThresholdMonths": 6,
                    "system": {"mode": "automatic"},
                    "vendor": {"mode": "automatic"},
                    "boot": {"mode": "automatic"}
                  },
                  "profiles": [],
                  "activeProfile": null
                }
                """.trimIndent(),
            )
            PolicyState.currentDateSource = { LocalDate.of(2026, 8, 14) }
            var reads = 0
            systemPropertiesGet = { _, default ->
                reads++
                default
            }

            val levels = PolicyState.resolveAttestationPatchLevels(12345, 202608, 20260805, 20260805)
            assertEquals(Config.PatchDisposition.KEEP, levels.system.disposition)
            assertEquals(Config.PatchDisposition.KEEP, levels.vendor.disposition)
            assertEquals(Config.PatchDisposition.KEEP, levels.boot.disposition)
            assertEquals(0, reads)
        } finally {
            systemPropertiesGet = oldProperties
            Config.reset()
            root.deleteRecursively()
        }
    }
}
