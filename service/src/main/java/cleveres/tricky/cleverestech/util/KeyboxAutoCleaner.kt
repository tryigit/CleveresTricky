package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.Config
import cleveres.tricky.cleverestech.Logger
import cleveres.tricky.cleverestech.ManagedFileCoordinator
import cleveres.tricky.cleverestech.StoredKeyboxInventory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

object KeyboxAutoCleaner {
    internal data class CleanupResult(
        val moved: Int,
        val cancelled: Boolean,
    )

    private val executorLock = Any()

    @Volatile
    private var executor: ScheduledExecutorService? = null
    private var scheduledCheck: ScheduledFuture<*>? = null
    private val configDir = File("/data/adb/cleverestricky")
    private val toggleFile = File(configDir, "auto_keybox_check")
    private val spoofEnabledFile = File(configDir, "spoof_enabled")

    fun start() {
        setEnabled(Config.isSpoofEnabled && isRegularFile(toggleFile))
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(executorLock) {
            val current = executor
            if (!enabled) {
                scheduledCheck?.cancel(true)
                scheduledCheck = null
                return
            }
            val activeExecutor = current?.takeUnless { it.isShutdown } ?: createExecutor().also { executor = it }
            if (scheduledCheck?.isDone == false) return

            scheduledCheck = activeExecutor.scheduleWithFixedDelay(
                {
                    try {
                        runCheck()
                    } catch (error: Throwable) {
                        Logger.e("AutoCleaner: Scheduled check failed", error)
                    }
                },
                1,
                1440,
                TimeUnit.MINUTES,
            )
        }
    }

    private fun createExecutor(): ScheduledExecutorService =
        ScheduledThreadPoolExecutor(
            1,
            ThreadFactory { runnable ->
                Thread(runnable, "CleveresTricky-KeyboxCheck").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            },
        ).apply {
            setKeepAliveTime(30, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
            setRemoveOnCancelPolicy(true)
        }

    private fun isEnabledNow(): Boolean = Config.isSpoofEnabled && isRegularFile(spoofEnabledFile) && isRegularFile(toggleFile)

    private fun runCheck() {
        if (!isEnabledNow()) return

        Logger.i("AutoCleaner: Starting daily revocation check...")
        val results = KeyboxVerifier.verify(configDir)
        val cleanup =
            applyVerifiedResults(configDir, results, ::isEnabledNow) {
                Config.updateKeyBoxesSync()
            }
        if (!cleanup.cancelled && cleanup.moved > 0) notifyUser(cleanup.moved)
        if (cleanup.cancelled) {
            Logger.i("AutoCleaner: Check stopped because automatic cleanup was disabled")
        } else {
            Logger.i("AutoCleaner: Finished check. Revoked/Invalid files moved: ${cleanup.moved}")
        }
    }

    /**
     * Rebinds every path to the exact descriptor-backed snapshot that produced its verification
     * result. The shared monitor prevents managed writers from replacing that path between the
     * final digest comparison, quarantine move, and runtime refresh.
     */
    internal fun applyVerifiedResults(
        configDir: File,
        results: List<KeyboxVerifier.Result>,
        isEnabled: () -> Boolean,
        refresh: () -> Unit,
    ): CleanupResult =
        synchronized(ManagedFileCoordinator.monitor) {
            val revokedDir = File(File(configDir, "keyboxes"), "revoked")
            SecureFile.mkdirs(revokedDir, 448)
            var moved = 0
            var cancelled = false

            for (result in results) {
                if (!isEnabled()) {
                    cancelled = true
                    break
                }
                if (result.status != KeyboxVerifier.Status.REVOKED &&
                    result.status != KeyboxVerifier.Status.INVALID
                ) {
                    continue
                }

                val expectedDigest = result.snapshotSha256
                if (expectedDigest == null) {
                    Logger.w("AutoCleaner: Skipping ${result.filename} without a stable verified snapshot")
                    continue
                }
                val scope = result.storageId.substringBefore(':', missingDelimiterValue = "")
                val source = StoredKeyboxInventory.resolve(configDir, scope, result.filename)
                if (source == null || source.id != result.storageId) {
                    Logger.w("AutoCleaner: Skipping changed or missing source ${result.storageId}")
                    continue
                }

                try {
                    val currentDigest = sha256Hex(source.file)
                    if (currentDigest != expectedDigest) {
                        Logger.w("AutoCleaner: Skipping replaced keybox source ${result.storageId}")
                        continue
                    }
                    val initialTarget = File(revokedDir, result.filename)
                    val target =
                        if (initialTarget.exists()) {
                            File(revokedDir, "${result.filename}.${System.currentTimeMillis()}.revoked")
                        } else {
                            initialTarget
                        }
                    try {
                        Files.move(
                            source.file.toPath(),
                            target.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(source.file.toPath(), target.toPath())
                    }
                    moved++
                    Logger.i("AutoCleaner: Keybox ${result.filename} is ${result.status}. Moved to revoked.")
                } catch (error: Exception) {
                    Logger.e("AutoCleaner: Failed to move ${result.filename}", error)
                }
            }

            refresh()
            CleanupResult(moved, cancelled)
        }

    private fun sha256Hex(file: File): String {
        val digest = sha256FileSnapshotBounded(file, 1, StoredKeyboxInventory.MAX_XML_BYTES)
        return try {
            buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }
        } finally {
            digest.fill(0)
        }
    }

    private fun notifyUser(count: Int) {
        try {
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

    private fun isRegularFile(file: File): Boolean = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private const val HEX = "0123456789abcdef"
}
