package cleveres.tricky.cleverestech

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Idempotent integrity violation response handler.
 *
 * On first confirmed violation:
 * 1. Sets a persistent in-memory violation flag
 * 2. Logs the violation details
 * 3. Safely deletes the compromised module directory
 * 4. Reboots the system
 *
 * Multiple simultaneous violations are conflated into a single response
 * via [AtomicBoolean] compare-and-set.
 */
object IntegrityViolationHandler {
    @Volatile
    var isViolated: Boolean = false
        private set

    private val violationOnce = AtomicBoolean(false)

    /** Injectable for testing — default performs real recursive delete via safe path operations. */
    internal var deleteModule: (String) -> Boolean = ::safeDeleteModule

    /** Injectable for testing — default calls /system/bin/reboot. */
    internal var rebootSystem: () -> Unit = ::performReboot

    /** WebUI violation message — exact text required by specification. */
    const val VIOLATION_MESSAGE = "Module change detected! Module is being deleted and system is being restarted."

    /**
     * Handle a confirmed integrity violation. Idempotent — only the first
     * call actually performs delete + reboot. Subsequent calls are no-ops.
     */
    fun handleViolation(violations: List<String>) {
        if (!violationOnce.compareAndSet(false, true)) return
        isViolated = true
        Logger.e("INTEGRITY VIOLATION DETECTED:")
        violations.forEach { Logger.e("  - $it") }

        val moduleDir = getModuleDir()
        Logger.e("Attempting safe deletion of module directory: $moduleDir")
        val deleted = try {
            deleteModule(moduleDir)
        } catch (error: Exception) {
            Logger.e("Module deletion failed with exception", error)
            false
        }
        if (deleted) {
            Logger.e("Module directory deleted successfully")
        } else {
            Logger.e("Module deletion failed — module remains fail-closed")
        }

        Logger.e("Initiating system reboot due to integrity violation")
        try {
            rebootSystem()
        } catch (error: Exception) {
            Logger.e("System reboot failed", error)
            // Remain fail-closed: isViolated is true, no normal operation can proceed
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        isViolated = false
        violationOnce.set(false)
        deleteModule = ::safeDeleteModule
        rebootSystem = ::performReboot
    }
}

/**
 * Safely delete a module directory without following symlinks.
 * Uses defensive path validation — never blindly removes arbitrary paths.
 */
private fun safeDeleteModule(moduleDir: String): Boolean {
    val dir = File(moduleDir)
    // Validate this is actually a module directory, not a symlink trick
    val path = dir.toPath()
    if (Files.isSymbolicLink(path)) {
        Logger.e("Refusing to delete symlink target: $moduleDir")
        return false
    }
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        Logger.e("Module path is not a directory: $moduleDir")
        return false
    }
    // Validate the path looks like a legitimate module directory
    if (!moduleDir.startsWith("/data/adb/") || !moduleDir.contains("cleverestricky")) {
        Logger.e("Refusing to delete suspicious path: $moduleDir")
        return false
    }
    return try {
        var success = true
        val entries = ArrayList<java.nio.file.Path>()
        Files.walk(path).use { stream ->
            val iterator = stream.iterator()
            var count = 0
            while (iterator.hasNext() && count <= 4096) {
                entries.add(iterator.next())
                count++
            }
            if (count > 4096) {
                Logger.e("Module directory has too many entries, aborting delete")
                return false
            }
        }
        // Delete in reverse order (deepest children first)
        entries.sortedByDescending { it.nameCount }.forEach { entry ->
            if (Files.isSymbolicLink(entry)) {
                // Delete the symlink itself, not its target
                Files.deleteIfExists(entry)
            } else {
                try {
                    Files.deleteIfExists(entry)
                } catch (e: Exception) {
                    Logger.e("Failed to delete: $entry", e)
                    success = false
                }
            }
        }
        success
    } catch (error: Exception) {
        Logger.e("Safe module deletion failed", error)
        false
    }
}

private fun performReboot() {
    try {
        ProcessBuilder("/system/bin/reboot")
            .redirectErrorStream(true)
            .start()
    } catch (error: Exception) {
        Logger.e("Reboot via /system/bin/reboot failed", error)
        // Try alternate reboot mechanism
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
