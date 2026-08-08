package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.junit.Assert.assertTrue
import org.junit.Test

class ReproFailOpenAmbiguityTest {
    @Test
    fun testParseCrlFailOpenOnAllDigitHash() {
        // A 32-digit string that is ambiguous.
        // It is a valid Decimal number.
        // It is ALSO a valid Hex Hash (MD5, 32 chars, using only 0-9).

        // This specific string "12345678901234567890123456789012"
        // If interpreted as Decimal Serial:
        //   BigInteger("123...").toString(16) -> "1056e0f36a6443de2df794..." (totally different hex)
        // If interpreted as Hex Hash:
        //   It IS the hash itself "12345678901234567890123456789012"

        val ambiguousStr = "12345678901234567890123456789012"

        val json =
            """
            {
              "entries": {
                "$ambiguousStr": "REVOKED"
              }
            }
            """.trimIndent()

        val revoked = KeyboxVerifier.parseCrl(json)
        println("Revoked Set: " + revoked)

        // FAIL OPEN CHECK:
        // The set MUST contain the string itself if it was intended as a hash.
        // Currently, it prioritizes Decimal interpretation and EXCLUDES the raw hex.
        // So this assertion is expected to FAIL.
        assertTrue("Should contain the raw hex hash '$ambiguousStr'", revoked.contains(ambiguousStr))
    }
}
