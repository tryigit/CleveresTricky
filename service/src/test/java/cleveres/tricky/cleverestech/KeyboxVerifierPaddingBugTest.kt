package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class KeyboxVerifierPaddingBugTest {
    @Test
    fun testParseCrlWithDecimalRepresentingHashWithLeadingZero() {
        // Create a fake certificate or just simulate the check logic
        // We want to test that if the CRL contains a decimal number which corresponds to a hash starting with 0x0A,
        // it is correctly identified.

        // 1. Create a dummy hash with a leading zero in hex representation
        // For simplicity, let's use a small hash or just construct the scenario manually.
        // SHA-256 hash is 32 bytes.
        // Let's say the hash is 0x0A11...

        val hexHash = "0a112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        val bigInt = BigInteger(hexHash, 16)
        val decimalString = bigInt.toString(10)

        println("Testing with Decimal String: " + decimalString)
        println("Which corresponds to Hex: " + hexHash)

        val json =
            """
            {
              "entries": {
                "$decimalString": "REVOKED"
              }
            }
            """.trimIndent()

        val revokedSet = KeyboxVerifier.parseCrl(json)
        println("Revoked Set contains: " + revokedSet)

        // In checkHash logic:
        // val hex = digest.toHexString(hexFormat) -> returns "0a11..." (padded)
        // KeyboxVerifier logic:
        // BigInteger(decimalString).toString(16) -> returns "a11..." (not padded)

        // The set should ideally contain the padded version OR the checkHash logic should handle unpadded checks.
        // Since we cannot easily change checkHash logic without affecting other things or being inconsistent,
        // we should probably ensure the set contains the padded version if it's a hash length.

        // However, the verifier doesn't know if it's a hash or serial number at parse time.
        // But hashes have fixed lengths (32 chars for MD5, 40 for SHA1, 64 for SHA256).

        assertTrue(
            "Revoked set should contain the padded hex string used by checkHash. Set: " + revokedSet,
            revokedSet.contains(hexHash),
        )
    }
}
