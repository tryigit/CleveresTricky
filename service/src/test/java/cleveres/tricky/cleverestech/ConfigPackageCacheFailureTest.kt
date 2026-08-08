package cleveres.tricky.cleverestech

import android.content.pm.IPackageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class ConfigPackageCacheFailureTest {
    private var originalPm: IPackageManager? = null

    @Before
    fun setup() {
        val field = Config::class.java.getDeclaredField("iPm")
        field.isAccessible = true
        originalPm = field.get(Config) as IPackageManager?

        // Clear cache
        val cacheField = Config::class.java.getDeclaredField("packageCache")
        cacheField.isAccessible = true
        (cacheField.get(Config) as MutableMap<*, *>).clear()

        // Ensure PM is null initially
        field.set(Config, null)
    }

    @After
    fun tearDown() {
        val field = Config::class.java.getDeclaredField("iPm")
        field.isAccessible = true
        field.set(Config, originalPm)
    }

    @Test
    fun testCacheDoesNotPersistFailure() {
        val uid = 10123

        // 1. First call fails (PM is null)
        val res1 = Config.getPackages(uid)
        assertEquals(0, res1.size)

        // 2. Setup a working PM mock
        val workingPm =
            Proxy.newProxyInstance(
                IPackageManager::class.java.classLoader,
                arrayOf(IPackageManager::class.java),
            ) { _, method, args ->
                if (method.name == "getPackagesForUid") {
                    return@newProxyInstance arrayOf("com.example.app")
                }
                null
            }

        val field = Config::class.java.getDeclaredField("iPm")
        field.isAccessible = true
        field.set(Config, workingPm)

        // 3. Second call should succeed immediately (not wait for TTL)
        val res2 = Config.getPackages(uid)

        // If bug exists, res2 is empty (cached failure)
        // If fix works, res2 has "com.example.app"
        if (res2.isEmpty()) {
            fail("Config cached the failure result! Expected [com.example.app] but got empty.")
        }
        assertEquals("com.example.app", res2[0])
    }
}
