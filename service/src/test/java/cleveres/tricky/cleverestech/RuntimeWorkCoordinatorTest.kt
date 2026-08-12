package cleveres.tricky.cleverestech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RuntimeWorkCoordinatorTest {
    @Test
    fun retryBackoffCapsAndResetsAfterRecovery() {
        var delayMs = RUNTIME_RETRY_INITIAL_MS
        val observed = ArrayList<Long>()
        repeat(8) {
            observed.add(delayMs)
            delayMs = nextRuntimeRetryDelayMs(delayMs, healthy = false)
        }

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            observed,
        )
        assertEquals(RUNTIME_RETRY_INITIAL_MS, nextRuntimeRetryDelayMs(delayMs, healthy = true))
    }

    @Test
    fun refreshSchedulerConflatesBurstIntoSingleRefresh() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val refreshed = CountDownLatch(1)
        val count = AtomicInteger(0)
        val scheduler =
            ConflatedRefreshScheduler(scope, debounceMs = 50L) {
                count.incrementAndGet()
                refreshed.countDown()
            }

        try {
            repeat(32) { scheduler.submit() }
            assertTrue("Debounced refresh did not run", refreshed.await(2, TimeUnit.SECONDS))
            Thread.sleep(150)
            assertEquals(1, count.get())
        } finally {
            scheduler.cancel()
            scope.cancel()
        }
    }

    @Test
    fun refreshSchedulerKeepsOnlyLatestFollowUpWhileRefreshIsActive() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val count = AtomicInteger(0)
        val scheduler =
            ConflatedRefreshScheduler(scope, debounceMs = 10L) {
                when (count.incrementAndGet()) {
                    1 -> {
                        firstStarted.countDown()
                        releaseFirst.await(2, TimeUnit.SECONDS)
                    }
                    2 -> secondFinished.countDown()
                }
            }

        try {
            scheduler.submit()
            assertTrue("Initial refresh did not start", firstStarted.await(2, TimeUnit.SECONDS))
            repeat(32) { scheduler.submit() }
            releaseFirst.countDown()
            assertTrue("Conflated follow-up refresh did not run", secondFinished.await(2, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals(2, count.get())
        } finally {
            releaseFirst.countDown()
            scheduler.cancel()
            scope.cancel()
        }
    }
}
