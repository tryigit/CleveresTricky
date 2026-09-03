package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class IntegrityViolationHandlerTest {

    private val deleteCalls = AtomicInteger(0)
    private val rebootCalls = AtomicInteger(0)
    private val terminateCalls = AtomicInteger(0)
    private val terminateExitCodes = CopyOnWriteArrayList<Int>()
    private val deletedPaths = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        IntegrityViolationHandler.resetForTesting()
        deleteCalls.set(0)
        rebootCalls.set(0)
        terminateCalls.set(0)
        terminateExitCodes.clear()
        deletedPaths.clear()
        IntegrityViolationHandler.deleteModule = { path ->
            deletedPaths.add(path)
            deleteCalls.incrementAndGet()
            true
        }
        IntegrityViolationHandler.rebootSystem = {
            rebootCalls.incrementAndGet()
        }
        IntegrityViolationHandler.terminateProcess = { code ->
            terminateCalls.incrementAndGet()
            terminateExitCodes.add(code)
        }
    }

    @After
    fun tearDown() {
        IntegrityViolationHandler.resetForTesting()
    }

    @Test
    fun singleViolationTriggersDeleteAndReboot() {
        IntegrityViolationHandler.handleViolation(listOf("test violation"))
        assertTrue(IntegrityViolationHandler.isViolated)
        assertEquals(1, deleteCalls.get())
        assertEquals(1, rebootCalls.get())
        assertEquals(0, terminateCalls.get())
    }

    @Test
    fun multipleViolationsAreIdempotent() {
        IntegrityViolationHandler.handleViolation(listOf("first"))
        IntegrityViolationHandler.handleViolation(listOf("second"))
        IntegrityViolationHandler.handleViolation(listOf("third"))
        assertEquals(1, deleteCalls.get())
        assertEquals(1, rebootCalls.get())
        assertEquals(0, terminateCalls.get())
    }

    @Test
    fun concurrentViolationsAreIdempotent() {
        val latch = CountDownLatch(1)
        val threads = (1..10).map { i ->
            Thread {
                latch.await()
                IntegrityViolationHandler.handleViolation(listOf("concurrent $i"))
            }
        }
        threads.forEach { it.start() }
        latch.countDown()
        threads.forEach { it.join(5000) }
        assertEquals(1, deleteCalls.get())
        assertEquals(1, rebootCalls.get())
        assertEquals(0, terminateCalls.get())
        assertTrue(IntegrityViolationHandler.isViolated)
    }

    @Test
    fun deleteFailureStillSetsViolatedFlagAndAbortsReboot() {
        IntegrityViolationHandler.deleteModule = { false }
        IntegrityViolationHandler.handleViolation(listOf("delete will fail"))
        assertTrue(IntegrityViolationHandler.isViolated)
        assertEquals(0, rebootCalls.get())
        assertEquals(1, terminateCalls.get())
        assertEquals(1, terminateExitCodes[0])
    }

    @Test
    fun deleteExceptionStillSetsViolatedFlagAndAbortsReboot() {
        IntegrityViolationHandler.deleteModule = { throw RuntimeException("I/O error") }
        IntegrityViolationHandler.handleViolation(listOf("delete throws"))
        assertTrue(IntegrityViolationHandler.isViolated)
        assertEquals(0, rebootCalls.get())
        assertEquals(1, terminateCalls.get())
        assertEquals(1, terminateExitCodes[0])
    }

    @Test
    fun rebootFailureStillKeepsViolatedFlag() {
        IntegrityViolationHandler.rebootSystem = { throw RuntimeException("reboot failed") }
        IntegrityViolationHandler.handleViolation(listOf("reboot fails"))
        assertTrue(IntegrityViolationHandler.isViolated)
        assertEquals(1, deleteCalls.get())
        assertEquals(1, terminateCalls.get())
        assertEquals(1, terminateExitCodes[0])
    }

    @Test
    fun notViolatedBeforeHandleViolation() {
        assertFalse(IntegrityViolationHandler.isViolated)
    }

    @Test
    fun resetForTestingClearsState() {
        IntegrityViolationHandler.handleViolation(listOf("test"))
        assertTrue(IntegrityViolationHandler.isViolated)
        IntegrityViolationHandler.resetForTesting()
        assertFalse(IntegrityViolationHandler.isViolated)
    }

    @Test
    fun deletedPathMatchesModuleDir() {
        IntegrityViolationHandler.handleViolation(listOf("check path"))
        assertEquals(1, deletedPaths.size)
        assertTrue(deletedPaths[0].contains("cleverestricky"))
    }

    @Test
    fun symlinkInsideModulePreservesExternalTarget() {
        val root = java.nio.file.Files.createTempDirectory("test_module_root").toFile()
        val externalTarget = java.nio.file.Files.createTempFile("external_target", ".txt").toFile()
        externalTarget.writeText("vital external system data")

        val subDir = java.io.File(root, "subdir")
        subDir.mkdirs()
        java.io.File(subDir, "payload.txt").writeText("payload")

        val symlinkFile = java.io.File(root, "external_link")
        try {
            java.nio.file.Files.createSymbolicLink(symlinkFile.toPath(), externalTarget.toPath())
        } catch (_: Exception) {
            // Symlinks not supported in host environment
            root.deleteRecursively()
            externalTarget.delete()
            return
        }

        val noFollowMethod =
            Class.forName("cleveres.tricky.cleverestech.IntegrityViolationHandlerKt")
                .getDeclaredMethod("deleteDirectoryRecursivelyNoFollow", java.nio.file.Path::class.java, Int::class.javaPrimitiveType)
        noFollowMethod.isAccessible = true
        val deleted = noFollowMethod.invoke(null, root.toPath(), 16) as Boolean

        assertTrue("Expected recursive deletion to succeed", deleted)
        assertFalse("Module directory should be deleted", root.exists())
        assertTrue("External target file MUST NOT be deleted!", externalTarget.exists())
        assertEquals("vital external system data", externalTarget.readText())
        externalTarget.delete()
    }

    @Test
    fun childDirectorySwappedToSymlinkDoesNotTraverseExternalTarget() {
        val root = java.nio.file.Files.createTempDirectory("test_module_swap_root").toFile()
        val externalDir = java.nio.file.Files.createTempDirectory("test_external_dir").toFile()
        val sensitiveFile = java.io.File(externalDir, "sensitive_system_file.txt")
        sensitiveFile.writeText("critical system data that must not be deleted")

        // Start with swappedEntry as a genuine directory
        val swappedEntry = java.io.File(root, "swapped_dir")
        swappedEntry.mkdirs()
        java.io.File(swappedEntry, "child_file.txt").writeText("initial content")

        var swapOccurred = false
        IntegrityViolationHandler.onPreDescentCheck = { entryPath ->
            if (entryPath == swappedEntry.toPath() && !swapOccurred) {
                swapOccurred = true
                swappedEntry.deleteRecursively()
                try {
                    java.nio.file.Files.createSymbolicLink(swappedEntry.toPath(), externalDir.toPath())
                } catch (_: Exception) {
                    // Symlinks not supported in host environment
                }
            }
        }

        try {
            val noFollowMethod =
                Class.forName("cleveres.tricky.cleverestech.IntegrityViolationHandlerKt")
                    .getDeclaredMethod(
                        "deleteDirectoryRecursivelyNoFollow",
                        java.nio.file.Path::class.java,
                        Int::class.javaPrimitiveType,
                    )
            noFollowMethod.isAccessible = true
            val deleted = noFollowMethod.invoke(null, root.toPath(), 16) as Boolean

            assertTrue("Expected recursive deletion to succeed", deleted)
            assertFalse("Module root should be deleted", root.exists())
            assertFalse("Swapped symlink should be deleted", swappedEntry.exists())
            assertTrue("External directory MUST NOT be traversed or deleted!", externalDir.exists())
            assertTrue("Sensitive file in external directory MUST NOT be deleted!", sensitiveFile.exists())
            assertEquals("critical system data that must not be deleted", sensitiveFile.readText())
        } finally {
            IntegrityViolationHandler.onPreDescentCheck = null
            root.deleteRecursively()
            externalDir.deleteRecursively()
        }
    }
}
