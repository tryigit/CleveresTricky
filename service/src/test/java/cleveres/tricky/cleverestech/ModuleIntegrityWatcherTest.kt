package cleveres.tricky.cleverestech

import android.os.FileObserver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

class ModuleIntegrityWatcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val violations = CopyOnWriteArrayList<List<String>>()
    private val testManifest = ParsedManifest(
        version = 1,
        files = listOf(
            ManifestFileEntry("test.so", "a".repeat(64), "regular"),
            ManifestFileEntry("inject", "b".repeat(64), "executable"),
        ),
        signature = "c".repeat(64),
    )

    @Before
    fun setUp() {
        violations.clear()
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
    fun startArmsObservers() {
        val dir = tempFolder.newFolder("modules", "cleverestricky")
        ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }
        assertTrue(ModuleIntegrityWatcher.isChildObserverActiveForTesting())
        assertTrue(ModuleIntegrityWatcher.isParentObserverActiveForTesting())
    }

    @Test
    fun stopDisarmsObservers() {
        val dir = tempFolder.newFolder("modules", "cleverestricky")
        ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }
        ModuleIntegrityWatcher.stop()
        assertFalse(ModuleIntegrityWatcher.isChildObserverActiveForTesting())
        assertFalse(ModuleIntegrityWatcher.isParentObserverActiveForTesting())
    }

    @Test
    fun deleteSelfTriggersViolation() {
        val dir = tempFolder.newFolder("modules", "cleverestricky")
        ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }
        ModuleIntegrityWatcher.injectChildEventForTesting(FileObserver.DELETE_SELF, null)
        assertTrue(violations.isNotEmpty())
        assertFalse(ModuleIntegrityWatcher.isChildObserverActiveForTesting())
    }

    @Test
    fun moveSelfTriggersViolation() {
        val dir = tempFolder.newFolder("modules", "cleverestricky")
        ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }
        ModuleIntegrityWatcher.injectChildEventForTesting(FileObserver.MOVE_SELF, null)
        assertTrue(violations.isNotEmpty())
        assertFalse(ModuleIntegrityWatcher.isChildObserverActiveForTesting())
    }

    @Test
    fun deleteOfCriticalPayloadTriggersViolation() {
        val dir = tempFolder.newFolder("modules", "cleverestricky")
        ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }
        ModuleIntegrityWatcher.injectChildEventForTesting(FileObserver.DELETE, "test.so")
        assertTrue(violations.isNotEmpty())
        assertTrue(violations.any { it.any { v -> v.contains("test.so") } })
    }

    @Test
    fun parentDeleteTriggersViolation() {
        val parent = tempFolder.newFolder("modules")
        val dir = java.io.File(parent, "cleverestricky")
        dir.mkdirs()
        ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }
        ModuleIntegrityWatcher.injectParentEventForTesting(
            FileObserver.DELETE,
            dir.name,
        )
        assertTrue(violations.isNotEmpty())
    }

    @Test
    fun startWithMissingDirectoryTriggersViolation() {
        val dir = java.io.File(tempFolder.root, "nonexistent/cleverestricky")
        dir.parentFile?.mkdirs()
        ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }
        assertTrue(violations.isNotEmpty())
    }

    @Test
    fun startStopStartIsIdempotent() {
        val dir = tempFolder.newFolder("modules", "cleverestricky")
        ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }
        ModuleIntegrityWatcher.stop()
        ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }
        assertTrue(ModuleIntegrityWatcher.isChildObserverActiveForTesting())
        ModuleIntegrityWatcher.stop()
    }

    @Test
    fun `retired callbacks cannot affect a restarted watcher generation`() {
        val dir = tempFolder.newFolder("modules", "cleverestricky_generation")
        val parentObservers = mutableListOf<FileObserver>()
        val childObservers = mutableListOf<FileObserver>()
        val currentViolations = CopyOnWriteArrayList<List<String>>()
        ModuleIntegrityWatcher.parentObserverStarter = { parentObservers += it }
        ModuleIntegrityWatcher.childObserverStarter = { childObservers += it }

        ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }
        assertEquals(1, parentObservers.size)
        assertEquals(1, childObservers.size)
        val retiredParent = parentObservers.single()
        val retiredChild = childObservers.single()

        ModuleIntegrityWatcher.stop()
        ModuleIntegrityWatcher.start(dir, testManifest) { currentViolations.add(it) }
        assertEquals(2, parentObservers.size)
        assertEquals(2, childObservers.size)
        assertTrue(ModuleIntegrityWatcher.isChildObserverActiveForTesting())

        retiredChild.onEvent(FileObserver.DELETE_SELF, null)
        retiredParent.onEvent(FileObserver.DELETE, dir.name)

        assertTrue(
            "Retired callbacks must not disarm child coverage owned by the restarted generation",
            ModuleIntegrityWatcher.isChildObserverActiveForTesting(),
        )
        assertTrue("Retired callbacks must not report violations to the new handler", currentViolations.isEmpty())
    }

    @Test
    fun `retired child cannot affect a rearmed child in the same watcher generation`() {
        val dir = tempFolder.newFolder("modules", "cleverestricky_rearm")
        val childObservers = mutableListOf<FileObserver>()
        ModuleIntegrityWatcher.parentObserverStarter = { }
        ModuleIntegrityWatcher.childObserverStarter = { childObservers += it }

        ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }
        assertEquals(1, childObservers.size)
        val retiredChild = childObservers.single()

        ModuleIntegrityWatcher.injectParentEventForTesting(FileObserver.DELETE, dir.name)
        assertFalse(ModuleIntegrityWatcher.isChildObserverActiveForTesting())
        ModuleIntegrityWatcher.injectParentEventForTesting(FileObserver.CREATE, dir.name)
        assertTrue(ModuleIntegrityWatcher.isChildObserverActiveForTesting())
        assertEquals(2, childObservers.size)

        // The current parent CREATE schedules a full fail-closed verification. Let that current
        // generation work finish, then isolate the effect of the retired child callback itself.
        Thread.sleep(250)
        violations.clear()
        retiredChild.onEvent(FileObserver.DELETE_SELF, null)

        assertTrue(
            "A child retired before re-arm must not disarm the current child observer",
            ModuleIntegrityWatcher.isChildObserverActiveForTesting(),
        )
        assertTrue("A retired child callback must not report a new violation", violations.isEmpty())
    }

    @Test
    fun nonCriticalDeleteDoesNotTriggerViolation() {
        val dir = tempFolder.newFolder("modules", "cleverestricky")
        ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }
        ModuleIntegrityWatcher.injectChildEventForTesting(FileObserver.DELETE, "supervisor.pid")
        assertTrue(violations.isEmpty())
    }

    @Test
    fun armsSubdirectoryObservers() {
        val dir = tempFolder.newFolder("modules", "cleverestricky")
        java.io.File(dir, "webroot").mkdirs()
        val manifestWithSubdir = ParsedManifest(
            version = 1,
            files = listOf(
                ManifestFileEntry("webroot/index.html", "a".repeat(64), "regular"),
                ManifestFileEntry("test.so", "b".repeat(64), "regular"),
            ),
            signature = "c".repeat(64),
        )
        ModuleIntegrityWatcher.start(dir, manifestWithSubdir) { violations.add(it) }
        assertEquals(1, ModuleIntegrityWatcher.subObserverCountForTesting())

        ModuleIntegrityWatcher.injectSubEventForTesting(0, FileObserver.DELETE, "index.html")
        assertTrue(violations.isNotEmpty())
        assertTrue(violations.any { it.any { v -> v.contains("webroot/index.html") } })

        ModuleIntegrityWatcher.stop()
        assertEquals(0, ModuleIntegrityWatcher.subObserverCountForTesting())
    }

    @Test
    fun subdirectoryDeleteSelfTriggersViolation() {
        val dir = tempFolder.newFolder("modules", "cleverestricky")
        java.io.File(dir, "webroot").mkdirs()
        val manifestWithSubdir = ParsedManifest(
            version = 1,
            files = listOf(
                ManifestFileEntry("webroot/index.html", "a".repeat(64), "regular"),
            ),
            signature = "c".repeat(64),
        )
        ModuleIntegrityWatcher.start(dir, manifestWithSubdir) { violations.add(it) }
        ModuleIntegrityWatcher.injectSubEventForTesting(0, FileObserver.DELETE_SELF, null)
        assertTrue(violations.isNotEmpty())
        assertTrue(violations.any { it.any { v -> v.contains("webroot") } })
    }

    @Test
    fun singleFileModifyTriggersTargetedVerificationNotFull() {
        val dir = tempFolder.newFolder("modules", "cleverestricky")
        val testFile = java.io.File(dir, "test.so")
        testFile.writeBytes(ByteArray(16))
        ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }

        ModuleIntegrityWatcher.injectChildEventForTesting(FileObserver.MODIFY, "test.so")
        Thread.sleep(250)

        assertEquals(1, ModuleIntegrityWatcher.targetedVerificationExecutions.get())
        assertEquals(0, ModuleIntegrityWatcher.fullVerificationExecutions.get())
        assertEquals(0, ModuleIntegrityVerifier.fullVerificationCount.get())
    }

    @Test
    fun repeatedModifyEventsAreCoalesced() {
        val dir = tempFolder.newFolder("modules", "cleverestricky")
        val testFile = java.io.File(dir, "test.so")
        testFile.writeBytes(ByteArray(16))
        ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }

        ModuleIntegrityWatcher.injectChildEventForTesting(FileObserver.MODIFY, "test.so")
        ModuleIntegrityWatcher.injectChildEventForTesting(FileObserver.MODIFY, "test.so")
        ModuleIntegrityWatcher.injectChildEventForTesting(FileObserver.MODIFY, "test.so")

        assertTrue(ModuleIntegrityWatcher.eventCoalescedCount.get() >= 2)
        Thread.sleep(250)

        assertEquals(1, ModuleIntegrityWatcher.targetedVerificationExecutions.get())
        assertEquals(0, ModuleIntegrityWatcher.fullVerificationExecutions.get())
    }

    @Test
    fun ignoredFilesDoNotTriggerAnyVerification() {
        val dir = tempFolder.newFolder("modules", "cleverestricky")
        ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }

        ModuleIntegrityWatcher.injectChildEventForTesting(FileObserver.MODIFY, "daemon.pid")
        ModuleIntegrityWatcher.injectChildEventForTesting(FileObserver.MODIFY, "adapter.pid")
        ModuleIntegrityWatcher.injectChildEventForTesting(FileObserver.CLOSE_WRITE, "spoof_build_vars")
        Thread.sleep(250)

        assertEquals(0, ModuleIntegrityWatcher.pendingDirtyCountForTesting())
        assertEquals(0, ModuleIntegrityWatcher.targetedVerificationExecutions.get())
        assertEquals(0, ModuleIntegrityWatcher.fullVerificationExecutions.get())
    }

    @Test
    fun watcherRegistrationCountersTrackActiveObservers() {
        val dir = tempFolder.newFolder("modules", "cleverestricky")
        java.io.File(dir, "webroot").mkdirs()
        val manifestWithSubdir = ParsedManifest(
            version = 1,
            files = listOf(
                ManifestFileEntry("webroot/index.html", "a".repeat(64), "regular"),
                ManifestFileEntry("test.so", "b".repeat(64), "regular"),
            ),
            signature = "c".repeat(64),
        )
        ModuleIntegrityWatcher.start(dir, manifestWithSubdir) { violations.add(it) }
        assertEquals(3, ModuleIntegrityWatcher.watcherRegistrationCount.get())
    }

    @Test
    fun parentObserverFailureThrowsAndFailsClosed() {
        val dir = tempFolder.newFolder("modules", "cleverestricky")
        ModuleIntegrityWatcher.parentObserverStarter = {
            throw RuntimeException("Injected parent observer registration failure")
        }

        var threw = false
        try {
            ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }
        } catch (e: Throwable) {
            threw = true
            assertTrue(e.message?.contains("Injected parent observer") == true)
        }

        assertTrue("start() must throw on parent observer failure", threw)
        assertFalse(ModuleIntegrityWatcher.isChildObserverActiveForTesting())
        assertFalse(ModuleIntegrityWatcher.isParentObserverActiveForTesting())
    }

    @Test
    fun childObserverFailureThrowsAndFailsClosed() {
        val dir = tempFolder.newFolder("modules", "cleverestricky_child_fail")
        ModuleIntegrityWatcher.childObserverStarter = {
            throw RuntimeException("Injected child observer registration failure")
        }

        var threw = false
        try {
            ModuleIntegrityWatcher.start(dir, testManifest) { violations.add(it) }
        } catch (e: Throwable) {
            threw = true
            assertTrue(e.message?.contains("Injected child observer") == true)
        }

        assertTrue("start() must throw on child observer failure", threw)
        assertEquals(1, violations.size)
        assertTrue(violations[0].any { it.contains("Failed to arm integrity child watcher") })
        assertFalse(ModuleIntegrityWatcher.isChildObserverActiveForTesting())
        assertFalse(ModuleIntegrityWatcher.isParentObserverActiveForTesting())
    }
}
