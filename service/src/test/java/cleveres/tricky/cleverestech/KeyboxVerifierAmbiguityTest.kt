package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboxVerifierAmbiguityTest {
    @Test
    fun testParseCrlAmbiguity() {
        // "10" is ambiguous:
        // - Decimal: 10 -> Hex "a"
        // - Hex: 0x10 -> Hex "10" (Decimal 16)

        // Google CRL uses decimal strings for numbers.
        // So "10" means serial number 10 (hex "a").
        // It should NOT mean serial number 16 (hex "10").

        val json =
            """
            {
              "entries": {
                "10": "REVOKED"
              }
            }
            """.trimIndent()

        val revoked = KeyboxVerifier.parseCrl(json)
        println("Revoked: $revoked")

        // Should contain "a" (decimal 10)
        assertTrue("Should contain 'a' (dec 10)", revoked.contains("a"))

        // Should NOT contain "10" (hex 10 => dec 16)
        // If it does, then we have a false positive for serial number 16.
        assertFalse("Should NOT contain '10' (hex interpretation of '10')", revoked.contains("10"))
    }
}
