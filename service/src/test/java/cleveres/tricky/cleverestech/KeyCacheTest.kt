package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyCacheTest {
    @Test
    fun testCacheEviction() {
        val cache = KeyCache<String, String>(2)
        cache["1"] = "a"
        cache["2"] = "b"
        assertEquals("a", cache["1"])
        assertEquals("b", cache["2"])

        cache["3"] = "c"
        // Should evict "1" because it's the eldest
        assertNull(cache["1"])
        assertEquals("b", cache["2"])
        assertEquals("c", cache["3"])
    }

    @Test
    fun testAccessOrder() {
        val cache = KeyCache<String, String>(2)
        cache["1"] = "a"
        cache["2"] = "b"

        // Access "1", so "2" becomes the LRU (because "1" was just used)
        val v = cache["1"]
        assertEquals("a", v)

        cache["3"] = "c"
        // Should evict "2"
        assertNull(cache["2"])
        assertEquals("a", cache["1"])
        assertEquals("c", cache["3"])
    }

    @Test
    fun testUpdateValue() {
        val cache = KeyCache<String, String>(2)
        cache["1"] = "a"
        cache["2"] = "b"

        // Update "1"
        cache["1"] = "updated_a"

        // "1" is now most recently used. "2" is LRU.

        cache["3"] = "c"
        assertNull(cache["2"])
        assertEquals("updated_a", cache["1"])
        assertEquals("c", cache["3"])
    }
}
