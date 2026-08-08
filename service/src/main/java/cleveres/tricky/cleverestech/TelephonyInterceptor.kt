package cleveres.tricky.cleverestech

import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import cleveres.tricky.cleverestech.binder.BinderInterceptor
import com.android.internal.telephony.IPhoneSubInfo
import java.io.File

object TelephonyInterceptor : BinderInterceptor() {
    private const val PHONE_SUB_INFO_DESCRIPTOR = "com.android.internal.telephony.IPhoneSubInfo"

    private val getDeviceIdTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getDeviceId")
    private val getDeviceIdForPhoneTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getDeviceIdForPhone")
    private val getImeiForSubscriberTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getImeiForSubscriber")

    private val getSubscriberIdTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getSubscriberId")
    private val getSubscriberIdForSubscriberTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getSubscriberIdForSubscriber")

    private val getIccSerialNumberTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getIccSerialNumber")
    private val getIccSerialNumberForSubscriberTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getIccSerialNumberForSubscriber")

    private val getLine1NumberTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getLine1Number")
    private val getLine1NumberForSubscriberTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getLine1NumberForSubscriber")

    private val getMeidForSubscriberTransaction =
        getTransactCode(IPhoneSubInfo.Stub::class.java, "getMeidForSubscriber")

    private val interceptedCodes =
        validTransactCodes(
            getDeviceIdTransaction,
            getDeviceIdForPhoneTransaction,
            getImeiForSubscriberTransaction,
            getSubscriberIdTransaction,
            getSubscriberIdForSubscriberTransaction,
            getIccSerialNumberTransaction,
            getIccSerialNumberForSubscriberTransaction,
            getLine1NumberTransaction,
            getLine1NumberForSubscriberTransaction,
            getMeidForSubscriberTransaction,
        )

    private lateinit var iphonesubinfo: IBinder
    private val triedCount = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var injected = false

    private val fallbackImei by lazy { generateFallbackImei() }
    private val fallbackImei2 by lazy { generateFallbackImei() }
    private val fallbackImsi by lazy { generateFallbackImsi() }
    private val fallbackIccid by lazy { generateFallbackIccid() }

    private fun generateFallbackImei(): String {
        return cleveres.tricky.cleverestech.util.RandomUtils.generateLuhn(15, "35")
    }

    private fun generateFallbackImsi(): String {
        return cleveres.tricky.cleverestech.util.RandomUtils.generateDigits(15, "310260")
    }

    private fun generateFallbackIccid(): String {
        return cleveres.tricky.cleverestech.util.RandomUtils.generateLuhn(20, "8901")
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

        val pos = reply.dataPosition()
        // Optimization: Replace runCatching with try-catch to avoid Result object allocation in hot path
        try {
            reply.readException()
        } catch (e: Exception) {
            reply.setDataPosition(pos)
            return Skip
        }

        var spoofedVal: String? = null

        when (code) {
            getDeviceIdTransaction -> spoofedVal = Config.getBuildVar("ATTESTATION_ID_IMEI") ?: fallbackImei
            getDeviceIdForPhoneTransaction -> {
                val phoneId = readLeadingIntArgument(data) ?: 0
                spoofedVal =
                    if (phoneId > 0) {
                        Config.getBuildVar("ATTESTATION_ID_IMEI2") ?: fallbackImei2
                    } else {
                        Config.getBuildVar("ATTESTATION_ID_IMEI") ?: fallbackImei
                    }
            }
            getImeiForSubscriberTransaction -> {
                val subscriptionId = readLeadingIntArgument(data) ?: 0
                spoofedVal =
                    if (subscriptionId > 0) {
                        Config.getBuildVar("ATTESTATION_ID_IMEI2") ?: fallbackImei2
                    } else {
                        Config.getBuildVar("ATTESTATION_ID_IMEI") ?: fallbackImei
                    }
            }
            getSubscriberIdTransaction,
            getSubscriberIdForSubscriberTransaction,
            -> spoofedVal = Config.getBuildVar("ATTESTATION_ID_IMSI") ?: fallbackImsi
            getIccSerialNumberTransaction,
            getIccSerialNumberForSubscriberTransaction,
            -> spoofedVal = Config.getBuildVar("ATTESTATION_ID_ICCID") ?: fallbackIccid
            getLine1NumberTransaction, getLine1NumberForSubscriberTransaction -> {
                val phoneNumber = Config.getBuildVar("ATTESTATION_ID_PHONE_NUMBER") ?: ""
                spoofedVal = if (phoneNumber.isNotEmpty()) phoneNumber else ""
            }
            getMeidForSubscriberTransaction -> {
                val meid = Config.getBuildVar("ATTESTATION_ID_MEID") ?: ""
                spoofedVal = if (meid.isNotEmpty()) meid else ""
            }
        }

        if (spoofedVal != null) {
            Logger.i(
                "Intercepted Telephony: code=$code uid=$callingUid pid=$callingPid " +
                    "-> Spoofed value (len=${spoofedVal.length})",
            )
            val p = Parcel.obtain()
            p.writeNoException()
            p.writeString(spoofedVal)
            return OverrideReply(0, p)
        }

        return Skip
    }

    private fun readLeadingIntArgument(data: Parcel): Int? {
        val originalPosition = data.dataPosition()
        return try {
            data.setDataPosition(0)
            data.enforceInterface(PHONE_SUB_INFO_DESCRIPTOR)
            if (data.dataAvail() < Int.SIZE_BYTES) null else data.readInt()
        } catch (error: RuntimeException) {
            Logger.e("Telephony request did not match the expected AIDL layout: ${error.javaClass.simpleName}")
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

    fun tryRunTelephonyInterceptor(): Boolean {
        Logger.i("trying to register telephony interceptor (${triedCount.get()}) ...")

        val b = ServiceManager.getService("iphonesubinfo")
        if (b == null) {
            Logger.e("iphonesubinfo service not found")
            triedCount.incrementAndGet()
            return false
        }

        val bd = getBinderControlEndpoint(b)
        if (bd == null) {
            if (triedCount.get() >= 3) {
                Logger.e("Telephony: native Binder control endpoint is unavailable after injection")
                return false
            }

            if (!injected) {
                Logger.i("Telephony: trying to inject com.android.phone ...")
                val pid = findPhoneProcessPid()
                if (pid == null) {
                    Logger.e("Telephony: failed to find com.android.phone pid!")
                    triedCount.incrementAndGet()
                    return false
                }

                val modulePath = getModuleDir()
                val p =
                    ProcessBuilder(
                        "$modulePath/inject",
                        pid.toString(),
                        "$modulePath/libcleverestricky.so",
                        "entry",
                    ).redirectOutput(java.io.File("/dev/null"))
                        .redirectError(java.io.File("/dev/null"))
                        .start()

                val completed = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                if (!completed) {
                    Logger.e("Telephony: inject timed out after 30s, killing process")
                    p.destroyForcibly()
                } else if (p.exitValue() != 0) {
                    Logger.e("Telephony: failed to inject!")
                } else {
                    Logger.i("Telephony: injected successfully")
                    injected = true
                }
            }
            triedCount.incrementAndGet()
            return false
        }

        iphonesubinfo = b
        if (!registerBinderInterceptor(bd, b, this, interceptedCodes)) {
            Logger.e("Telephony: native Binder registration failed")
            triedCount.incrementAndGet()
            return false
        }
        Logger.i("Telephony Binder interceptor registered")
        iphonesubinfo.linkToDeath(
            {
                Logger.e("iphonesubinfo died! Resetting injection state.")
                injected = false
                triedCount.set(0)
            },
            0,
        )

        return true
    }
}
