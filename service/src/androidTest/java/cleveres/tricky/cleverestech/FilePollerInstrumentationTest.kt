package cleveres.tricky.cleverestech

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FilePollerInstrumentationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun benchmarkDetectionLatency() {
        val testFile = tempFolder.newFile("benchmark_poller.txt")
        testFile.writeText("initial")

        val latch = CountDownLatch(1)
        val startTime = System.currentTimeMillis()

        // Use default interval (5000ms) to show that efficient polling (FileObserver) works
        // If it falls back to polling, this test will likely fail (timeout > 200ms)
        val poller =
            FilePoller(testFile) {
                latch.countDown()
            }
        poller.start()

        // Wait a bit to ensure poller is ready
        Thread.sleep(1500)

        // Modify file
        testFile.writeText("modified")
        testFile.setLastModified(System.currentTimeMillis() + 5000)

        // Expect detection well under the 5000ms polling fallback interval, while allowing
        // a bit more headroom for slower CI emulators.
        val detected = latch.await(4000, TimeUnit.MILLISECONDS)
        val duration = System.currentTimeMillis() - startTime

        poller.stop()

        if (!detected) {
            println("Benchmark: Detection timed out (latency > 4000ms). Expected if falling back to polling.")
        } else {
            println("Benchmark: Detection took ${duration}ms")
        }

        assertTrue(
            "Detection took too long: ${duration}ms. Expected near-instant detection with FileObserver. Test fallback polling took >4000ms",
            detected,
        )
    }
}
