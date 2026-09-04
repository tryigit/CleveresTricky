package cleveres.tricky.cleverestech

import android.os.FileObserver
import android.os.FileObserver.ATTRIB
import android.os.FileObserver.CLOSE_WRITE
import android.os.FileObserver.CREATE
import android.os.FileObserver.DELETE
import android.os.FileObserver.DELETE_SELF
import android.os.FileObserver.MODIFY
import android.os.FileObserver.MOVED_FROM
import android.os.FileObserver.MOVED_TO
import android.os.FileObserver.MOVE_SELF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

internal interface RuntimeWatchHandle {
    fun startWatching()

    fun stopWatching()
}

internal fun interface RuntimeWatchFactory {
    fun create(
        file: File,
        mask: Int,
        onEvent: (Int, String?) -> Unit,
    ): RuntimeWatchHandle
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

    private var childObserver: RuntimeWatchHandle? = null
    private var parentObserver: RuntimeWatchHandle? = null
    private var watcherGeneration = 0L
    private var childGeneration = 0L

    @Volatile
    private var isRunning = false
    private val lock = Any()

    @Volatile
    internal var watchFactory: RuntimeWatchFactory = defaultWatchFactory()

    @Synchronized
    fun start(directory: File) {
        synchronized(lock) {
            if (isRunning) return
            val generation = ++watcherGeneration
            isRunning = true
            try {
                val parent = directory.parentFile
                if (parent != null) {
                    try {
                        val pObserver =
                            watchFactory.create(parent, CREATE or MOVED_TO or DELETE or MOVED_FROM) { event, path ->
                                handleParentEvent(directory, generation, event, path)
                            }
                        parentObserver = pObserver
                        pObserver.startWatching()
                        Logger.i("Keybox parent directory watcher armed on ${parent.absolutePath}")
                    } catch (e: Throwable) {
                        runCatching { parentObserver?.stopWatching() }
                        parentObserver = null
                        Logger.e("Failed to arm keybox parent directory watcher", e)
                    }
                }

                val directoryExists = directory.exists()
                if (directoryExists) {
                    tryArmChildLocked(directory, generation)
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
                invalidateWatcherLocked()
                cleanupReplacementLocked()
                throw error
            }

            runCatching { Config.KeyboxDirObserver.stopWatching() }
                .onFailure { Logger.w("Failed to retire legacy keybox observer after replacement was armed", it) }
        }
    }

    private fun handleParentEvent(
        directory: File,
        generation: Long,
        event: Int,
        path: String?,
    ) {
        synchronized(lock) {
            if (!ownsGenerationLocked(generation) || path != directory.name) return
            if ((event and (CREATE or MOVED_TO)) != 0) {
                Logger.i("Parent watcher detected keybox directory created/moved into place")
                if (directory.exists()) {
                    if (!tryArmChildLocked(directory, generation)) {
                        fallBackToLegacyLocked("Failed to re-arm keybox directory watcher")
                        return
                    }
                    triggerRefreshLocked()
                }
            } else if ((event and (DELETE or MOVED_FROM)) != 0) {
                Logger.w("Parent watcher detected keybox directory removed")
                disarmChildLocked()
                triggerRefreshLocked()
            }
        }
    }

    private fun tryArmChildLocked(
        directory: File,
        generation: Long,
    ): Boolean {
        if (!ownsGenerationLocked(generation)) return false
        if (childObserver != null) return true
        if (!directory.exists()) return false

        val childToken = ++childGeneration
        var replacement: RuntimeWatchHandle? = null
        return try {
            val handle =
                watchFactory.create(
                    directory,
                    CREATE or CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO or MODIFY or ATTRIB or DELETE_SELF or MOVE_SELF,
                ) { event, _ ->
                    handleChildEvent(generation, childToken, event)
                }
            replacement = handle
            childObserver = handle
            handle.startWatching()
            Logger.i("Keybox directory watcher armed on ${directory.absolutePath}")
            true
        } catch (e: Throwable) {
            if (childObserver === replacement) childObserver = null
            childGeneration++
            runCatching { replacement?.stopWatching() }
            Logger.e("Failed to arm keybox directory watcher", e)
            false
        }
    }

    private fun handleChildEvent(
        generation: Long,
        childToken: Long,
        event: Int,
    ) {
        synchronized(lock) {
            if (!ownsGenerationLocked(generation) || childToken != childGeneration) return
            if ((event and DELETE_SELF) != 0 || (event and MOVE_SELF) != 0) {
                Logger.w("Keybox directory lost via MOVE_SELF or DELETE_SELF")
                disarmChildLocked()
            }
            triggerRefreshLocked()
        }
    }

    private fun ownsGenerationLocked(generation: Long): Boolean =
        isRunning && generation == watcherGeneration

    private fun invalidateWatcherLocked() {
        isRunning = false
        watcherGeneration++
    }

    private fun disarmChildLocked() {
        childGeneration++
        val retired = childObserver
        childObserver = null
        runCatching { retired?.stopWatching() }
            .onFailure { Logger.w("Failed to stop retired keybox directory watcher", it) }
    }

    private fun cleanupReplacementLocked() {
        runCatching { disarmChildLocked() }
            .onFailure { Logger.w("Failed to stop partial keybox directory watcher", it) }
        runCatching { parentObserver?.stopWatching() }
            .onFailure { Logger.w("Failed to stop partial keybox parent watcher", it) }
        parentObserver = null
        scheduler.cancel()
    }

    private fun fallBackToLegacyLocked(reason: String) {
        Logger.e(reason)
        invalidateWatcherLocked()
        cleanupReplacementLocked()
        runCatching { Config.KeyboxDirObserver.startWatching() }
            .onFailure { Logger.e("Failed to restore legacy keybox observer", it) }
    }

    private fun triggerRefreshLocked() {
        if (!isRunning) return
        Config.keyboxInventoryFingerprintDirty = true
        scheduler.submit()
    }

    @Synchronized
    fun stop() {
        synchronized(lock) {
            invalidateWatcherLocked()
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
        val state = synchronized(lock) { watcherGeneration to childGeneration }
        handleChildEvent(state.first, state.second, event)
    }

    @androidx.annotation.VisibleForTesting
    internal fun injectParentEventForTesting(
        directory: File,
        event: Int,
        path: String?,
    ) {
        val generation = synchronized(lock) { watcherGeneration }
        handleParentEvent(directory, generation, event, path)
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetWatchFactoryForTesting() {
        synchronized(lock) {
            check(!isRunning) { "Cannot replace watch factory while watcher is active" }
            watchFactory = defaultWatchFactory()
        }
    }

    private fun defaultWatchFactory(): RuntimeWatchFactory =
        RuntimeWatchFactory { file, mask, callback ->
            val observer =
                object : FileObserver(file, mask) {
                    override fun onEvent(
                        event: Int,
                        path: String?,
                    ) {
                        callback(event, path)
                    }
                }
            object : RuntimeWatchHandle {
                override fun startWatching() {
                    observer.startWatching()
                }

                override fun stopWatching() {
                    observer.stopWatching()
                }
            }
        }
}
