package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.keystore.CertHack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import java.io.IOException
import java.security.cert.X509Certificate
import java.nio.file.Files

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
    fun `verify rejects keybox directories above the file limit`() {
        val configDir = Files.createTempDirectory("keybox-verifier-limit").toFile()
        try {
            val keyboxDir = configDir.resolve("keyboxes").apply { mkdirs() }
            repeat(65) { index -> keyboxDir.resolve("keybox-$index.xml").writeText("x") }

            val results = KeyboxVerifier.verify(configDir) { emptySet() }

            assertEquals(1, results.size)
            assertEquals(KeyboxVerifier.Status.ERROR, results.single().status)
            assertEquals("Too many keybox files", results.single().details)
        } finally {
            configDir.deleteRecursively()
        }
    }
}
