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
    private var targetDirectory: File? = null

    @Volatile
    private var isRunning = false
    private val lock = Any()

    /**
     * Starts watching the keybox directory and its parent for filesystem changes.
     * Arms both a parent directory watcher (to detect directory recreation) and a child watcher
     * (to detect file modifications within the directory).
     *
     * @param directory The keybox directory to monitor
     */
    @Synchronized
    fun start(directory: File) {
        synchronized(lock) {
            if (isRunning) return
            isRunning = true
            targetDirectory = directory

            // Config.initialize() already started the legacy observer. Retire it first.
            Config.KeyboxDirObserver.stopWatching()

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
                                            tryArmChildLocked(directory)
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

            if (directory.exists()) {
                tryArmChildLocked(directory)
            } else {
                Logger.w("Keybox directory not present at startup, waiting for parent watcher event")
            }
        }
    }

    /**
     * Attempts to arm the child observer for the keybox directory.
     * Only arms if the directory exists and no child observer is currently active.
     *
     * @param directory The keybox directory to watch
     */
    private fun tryArmChildLocked(directory: File) {
        if (childObserver != null) return
        if (!directory.exists()) return

        try {
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
        } catch (e: Throwable) {
            Logger.e("Failed to arm keybox directory watcher", e)
        }
    }

    /**
     * Disarms and clears the child observer if one is active.
     */
    private fun disarmChildLocked() {
        childObserver?.stopWatching()
        childObserver = null
    }

    /**
     * Marks the keybox inventory as dirty and schedules a refresh.
     */
    private fun triggerRefresh() {
        Config.keyboxInventoryFingerprintDirty = true
        scheduler.submit()
    }

    /**
     * Stops all filesystem watchers and cancels any pending refresh operations.
     */
    @Synchronized
    fun stop() {
        synchronized(lock) {
            isRunning = false
            targetDirectory = null
            disarmChildLocked()
            parentObserver?.stopWatching()
            parentObserver = null
            scheduler.cancel()
        }
    }

    /**
     * Returns whether the child observer is currently active.
     *
     * @return true if the child observer is armed, false otherwise
     */
    @androidx.annotation.VisibleForTesting
    internal fun isChildObserverActiveForTesting(): Boolean = synchronized(lock) { childObserver != null }

    /**
     * Returns whether the parent observer is currently active.
     *
     * @return true if the parent observer is armed, false otherwise
     */
    @androidx.annotation.VisibleForTesting
    internal fun isParentObserverActiveForTesting(): Boolean = synchronized(lock) { parentObserver != null }

    /**
     * Injects a filesystem event into the child observer for testing purposes.
     *
     * @param event The FileObserver event mask to inject
     */
    @androidx.annotation.VisibleForTesting
    internal fun injectChildEventForTesting(event: Int) {
        synchronized(lock) {
            childObserver?.onEvent(event, null)
        }
    }

    /**
     * Injects a filesystem event into the parent observer for testing purposes.
     *
     * @param event The FileObserver event mask to inject
     * @param path The path affected by the event (relative to parent directory)
     */
    @androidx.annotation.VisibleForTesting
    internal fun injectParentEventForTesting(event: Int, path: String?) {
        synchronized(lock) {
            parentObserver?.onEvent(event, path)
        }
    }
}
