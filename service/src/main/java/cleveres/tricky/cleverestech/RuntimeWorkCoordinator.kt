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

internal fun keyboxWatcherCoverageReady(
    parentArmed: Boolean,
    directoryExists: Boolean,
    childArmed: Boolean,
): Boolean = parentArmed && (!directoryExists || childArmed)

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

    private var childObserver: FileObserver? = null
    private var parentObserver: FileObserver? = null

    @Volatile
    private var isRunning = false
    private val lock = Any()

    @Synchronized
    fun start(directory: File) {
        synchronized(lock) {
            if (isRunning) return
            isRunning = true
            try {
                val parent = directory.parentFile
                if (parent != null) {
                    try {
                        val pObserver = object : FileObserver(parent, CREATE or MOVED_TO or DELETE or MOVED_FROM) {
                            override fun onEvent(event: Int, path: String?) {
                                if (path == directory.name) {
                                    synchronized(lock) {
                                        if (!isRunning) return
                                        if ((event and (CREATE or MOVED_TO)) != 0) {
                                            Logger.i("Parent watcher detected keybox directory created/moved into place")
                                            if (directory.exists()) {
                                                if (!tryArmChildLocked(directory)) {
                                                    fallBackToLegacyLocked("Failed to re-arm keybox directory watcher")
                                                    return
                                                }
                                                triggerRefresh()
                                            }
                                        } else if ((event and (DELETE or MOVED_FROM)) != 0) {
                                            Logger.w("Parent watcher detected keybox directory removed")
                                            disarmChildLocked()
                                            triggerRefresh()
                                        }
                                    }
                                }
                            }
                        }
                        pObserver.startWatching()
                        parentObserver = pObserver
                        Logger.i("Keybox parent directory watcher armed on ${parent.absolutePath}")
                    } catch (e: Throwable) {
                        Logger.e("Failed to arm keybox parent directory watcher", e)
                    }
                }

                val directoryExists = directory.exists()
                if (directoryExists) {
                    tryArmChildLocked(directory)
                } else {
                    Logger.w("Keybox directory not present at startup, waiting for parent watcher event")
                }

                check(
                    keyboxWatcherCoverageReady(
                        parentArmed = parentObserver != null,
                        directoryExists = directoryExists,
                        childArmed = childObserver != null,
                    ),
                ) { "Failed to arm bounded keybox directory watchers" }
            } catch (error: Throwable) {
                cleanupReplacementLocked()
                isRunning = false
                throw error
            }

            runCatching { Config.KeyboxDirObserver.stopWatching() }
                .onFailure { Logger.w("Failed to retire legacy keybox observer after replacement was armed", it) }
        }
    }

    private fun tryArmChildLocked(directory: File): Boolean {
        if (childObserver != null) return true
        if (!directory.exists()) return false

        return try {
            val replacement =
                object : FileObserver(directory, CREATE or CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO or MODIFY or ATTRIB or DELETE_SELF or MOVE_SELF) {
                    override fun onEvent(
                        event: Int,
                        path: String?,
                    ) {
                        if ((event and DELETE_SELF) != 0 || (event and MOVE_SELF) != 0) {
                            Logger.w("Keybox directory lost via MOVE_SELF or DELETE_SELF")
                            synchronized(lock) {
                                disarmChildLocked()
                            }
                            triggerRefresh()
                        } else {
                            triggerRefresh()
                        }
                    }
                }
            replacement.startWatching()
            childObserver = replacement
            Logger.i("Keybox directory watcher armed on ${directory.absolutePath}")
            true
        } catch (e: Throwable) {
            Logger.e("Failed to arm keybox directory watcher", e)
            false
        }
    }

    private fun disarmChildLocked() {
        childObserver?.stopWatching()
        childObserver = null
    }

    private fun cleanupReplacementLocked() {
        runCatching { childObserver?.stopWatching() }
            .onFailure { Logger.w("Failed to stop partial keybox directory watcher", it) }
        childObserver = null
        runCatching { parentObserver?.stopWatching() }
            .onFailure { Logger.w("Failed to stop partial keybox parent watcher", it) }
        parentObserver = null
        scheduler.cancel()
    }

    private fun fallBackToLegacyLocked(reason: String) {
        Logger.e(reason)
        cleanupReplacementLocked()
        isRunning = false
        runCatching { Config.KeyboxDirObserver.startWatching() }
            .onFailure { Logger.e("Failed to restore legacy keybox observer", it) }
    }

    private fun triggerRefresh() {
        Config.keyboxInventoryFingerprintDirty = true
        scheduler.submit()
    }

    @Synchronized
    fun stop() {
        synchronized(lock) {
            isRunning = false
            cleanupReplacementLocked()
        }
    }

    internal fun isReplacementActive(): Boolean = synchronized(lock) { isRunning }

    @androidx.annotation.VisibleForTesting
    internal fun isChildObserverActiveForTesting(): Boolean = synchronized(lock) { childObserver != null }

    @androidx.annotation.VisibleForTesting
    internal fun isParentObserverActiveForTesting(): Boolean = synchronized(lock) { parentObserver != null }

    @androidx.annotation.VisibleForTesting
    internal fun injectChildEventForTesting(event: Int) {
        synchronized(lock) {
            childObserver?.onEvent(event, null)
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun injectParentEventForTesting(event: Int, path: String?) {
        synchronized(lock) {
            parentObserver?.onEvent(event, path)
        }
    }
}