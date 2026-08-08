package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.regex.Pattern

class CommunityStatsTest {
    @Test
    fun testParseMemberCount() {
        val html =
            """
            <div class="tgme_page_extra">10 026 members, 750 online</div>
            """.trimIndent()

        val regex = Pattern.compile("tgme_page_extra\">([0-9 ]+) members")
        val matcher = regex.matcher(html)

        var count = "Unknown"
        if (matcher.find()) {
            count = matcher.group(1)?.trim() ?: "Unknown"
        }

        assertEquals("10 026", count)
    }

    @Test
    fun testParseMemberCountNoMatch() {
        val html =
            """
            <div class="something_else">No members here</div>
            """.trimIndent()

        val regex = Pattern.compile("tgme_page_extra\">([0-9 ]+) members")
        val matcher = regex.matcher(html)

        var count = "Unknown"
        if (matcher.find()) {
            count = matcher.group(1)?.trim() ?: "Unknown"
        }

        assertEquals("Unknown", count)
    }
}
