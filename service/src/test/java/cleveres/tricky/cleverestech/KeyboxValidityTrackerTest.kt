package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KeyboxValidityTrackerTest {

    @Before
    @After
    fun tearDown() {
        KeyboxValidityTracker.clear()
    }

    @Test
    fun `unknown keybox is eligible regardless of block setting`() {
        assertTrue(KeyboxValidityTracker.isEligible("unknown.xml", blockInvalid = true))
        assertTrue(KeyboxValidityTracker.isEligible("unknown.xml", blockInvalid = false))
    }

    @Test
    fun `valid keybox is eligible regardless of block setting`() {
        val result = createResult(
            filename = "valid.xml",
            status = KeyboxVerifier.Status.VALID,
            validityState = KeyboxVerifier.ValidityState.VALID,
            invalidReason = null,
        )
        KeyboxValidityTracker.update(listOf(result))

        assertTrue(KeyboxValidityTracker.isEligible("valid.xml", blockInvalid = true))
        assertTrue(KeyboxValidityTracker.isEligible("valid.xml", blockInvalid = false))
    }

    @Test
    fun `expired keybox is blocked when block is true and eligible when block is false`() {
        val result = createResult(
            filename = "expired.xml",
            status = KeyboxVerifier.Status.INVALID,
            validityState = KeyboxVerifier.ValidityState.INVALID,
            invalidReason = KeyboxVerifier.InvalidReason.EXPIRED,
        )
        KeyboxValidityTracker.update(listOf(result))

        assertFalse(KeyboxValidityTracker.isEligible("expired.xml", blockInvalid = true))
        assertTrue(KeyboxValidityTracker.isEligible("expired.xml", blockInvalid = false))
    }

    @Test
    fun `revoked keybox is blocked when block is true and eligible when block is false`() {
        val result = createResult(
            filename = "revoked.xml",
            status = KeyboxVerifier.Status.REVOKED,
            validityState = KeyboxVerifier.ValidityState.INVALID,
            invalidReason = KeyboxVerifier.InvalidReason.REVOKED,
        )
        KeyboxValidityTracker.update(listOf(result))

        assertFalse(KeyboxValidityTracker.isEligible("revoked.xml", blockInvalid = true))
        assertTrue(KeyboxValidityTracker.isEligible("revoked.xml", blockInvalid = false))
    }

    @Test
    fun `verification failed keybox is blocked regardless of block setting`() {
        val result = createResult(
            filename = "corrupt.xml",
            status = KeyboxVerifier.Status.INVALID,
            validityState = KeyboxVerifier.ValidityState.INVALID,
            invalidReason = KeyboxVerifier.InvalidReason.VERIFICATION_FAILED,
        )
        KeyboxValidityTracker.update(listOf(result))

        assertFalse(KeyboxValidityTracker.isEligible("corrupt.xml", blockInvalid = true))
        assertFalse(KeyboxValidityTracker.isEligible("corrupt.xml", blockInvalid = false))
    }

    @Test
    fun `transient error retains revoked state while absent keyboxes are removed`() {
        val revokedResult = createResult(
            filename = "revoked.xml",
            status = KeyboxVerifier.Status.REVOKED,
            validityState = KeyboxVerifier.ValidityState.INVALID,
            invalidReason = KeyboxVerifier.InvalidReason.REVOKED,
        )
        val errorResult = createResult(
            filename = "revoked.xml",
            status = KeyboxVerifier.Status.ERROR,
            validityState = KeyboxVerifier.ValidityState.VALID,
            invalidReason = null,
        )
        val absentResult = createResult(
            filename = "absent.xml",
            status = KeyboxVerifier.Status.INVALID,
            validityState = KeyboxVerifier.ValidityState.INVALID,
            invalidReason = KeyboxVerifier.InvalidReason.EXPIRED,
        )

        KeyboxValidityTracker.update(listOf(revokedResult, absentResult))
        KeyboxValidityTracker.update(listOf(errorResult))

        val retained = KeyboxValidityTracker.getState("revoked.xml")
        assertEquals(KeyboxVerifier.ValidityState.INVALID, retained?.validityState)
        assertEquals(KeyboxVerifier.InvalidReason.REVOKED, retained?.invalidReason)
        assertNull(KeyboxValidityTracker.getState("absent.xml"))
    }

    @Test
    fun `shared tracker key keeps the worst verdict instead of last write wins`() {
        val valid = createResult(
            filename = "multi.xml",
            status = KeyboxVerifier.Status.VALID,
            validityState = KeyboxVerifier.ValidityState.VALID,
            invalidReason = null,
        )
        val revoked = createResult(
            filename = "multi.xml",
            status = KeyboxVerifier.Status.REVOKED,
            validityState = KeyboxVerifier.ValidityState.INVALID,
            invalidReason = KeyboxVerifier.InvalidReason.REVOKED,
        )
        val failed = createResult(
            filename = "multi.xml",
            status = KeyboxVerifier.Status.INVALID,
            validityState = KeyboxVerifier.ValidityState.INVALID,
            invalidReason = KeyboxVerifier.InvalidReason.VERIFICATION_FAILED,
        )

        KeyboxValidityTracker.update(listOf(valid, revoked))
        assertEquals(KeyboxVerifier.InvalidReason.REVOKED, KeyboxValidityTracker.getState("multi.xml")?.invalidReason)

        KeyboxValidityTracker.update(listOf(revoked, valid))
        assertEquals(KeyboxVerifier.InvalidReason.REVOKED, KeyboxValidityTracker.getState("multi.xml")?.invalidReason)

        KeyboxValidityTracker.update(listOf(revoked, failed))
        assertEquals(
            KeyboxVerifier.InvalidReason.VERIFICATION_FAILED,
            KeyboxValidityTracker.getState("multi.xml")?.invalidReason,
        )
        assertFalse(KeyboxValidityTracker.isEligible("multi.xml", blockInvalid = false))
    }

    @Test
    fun `clear empties the tracker snapshot`() {
        val result = createResult(
            filename = "sample.xml",
            status = KeyboxVerifier.Status.VALID,
            validityState = KeyboxVerifier.ValidityState.VALID,
            invalidReason = null,
        )
        KeyboxValidityTracker.update(listOf(result))
        assertFalse(KeyboxValidityTracker.snapshot().isEmpty())

        KeyboxValidityTracker.clear()
        assertTrue(KeyboxValidityTracker.snapshot().isEmpty())
    }

    private fun createResult(
        filename: String,
        status: KeyboxVerifier.Status,
        validityState: KeyboxVerifier.ValidityState,
        invalidReason: KeyboxVerifier.InvalidReason?,
    ): KeyboxVerifier.Result =
        KeyboxVerifier.Result(
            file = File(filename),
            filename = filename,
            status = status,
            details = "test",
            storageId = filename,
            validityState = validityState,
            invalidReason = invalidReason,
        )
}
