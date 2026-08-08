package cleveres.tricky.cleverestech

import android.content.pm.IPackageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConfigThunderingHerdTest {
    private lateinit var mockPm: IPackageManager
    private var originalPm: IPackageManager? = null
    private val callLatencyMs = 100L
    private val callCount = AtomicInteger(0)

    @Before
    fun setup() {
        // Create dynamic proxy for IPackageManager
        val handler =
            InvocationHandler { _, method, args ->
                if (method.name == "getPackagesForUid") {
                    callCount.incrementAndGet()
                    Thread.sleep(callLatencyMs)
                    return@InvocationHandler arrayOf("com.example.app")
                }
                null
            }

        mockPm =
            Proxy.newProxyInstance(
                IPackageManager::class.java.classLoader,
                arrayOf(IPackageManager::class.java),
                handler,
            ) as IPackageManager

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
    fun testThunderingHerd() {
        val threadCount = 10
        val pool = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        val uid = 12345

        for (i in 0 until threadCount) {
            pool.submit {
                readyLatch.countDown()
                try {
                    startLatch.await()
                    Config.getPackages(uid)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        readyLatch.await()
        startLatch.countDown() // GO!
        val completed = doneLatch.await(5, TimeUnit.SECONDS)
        pool.shutdownNow()

        if (!completed) {
            throw RuntimeException("Test timed out")
        }

        // Without optimization, multiple threads might enter the "if (cached == null)" block
        // and invoke the IPC.
        // With optimization, only 1 should invoke it.
        println("Call count: ${callCount.get()}")
        assertEquals("Should only call PM once", 1, callCount.get())
    }
}
