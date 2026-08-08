package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ReproPatchLevelTimeTest {
    @Test
    fun testGetPatchLevelRespectsClockSource() {
        // Clear cache to prevent pollution
        val dynamicPatchCacheField = Config::class.java.getDeclaredField("dynamicPatchCache")
        dynamicPatchCacheField.isAccessible = true
        (dynamicPatchCacheField.get(Config) as ConcurrentHashMap<*, *>).clear()

        // 1. Set a fixed time: 2023-05-20
        val fixedTime = Instant.parse("2023-05-20T12:00:00Z").toEpochMilli()
        val originalClock = Config.clockSource
        Config.clockSource = { fixedTime }

        // 2. Set default security patch to "today"
        // We use reflection to set private state or use updateSecurityPatch with a file
        val file = File.createTempFile("security_patch", "txt")
        file.writeText("today") // Sets default patch to today

        // Use reflection to invoke updateSecurityPatch
        val method = Config::class.java.declaredMethods.find { it.name.startsWith("updateSecurityPatch") }
        method!!.isAccessible = true
        method.invoke(Config, file)

        try {
            // 3. Get patch level
            // If it respects clockSource, it should be 20230520.
            // If it uses LocalDate.now(), it will be the ACTUAL today (e.g. 2024...)
            val level = Config.getPatchLevel(12345)

            // Expected: 202305 (convertPatchLevel(false) returns YYYYMM)
            assertEquals("Patch level should use clockSource time", 202305, level)
        } finally {
            Config.clockSource = originalClock
            file.delete()
            (dynamicPatchCacheField.get(Config) as ConcurrentHashMap<*, *>).clear()
        }
    }
}
