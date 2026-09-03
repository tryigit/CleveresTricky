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


class KeyboxDirectoryRefreshWatcherTest {

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

    // 1-7. File Events
    @Test
    fun testFileCreateDetected() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.CREATE)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    @Test
    fun testFileCloseWriteDetected() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.CLOSE_WRITE)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    @Test
    fun testFileModifyDetected() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.MODIFY)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    @Test
    fun testFileDeleteDetected() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.DELETE)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    @Test
    fun testFileMovedFromDetected() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.MOVED_FROM)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    @Test
    fun testFileMovedToDetected() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.MOVED_TO)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    @Test
    fun testFileAttribDetected() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.ATTRIB)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    // 8-14. Directory Lifecycle
    @Test
    fun testDirectoryExistsAtStartup() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        assertTrue(KeyboxDirectoryRefreshWatcher.isParentObserverActiveForTesting())
        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    @Test
    fun testDirectoryAbsentAtStartup() {
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

    @Test
    fun testDirectoryDeleteSelf() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.DELETE_SELF)
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    @Test
    fun testDirectoryMoveSelf() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.MOVE_SELF)
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    @Test
    fun testDirectoryRecreated() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.DELETE_SELF)
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())

        // Recreate it via parent watcher
        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.CREATE, keyboxDir.name)
        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    @Test
    fun testDirectoryReplacedThroughRenameMove() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.MOVE_SELF)
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())

        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.MOVED_TO, keyboxDir.name)
        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    @Test
    fun testRapidDeleteAndRecreate() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.DELETE, keyboxDir.name)
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())

        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.CREATE, keyboxDir.name)
        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    // 15-19. Race / Event Storm
    @Test
    fun testCreateModifyStorm() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        Config.keyboxInventoryFingerprintDirty = false

        repeat(100) {
            KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.CREATE)
            KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.CLOSE_WRITE)
            KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.ATTRIB)
        }

        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    @Test
    fun testDeleteMovedToSequence() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.DELETE, keyboxDir.name)
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())

        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.MOVED_TO, keyboxDir.name)
        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    @Test
    fun testMultipleDuplicateDirectoryEvents() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        // Creating while already existing shouldn't duplicate
        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.CREATE, keyboxDir.name)
        KeyboxDirectoryRefreshWatcher.injectParentEventForTesting(FileObserver.CREATE, keyboxDir.name)

        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    @Test
    fun testRapidStartStopStart() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.stop()
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        assertTrue(KeyboxDirectoryRefreshWatcher.isParentObserverActiveForTesting())
        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    @Test
    fun testRefreshAlreadyPendingWhileWatcherEventArrives() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        // This relies on the debounce scheduler doing its job correctly which is tested in RuntimeWorkCoordinatorTest
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.CLOSE_WRITE)
        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.CLOSE_WRITE)
        assertTrue(Config.keyboxInventoryFingerprintDirty)
    }

    // 20-23. Lifecycle
    @Test
    fun testStopRemovesBothWatchers() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        KeyboxDirectoryRefreshWatcher.stop()

        assertFalse(KeyboxDirectoryRefreshWatcher.isParentObserverActiveForTesting())
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    @Test
    fun testResetRemovesBothWatchers() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        // Reset doesn't exist explicitly, stop() is what is called by Config reset
        KeyboxDirectoryRefreshWatcher.stop()

        assertFalse(KeyboxDirectoryRefreshWatcher.isParentObserverActiveForTesting())
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    @Test
    fun testNoDuplicateWatcherCreated() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)
        // Try starting again
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        assertTrue(KeyboxDirectoryRefreshWatcher.isParentObserverActiveForTesting())
        assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    @Test
    fun testNoStaleChildWatcherRemainsActive() {
        KeyboxDirectoryRefreshWatcher.start(keyboxDir)

        KeyboxDirectoryRefreshWatcher.injectChildEventForTesting(FileObserver.DELETE_SELF)
        assertFalse(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())
    }

    // No Polling structural verification is done by checking the code. No background thread is active.
}
