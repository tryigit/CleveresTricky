package cleveres.tricky.cleverestech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    fun refreshSchedulerKeepsOnlyLatestFollowUpWithoutCancellingActiveRefresh() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CompletableDeferred<Unit>()
        val secondFinished = CountDownLatch(1)
        val firstCancelled = AtomicBoolean(false)
        val count = AtomicInteger(0)
        val scheduler =
            ConflatedRefreshScheduler(scope, debounceMs = 10L) {
                when (count.incrementAndGet()) {
                    1 -> {
                        firstStarted.countDown()
                        try {
                            releaseFirst.await()
                        } catch (error: CancellationException) {
                            firstCancelled.set(true)
                            throw error
                        }
                    }
                    2 -> secondFinished.countDown()
                }
            }

        try {
            scheduler.submit()
            assertTrue("Initial refresh did not start", firstStarted.await(2, TimeUnit.SECONDS))
            repeat(32) { scheduler.submit() }
            Thread.sleep(50)
            assertFalse("Active refresh was cancelled by a follow-up request", firstCancelled.get())
            releaseFirst.complete(Unit)
            assertTrue("Conflated follow-up refresh did not run", secondFinished.await(2, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals(2, count.get())
            assertFalse("Active refresh was cancelled", firstCancelled.get())
        } finally {
            releaseFirst.complete(Unit)
            scheduler.cancel()
            scope.cancel()
        }
    }

    @Test
    fun refreshSchedulerRunsPendingFollowUpAfterRefreshFailure() {
        val failureObserved = CountDownLatch(1)
        val exceptionHandler = CoroutineExceptionHandler { _, _ -> failureObserved.countDown() }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + exceptionHandler)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CompletableDeferred<Unit>()
        val secondFinished = CountDownLatch(1)
        val count = AtomicInteger(0)
        val scheduler =
            ConflatedRefreshScheduler(scope, debounceMs = 10L) {
                when (count.incrementAndGet()) {
                    1 -> {
                        firstStarted.countDown()
                        releaseFirst.await()
                        throw IllegalStateException("refresh failed")
                    }
                    2 -> secondFinished.countDown()
                }
            }

        try {
            scheduler.submit()
            assertTrue("Initial refresh did not start", firstStarted.await(2, TimeUnit.SECONDS))
            scheduler.submit()
            releaseFirst.complete(Unit)
            assertTrue("Refresh failure was not observed", failureObserved.await(2, TimeUnit.SECONDS))
            assertTrue("Pending follow-up was lost after failure", secondFinished.await(2, TimeUnit.SECONDS))
            assertEquals(2, count.get())
        } finally {
            releaseFirst.complete(Unit)
            scheduler.cancel()
            scope.cancel()
        }
    }

    @Test
    fun refreshSchedulerKeepsRefreshesSerializedAcrossCancelAndRestart() {
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
            scheduler.cancel()
            scheduler.submit()
            Thread.sleep(100)
            assertEquals("Restart overlapped an in-flight refresh", 1, count.get())
            releaseFirst.countDown()
            assertTrue("Restarted refresh did not run", secondFinished.await(2, TimeUnit.SECONDS))
            assertEquals(2, count.get())
        } finally {
            releaseFirst.countDown()
            scheduler.cancel()
            scope.cancel()
        }
    }
}
