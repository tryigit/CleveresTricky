package cleveres.tricky.cleverestech

import android.os.FileObserver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CronAutoIdentityWatcherLifecycleRegressionTest {
    private lateinit var root: File
    private lateinit var secondRoot: File

    @Before
    fun setUp() {
        Config.reset()
        PolicyState.resetForTesting()
        root = Files.createTempDirectory("cron-watcher-lifecycle").toFile()
        secondRoot = Files.createTempDirectory("cron-watcher-lifecycle-second").toFile()
        Config.setRootForTesting(root)
        CronAutoIdentity.stop()
        CronAutoIdentity.resetObserverHooksForTesting()
    }

    @After
    fun tearDown() {
        CronAutoIdentity.stop()
        CronAutoIdentity.resetObserverHooksForTesting()
        PolicyState.resetForTesting()
        root.deleteRecursively()
        secondRoot.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `partially armed observer is retired when start throws`() {
        var attempted: FileObserver? = null
        val stopped = mutableListOf<FileObserver>()
        CronAutoIdentity.observerStarter = {
            attempted = it
            throw RuntimeException("injected watcher start failure")
        }
        CronAutoIdentity.observerStopper = { stopped += it }

        val result = runCatching { CronAutoIdentity.start(root) }

        assertTrue(result.isFailure)
        val attemptedObserver = requireNotNull(attempted) { "Auto Identity watcher start was never attempted" }
        assertTrue(
            "The exact partially armed Auto Identity watcher must be retired",
            stopped.contains(attemptedObserver),
        )
    }

    @Test
    fun `stop failure cannot retain observer ownership or block restart`() {
        val started = mutableListOf<FileObserver>()
        CronAutoIdentity.observerStarter = { started += it }
        CronAutoIdentity.observerStopper = { }
        CronAutoIdentity.start(root)
        assertEquals(1, started.size)

        CronAutoIdentity.observerStopper = { throw RuntimeException("injected watcher stop failure") }
        val stopResult = runCatching { CronAutoIdentity.stop() }
        assertTrue("Watcher cleanup failure must not escape stop()", stopResult.isSuccess)

        CronAutoIdentity.observerStopper = { }
        CronAutoIdentity.start(secondRoot)
        assertEquals(
            "A failed watcher cleanup must not leave a stale handle that suppresses restart",
            2,
            started.size,
        )
    }
}
