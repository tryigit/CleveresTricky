package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {

    @Test
    fun testParsePackages_normal() {
        val lines = listOf(
            "com.example.app1",
            "com.example.app2"
        )
        val (hack, generate) = Config.parsePackages(lines, false)

        assertEquals(setOf("com.example.app1", "com.example.app2"), hack)
        assertTrue(generate.isEmpty())
    }

    @Test
    fun testParsePackages_withGenerate() {
        val lines = listOf(
            "com.example.app1",
            "com.example.app2!"
        )
        val (hack, generate) = Config.parsePackages(lines, false)

        assertEquals(setOf("com.example.app1"), hack)
        assertEquals(setOf("com.example.app2"), generate)
    }

    @Test
    fun testParsePackages_commentsAndWhitespace() {
        val lines = listOf(
            "# This is a comment",
            "  com.example.app1  ",
            "",
            "com.example.app2!  "
        )
        val (hack, generate) = Config.parsePackages(lines, false)

        assertEquals(setOf("com.example.app1"), hack)
        assertEquals(setOf("com.example.app2"), generate)
    }

    @Test
    fun testParsePackages_teeBrokenMode() {
        val lines = listOf(
            "com.example.app1",
            "com.example.app2!"
        )
        // In TEE broken mode, all packages should go to generatePackages
        val (hack, generate) = Config.parsePackages(lines, true)

        assertTrue(hack.isEmpty())
        assertEquals(setOf("com.example.app1", "com.example.app2"), generate)
    }

    @Test
    fun testMatchesPackage_exact() {
        val rules = setOf("com.example.app1", "com.example.app2")
        assertTrue(Config.matchesPackage("com.example.app1", rules))
        assertTrue(Config.matchesPackage("com.example.app2", rules))
        assertTrue(!Config.matchesPackage("com.example.app3", rules))
    }

    @Test
    fun testMatchesPackage_wildcard() {
        val rules = setOf("com.google.*", "com.example.app1")
        assertTrue(Config.matchesPackage("com.google.android.gms", rules))
        assertTrue(Config.matchesPackage("com.google.android.gsf", rules))
        assertTrue(Config.matchesPackage("com.google.something.else", rules))
        assertTrue(Config.matchesPackage("com.example.app1", rules))
        assertTrue(!Config.matchesPackage("com.example.app2", rules))
        assertTrue(!Config.matchesPackage("org.google.fake", rules))
    }
}
