package cleveres.tricky.cleverestech

import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.os.SystemClock
import android.telephony.SubscriptionManager
import cleveres.tricky.cleverestech.binder.BinderInterceptor
import com.android.internal.telephony.IPhoneSubInfo
import java.io.File
import java.util.concurrent.atomic.AtomicLong

object TelephonyInterceptor : BinderInterceptor() {
    private const val PHONE_SUB_INFO_DESCRIPTOR = "com.android.internal.telephony.IPhoneSubInfo"
    private const val INJECTION_RETRY_INTERVAL_MS = 30_000L
    private const val MALFORMED_REQUEST_LOG_INTERVAL_MS = 60_000L

    private val getDeviceIdTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getDeviceId")
    private val getDeviceIdWithFeatureTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getDeviceIdWithFeature")
    private val getDeviceIdForPhoneTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getDeviceIdForPhone")
    private val getImeiForSubscriberTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getImeiForSubscriber")

    private val getSubscriberIdTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getSubscriberId")
    private val getSubscriberIdWithFeatureTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getSubscriberIdWithFeature")
    private val getSubscriberIdForSubscriberTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getSubscriberIdForSubscriber")

    private val getIccSerialNumberTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getIccSerialNumber")
    private val getIccSerialNumberWithFeatureTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getIccSerialNumberWithFeature")
    private val getIccSerialNumberForSubscriberTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getIccSerialNumberForSubscriber")

    private val getLine1NumberTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getLine1Number")
    private val getLine1NumberForSubscriberTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getLine1NumberForSubscriber")

    private val getMeidForSubscriberTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getMeidForSubscriber")
    private val getMsisdnTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getMsisdn")
    private val getMsisdnForSubscriberTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getMsisdnForSubscriber")

    private val interceptedCodes =
        validTransactCodes(
            getDeviceIdTransaction,
            getDeviceIdWithFeatureTransaction,
            getDeviceIdForPhoneTransaction,
            getImeiForSubscriberTransaction,
            getSubscriberIdTransaction,
            getSubscriberIdWithFeatureTransaction,
            getSubscriberIdForSubscriberTransaction,
            getIccSerialNumberTransaction,
            getIccSerialNumberWithFeatureTransaction,
            getIccSerialNumberForSubscriberTransaction,
            getLine1NumberTransaction,
            getLine1NumberForSubscriberTransaction,
            getMeidForSubscriberTransaction,
            getMsisdnTransaction,
            getMsisdnForSubscriberTransaction,
        )

    private lateinit var iphonesubinfo: IBinder
    private val triedCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val lastMalformedRequestLogMs = AtomicLong(0L)

    @Volatile
    private var injected = false

    @Volatile
    private var registered = false

    @Volatile
    private var binderBackdoor: IBinder? = null

    @Volatile
    private var deathRecipientLinked = false

    @Volatile
    private var injectedPid: Int? = null

    @Volatile
    private var lastInjectionAttemptMs = 0L

    private val phoneDeathRecipient =
        object : IBinder.DeathRecipient {
            override fun binderDied() {
                Logger.e("Phone subscription service exited; resetting interceptor state")
                registered = false
                deathRecipientLinked = false
                injected = false
                injectedPid = null
                binderBackdoor = null
                triedCount.set(0)
                Config.signalRuntimeController()
            }
        }

    override fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
    ): Result =
        if (
            target == iphonesubinfo &&
            code in interceptedCodes &&
            Config.isTelephonyEnabled &&
            Config.needHack(callingUid)
        ) {
            Continue
        } else {
            Skip
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
            target != iphonesubinfo ||
            code !in interceptedCodes ||
            reply == null ||
            resultCode != 0 ||
            !Config.isTelephonyEnabled ||
            !Config.needHack(callingUid)
        ) {
            return Skip
        }

        val replyPosition = reply.dataPosition()
        val originalValue: String?
        try {
            reply.readException()
            originalValue = reply.readString()
        } catch (_: RuntimeException) {
            reply.setDataPosition(replyPosition)
            return Skip
        }
        reply.setDataPosition(replyPosition)

        // Preserve Android's permission and availability decision. A null or empty
        // platform result must never be upgraded into a readable identifier.
        if (originalValue.isNullOrEmpty()) return Skip

        val identifiers = Config.getIdentityOverrides()
        val spoofedVal =
            when (code) {
                getDeviceIdTransaction,
                getDeviceIdWithFeatureTransaction,
                -> identifiers.imei
                getDeviceIdForPhoneTransaction -> {
                    val phoneId = readLeadingIntArgument(data) ?: return Skip
                    identifiers.imeiForSlot(phoneId)
                }
                getImeiForSubscriberTransaction -> {
                    val slotIndex = readSubscriptionSlot(data) ?: return Skip
                    identifiers.imeiForSlot(slotIndex)
                }
                getSubscriberIdTransaction,
                getSubscriberIdWithFeatureTransaction,
                -> identifiers.imsi
                getSubscriberIdForSubscriberTransaction,
                -> identifiers.imsiForSlot(readSubscriptionSlot(data) ?: return Skip)
                getIccSerialNumberTransaction,
                getIccSerialNumberWithFeatureTransaction,
                -> identifiers.iccid
                getIccSerialNumberForSubscriberTransaction,
                -> identifiers.iccidForSlot(readSubscriptionSlot(data) ?: return Skip)
                getLine1NumberTransaction,
                getMsisdnTransaction,
                -> identifiers.phoneNumber
                getLine1NumberForSubscriberTransaction,
                getMsisdnForSubscriberTransaction,
                -> identifiers.phoneNumberForSlot(readSubscriptionSlot(data) ?: return Skip)
                getMeidForSubscriberTransaction ->
                    identifiers.meidForSlot(readSubscriptionSlot(data) ?: return Skip)
                else -> null
            }

        if (spoofedVal != null) {
            val p = Parcel.obtain()
            p.writeNoException()
            p.writeString(spoofedVal)
            return OverrideReply(0, p)
        }

        return Skip
    }

    private fun readSubscriptionSlot(data: Parcel): Int? {
        val subscriptionId = readLeadingIntArgument(data) ?: return null
        return try {
            SubscriptionManager.getSlotIndex(subscriptionId).takeIf { it >= 0 }
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun readLeadingIntArgument(data: Parcel): Int? {
        val originalPosition = data.dataPosition()
        return try {
            data.setDataPosition(0)
            data.enforceInterface(PHONE_SUB_INFO_DESCRIPTOR)
            if (data.dataAvail() < Int.SIZE_BYTES) null else data.readInt()
        } catch (error: RuntimeException) {
            val now = SystemClock.elapsedRealtime()
            val previous = lastMalformedRequestLogMs.get()
            if (
                now - previous >= MALFORMED_REQUEST_LOG_INTERVAL_MS &&
                lastMalformedRequestLogMs.compareAndSet(previous, now)
            ) {
                Logger.w("Telephony request did not match the expected AIDL layout")
            }
            null
        } finally {
            data.setDataPosition(originalPosition)
        }
    }

    @Volatile
    private var cachedPhonePid: Int? = null

    private fun findPhoneProcessPid(): Int? {
        val cachedPid = cachedPhonePid
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
                    if (argv0 == "com.android.phone") {
                        return cachedPid
                    }
                }
            } catch (e: Exception) {
                // Ignore file read errors
            }
            cachedPhonePid = null
        }

        val proc = File("/proc")
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
                        if (argv0 == "com.android.phone") {
                            val pid = pidStr.toInt()
                            cachedPhonePid = pid
                            return pid
                        }
                    }
                } catch (e: Exception) {
                    // Ignore file read errors
                }
            }
        }
        return null
    }

    @Synchronized
    fun tryRunTelephonyInterceptor(): Boolean {
        if (!Config.isSpoofEnabled || !Config.isTelephonyEnabled) {
            stopTelephonyInterceptor()
            return true
        }
        if (registered && ::iphonesubinfo.isInitialized && iphonesubinfo.isBinderAlive) return true
        registered = false
        Logger.d("trying to register telephony interceptor (${triedCount.get()}) ...")

        val b = ServiceManager.getService("iphonesubinfo")
        if (b == null) {
            Logger.e("iphonesubinfo service not found")
            triedCount.incrementAndGet()
            return false
        }

        val bd = getBinderControlEndpoint(b)
        if (bd == null) {
            val pid = findPhoneProcessPid()
            if (pid == null) {
                Logger.e("Telephony: failed to find com.android.phone pid!")
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
            Logger.i("Telephony: trying to activate the Binder hook ...")
            try {
                val modulePath = getModuleDir()
                val p =
                    ProcessBuilder(
                        "$modulePath/inject",
                        pid.toString(),
                        "$modulePath/libcleverestricky.so",
                        symbol,
                    ).redirectOutput(java.io.File("/dev/null"))
                        .redirectError(java.io.File("/dev/null"))
                        .start()

                val completed = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                if (!completed) {
                    Logger.e("Telephony: inject timed out after 30s, killing process")
                    p.destroyForcibly()
                } else if (p.exitValue() != 0) {
                    Logger.e("Telephony: failed to activate Binder hook (exit=${p.exitValue()})")
                } else {
                    Logger.i("Telephony: Binder hook activated successfully")
                    injected = true
                    injectedPid = pid
                }
            } catch (error: Exception) {
                Logger.e("Telephony: injector failed", error)
            }
            triedCount.incrementAndGet()
            return false
        }

        if (!Config.isSpoofEnabled || !Config.isTelephonyEnabled) {
            parkBinderHook(bd)
            return true
        }

        iphonesubinfo = b
        binderBackdoor = bd
        if (!registerBinderInterceptor(bd, b, this, interceptedCodes)) {
            Logger.e("Telephony: native Binder registration failed")
            parkBinderHook(bd)
            triedCount.incrementAndGet()
            return false
        }
        Logger.i("Telephony Binder interceptor registered")
        try {
            iphonesubinfo.linkToDeath(phoneDeathRecipient, 0)
            deathRecipientLinked = true
        } catch (_: android.os.RemoteException) {
            Logger.w("Phone subscription service exited before lifecycle monitoring was attached")
            stopTelephonyInterceptor()
            return false
        }
        registered = true
        if (!Config.isSpoofEnabled || !Config.isTelephonyEnabled) {
            stopTelephonyInterceptor()
            return true
        }
        triedCount.set(0)

        return true
    }

    fun isRunning(): Boolean = registered && ::iphonesubinfo.isInitialized && iphonesubinfo.isBinderAlive

    @Synchronized
    fun stopTelephonyInterceptor(): Boolean {
        val control = binderBackdoor
        val success =
            if (registered && control != null && ::iphonesubinfo.isInitialized) {
                unregisterBinderInterceptor(control, iphonesubinfo, this)
            } else {
                !registered
            }
        if (deathRecipientLinked && ::iphonesubinfo.isInitialized) {
            try {
                iphonesubinfo.unlinkToDeath(phoneDeathRecipient, 0)
            } catch (_: java.util.NoSuchElementException) {
                // The Binder driver already removed the recipient after death.
            }
            deathRecipientLinked = false
        }
        registered = false
        binderBackdoor = null
        return success
    }

    override fun onInterceptorReplaced() {
        if (deathRecipientLinked && ::iphonesubinfo.isInitialized) {
            try {
                iphonesubinfo.unlinkToDeath(phoneDeathRecipient, 0)
            } catch (_: java.util.NoSuchElementException) {
                // The Binder driver already removed the recipient after death.
            }
        }
        deathRecipientLinked = false
        registered = false
        binderBackdoor = null
        Config.signalRuntimeController()
    }
}
