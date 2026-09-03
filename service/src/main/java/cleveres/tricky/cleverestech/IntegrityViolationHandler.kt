package cleveres.tricky.cleverestech

import android.net.LocalSocket
import android.net.LocalSocketAddress
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
    internal var terminateProcess: (Int) -> Unit = ::defaultTerminateProcess
    internal var onPreDescentCheck: ((java.nio.file.Path) -> Unit)? = null

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

        try {
            ModuleIntegrityWatcher.stop()
        } catch (e: Throwable) {
            Logger.e("Failed to stop ModuleIntegrityWatcher during violation handling", e)
        }

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
                Logger.e("System reboot failed - halting runtime and terminating process", error)
                terminateProcess(1)
            }
        } else {
            Logger.e("Module deletion failed - module remains fail-closed; aborting reboot and terminating process")
            terminateProcess(1)
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
        terminateProcess = ::defaultTerminateProcess
        onPreDescentCheck = null
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

    // 1. Authoritative primary path: On Android, deletion MUST be performed by the privileged
    // Rust daemon using root descriptor-relative openat(..., O_NOFOLLOW | O_DIRECTORY | O_CLOEXEC)
    // and unlinkat. If the daemon is unavailable or deletion fails, we fail closed immediately
    // rather than falling back to an unprivileged, pathname-based recursive deletion.
    if (isAndroidRuntime()) {
        val daemonDeleted = requestDaemonDeleteModule()
        if (daemonDeleted && !dir.exists()) {
            return true
        }
        Logger.e("Authoritative daemon module deletion failed or was unavailable; refusing insecure pathname fallback")
        return false
    }

    // 2. Local fallback deletion on non-Android host/test runtime with strict NOFOLLOW_LINKS protection:
    return deleteDirectoryRecursivelyNoFollow(path)
}

private fun requestDaemonDeleteModule(): Boolean {
    return try {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress("cleverestrickyd.v1", LocalSocketAddress.Namespace.ABSTRACT))
            val peer = socket.peerCredentials
            if (peer == null || peer.uid != 0) {
                Logger.e("Untrusted daemon peer UID: ${peer?.uid}")
                return false
            }
            socket.soTimeout = 5000
            val output = socket.outputStream
            val input = socket.inputStream

            // Magic: "CTIP", version: 1, opcode: 0x32 (OP_INTEGRITY_DELETE_MODULE), flags: 0, payload_len: 0
            val header = ByteArray(16)
            "CTIP".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
            header[4] = 0
            header[5] = 1
            header[6] = 0
            header[7] = 0x32
            output.write(header)
            output.flush()

            val responseHeader = ByteArray(16)
            var read = 0
            while (read < 16) {
                val count = input.read(responseHeader, read, 16 - read)
                if (count < 0) return false
                read += count
            }
            val flags =
                ((responseHeader[8].toInt() and 0xff) shl 24) or
                    ((responseHeader[9].toInt() and 0xff) shl 16) or
                    ((responseHeader[10].toInt() and 0xff) shl 8) or
                    (responseHeader[11].toInt() and 0xff)
            flags == 0
        }
    } catch (e: Exception) {
        Logger.e("Daemon module deletion request failed", e)
        false
    }
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
                    IntegrityViolationHandler.onPreDescentCheck?.invoke(entry)
                    val attrs =
                        try {
                            Files.readAttributes(
                                entry,
                                java.nio.file.attribute.BasicFileAttributes::class.java,
                                LinkOption.NOFOLLOW_LINKS,
                            )
                        } catch (_: Exception) {
                            null
                        }
                    if (attrs == null || attrs.isSymbolicLink) {
                        if (!tryDeleteEntry(entry)) allSuccess = false
                    } else if (attrs.isDirectory) {
                        if (!deleteDirectoryRecursivelyNoFollow(entry, maxDepth - 1)) {
                            allSuccess = false
                        }
                    } else {
                        if (!tryDeleteEntry(entry)) allSuccess = false
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

private fun isAndroidRuntime(): Boolean {
    val runtimeName = System.getProperty("java.runtime.name").orEmpty()
    val vmName = System.getProperty("java.vm.name").orEmpty()
    return runtimeName.contains("Android", ignoreCase = true) || vmName.equals("Dalvik", ignoreCase = true)
}

private fun defaultTerminateProcess(status: Int) {
    try {
        android.os.Process.killProcess(android.os.Process.myPid())
    } catch (_: Throwable) {
    }
    try {
        kotlin.system.exitProcess(status)
    } catch (_: Throwable) {
    }
    Runtime.getRuntime().halt(status)
}
