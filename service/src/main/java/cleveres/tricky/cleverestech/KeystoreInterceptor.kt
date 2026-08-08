package cleveres.tricky.cleverestech

import android.annotation.SuppressLint
import android.hardware.security.keymint.SecurityLevel
import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.os.SystemClock
import android.system.keystore2.IKeystoreService
import android.system.keystore2.KeyEntryResponse
import cleveres.tricky.cleverestech.binder.BinderInterceptor
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.keystore.Utils
import kotlin.system.exitProcess

@SuppressLint("BlockedPrivateApi")
object KeystoreInterceptor : BinderInterceptor() {
    private const val INJECTION_RETRY_INTERVAL_MS = 15_000L

    private val getKeyEntryTransaction =
        getTransactCode(IKeystoreService.Stub::class.java, "getKeyEntry") // 2

    private lateinit var keystore: IBinder

    private var teeInterceptor: SecurityLevelInterceptor? = null
    private var strongBoxInterceptor: SecurityLevelInterceptor? = null
    private var teeTarget: IBinder? = null
    private var strongBoxTarget: IBinder? = null
    private var binderBackdoor: IBinder? = null

    @Volatile private var keystoreRegistered = false

    @Volatile private var registered = false

    @Volatile private var deathRecipientLinked = false

    override fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
    ): Result {
        if (target != keystore || code != getKeyEntryTransaction || !CertHack.canHack()) return Skip
        return if (Config.needHack(callingUid)) Continue else Skip
    }

    override fun onPostTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
        reply: Parcel?,
        resultCode: Int,
    ): Result {
        if (
            target != keystore ||
            code != getKeyEntryTransaction ||
            reply == null ||
            resultCode != 0 ||
            !CertHack.canHack() ||
            !Config.needHack(callingUid)
        ) {
            return Skip
        }
        try {
            reply.readException()
        } catch (e: Exception) {
            return Skip
        }
        val p = Parcel.obtain()
        try {
            val response = reply.readTypedObject(KeyEntryResponse.CREATOR)
            val originalChain = Utils.getCertificateChain(response)
            val newChain =
                if (originalChain == null) {
                    null
                } else {
                    CertHack.hackCertificateChain(originalChain, callingUid).takeUnless { it === originalChain }
                }

            if (newChain != null) {
                Utils.putCertificateChain(response, newChain)
                p.writeNoException()
                p.writeTypedObject(response, 0)
                return OverrideReply(0, p)
            } else {
                p.recycle()
            }
        } catch (t: Throwable) {
            Logger.e("Failed to rewrite a stored attestation certificate chain", t)
            p.recycle()
        }
        return Skip
    }

    private val triedCount = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile private var injected = false

    @Volatile private var cachedKeystorePid: Int? = null

    @Volatile private var injectedPid: Int? = null

    @Volatile private var lastInjectionAttemptMs = 0L

    private fun findKeystore2Pid(): Int? {
        val cachedPid = cachedKeystorePid
        if (cachedPid != null) {
            val buf = ByteArray(1024)
            try {
                val stream = java.io.FileInputStream("/proc/$cachedPid/cmdline")
                val length =
                    try {
                        stream.read(buf)
                    } finally {
                        stream.close()
                    }
                if (length > 0) {
                    var end = 0
                    var start = 0
                    while (end < length && buf[end] != 0.toByte()) {
                        if (buf[end] == 47.toByte()) start = end + 1 // Track last slash '/'
                        end++
                    }
                    val argv0 = String(buf, start, end - start)
                    if (argv0 == "keystore2") {
                        return cachedPid
                    }
                }
            } catch (e: Exception) {
                // Ignore file read errors
            }
            cachedKeystorePid = null
        }

        // Optimized directory listing to prevent N+1 File allocations.
        // Process spawning (like pidof) is avoided due to high overhead.
        val proc = java.io.File("/proc")
        if (!proc.exists() || !proc.isDirectory) return null

        val pids = proc.list() ?: return null
        val buf = ByteArray(1024)
        for (i in 0 until pids.size) {
            val pidStr = pids[i]
            if (pidStr.isNotEmpty() && pidStr[0] in '1'..'9') {
                try {
                    val stream = java.io.FileInputStream("/proc/$pidStr/cmdline")
                    val length =
                        try {
                            stream.read(buf)
                        } finally {
                            stream.close()
                        }
                    if (length > 0) {
                        var end = 0
                        var start = 0
                        while (end < length && buf[end] != 0.toByte()) {
                            if (buf[end] == 47.toByte()) start = end + 1 // Track last slash '/'
                            end++
                        }
                        val argv0 = String(buf, start, end - start)
                        if (argv0 == "keystore2") {
                            val parsedPid = pidStr.toInt()
                            cachedKeystorePid = parsedPid
                            return parsedPid
                        }
                    }
                } catch (e: Exception) {
                    // Ignore file read errors for individual processes
                }
            }
        }
        return null
    }

    @Synchronized
    fun tryRunKeystoreInterceptor(): Boolean {
        if (!Config.isSpoofEnabled) {
            stopKeystoreInterceptor()
            return true
        }
        if (registered && ::keystore.isInitialized && keystore.isBinderAlive) return true
        registered = false
        Logger.d("trying to register keystore interceptor (attempt=${triedCount.get()}) ...")
        val b =
            ServiceManager.getService("android.system.keystore2.IKeystoreService/default") ?: run {
                Logger.d("keystore2 service not yet available, will retry")
                return false
            }
        val bd = getBinderControlEndpoint(b)
        binderBackdoor = bd
        if (bd == null) {
            val pid = findKeystore2Pid()
            if (pid == null) {
                Logger.e("failed to find keystore2 pid! will retry (attempt=${triedCount.get()})")
                triedCount.incrementAndGet()
                return false
            }
            val now = SystemClock.elapsedRealtime()
            if (
                lastInjectionAttemptMs != 0L &&
                now - lastInjectionAttemptMs < INJECTION_RETRY_INTERVAL_MS
            ) {
                return false
            }
            lastInjectionAttemptMs = now
            val symbol = if (injected && injectedPid == pid) "resume" else "entry"
            Logger.i("trying to activate the keystore Binder hook ...")
            try {
                val modulePath = getModuleDir()
                val injectPath = "$modulePath/inject"
                val p =
                    ProcessBuilder(
                        injectPath,
                        pid.toString(),
                        "$modulePath/libcleverestricky.so",
                        symbol,
                    ).redirectOutput(java.io.File("/dev/null"))
                        .redirectError(java.io.File("/dev/null"))
                        .start()
                val completed = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                if (!completed) {
                    Logger.e("inject process timed out after 30s, killing it")
                    p.destroyForcibly()
                    triedCount.incrementAndGet()
                    return false
                }
                val exitCode = p.exitValue()
                if (exitCode != 0) {
                    Logger.e("failed to activate the keystore Binder hook (exit=$exitCode)!")
                    triedCount.incrementAndGet()
                    return false
                } else {
                    Logger.i("keystore Binder hook activated successfully")
                    injected = true
                    injectedPid = pid
                }
                triedCount.incrementAndGet()
                return false
            } catch (error: Exception) {
                triedCount.incrementAndGet()
                Logger.e("failed to run the keystore injector", error)
                return false
            }
        }
        if (!Config.isSpoofEnabled) {
            parkBinderHook(bd)
            return true
        }
        val ks = IKeystoreService.Stub.asInterface(b)
        val tee =
            try {
                ks.getSecurityLevel(SecurityLevel.TRUSTED_ENVIRONMENT)
            } catch (e: Exception) {
                null
            }
        val strongBox =
            try {
                ks.getSecurityLevel(SecurityLevel.STRONGBOX)
            } catch (e: Exception) {
                null
            }
        val interceptedCodes = validTransactCodes(getKeyEntryTransaction)
        keystore = b
        binderBackdoor = bd
        if (!registerBinderInterceptor(bd, b, this, interceptedCodes)) {
            Logger.e("Failed to register the Keystore Binder interceptor")
            parkBinderHook(bd)
            return false
        }
        keystoreRegistered = true

        Logger.i("Keystore Binder interceptor registered")
        if (tee != null) {
            val interceptor = SecurityLevelInterceptor()
            if (!registerBinderInterceptor(
                    bd,
                    tee.asBinder(),
                    interceptor,
                    SecurityLevelInterceptor.INTERCEPTED_CODES,
                )
            ) {
                Logger.e("Failed to register the TEE SecurityLevel interceptor")
                stopKeystoreInterceptor()
                return false
            }
            teeInterceptor = interceptor
            teeTarget = tee.asBinder()
            Logger.i("TEE SecurityLevel interceptor registered")
        } else {
            Logger.i("TEE SecurityLevel is unavailable")
        }
        if (strongBox != null) {
            val interceptor = SecurityLevelInterceptor()
            if (!registerBinderInterceptor(
                    bd,
                    strongBox.asBinder(),
                    interceptor,
                    SecurityLevelInterceptor.INTERCEPTED_CODES,
                )
            ) {
                Logger.e("Failed to register the StrongBox SecurityLevel interceptor")
                stopKeystoreInterceptor()
                return false
            }
            strongBoxInterceptor = interceptor
            strongBoxTarget = strongBox.asBinder()
            Logger.i("StrongBox SecurityLevel interceptor registered")
        } else {
            Logger.i("StrongBox SecurityLevel is unavailable")
        }

        try {
            keystore.linkToDeath(Killer, 0)
            deathRecipientLinked = true
        } catch (error: android.os.RemoteException) {
            Logger.w("Keystore exited before its interceptor lifecycle could be monitored")
            stopKeystoreInterceptor()
            return false
        }
        registered = true
        if (!Config.isSpoofEnabled) {
            stopKeystoreInterceptor()
            return true
        }
        triedCount.set(0)
        return true
    }

    fun isRunning(): Boolean = registered && ::keystore.isInitialized && keystore.isBinderAlive

    @Synchronized
    fun stopKeystoreInterceptor(): Boolean {
        val control = binderBackdoor
        var success = true
        if (control != null) {
            strongBoxInterceptor?.let { interceptor ->
                strongBoxTarget?.let { target ->
                    success = unregisterBinderInterceptor(control, target, interceptor) && success
                }
            }
            teeInterceptor?.let { interceptor ->
                teeTarget?.let { target ->
                    success = unregisterBinderInterceptor(control, target, interceptor) && success
                }
            }
            if (keystoreRegistered && ::keystore.isInitialized) {
                success = unregisterBinderInterceptor(control, keystore, this) && success
            }
        } else if (registered || keystoreRegistered) {
            success = false
        }
        if (deathRecipientLinked && ::keystore.isInitialized) {
            try {
                keystore.unlinkToDeath(Killer, 0)
            } catch (_: java.util.NoSuchElementException) {
                // The Binder driver already removed the recipient after death.
            }
            deathRecipientLinked = false
        }

        strongBoxInterceptor = null
        strongBoxTarget = null
        teeInterceptor = null
        teeTarget = null
        keystoreRegistered = false
        registered = false
        binderBackdoor = null
        return success
    }

    override fun onInterceptorReplaced() {
        if (deathRecipientLinked && ::keystore.isInitialized) {
            try {
                keystore.unlinkToDeath(Killer, 0)
            } catch (_: java.util.NoSuchElementException) {
                // The Binder driver already removed the recipient after death.
            }
        }
        deathRecipientLinked = false
        registered = false
        keystoreRegistered = false
        binderBackdoor = null
        teeInterceptor = null
        teeTarget = null
        strongBoxInterceptor = null
        strongBoxTarget = null
        Config.signalRuntimeController()
    }

    object Killer : IBinder.DeathRecipient {
        override fun binderDied() {
            Logger.d("keystore exit, daemon restart")
            exitProcess(0)
        }
    }
}
