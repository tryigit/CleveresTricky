package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.Config
import cleveres.tricky.cleverestech.KeyboxValidityTracker
import cleveres.tricky.cleverestech.Logger
import cleveres.tricky.cleverestech.ManagedFileCoordinator
import cleveres.tricky.cleverestech.StoredKeyboxInventory
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

object KeyboxAutoCleaner {
    internal data class CleanupResult(
        val detected: Int,
        val cancelled: Boolean,
        val moved: Int = 0,
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
        if (!cleanup.cancelled && cleanup.detected > 0) notifyUser(cleanup.detected)
        if (cleanup.cancelled) {
            Logger.i("AutoCleaner: Check stopped because automatic cleanup was disabled")
        } else {
            Logger.i("AutoCleaner: Finished check. Revoked/Invalid files detected: ${cleanup.detected}")
        }
    }

    /**
     * Rebinds every path to the exact descriptor-backed snapshot that produced its verification
     * result. Updates KeyboxValidityTracker so invalid entries are tracked in-place rather than
     * quarantined.
     */
    internal fun applyVerifiedResults(
        configDir: File,
        results: List<KeyboxVerifier.Result>,
        isEnabled: () -> Boolean,
        refresh: () -> Unit,
    ): CleanupResult =
        synchronized(ManagedFileCoordinator.monitor) {
            var detected = 0
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
                    detected++
                    Logger.i("AutoCleaner: Keybox ${result.filename} is ${result.status}. Retained with state metadata.")
                } catch (error: Exception) {
                    Logger.e("AutoCleaner: Failed to inspect ${result.filename}", error)
                }
            }

            KeyboxValidityTracker.update(results)
            refresh()
            CleanupResult(detected, cancelled, moved = 0)
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
                "$count keybox(es) are invalid, expired, or revoked. Check WebUI.",
            )
        val nullDevice = File("/dev/null")
        val process =
            try {
                ProcessBuilder(*cmd)
                    .redirectOutput(nullDevice)
                    .redirectError(nullDevice)
                    .start()
            } catch (e: Exception) {
                Logger.e("AutoCleaner: Failed to start notification process", e)
                return
            }
        try {
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
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    private fun isRegularFile(file: File): Boolean = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private const val HEX = "0123456789abcdef"
}
