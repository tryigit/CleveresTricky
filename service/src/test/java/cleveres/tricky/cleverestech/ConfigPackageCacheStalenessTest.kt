package cleveres.tricky.cleverestech

import android.content.pm.IPackageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Proxy

class ConfigPackageCacheStalenessTest {
    private lateinit var mockPmProxy: Any
    private var originalPm: Any? = null
    private var originalClock: (() -> Long)? = null

    // We need to access private fields of Config object
    private val configClass = Config::class.java
    private lateinit var iPmField: Field
    private lateinit var packageCacheField: Field

    // Mock state
    private val packages = mutableMapOf<Int, Array<String>>()
    private val callCounts = mutableMapOf<Int, Int>()

    @Before
    fun setup() {
        iPmField = configClass.getDeclaredField("iPm")
        iPmField.isAccessible = true
        originalPm = iPmField.get(Config)

        originalClock = Config.clockSource

        packageCacheField = configClass.getDeclaredField("packageCache")
        packageCacheField.isAccessible = true

        // Clear cache before test
        (packageCacheField.get(Config) as MutableMap<*, *>).clear()

        // Create Mock IPackageManager using Proxy
        val stubInterface = IPackageManager::class.java

        mockPmProxy =
            Proxy.newProxyInstance(
                stubInterface.classLoader,
                arrayOf(stubInterface),
            ) { _, method, args ->
                if (method.name == "getPackagesForUid") {
                    val uid = args[0] as Int
                    callCounts[uid] = (callCounts[uid] ?: 0) + 1
                    return@newProxyInstance packages[uid]
                }
                null
            }

        // Inject mock
        iPmField.set(Config, mockPmProxy)

        packages.clear()
        callCounts.clear()
    }

    @After
    fun tearDown() {
        // Restore original PM
        iPmField.set(Config, originalPm)
        // Restore clock
        if (originalClock != null) {
            Config.clockSource = originalClock!!
        }
        // Clear cache
        (packageCacheField.get(Config) as MutableMap<*, *>).clear()
    }

    @Test
    fun testCacheStaleness() {
        // Mock time
        var currentTime = 1000L
        Config.clockSource = { currentTime }

        val uid = 10123

        // 1. Install "OldApp" with UID 10123
        packages[uid] = arrayOf("com.example.oldapp")

        // 2. Call getPackages(uid) -> Should return "OldApp" and cache it (t=1000)
        val result1 = Config.getPackages(uid)
        assertEquals("Should return OldApp", "com.example.oldapp", result1.firstOrNull())
        assertEquals("Should call PM once", 1, callCounts[uid])

        // 3. Uninstall "OldApp" and install "NewApp" with SAME UID 10123
        packages[uid] = arrayOf("com.example.newapp")

        // 4. Call getPackages(uid) again immediately (t=1000) -> Should return "OldApp" (cache hit)
        val result2 = Config.getPackages(uid)
        assertEquals("Should return OldApp (cache hit)", "com.example.oldapp", result2.firstOrNull())
        assertEquals("Should NOT call PM again", 1, callCounts[uid])

        // 5. Advance time by 30 seconds (t=31000). Still within 60s TTL.
        currentTime += 30000
        val result3 = Config.getPackages(uid)
        assertEquals("Should return OldApp (within TTL)", "com.example.oldapp", result3.firstOrNull())
        assertEquals("Should NOT call PM again", 1, callCounts[uid])

        // 6. Advance time by another 31 seconds (total 61s > 60s TTL).
        currentTime += 31000 // t=62000
        val result4 = Config.getPackages(uid)

        // 7. Should now return "NewApp" because cache expired
        assertEquals("Should return NewApp (cache expired)", "com.example.newapp", result4.firstOrNull())
        assertEquals("Should call PM again", 2, callCounts[uid])
    }
}
