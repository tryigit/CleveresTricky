package cleveres.tricky.cleverestech

import android.os.FileObserver
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModuleIntegrityWatcherLifecycleRegressionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val manifest =
        ParsedManifest(
            version = 1,
            files = listOf(ManifestFileEntry("test.so", "a".repeat(64), "regular")),
            signature = "c".repeat(64),
        )

    @Before
    fun setUp() {
        Config.setRootForTesting(tempFolder.root)
        ModuleIntegrityVerifier.resetForTesting()
        ModuleIntegrityWatcher.resetForTesting()
    }

    @After
    fun tearDown() {
        ModuleIntegrityWatcher.resetForTesting()
        ModuleIntegrityVerifier.resetForTesting()
        Config.reset()
    }

    @Test
    fun `partially armed parent is stopped when starter throws`() {
        val dir = tempFolder.newFolder("modules-parent-failure", "cleverestricky")
        var attempted: FileObserver? = null
        val stopped = mutableListOf<FileObserver>()
        ModuleIntegrityWatcher.parentObserverStarter = {
            attempted = it
            throw RuntimeException("injected parent start failure")
        }
        ModuleIntegrityWatcher.observerStopper = { stopped += it }

        val result = runCatching { ModuleIntegrityWatcher.start(dir, manifest) { } }

        assertTrue(result.isFailure)
        val attemptedObserver = requireNotNull(attempted) { "Parent watcher start was never attempted" }
        assertTrue("The exact partially armed parent handle must be retired", stopped.contains(attemptedObserver))
        assertFalse(ModuleIntegrityWatcher.isParentObserverActiveForTesting())
        assertFalse(ModuleIntegrityWatcher.isChildObserverActiveForTesting())
    }

    @Test
    fun `partially armed child is stopped when starter throws`() {
        val dir = tempFolder.newFolder("modules-child-failure", "cleverestricky")
        var attempted: FileObserver? = null
        val stopped = mutableListOf<FileObserver>()
        ModuleIntegrityWatcher.parentObserverStarter = { }
        ModuleIntegrityWatcher.childObserverStarter = {
            attempted = it
            throw RuntimeException("injected child start failure")
        }
        ModuleIntegrityWatcher.observerStopper = { stopped += it }

        val result = runCatching { ModuleIntegrityWatcher.start(dir, manifest) { } }

        assertTrue(result.isFailure)
        val attemptedObserver = requireNotNull(attempted) { "Child watcher start was never attempted" }
        assertTrue("The exact partially armed child handle must be retired", stopped.contains(attemptedObserver))
        assertFalse(ModuleIntegrityWatcher.isParentObserverActiveForTesting())
        assertFalse(ModuleIntegrityWatcher.isChildObserverActiveForTesting())
    }

    @Test
    fun `partially armed subdirectory observer is stopped when starter throws`() {
        val dir = tempFolder.newFolder("modules-sub-failure", "cleverestricky")
        java.io.File(dir, "webroot").mkdirs()
        val manifestWithSubdir =
            ParsedManifest(
                version = 1,
                files = listOf(ManifestFileEntry("webroot/index.html", "a".repeat(64), "regular")),
                signature = "c".repeat(64),
            )
        var startCount = 0
        var attemptedSub: FileObserver? = null
        val stopped = mutableListOf<FileObserver>()
        ModuleIntegrityWatcher.parentObserverStarter = { }
        ModuleIntegrityWatcher.childObserverStarter = {
            startCount++
            if (startCount == 2) {
                attemptedSub = it
                throw RuntimeException("injected subdirectory start failure")
            }
        }
        ModuleIntegrityWatcher.observerStopper = { stopped += it }

        val result = runCatching { ModuleIntegrityWatcher.start(dir, manifestWithSubdir) { } }

        assertTrue(result.isFailure)
        val attemptedObserver = requireNotNull(attemptedSub) { "Subdirectory watcher start was never attempted" }
        assertTrue("The exact partially armed subdirectory handle must be retired", stopped.contains(attemptedObserver))
        assertFalse(ModuleIntegrityWatcher.isParentObserverActiveForTesting())
        assertFalse(ModuleIntegrityWatcher.isChildObserverActiveForTesting())
        assertTrue(ModuleIntegrityWatcher.subObserverCountForTesting() == 0)
    }
}
