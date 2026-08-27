package cleveres.tricky.cleverestech

import java.io.File
import java.nio.file.Files as NioFiles
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainStartupContractTest {
    @Test
    fun `configured keybox source probe ignores symlinks`() {
        val root = NioFiles.createTempDirectory("cleverestricky-source-probe").toFile()
        try {
            val outside = NioFiles.createTempFile("cleverestricky-outside", ".xml").toFile()
            try {
                NioFiles.createSymbolicLink(File(root, "linked.xml").toPath(), outside.toPath())
                assertFalse(hasConfiguredKeyboxSource(root))
            } finally {
                outside.delete()
            }
            File(root, "root.cbox").writeText("placeholder")
            assertFalse(hasConfiguredKeyboxSource(root))
            File(root, "keybox.xml").writeText("placeholder")
            assertTrue(hasConfiguredKeyboxSource(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `configured keybox source probe scans the runtime keybox directory`() {
        val root = NioFiles.createTempDirectory("cleverestricky-keyboxes-probe").toFile()
        try {
            val keyboxes = File(root, "keyboxes")
            assertTrue(keyboxes.mkdir())
            File(keyboxes, "encrypted.cbox").writeText("placeholder")
            File(keyboxes, "ignored.txt").writeText("placeholder")
            assertTrue(hasConfiguredKeyboxSource(root))

            File(keyboxes, "encrypted.cbox").delete()
            assertFalse(hasConfiguredKeyboxSource(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `deferred keybox retry recovers on an early attempt`() = runBlocking {
        val waits = mutableListOf<Long>()
        var attempts = 0
        var active = false

        val recovered =
            retryDeferredKeyboxRefresh(
                isActive = { active },
                refresh = {
                    attempts++
                    active = attempts == 2
                    active
                },
                wait = { waits += it },
                retryDelaysMs = longArrayOf(1L, 5L, 15L),
            )

        assertTrue(recovered)
        assertEquals(2, attempts)
        assertEquals(listOf(1L, 5L), waits)
    }

    @Test
    fun `deferred keybox retry remains bounded when refresh never recovers`() = runBlocking {
        var attempts = 0
        val recovered =
            retryDeferredKeyboxRefresh(
                isActive = { false },
                refresh = {
                    attempts++
                    false
                },
                wait = {},
                retryDelaysMs = longArrayOf(1L, 5L),
                maxAttempts = 3,
            )

        assertFalse(recovered)
        assertEquals(3, attempts)
    }

    @Test
    fun `deferred keybox retry reuses the final backoff delay after schedule exhaustion`() = runBlocking {
        val waits = mutableListOf<Long>()
        retryDeferredKeyboxRefresh(
            isActive = { false },
            refresh = { false },
            wait = { waits += it },
            retryDelaysMs = longArrayOf(1L, 5L),
            maxAttempts = 4,
        )

        assertEquals(listOf(1L, 5L, 5L, 5L), waits)
    }

    @Test
    fun `deferred keybox retry stops when the source disappears`() = runBlocking {
        var attempts = 0
        var sourceExists = true
        val recovered =
            retryDeferredKeyboxRefresh(
                isActive = { false },
                refresh = {
                    attempts++
                    sourceExists = false
                    false
                },
                wait = {},
                retryDelaysMs = longArrayOf(1L, 5L),
                shouldRetry = { sourceExists },
            )

        assertFalse(recovered)
        assertEquals(1, attempts)
    }

    @Test
    fun `deferred keybox retry propagates cancellation from refresh`() = runBlocking {
        val cancellation = CancellationException("cancelled")
        try {
            retryDeferredKeyboxRefresh(
                isActive = { false },
                refresh = { throw cancellation },
                wait = {},
                retryDelaysMs = longArrayOf(1L),
            )
            throw AssertionError("cancellation must propagate")
        } catch (caught: CancellationException) {
            assertTrue(caught === cancellation)
        }
    }

    @Test
    fun `web ui adapter registers before backend readiness gate`() {
        val root = locateRoot()
        val source = File(root, "service/src/main/java/cleveres/tricky/cleverestech/Main.kt").readText()
        val entry = source.indexOf("fun main(args: Array<String>)")
        val registration = source.indexOf("startWebUiBridge(configDir, isTampered, webUiReady)", entry)
        val backendWait = source.indexOf("NativeBackend.awaitReady(BACKEND_STARTUP_TIMEOUT_MS)", entry)
        val gateRelease = source.indexOf("webUiReady.countDown()", backendWait)
        assertTrue(entry >= 0)
        assertTrue(registration > entry)
        assertTrue(backendWait > registration)
        assertTrue(gateRelease > backendWait)
    }

    private fun locateRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(6) {
            if (File(current, "service").isDirectory && File(current, "rust").isDirectory) return current
            current = current.parentFile ?: return@repeat
        }
        error("Repository root not found")
    }
}
