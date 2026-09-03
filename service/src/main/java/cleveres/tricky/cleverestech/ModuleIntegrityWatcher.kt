package cleveres.tricky.cleverestech

import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

private const val INTEGRITY_DEBOUNCE_MS = 100L

/**
 * Event-driven module integrity watcher.
 *
 * Uses a two-tier [FileObserver] pattern (parent + child) to detect filesystem
 * changes to critical module payloads and trigger targeted hash verification.
 *
 * Normal operation with no filesystem changes produces zero CPU activity.
 */
internal object ModuleIntegrityWatcher {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scheduler: ConflatedRefreshScheduler? = null

    private var childObserver: FileObserver? = null
    private val subObservers = mutableListOf<FileObserver>()
    private var parentObserver: FileObserver? = null

    @Volatile
    private var isRunning = false
    private val lock = Any()

    private var onViolation: ((List<String>) -> Unit)? = null
    private var manifest: ParsedManifest? = null

    @Synchronized
    fun start(
        directory: File,
        loadedManifest: ParsedManifest,
        violationHandler: (List<String>) -> Unit,
    ) {
        synchronized(lock) {
            if (isRunning) return
            isRunning = true
            manifest = loadedManifest
            onViolation = violationHandler

            scheduler = ConflatedRefreshScheduler(scope, INTEGRITY_DEBOUNCE_MS) {
                // This is the debounced integrity check action.
                // We do a full re-verification when events are conflated.
                val result = ModuleIntegrityVerifier.verifyFull()
                if (result is IntegrityResult.Fail) {
                    onViolation?.invoke(result.violations)
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
                            if (path == directory.name) {
                                synchronized(lock) {
                                    if (!isRunning) return
                                    if ((event and (DELETE or MOVED_FROM)) != 0) {
                                        Logger.e("Module directory removed — integrity violation")
                                        disarmChildLocked()
                                        onViolation?.invoke(
                                            listOf("Module directory was removed or moved")
                                        )
                                    } else if ((event and (CREATE or MOVED_TO)) != 0) {
                                        Logger.w("Module directory recreated — triggering verification")
                                        if (directory.exists()) {
                                            tryArmChildLocked(directory)
                                            triggerRefresh()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    pObserver.startWatching()
                    parentObserver = pObserver
                } catch (e: Throwable) {
                    Logger.e("Failed to arm integrity parent watcher", e)
                }
            }

            if (directory.exists()) {
                tryArmChildLocked(directory)
            } else {
                Logger.e("Module directory missing at watcher start — integrity violation")
                onViolation?.invoke(listOf("Module directory does not exist at watcher start"))
            }
        }
    }

    private fun tryArmChildLocked(directory: File) {
        if (childObserver != null) return
        if (!directory.exists()) return

        try {
            val cObserver = object : FileObserver(
                directory,
                CREATE or CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO or
                    MODIFY or ATTRIB or DELETE_SELF or MOVE_SELF
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if ((event and DELETE_SELF) != 0 || (event and MOVE_SELF) != 0) {
                        Logger.e("Module directory lost (DELETE_SELF/MOVE_SELF) — integrity violation")
                        synchronized(lock) {
                            disarmChildLocked()
                        }
                        onViolation?.invoke(
                            listOf("Module directory was deleted or moved (self event)")
                        )
                    } else if ((event and DELETE) != 0) {
                        // A file was deleted from the module directory
                        val deletedPath = path ?: return
                        val currentManifest = manifest ?: return
                        // Check if the deleted file is in the manifest
                        if (currentManifest.files.any { it.path == deletedPath }) {
                            Logger.e("Critical payload deleted: $deletedPath")
                            onViolation?.invoke(listOf("Critical payload deleted: $deletedPath"))
                        }
                    } else {
                        // MODIFY, CLOSE_WRITE, CREATE, MOVED_TO, MOVED_FROM, ATTRIB
                        triggerRefresh()
                    }
                }
            }
            cObserver.startWatching()
            childObserver = cObserver

            // Also arm observers for any subdirectories in the manifest (e.g. webroot)
            val subdirs = manifest?.files?.mapNotNull {
                val idx = it.path.lastIndexOf('/')
                if (idx > 0) it.path.substring(0, idx) else null
            }?.distinct() ?: emptyList()

            for (subdirRel in subdirs) {
                val subDir = File(directory, subdirRel)
                if (subDir.isDirectory) {
                    val sObserver = object : FileObserver(
                        subDir,
                        CREATE or CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO or
                            MODIFY or ATTRIB or DELETE_SELF or MOVE_SELF
                    ) {
                        override fun onEvent(event: Int, path: String?) {
                            if ((event and (DELETE_SELF or MOVE_SELF)) != 0) {
                                Logger.e("Critical subdirectory $subdirRel removed")
                                onViolation?.invoke(listOf("Critical subdirectory removed: $subdirRel"))
                            } else if ((event and DELETE) != 0) {
                                val deletedPath = path?.let { "$subdirRel/$it" } ?: return
                                if (manifest?.files?.any { it.path == deletedPath } == true) {
                                    Logger.e("Critical payload deleted: $deletedPath")
                                    onViolation?.invoke(listOf("Critical payload deleted: $deletedPath"))
                                }
                            } else {
                                triggerRefresh()
                            }
                        }
                    }
                    sObserver.startWatching()
                    subObservers.add(sObserver)
                }
            }
        } catch (e: Throwable) {
            Logger.e("Failed to arm integrity child watcher", e)
        }
    }

    private fun disarmChildLocked() {
        childObserver?.stopWatching()
        childObserver = null
        for (observer in subObservers) {
            observer.stopWatching()
        }
        subObservers.clear()
    }

    private fun triggerRefresh() {
        scheduler?.submit()
    }

    @Synchronized
    fun stop() {
        synchronized(lock) {
            isRunning = false
            disarmChildLocked()
            parentObserver?.stopWatching()
            parentObserver = null
            scheduler?.cancel()
            scheduler = null
            onViolation = null
            manifest = null
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun isChildObserverActiveForTesting(): Boolean =
        synchronized(lock) { childObserver != null }

    @androidx.annotation.VisibleForTesting
    internal fun isParentObserverActiveForTesting(): Boolean =
        synchronized(lock) { parentObserver != null }

    @androidx.annotation.VisibleForTesting
    internal fun injectChildEventForTesting(event: Int, path: String?) {
        synchronized(lock) {
            childObserver?.onEvent(event, path)
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun subObserverCountForTesting(): Int =
        synchronized(lock) { subObservers.size }

    @androidx.annotation.VisibleForTesting
    internal fun injectSubEventForTesting(index: Int, event: Int, path: String?) {
        synchronized(lock) {
            if (index in subObservers.indices) {
                subObservers[index].onEvent(event, path)
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun injectParentEventForTesting(event: Int, path: String?) {
        synchronized(lock) {
            parentObserver?.onEvent(event, path)
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        stop()
    }
}
