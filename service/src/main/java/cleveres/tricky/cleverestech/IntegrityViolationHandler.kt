package cleveres.tricky.cleverestech

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicBoolean

object IntegrityViolationHandler {
    @Volatile
    var isViolated: Boolean = false
        private set

    private val violationOnce = AtomicBoolean(false)

    internal var deleteModule: (String) -> Boolean = ::safeDeleteModule
    internal var rebootSystem: () -> Unit = ::performReboot

    const val VIOLATION_MESSAGE = "Module change detected! Module is being deleted and system is being restarted."

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
            Logger.e("Module directory deleted successfully")
        } else {
            Logger.e("Module deletion failed - module remains fail-closed")
        }

        Logger.e("Initiating system reboot due to integrity violation")
        try {
            rebootSystem()
        } catch (error: Exception) {
            Logger.e("System reboot failed", error)
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
        entries.sortedByDescending { it.nameCount }.forEach { entry ->
            if (Files.isSymbolicLink(entry)) {
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
