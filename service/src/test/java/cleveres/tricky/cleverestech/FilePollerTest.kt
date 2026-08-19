package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FilePollerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testFile: File
    private lateinit var poller: FilePoller
    private val intervalMs = 60_000L

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

    private fun checkForChange() {
        val method = FilePoller::class.java.getDeclaredMethod("checkForChange")
        method.isAccessible = true
        method.invoke(poller)
    }

    private fun handleObserverLoss() {
        val method = FilePoller::class.java.getDeclaredMethod("handleObserverLoss")
        method.isAccessible = true
        method.invoke(poller)
    }

    private fun scheduledFallback(): Any? {
        val field = FilePoller::class.java.getDeclaredField("scheduledFuture")
        field.isAccessible = true
        return field.get(poller)
    }

    @Test
    fun testModificationDetected() {
        var callbackFile: File? = null
        poller = FilePoller(testFile, intervalMs) { callbackFile = it }
        poller.start()

        testFile.writeText("modified-content")
        checkForChange()

        assertEquals(testFile, callbackFile)
    }

    @Test
    fun testAtomicReplacementWithSameMetadataDetected() {
        var callbackFile: File? = null
        poller = FilePoller(testFile, intervalMs) { callbackFile = it }
        poller.start()

        val originalTimestamp = Files.getLastModifiedTime(testFile.toPath())
        val replacement = tempFolder.newFile("replacement.txt")
        replacement.writeText("changed")
        Files.setLastModifiedTime(replacement.toPath(), originalTimestamp)
        Files.move(replacement.toPath(), testFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        checkForChange()

        assertEquals(testFile, callbackFile)
    }

    @Test
    fun testCallbackBaselineUpdatePreventsDuplicateTrigger() {
        var callbackCount = 0
        poller =
            FilePoller(testFile, intervalMs) {
                callbackCount++
                testFile.writeText("callback-content")
                poller.updateLastModified()
            }
        poller.start()

        testFile.writeText("external-content")
        checkForChange()
        checkForChange()

        assertEquals(1, callbackCount)
    }

    @Test
    fun testNoFalsePositives() {
        var callbackCount = 0
        poller = FilePoller(testFile, intervalMs) { callbackCount++ }
        poller.start()

        checkForChange()

        assertEquals(0, callbackCount)
    }

    @Test
    fun testUpdateLastModifiedPreventsTrigger() {
        var callbackCount = 0
        poller = FilePoller(testFile, intervalMs) { callbackCount++ }
        poller.start()

        testFile.writeText("modified-content")
        poller.updateLastModified()
        checkForChange()

        assertEquals(0, callbackCount)
    }

    @Test
    fun testObserverSuccessDoesNotScheduleFallbackPolling() {
        poller = FilePoller(testFile, intervalMs) {}
        poller.start()

        assertNull(scheduledFallback())
    }

    @Test
    fun testMissingObserverDirectorySchedulesFallbackPolling() {
        val missingFile = File(tempFolder.root, "missing-parent/file.txt")
        poller = FilePoller(missingFile, intervalMs) {}
        poller.start()

        assertNotNull(scheduledFallback())
    }

    @Test
    fun testParentReplacementFallsBackWhenObserverCannotBeRearmed() {
        val watchedDir = tempFolder.newFolder("watched")
        testFile = File(watchedDir, "state.txt").apply { writeText("initial") }
        poller = FilePoller(testFile, intervalMs) {}
        poller.start()
        assertNull(scheduledFallback())

        val movedDir = File(tempFolder.root, "watched-old")
        Files.move(watchedDir.toPath(), movedDir.toPath())
        handleObserverLoss()

        assertNotNull(scheduledFallback())
    }

    @Test
    fun testFailedCallbackRetriesSameChange() {
        var callbackCount = 0
        poller =
            FilePoller(testFile, intervalMs) {
                callbackCount++
                if (callbackCount == 1) throw IllegalStateException("first attempt fails")
            }
        poller.start()

        testFile.writeText("modified-content")
        try {
            checkForChange()
        } catch (_: InvocationTargetException) {
        }
        checkForChange()

        assertEquals(2, callbackCount)
    }
}
