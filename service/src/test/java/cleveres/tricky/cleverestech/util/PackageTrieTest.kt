package cleveres.tricky.cleverestech.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageTrieTest {
    @Test
    fun testExactMatch() {
        val trie = PackageTrie<String>()
        trie.add("com.example.app", "config1")

        assertEquals("config1", trie.get("com.example.app"))
        assertNull(trie.get("com.example"))
        assertNull(trie.get("com.example.app.sub"))
    }

    @Test
    fun testWildcardMatch() {
        val trie = PackageTrie<String>()
        trie.add("com.google.*", "config_google")

        assertNull(trie.get("com.google"))
        assertEquals("config_google", trie.get("com.google.android"))
        assertEquals("config_google", trie.get("com.google.android.gms"))
        assertNull(trie.get("com.goo"))
    }

    @Test
    fun testOverride() {
        val trie = PackageTrie<String>()
        trie.add("com.google.*", "generic")
        trie.add("com.google.maps", "specific")

        assertEquals("generic", trie.get("com.google.android"))
        assertEquals("specific", trie.get("com.google.maps"))
        assertEquals("generic", trie.get("com.google.maps.beta"))
    }

    @Test
    fun testDeepWildcard() {
        val trie = PackageTrie<String>()
        trie.add("com.*", "root")
        trie.add("com.google.*", "google")

        assertEquals("root", trie.get("com.example"))
        assertEquals("google", trie.get("com.google.android"))
    }

    @Test
    fun testBooleanHelper() {
        val trie = PackageTrie<Boolean>()
        trie.add("com.hack.*", true)

        assertTrue(trie.matches("com.hack.app"))
        assertFalse(trie.matches("com.safe.app"))
    }

    @Test
    fun testBranchingLookup() {
        val trie = PackageTrie<String>()
        trie.add("a", "1")
        trie.add("b", "2")
        trie.add("c", "3")
        trie.add("d", "4")
        trie.add("e", "5")
        trie.add("f", "6")

        assertEquals("1", trie.get("a"))
        assertEquals("2", trie.get("b"))
        assertEquals("3", trie.get("c"))
        assertEquals("4", trie.get("d"))
        assertEquals("5", trie.get("e"))
        assertEquals("6", trie.get("f"))
        assertNull(trie.get("g"))
    }

    @Test
    fun duplicateRulesDoNotInflateSize() {
        val trie = PackageTrie<String>()
        trie.add("com.example.app", "first")
        trie.add("com.example.app", "second")
        trie.add("com.example.*", "wildcard-first")
        trie.add("com.example.*", "wildcard-second")

        assertEquals(2, trie.size)
        assertEquals("second", trie.get("com.example.app"))
        assertEquals("wildcard-second", trie.get("com.example.other"))
    }
}
