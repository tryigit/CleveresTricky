package cleveres.tricky.cleverestech

import android.annotation.SuppressLint
import android.hardware.security.keymint.ErrorCode
import android.hardware.security.keymint.KeyParameterValue
import android.hardware.security.keymint.KeyPurpose
import android.hardware.security.keymint.SecurityLevel
import android.hardware.security.keymint.Tag
import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.os.SystemClock
import android.system.keystore2.IKeystoreService
import android.system.keystore2.KeyEntryResponse
import android.system.keystore2.KeyMetadata
import cleveres.tricky.cleverestech.binder.BinderInterceptor
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.keystore.Utils
import java.security.cert.Certificate
import kotlin.system.exitProcess

@SuppressLint("BlockedPrivateApi")
object KeystoreInterceptor : BinderInterceptor() {
    private const val FIRST_APPLICATION_UID = 10_000
    private const val MAX_PROC_SCAN_ENTRIES = 4_096
    private const val INJECTION_RETRY_INTERVAL_MS = 15_000L

    private val getKeyEntryTransaction =
        getTransactCode(IKeystoreService.Stub::class.java, "getKeyEntry") // 2

    const val WARN_KEYMINT_TEE_BROKEN =
        "[WARN] Platform KeyMint HAL unreachable or TEE broken (SECURE_HW_COMMUNICATION_FAILED)."
    const val INFO_KEYMINT_ABORT_INJECTION =
        "[INFO] CleveresTricky requires a functional hardware KeyMint; aborting injection to prevent framework deadlock."
    const val INFO_KEYMINT_SEE_DOCS =
        "[INFO] Please consult documentation (docs/security/Attestation.md or docs/LOG.md) for TEE recovery guidance."

    private val updateSubcomponentTransaction =
        getTransactCode(IKeystoreService.Stub::class.java, "updateSubcomponent").takeIf { it > 0 } ?: 3

    private lateinit var keystore: IBinder

    private var teeInterceptor: SecurityLevelInterceptor? = null
    private var teeTarget: IBinder? = null
    private var strongboxInterceptor: SecurityLevelInterceptor? = null
    @Volatile internal var strongboxTarget: IBinder? = null
    private var binderBackdoor: IBinder? = null

    @Volatile private var keystoreRegistered = false

    @Volatile private var registered = false

    @Volatile private var deathRecipientLinked = false

    @Volatile private var lifecycleEpoch = 0L

    @Volatile private var teeBrokenCircuitBreaker = false

    @Volatile private var hasLoggedKeyMintFailure = false

    fun isTeeBroken(): Boolean = synchronized(this) { teeBrokenCircuitBreaker }

    fun tripTeeCircuitBreaker() {
        synchronized(this) {
            teeBrokenCircuitBreaker = true
        }
        logSecureHwCommunicationFailure()
        stopKeystoreInterceptor()
    }

    fun resetTeeCircuitBreaker() {
        synchronized(this) {
            teeBrokenCircuitBreaker = false
            hasLoggedKeyMintFailure = false
        }
    }

    fun isSecureHwCommunicationFailure(t: Throwable?): Boolean {
        var curr = t
        while (curr != null) {
            val errorCode = runCatching {
                curr.javaClass.getField("errorCode").getInt(curr)
            }.recoverCatching {
                curr.javaClass.getDeclaredField("errorCode").apply { isAccessible = true }.getInt(curr)
            }.recoverCatching {
                curr.javaClass.getMethod("getErrorCode").invoke(curr) as Int
            }.getOrNull()
            if (errorCode == ErrorCode.SECURE_HW_COMMUNICATION_FAILED) {
                return true
            }
            val msg = curr.message
            if (msg != null && (msg.contains("SECURE_HW_COMMUNICATION_FAILED", ignoreCase = true) || msg.contains("-49"))) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    fun logSecureHwCommunicationFailure() {
        synchronized(this) {
            if (hasLoggedKeyMintFailure) return
            hasLoggedKeyMintFailure = true
        }
        Logger.w(WARN_KEYMINT_TEE_BROKEN)
        Logger.i(INFO_KEYMINT_ABORT_INJECTION)
        Logger.i(INFO_KEYMINT_SEE_DOCS)

        System.err.println(WARN_KEYMINT_TEE_BROKEN)
        System.err.flush()
        System.out.println(INFO_KEYMINT_ABORT_INJECTION)
        System.out.println(INFO_KEYMINT_SEE_DOCS)
        System.out.flush()

        runCatching {
            val logFile = java.io.File("/data/adb/cleverestricky/native_runtime.log")
            if (logFile.parentFile?.exists() == true) {
                logFile.appendText("$WARN_KEYMINT_TEE_BROKEN\n$INFO_KEYMINT_ABORT_INJECTION\n$INFO_KEYMINT_SEE_DOCS\n")
            }
        }
    }

    @Throws(Exception::class)
    fun validateKeyMintHardware(service: IKeystoreService) {
        service.getSecurityLevel(SecurityLevel.TRUSTED_ENVIRONMENT)
    }

    override val requiresPostRequestPayload: Boolean = true

    private fun captureGetKeyEntryIdentity(data: Parcel, callingUid: Int): ByteArray? {
        val position = data.dataPosition()
        return try {
            data.enforceInterface(IKeystoreService.DESCRIPTOR)
            Utils.extractKeyDescriptorIdentity(data, callingUid)
        } catch (_: RuntimeException) {
            null
        } finally {
            data.setDataPosition(position)
        }
    }

    private fun isAttestKeyEntry(metadata: KeyMetadata): Boolean {
        val authorizations = metadata.authorizations ?: return false
        for (authorization in authorizations) {
            val parameter = authorization?.keyParameter ?: continue
            if (parameter.tag != Tag.PURPOSE) continue
            val value = parameter.value ?: continue
            if (value.tag == KeyParameterValue.keyPurpose && value.keyPurpose == KeyPurpose.ATTEST_KEY) {
                return true
            }
        }
        return false
    }

    private fun tryRewriteManagedLeafOnlyChild(
        callingUid: Int,
        requestedKeyId: ByteArray?,
        metadata: KeyMetadata,
    ): ByteArray? {
        if (requestedKeyId == null) return null
        val parentKeyId =
            ManagedAttestKeyRegistry.getParentKeyId(callingUid, requestedKeyId) ?: return null
        val originalLeaf = Utils.getLeafCertificate(metadata) ?: return null
        val isAttestKey = isAttestKeyEntry(metadata)
        if (!Utils.hasAndroidAttestationExtension(originalLeaf) && !isAttestKey) return null
        val originalLeafOnly = arrayOf<Certificate>(originalLeaf)
        val rewritten =
            rewriteManagedChildWithParentRecovery(
                original = originalLeafOnly,
                callingUid = callingUid,
                isAttestKey = isAttestKey,
                leafOnlySafe = true,
                parentKeyId = parentKeyId,
                childKeyId = requestedKeyId,
                platformSecurityLevel = metadata.keySecurityLevel,
            )
        if (rewritten === originalLeafOnly || rewritten.isEmpty()) return null
        return try {
            rewritten[0].encoded
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Rewrites child certificates under managed attest keys, releasing the publication lock across IPC.
     */
    private fun rewriteManagedChildWithParentRecovery(
        original: Array<Certificate>,
        callingUid: Int,
        isAttestKey: Boolean,
        leafOnlySafe: Boolean = true,
        parentKeyId: ByteArray,
        childKeyId: ByteArray?,
        platformSecurityLevel: Int,
    ): Array<Certificate> {
        val first = run {
            KeyboxActivation.lockPublishedSnapshot()
            try {
                CertHack.hackChildKeyCertificate(
                    original,
                    callingUid,
                    isAttestKey,
                    leafOnlySafe,
                    parentKeyId,
                    childKeyId,
                    platformSecurityLevel,
                )
            } finally {
                KeyboxActivation.unlockPublishedSnapshot()
            }
        }
        if (first !== original || !ManagedAttestKeyRegistry.isKnown(callingUid, parentKeyId)) {
            return first
        }
        val presence =
            runCatching { CertificateBackend.touchAttestKey(callingUid, parentKeyId) }
                .getOrElse { return first }
        if (presence != CertificateBackend.AttestKeyTouchResult.ABSENT) return first
        if (!ManagedAttestKeyRehydrator.restore(callingUid, parentKeyId)) return first

        KeyboxActivation.lockPublishedSnapshot()
        return try {
            CertHack.hackChildKeyCertificate(
                original,
                callingUid,
                isAttestKey,
                leafOnlySafe,
                parentKeyId,
                childKeyId,
                platformSecurityLevel,
            )
        } finally {
            KeyboxActivation.unlockPublishedSnapshot()
        }
    }

    override fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
    ): Result {
        if (target != keystore) {
            return Skip
        }

        // Security-level discovery remains completely platform-owned. In particular, a StrongBox
        // request must reach Keystore2 unchanged so callers receive the genuine StrongBox child
        // binder when the device provides one. Never substitute TEE and never manufacture an
        // unavailable result for hardware that is actually present.
        if (!CertHack.canHack()) {
            CertHack.noteAttestFailure(callingUid, 42)
            logAttestSkipThrottled(callingUid, 42, "no active keyboxes in memory (keybox_count=0)")
            return Skip
        }
        if (code == getKeyEntryTransaction) {
            val targeted = Config.needHack(callingUid)
            val mayReadGrantedChain =
                callingUid >= FIRST_APPLICATION_UID && CertHack.hasCachedCertificateChains()
            return if (targeted || mayReadGrantedChain) Continue else Skip
        }
        if (code == updateSubcomponentTransaction) {
            val targeted = Config.needHack(callingUid)
            if (!targeted) return Skip
            return handleUpdateSubcomponent(callingUid, data)
        }
        return Skip
    }

    private var lastAttestSkipLogNanos = 0L
    private const val ATTEST_SKIP_LOG_INTERVAL_NANOS = 5_000_000_000L

    private fun logAttestSkipThrottled(callingUid: Int, code: Int, reason: String) {
        val now = System.nanoTime()
        if (now - lastAttestSkipLogNanos >= ATTEST_SKIP_LOG_INTERVAL_NANOS) {
            lastAttestSkipLogNanos = now
            Logger.w("Attestation skipped for UID $callingUid (code $code): $reason")
        }
    }

    private fun handleUpdateSubcomponent(callingUid: Int, data: Parcel): Result {
        val info = Utils.parseUpdateSubcomponentRequest(data, callingUid) ?: return Skip
        val publicCert = info.publicCert ?: return Skip
        val isAttestKey =
            ManagedAttestKeyRegistry.isAttestKey(callingUid, info.keyId) ||
                Utils.isAttestKeyCertificate(publicCert)
        if (!isAttestKey) {
            return Skip
        }

        val originalCert = Utils.toCertificate(publicCert) ?: return Skip
        val originalChain = arrayOf<Certificate>(originalCert)
        val platformSecurityLevel =
            ManagedAttestKeyRegistry.getPlatformSecurityLevel(callingUid, info.keyId)

        val rewritten =
            CertHack.hackAttestKeyCertificateChain(
                originalChain,
                callingUid,
                true,
                info.keyId,
                platformSecurityLevel,
            )
        if (rewritten === originalChain || rewritten.isEmpty()) {
            return Skip
        }

        val newPublicCert = runCatching { rewritten[0].encoded }.getOrNull() ?: return Skip
        ManagedAttestKeyRegistry.remember(
            callingUid,
            info.keyId,
            null,
            publicCert,
            true,
            platformSecurityLevel,
            newPublicCert,
        )

        val replacement =
            Utils.createRewrittenUpdateSubcomponentParcel(data, info, newPublicCert)
                ?: return Skip
        return OverrideData(replacement)
    }

    fun queryParentCertificate(parentDescriptor: android.system.keystore2.KeyDescriptor, callingUid: Int): ByteArray? {
        val target = if (this::keystore.isInitialized) keystore else return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IKeystoreService.DESCRIPTOR)
            val queryNspace = if (parentDescriptor.domain == 0) callingUid.toLong() else parentDescriptor.nspace
            Utils.writeKeyDescriptorToParcel(
                data,
                parentDescriptor.domain,
                queryNspace,
                parentDescriptor.alias,
                parentDescriptor.blob,
            )
            val success = target.transact(getKeyEntryTransaction, data, reply, 0)
            if (!success) return null
            reply.readException()
            val parsed = Utils.parseKeyEntryResponseParcel(reply)
            if (parsed != null && parsed.leafEncoded != null && parsed.leafEncoded.isNotEmpty()) {
                parsed.leafEncoded
            } else {
                val response = reply.readTypedObject(KeyEntryResponse.CREATOR)
                response?.metadata?.certificate
            }
        } catch (_: Throwable) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
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
        if (target != keystore || code != getKeyEntryTransaction) {
            return Skip
        }
        val requestedKeyId = captureGetKeyEntryIdentity(data, callingUid)

        if (reply == null || resultCode != 0 || !CertHack.canHack()) return Skip

        try {
            reply.readException()
        } catch (e: Exception) {
            return Skip
        }
        val forceManagedAttestRefresh =
            if (requestedKeyId != null && ManagedAttestKeyRegistry.isAttestKey(callingUid, requestedKeyId)) {
                val touchResult =
                    runCatching { CertificateBackend.touchAttestKey(callingUid, requestedKeyId) }
                        .getOrElse { return Skip }
                when (touchResult) {
                    CertificateBackend.AttestKeyTouchResult.PRESENT -> false
                    CertificateBackend.AttestKeyTouchResult.ABSENT ->
                        !ManagedAttestKeyRehydrator.restore(callingUid, requestedKeyId)
                    CertificateBackend.AttestKeyTouchResult.UNAVAILABLE -> return Skip
                }
            } else {
                false
            }
        try {
            // Duck Detector measures repeated service.getKeyEntry calls, not generateKey. Read only
            // the stable-AIDL offsets and genuine leaf bytes on that path. A cache hit rewrites the
            // existing reply in place, avoiding the KeyEntryResponse/KeyMetadata object graph and
            // the second Parcel that made attested reads observably slower than non-attested reads.
            val parsed = Utils.parseKeyEntryResponseParcel(reply)
            if (parsed != null) {
                if (
                    parsed.keySecurityLevel != SecurityLevel.TRUSTED_ENVIRONMENT &&
                    parsed.keySecurityLevel != SecurityLevel.STRONGBOX
                ) {
                    return Skip
                }

                if (!forceManagedAttestRefresh) {
                    when (CertHack.applyCachedCertificateChain(reply, parsed)) {
                        CertHack.CachedParcelAction.REWRITTEN ->
                            return OverrideReply(code = 0, reply = reply)
                        CertHack.CachedParcelAction.PASSTHROUGH -> return Skip
                        CertHack.CachedParcelAction.MISS -> Unit
                    }
                }

                if (!Config.needHack(callingUid)) {
                    return Skip
                }
                if (parsed.hasLeafOnlyCertificate() &&
                    (requestedKeyId == null ||
                        (!ManagedAttestKeyRegistry.isAttestKey(callingUid, requestedKeyId) &&
                            ManagedAttestKeyRegistry.getParentKeyId(callingUid, requestedKeyId) == null))
                ) {
                    return Skip
                }
            }

            // Compatibility fallback for a non-standard parcel or an uncached full chain.
            val response = reply.readTypedObject(KeyEntryResponse.CREATOR)
            val metadata = response?.metadata ?: return Skip

            // getKeyEntry exposes the platform-owned security level directly in KeyMetadata.
            // Generic replacement policy applies to both TEE and StrongBox metadata.
            if (metadata.keySecurityLevel != SecurityLevel.TRUSTED_ENVIRONMENT &&
                metadata.keySecurityLevel != SecurityLevel.STRONGBOX) {
                return Skip
            }

            // Caller-signed attestation leaves must keep their original issuer and signature.
            // This also avoids re-parsing ordinary non-attested keys on every getKeyEntry call.
            val isFullChain = Utils.isCertificateChainRewriteCandidate(metadata)
            val isLeafOnly = Utils.hasRewritableLeafCertificate(metadata)
            if (!isFullChain && !isLeafOnly) {
                return Skip
            }

            val targeted = Config.needHack(callingUid)
            val mayReadGrantedChain = callingUid >= FIRST_APPLICATION_UID

            // A non-standard parcel cannot use the raw fast path, but it may still carry a cacheable
            // platform KeyMetadata object. Keep this path contract-compatible for vendor variants.
            if (
                !forceManagedAttestRefresh &&
                (targeted || mayReadGrantedChain) &&
                CertHack.applyCachedCertificateChain(metadata)
            ) {
                val p = Parcel.obtain()
                try {
                    p.writeNoException()
                    p.writeTypedObject(response, 0)
                    return OverrideReply(0, p)
                } catch (t: Throwable) {
                    p.recycle()
                    throw t
                }
            }

            val originalLeaf = Utils.getLeafCertificate(metadata)
            val isAttestKey = isAttestKeyEntry(metadata)
            if (!targeted || (!isFullChain && (!isAttestKey || requestedKeyId == null))) {
                if (targeted && isLeafOnly) {
                    val rewrittenLeaf =
                        tryRewriteManagedLeafOnlyChild(callingUid, requestedKeyId, metadata)
                    if (rewrittenLeaf != null) {
                        metadata.certificate = rewrittenLeaf
                        val p = Parcel.obtain()
                        try {
                            p.writeNoException()
                            p.writeTypedObject(response, 0)
                            return OverrideReply(0, p)
                        } catch (t: Throwable) {
                            p.recycle()
                            throw t
                        }
                    }
                }
                return Skip
            }

            // Cache miss fallback for full chains: verify attestation extension before invoking CertHack.
            if (
                originalLeaf == null ||
                (!Utils.hasAndroidAttestationExtension(originalLeaf) && !isAttestKey) ||
                (isAttestKey && requestedKeyId == null)
            ) {
                return Skip
            }

            val attestKeyId = requestedKeyId?.takeIf { isAttestKey }
            val originalChain = Utils.getCertificateChain(response)
            var resolvedParentKeyId: ByteArray? = null
            val newChain =
                originalChain?.let { chain ->
                    val rewritten =
                        if (attestKeyId != null) {
                            // Persistent Android attest-key aliases survive backend restarts. Re-run
                            // the attest-key path so Rust derives the same synthetic SPKI from the
                            // descriptor instead of publishing a generic rewrite with the genuine
                            // parent public key.
                            CertHack.hackAttestKeyCertificateChain(
                                chain,
                                callingUid,
                                false,
                                attestKeyId,
                                metadata.keySecurityLevel,
                            )
                        } else {
                            val parentKeyId =
                                (requestedKeyId?.let { ManagedAttestKeyRegistry.getParentKeyId(callingUid, it) })
                                    ?: (if (chain.size > 1) {
                                        val issuerDer = runCatching { chain[1].encoded }.getOrNull()
                                        (CertHack.findAttestKeyIdByCertificate(callingUid, issuerDer)
                                            ?: ManagedAttestKeyRegistry.findAttestKeyIdByCertificate(callingUid, issuerDer))
                                            ?.takeUnless { requestedKeyId != null && it.contentEquals(requestedKeyId) }
                                    } else {
                                        null
                                    })
                            resolvedParentKeyId = parentKeyId
                            if (parentKeyId != null) {
                                rewriteManagedChildWithParentRecovery(
                                    original = chain,
                                    callingUid = callingUid,
                                    isAttestKey = false,
                                    leafOnlySafe = false,
                                    parentKeyId = parentKeyId,
                                    childKeyId = requestedKeyId,
                                    platformSecurityLevel = metadata.keySecurityLevel,
                                )
                            } else {
                                CertHack.hackCertificateChain(chain, callingUid, false)
                            }
                        }
                    rewritten.takeUnless { it === chain }
                }
            if (newChain != null) {
                if (attestKeyId != null) {
                    ManagedAttestKeyRegistry.remember(
                        callingUid,
                        attestKeyId,
                        null,
                        metadata.certificate,
                        true,
                        metadata.keySecurityLevel,
                        newChain.getOrNull(0)?.encoded,
                    )
                } else if (resolvedParentKeyId != null && requestedKeyId != null) {
                    ManagedAttestKeyRegistry.remember(
                        callingUid,
                        requestedKeyId,
                        resolvedParentKeyId,
                        metadata.certificate,
                        false,
                        metadata.keySecurityLevel,
                        newChain.getOrNull(0)?.encoded,
                    )
                }
                if (!CertHack.applyCachedCertificateChain(metadata)) {
                    Utils.putCertificateChain(response, newChain)
                }
                val p = Parcel.obtain()
                try {
                    p.writeNoException()
                    p.writeTypedObject(response, 0)
                    return OverrideReply(0, p)
                } catch (t: Throwable) {
                    p.recycle()
                    throw t
                }
            }
        } catch (t: Throwable) {
            Logger.e("Failed to rewrite a stored attestation certificate chain", t)
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
            if (teeBrokenCircuitBreaker) return false
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

            val ksPrecheck = IKeystoreService.Stub.asInterface(b)
            try {
                validateKeyMintHardware(ksPrecheck)
            } catch (e: Exception) {
                if (isSecureHwCommunicationFailure(e)) {
                    tripTeeCircuitBreaker()
                    return false
                }
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
                if (isSecureHwCommunicationFailure(e)) {
                    tripTeeCircuitBreaker()
                    return false
                }
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
        val interceptedCodes =
            validTransactCodes(getKeyEntryTransaction) + validTransactCodes(updateSubcomponentTransaction)

        val expectedEpoch = synchronized(this) { lifecycleEpoch }

        val registeredHook =
            registerBinderInterceptor(
                bd,
                b,
                this,
                interceptedCodes,
                CAP_OMIT_POST_REQUEST_PAYLOAD,
            )
        if (!registeredHook) {
            Logger.e("Failed to register the Keystore Binder interceptor")
            parkBinderHook(bd)
            return false
        }

        val currentEpoch = synchronized(this) {
            if (lifecycleEpoch != expectedEpoch) {
                null
            } else {
                keystore = b
                binderBackdoor = bd
                keystoreRegistered = true
                lifecycleEpoch
            }
        }
        if (currentEpoch == null) {
            Logger.w("Root interceptor registration raced with teardown; rolling back")
            unregisterBinderInterceptor(bd, b, this)
            parkBinderHook(bd)
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
                    CAP_OMIT_POST_REQUEST_PAYLOAD,
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
            val preStale =
                synchronized(this) {
                    if (lifecycleEpoch != currentEpoch || !keystoreRegistered) {
                        true
                    } else {
                        strongboxTarget = strongbox.asBinder()
                        false
                    }
                }
            if (preStale) {
                Logger.w("Keystore registration stale before StrongBox interceptor could be registered")
                stopKeystoreInterceptor()
                return false
            }

            if (!registerBinderInterceptor(
                    bd,
                    strongbox.asBinder(),
                    interceptor,
                    SecurityLevelInterceptor.INTERCEPTED_CODES,
                    CAP_OMIT_POST_REQUEST_PAYLOAD,
                )
            ) {
                Logger.e("Failed to register the StrongBox SecurityLevel interceptor")
                synchronized(this) {
                    if (strongboxTarget === strongbox.asBinder()) {
                        strongboxTarget = null
                    }
                }
                stopKeystoreInterceptor()
                return false
            }
            val stale =
                synchronized(this) {
                    if (lifecycleEpoch != currentEpoch || !keystoreRegistered) {
                        if (strongboxTarget === strongbox.asBinder()) {
                            strongboxTarget = null
                        }
                        true
                    } else {
                        strongboxInterceptor = interceptor
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
