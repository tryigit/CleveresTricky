package cleveres.tricky.cleverestech

import android.os.FileObserver
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class FilePoller(
    private val file: File,
    private val intervalMs: Long = 5000,
    private val onModified: (File) -> Unit,
) {
    private data class Snapshot(
        val exists: Boolean,
        val lastModified: Long,
        val length: Long,
        val fileKey: Any?,
    )

    @Volatile
    private var isRunning = false
    private var lastSnapshot = snapshot()
    private var scheduledFuture: ScheduledFuture<*>? = null
    private var observer: FileObserver? = null

    companion object {
        private val scheduler =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "FilePoller-Fallback").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
    }

    init {
        require(intervalMs > 0) { "intervalMs must be positive" }
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        lastSnapshot = snapshot()
        if (!startObserver()) {
            scheduleFallbackPolling()
        }
    }

    private fun startObserver(): Boolean {
        return try {
            val parent = file.parentFile
            if (parent == null || !parent.isDirectory) return false

            val eventMask =
                FileObserver.CLOSE_WRITE or
                    FileObserver.MOVED_TO or
                    FileObserver.MOVED_FROM or
                    FileObserver.CREATE or
                    FileObserver.DELETE or
                    FileObserver.ATTRIB or
                    FileObserver.DELETE_SELF or
                    FileObserver.MOVE_SELF

            @Suppress("DEPRECATION")
            val fileObserver =
                object : FileObserver(parent.absolutePath, eventMask) {
                    override fun onEvent(
                        event: Int,
                        path: String?,
                    ) {
                        val kind = event and FileObserver.ALL_EVENTS
                        if (kind == FileObserver.DELETE_SELF || kind == FileObserver.MOVE_SELF) {
                            handleObserverLoss()
                            return
                        }
                        if (path != file.name) return
                        try {
                            checkForChange()
                        } catch (error: Throwable) {
                            Logger.e("FilePoller: Observer check failed for ${file.name}", error)
                        }
                    }
                }
            fileObserver.startWatching()
            observer = fileObserver
            true
        } catch (error: Throwable) {
            Logger.e("FilePoller: Could not start FileObserver for ${file.name}; periodic polling remains active", error)
            false
        }
    }

    @Synchronized
    private fun handleObserverLoss() {
        if (!isRunning) return
        observer?.stopWatching()
        observer = null
        if (!startObserver()) scheduleFallbackPolling()
    }

    @Synchronized
    private fun scheduleFallbackPolling() {
        if (scheduledFuture != null) return
        scheduledFuture =
            scheduler.scheduleWithFixedDelay(
                {
                    try {
                        checkForChange()
                    } catch (error: Throwable) {
                        Logger.e("FilePoller: Periodic check failed for ${file.name}", error)
                    }
                },
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS,
            )
    }

    @Synchronized
    private fun checkForChange() {
        if (!isRunning) return
        val current = snapshot()
        if (current == lastSnapshot) return
        val previous = lastSnapshot
        lastSnapshot = current
        try {
            onModified(file)
        } catch (error: Throwable) {
            lastSnapshot = previous
            throw error
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        observer?.stopWatching()
        observer = null
        scheduledFuture?.cancel(false)
        scheduledFuture = null
    }

    @Synchronized
    fun updateLastModified() {
        lastSnapshot = snapshot()
    }

    private fun snapshot(): Snapshot {
        return try {
            val attributes =
                Files.readAttributes(
                    file.toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            if (!attributes.isRegularFile) {
                Snapshot(false, 0L, 0L, null)
            } else {
                Snapshot(
                    exists = true,
                    lastModified = attributes.lastModifiedTime().toMillis(),
                    length = attributes.size(),
                    fileKey = attributes.fileKey(),
                )
            }
        } catch (_: IOException) {
            Snapshot(false, 0L, 0L, null)
        }
    }
}
