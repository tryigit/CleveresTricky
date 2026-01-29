package cleveres.tricky.cleverestech

import org.junit.Test
import org.junit.Assert.assertEquals

class TrimLineTest {

    // Reference implementation (old slow version)
    private fun String.referenceTrimLine() = trim().split("\n").joinToString("\n") { it.trim() }

    @Test
    fun verifyTrimLineCorrectness() {
        // Construct a large string for verification
        val sb = StringBuilder()
        for (i in 0 until 100) {
            sb.append("  Line $i content with padding  \n")
            sb.append("    \n") // Empty line
            sb.append("\tIndented line $i\t\n")
        }
        val input = sb.toString()

        // Edge cases
        val edgeCases = listOf(
            "",
            "   ",
            "\n",
            "  \n  ",
            "A",
            " A ",
            "\nA\n",
            "  A  \n  B  ",
            "A\n\nB",
            " A \n \n B ",
            "\n\n\n"
        )

        // Verify correctness for edge cases
        for (case in edgeCases) {
            assertEquals("Mismatch for case: '$case'", case.referenceTrimLine(), case.trimLine())
        }

        // Verify correctness for large input
        assertEquals(input.referenceTrimLine(), input.trimLine())
    }
}
