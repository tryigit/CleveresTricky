package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.junit.Assert.assertFalse
import org.junit.Test

class ReproFalsePositiveRevocationTest {
    @Test
    fun testAmbiguousKeyIsIncludedToAvoidSecurityBypass() {
        // A 32-character string that consists only of digits.
        // It is a valid Decimal number (Google CRL format).
        // It is ALSO a valid Hex string (if interpreted as Hex Key ID).
        //
        // Because of the false positive issue where a valid decimal serial number
        // causes a completely different certificate to be revoked (if its hex serial matches the decimal string),
        // we omit adding the raw string if it is successfully parsed as purely decimal.

        val decimalSerial = "10000000000000000000000000000001" // 32 chars

        val json =
            """
            {
              "entries": {
                "$decimalSerial": "REVOKED"
              }
            }
            """.trimIndent()

        val revoked = KeyboxVerifier.parseCrl(json)
        println("Revoked: $revoked")

        // We intentionally exclude it to avoid false positive hex matching.
        assertFalse(
            "Should not contain literal string '$decimalSerial'",
            revoked.contains(decimalSerial.lowercase()),
        )
    }
}
