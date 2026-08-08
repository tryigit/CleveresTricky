package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboxVerifierNegativeTest {
    @Test
    fun testParseCrlWithNegativeDecimal() {
        // Serial Number -10.
        // BigInteger(-10).toString(16) = "-a".

        val negativeSerial = "-10"

        val json =
            """
            {
              "entries": {
                "$negativeSerial": "REVOKED"
              }
            }
            """.trimIndent()

        val revoked = KeyboxVerifier.parseCrl(json)

        println("Revoked Set: $revoked")

        // If bug exists, "-a" is missing because "-10" fails isDecimal check (has '-') and HEX_REGEX (has '-').
        assertTrue("Should contain '-a' for negative decimal -10", revoked.contains("-a"))
    }
}
