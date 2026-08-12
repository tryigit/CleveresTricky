package cleveres.tricky.cleverestech

import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private var pendingJob: Job? = null

    init {
        require(debounceMs >= 0) { "debounceMs must not be negative" }
    }

    fun submit() {
        synchronized(stateLock) {
            pendingJob?.cancel()
            pendingJob =
                scope.launch {
                    if (debounceMs > 0) delay(debounceMs)
                    executionMutex.withLock { refresh() }
                }
        }
    }

    fun cancel() {
        synchronized(stateLock) {
            pendingJob?.cancel()
            pendingJob = null
        }
    }
}

/** Replaces the legacy one-coroutine-per-event keybox observer with a conflated watcher. */
internal object KeyboxDirectoryRefreshWatcher {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val scheduler =
        ConflatedRefreshScheduler(scope, KEYBOX_REFRESH_DEBOUNCE_MS) {
            Config.updateKeyBoxesSync()
        }
    private var observer: FileObserver? = null

    @Synchronized
    fun start(directory: File) {
        if (observer != null) return

        // Config.initialize() already started the legacy observer. Start the replacement first so
        // a failure leaves the original observer intact, then retire the old one after hand-off.
        val replacement =
            object : FileObserver(directory, CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO) {
                override fun onEvent(
                    event: Int,
                    path: String?,
                ) {
                    Logger.i("Keybox directory event: $path")
                    scheduler.submit()
                }
            }
        replacement.startWatching()
        Config.KeyboxDirObserver.stopWatching()
        observer = replacement
    }

    @Synchronized
    fun stop() {
        observer?.stopWatching()
        observer = null
        scheduler.cancel()
    }
}
