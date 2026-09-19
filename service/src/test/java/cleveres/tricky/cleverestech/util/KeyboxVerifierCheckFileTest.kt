package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.KeyboxLoader
import cleveres.tricky.cleverestech.RustBackendUnavailableException
import cleveres.tricky.cleverestech.keystore.CertHack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.Date
import cleveres.tricky.cleverestech.util.KeyboxVerifier.RevocationSource

class KeyboxVerifierCheckFileTest {

    private lateinit var tempFile: File

    @Before
    fun setUp() {
        tempFile = File.createTempFile("keybox", ".xml")
    }

    @After
    fun tearDown() {
        if (tempFile.exists()) {
            tempFile.delete()
        }
        KeyboxLoader.resetForTesting()
    }

    @Test
    fun `checkFile returns ERROR for unsafe or oversized file`() {
        val nonExistentFile = File("does_not_exist.xml")
        val result = KeyboxVerifier.checkFile(
            nonExistentFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            "filename.xml",
            "storage123"
        ) { null }

        assertEquals(KeyboxVerifier.Status.ERROR, result.status)
        assertEquals("Unsafe or oversized keybox file", result.details)
    }

    @Test
    fun `checkFile returns INVALID when keyboxes is empty`() {
        tempFile.writeText("empty")
        KeyboxLoader.fileParserOverride = { _, _ ->
            KeyboxLoader.ParsedFile(
                snapshotSha256 = null,
                keyboxes = emptyList()
            )
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123"
        ) { null }

        assertEquals(KeyboxVerifier.Status.INVALID, result.status)
        assertEquals("No valid keybox found or parse error", result.details)
    }

    @Test
    fun `checkFile returns ERROR when crlFetcher returns null`() {
        tempFile.writeText("content")
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        KeyboxLoader.fileParserOverride = { _, _ ->
            KeyboxLoader.ParsedFile(
                snapshotSha256 = null,
                keyboxes = listOf(mockKeyBox)
            )
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123"
        ) { null }

        assertEquals(KeyboxVerifier.Status.ERROR, result.status)
        assertEquals("Failed to initialize CRL index", result.details)
    }

    @Test
    fun `checkFile returns REVOKED when certificate is revoked`() {
        tempFile.writeText("content")
        val mockCert = Mockito.mock(X509Certificate::class.java)
        val revokedSerial = "deadbeef"
        Mockito.`when`(mockCert.serialNumber).thenReturn(java.math.BigInteger(revokedSerial, 16))
        val mockPublicKey = Mockito.mock(java.security.PublicKey::class.java)
        Mockito.`when`(mockPublicKey.encoded).thenReturn(ByteArray(0))
        Mockito.`when`(mockCert.publicKey).thenReturn(mockPublicKey)

        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))

        KeyboxLoader.fileParserOverride = { _, _ ->
            KeyboxLoader.ParsedFile(
                snapshotSha256 = null,
                keyboxes = listOf(mockKeyBox)
            )
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123"
        ) { RevocationSource.Legacy(setOf(revokedSerial)) }

        assertEquals(KeyboxVerifier.Status.REVOKED, result.status)
        assertTrue(result.details.contains(revokedSerial))
    }

    @Test
    fun `checkFile returns INVALID when keybox structure is invalid`() {
        tempFile.writeText("content")
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(emptyList()) // Will cause Status.INVALID

        KeyboxLoader.fileParserOverride = { _, _ ->
            KeyboxLoader.ParsedFile(
                snapshotSha256 = null,
                keyboxes = listOf(mockKeyBox)
            )
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123"
        ) { RevocationSource.Legacy(emptySet()) }

        assertEquals(KeyboxVerifier.Status.INVALID, result.status)
        assertEquals("Keybox structure is invalid", result.details)
    }

    @Test
    fun `checkFile returns VALID when keybox is valid`() {
        tempFile.writeText("content")
        val mockCert = Mockito.mock(X509Certificate::class.java)
        Mockito.`when`(mockCert.serialNumber).thenReturn(java.math.BigInteger("123456"))
        val mockPublicKey = Mockito.mock(java.security.PublicKey::class.java)
        Mockito.`when`(mockPublicKey.encoded).thenReturn(ByteArray(0))
        Mockito.`when`(mockCert.publicKey).thenReturn(mockPublicKey)

        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))

        KeyboxLoader.fileParserOverride = { _, _ ->
            KeyboxLoader.ParsedFile(
                snapshotSha256 = null,
                keyboxes = listOf(mockKeyBox)
            )
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123"
        ) { RevocationSource.Legacy(emptySet()) }

        assertEquals(KeyboxVerifier.Status.VALID, result.status)
        assertEquals("Active keybox", result.details)
    }

    @Test
    fun `checkFile marks an expired certificate invalid after successful verification`() {
        tempFile.writeText("content")
        val mockCert = Mockito.mock(X509Certificate::class.java)
        Mockito.`when`(mockCert.serialNumber).thenReturn(java.math.BigInteger.ONE)
        Mockito.`when`(mockCert.notAfter).thenReturn(Date(0))
        val mockPublicKey = Mockito.mock(java.security.PublicKey::class.java)
        Mockito.`when`(mockPublicKey.encoded).thenReturn(ByteArray(0))
        Mockito.`when`(mockCert.publicKey).thenReturn(mockPublicKey)
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))
        KeyboxLoader.fileParserOverride = { _, _ ->
            KeyboxLoader.ParsedFile(snapshotSha256 = null, keyboxes = listOf(mockKeyBox))
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123",
        ) { RevocationSource.Legacy(emptySet()) }

        assertEquals(KeyboxVerifier.Status.VALID, result.status)
        assertEquals(KeyboxVerifier.ValidityState.INVALID, result.validityState)
        assertEquals(KeyboxVerifier.InvalidReason.EXPIRED, result.invalidReason)
    }

    @Test
    fun `checkFile handles RustBackendUnavailableException`() {
        tempFile.writeText("content")
        KeyboxLoader.fileParserOverride = { _, _ ->
            throw RustBackendUnavailableException(IOException("backend restart"))
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123"
        ) { null }

        assertEquals(KeyboxVerifier.Status.ERROR, result.status)
        assertEquals("Rust backend unavailable", result.details)
        assertTrue(result.retryableBackendFailure)
    }

    @Test
    fun `checkFile handles generic Exception`() {
        tempFile.writeText("content")
        KeyboxLoader.fileParserOverride = { _, _ ->
            throw RuntimeException("test error")
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123"
        ) { null }

        assertEquals(KeyboxVerifier.Status.ERROR, result.status)
        assertTrue(result.details.contains("RuntimeException"))
    }

    @Test
    fun `checkFile preserves StrongBox securityLevel on post-parse backend exception`() {
        tempFile.writeText("content")
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.filename()).thenReturn("strongbox_keybox.xml")

        KeyboxLoader.fileParserOverride = { _, _ ->
            KeyboxLoader.ParsedFile(
                snapshotSha256 = null,
                keyboxes = listOf(mockKeyBox)
            )
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123"
        ) {
            throw RustBackendUnavailableException(IOException("backend restart"))
        }

        assertEquals(KeyboxVerifier.Status.ERROR, result.status)
        assertEquals("Rust backend unavailable", result.details)
        assertTrue(result.retryableBackendFailure)
        assertEquals("StrongBox", result.securityLevel)
    }

    @Test
    fun `checkFile preserves StrongBox securityLevel on post-parse generic exception`() {
        tempFile.writeText("content")
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.filename()).thenReturn("strongbox_keybox.xml")

        KeyboxLoader.fileParserOverride = { _, _ ->
            KeyboxLoader.ParsedFile(
                snapshotSha256 = null,
                keyboxes = listOf(mockKeyBox)
            )
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123"
        ) {
            throw RuntimeException("network crash")
        }

        assertEquals(KeyboxVerifier.Status.ERROR, result.status)
        assertTrue(result.details.contains("RuntimeException"))
        assertEquals("StrongBox", result.securityLevel)
    }

    @Test
    fun `checkFile preserves Unknown securityLevel on parse failure or empty keyboxes`() {
        tempFile.writeText("corrupt")
        KeyboxLoader.fileParserOverride = { _, _ ->
            KeyboxLoader.ParsedFile(
                snapshotSha256 = null,
                keyboxes = emptyList(),
            )
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123",
        ) { null }

        assertEquals(KeyboxVerifier.Status.INVALID, result.status)
        assertEquals("Unknown", result.securityLevel)
    }

    @Test
    fun `checkFile classifies TEE securityLevel when TEE keybox is present`() {
        tempFile.writeText("content")
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.filename()).thenReturn("tee_keybox.xml")

        KeyboxLoader.fileParserOverride = { _, _ ->
            KeyboxLoader.ParsedFile(
                snapshotSha256 = null,
                keyboxes = listOf(mockKeyBox),
            )
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123",
        ) {
            throw RuntimeException("test")
        }

        assertEquals(KeyboxVerifier.Status.ERROR, result.status)
        assertEquals("TEE", result.securityLevel)
    }

    @Test
    fun `checkFile classifies TEE securityLevel for standard keybox without StrongBox markers`() {
        tempFile.writeText("content")
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        val mockCert = Mockito.mock(java.security.cert.X509Certificate::class.java)
        Mockito.`when`(mockKeyBox.filename()).thenReturn("keybox.xml")
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))

        KeyboxLoader.fileParserOverride = { _, _ ->
            KeyboxLoader.ParsedFile(
                snapshotSha256 = null,
                keyboxes = listOf(mockKeyBox),
            )
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123",
        ) {
            throw RuntimeException("test")
        }

        assertEquals(KeyboxVerifier.Status.ERROR, result.status)
        assertEquals("TEE", result.securityLevel)
    }

    @Test
    fun `checkFile does not trust RKP filename or certificate subject`() {
        tempFile.writeText("content")
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        val mockCert = Mockito.mock(java.security.cert.X509Certificate::class.java)
        Mockito.`when`(mockCert.subjectX500Principal)
            .thenReturn(javax.security.auth.x500.X500Principal("CN=Droid CA1, O=Google LLC, C=US"))
        Mockito.`when`(mockKeyBox.filename()).thenReturn("rkp_keybox.xml")
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))

        KeyboxLoader.fileParserOverride = { _, _ ->
            KeyboxLoader.ParsedFile(
                snapshotSha256 = null,
                keyboxes = listOf(mockKeyBox),
            )
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123",
        ) {
            throw RuntimeException("test")
        }

        assertEquals(KeyboxVerifier.Status.ERROR, result.status)
        assertEquals("TEE", result.securityLevel)
        assertFalse(result.isRkp)
    }

    @Test
    fun `checkFile normalizes securityLevel to RKP when keybox is authenticated RKP and not StrongBox`() {
        tempFile.writeText("content")
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        val mockCert = Mockito.mock(java.security.cert.X509Certificate::class.java)
        Mockito.`when`(mockKeyBox.filename()).thenReturn("standard_keybox.xml")
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))
        Mockito.`when`(mockKeyBox.authenticatedRkpProvenance()).thenReturn(true)

        KeyboxLoader.fileParserOverride = { _, _ ->
            KeyboxLoader.ParsedFile(
                snapshotSha256 = null,
                keyboxes = listOf(mockKeyBox),
            )
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123",
        ) {
            throw RuntimeException("test")
        }

        assertEquals(KeyboxVerifier.Status.ERROR, result.status)
        assertEquals("RKP", result.securityLevel)
        assertTrue(result.isRkp)
    }

    @Test
    fun `checkFile preserves StrongBox securityLevel when keybox is StrongBox even if RKP`() {
        tempFile.writeText("content")
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        val mockCert = Mockito.mock(java.security.cert.X509Certificate::class.java)
        Mockito.`when`(mockKeyBox.filename()).thenReturn("strongbox_keybox.xml")
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))
        Mockito.`when`(mockKeyBox.authenticatedRkpProvenance()).thenReturn(true)

        KeyboxLoader.fileParserOverride = { _, _ ->
            KeyboxLoader.ParsedFile(
                snapshotSha256 = null,
                keyboxes = listOf(mockKeyBox),
            )
        }

        val result = KeyboxVerifier.checkFile(
            tempFile,
            KeyboxLoader.FileScope.CONFIG_ROOT,
            tempFile.name,
            "storage123",
        ) {
            throw RuntimeException("test")
        }

        assertEquals(KeyboxVerifier.Status.ERROR, result.status)
        assertEquals("StrongBox", result.securityLevel)
        assertTrue(result.isRkp)
    }

    @Test
    fun `resolveValidity keeps structurally invalid keyboxes as verification failed even when expired`() {
        val (state, reason) =
            KeyboxVerifier.resolveValidity(KeyboxVerifier.Status.INVALID, "2020-01-01 00:00")
        assertEquals(KeyboxVerifier.ValidityState.INVALID, state)
        assertEquals(KeyboxVerifier.InvalidReason.VERIFICATION_FAILED, reason)
    }

    @Test
    fun `resolveValidity reports expired for structurally valid keyboxes past notAfter`() {
        val (state, reason) =
            KeyboxVerifier.resolveValidity(KeyboxVerifier.Status.VALID, "2020-01-01 00:00")
        assertEquals(KeyboxVerifier.ValidityState.INVALID, state)
        assertEquals(KeyboxVerifier.InvalidReason.EXPIRED, reason)
    }

    @Test
    fun `resolveValidity reports revoked for fresh keyboxes flagged by the revocation source`() {
        val (state, reason) =
            KeyboxVerifier.resolveValidity(KeyboxVerifier.Status.REVOKED, "2126-07-08 19:46")
        assertEquals(KeyboxVerifier.ValidityState.INVALID, state)
        assertEquals(KeyboxVerifier.InvalidReason.REVOKED, reason)
    }

    @Test
    fun `policy blocks verification failures in both modes and expired entries only when blocking`() {
        assertTrue(
            KeyboxVerifier.isBlockedByPolicy(KeyboxVerifier.Status.INVALID, "2020-01-01 00:00", blockInvalid = false),
        )
        assertTrue(
            KeyboxVerifier.isBlockedByPolicy(KeyboxVerifier.Status.INVALID, "2020-01-01 00:00", blockInvalid = true),
        )
        assertFalse(
            KeyboxVerifier.isBlockedByPolicy(KeyboxVerifier.Status.VALID, "2020-01-01 00:00", blockInvalid = false),
        )
        assertTrue(
            KeyboxVerifier.isBlockedByPolicy(KeyboxVerifier.Status.VALID, "2020-01-01 00:00", blockInvalid = true),
        )
        assertFalse(
            KeyboxVerifier.isBlockedByPolicy(KeyboxVerifier.Status.REVOKED, "2126-07-08 19:46", blockInvalid = false),
        )
        assertTrue(
            KeyboxVerifier.isBlockedByPolicy(KeyboxVerifier.Status.REVOKED, "2126-07-08 19:46", blockInvalid = true),
        )
        assertFalse(
            KeyboxVerifier.isBlockedByPolicy(KeyboxVerifier.Status.VALID, "2126-07-08 19:46", blockInvalid = true),
        )
    }

    @Test
    fun `policy keeps serving previously valid keyboxes through failed checks but fails closed without history`() {
        assertFalse(
            KeyboxVerifier.isBlockedByPolicy(
                KeyboxVerifier.Status.ERROR,
                "2126-07-08 19:46",
                blockInvalid = true,
                previouslyValid = true,
            ),
        )
        assertTrue(
            KeyboxVerifier.isBlockedByPolicy(
                KeyboxVerifier.Status.ERROR,
                "2126-07-08 19:46",
                blockInvalid = false,
                previouslyValid = false,
            ),
        )
        assertTrue(
            KeyboxVerifier.isBlockedByPolicy(KeyboxVerifier.Status.ERROR, "2126-07-08 19:46", blockInvalid = true),
        )
    }
}
