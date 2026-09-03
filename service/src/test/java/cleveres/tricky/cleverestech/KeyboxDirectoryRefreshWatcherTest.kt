package cleveres.tricky.cleverestech

import android.os.FileObserver
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlinx.coroutines.runBlocking

class KeyboxDirectoryRefreshWatcherTest {

    @Test
    fun testEventStormDebounce() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        // Simulate event storm
        repeat(50) {
            KeyboxDirectoryRefreshWatcher.injectEventForTesting(FileObserver.CLOSE_WRITE)
            KeyboxDirectoryRefreshWatcher.injectEventForTesting(FileObserver.CREATE)
            KeyboxDirectoryRefreshWatcher.injectEventForTesting(FileObserver.CLOSE_WRITE)
        }

        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    @Test
    fun testCreateEventDetected() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectEventForTesting(FileObserver.CREATE)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    @Test
    fun testModifySameMetadata() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectEventForTesting(FileObserver.CLOSE_WRITE)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    @Test
    fun testRefreshDuringEvent() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        Config.keyboxInventoryFingerprintDirty = false

        // Emulate an event during processing
        KeyboxDirectoryRefreshWatcher.injectEventForTesting(FileObserver.CLOSE_WRITE)

        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }


    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var keyboxDir: File

    @Before
    fun setUp() {
        keyboxDir = tempFolder.newFolder("keyboxes")
        Config.setRootForTesting(tempFolder.root)
        KeyboxDirectoryRefreshWatcher.stop()
        Config.keyboxInventoryFingerprintDirty = false
    }

    @After
    fun tearDown() {
        KeyboxDirectoryRefreshWatcher.stop()
    }

    @Test
    fun testModificationDetected() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        assertTrue("Observer should be active initially", KeyboxDirectoryRefreshWatcher.isObserverActiveForTesting())

        val file = File(keyboxDir, "foo.xml")
        file.writeText("initial")

        KeyboxDirectoryRefreshWatcher.injectEventForTesting(FileObserver.CLOSE_WRITE)

        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    @Test
    fun testObserverLossRecoveryMoveSelf() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        assertTrue("Observer should be active initially", KeyboxDirectoryRefreshWatcher.isObserverActiveForTesting())

        // Simulate MOVE_SELF
        KeyboxDirectoryRefreshWatcher.injectEventForTesting(FileObserver.MOVE_SELF)

        assertFalse("Observer should be cleared after MOVE_SELF", KeyboxDirectoryRefreshWatcher.isObserverActiveForTesting())

        val newKeyboxDir = tempFolder.newFolder("keyboxes_new")
        assertTrue(keyboxDir.delete())
        assertTrue(newKeyboxDir.renameTo(keyboxDir))

        KeyboxDirectoryRefreshWatcher.checkRecoveryForTesting()

        assertTrue("Observer should be re-armed after directory restored", KeyboxDirectoryRefreshWatcher.isObserverActiveForTesting())
    }

    @Test
    fun testObserverLossRecoveryDeleteSelf() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        assertTrue("Observer should be active initially", KeyboxDirectoryRefreshWatcher.isObserverActiveForTesting())

        // Simulate DELETE_SELF
        KeyboxDirectoryRefreshWatcher.injectEventForTesting(FileObserver.DELETE_SELF)

        assertFalse("Observer should be cleared after DELETE_SELF", KeyboxDirectoryRefreshWatcher.isObserverActiveForTesting())

        val newKeyboxDir = tempFolder.newFolder("keyboxes_new")
        assertTrue(keyboxDir.delete())
        assertTrue(newKeyboxDir.renameTo(keyboxDir))

        KeyboxDirectoryRefreshWatcher.checkRecoveryForTesting()

        assertTrue("Observer should be re-armed after directory restored", KeyboxDirectoryRefreshWatcher.isObserverActiveForTesting())
    }

    @Test
    fun testObserverStartupFailure() = runBlocking {
        // Start watching a directory that doesn't exist
        val nonExistentDir = File(tempFolder.root, "does_not_exist")
        KeyboxDirectoryRefreshWatcher.start(nonExistentDir)

        assertFalse("Observer should not be active initially", KeyboxDirectoryRefreshWatcher.isObserverActiveForTesting())

        // Create the directory
        assertTrue(nonExistentDir.mkdirs())

        KeyboxDirectoryRefreshWatcher.checkRecoveryForTesting()

        assertTrue("Observer should be armed after directory is created", KeyboxDirectoryRefreshWatcher.isObserverActiveForTesting())
    }
}
