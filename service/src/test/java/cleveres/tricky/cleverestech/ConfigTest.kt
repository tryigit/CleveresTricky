package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.PackageTrie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {
    @Test
    fun testParsePackages_normal() {
        val lines =
            listOf(
                "com.example.app1",
                "com.example.app2",
            )
        val targets = Config.parsePackages(lines.asSequence())

        assertTrue(targets.matches("com.example.app1"))
        assertTrue(targets.matches("com.example.app2"))
        assertEquals(2, targets.size)
    }

    @Test
    fun testParsePackages_migratesLegacyGenerateSuffix() {
        val lines =
            listOf(
                "com.example.app1",
                "com.example.app2!",
            )
        val targets = Config.parsePackages(lines.asSequence())

        assertTrue(targets.matches("com.example.app1"))
        assertTrue(targets.matches("com.example.app2"))
        assertEquals(2, targets.size)
    }

    @Test
    fun testParsePackages_commentsAndWhitespace() {
        val lines =
            listOf(
                "# This is a comment",
                "  com.example.app1  ",
                "",
                "com.example.app2!  ",
            )
        val targets = Config.parsePackages(lines.asSequence())

        assertTrue(targets.matches("com.example.app1"))
        assertTrue(targets.matches("com.example.app2"))
        assertEquals(2, targets.size)
    }

    private fun createTrie(rules: Set<String>): PackageTrie<Boolean> {
        val trie = PackageTrie<Boolean>()
        rules.forEach { trie.add(it, true) }
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
