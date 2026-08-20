package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.KeyboxAutoCleaner
import cleveres.tricky.cleverestech.util.SecureFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

private const val CONFIG_DIR_MODE = 448
private const val BACKEND_STARTUP_TIMEOUT_MS = 30_000L
private const val WEB_UI_START_ATTEMPTS = 12
private const val WEB_UI_START_INITIAL_DELAY_MS = 50L
private const val WEB_UI_START_MAX_DELAY_MS = 1_000L

private fun startWebUiBridge(
    configDir: File,
    isTampered: Boolean,
): WebUiBridge? {
    val bridge = WebUiBridge(WebServer(0, configDir, isTampered), configDir)
    var retryDelayMs = WEB_UI_START_INITIAL_DELAY_MS
    repeat(WEB_UI_START_ATTEMPTS) { attempt ->
        try {
            bridge.start()
            if (attempt > 0) {
                Logger.i("Native WebUI adapter registered after ${attempt + 1} attempts")
            }
            return bridge
        } catch (error: Exception) {
            Logger.e("Native WebUI adapter registration attempt ${attempt + 1} failed", error)
        }
        if (attempt + 1 < WEB_UI_START_ATTEMPTS) {
            try {
                Thread.sleep(retryDelayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            retryDelayMs = minOf(retryDelayMs * 2, WEB_UI_START_MAX_DELAY_MS)
        }
    }
    return null
}


private fun hasConfiguredKeyboxSource(configDir: File): Boolean {
    val roots =
        listOf(
            configDir,
            File(configDir, "keyboxes"),
            File("/data/adb/tricky_store/keybox"),
        )
    return roots.any { root ->
        root.listFiles()?.any { file ->
            file.isFile &&
                (file.name.endsWith(".xml", ignoreCase = true) || file.name.endsWith(".cbox", ignoreCase = true))
        } == true
    }
}

fun main(args: Array<String>) {
    Logger.i("Welcome to Service!")
    val isTampered =
        try {
            !Verification.check()
        } catch (error: Exception) {
            Logger.e("Module verification failed unexpectedly", error)
            true
        }
    if (isTampered) {
        Logger.e("TAMPER DETECTED: Disabling all interceptors and running in safe mode.")
    }
    runBlocking {
        val configDir = File("/data/adb/cleverestricky")
        try {
            SecureFile.mkdirs(configDir, CONFIG_DIR_MODE)
        } catch (e: Exception) {
            Logger.e("Failed to prepare configuration directory", e)
            return@runBlocking
        }

        val webUiBridge = startWebUiBridge(configDir, isTampered)
        if (webUiBridge == null) {
            Logger.e("Main: Native WebUI adapter could not register; exiting for supervisor retry")
            return@runBlocking
        }

        if (isTampered) {
            Logger.e("Main: Running in tamper lockdown; native interceptors will not be registered")
            while (true) {
                delay(60000)
            }
        }

        while (!NativeBackend.awaitReady(BACKEND_STARTUP_TIMEOUT_MS)) {

            if (Thread.currentThread().isInterrupted) {
                Logger.i("Main: Interrupted while waiting for Rust backend")
                return@runBlocking
            }
            // The daemon supervises and restarts the backend independently. A transient backend
            // failure must not terminate the Android adapter, because the daemon intentionally
            // treats adapter death as a full-stack failure and would otherwise churn the WebUI
            // control socket into EPIPE/Broken-pipe errors.
            Logger.e("Rust backend is not ready; waiting for backend supervisor recovery")
        }

        try {
            if (PolicyMigration.sanitize(configDir)) {
                Logger.i("Prepared persisted policy state for this runtime")
            }
            KernelIdentityManager.initialize(configDir)
            Config.initialize()
            check(BootLogic.run()) { "Boot property compatibility initialization failed" }
            CertificatePolicyWatcher.start(configDir)
        } catch (e: Exception) {
            Logger.e("Failed to initialize Config/BootLogic", e)
            Logger.e("Main: Exiting so the module supervisor can retry initialization")
            return@runBlocking
        }

        runCatching { KeyboxDirectoryRefreshWatcher.start(Config.keyboxDirectory) }
            .onFailure { Logger.e("Failed to install conflated keybox watcher; keeping legacy observer", it) }

        KeyboxAutoCleaner.start()

        // During an upgrade the native service can start before networking is ready. Revocation
        // checks intentionally fail closed, so a verified keybox may be unavailable on the first
        // scan even though the stored source is valid. Retry a few times in the background instead
        // of requiring a destructive environment reset from WebUI.
        if (CertHack.getKeyboxCount() == 0 && hasConfiguredKeyboxSource(configDir)) {
            launch(Dispatchers.IO) {
                val retryDelaysMs = longArrayOf(5_000L, 15_000L, 45_000L, 120_000L, 300_000L)
                for (retryDelay in retryDelaysMs) {
                    delay(retryDelay)
                    if (CertHack.getKeyboxCount() > 0) break
                    runCatching { Config.updateKeyBoxesSync() }
                        .onFailure { Logger.d { "Deferred keybox refresh failed: ${it.javaClass.simpleName}" } }
                    val recovered = CertHack.getKeyboxCount()
                    if (recovered > 0) {
                        Logger.i("Deferred keybox refresh activated $recovered verified keybox(es)")
                        break
                    }
                }
            }
        }

        var previousIdentityEngineState: Boolean? = null
        var previousTelephonyState: Boolean? = null
        var previousCameraState: Boolean? = null
        var previousDrmEngineState: Boolean? = null
        var telephonyStopPending = false
        var cameraStopPending = false
        var drmStopPending = false
        var runtimeRetryDelayMs = RUNTIME_RETRY_INITIAL_MS
        while (true) {
            val identityEngineEnabled = Config.isSpoofEnabled
            if (previousIdentityEngineState != identityEngineEnabled) {
                Logger.i(
                    if (identityEngineEnabled) {
                        "Identity Spoof Engine enabled; identity overrides may be applied"
                    } else {
                        "Identity Spoof Engine disabled; core Keystore/TEE protection remains active"
                    },
                )
                previousIdentityEngineState = identityEngineEnabled
            }

            // Keystore interception is the always-on core path. Disabling identity
            // spoofing must never unregister it or park the native Binder hook.
            var ksSuccess = KeystoreInterceptor.isRunning()
            var telSuccess =
                !Config.shouldInterceptTelephony ||
                    (
                        TelephonyInterceptor.isRunning() &&
                            (!Config.shouldInterceptSubscriptionVisibility || SubscriptionVisibilityInterceptor.isRunning())
                    )
            val cameraEnabled = Config.shouldInterceptCameraVisibility
            var cameraSuccess = !cameraEnabled || CameraVisibilityInterceptor.isRunning()
            val drmEnabled = Config.shouldInterceptDrm
            var drmSuccess = !drmEnabled || DrmInterceptor.isRunning()

            val ksJob =
                if (!ksSuccess) {
                    launch(Dispatchers.IO) {
                        try {
                            ksSuccess = KeystoreInterceptor.tryRunKeystoreInterceptor()
                        } catch (e: Exception) {
                            Logger.e("Keystore interceptor threw unexpected exception", e)
                        }
                    }
                } else {
                    null
                }

            val telephonyEnabled = Config.shouldInterceptTelephony
            val telJob =
                if (telephonyEnabled && !telSuccess) {
                    launch(Dispatchers.IO) {
                        try {
                            telSuccess =
                                TelephonyInterceptor.tryRunTelephonyInterceptor() &&
                                    SubscriptionVisibilityInterceptor.tryRun()
                        } catch (e: Exception) {
                            Logger.e("Telephony interceptor threw unexpected exception", e)
                        }
                    }
                } else {
                    null
                }

            val cameraJob =
                if (cameraEnabled && !cameraSuccess) {
                    launch(Dispatchers.IO) {
                        try {
                            cameraSuccess = CameraVisibilityInterceptor.tryRun()
                        } catch (e: Exception) {
                            Logger.e("Camera visibility interceptor threw unexpected exception", e)
                        }
                    }
                } else {
                    null
                }

            val drmJob =
                if (drmEnabled && !drmSuccess) {
                    launch(Dispatchers.IO) {
                        try {
                            drmSuccess = DrmInterceptor.tryRunDrmInterceptor()
                        } catch (e: Exception) {
                            Logger.e("DRM privacy interceptor threw unexpected exception", e)
                        }
                    }
                } else {
                    null
                }

            ksJob?.join()
            telJob?.join()
            cameraJob?.join()
            drmJob?.join()

            if (!telephonyEnabled && (previousTelephonyState != false || telephonyStopPending)) {
                val wasPending = telephonyStopPending
                val subscriptionStopped = SubscriptionVisibilityInterceptor.stop()
                telephonyStopPending = !subscriptionStopped || !TelephonyInterceptor.stopTelephonyInterceptor()
                if (telephonyStopPending && !wasPending) {
                    Logger.w("Telephony hook cleanup is incomplete; retry scheduled")
                }
                telSuccess = !telephonyStopPending
            } else if (telephonyEnabled) {
                telephonyStopPending = false
                if (!Config.shouldInterceptSubscriptionVisibility) {
                    SubscriptionVisibilityInterceptor.stop()
                }
            }
            previousTelephonyState = if (telephonyStopPending) null else telephonyEnabled

            if (
                !cameraEnabled &&
                (previousCameraState != false || cameraStopPending || CameraVisibilityInterceptor.isDraining())
            ) {
                val wasPending = cameraStopPending
                cameraStopPending = !CameraVisibilityInterceptor.stop()
                if (cameraStopPending && !wasPending) {
                    Logger.w("Camera visibility hook cleanup is incomplete; retry scheduled")
                }
                cameraSuccess = !cameraStopPending
            } else if (cameraEnabled) {
                cameraStopPending = false
            }
            previousCameraState = if (cameraStopPending) null else cameraEnabled

            if (!drmEnabled && (previousDrmEngineState != false || drmStopPending)) {
                val wasPending = drmStopPending
                drmStopPending = !DrmInterceptor.stopDrmInterceptor()
                if (drmStopPending && !wasPending) {
                    Logger.w("DRM privacy hook cleanup is incomplete; retry scheduled")
                }
                drmSuccess = !drmStopPending
            } else if (drmEnabled) {
                drmStopPending = false
            }
            previousDrmEngineState = if (drmStopPending) null else drmEnabled

            if (!ksSuccess) Logger.d("Core Keystore interceptor is not ready; retry scheduled")
            if (!telSuccess) Logger.d("Telephony interceptor not ready yet")
            if (!cameraSuccess) Logger.d("Camera visibility interceptor not ready yet")
            if (!drmSuccess) Logger.d("DRM privacy interceptor not ready yet")

            val runtimeHealthy =
                ksSuccess &&
                    telSuccess &&
                    cameraSuccess &&
                    drmSuccess &&
                    !telephonyStopPending &&
                    !cameraStopPending &&
                    !drmStopPending
            val controllerWaitMs = if (runtimeHealthy) 30_000L else runtimeRetryDelayMs
            try {
                Config.awaitRuntimeController(controllerWaitMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                KeyboxDirectoryRefreshWatcher.stop()
                CertificatePolicyWatcher.stop()
                SubscriptionVisibilityInterceptor.stop()
                CameraVisibilityInterceptor.stop()
                DrmInterceptor.stopDrmInterceptor()
                Logger.i("Main: Runtime controller interrupted, shutting down")
                return@runBlocking
            }
            runtimeRetryDelayMs = nextRuntimeRetryDelayMs(runtimeRetryDelayMs, runtimeHealthy)
        }
    }
}
