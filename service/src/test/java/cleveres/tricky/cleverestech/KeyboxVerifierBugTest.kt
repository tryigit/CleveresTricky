package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboxVerifierBugTest {
    @Test
    fun testParseCrlWithLongAllDigitHex() {
        // A 32-character string that happens to be all digits.
        // Ambiguous: Could be Decimal Serial or Hex KeyID.
        // We prioritize Decimal Serial (more common for variable length), so this should be parsed as Decimal.

        val targetStr = "10000000000000000000000000000001"
        val expectedHex = java.math.BigInteger(targetStr).toString(16)
        val json =
            """
            {
              "entries": {
                "$targetStr": "REVOKED"
              }
            }
            """.trimIndent()

        val revoked = KeyboxVerifier.parseCrl(json)

        println("Revoked Set: $revoked")

        // We expect the set to contain the hex representation of the DECIMAL value.
        org.junit.Assert.assertTrue("Should contain '$expectedHex'", revoked.contains(expectedHex))
    }
}
