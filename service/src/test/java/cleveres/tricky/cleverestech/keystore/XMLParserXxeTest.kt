package cleveres.tricky.cleverestech.keystore

import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader

class XMLParserXxeTest {
    @Test
    fun testDtdIsIgnoredOrRejected() {
        val xml =
            """
            <!DOCTYPE foo [
              <!ENTITY xxe "vulnerable">
            ]>
            <root>&xxe;</root>
            """.trimIndent()

        try {
            // Attempt to parse XML with DTD and entity
            val parser = XMLParser(StringReader(xml))

            // If parsing succeeds, verify that the entity was NOT resolved.
            // If DTD was ignored, the entity reference &xxe; should be unresolved or empty.
            // If it resolves to "vulnerable", we have XXE!
            val text = parser.obtainPath("root")["text"]
            if (text == "vulnerable") {
                fail("XXE Vulnerability! Entity was resolved.")
            }

            // If it resolved to empty or literal "&xxe;", it's safe but unexpected for kxml2.
        } catch (e: SecurityException) {
            // Success: Explicitly rejected by our security check (DOCDECL event)
            if (e.message?.contains("DTD is not allowed") != true) {
                fail("Threw SecurityException but with unexpected message: ${e.message}")
            }
        } catch (e: Exception) {
            // Success: Rejected by parser configuration (docdecl not permitted)
            // OR Ignored by parser (unresolved entity reference)
            val msg = e.message ?: ""
            if (msg.contains("docdecl not permitted") || msg.contains("unresolved")) {
                return
            }

            // Check cause for wrapper exceptions
            if (e.cause != null) {
                val cMsg = e.cause?.message ?: ""
                if (cMsg.contains("docdecl not permitted") || cMsg.contains("unresolved")) {
                    return
                }
            }

            // If it failed for another reason (e.g. malformed), rethrow to fail test
            throw e
        }
    }
}
