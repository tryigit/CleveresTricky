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

        if (isTampered) {
            runCatching { WebUiBridge(WebServer(0, configDir, true), configDir).start() }
                .onFailure { Logger.e("Failed to start native WebUI lockdown endpoint", it) }
            Logger.e("Main: Running in tamper lockdown; native interceptors will not be registered")
            while (true) {
                delay(60000)
            }
        }

        try {
            if (PolicyMigration.sanitize(configDir)) {
                Logger.i("Prepared persisted policy state for this runtime")
            }
            Config.initialize()
            BootLogic.run()
            CertificatePolicyWatcher.start(configDir)
        } catch (e: Exception) {
            Logger.e("Failed to initialize Config/BootLogic", e)
            Logger.e("Main: Exiting so the module supervisor can retry initialization")
            return@runBlocking
        }

        try {
            WebUiBridge(WebServer(0, configDir), configDir).start()
        } catch (e: Exception) {
            Logger.e("Failed to start native WebUI bridge", e)
            Logger.e("Main: Exiting so the module supervisor can restore native WebUI service")
            return@runBlocking
        }

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
        var previousDrmEngineState: Boolean? = null
        var telephonyStopPending = false
        var drmStopPending = false
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
            var telSuccess = !Config.shouldInterceptTelephony || TelephonyInterceptor.isRunning()
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
                            telSuccess = TelephonyInterceptor.tryRunTelephonyInterceptor()
                        } catch (e: Exception) {
                            Logger.e("Telephony interceptor threw unexpected exception", e)
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
            drmJob?.join()

            if (!telephonyEnabled && (previousTelephonyState != false || telephonyStopPending)) {
                val wasPending = telephonyStopPending
                telephonyStopPending = !TelephonyInterceptor.stopTelephonyInterceptor()
                if (telephonyStopPending && !wasPending) {
                    Logger.w("Telephony hook cleanup is incomplete; retry scheduled")
                }
                telSuccess = !telephonyStopPending
            } else if (telephonyEnabled) {
                telephonyStopPending = false
            }
            previousTelephonyState = if (telephonyStopPending) null else telephonyEnabled

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
            if (!drmSuccess) Logger.d("DRM privacy interceptor not ready yet")

            try {
                Config.awaitRuntimeController(
                    if (ksSuccess && telSuccess && drmSuccess && !telephonyStopPending && !drmStopPending) 30_000 else 1_000,
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                CertificatePolicyWatcher.stop()
                DrmInterceptor.stopDrmInterceptor()
                Logger.i("Main: Runtime controller interrupted, shutting down")
                return@runBlocking
            }
        }
    }
}
