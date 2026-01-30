package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FilePollerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testFile: File
    private lateinit var poller: FilePoller
    private val intervalMs = 100L

    @Before
    fun setUp() {
        testFile = tempFolder.newFile("test_poller.txt")
        testFile.writeText("initial")
    }

    @After
    fun tearDown() {
        if (::poller.isInitialized) {
            poller.stop()
        }
    }

    @Test
    fun testModificationDetected() {
        val latch = CountDownLatch(1)
        var callbackFile: File? = null

        poller = FilePoller(testFile, intervalMs) {
            callbackFile = it
            latch.countDown()
        }
        poller.start()

        // Wait a bit to ensure poller started and read initial state
        Thread.sleep(intervalMs * 2)

        // Modify file
        // Ensure lastModified changes (some FS have 1s resolution)
        val oldTime = testFile.lastModified()
        var newTime = oldTime
        while (newTime <= oldTime) {
            Thread.sleep(100)
            testFile.writeText("modified")
            testFile.setLastModified(System.currentTimeMillis())
            newTime = testFile.lastModified()
        }

        assertTrue("Callback should be invoked", latch.await(2, TimeUnit.SECONDS))
        assertEquals(testFile, callbackFile)
    }

    @Test
    fun testNoFalsePositives() {
        val latch = CountDownLatch(1)
        poller = FilePoller(testFile, intervalMs) {
            latch.countDown()
        }
        poller.start()

        Thread.sleep(intervalMs * 3)
        // Should not have triggered
        assertEquals(1, latch.count)
    }

    @Test
    fun testUpdateLastModifiedPreventsTrigger() {
        val latch = CountDownLatch(1)
        poller = FilePoller(testFile, intervalMs) {
            latch.countDown()
        }
        poller.start()

        Thread.sleep(intervalMs * 2)

        // Modify file
        testFile.writeText("modified")
        val t = System.currentTimeMillis()
        // Ensure time moves forward
        if (t <= testFile.lastModified()) Thread.sleep(100)
        testFile.setLastModified(System.currentTimeMillis())

        // Manually update poller state
        poller.updateLastModified()

        // Wait for poller to run cycle
        Thread.sleep(intervalMs * 3)

        // Should NOT trigger because we updated state manually
        assertEquals(1, latch.count)
    }
}
