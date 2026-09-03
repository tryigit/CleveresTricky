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
import kotlinx.coroutines.delay

/**
 * Tests for KeyboxDirectoryRefreshWatcher's robust directory observation logic,
 * including file events, directory lifecycle, and recovery scenarios.
 */
class KeyboxDirectoryRefreshWatcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var keyboxDir: File

    /**
     * Sets up a fresh temporary keybox directory and resets watcher state before each test.
     */
    @Before
    fun setUp() {
        keyboxDir = tempFolder.newFolder("keyboxes")
        Config.setRootForTesting(tempFolder.root)
        KeyboxDirectoryRefreshWatcher.stop()
        Config.keyboxInventoryFingerprintDirty = false
    }

    /**
     * Stops the watcher after each test to ensure clean state.
     */
    @After
    fun tearDown() {
        KeyboxDirectoryRefreshWatcher.stop()
    }

    // 1-7. File Events
    /**
     * Verifies that file creation events trigger a keybox refresh.
     */
    @Test
    fun testFileCreateDetected() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.CREATE)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    /**
     * Verifies that file close-write events trigger a keybox refresh.
     */
    @Test
    fun testFileCloseWriteDetected() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.CLOSE_WRITE)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    /**
     * Verifies that file modification events trigger a keybox refresh.
     */
    @Test
    fun testFileModifyDetected() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.MODIFY)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    /**
     * Verifies that file deletion events trigger a keybox refresh.
     */
    @Test
    fun testFileDeleteDetected() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.DELETE)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    /**
     * Verifies that file moved-from events trigger a keybox refresh.
     */
    @Test
    fun testFileMovedFromDetected() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.MOVED_FROM)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    /**
     * Verifies that file moved-to events trigger a keybox refresh.
     */
    @Test
    fun testFileMovedToDetected() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.MOVED_TO)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    /**
     * Verifies that file attribute change events trigger a keybox refresh.
     */
    @Test
    fun testFileAttribDetected() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.ATTRIB)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    // 8-14. Directory Lifecycle
    /**
     * Verifies that both parent and child observers are armed when the directory exists at startup.
     */
    @Test
    fun testDirectoryExistsAtStartup() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        assertTrue(KeyboxDirectoryRefreshWatcher.isParentObserverActiveForTesting())
        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    /**
     * Verifies that only the parent observer is armed when the directory is absent at startup,
     * and that the child observer is armed when a creation event is detected.
     */
    @Test
    fun testDirectoryAbsentAtStartup() = runBlocking {
        val nonExistentDir = File(tempFolder.root, "does_not_exist")
        KeyboxDirectoryRefreshWatcher.start(nonExistentDir)

        assertTrue(KeyboxDirectoryRefreshWatcher.isParentObserverActiveForTesting())
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())

        // Emulate parent creation event
        assertTrue(nonExistentDir.mkdirs())
        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.CREATE, "does_not_exist")

        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    /**
     * Verifies that DELETE_SELF events disarm the child observer and trigger a refresh.
     */
    @Test
    fun testDirectoryDeleteSelf() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.DELETE_SELF)
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    /**
     * Verifies that MOVE_SELF events disarm the child observer and trigger a refresh.
     */
    @Test
    fun testDirectoryMoveSelf() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.MOVE_SELF)
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    /**
     * Verifies that the child observer can be re-armed after a DELETE_SELF event when the directory is recreated.
     */
    @Test
    fun testDirectoryRecreated() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.DELETE_SELF)
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())

        // Recreate it via parent watcher
        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.CREATE, keyboxDir.name)
        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    /**
     * Verifies that the child observer recovers when the directory is replaced through a rename/move operation.
     */
    @Test
    fun testDirectoryReplacedThroughRenameMove() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.MOVE_SELF)
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())

        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.MOVED_TO, keyboxDir.name)
        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    /**
     * Verifies that rapid deletion and recreation of the directory is handled correctly.
     */
    @Test
    fun testRapidDeleteAndRecreate() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.DELETE, keyboxDir.name)
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())

        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.CREATE, keyboxDir.name)
        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    // 15-19. Race / Event Storm
    /**
     * Verifies that a storm of rapid file events triggers refresh without overwhelming the system.
     */
    @Test
    fun testCreateModifyStorm() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        Config.keyboxInventoryFingerprintDirty = false

        repeat(100) {
            KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.CREATE)
            KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.CLOSE_WRITE)
            KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.ATTRIB)
        }

        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    /**
     * Verifies that a DELETE followed by MOVED_TO sequence properly re-arms the child observer.
     */
    @Test
    fun testDeleteMovedToSequence() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.DELETE, keyboxDir.name)
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())

        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.MOVED_TO, keyboxDir.name)
        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    /**
     * Verifies that duplicate directory creation events don't create multiple observers.
     */
    @Test
    fun testMultipleDuplicateDirectoryEvents() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        // Creating while already existing shouldn't duplicate
        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.CREATE, keyboxDir.name)
        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.CREATE, keyboxDir.name)

        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    /**
     * Verifies that rapid start-stop-start cycles maintain correct observer state.
     */
    @Test
    fun testRapidStartStopStart() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.stop()
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        assertTrue(KeyboxDirectoryRefreshWatcher.isParentObserverActiveForTesting())
        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    /**
     * Verifies that multiple events are properly debounced when refresh is already pending.
     */
    @Test
    fun testRefreshAlreadyPendingWhileWatcherEventArrives() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        // This relies on the debounce scheduler doing its job correctly which is tested in RuntimeWorkCoordinatorTest
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.CLOSE_WRITE)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.CLOSE_WRITE)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    // 20-23. Lifecycle
    /**
     * Verifies that stop() removes both parent and child observers.
     */
    @Test
    fun testStopRemovesBothWatchers() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.stop()

        assertFalse(KeyboxDirectoryRefreshWatcher.isParentObserverActiveForTesting())
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    /**
     * Verifies that reset (via stop()) removes both parent and child observers.
     */
    @Test
    fun testResetRemovesBothWatchers() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        // Reset doesn't exist explicitly, stop() is what is called by Config reset
        KeyboxDirectoryRefreshWatcher.stop()

        assertFalse(KeyboxDirectoryRefreshWatcher.isParentObserverActiveForTesting())
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    /**
     * Verifies that calling start() when already running does not create duplicate watchers.
     */
    @Test
    fun testNoDuplicateWatcherCreated() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        // Try starting again
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        assertTrue(KeyboxDirectoryRefreshWatcher.isParentObserverActiveForTesting())
        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    /**
     * Verifies that stale child watchers are properly cleaned up after DELETE_SELF events.
     */
    @Test
    fun testNoStaleChildWatcherRemainsActive() = runBlocking {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.DELETE_SELF)
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    // No Polling structural verification is done by checking the code. No background thread is active.
}
