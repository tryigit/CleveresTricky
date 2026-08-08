package cleveres.tricky.cleverestech

import android.content.pm.IPackageManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConfigPackageCachePerformanceTest {
    private lateinit var mockPm: IPackageManager
    private val callLatencyMs = 50L

    @Before
    fun setup() {
        Config.reset()

        // Create dynamic proxy for IPackageManager
        mockPm =
            Proxy.newProxyInstance(
                IPackageManager::class.java.classLoader,
                arrayOf(IPackageManager::class.java),
            ) { _, method, args ->
                if (method.name == "getPackagesForUid") {
                    Thread.sleep(callLatencyMs)
                    val uid = args[0] as Int
                    return@newProxyInstance arrayOf("com.example.app$uid")
                }
                null
            } as IPackageManager

        // Reflection to set Config.iPm
        val field = Config::class.java.getDeclaredField("iPm")
        field.isAccessible = true
        field.set(Config, mockPm)
    }

    @After
    fun tearDown() {
        Config.reset()
    }

    @Test
    fun testConcurrentAccessPerformance() {
        val threadCount = 32
        val pool = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)

        val uids = (1..threadCount).map { 10000 + it }

        val start = System.nanoTime()

        uids.forEach { uid ->
            pool.submit {
                try {
                    startLatch.await()
                    Config.getPackages(uid)
                } finally {
                    latch.countDown()
                }
            }
        }

        startLatch.countDown()
        val completed = latch.await(10, TimeUnit.SECONDS)
        val duration = System.nanoTime() - start
        val durationMs = TimeUnit.NANOSECONDS.toMillis(duration)

        pool.shutdownNow()

        assertTrue("Test timed out", completed)

        println("PERF_RESULT: $durationMs")
    }
}
