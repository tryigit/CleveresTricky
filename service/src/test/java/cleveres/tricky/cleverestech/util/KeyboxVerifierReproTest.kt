package cleveres.tricky.cleverestech.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboxVerifierReproTest {
    @Test
    fun testAmbiguousNumericStringInCrl() {
        // "123456" is a valid hex string (digits only).
        // Google CRLs typically use Decimal strings for keys.
        // Treating "123456" as Hex (0x123456) when it is likely Decimal (123456 = 0x1E240)
        // causes false positives.
        // We have updated the logic to prefer Decimal interpretation.

        val json =
            """
            {
              "entries": {
                "123456" : {
                  "status": "REVOKED"
                }
              }
            }
            """.trimIndent()

        val revoked = KeyboxVerifier.parseCrl(json)

        // 123456 (Decimal) -> 1E240 (Hex)
        assertTrue("Set should contain '1e240' (Decimal interpretation)", revoked.contains("1e240"))

        // 123456 (Hex) -> 123456.
        // This is the Ambiguous Hex interpretation. We should NOT include this as it causes false positives.
        assertFalse("Set should NOT contain '123456' (Ambiguous Hex interpretation)", revoked.contains("123456"))
    }
}
