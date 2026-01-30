package cleveres.tricky.cleverestech

import java.io.File
import kotlin.concurrent.thread

class FilePoller(
    private val file: File,
    private val intervalMs: Long = 5000,
    private val onModified: (File) -> Unit
) {
    @Volatile
    private var isRunning = false
    @Volatile
    private var lastModified: Long = 0

    init {
        if (file.exists()) {
            lastModified = file.lastModified()
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        thread(start = true, isDaemon = true, name = "FilePoller-${file.name}") {
            while (isRunning) {
                try {
                    Thread.sleep(intervalMs)
                    if (file.exists()) {
                        val currentModified = file.lastModified()
                        if (currentModified > lastModified) {
                            lastModified = currentModified
                            onModified(file)
                        }
                    } else {
                        // Reset if file deleted? Or keep lastModified?
                        // If deleted and recreated, lastModified checks should handle it if new time > old time.
                        // Usually specific logic needed? For now, keep simple.
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    fun updateLastModified() {
         if (file.exists()) {
            lastModified = file.lastModified()
        } else {
            lastModified = 0
        }
    }
}
