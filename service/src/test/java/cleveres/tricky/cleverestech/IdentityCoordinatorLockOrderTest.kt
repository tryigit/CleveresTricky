package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class IdentityCoordinatorLockOrderTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        Config.reset()
        root = Files.createTempDirectory("identity-lock-order-test").toFile()
        Config.setRootForTesting(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `identity refresh acquires managed file ownership before commit barrier`() {
        val fetchCompleted = CountDownLatch(1)
        val barrierProbeAcquired = CountDownLatch(1)
        val refreshResult = AtomicReference<Result<IdentityCoordinator.RefreshOutcome>>()

        val refreshWorker =
            Thread {
                refreshResult.set(
                    IdentityCoordinator.refresh(
                        root = root,
                        persistGlobal = true,
                        persistProfile = false,
                        liveApplyGlobal = false,
                        fetcher = {
                            fetchCompleted.countDown()
                            identityResult()
                        },
                    ),
                )
            }.apply {
                isDaemon = true
                name = "identity-managed-lock-regression"
            }

        val barrierProbe =
            Thread {
                IdentityCoordinator.withCommitBarrier {
                    barrierProbeAcquired.countDown()
                }
            }.apply {
                isDaemon = true
                name = "identity-commit-barrier-probe"
            }

        synchronized(ManagedFileCoordinator.monitor) {
            refreshWorker.start()
            assertTrue("Auto Identity fetch did not complete", fetchCompleted.await(2, TimeUnit.SECONDS))

            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (refreshWorker.state != Thread.State.BLOCKED && System.nanoTime() < deadlineNanos) {
                Thread.sleep(5)
            }
            assertTrue(
                "Refresh should wait for managed-file ownership after fetching",
                refreshWorker.state == Thread.State.BLOCKED,
            )

            barrierProbe.start()
            assertTrue(
                "Refresh must not hold the identity commit barrier while waiting for managed-file ownership",
                barrierProbeAcquired.await(1, TimeUnit.SECONDS),
            )
        }

        refreshWorker.join(5_000)
        barrierProbe.join(5_000)
        assertFalse("Identity refresh did not finish after managed-file ownership was released", refreshWorker.isAlive)
        assertFalse("Identity barrier probe did not finish", barrierProbe.isAlive)
        assertNotNull(refreshResult.get())
        assertTrue("Identity refresh should succeed after the lock is released", refreshResult.get().isSuccess)
    }

    private fun identityResult(): AutoIdentityManager.Result =
        AutoIdentityManager.Result(
            model = "Pixel 9",
            product = "tokay_beta",
            device = "tokay",
            fingerprint = "google/tokay_beta/tokay:17/BP31.260801.001/12345678:user/release-keys",
            buildId = "BP31.260801.001",
            incremental = "12345678",
            release = "17",
            securityPatch = "2026-08-05",
            securityPatchEstimated = false,
        )
}
