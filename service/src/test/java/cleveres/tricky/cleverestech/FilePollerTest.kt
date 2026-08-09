package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    private fun checkForChange() {
        val method = FilePoller::class.java.getDeclaredMethod("checkForChange")
        method.isAccessible = true
        method.invoke(poller)
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
}
