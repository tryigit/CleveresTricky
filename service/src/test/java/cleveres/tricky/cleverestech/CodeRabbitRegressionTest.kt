package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

class CodeRabbitRegressionTest {
    @Test
    fun `forced package process cleanup waits through interruption`() {
        val process = InterruptOnceProcess()

        try {
            InstalledPackagesCompat.terminateProcessBeforePermitRelease(process)

            assertTrue("Cleanup must request forcible termination", process.destroyed)
            assertEquals("Cleanup must retry waitFor after interruption", 2, process.waitCalls.get())
            assertFalse("Cleanup must not return while the child is alive", process.isAlive)
            assertTrue("Interrupted status must be restored after cleanup", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `keybox child observer cleanup failure is isolated after retirement`() {
        val watcher = KeyboxDirectoryRefreshWatcher
        val type = watcher::class.java
        val childField = type.getDeclaredField("childObserver").apply { isAccessible = true }
        val generationField = type.getDeclaredField("childGeneration").apply { isAccessible = true }
        val disarmMethod = type.getDeclaredMethod("disarmChildLocked").apply { isAccessible = true }
        val originalChild = childField.get(watcher)
        val originalGeneration = generationField.getLong(watcher)
        val throwingHandle =
            object : RuntimeWatchHandle {
                override fun startWatching() = Unit

                override fun stopWatching() {
                    throw IOException("injected stop failure")
                }
            }

        childField.set(watcher, throwingHandle)
        try {
            disarmMethod.invoke(watcher)

            assertNull("Retired child handle must be cleared before stopWatching", childField.get(watcher))
            assertEquals(
                "Retiring the child must invalidate callbacks even when cleanup fails",
                originalGeneration + 1,
                generationField.getLong(watcher),
            )
        } finally {
            childField.set(watcher, originalChild)
            generationField.setLong(watcher, originalGeneration)
        }
    }

    @Test
    fun `integrity watcher cleanup survives stop failures and can restart`() {
        val root = java.nio.file.Files.createTempDirectory("integrity-stop-failure").toFile()
        val directory = java.io.File(root, "modules/cleverestricky")
        val subdirectory = java.io.File(directory, "webroot")
        assertTrue(subdirectory.mkdirs())
        val manifest =
            ParsedManifest(
                version = 1,
                files = listOf(ManifestFileEntry("webroot/index.html", "a".repeat(64), "regular")),
                signature = "b".repeat(64),
            )
        val parentStarts = AtomicInteger(0)
        val childStarts = AtomicInteger(0)

        ModuleIntegrityWatcher.resetForTesting()
        ModuleIntegrityWatcher.parentObserverStarter = { parentStarts.incrementAndGet() }
        ModuleIntegrityWatcher.childObserverStarter = { childStarts.incrementAndGet() }
        ModuleIntegrityWatcher.observerStopper = { throw IOException("injected observer stop failure") }
        try {
            ModuleIntegrityWatcher.start(directory, manifest) { }
            assertTrue(ModuleIntegrityWatcher.isParentObserverActiveForTesting())
            assertTrue(ModuleIntegrityWatcher.isChildObserverActiveForTesting())
            assertEquals(1, ModuleIntegrityWatcher.subObserverCountForTesting())

            ModuleIntegrityWatcher.stop()

            assertFalse("Parent reference must clear despite stop failure", ModuleIntegrityWatcher.isParentObserverActiveForTesting())
            assertFalse("Child reference must clear despite stop failure", ModuleIntegrityWatcher.isChildObserverActiveForTesting())
            assertEquals("Sub-observer references must clear despite stop failure", 0, ModuleIntegrityWatcher.subObserverCountForTesting())

            ModuleIntegrityWatcher.observerStopper = { }
            ModuleIntegrityWatcher.start(directory, manifest) { }
            assertEquals("Restart must arm a fresh parent observer", 2, parentStarts.get())
            assertEquals("Restart must arm a fresh child and subdirectory observer", 4, childStarts.get())
            assertTrue(ModuleIntegrityWatcher.isParentObserverActiveForTesting())
            assertTrue(ModuleIntegrityWatcher.isChildObserverActiveForTesting())
            assertEquals(1, ModuleIntegrityWatcher.subObserverCountForTesting())
        } finally {
            ModuleIntegrityWatcher.observerStopper = { }
            ModuleIntegrityWatcher.stop()
            ModuleIntegrityWatcher.resetForTesting()
            root.deleteRecursively()
        }
    }

    @Test
    fun `integrity manifest deletion triggers full verification`() {
        val root = java.nio.file.Files.createTempDirectory("integrity-manifest-delete").toFile()
        val directory = java.io.File(root, "modules/cleverestricky").apply { mkdirs() }
        val violations = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        val loadedManifest = ParsedManifest(version = 1, files = emptyList(), signature = "")

        ModuleIntegrityVerifier.resetForTesting()
        ModuleIntegrityVerifier.moduleDirProvider = { directory.absolutePath }
        ModuleIntegrityVerifier.remoteDisabledForTesting = true
        ModuleIntegrityWatcher.resetForTesting()
        ModuleIntegrityWatcher.parentObserverStarter = { }
        ModuleIntegrityWatcher.childObserverStarter = { }
        ModuleIntegrityWatcher.observerStopper = { }
        try {
            ModuleIntegrityWatcher.start(directory, loadedManifest) { violations += it }
            ModuleIntegrityWatcher.injectChildEventForTesting(android.os.FileObserver.DELETE, "integrity_manifest.json")

            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (violations.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }

            assertEquals("Manifest deletion must escalate to a full verification", 1, ModuleIntegrityWatcher.fullVerificationExecutions.get())
            assertTrue("Missing trust metadata must be reported as an integrity violation", violations.flatten().any { it.contains("Manifest") })
        } finally {
            ModuleIntegrityWatcher.stop()
            ModuleIntegrityWatcher.resetForTesting()
            ModuleIntegrityVerifier.resetForTesting()
            root.deleteRecursively()
        }
    }

    private class InterruptOnceProcess : Process() {
        var destroyed = false
        val waitCalls = AtomicInteger(0)
        private var alive = true

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            if (waitCalls.incrementAndGet() == 1) {
                throw InterruptedException("injected interruption")
            }
            alive = false
            return 0
        }

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("still running")
            return 0
        }

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }

        override fun isAlive(): Boolean = alive
    }
}
