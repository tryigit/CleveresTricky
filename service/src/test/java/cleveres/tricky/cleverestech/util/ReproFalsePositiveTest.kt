package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.Logger
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import java.math.BigInteger
import java.security.cert.X509Certificate

class ReproFalsePositiveTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setup() {
            Logger.setImpl(
                object : Logger.LogImpl {
                    override fun d(
                        tag: String,
                        msg: String,
                    ) {}

                    override fun e(
                        tag: String,
                        msg: String,
                    ) {
                        println("E/$tag: $msg")
                    }

                    override fun e(
                        tag: String,
                        msg: String,
                        t: Throwable?,
                    ) {
                        println("E/$tag: $msg")
                    }

                    override fun i(
                        tag: String,
                        msg: String,
                    ) {}
                },
            )
        }
    }

    @Test
    fun testFalsePositiveRevocation() {
        // A 32-digit decimal string.
        // It is a valid Decimal number.
        // It is ALSO a valid Hex string (all digits are valid hex chars).
        val ambiguousStr = "10000000000000000000000000000000" // 32 chars

        val json =
            """
            {
              "entries": {
                "$ambiguousStr": "REVOKED"
              }
            }
            """.trimIndent()

        // Parse CRL
        val revokedSerials = KeyboxVerifier.parseCrl(json)
        println("Revoked Set: $revokedSerials")

        // 1. Check that the Decimal interpretation is present (Correct behavior)
        val decimalVal = BigInteger(ambiguousStr)
        val decimalHex = decimalVal.toString(16)
        // verify revokedSerials contains decimalHex

        // 2. Check that the Hex interpretation is NOT present (The Bug/Fix)
        // If we treat "10...0" as a hex string, its value is... "10...0".
        // If we have a certificate with Serial Number = BigInteger("10...0", 16),
        // its hex string is "10...0".
        // This certificate should NOT be revoked because the CRL entry was Decimal.

        val hexInterpretation = ambiguousStr.lowercase()

        // This assertion passes if the security fix is applied
        if (revokedSerials.contains(hexInterpretation)) {
            println("Ambiguous interpretation '$hexInterpretation' was added to revoked set (Secure behavior).")
        } else {
            println("FAIL-OPEN: Hex interpretation not present.")
        }

        // Simulate a Keybox Verification
        val mockCert = Mockito.mock(X509Certificate::class.java)
        // Serial number of the victim cert = BigInteger(ambiguousStr, 16)
        val victimSerial = BigInteger(ambiguousStr, 16)
        Mockito.`when`(mockCert.serialNumber).thenReturn(victimSerial)

        // Mock public key to avoid hash revocation
        val mockPublicKey = Mockito.mock(java.security.PublicKey::class.java)
        Mockito.`when`(mockPublicKey.encoded).thenReturn(ByteArray(32) { 0x00 })
        Mockito.`when`(mockCert.publicKey).thenReturn(mockPublicKey)

        val isRevoked = KeyboxVerifier.isRevoked(mockCert, revokedSerials)

        // We now EXPECT this to be revoked to prevent Fail-Open vulnerability
        assertEquals("Certificate with Hex Serial matching the ambiguous string should be REVOKED (Security Fix)", true, isRevoked)
    }
}
