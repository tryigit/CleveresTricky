package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxAutoCleaner
import cleveres.tricky.cleverestech.util.SecureFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

private const val CONFIG_DIR_MODE = 448

fun main(args: Array<String>) {
    Logger.i("Welcome to Service!")
    val isTampered = !Verification.check()
    if (isTampered) {
        Logger.e("TAMPER DETECTED: Disabling all interceptors and running in safe mode.")
    }
    runBlocking {
        val configDir = File("/data/adb/cleverestricky")

        // === WebUI Setup ===
        // The port file must be written regardless of whether the readiness probe
        // succeeds, so that action.sh can always open the correct URL.
        try {
            Logger.d("Main: Preparing WebUI config directory at ${configDir.absolutePath}")
            val server = WebServer(WEB_UI_PORT, configDir, isTampered)
            Logger.d("Main: Starting WebUI server bootstrap on requested port $WEB_UI_PORT")
            try {
                server.startAsync()
                Logger.d("Main: WebUI server readiness probe succeeded on $WEB_UI_LOOPBACK_HOST:${server.listeningPort}")
            } catch (e: Exception) {
                // Readiness probe timed out; the server thread may still be
                // binding.  Log and continue; the port file will be written below
                // if listeningPort > 0 (i.e. NanoHTTPD opened the ServerSocket).
                Logger.e("WebServer readiness probe failed; will write port file if server bound (port > 0)", e)
            }
            val port = server.listeningPort
            val token = server.token
            Logger.i("Web server on port $port (alive=${server.isAlive})")
            Logger.d("Main: WebUI server on $WEB_UI_LOOPBACK_HOST:$port (tokenLength=${token.length})")
            if (port > 0) {
                val portFile = File(configDir, "web_port")
                // Secure directory before writing sensitive file
                try {
                    SecureFile.mkdirs(configDir, CONFIG_DIR_MODE) // 0700
                    Logger.d("Main: Ensured WebUI config directory permissions for ${configDir.absolutePath}")
                } catch (t: Throwable) {
                    Logger.e("failed to set permissions for config dir", t)
                }

                SecureFile.writeText(portFile, "$port|$token")
                Logger.d("Main: Wrote WebUI port metadata to ${portFile.absolutePath}")
            } else {
                Logger.e("Main: Server reported invalid port $port after start; port file not written")
            }
        } catch (e: Exception) {
            Logger.e("Failed to start web server", e)
        }

        // If tampered, we stop here. We only serve the WebUI warning page.
        if (isTampered) {
            Logger.e("Main: Running in tamper lockdown; native interceptors will not be registered")
            // Keep the daemon alive just to serve the WebUI
            while (true) {
                delay(60000)
            }
        }

        // === Config Initialization ===
        // Load keyboxes and all settings before the interceptor loop.  This
        // guarantees that CertHack.canHack() returns true as soon as the
        // first attestation request arrives, even if injection is delayed or
        // permanently blocked by a ptrace conflict with another module.
        try {
            SecureFile.mkdirs(configDir, CONFIG_DIR_MODE)
            Config.initialize()
            BootLogic.run()
        } catch (e: Exception) {
            Logger.e("Failed to initialize Config/BootLogic", e)
            Logger.e("Main: Interceptors remain disabled because initialization did not complete")
            while (true) delay(60_000)
        }

        KeyboxAutoCleaner.start()

        // Runtime controller. The master switch owns the complete interceptor
        // lifecycle, including native registration teardown when it is paused.
        var previousEngineState: Boolean? = null
        var previousTelephonyState: Boolean? = null
        while (true) {
            val engineEnabled = Config.isSpoofEnabled
            if (!engineEnabled) {
                if (previousEngineState != false) {
                    val telephonyStopped = TelephonyInterceptor.stopTelephonyInterceptor()
                    val keystoreStopped = KeystoreInterceptor.stopKeystoreInterceptor()
                    if (!telephonyStopped || !keystoreStopped) {
                        Logger.w("One or more Binder registrations had already disappeared while pausing")
                    }
                    Logger.i("Spoof Engine paused; Binder hooks are parked")
                }
                previousEngineState = false
                previousTelephonyState = Config.isTelephonyEnabled
                try {
                    Config.awaitRuntimeController(30_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@runBlocking
                }
                continue
            }

            if (previousEngineState == false) {
                Logger.i("Spoof Engine resumed; restoring configured Binder interceptors")
            }
            previousEngineState = true

            var ksSuccess = KeystoreInterceptor.isRunning()
            var telSuccess = !Config.isTelephonyEnabled || TelephonyInterceptor.isRunning()

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
            val telJob =
                if (!telSuccess) {
                    launch(Dispatchers.IO) {
                        try {
                            if (Config.isTelephonyEnabled) {
                                telSuccess = TelephonyInterceptor.tryRunTelephonyInterceptor()
                            }
                        } catch (e: Exception) {
                            Logger.e("Telephony interceptor threw unexpected exception", e)
                        }
                    }
                } else {
                    null
                }

            ksJob?.join()
            telJob?.join()

            val telephonyEnabled = Config.isTelephonyEnabled
            if (!telephonyEnabled && previousTelephonyState != false) {
                TelephonyInterceptor.stopTelephonyInterceptor()
            }
            previousTelephonyState = telephonyEnabled

            if (!ksSuccess) Logger.d("Keystore interceptor is not ready; retry scheduled")
            if (!telSuccess) {
                Logger.d("Telephony interceptor not ready yet")
            }

            try {
                Config.awaitRuntimeController(if (ksSuccess && telSuccess) 30_000 else 1_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                Logger.i("Main: Runtime controller interrupted, shutting down")
                return@runBlocking
            }
        }
    }
}
