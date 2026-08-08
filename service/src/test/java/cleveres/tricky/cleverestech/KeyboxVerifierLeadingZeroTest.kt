package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboxVerifierLeadingZeroTest {
    @Test
    fun testParseCrlWithLeadingZeroDigits() {
        // "0123" consists only of digits.
        // BigInteger("0123") parses as 123 (decimal) -> "7b" (hex).
        // However, standard decimal formatting usually prohibits leading zeros.
        // "0123" is much more likely to be a Hex string "0123".

        val json =
            """
            {
              "entries": {
                "0123": "REVOKED"
              }
            }
            """.trimIndent()

        val revoked = KeyboxVerifier.parseCrl(json)

        println("Revoked Set: $revoked")

        // We expect "0123" (canonicalized to "123" maybe? or just "0123"?)
        // BigInteger("0123", 16) -> 291 -> "123" (hex)

        // If the Key ID is "0123", the BigInteger representation is 291.
        // toString(16) gives "123".

        // Wait, if the serial is hex "123", toString(16) is "123".

        assertTrue("Should contain '123' (hex value of 0123)", revoked.contains("123"))

        // Currently, it likely contains "7b" (hex value of decimal 123).
        // Let's verify failure.
    }
}
