package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.Logger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class KeyboxVerifierBugTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setup() {
            Logger.setImpl(
                object : Logger.LogImpl {
                    override fun d(
                        tag: String,
                        msg: String,
                    ) {}

                    override fun e(
                        tag: String,
                        msg: String,
                    ) {
                        println("E/$tag: $msg")
                    }

                    override fun e(
                        tag: String,
                        msg: String,
                        t: Throwable?,
                    ) {
                        println("E/$tag: $msg")
                    }

                    override fun i(
                        tag: String,
                        msg: String,
                    ) {}
                },
            )
        }
    }

    @Test
    fun testParseCrlDecimalAmbiguity() {
        // CRL entry "10" (Decimal 10).
        // This corresponds to Hex "a".
        // It should NOT correspond to Hex "10" (Decimal 16).

        val json =
            """
            {
              "entries": {
                "10": { "status": "REVOKED" }
              }
            }
            """.trimIndent()

        val revoked = KeyboxVerifier.parseCrl(json)
        println("Revoked Set: $revoked")

        // Should contain "a" (Decimal 10)
        assertTrue("Should revoke decimal 10 (hex 'a')", revoked.contains("a"))

        // Should NOT contain "10" (Decimal 16)
        assertFalse("Should NOT revoke decimal 16 (hex '10')", revoked.contains("10"))
    }
}
