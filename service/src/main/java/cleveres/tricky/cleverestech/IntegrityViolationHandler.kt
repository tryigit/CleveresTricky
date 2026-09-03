package cleveres.tricky.cleverestech

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles integrity violations by deleting the module directory and rebooting the system.
 * Idempotent: multiple calls only execute the violation response once.
 */
object IntegrityViolationHandler {
    @Volatile
    var isViolated: Boolean = false
        private set

    private val violationOnce = AtomicBoolean(false)

    internal var deleteModule: (String) -> Boolean = ::safeDeleteModule
    internal var rebootSystem: () -> Unit = ::performReboot

    const val VIOLATION_MESSAGE = "Module change detected! Module is being deleted and system is being restarted."

    /**
     * Handles an integrity violation by attempting to delete the module and reboot the system.
     * This function is idempotent: only the first call executes the violation response.
     */
    fun handleViolation(violations: List<String>) {
        if (!violationOnce.compareAndSet(false, true)) return
        isViolated = true
        Logger.e("INTEGRITY VIOLATION DETECTED:")
        violations.forEach { Logger.e("  - $it") }

        val moduleDir = getModuleDir()
        val deleted = try {
            deleteModule(moduleDir)
        } catch (error: Exception) {
            Logger.e("Module deletion failed with exception", error)
            false
        }

        if (deleted) {
            Logger.e("Module directory deleted successfully - initiating reboot")
            try {
                rebootSystem()
            } catch (error: Exception) {
                Logger.e("System reboot failed", error)
            }
        } else {
            Logger.e("Module deletion failed - module remains fail-closed; aborting reboot")
        }
    }

    /**
     * Resets the violation state and injectable handlers for testing.
     */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        isViolated = false
        violationOnce.set(false)
        deleteModule = ::safeDeleteModule
        rebootSystem = ::performReboot
    }
}

/**
 * Safely deletes the module directory using descriptor/no-follow traversal without following symlinks.
 * Returns true if deletion succeeded completely, false if any file could not be deleted.
 */
private fun safeDeleteModule(moduleDir: String): Boolean {
    val dir = File(moduleDir)
    val path = dir.toPath()
    if (Files.isSymbolicLink(path)) {
        Logger.e("Refusing to delete symlink target: $moduleDir")
        return false
    }
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        Logger.e("Module path is not a directory: $moduleDir")
        return false
    }
    if (!moduleDir.startsWith("/data/adb/") || !moduleDir.contains("cleverestricky")) {
        Logger.e("Refusing to delete suspicious path: $moduleDir")
        return false
    }

    return deleteDirectoryRecursivelyNoFollow(path)
}

private fun deleteDirectoryRecursivelyNoFollow(dir: java.nio.file.Path, maxDepth: Int = 16): Boolean {
    if (maxDepth <= 0) {
        Logger.e("Exceeded maximum recursion depth while deleting: $dir")
        return false
    }
    if (Files.isSymbolicLink(dir)) {
        return try {
            Files.deleteIfExists(dir)
        } catch (e: Exception) {
            Logger.e("Failed to delete symlink: $dir", e)
            false
        }
    }
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
        return try {
            Files.deleteIfExists(dir)
        } catch (e: Exception) {
            Logger.e("Failed to delete non-directory: $dir", e)
            false
        }
    }

    var allSuccess = true
    try {
        Files.newDirectoryStream(dir).use { stream ->
            for (entry in stream) {
                if (Files.isSymbolicLink(entry)) {
                    if (!tryDeleteEntry(entry)) allSuccess = false
                } else if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    if (!deleteDirectoryRecursivelyNoFollow(entry, maxDepth - 1)) {
                        allSuccess = false
                    }
                } else {
                    if (!tryDeleteEntry(entry)) allSuccess = false
                }
            }
        }
    } catch (e: Exception) {
        Logger.e("Failed to iterate directory: $dir", e)
        return false
    }

    if (allSuccess) {
        try {
            Files.deleteIfExists(dir)
        } catch (e: Exception) {
            Logger.e("Failed to delete directory: $dir", e)
            allSuccess = false
        }
    }
    return allSuccess
}

private fun tryDeleteEntry(entry: java.nio.file.Path): Boolean {
    return try {
        Files.deleteIfExists(entry)
    } catch (e: Exception) {
        Logger.e("Failed to delete entry: $entry", e)
        false
    }
}

/**
 * Initiates a system reboot using /system/bin/reboot, with a fallback to the 'reboot' command.
 */
private fun performReboot() {
    try {
        ProcessBuilder("/system/bin/reboot")
            .redirectErrorStream(true)
            .start()
    } catch (error: Exception) {
        Logger.e("Reboot via /system/bin/reboot failed", error)
        try {
            ProcessBuilder("reboot")
                .redirectErrorStream(true)
                .start()
        } catch (fallbackError: Exception) {
            Logger.e("Reboot via 'reboot' also failed", fallbackError)
            throw fallbackError
        }
    }
}
