package cleveres.tricky.cleverestech

import android.os.FileObserver
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Optional daily Auto Identity refresh.
 *
 * The worker exists only while both the explicit cron marker and Build Identity are enabled.
 * Its cadence intentionally matches Auto Keybox Check: first run after one minute, then every
 * 1440 minutes. Turning either prerequisite off tears the worker down immediately.
 */
internal object CronAutoIdentity {
    const val TOGGLE_FILE = "cron_auto_identity"

    private val lock = Any()

    @Volatile
    private var configDir: File? = null

    @Volatile
    private var executor: ScheduledExecutorService? = null

    @Volatile
    private var observer: FileObserver? = null

    fun start(root: File) {
        synchronized(lock) {
            if (configDir?.absoluteFile != root.absoluteFile) {
                observer?.stopWatching()
                observer = null
                executor?.shutdownNow()
                executor = null
                configDir = root
            }
            if (observer == null) {
                observer =
                    object : FileObserver(root, CREATE or CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO) {
                        override fun onEvent(event: Int, path: String?) {
                            if (path == TOGGLE_FILE) refreshEnabled()
                        }
                    }.also { it.startWatching() }
            }
        }
        refreshEnabled()
    }

    fun onPolicyChanged() {
        if (configDir != null) refreshEnabled()
    }

    fun stop() {
        synchronized(lock) {
            observer?.stopWatching()
            observer = null
            executor?.shutdownNow()
            executor = null
            configDir = null
        }
    }

    internal fun refreshEnabled() {
        synchronized(lock) {
            val root = configDir ?: return
            val shouldRun =
                isRegularMarker(File(root, TOGGLE_FILE)) &&
                    PolicyState.isFeatureEnabled(PolicyState.Feature.BUILD_IDENTITY)
            val current = executor
            if (!shouldRun) {
                current?.shutdownNow()
                executor = null
                return
            }
            if (current != null && !current.isShutdown) return

            val created =
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "CleveresTricky-AutoIdentity").apply {
                        isDaemon = true
                        priority = Thread.MIN_PRIORITY
                    }
                }
            created.scheduleWithFixedDelay(
                { runCheck(root) },
                1,
                1440,
                TimeUnit.MINUTES,
            )
            executor = created
            Logger.i("Cron Auto Identity enabled; next refresh is scheduled")
        }
    }

    private fun runCheck(root: File) {
        if (!isEnabledNow(root)) {
            refreshEnabled()
            return
        }
        try {
            Logger.i("Cron Auto Identity: fetching a fresh identity")
            val resolved = AutoIdentityManager.fetchLatest()
            AutoIdentityPersistence.save(root, resolved).getOrThrow()
            if (!isEnabledNow(root)) {
                Logger.i("Cron Auto Identity: refresh saved but live apply skipped because the feature was disabled")
                refreshEnabled()
                return
            }
            val applied = IdentityRuntimeApplier.apply(root)
            if (applied.applied) {
                Logger.i("Cron Auto Identity: refreshed and applied Build Identity without reboot")
            } else if (applied.rebootRequired) {
                Logger.w("Cron Auto Identity: identity refreshed; reboot is required on this environment (${applied.reason})")
            } else {
                Logger.i("Cron Auto Identity: identity refreshed; live apply skipped (${applied.reason})")
            }
        } catch (error: Throwable) {
            Logger.e("Cron Auto Identity refresh failed", error)
        }
    }

    private fun isEnabledNow(root: File): Boolean =
        isRegularMarker(File(root, TOGGLE_FILE)) &&
            PolicyState.isFeatureEnabled(PolicyState.Feature.BUILD_IDENTITY)

    private fun isRegularMarker(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())
}
