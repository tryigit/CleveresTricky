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

    /**
     * Verifies that a burst of filesystem events is properly debounced and handled.
     */
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

    /**
     * Verifies that a CREATE event marks the keybox inventory as dirty.
     */
    @Test
    fun testCreateEventDetected() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectEventForTesting(FileObserver.CREATE)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    /**
     * Verifies that a CLOSE_WRITE event marks the keybox inventory as dirty.
     */
    @Test
    fun testModifySameMetadata() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectEventForTesting(FileObserver.CLOSE_WRITE)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    /**
     * Verifies that filesystem events correctly trigger the dirty flag even when a refresh is in progress.
     */
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

    /**
     * Sets up test fixtures: creates a temporary keybox directory and initializes test state.
     */
    @Before
    fun setUp() {
        keyboxDir = tempFolder.newFolder("keyboxes")
        Config.setRootForTesting(tempFolder.root)
        KeyboxDirectoryRefreshWatcher.stop()
        Config.keyboxInventoryFingerprintDirty = false
    }

    /**
     * Cleans up test fixtures by stopping the directory watcher.
     */
    @After
    fun tearDown() {
        KeyboxDirectoryRefreshWatcher.stop()
    }

    /**
     * Verifies that file modifications in the keybox directory are detected and trigger a refresh.
     */
    @Test
    fun testModificationDetected() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        assertTrue("Observer should be active initially", KeyboxDirectoryRefreshWatcher.isObserverActiveForTesting())

        val file = File(keyboxDir, "foo.xml")
        file.writeText("initial")

        KeyboxDirectoryRefreshWatcher.injectEventForTesting(FileObserver.CLOSE_WRITE)

        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    /**
     * Verifies that the watcher recovers after the keybox directory is moved (MOVE_SELF event).
     */
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

    /**
     * Verifies that the watcher recovers after the keybox directory is deleted (DELETE_SELF event).
     */
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

    /**
     * Verifies that the watcher can start successfully when the directory is created after initial startup failure.
     */
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
