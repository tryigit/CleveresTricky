package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.Logger
import cleveres.tricky.cleverestech.WEB_UI_LOOPBACK_HOST
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object KeyboxAutoCleaner {
    private fun isTokenValid(token: String): Boolean {
        if (token.length !in 32..128) return false
        for (i in 0 until token.length) {
            val c = token[i]
            if (!(c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_')) {
                return false
            }
        }
        return true
    }

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val configDir = File("/data/adb/cleverestricky")
    private val keyboxDir = File(configDir, "keyboxes")
    private val revokedDir = File(keyboxDir, "revoked")
    private val toggleFile = File(configDir, "auto_keybox_check")
    private val webPortFile = File(configDir, "web_port")
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        executor.scheduleWithFixedDelay({
            try {
                runCheck()
            } catch (error: Throwable) {
                Logger.e("AutoCleaner: Scheduled check failed", error)
            }
        }, 1, 1440, TimeUnit.MINUTES) // Run 1 min after start, then every 24 hours
    }

    private fun runCheck() {
        if (!toggleFile.exists()) return

        Logger.i("AutoCleaner: Starting daily revocation check...")
        val results = KeyboxVerifier.verify(configDir)
        var revokedCount = 0

        SecureFile.mkdirs(revokedDir, 448)

        for (res in results) {
            if (res.status == KeyboxVerifier.Status.REVOKED || res.status == KeyboxVerifier.Status.INVALID) {
                Logger.i("AutoCleaner: Keybox ${res.filename} is ${res.status}. Moving to revoked.")
                val file = res.file
                if (file.exists() && File(res.filename).name == res.filename) {
                    try {
                        val initialTarget = File(revokedDir, res.filename)
                        val target =
                            if (initialTarget.exists()) {
                                File(revokedDir, "${res.filename}.${System.currentTimeMillis()}.revoked")
                            } else {
                                initialTarget
                            }
                        try {
                            Files.move(
                                file.toPath(),
                                target.toPath(),
                                StandardCopyOption.ATOMIC_MOVE,
                            )
                        } catch (_: AtomicMoveNotSupportedException) {
                            Files.move(file.toPath(), target.toPath())
                        }
                        revokedCount++
                    } catch (e: Exception) {
                        Logger.e("AutoCleaner: Failed to move ${res.filename}", e)
                    }
                }
            }
        }

        cleveres.tricky.cleverestech.Config.updateKeyBoxesSync()
        if (revokedCount > 0) notifyUser(revokedCount)
        Logger.i("AutoCleaner: Finished check. Revoked/Invalid files moved: $revokedCount")
    }

    private fun notifyUser(count: Int) {
        try {
            val url = readWebUiUrl() ?: return
            // Post a high-priority, actionable notification
            val cmd =
                arrayOf(
                    "cmd",
                    "notification",
                    "post",
                    "-S",
                    "bigtext",
                    "-t",
                    "CleveresTricky",
                    "Keybox Revoked Alert",
                    "$count keybox(es) were revoked or invalid and have been disabled. Check WebUI.",
                    "-a",
                    "android.intent.action.VIEW",
                    "-d",
                    url,
                )
            val nullDevice = File("/dev/null")
            val process =
                ProcessBuilder(*cmd)
                    .redirectOutput(nullDevice)
                    .redirectError(nullDevice)
                    .start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Logger.e("AutoCleaner: Notification command timed out")
                return
            }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                Logger.e("AutoCleaner: Failed to send notification (exit=$exitCode)")
            }
        } catch (e: Exception) {
            Logger.e("AutoCleaner: Failed to send notification", e)
        }
    }

    /**
     * Reads the `web_port` metadata file (`port|token`) and returns the tokenized WebUI URL.
     *
     * Returns null if the file is missing or malformed so a notification never
     * exposes a guessed or unauthenticated endpoint.
     */
    private fun readWebUiUrl(): String? {
        return try {
            val raw = webPortFile.readText().trim()
            val pipeIdx = raw.indexOf('|')
            val portStr = if (pipeIdx != -1) raw.substring(0, pipeIdx) else raw
            val port = portStr.toIntOrNull()
            val token = if (pipeIdx != -1) raw.substring(pipeIdx + 1).trim() else ""
            if (port == null || port !in 1..65535 || token.isBlank() || !isTokenValid(token)) {
                Logger.e("AutoCleaner: Invalid WebUI endpoint metadata")
                null
            } else {
                "http://$WEB_UI_LOOPBACK_HOST:$port/?token=$token"
            }
        } catch (e: Exception) {
            Logger.e("AutoCleaner: Failed to read WebUI endpoint metadata", e)
            null
        }
    }
}
