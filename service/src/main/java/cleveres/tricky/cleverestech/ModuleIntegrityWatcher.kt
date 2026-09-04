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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val INTEGRITY_DEBOUNCE_MS = 100L
private const val MAX_PENDING_PATHS = 64
private const val INTEGRITY_MANIFEST_FILENAME = "integrity_manifest.json"

/**
 * Watches the module directory for filesystem events and triggers integrity verification.
 * Uses FileObserver to detect modifications, deletions, and moves.
 */
internal object ModuleIntegrityWatcher {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var targetedScheduler: ConflatedRefreshScheduler? = null
    private var fullScheduler: ConflatedRefreshScheduler? = null

    private var childObserver: FileObserver? = null
    private val subObservers = mutableListOf<FileObserver>()
    private var parentObserver: FileObserver? = null
    internal var parentObserverStarter: (FileObserver) -> Unit = { it.startWatching() }
    internal var childObserverStarter: (FileObserver) -> Unit = { it.startWatching() }
    internal var observerStopper: (FileObserver) -> Unit = { it.stopWatching() }

    @Volatile
    private var isRunning = false
    private val lock = Any()
    private var watcherGeneration = 0L
    private var childGeneration = 0L

    private val pendingDirtyPaths = LinkedHashSet<String>()

    val watcherRegistrationCount = AtomicInteger(0)
    val eventCoalescedCount = AtomicInteger(0)
    val targetedVerificationExecutions = AtomicInteger(0)
    val fullVerificationExecutions = AtomicInteger(0)

    /**
     * Starts watching the module directory for integrity violations.
     * Registers FileObserver instances for the directory, its parent, and subdirectories.
     */
    fun start(
        directory: File,
        loadedManifest: ParsedManifest,
        violationHandler: (List<String>) -> Unit,
    ) {
        synchronized(lock) {
            if (isRunning) return
            val generation = ++watcherGeneration
            isRunning = true
            pendingDirtyPaths.clear()

            targetedScheduler = ConflatedRefreshScheduler(scope, INTEGRITY_DEBOUNCE_MS) {
                val pathsToVerify =
                    synchronized(lock) {
                        if (!ownsWatcherGenerationLocked(generation)) return@ConflatedRefreshScheduler
                        val snapshot = ArrayList(pendingDirtyPaths)
                        pendingDirtyPaths.clear()
                        snapshot
                    }
                if (pathsToVerify.isEmpty()) return@ConflatedRefreshScheduler
                targetedVerificationExecutions.incrementAndGet()

                for (relPath in pathsToVerify) {
                    val result = ModuleIntegrityVerifier.verifySingleFile(relPath, loadedManifest)
                    if (result is IntegrityResult.Fail) {
                        synchronized(lock) {
                            if (ownsWatcherGenerationLocked(generation)) {
                                violationHandler(result.violations)
                            }
                        }
                        return@ConflatedRefreshScheduler
                    }
                }
            }

            fullScheduler = ConflatedRefreshScheduler(scope, INTEGRITY_DEBOUNCE_MS) {
                if (!synchronized(lock) { ownsWatcherGenerationLocked(generation) }) {
                    return@ConflatedRefreshScheduler
                }
                fullVerificationExecutions.incrementAndGet()
                val result = ModuleIntegrityVerifier.verifyFull()
                if (result is IntegrityResult.Fail) {
                    synchronized(lock) {
                        if (ownsWatcherGenerationLocked(generation)) {
                            violationHandler(result.violations)
                        }
                    }
                }
            }

            val parent = directory.parentFile
            if (parent != null) {
                try {
                    val pObserver = object : FileObserver(
                        parent,
                        CREATE or MOVED_TO or DELETE or MOVED_FROM
                    ) {
                        override fun onEvent(event: Int, path: String?) {
                            handleParentEvent(directory, loadedManifest, violationHandler, generation, event, path)
                        }
                    }
                    parentObserver = pObserver
                    parentObserverStarter(pObserver)
                    watcherRegistrationCount.incrementAndGet()
                } catch (e: Throwable) {
                    Logger.e("Failed to arm integrity parent watcher", e)
                    stop()
                    throw e
                }
            }

            if (directory.exists()) {
                try {
                    tryArmChildLocked(directory, loadedManifest, violationHandler, generation)
                } catch (e: Throwable) {
                    Logger.e("Failed to arm integrity child watcher at start - failing closed", e)
                    stop()
                    violationHandler(listOf("Failed to arm integrity child watcher: ${e.message}"))
                    throw e
                }
            } else {
                Logger.e("Module directory missing at watcher start - integrity violation")
                violationHandler(listOf("Module directory does not exist at watcher start"))
            }
        }
    }

    private fun handleParentEvent(
        directory: File,
        loadedManifest: ParsedManifest,
        violationHandler: (List<String>) -> Unit,
        generation: Long,
        event: Int,
        path: String?,
    ) {
        synchronized(lock) {
            if (!ownsWatcherGenerationLocked(generation) || path != directory.name) return
            if ((event and (DELETE or MOVED_FROM)) != 0) {
                Logger.e("Module directory removed - integrity violation")
                disarmChildLocked()
                violationHandler(listOf("Module directory was removed or moved"))
            } else if ((event and (CREATE or MOVED_TO)) != 0) {
                Logger.w("Module directory recreated - triggering verification")
                if (directory.exists()) {
                    try {
                        tryArmChildLocked(directory, loadedManifest, violationHandler, generation)
                        fullScheduler?.submit()
                    } catch (e: Throwable) {
                        Logger.e("Failed to arm child watcher upon recreate - failing closed", e)
                        disarmChildLocked()
                        violationHandler(listOf("Failed to arm integrity child watcher upon recreate: ${e.message}"))
                    }
                }
            }
        }
    }

    /**
     * Attempts to register FileObserver instances for the module directory and its subdirectories.
     * Throws an exception if registration fails to enforce fail-closed behavior.
     */
    private fun tryArmChildLocked(
        directory: File,
        loadedManifest: ParsedManifest,
        violationHandler: (List<String>) -> Unit,
        generation: Long,
    ) {
        if (!ownsWatcherGenerationLocked(generation)) return
        if (childObserver != null) return
        if (!directory.exists()) return

        val childToken = ++childGeneration
        try {
            val cObserver = object : FileObserver(
                directory,
                CREATE or CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO or
                    MODIFY or ATTRIB or DELETE_SELF or MOVE_SELF
            ) {
                override fun onEvent(event: Int, path: String?) {
                    handleChildEvent(loadedManifest, violationHandler, generation, childToken, event, path)
                }
            }
            childObserver = cObserver
            childObserverStarter(cObserver)
            watcherRegistrationCount.incrementAndGet()

            val subdirs = loadedManifest.files.mapNotNull {
                val idx = it.path.lastIndexOf('/')
                if (idx > 0) it.path.substring(0, idx) else null
            }.distinct()

            for (subdirRel in subdirs) {
                val subDir = File(directory, subdirRel)
                if (subDir.isDirectory) {
                    val sObserver = object : FileObserver(
                        subDir,
                        CREATE or CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO or
                            MODIFY or ATTRIB or DELETE_SELF or MOVE_SELF
                    ) {
                        override fun onEvent(event: Int, path: String?) {
                            handleSubdirectoryEvent(
                                loadedManifest,
                                violationHandler,
                                generation,
                                childToken,
                                subdirRel,
                                event,
                                path,
                            )
                        }
                    }
                    subObservers.add(sObserver)
                    childObserverStarter(sObserver)
                    watcherRegistrationCount.incrementAndGet()
                }
            }
        } catch (e: Throwable) {
            Logger.e("Failed to arm integrity child watcher - disarming and failing closed", e)
            disarmChildLocked()
            throw e
        }
    }

    private fun handleChildEvent(
        loadedManifest: ParsedManifest,
        violationHandler: (List<String>) -> Unit,
        generation: Long,
        childToken: Long,
        event: Int,
        path: String?,
    ) {
        synchronized(lock) {
            if (!ownsChildGenerationLocked(generation, childToken)) return
            if ((event and DELETE_SELF) != 0 || (event and MOVE_SELF) != 0) {
                Logger.e("Module directory lost - integrity violation")
                disarmChildLocked()
                violationHandler(listOf("Module directory was deleted or moved (self event)"))
                return
            }

            val affectedPath = path ?: return
            if (affectedPath == INTEGRITY_MANIFEST_FILENAME) {
                fullScheduler?.submit()
                return
            }

            if ((event and DELETE) != 0) {
                if (loadedManifest.files.any { it.path == affectedPath }) {
                    Logger.e("Critical payload deleted: $affectedPath")
                    violationHandler(listOf("Critical payload deleted: $affectedPath"))
                } else if (!ModuleIntegrityVerifier.isIgnoredFile(affectedPath)) {
                    scheduleTargetedCheckLocked(affectedPath, generation, childToken)
                }
            } else if (!ModuleIntegrityVerifier.isIgnoredFile(affectedPath)) {
                scheduleTargetedCheckLocked(affectedPath, generation, childToken)
            }
        }
    }

    private fun handleSubdirectoryEvent(
        loadedManifest: ParsedManifest,
        violationHandler: (List<String>) -> Unit,
        generation: Long,
        childToken: Long,
        subdirRel: String,
        event: Int,
        path: String?,
    ) {
        synchronized(lock) {
            if (!ownsChildGenerationLocked(generation, childToken)) return
            if ((event and (DELETE_SELF or MOVE_SELF)) != 0) {
                Logger.e("Critical subdirectory $subdirRel removed")
                violationHandler(listOf("Critical subdirectory removed: $subdirRel"))
            } else if ((event and DELETE) != 0) {
                val deletedPath = path?.let { "$subdirRel/$it" } ?: return
                if (loadedManifest.files.any { it.path == deletedPath }) {
                    Logger.e("Critical payload deleted: $deletedPath")
                    violationHandler(listOf("Critical payload deleted: $deletedPath"))
                } else if (!ModuleIntegrityVerifier.isIgnoredFile(deletedPath)) {
                    scheduleTargetedCheckLocked(deletedPath, generation, childToken)
                }
            } else {
                val modifiedPath = path?.let { "$subdirRel/$it" } ?: return
                if (!ModuleIntegrityVerifier.isIgnoredFile(modifiedPath)) {
                    scheduleTargetedCheckLocked(modifiedPath, generation, childToken)
                }
            }
        }
    }

    /**
     * Schedules a targeted integrity check for a specific file path, coalescing duplicate events.
     * The caller already owns [lock], which keeps callback ownership and queue mutation atomic.
     */
    private fun scheduleTargetedCheckLocked(
        relPath: String,
        generation: Long,
        childToken: Long,
    ) {
        if (!ownsChildGenerationLocked(generation, childToken)) return
        if (pendingDirtyPaths.contains(relPath)) {
            eventCoalescedCount.incrementAndGet()
            return
        }
        if (pendingDirtyPaths.size >= MAX_PENDING_PATHS) {
            pendingDirtyPaths.clear()
            fullScheduler?.submit()
            return
        }
        pendingDirtyPaths.add(relPath)
        targetedScheduler?.submit()
    }

    private fun ownsWatcherGenerationLocked(generation: Long): Boolean =
        isRunning && watcherGeneration == generation

    private fun ownsChildGenerationLocked(
        generation: Long,
        childToken: Long,
    ): Boolean = ownsWatcherGenerationLocked(generation) && childGeneration == childToken

    /**
     * Stops and clears all child and subdirectory FileObserver instances.
     */
    private fun disarmChildLocked() {
        childGeneration++
        val retiredChild = childObserver
        childObserver = null
        val retiredSubs = subObservers.toList()
        subObservers.clear()

        runCatching { retiredChild?.let(observerStopper) }
            .onFailure { Logger.w("Failed to stop retired integrity child watcher", it) }
        for (observer in retiredSubs) {
            runCatching { observerStopper(observer) }
                .onFailure { Logger.w("Failed to stop retired integrity subdirectory watcher", it) }
        }
    }

    /**
     * Stops all FileObserver instances and clears watcher state.
     */
    fun stop() {
        synchronized(lock) {
            isRunning = false
            watcherGeneration++
            val retiredParent = parentObserver
            parentObserver = null
            disarmChildLocked()
            runCatching { retiredParent?.let(observerStopper) }
                .onFailure { Logger.w("Failed to stop retired integrity parent watcher", it) }
            targetedScheduler?.cancel()
            targetedScheduler = null
            fullScheduler?.cancel()
            fullScheduler = null
            pendingDirtyPaths.clear()
        }
    }

    /**
     * Returns true if the child observer is currently active.
     */
    @androidx.annotation.VisibleForTesting
    internal fun isChildObserverActiveForTesting(): Boolean =
        synchronized(lock) { childObserver != null }

    /**
     * Returns true if the parent observer is currently active.
     */
    @androidx.annotation.VisibleForTesting
    internal fun isParentObserverActiveForTesting(): Boolean =
        synchronized(lock) { parentObserver != null }

    /**
     * Injects a file observer event into the child observer for testing.
     */
    @androidx.annotation.VisibleForTesting
    internal fun injectChildEventForTesting(event: Int, path: String?) {
        synchronized(lock) {
            childObserver?.onEvent(event, path)
        }
    }

    /**
     * Returns the number of active subdirectory observers.
     */
    @androidx.annotation.VisibleForTesting
    internal fun subObserverCountForTesting(): Int =
        synchronized(lock) { subObservers.size }

    /**
     * Injects a file observer event into a specific subdirectory observer for testing.
     */
    @androidx.annotation.VisibleForTesting
    internal fun injectSubEventForTesting(index: Int, event: Int, path: String?) {
        synchronized(lock) {
            if (index in subObservers.indices) {
                subObservers[index].onEvent(event, path)
            }
        }
    }

    /**
     * Injects a file observer event into the parent observer for testing.
     */
    @androidx.annotation.VisibleForTesting
    internal fun injectParentEventForTesting(event: Int, path: String?) {
        synchronized(lock) {
            parentObserver?.onEvent(event, path)
        }
    }

    /**
     * Returns the number of pending dirty paths awaiting verification.
     */
    @androidx.annotation.VisibleForTesting
    internal fun pendingDirtyCountForTesting(): Int =
        synchronized(lock) { pendingDirtyPaths.size }

    /**
     * Resets watcher state and counters for testing.
     */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        stop()
        parentObserverStarter = { it.startWatching() }
        childObserverStarter = { it.startWatching() }
        observerStopper = { it.stopWatching() }
        watcherRegistrationCount.set(0)
        eventCoalescedCount.set(0)
        targetedVerificationExecutions.set(0)
        fullVerificationExecutions.set(0)
    }
}
