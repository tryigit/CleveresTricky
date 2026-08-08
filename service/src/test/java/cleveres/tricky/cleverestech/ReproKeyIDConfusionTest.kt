package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.junit.Assert.assertTrue
import org.junit.Test

class ReproKeyIDConfusionTest {
    @Test
    fun testAmbiguousKeyID() {
        // A 32-character string that happens to be all digits.
        // It is statistically possible for a Hex Key ID (MD5/128-bit) or truncated SHA to be all digits.
        // Google CRL uses Hex for Key IDs.
        // If "11112222333344445555666677778888" is in the CRL, it implies the Key with that ID is revoked.

        val ambiguousKey = "11112222333344445555666677778888"
        val json =
            """
            {
              "entries": {
                "$ambiguousKey": "REVOKED"
              }
            }
            """.trimIndent()

        val revoked = KeyboxVerifier.parseCrl(json)

        println("Ambiguous Key: $ambiguousKey")
        println("Revoked Set: $revoked")

        // We DO expect the set to contain the key ITSELF (treated as Hex) to prevent Fail-Open.
        // We prioritize catching potentially revoked Key IDs over avoiding rare False Positives.
        assertTrue("Should contain '$ambiguousKey' (Hex literal) (Security Fix)", revoked.contains(ambiguousKey))

        // We DO expect the Decimal interpretation (ambiguity handling).
        val decimalInterpretation = java.math.BigInteger(ambiguousKey).toString(16)
        assertTrue("Should contain '$decimalInterpretation' (Decimal interpretation)", revoked.contains(decimalInterpretation))
    }
}
