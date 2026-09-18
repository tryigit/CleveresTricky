package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BlockInvalidKeyboxesTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        Config.reset()
        root = Files.createTempDirectory("block-invalid-test").toFile()
        Config.setRootForTesting(root)
        PolicyState.setRootForTesting(root)
        KeyboxValidityTracker.clear()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        Config.reset()
        PolicyState.resetForTesting()
        KeyboxValidityTracker.clear()
    }

    @Test
    fun `block invalid keyboxes is enabled when flag file exists`() {
        assertFalse(Config.isBlockInvalidKeyboxesEnabled)

        File(root, "block_invalid_keyboxes").createNewFile()
        Config.initialize()
        assertTrue(Config.isBlockInvalidKeyboxesEnabled)
    }

    @Test
    fun `profile presets configure block_invalid_keyboxes correctly`() {
        // Default preset
        Config.applyProfile("default")
        assertTrue(File(root, "block_invalid_keyboxes").exists())

        // Minimal preset removes it
        Config.applyProfile("minimal")
        assertFalse(File(root, "block_invalid_keyboxes").exists())

        // Daily preset creates it
        Config.applyProfile("daily")
        assertTrue(File(root, "block_invalid_keyboxes").exists())

        // Maximum preset creates it
        Config.applyProfile("maximum")
        assertTrue(File(root, "block_invalid_keyboxes").exists())
    }

    @Test
    fun `policy state preserves blockInvalidKeyboxes across persistence`() {
        PolicyState.applyRecommendedDefaults()
        assertTrue(PolicyState.stateJson().getBoolean("blockInvalidKeyboxes"))

        PolicyState.setBlockInvalidKeyboxes(false)
        assertFalse(PolicyState.stateJson().getBoolean("blockInvalidKeyboxes"))

        // Re-read policy state
        PolicyState.reload()
        assertFalse(PolicyState.stateJson().getBoolean("blockInvalidKeyboxes"))
    }

    @Test
    fun `validity tracker filters pool according to block toggle`() {
        val validResult = KeyboxVerifier.Result(
            file = File(root, "valid.xml"),
            filename = "valid.xml",
            status = KeyboxVerifier.Status.VALID,
            details = "valid",
            storageId = "valid.xml",
            validityState = KeyboxVerifier.ValidityState.VALID,
            invalidReason = null,
        )
        val expiredResult = KeyboxVerifier.Result(
            file = File(root, "expired.xml"),
            filename = "expired.xml",
            status = KeyboxVerifier.Status.INVALID,
            details = "expired",
            storageId = "expired.xml",
            validityState = KeyboxVerifier.ValidityState.INVALID,
            invalidReason = KeyboxVerifier.InvalidReason.EXPIRED,
        )
        val revokedResult = KeyboxVerifier.Result(
            file = File(root, "revoked.xml"),
            filename = "revoked.xml",
            status = KeyboxVerifier.Status.REVOKED,
            details = "revoked",
            storageId = "revoked.xml",
            validityState = KeyboxVerifier.ValidityState.INVALID,
            invalidReason = KeyboxVerifier.InvalidReason.REVOKED,
        )
        val failedResult = KeyboxVerifier.Result(
            file = File(root, "failed.xml"),
            filename = "failed.xml",
            status = KeyboxVerifier.Status.INVALID,
            details = "failed",
            storageId = "failed.xml",
            validityState = KeyboxVerifier.ValidityState.INVALID,
            invalidReason = KeyboxVerifier.InvalidReason.VERIFICATION_FAILED,
        )

        KeyboxValidityTracker.update(listOf(validResult, expiredResult, revokedResult, failedResult))

        // When block is ON (true):
        assertTrue(KeyboxValidityTracker.isEligible("valid.xml", blockInvalid = true))
        assertFalse(KeyboxValidityTracker.isEligible("expired.xml", blockInvalid = true))
        assertFalse(KeyboxValidityTracker.isEligible("revoked.xml", blockInvalid = true))
        assertFalse(KeyboxValidityTracker.isEligible("failed.xml", blockInvalid = true))

        // When block is OFF (false):
        assertTrue(KeyboxValidityTracker.isEligible("valid.xml", blockInvalid = false))
        assertTrue(KeyboxValidityTracker.isEligible("expired.xml", blockInvalid = false))
        assertTrue(KeyboxValidityTracker.isEligible("revoked.xml", blockInvalid = false))
        // Verification failed is ALWAYS excluded:
        assertFalse(KeyboxValidityTracker.isEligible("failed.xml", blockInvalid = false))
    }
}
