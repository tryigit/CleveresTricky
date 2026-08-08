package cleveres.tricky.cleverestech

import android.os.FileObserver
import java.io.File
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
    )

    @Volatile
    private var isRunning = false
    private var lastSnapshot = snapshot()
    private var scheduledFuture: ScheduledFuture<*>? = null
    private var observer: FileObserver? = null

    companion object {
        private val scheduler =
            Executors.newScheduledThreadPool(2) { runnable ->
                Thread(runnable, "FilePoller-Scheduler").apply {
                    isDaemon = true
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

        try {
            val parent = file.parentFile
            if (parent != null && parent.isDirectory) {
                val eventMask =
                    FileObserver.CLOSE_WRITE or
                        FileObserver.MOVED_TO or
                        FileObserver.MOVED_FROM or
                        FileObserver.CREATE or
                        FileObserver.DELETE or
                        FileObserver.ATTRIB

                @Suppress("DEPRECATION")
                val fileObserver =
                    object : FileObserver(parent.absolutePath, eventMask) {
                        override fun onEvent(
                            event: Int,
                            path: String?,
                        ) {
                            if (path == file.name) checkForChange()
                        }
                    }
                fileObserver.startWatching()
                observer = fileObserver
            }
        } catch (error: Throwable) {
            Logger.e("FilePoller: Could not start FileObserver for ${file.name}", error)
        }

        // Poll even when FileObserver is available. Some vendor kernels drop
        // inotify events during atomic replacement or early boot.
        scheduledFuture =
            scheduler.scheduleWithFixedDelay(
                {
                    try {
                        checkForChange()
                    } catch (error: Throwable) {
                        Logger.e("FilePoller: Check failed for ${file.name}", error)
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
        lastSnapshot = current
        onModified(file)
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
        val exists = file.exists()
        return Snapshot(
            exists = exists,
            lastModified = if (exists) file.lastModified() else 0L,
            length = if (exists) file.length() else 0L,
        )
    }
}
