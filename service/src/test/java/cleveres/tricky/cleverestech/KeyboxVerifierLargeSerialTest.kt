package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class KeyboxVerifierLargeSerialTest {
    @Test
    fun testParseCrlLargeSerialNumber() {
        // A 25-digit decimal number.
        // Google CRL uses decimal strings for serial numbers.
        // X.509 Serial Numbers can be up to 20 octets (usually handled as large integers).
        val decimalSerial = "1234567890123456789012345"

        // Correct Hex representation
        val expectedHex = BigInteger(decimalSerial).toString(16)
        // 105d4ac1d3f232b78129

        // If bug exists, it parses as Hex literal
        val wrongHex = BigInteger(decimalSerial, 16).toString(16)
        // 1234567890123456789012345

        val json =
            """
            {
              "entries": {
                "$decimalSerial": "REVOKED"
              }
            }
            """.trimIndent()

        val revoked = KeyboxVerifier.parseCrl(json)

        println("Decimal Input: $decimalSerial")
        println("Expected Hex: $expectedHex")
        println("Wrong Hex:   $wrongHex")
        println("Revoked Set: $revoked")

        assertTrue("Should contain correct hex '$expectedHex' (parsed as Decimal)", revoked.contains(expectedHex))
        assertFalse("Should NOT contain wrong hex '$wrongHex' (parsed as Hex literal)", revoked.contains(wrongHex))
    }
}
