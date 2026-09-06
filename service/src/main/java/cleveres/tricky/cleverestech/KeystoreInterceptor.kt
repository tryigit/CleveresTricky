package cleveres.tricky.cleverestech

import android.annotation.SuppressLint
import android.hardware.security.keymint.ErrorCode
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
    private const val FIRST_APPLICATION_UID = 10_000
    private const val MAX_PROC_SCAN_ENTRIES = 4_096
    private const val INJECTION_RETRY_INTERVAL_MS = 15_000L

    private val getKeyEntryTransaction =
        getTransactCode(IKeystoreService.Stub::class.java, "getKeyEntry") // 2

    private lateinit var keystore: IBinder

    private var teeInterceptor: SecurityLevelInterceptor? = null
    private var teeTarget: IBinder? = null
    private var strongboxInterceptor: SecurityLevelInterceptor? = null
    private var strongboxTarget: IBinder? = null
    private var binderBackdoor: IBinder? = null

    @Volatile private var keystoreRegistered = false

    @Volatile private var registered = false

    @Volatile private var deathRecipientLinked = false

    @Volatile private var lifecycleEpoch = 0L

    override fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
    ): Result {
        if (target != keystore) return Skip

        // Security-level discovery remains completely platform-owned. In particular, a StrongBox
        // request must reach Keystore2 unchanged so callers receive the genuine StrongBox child
        // binder when the device provides one. Never substitute TEE and never manufacture an
        // unavailable result for hardware that is actually present.
        if (!CertHack.canHack()) return Skip
        if (code == getKeyEntryTransaction) {
            val targeted = Config.needHack(callingUid)
            val mayReadGrantedChain =
                callingUid >= FIRST_APPLICATION_UID && CertHack.hasCachedCertificateChains()
            return if (targeted || mayReadGrantedChain) Continue else Skip
        }
        return Skip
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
        if (target != keystore || reply == null || resultCode != 0) return Skip

        if (!CertHack.canHack() || code != getKeyEntryTransaction) return Skip

        try {
            reply.readException()
        } catch (e: Exception) {
            return Skip
        }
        val p = Parcel.obtain()
        try {
            val response = reply.readTypedObject(KeyEntryResponse.CREATOR)
            val metadata = response?.metadata
            if (metadata == null) {
                p.recycle()
                return Skip
            }

            // getKeyEntry exposes the platform-owned security level directly in KeyMetadata.
            // Generic replacement policy applies to both TEE and StrongBox metadata.
            if (metadata.keySecurityLevel != SecurityLevel.TRUSTED_ENVIRONMENT &&
                metadata.keySecurityLevel != SecurityLevel.STRONGBOX) {
                p.recycle()
                return Skip
            }

            // Caller-signed attestation leaves must keep their original issuer and signature.
            // This also avoids re-parsing ordinary non-attested keys on every getKeyEntry call.
            if (!Utils.isCertificateChainRewriteCandidate(metadata)) {
                p.recycle()
                return Skip
            }

            val targeted = Config.needHack(callingUid)
            val mayReadGrantedChain = callingUid >= FIRST_APPLICATION_UID

            // Duck Detector's timing probe creates the keys once and then measures repeated
            // service.getKeyEntry calls. generateKey has already populated CertHack's replacement
            // cache for an attested key, so try the genuine raw leaf DER before constructing any
            // X509Certificate objects. A hit assigns the already-encoded replacement leaf/issuers
            // directly to KeyMetadata and avoids CertificateFactory, Certificate[] allocation,
            // getEncoded(), issuer parsing and every Rust backend operation on the measured path.
            if (
                (targeted || mayReadGrantedChain) &&
                CertHack.applyCachedCertificateChain(metadata)
            ) {
                p.writeNoException()
                p.writeTypedObject(response, 0)
                return OverrideReply(0, p)
            }

            if (!targeted) {
                p.recycle()
                return Skip
            }

            // Cache miss is the exceptional/recovery path. Match the 2.5.8 ordering here: parse the
            // returned chain and let CertHack classify the uncached leaf after its own cache lookup.
            // CertHack rejects an ordinary non-attested leaf locally before any Rust IPC.
            val originalChain = Utils.getCertificateChain(response)
            val newChain =
                originalChain?.let {
                    CertHack.hackCertificateChain(it, callingUid).takeUnless { rewritten -> rewritten === it }
                }
            if (newChain != null) {
                Utils.putCertificateChain(response, newChain)
                p.writeNoException()
                p.writeTypedObject(response, 0)
                return OverrideReply(0, p)
            }
            p.recycle()
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

        val proc = java.io.File("/proc")
        if (!proc.exists() || !proc.isDirectory) return null

        val buf = ByteArray(1024)
        try {
            java.nio.file.Files.newDirectoryStream(proc.toPath()).use { entries ->
                var scanned = 0
                for (entry in entries) {
                    if (++scanned > MAX_PROC_SCAN_ENTRIES) break
                    val pidStr = entry.fileName.toString()
                    if (pidStr.isEmpty() || pidStr[0] !in '1'..'9') continue
                    try {
                        val length =
                            java.nio.file.Files.newInputStream(entry.resolve("cmdline")).use { stream ->
                                stream.read(buf)
                            }
                        if (length <= 0) continue
                        var end = 0
                        var start = 0
                        while (end < length && buf[end] != 0.toByte()) {
                            if (buf[end] == 47.toByte()) start = end + 1
                            end++
                        }
                        if (String(buf, start, end - start) == "keystore2") {
                            val parsedPid = pidStr.toIntOrNull() ?: continue
                            cachedKeystorePid = parsedPid
                            return parsedPid
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun runNativeActivation(pid: Int, symbol: String): Boolean {
        val modulePath = getModuleDir()
        val injectPath = "$modulePath/inject"
        val process =
            try {
                ProcessBuilder(
                    injectPath,
                    pid.toString(),
                    "$modulePath/libcleverestricky.so",
                    symbol,
                    KernelIdentityManager.activationPayload(),
                ).redirectOutput(java.io.File("/dev/null"))
                    .redirectError(java.io.File("/dev/null"))
                    .start()
            } catch (error: Exception) {
                Logger.e("failed to start native activation", error)
                return false
            }
        return try {
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                Logger.e("native activation timed out after 30s, killing it")
                process.destroyForcibly()
                false
            } else {
                val exitCode = process.exitValue()
                if (exitCode != 0) Logger.e("native activation failed (exit=$exitCode)")
                exitCode == 0
            }
        } catch (error: Exception) {
            Logger.e("failed to run native activation", error)
            false
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    fun refreshKernelIdentity(): Boolean {
        val pid = findKeystore2Pid() ?: return false

        val needsActivation = synchronized(this) {
            injected && injectedPid == pid
        }
        if (!needsActivation) return true

        return runNativeActivation(pid, "resume")
    }

    fun tryRunKeystoreInterceptor(): Boolean {
        synchronized(this) {
            if (registered && ::keystore.isInitialized && keystore.isBinderAlive) return true
            registered = false
        }
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
            val symbol = synchronized(this) {
                if (lastInjectionAttemptMs != 0L && now - lastInjectionAttemptMs < INJECTION_RETRY_INTERVAL_MS) {
                    return false
                }
                lastInjectionAttemptMs = now
                if (injected && injectedPid == pid) "resume" else "entry"
            }

            Logger.i("trying to activate the keystore Binder hook ...")
            if (!runNativeActivation(pid, symbol)) {
                triedCount.incrementAndGet()
                return false
            }

            Logger.i("Keystore Binder hook activated successfully")
            synchronized(this) {
                injected = true
                injectedPid = pid
            }
            triedCount.incrementAndGet()
            return false
        }

        val ks = IKeystoreService.Stub.asInterface(b)
        val tee =
            try {
                ks.getSecurityLevel(SecurityLevel.TRUSTED_ENVIRONMENT)
            } catch (e: Exception) {
                Logger.e("Failed to obtain TEE SecurityLevel", e)
                null
            }

        val strongbox =
            try {
                ks.getSecurityLevel(SecurityLevel.STRONGBOX)
            } catch (e: Exception) {
                val isHardwareUnavailable =
                    e.javaClass.simpleName == "ServiceSpecificException" &&
                        runCatching {
                            e.javaClass.getField("errorCode").getInt(e) == ErrorCode.HARDWARE_TYPE_UNAVAILABLE
                        }.getOrDefault(false)
                if (!isHardwareUnavailable) {
                    Logger.e("Failed to obtain StrongBox SecurityLevel", e)
                }
                null
            }

        // Root discovery is not intercepted. Both TEE and StrongBox children
        // are hooked for certificate compatibility.
        val interceptedCodes = validTransactCodes(getKeyEntryTransaction)

        val registeredHook = registerBinderInterceptor(bd, b, this, interceptedCodes)
        if (!registeredHook) {
            Logger.e("Failed to register the Keystore Binder interceptor")
            parkBinderHook(bd)
            return false
        }

        val currentEpoch = synchronized(this) {
            keystore = b
            binderBackdoor = bd
            keystoreRegistered = true
            lifecycleEpoch
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
                stopKeystoreInterceptor()
                return false
            }
            val stale = synchronized(this) {
                if (lifecycleEpoch != currentEpoch || !keystoreRegistered) {
                    true
                } else {
                    teeInterceptor = interceptor
                    teeTarget = tee.asBinder()
                    false
                }
            }
            if (stale) {
                Logger.w("TEE interceptor registration raced with teardown; rolling back")
                unregisterBinderInterceptor(bd, tee.asBinder(), interceptor)
                return false
            }
            Logger.i("TEE SecurityLevel interceptor registered")
        } else {
            Logger.i("TEE SecurityLevel is unavailable")
        }

        if (strongbox != null) {
            val interceptor = SecurityLevelInterceptor()
            if (!registerBinderInterceptor(
                    bd,
                    strongbox.asBinder(),
                    interceptor,
                    SecurityLevelInterceptor.INTERCEPTED_CODES,
                )
            ) {
                Logger.e("Failed to register the StrongBox SecurityLevel interceptor")
                stopKeystoreInterceptor()
                return false
            }
            val stale = synchronized(this) {
                if (lifecycleEpoch != currentEpoch || !keystoreRegistered) {
                    true
                } else {
                    strongboxInterceptor = interceptor
                    strongboxTarget = strongbox.asBinder()
                    false
                }
            }
            if (stale) {
                Logger.w("StrongBox interceptor registration raced with teardown; rolling back")
                unregisterBinderInterceptor(bd, strongbox.asBinder(), interceptor)
                return false
            }
            Logger.i("StrongBox SecurityLevel interceptor registered")
        } else {
            Logger.i("StrongBox SecurityLevel is unavailable")
        }

        var linkSuccess = false
        try {
            b.linkToDeath(Killer, 0)
            linkSuccess = true
        } catch (error: android.os.RemoteException) {
            Logger.w("Keystore exited before its interceptor lifecycle could be monitored")
        }

        val linked = synchronized(this) {
            if (linkSuccess && lifecycleEpoch == currentEpoch && keystoreRegistered) {
                deathRecipientLinked = true
                registered = true
                true
            } else {
                false
            }
        }
        if (!linked) {
            if (linkSuccess) {
                runCatching { b.unlinkToDeath(Killer, 0) }
            }
            stopKeystoreInterceptor()
            return false
        }
        triedCount.set(0)
        return true
    }

    fun isRunning(): Boolean = registered && ::keystore.isInitialized && keystore.isBinderAlive

    fun stopKeystoreInterceptor(): Boolean {
        var targetAlive = false
        var control: IBinder? = null
        synchronized(this) {
            lifecycleEpoch++
            targetAlive = ::keystore.isInitialized && keystore.isBinderAlive
            control = binderBackdoor
        }

        if (control == null && targetAlive) {
            control = getBinderControlEndpoint(keystore)
        }

        var stopped = control?.let(::clearAndParkBinderHook) == true
        if (!stopped && control != null) {
            teeInterceptor?.let { interceptor ->
                teeTarget?.let { target ->
                    unregisterBinderInterceptor(control, target, interceptor)
                }
            }
            strongboxInterceptor?.let { interceptor ->
                strongboxTarget?.let { target ->
                    unregisterBinderInterceptor(control, target, interceptor)
                }
            }
            if (keystoreRegistered && ::keystore.isInitialized) {
                unregisterBinderInterceptor(control, keystore, this)
            }
            stopped = parkBinderHook(control)
        }

        val shouldUnlink = synchronized(this) {
            val hasKnownRegistration =
                registered || keystoreRegistered || teeInterceptor != null || strongboxInterceptor != null
            if (!targetAlive || (!hasKnownRegistration && control == null)) stopped = true
            if (!stopped) {
                binderBackdoor = control
                Logger.d("Keystore Binder hook cleanup remains pending")
                return false
            }
            deathRecipientLinked && ::keystore.isInitialized
        }

        if (shouldUnlink) {
            try {
                keystore.unlinkToDeath(Killer, 0)
            } catch (_: java.util.NoSuchElementException) {
                // The Binder driver already removed the recipient after death.
            }
        }

        synchronized(this) {
            deathRecipientLinked = false
            teeInterceptor = null
            teeTarget = null
            strongboxInterceptor = null
            strongboxTarget = null
            keystoreRegistered = false
            registered = false
            binderBackdoor = null
        }
        return true
    }

    override fun onInterceptorReplaced() {
        synchronized(this) {
            lifecycleEpoch++
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
            strongboxInterceptor = null
            strongboxTarget = null
        }
        Config.signalRuntimeController()
    }

    object Killer : IBinder.DeathRecipient {
        override fun binderDied() {
            Logger.d("keystore exit, daemon restart")
            exitProcess(0)
        }
    }
}
