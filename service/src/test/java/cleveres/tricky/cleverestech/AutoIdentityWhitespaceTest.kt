package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoIdentityWhitespaceTest {
    @Test
    fun `model html whitespace is normalized`() {
        val candidates =
            AutoIdentityManager.parseDeviceCandidates(
                """
                <table>
                  <tr id="tokay"><td>Pixel <span>9</span>
                      Pro</td></tr>
                </table>
                """.trimIndent(),
            )

        assertEquals("Pixel 9 Pro", candidates.single().model)
    }
}
