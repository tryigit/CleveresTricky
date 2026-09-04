package cleveres.tricky.cleverestech

import android.os.FileObserver
import androidx.annotation.VisibleForTesting
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Daily Auto Identity scheduler with cancellation-safe one-shot rescheduling and bounded backoff. */
internal object CronAutoIdentity {
    const val TOGGLE_FILE = "cron_auto_identity"
    private const val INITIAL_DELAY_MS = 60_000L
    private const val SUCCESS_DELAY_MS = 24L * 60L * 60L * 1000L
    private val failureBackoffMs =
        longArrayOf(
            5L * 60L * 1000L,
            15L * 60L * 1000L,
            30L * 60L * 1000L,
            60L * 60L * 1000L,
            3L * 60L * 60L * 1000L,
            6L * 60L * 60L * 1000L,
        )

    private val lock = Any()

    @Volatile
    private var configDir: File? = null

    @Volatile
    private var executor: ScheduledExecutorService? = null

    @Volatile
    private var observer: FileObserver? = null

    @Volatile
    internal var observerStarter: (FileObserver) -> Unit = { it.startWatching() }

    @Volatile
    internal var observerStopper: (FileObserver) -> Unit = { it.stopWatching() }

    private var scheduled: ScheduledFuture<*>? = null
    private var workerGeneration = 0L
    private var inFlight = false
    private var nextRunMs = 0L
    private var lastAttemptMs = 0L
    private var lastSuccessMs = 0L
    private var lastError: String? = null
    private var failureCount = 0

    fun start(root: File) {
        IdentityCoordinator.initialize(root)
        IdentityCoordinator.withCommitBarrier {
            synchronized(lock) {
                if (configDir?.absoluteFile != root.absoluteFile) {
                    retireObserverLocked()
                    shutdownExecutorLocked()
                    configDir = root
                }
                if (observer == null) {
                    armObserverLocked(root)
                }
            }
        }
        refreshEnabled()
    }

    private fun armObserverLocked(root: File) {
        val replacement =
            object : FileObserver(root, CREATE or CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == TOGGLE_FILE) refreshEnabled()
                }
            }
        observer = replacement
        try {
            observerStarter(replacement)
        } catch (error: Throwable) {
            retireObserverLocked()
            throw error
        }
    }

    private fun retireObserverLocked() {
        val retired = observer
        observer = null
        runCatching { retired?.let { observerStopper(it) } }
            .onFailure { Logger.w("Failed to stop retired Auto Identity policy watcher", it) }
    }

    fun setEnabled(
        root: File,
        enabled: Boolean,
    ) {
        IdentityCoordinator.withCommitBarrier {
            SafeConfigStore.setMarker(root, TOGGLE_FILE, enabled)
            if (configDir?.absoluteFile == root.absoluteFile) refreshEnabled()
        }
    }

    fun isEnabled(root: File): Boolean = runCatching { SafeConfigStore.markerEnabled(root, TOGGLE_FILE) }.getOrDefault(false)

    fun onPolicyChanged() {
        if (configDir != null) refreshEnabled()
    }

    fun stop() {
        IdentityCoordinator.withCommitBarrier {
            synchronized(lock) {
                retireObserverLocked()
                shutdownExecutorLocked()
                configDir = null
            }
        }
    }

    internal fun refreshEnabled() {
        IdentityCoordinator.withCommitBarrier {
            synchronized(lock) {
                val root = configDir ?: return@synchronized
                val decision = currentDecision(root)
                if (!decision.shouldRun) {
                    shutdownExecutorLocked()
                    return@synchronized
                }
                ensureExecutorLocked()
                if (!inFlight && scheduled == null) {
                    scheduleLocked(root, workerGeneration, INITIAL_DELAY_MS)
                }
            }
        }
    }

    fun statusJson(): JSONObject =
        synchronized(lock) {
            val root = configDir
            val decision = root?.let(::currentDecision)
            JSONObject()
                .put("enabled", decision?.shouldRun == true)
                .put("global", decision?.globalLiveApply == true)
                .put("profile", decision?.profileScoped == true)
                .put("running", executor?.isShutdown == false)
                .put("inFlight", inFlight)
                .put("nextRunMs", nextRunMs)
                .put("lastAttemptMs", lastAttemptMs)
                .put("lastSuccessMs", lastSuccessMs)
                .put("lastError", lastError ?: JSONObject.NULL)
                .put("failureCount", failureCount)
        }

    private fun ensureExecutorLocked() {
        val current = executor
        if (current != null && !current.isShutdown) return
        workerGeneration++
        executor =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "CleveresTricky-AutoIdentity").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
    }

    private fun scheduleLocked(
        root: File,
        generation: Long,
        delayMs: Long,
    ) {
        val current = executor ?: return
        val boundedDelay = delayMs.coerceAtLeast(1_000L)
        nextRunMs = System.currentTimeMillis() + boundedDelay
        scheduled = current.schedule({ runCheck(root, generation) }, boundedDelay, TimeUnit.MILLISECONDS)
    }

    private fun stopWorkerLocked() {
        workerGeneration++
        scheduled?.cancel(true)
        scheduled = null
        inFlight = false
        nextRunMs = 0L
    }

    private fun shutdownExecutorLocked() {
        stopWorkerLocked()
        executor?.shutdownNow()
        executor = null
    }

    private fun ownsWork(
        root: File,
        generation: Long,
    ): Boolean =
        synchronized(lock) {
            generation == workerGeneration &&
                configDir?.absoluteFile == root.absoluteFile &&
                executor?.isShutdown == false
        }

    private fun runCheck(
        root: File,
        generation: Long,
        fetcher: () -> AutoIdentityManager.Result = { AutoIdentityManager.fetchLatest() },
    ) {
        val decision =
            synchronized(lock) {
                if (!ownsWorkLocked(root, generation)) return
                scheduled = null
                val current = currentDecision(root)
                if (!current.shouldRun) {
                    stopWorkerLocked()
                    return
                }
                inFlight = true
                lastAttemptMs = System.currentTimeMillis()
                nextRunMs = 0L
                current
            }

        val result =
            IdentityCoordinator.refresh(
                root = root,
                persistGlobal = decision.globalLiveApply,
                persistProfile = decision.profileScoped,
                liveApplyGlobal = decision.globalLiveApply,
                fetcher = fetcher,
                commitAllowed = {
                    synchronized(lock) {
                        ownsWorkLocked(root, generation) && currentDecision(root) == decision
                    }
                },
            )

        synchronized(lock) {
            if (!ownsWorkLocked(root, generation)) return
            inFlight = false
            val current = currentDecision(root)
            if (!current.shouldRun) {
                stopWorkerLocked()
                return
            }
            val failure = result.exceptionOrNull()
            if (result.isSuccess) {
                failureCount = 0
                lastError = null
                lastSuccessMs = System.currentTimeMillis()
                scheduleLocked(root, generation, SUCCESS_DELAY_MS)
                Logger.i("Auto Identity refresh completed; next run is scheduled in 24 hours")
            } else if (failure is IdentityRefreshCancelledException) {
                scheduleLocked(root, generation, INITIAL_DELAY_MS)
                Logger.d("Auto Identity policy ownership changed; refresh is rescheduled without failure backoff")
            } else {
                failureCount = (failureCount + 1).coerceAtMost(Int.MAX_VALUE)
                lastError = failure?.javaClass?.simpleName ?: "UnknownFailure"
                val delay = failureBackoffMs[minOf(failureCount - 1, failureBackoffMs.lastIndex)]
                scheduleLocked(root, generation, delay)
                Logger.w("Auto Identity refresh deferred after failure; bounded retry is scheduled")
            }
        }
    }

    private fun ownsWorkLocked(
        root: File,
        generation: Long,
    ): Boolean =
        generation == workerGeneration &&
            configDir?.absoluteFile == root.absoluteFile &&
            executor?.isShutdown == false

    private fun currentDecision(root: File): AutoIdentityPolicy.Decision =
        AutoIdentityPolicy.evaluate(isEnabled(root))

    @VisibleForTesting
    internal fun configureForTesting(root: File) {
        IdentityCoordinator.withCommitBarrier {
            synchronized(lock) {
                retireObserverLocked()
                shutdownExecutorLocked()
                configDir = root
            }
        }
    }

    @VisibleForTesting
    internal fun resetObserverHooksForTesting() {
        synchronized(lock) {
            check(observer == null) { "Cannot reset Auto Identity watcher hooks while observer is active" }
            observerStarter = { it.startWatching() }
            observerStopper = { it.stopWatching() }
        }
    }

    @VisibleForTesting
    internal fun isRunningForTesting(): Boolean =
        synchronized(lock) {
            executor?.let { !it.isShutdown && (scheduled != null || inFlight) } == true
        }

    @VisibleForTesting
    internal fun runNowForTesting(fetcher: () -> AutoIdentityManager.Result) {
        val work =
            IdentityCoordinator.withCommitBarrier {
                synchronized(lock) {
                    val root = configDir ?: error("Auto Identity test root is not configured")
                    check(currentDecision(root).shouldRun) { "Auto Identity test worker is disabled" }
                    ensureExecutorLocked()
                    scheduled?.cancel(false)
                    scheduled = null
                    nextRunMs = 0L
                    root to workerGeneration
                }
            }
        runCheck(work.first, work.second, fetcher)
    }
}
