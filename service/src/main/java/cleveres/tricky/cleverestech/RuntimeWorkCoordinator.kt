package cleveres.tricky.cleverestech

import android.os.FileObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal const val RUNTIME_RETRY_INITIAL_MS = 1_000L
internal const val RUNTIME_RETRY_MAX_MS = 30_000L
private const val KEYBOX_REFRESH_DEBOUNCE_MS = 250L

internal fun nextRuntimeRetryDelayMs(
    currentDelayMs: Long,
    healthy: Boolean,
): Long {
    if (healthy) return RUNTIME_RETRY_INITIAL_MS
    val normalized = currentDelayMs.coerceAtLeast(RUNTIME_RETRY_INITIAL_MS)
    return if (normalized >= RUNTIME_RETRY_MAX_MS / 2) {
        RUNTIME_RETRY_MAX_MS
    } else {
        normalized * 2
    }
}

/**
 * Keeps one refresh active and at most one debounced follow-up queued.
 * A burst of FileObserver events therefore cannot fan out into parallel scans.
 */
internal class ConflatedRefreshScheduler(
    private val scope: CoroutineScope,
    private val debounceMs: Long,
    private val refresh: suspend () -> Unit,
) {
    private val executionMutex = Mutex()
    private val stateLock = Any()
    private var workerJob: Job? = null
    private var requestedGeneration = 0L

    init {
        require(debounceMs >= 0) { "debounceMs must not be negative" }
    }

    fun submit(): Job =
        synchronized(stateLock) {
            requestedGeneration++
            if (workerJob?.isActive != true) {
                workerJob = scope.launch { drainRequests() }
            }
            requireNotNull(workerJob)
        }

    private suspend fun drainRequests() {
        val currentWorker = currentCoroutineContext()[Job]
        while (true) {
            currentCoroutineContext().ensureActive()
            val generation = synchronized(stateLock) { requestedGeneration }
            if (debounceMs > 0) delay(debounceMs)

            if (synchronized(stateLock) { requestedGeneration != generation }) {
                continue
            }

            try {
                currentCoroutineContext().ensureActive()
                executionMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    refresh()
                }
                currentCoroutineContext().ensureActive()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                synchronized(stateLock) {
                    if (workerJob === currentWorker) {
                        workerJob =
                            if (requestedGeneration != generation) {
                                scope.launch { drainRequests() }
                            } else {
                                null
                            }
                    }
                }
                throw error
            }

            val finished =
                synchronized(stateLock) {
                    if (requestedGeneration == generation) {
                        workerJob = null
                        true
                    } else {
                        false
                    }
                }
            if (finished) return
        }
    }

    fun cancel() {
        synchronized(stateLock) {
            requestedGeneration++
            workerJob?.cancel()
            workerJob = null
        }
    }
}

/** Replaces the legacy one-coroutine-per-event keybox observer with a conflated watcher. */
internal object KeyboxDirectoryRefreshWatcher {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val scheduler =
        ConflatedRefreshScheduler(scope, KEYBOX_REFRESH_DEBOUNCE_MS) {
            Logger.d("Refreshing keyboxes after filesystem changes")
            Config.updateKeyBoxesSync()
        }

    private val RECOVERY_INTERVAL_MS = 5000L
    private val recoveryScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        val t = Thread(r, "KeyboxDir-Recovery")
        t.isDaemon = true
        t
    }

    private var observer: FileObserver? = null
    private var recoveryFuture: ScheduledFuture<*>? = null
    private var currentDirectory: File? = null
    @Volatile
    private var isRunning = false

    @Synchronized
    fun start(directory: File) {
        if (isRunning) return
        isRunning = true
        currentDirectory = directory

        // Config.initialize() already started the legacy observer. Start the replacement first so
        // a failure leaves the original observer intact, then retire the old one after hand-off.
        tryWatch(directory)
        Config.KeyboxDirObserver.stopWatching()

        recoveryFuture = recoveryScheduler.scheduleWithFixedDelay({
            if (!isRunning) return@scheduleWithFixedDelay
            checkRecovery()
        }, RECOVERY_INTERVAL_MS, RECOVERY_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun tryWatch(directory: File) {
        if (observer != null) return
        if (!directory.exists()) return

        try {
            val replacement =
                object : FileObserver(directory, CREATE or CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO or MODIFY or ATTRIB or DELETE_SELF or MOVE_SELF) {
                    override fun onEvent(
                        event: Int,
                        path: String?,
                    ) {
                        if ((event and DELETE_SELF) != 0 || (event and MOVE_SELF) != 0) {
                            Logger.w("Keybox directory lost via MOVE_SELF or DELETE_SELF, entering recovery")
                            synchronized(this@KeyboxDirectoryRefreshWatcher) {
                                observer?.stopWatching()
                                observer = null
                                // schedule immediate recovery check
                            }
                            Config.keyboxInventoryFingerprintDirty = true
                            scheduler.submit()
                        } else {
                            Config.keyboxInventoryFingerprintDirty = true
                            scheduler.submit()
                        }
                    }
                }
            replacement.startWatching()
            observer = replacement
            Logger.i("Keybox directory watcher armed on ${directory.absolutePath}")
        } catch (e: Throwable) {
            Logger.e("Failed to arm keybox directory watcher", e)
        }
    }

    @Synchronized
    private fun checkRecovery() {
        val dir = currentDirectory ?: return
        if (observer == null) {
            if (dir.exists()) {
                Logger.i("Keybox directory recovered, re-arming watcher")
                tryWatch(dir)
                if (observer != null) {
                    // Trigger a refresh since we might have missed events while dead
                    Config.keyboxInventoryFingerprintDirty = true
                    scheduler.submit()
                }
            }
        } else {
            // Observer exists but directory might be recreated silently without sending MOVE_SELF in some edge cases
            if (!dir.exists()) {
                 Logger.w("Keybox directory no longer exists but observer was active, entering recovery")
                 observer?.stopWatching()
                 observer = null
            }
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        currentDirectory = null
        observer?.stopWatching()
        observer = null
        recoveryFuture?.cancel(false)
        recoveryFuture = null
        scheduler.cancel()
    }

    @androidx.annotation.VisibleForTesting
    internal fun isObserverActiveForTesting(): Boolean = observer != null

    @androidx.annotation.VisibleForTesting
    internal fun injectEventForTesting(event: Int) {
        observer?.onEvent(event, null)
    }

    @androidx.annotation.VisibleForTesting
    internal fun checkRecoveryForTesting() {
        checkRecovery()
    }
}
