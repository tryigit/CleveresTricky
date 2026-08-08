package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ReproPatchLevelFormatTest {
    @Test
    fun testPatchLevelWithoutDashes() {
        // Many users might input "20231201" instead of "2023-12-01"
        // The regex in WebServer allows alphanumeric, so "20231201" is valid input.
        // However, parsing logic requires dashes.

        val file = File.createTempFile("security_patch", "txt")
        file.writeText("20231201")

        // Use reflection to invoke updateSecurityPatch
        val method = Config::class.java.declaredMethods.find { it.name.startsWith("updateSecurityPatch") }
        method!!.isAccessible = true
        method.invoke(Config, file)

        try {
            // We expect 202312
            val level = Config.getPatchLevel(12345)

            // Current bug: returns default (202404) because parsing fails
            assertEquals("Should parse YYYYMMDD format", 202312, level)
        } finally {
            file.delete()
        }
    }
}
