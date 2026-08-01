package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.Config
import cleveres.tricky.cleverestech.Logger
import java.io.File
import java.io.IOException

/**
 * Synchronously consumes configuration command files written by the service.
 *
 * FileObserver remains the fallback for commands created by external tools, while writes made
 * through [SecureFile] are completed before the caller receives a success response.
 */
internal object ConfigCommandDispatcher {
    private const val APPLY_PROFILE_COMMAND = "apply_profile"
    private const val PROCESSING_SUFFIX = ".processing"
    private const val COMPLETION_TIMEOUT_MS = 5_000L
    private const val POLL_INTERVAL_MS = 20L

    private val supportedProfiles = setOf("godprofile", "dailyuse", "minimal", "default")

    fun onTextWritten(file: File, content: String) {
        if (file.name != APPLY_PROFILE_COMMAND) return

        val profileName = content.trim()
        if (profileName.lowercase() !in supportedProfiles) {
            file.delete()
            throw IllegalArgumentException("Unsupported profile: $profileName")
        }

        val processingFile = File(file.parentFile, file.name + PROCESSING_SUFFIX)
        if (file.renameTo(processingFile)) {
            try {
                Config.applyProfile(profileName)
            } finally {
                if (processingFile.exists() && !processingFile.delete()) {
                    Logger.e("Failed to delete processed profile command: ${processingFile.absolutePath}")
                }
            }
            return
        }

        // FileObserver may have won the atomic rename. In that case wait for its processing
        // marker to disappear so the HTTP request still represents completed work.
        if (!file.exists() && processingFile.exists()) {
            waitForExternalProcessor(processingFile)
            return
        }

        throw IOException("Failed to claim profile command: ${file.absolutePath}")
    }

    private fun waitForExternalProcessor(processingFile: File) {
        val deadline = System.nanoTime() + COMPLETION_TIMEOUT_MS * 1_000_000L
        while (processingFile.exists() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for profile application", e)
            }
        }

        if (processingFile.exists()) {
            throw IOException("Timed out waiting for profile application: ${processingFile.absolutePath}")
        }
    }
}
