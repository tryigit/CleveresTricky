package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

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
        PolicyState.setBlockInvalidKeyboxes(false).getOrThrow()
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

    @Test
    fun `selection filters invalid candidates in default and custom modes`() {
        val validBox = Mockito.mock(CertHack.KeyBox::class.java)
        val expiredBox = Mockito.mock(CertHack.KeyBox::class.java)
        val revokedBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(validBox.filename()).thenReturn("valid.xml")
        Mockito.`when`(expiredBox.filename()).thenReturn("expired.xml")
        Mockito.`when`(revokedBox.filename()).thenReturn("revoked.xml")
        val candidates = listOf(validBox, expiredBox, revokedBox)
        KeyboxValidityTracker.update(
            listOf(
                result("valid.xml", KeyboxVerifier.ValidityState.VALID, null),
                result("expired.xml", KeyboxVerifier.ValidityState.INVALID, KeyboxVerifier.InvalidReason.EXPIRED),
                result("revoked.xml", KeyboxVerifier.ValidityState.INVALID, KeyboxVerifier.InvalidReason.REVOKED),
            ),
        )

        PolicyState.setBlockInvalidKeyboxes(false).getOrThrow()
        assertEquals(candidates, KeyboxPriorityOrder.filterTopPriorityTier(candidates))

        File(root, "block_invalid_keyboxes").createNewFile()
        assertEquals(listOf(validBox), KeyboxPriorityOrder.filterTopPriorityTier(candidates))

        val customPreference = KeyboxPriorityPreference(
            KeyboxPriorityPreference.Mode.CUSTOM,
            KeyboxPriorityCategory.DEFAULT_ORDER.reversed(),
        )
        PolicyState.setKeyboxPriorityPreference(customPreference).getOrThrow()
        assertEquals(listOf(validBox), KeyboxPriorityOrder.filterTopPriorityTier(candidates))
    }

    private fun result(
        filename: String,
        validityState: KeyboxVerifier.ValidityState,
        invalidReason: KeyboxVerifier.InvalidReason?,
    ) = KeyboxVerifier.Result(
        file = File(root, filename),
        filename = filename,
        status = if (invalidReason == KeyboxVerifier.InvalidReason.REVOKED) {
            KeyboxVerifier.Status.REVOKED
        } else if (validityState == KeyboxVerifier.ValidityState.VALID) {
            KeyboxVerifier.Status.VALID
        } else {
            KeyboxVerifier.Status.INVALID
        },
        details = "test",
        storageId = filename,
        validityState = validityState,
        invalidReason = invalidReason,
    )
}
