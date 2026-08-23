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

class ProfileAutoIdentityStoreTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        Config.reset()
        root = Files.createTempDirectory("profile-auto-identity-store-test").toFile()
        Config.setRootForTesting(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `profile refresh is persisted separately and only matching apps resolve it`() {
        val global = File(root, "spoof_build_vars")
        val globalContent =
            "MODEL=Global Device\n" +
                "FINGERPRINT=google/global/global:16/GLOBAL/1:user/release-keys\n"
        global.writeText(globalContent)
        Config.updateBuildVars(global).getOrThrow()

        PolicyState.installStateForTesting(state(profile(buildIdentity = true, identityRefresh = true)).toString())
        Config.setPackagesForTesting(20_001, arrayOf("com.example.bank"))
        Config.setPackagesForTesting(20_002, arrayOf("com.example.other"))
        val result = result()

        assertTrue(ProfileAutoIdentityStore.save(root, result).isSuccess)

        assertEquals(globalContent, global.readText())
        assertEquals(result.fingerprint, Config.getBuildVar("FINGERPRINT", 20_001))
        assertEquals("Pixel 9", Config.getBuildVar("MODEL", 20_001))
        assertEquals("google/global/global:16/GLOBAL/1:user/release-keys", Config.getBuildVar("FINGERPRINT", 20_002))
        assertEquals("Global Device", Config.getBuildVar("MODEL", 20_002))
    }

    @Test
    fun `profile snapshot survives reload without becoming global identity`() {
        val result = result()
        assertTrue(ProfileAutoIdentityStore.save(root, result).isSuccess)
        val file = File(root, ProfileAutoIdentityStore.FILE_NAME)
        assertTrue(file.isFile)

        ProfileAutoIdentityStore.resetForTesting()
        assertNull(ProfileAutoIdentityStore.get("FINGERPRINT"))
        assertTrue(ProfileAutoIdentityStore.load(root).isSuccess)
        assertEquals(result.fingerprint, ProfileAutoIdentityStore.get("FINGERPRINT"))
        assertFalse(File(root, "spoof_build_vars").exists())
    }

    @Test
    fun `nullable release is reconstructed from the verified fingerprint`() {
        val result = result().copy(release = null)

        assertTrue(ProfileAutoIdentityStore.save(root, result).isSuccess)
        assertEquals("17", ProfileAutoIdentityStore.get("RELEASE"))
    }

    @Test
    fun `disabled Build Identity cannot consume the profile snapshot`() {
        val global = File(root, "spoof_build_vars")
        global.writeText("FINGERPRINT=google/global/global:16/GLOBAL/1:user/release-keys\n")
        Config.updateBuildVars(global).getOrThrow()
        assertTrue(ProfileAutoIdentityStore.save(root, result()).isSuccess)
        PolicyState.installStateForTesting(state(profile(buildIdentity = false, identityRefresh = true)).toString())
        Config.setPackagesForTesting(20_001, arrayOf("com.example.bank"))

        assertEquals("google/global/global:16/GLOBAL/1:user/release-keys", Config.getBuildVar("FINGERPRINT", 20_001))
    }

    @Test
    fun `symbolic link profile snapshot is rejected without modifying target`() {
        val outside = File(root.parentFile, "profile-auto-outside-${System.nanoTime()}.txt")
        outside.writeText("KEEP=unchanged\n")
        val file = File(root, ProfileAutoIdentityStore.FILE_NAME)
        try {
            Files.createSymbolicLink(file.toPath(), outside.toPath())
        } catch (_: UnsupportedOperationException) {
            outside.delete()
            return
        }

        assertTrue(ProfileAutoIdentityStore.save(root, result()).isFailure)
        assertEquals("KEEP=unchanged\n", outside.readText())
        outside.delete()
    }

    private fun profile(
        buildIdentity: Boolean,
        identityRefresh: Boolean,
    ): JSONObject =
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
                    .put("buildIdentity", buildIdentity)
                    .put("identityRefresh", identityRefresh),
            )
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

    private fun result(): AutoIdentityManager.Result =
        AutoIdentityManager.Result(
            model = "Pixel 9",
            product = "tokay_beta",
            device = "tokay",
            fingerprint = "google/tokay_beta/tokay:17/BP31.260801.001/12345678:user/release-keys",
            buildId = "BP31.260801.001",
            incremental = "12345678",
            release = "17",
            securityPatch = "2026-08-05",
            securityPatchEstimated = false,
        )
}
