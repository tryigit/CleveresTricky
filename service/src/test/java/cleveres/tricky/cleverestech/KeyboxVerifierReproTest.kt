package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboxVerifierReproTest {
    @Test
    fun testParseCrlWithLeadingZeroHex() {
        // "0a" is valid hex for 10.
        // Certificate serial number 10 is converted to "a" (no leading zero) by BigInteger.toString(16).
        // If CRL contains "0a", it should match "a".

        val json =
            """
            {
              "entries": {
                "0a": "REVOKED"
              }
            }
            """.trimIndent()

        val revoked = KeyboxVerifier.parseCrl(json)

        // 10 (decimal) -> "a" (hex)
        val targetSerial = "a"

        println("Revoked Set: $revoked")

        assertTrue("Should contain 'a' but has $revoked", revoked.contains(targetSerial))
    }
}
