package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertTrue(hack.matches("com.example.app1"))
        assertTrue(hack.matches("com.example.app2"))
        assertEquals(2, hack.size)
        assertTrue(generate.isEmpty())
    }

    @Test
    fun testParsePackages_withGenerate() {
        val lines = listOf(
            "com.example.app1",
            "com.example.app2!"
        )
        val (hack, generate) = Config.parsePackages(lines, false)

        assertTrue(hack.matches("com.example.app1"))
        assertEquals(1, hack.size)

        assertTrue(generate.matches("com.example.app2"))
        assertEquals(1, generate.size)
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

        assertTrue(hack.matches("com.example.app1"))
        assertEquals(1, hack.size)

        assertTrue(generate.matches("com.example.app2"))
        assertEquals(1, generate.size)
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
        assertTrue(generate.matches("com.example.app1"))
        assertTrue(generate.matches("com.example.app2"))
    }

    private fun createTrie(rules: Set<String>): PackageTrie {
        val trie = PackageTrie()
        rules.forEach { trie.add(it) }
        return trie
    }

    @Test
    fun testMatchesPackage_exact() {
        val rules = createTrie(setOf("com.example.app1", "com.example.app2"))
        assertTrue(Config.matchesPackage("com.example.app1", rules))
        assertTrue(Config.matchesPackage("com.example.app2", rules))
        assertFalse(Config.matchesPackage("com.example.app3", rules))
    }

    @Test
    fun testMatchesPackage_wildcard() {
        val rules = createTrie(setOf("com.google.*", "com.example.app1"))
        assertTrue(Config.matchesPackage("com.google.android.gms", rules))
        assertTrue(Config.matchesPackage("com.google.android.gsf", rules))
        assertTrue(Config.matchesPackage("com.google.something.else", rules))
        assertTrue(Config.matchesPackage("com.example.app1", rules))
        assertFalse(Config.matchesPackage("com.example.app2", rules))
        assertFalse(Config.matchesPackage("org.google.fake", rules))
        assertFalse(Config.matchesPackage("com.google", rules))
    }
}
