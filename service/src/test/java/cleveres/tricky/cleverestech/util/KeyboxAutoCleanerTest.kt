package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.KeyboxValidityTracker
import cleveres.tricky.cleverestech.ManagedFileCoordinator
import cleveres.tricky.cleverestech.WebServer
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeyboxAutoCleanerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `web server mutations share the cleaner coordination boundary`() {
        val server = WebServer(0, temp.newFolder("web-server"))
        val field = WebServer::class.java.getDeclaredField("fileLock").apply { isAccessible = true }

        assertSame(ManagedFileCoordinator.monitor, field.get(server))
    }

    @Test
    fun `rapid toggle reuses one executor instead of accumulating workers`() {
        val field = KeyboxAutoCleaner::class.java.getDeclaredField("executor").apply { isAccessible = true }
        try {
            KeyboxAutoCleaner.setEnabled(false)
            KeyboxAutoCleaner.setEnabled(true)
            val first = field.get(KeyboxAutoCleaner)
            KeyboxAutoCleaner.setEnabled(false)
            KeyboxAutoCleaner.setEnabled(true)
            val second = field.get(KeyboxAutoCleaner)

            assertSame(first, second)
        } finally {
            KeyboxAutoCleaner.setEnabled(false)
        }
    }

    @Test
    fun `replacement after verification is not quarantined`() {
        val root = temp.newFolder("replacement")
        val source = File(File(root, "keyboxes").apply { mkdirs() }, "candidate.xml")
        val verified = "verified-revoked-snapshot".toByteArray()
        source.writeBytes(verified)
        val result = revokedResult(source, sha256Hex(verified))

        val replacement = "new-valid-snapshot".toByteArray()
        source.writeBytes(replacement)
        var refreshes = 0
        val cleanup =
            KeyboxAutoCleaner.applyVerifiedResults(root, listOf(result), { true }) {
                refreshes++
            }

        assertEquals(0, cleanup.detected)
        assertEquals(0, cleanup.moved)
        assertFalse(cleanup.cancelled)
        assertEquals(1, refreshes)
        assertTrue(source.readBytes().contentEquals(replacement))
        assertFalse(File(File(root, "keyboxes/revoked"), source.name).exists())
    }

    @Test
    fun `matching verified snapshot is retained in place and updates tracker`() {
        val root = temp.newFolder("matching")
        val source = File(File(root, "keyboxes").apply { mkdirs() }, "candidate.xml")
        val verified = "verified-revoked-snapshot".toByteArray()
        source.writeBytes(verified)
        val result = revokedResult(source, sha256Hex(verified))
        var sourceExistedDuringRefresh = false

        val cleanup =
            KeyboxAutoCleaner.applyVerifiedResults(root, listOf(result), { true }) {
                sourceExistedDuringRefresh = source.exists()
            }

        assertEquals(1, cleanup.detected)
        assertEquals(0, cleanup.moved)
        assertFalse(cleanup.cancelled)
        assertTrue(sourceExistedDuringRefresh)
        assertTrue(source.exists())
        assertTrue(source.readBytes().contentEquals(verified))
        val entry = KeyboxValidityTracker.getState(result.storageId)
        assertEquals(KeyboxVerifier.ValidityState.INVALID, entry?.validityState)
        assertEquals(KeyboxVerifier.InvalidReason.REVOKED, entry?.invalidReason)
    }

    @Test
    fun `unstable verification result is not eligible for quarantine`() {
        val root = temp.newFolder("unstable")
        val source = File(File(root, "keyboxes").apply { mkdirs() }, "candidate.xml")
        source.writeText("malformed")
        val result = revokedResult(source, null)

        val cleanup = KeyboxAutoCleaner.applyVerifiedResults(root, listOf(result), { true }) {}

        assertEquals(0, cleanup.moved)
        assertTrue(source.exists())
    }

    private fun revokedResult(
        source: File,
        digest: String?,
    ): KeyboxVerifier.Result =
        KeyboxVerifier.Result(
            file = source,
            filename = source.name,
            status = KeyboxVerifier.Status.REVOKED,
            details = "revoked",
            storageId = "keyboxes:${source.name}",
            snapshotSha256 = digest,
            validityState = KeyboxVerifier.ValidityState.INVALID,
            invalidReason = KeyboxVerifier.InvalidReason.REVOKED,
        )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
}
