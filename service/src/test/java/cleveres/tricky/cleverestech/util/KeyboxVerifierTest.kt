package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.KeyboxLoader
import cleveres.tricky.cleverestech.RustBackendUnavailableException
import cleveres.tricky.cleverestech.keystore.CertHack
import java.io.IOException
import java.nio.file.Files
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito

class KeyboxVerifierTest {
    @Test
    fun `verifyKeybox returns VALID for unrevoked certificate`() {
        val mockCert = Mockito.mock(X509Certificate::class.java)
        Mockito.`when`(mockCert.serialNumber).thenReturn(java.math.BigInteger("123456"))
        val mockPublicKey = Mockito.mock(java.security.PublicKey::class.java)
        Mockito.`when`(mockPublicKey.encoded).thenReturn(ByteArray(0))
        Mockito.`when`(mockCert.publicKey).thenReturn(mockPublicKey)

        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))

        val result = KeyboxVerifier.verifyKeybox(mockKeyBox, setOf("deadbeef", "cafebabe"))

        assertEquals(KeyboxVerifier.Status.VALID, result)
    }

    @Test
    fun `verifyKeybox returns REVOKED for revoked serial`() {
        val mockCert = Mockito.mock(X509Certificate::class.java)
        val revokedSerial = "deadbeef"
        Mockito.`when`(mockCert.serialNumber).thenReturn(java.math.BigInteger(revokedSerial, 16))

        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))

        val result = KeyboxVerifier.verifyKeybox(mockKeyBox, setOf(revokedSerial, "cafebabe"))

        assertEquals(KeyboxVerifier.Status.REVOKED, result)
    }

    @Test
    fun `verifyKeybox returns INVALID for empty chain`() {
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(emptyList())

        val result = KeyboxVerifier.verifyKeybox(mockKeyBox, emptySet())

        assertEquals(KeyboxVerifier.Status.INVALID, result)
    }

    @Test
    fun `parseCrl rejects oversized entry keys`() {
        val key = "1".repeat(129)
        assertThrows(IOException::class.java) {
            KeyboxVerifier.parseCrl("""{"entries":{"$key":"REVOKED"}}""")
        }
    }

    @Test
    fun `verify parses keybox files through Rust loader boundary`() {
        val configDir = Files.createTempDirectory("keybox-verifier-rust-path").toFile()
        val file = configDir.resolve("keybox.xml")
        file.writeText("not legacy XML")
        val mockCert = Mockito.mock(X509Certificate::class.java)
        Mockito.`when`(mockCert.serialNumber).thenReturn(java.math.BigInteger.ONE)
        val publicKey = Mockito.mock(java.security.PublicKey::class.java)
        Mockito.`when`(publicKey.encoded).thenReturn(byteArrayOf(1, 2, 3))
        Mockito.`when`(mockCert.publicKey).thenReturn(publicKey)
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))
        var observedScope: KeyboxLoader.FileScope? = null
        var observedFilename: String? = null
        KeyboxLoader.fileParserOverride = { scope, filename ->
            observedScope = scope
            observedFilename = filename
            KeyboxLoader.ParsedFile(
                snapshotSha256 = "00".repeat(32),
                keyboxes = listOf(mockKeyBox),
            )
        }

        try {
            val result = KeyboxVerifier.verify(configDir) { emptySet() }.single()

            assertEquals(KeyboxVerifier.Status.VALID, result.status)
            assertEquals("00".repeat(32), result.snapshotSha256)
            assertEquals(KeyboxLoader.FileScope.CONFIG_ROOT, observedScope)
            assertEquals("keybox.xml", observedFilename)
        } finally {
            KeyboxLoader.resetForTesting()
            configDir.deleteRecursively()
        }
    }

    @Test
    fun `manual verification retries one transient Rust backend outage`() {
        val configDir = Files.createTempDirectory("keybox-verifier-retry").toFile()
        configDir.resolve("keybox.xml").writeText("backend-owned input")
        val mockCert = Mockito.mock(X509Certificate::class.java)
        Mockito.`when`(mockCert.serialNumber).thenReturn(java.math.BigInteger.ONE)
        val publicKey = Mockito.mock(java.security.PublicKey::class.java)
        Mockito.`when`(publicKey.encoded).thenReturn(byteArrayOf(1, 2, 3))
        Mockito.`when`(mockCert.publicKey).thenReturn(publicKey)
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))
        var attempts = 0
        KeyboxLoader.fileParserOverride = { _, _ ->
            attempts++
            if (attempts == 1) throw RustBackendUnavailableException(IOException("backend restart"))
            KeyboxLoader.ParsedFile(
                snapshotSha256 = "11".repeat(32),
                keyboxes = listOf(mockKeyBox),
            )
        }

        try {
            val result = KeyboxVerifier.verifyWithRetryForTesting(configDir) { emptySet() }.single()

            assertEquals(2, attempts)
            assertEquals(KeyboxVerifier.Status.VALID, result.status)
            assertEquals("11".repeat(32), result.snapshotSha256)
        } finally {
            KeyboxLoader.resetForTesting()
            configDir.deleteRecursively()
        }
    }

    @Test
    fun `verify rejects keybox directories above the file limit`() {
        val configDir = Files.createTempDirectory("keybox-verifier-limit").toFile()
        try {
            val keyboxDir = configDir.resolve("keyboxes").apply { mkdirs() }
            repeat(65) { index -> keyboxDir.resolve("keybox-$index.xml").writeText("x") }

            val results = KeyboxVerifier.verify(configDir) { emptySet() }

            assertEquals(1, results.size)
            assertEquals(KeyboxVerifier.Status.ERROR, results.single().status)
            assertEquals("Too many keybox XML files", results.single().details)
        } finally {
            configDir.deleteRecursively()
        }
    }
}
