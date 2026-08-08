package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.keystore.CertHack
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import java.security.cert.X509Certificate

class KeyboxVerifierTest {
    @Test
    fun `verifyKeybox returns VALID for unrevoked certificate`() {
        // Arrange
        val mockCert = Mockito.mock(X509Certificate::class.java)
        // Not revoked serial
        Mockito.`when`(mockCert.serialNumber).thenReturn(java.math.BigInteger("123456"))
        val mockPublicKey = Mockito.mock(java.security.PublicKey::class.java)
        Mockito.`when`(mockPublicKey.encoded).thenReturn(ByteArray(0))
        Mockito.`when`(mockCert.publicKey).thenReturn(mockPublicKey)

        // Mock KeyBox to return our mock cert
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))

        val revokedSerials = setOf("deadbeef", "cafebabe")

        // Act
        val result = KeyboxVerifier.verifyKeybox(mockKeyBox, revokedSerials)

        // Assert
        assertEquals(KeyboxVerifier.Status.VALID, result)
    }

    @Test
    fun `verifyKeybox returns REVOKED for revoked serial`() {
        // Arrange
        val mockCert = Mockito.mock(X509Certificate::class.java)
        val revokedSerial = "deadbeef"
        Mockito.`when`(mockCert.serialNumber).thenReturn(java.math.BigInteger(revokedSerial, 16))

        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))

        val revokedSerials = setOf(revokedSerial, "cafebabe")

        // Act
        val result = KeyboxVerifier.verifyKeybox(mockKeyBox, revokedSerials)

        // Assert
        assertEquals(KeyboxVerifier.Status.REVOKED, result)
    }

    @Test
    fun `verifyKeybox returns INVALID for empty chain`() {
        // Arrange
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(emptyList())

        val revokedSerials = emptySet<String>()

        // Act
        val result = KeyboxVerifier.verifyKeybox(mockKeyBox, revokedSerials)

        // Assert
        assertEquals(KeyboxVerifier.Status.INVALID, result)
    }
}
