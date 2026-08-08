package cleveres.tricky.cleverestech

import android.content.pm.IPackageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class ConfigPackageCacheTest {
    private lateinit var mockPm: IPackageManager
    private var originalPm: IPackageManager? = null

    // State for the mock
    private val callCounts = mutableMapOf<Int, Int>()
    private val packages = mutableMapOf<Int, Array<String>>()

    @Before
    fun setup() {
        callCounts.clear()
        packages.clear()

        // Create dynamic proxy for IPackageManager
        mockPm =
            Proxy.newProxyInstance(
                IPackageManager::class.java.classLoader,
                arrayOf(IPackageManager::class.java),
            ) { _, method, args ->
                if (method.name == "getPackagesForUid") {
                    val uid = args[0] as Int
                    callCounts[uid] = (callCounts[uid] ?: 0) + 1
                    return@newProxyInstance packages[uid] ?: emptyArray<String>()
                }
                // Return null for other methods (getPackageInfo, etc)
                null
            } as IPackageManager

        // Reflection to set Config.iPm
        val field = Config::class.java.getDeclaredField("iPm")
        field.isAccessible = true
        originalPm = field.get(Config) as IPackageManager?
        field.set(Config, mockPm)

        // Clear cache
        clearPackageCache()
    }

    @After
    fun tearDown() {
        // Restore original PM
        val field = Config::class.java.getDeclaredField("iPm")
        field.isAccessible = true
        field.set(Config, originalPm)

        clearPackageCache()
    }

    private fun clearPackageCache() {
        val field = Config::class.java.getDeclaredField("packageCache")
        field.isAccessible = true
        val cache = field.get(Config) as MutableMap<*, *>
        cache.clear()
    }

    @Test
    fun testCacheBehavior() {
        val uid = 1001
        packages[uid] = arrayOf("com.example.app1")

        // First call - should hit PM
        val result1 = Config.getPackages(uid)
        assertEquals(1, result1.size)
        assertEquals("com.example.app1", result1[0])
        assertEquals(1, callCounts[uid] ?: 0)

        // Second call - should hit cache
        val result2 = Config.getPackages(uid)
        assertEquals(1, result2.size)
        assertEquals("com.example.app1", result2[0])
        assertEquals(1, callCounts[uid] ?: 0) // Count should still be 1
    }

    @Test
    fun testCacheMiss() {
        val uid = 9999
        // No package for this UID

        val result1 = Config.getPackages(uid)
        assertEquals(0, result1.size)
        assertEquals(1, callCounts[uid] ?: 0)

        val result2 = Config.getPackages(uid)
        assertEquals(0, result2.size)
        assertEquals(1, callCounts[uid] ?: 0)
    }

    @Test
    fun testMultipleUids() {
        val uid1 = 2001
        val uid2 = 2002
        packages[uid1] = arrayOf("app1")
        packages[uid2] = arrayOf("app2")

        Config.getPackages(uid1)
        assertEquals(1, callCounts[uid1])
        assertEquals(0, callCounts[uid2] ?: 0)

        Config.getPackages(uid2)
        assertEquals(1, callCounts[uid1])
        assertEquals(1, callCounts[uid2])

        Config.getPackages(uid1)
        Config.getPackages(uid2)
        assertEquals(1, callCounts[uid1])
        assertEquals(1, callCounts[uid2])
    }
}
