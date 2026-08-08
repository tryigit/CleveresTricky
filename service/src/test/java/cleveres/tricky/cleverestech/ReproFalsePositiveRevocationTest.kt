package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.junit.Assert.assertTrue
import org.junit.Test

class ReproFalsePositiveRevocationTest {
    @Test
    fun testAmbiguousKeyIsIncludedToAvoidSecurityBypass() {
        // A 32-character string that consists only of digits.
        // It is a valid Decimal number (Google CRL format).
        // It is ALSO a valid Hex string (if interpreted as Hex Key ID).
        //
        // We accept the risk of False Positive (revoking a cert with serial 'decimalSerial' treated as hex)
        // in exchange for ensuring we don't miss a banned Key ID.

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

        // We expect it to be included as literal hex.
        // We now prioritize Security (catching potentially revoked hashes) over avoiding rare False Positives.
        org.junit.Assert.assertTrue(
            "Should contain literal string '$decimalSerial' (Security Fix)",
            revoked.contains(decimalSerial.lowercase()),
        )
    }
}
