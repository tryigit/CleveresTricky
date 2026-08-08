package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class ConfigResetTest {
    @Test
    fun testResetClearsDynamicPatchCache() {
        // 1. Pollute dynamicPatchCache via reflection
        val field = Config::class.java.getDeclaredField("dynamicPatchCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache =
            field.get(Config) as
                @Suppress("UNCHECKED_CAST")
                ConcurrentHashMap<String, Pair<Long, Int>>

        val pollutedKey = "2023-12-05"
        val pollutedValue = System.currentTimeMillis() to 202401 // Wrong value
        cache[pollutedKey] = pollutedValue

        // Verify pollution
        assertEquals(202401, cache[pollutedKey]?.second)

        // 2. Call reset
        Config.reset()

        // 3. Verify cache is cleared
        val cacheAfterReset =
            field.get(Config) as
                @Suppress("UNCHECKED_CAST")
                ConcurrentHashMap<String, Pair<Long, Int>>
        assertEquals(0, cacheAfterReset.size)

        // 4. Verify that correct value is computed now (optional, but good sanity check)
        // We can't easily call getPatchLevel without mocking everything, but we can verify the cache is empty.
    }
}
