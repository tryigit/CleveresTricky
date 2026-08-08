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
    private var binderBackdoor: IBinder? = null

    override fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
    ): Result {
        if (target != keystore || code != getKeyEntryTransaction || !CertHack.canHack()) return Skip
        Logger.d { "intercept pre $target uid=$callingUid pid=$callingPid dataSz=${data.dataSize()}" }
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
        // Optimization: Replace runCatching with try-catch to avoid Result object allocation in hot path
        try {
            reply.readException()
        } catch (e: Exception) {
            return Skip
        }
        val p = Parcel.obtain()
        Logger.d { "intercept post $target uid=$callingUid pid=$callingPid dataSz=${data.dataSize()} replySz=${reply.dataSize()}" }
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
                Logger.d { "Rewrote stored attestation chain for uid=$callingUid" }
                p.writeNoException()
                p.writeTypedObject(response, 0)
                return OverrideReply(0, p)
            } else {
                p.recycle()
            }
        } catch (t: Throwable) {
            Logger.e("failed to hack certificate chain of uid=$callingUid pid=$callingPid!", t)
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

    fun tryRunKeystoreInterceptor(): Boolean {
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
            if (injected && injectedPid == pid) {
                Logger.d("Waiting for the injected keystore control endpoint")
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
            Logger.i("trying to inject keystore (native Binder control endpoint unavailable) ...")
            try {
                Logger.i("found keystore2 at pid=$pid, injecting libcleverestricky.so ...")
                val modulePath = getModuleDir()
                val injectPath = "$modulePath/inject"
                val p =
                    ProcessBuilder(
                        injectPath,
                        pid.toString(),
                        "$modulePath/libcleverestricky.so",
                        "entry",
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
                    Logger.e("failed to inject keystore (exit=$exitCode)!")
                    triedCount.incrementAndGet()
                    return false
                } else {
                    Logger.i("injected keystore successfully")
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
        if (!registerBinderInterceptor(bd, b, this, interceptedCodes)) {
            Logger.e("Failed to register the Keystore Binder interceptor")
            return false
        }

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
                return false
            }
            teeInterceptor = interceptor
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
                return false
            }
            strongBoxInterceptor = interceptor
            Logger.i("StrongBox SecurityLevel interceptor registered")
        } else {
            Logger.i("StrongBox SecurityLevel is unavailable")
        }

        keystore.linkToDeath(Killer, 0)
        triedCount.set(0)
        return true
    }

    object Killer : IBinder.DeathRecipient {
        override fun binderDied() {
            Logger.d("keystore exit, daemon restart")
            exitProcess(0)
        }
    }
}
