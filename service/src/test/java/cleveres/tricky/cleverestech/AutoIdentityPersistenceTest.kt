package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AutoIdentityPersistenceTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        Config.reset()
        root = Files.createTempDirectory("auto-identity-persistence-test").toFile()
        Config.setRootForTesting(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `auto identity replaces only build fields and never enables Identity`() {
        val vars = File(root, "spoof_build_vars")
        vars.writeText(
            """
            ATTESTATION_ID_IMEI=356938035643809
            TEMPLATE=pixel8
            # BEGIN CLEVERESTRICKY BUILD IDENTITY
            FINGERPRINT=old/fingerprint
            MODEL=Old Model
            # END CLEVERESTRICKY BUILD IDENTITY
            """.trimIndent() + "\n",
        )
        val result =
            AutoIdentityManager.Result(
                model = "Pixel 9",
                product = "tokay_beta",
                device = "tokay",
                fingerprint = "google/tokay_beta/tokay:CANARY/BP31.260801.001/12345678:user/release-keys",
                buildId = "BP31.260801.001",
                incremental = "12345678",
                release = "17",
                securityPatch = "2026-08-05",
                securityPatchEstimated = false,
            )

        assertTrue(AutoIdentityPersistence.save(root, result).isSuccess)

        val content = vars.readText()
        assertTrue(content.contains("ATTESTATION_ID_IMEI=356938035643809"))
        assertTrue(content.contains("MODEL=Pixel 9"))
        assertTrue(content.contains("FINGERPRINT=${result.fingerprint}"))
        assertFalse(content.lineSequence().any { it.startsWith("TEMPLATE=") })
        assertFalse(content.contains("# BEGIN CLEVERESTRICKY BUILD IDENTITY"))
        assertFalse(File(root, "spoof_enabled").exists())
        assertFalse(File(root, "spoof_build_identity").exists())
    }

    @Test
    fun `symbolic link build vars are rejected without modifying target`() {
        val outside = File(root.parentFile, "identity-outside-${System.nanoTime()}.txt")
        outside.writeText("KEEP=unchanged\n")
        val vars = File(root, "spoof_build_vars")
        try {
            Files.createSymbolicLink(vars.toPath(), outside.toPath())
        } catch (_: UnsupportedOperationException) {
            outside.delete()
            return
        }
        val result =
            AutoIdentityManager.Result(
                model = "Pixel 9",
                product = "tokay_beta",
                device = "tokay",
                fingerprint = "google/tokay_beta/tokay:CANARY/BP31.260801.001/12345678:user/release-keys",
                buildId = "BP31.260801.001",
                incremental = "12345678",
                release = "17",
                securityPatch = "2026-08-05",
                securityPatchEstimated = false,
            )

        assertTrue(AutoIdentityPersistence.save(root, result).isFailure)
        assertTrue(outside.readText().contains("KEEP=unchanged"))
        outside.delete()
    }
}
