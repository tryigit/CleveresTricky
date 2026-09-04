package cleveres.tricky.cleverestech

import android.os.FileObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RuntimeWorkCoordinatorRegressionTest {
    @Test
    fun `existing keybox directory requires parent and child coverage before handoff`() {
        assertTrue(
            keyboxWatcherCoverageReady(
                parentArmed = true,
                directoryExists = true,
                childArmed = true,
            ),
        )
        assertFalse(
            keyboxWatcherCoverageReady(
                parentArmed = true,
                directoryExists = true,
                childArmed = false,
            ),
        )
        assertFalse(
            keyboxWatcherCoverageReady(
                parentArmed = false,
                directoryExists = true,
                childArmed = true,
            ),
        )
    }

    @Test
    fun `absent keybox directory still requires parent coverage before handoff`() {
        assertTrue(
            keyboxWatcherCoverageReady(
                parentArmed = true,
                directoryExists = false,
                childArmed = false,
            ),
        )
        assertFalse(
            keyboxWatcherCoverageReady(
                parentArmed = false,
                directoryExists = false,
                childArmed = false,
            ),
        )
    }

    @Test
    fun `retired watcher callbacks cannot affect a restarted generation`() {
        val root = Files.createTempDirectory("keybox-watcher-generation-test").toFile()
        val directory = File(root, "keyboxes").apply { mkdirs() }
        val handles = mutableListOf<FakeWatchHandle>()
        KeyboxDirectoryRefreshWatcher.stop()
        KeyboxDirectoryRefreshWatcher.watchFactory =
            RuntimeWatchFactory { _, _, callback ->
                FakeWatchHandle(callback).also(handles::add)
            }

        try {
            KeyboxDirectoryRefreshWatcher.start(directory)
            assertEquals(2, handles.size)
            val retiredParent = handles[0]
            val retiredChild = handles[1]

            KeyboxDirectoryRefreshWatcher.stop()
            KeyboxDirectoryRefreshWatcher.start(directory)
            assertEquals(4, handles.size)
            val currentChild = handles[3]
            assertTrue(KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting())

            Config.keyboxInventoryFingerprintDirty = false
            retiredChild.emit(FileObserver.MODIFY, null)
            assertFalse(
                "A child event queued before stop must not submit refresh work after restart",
                Config.keyboxInventoryFingerprintDirty,
            )

            retiredParent.emit(FileObserver.DELETE, directory.name)
            assertTrue(
                "A retired parent callback must not disarm the child observer owned by the new generation",
                KeyboxDirectoryRefreshWatcher.isChildObserverActiveForTesting(),
            )
            assertEquals(
                "The current child observer must not be stopped by a retired parent callback",
                0,
                currentChild.stopCount,
            )
        } finally {
            KeyboxDirectoryRefreshWatcher.stop()
            KeyboxDirectoryRefreshWatcher.resetWatchFactoryForTesting()
            root.deleteRecursively()
        }
    }

    private class FakeWatchHandle(
        private val callback: (Int, String?) -> Unit,
    ) : RuntimeWatchHandle {
        var stopCount = 0
            private set

        override fun startWatching() = Unit

        override fun stopWatching() {
            stopCount++
        }

        fun emit(
            event: Int,
            path: String?,
        ) {
            callback(event, path)
        }
    }
}
