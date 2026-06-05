package cleveres.tricky.cleverestech

import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import cleveres.tricky.cleverestech.binder.BinderInterceptor
import com.android.internal.telephony.IPhoneSubInfo
import java.io.File

object TelephonyInterceptor : BinderInterceptor() {

    private val getDeviceIdTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getDeviceId")
    private val getDeviceIdForPhoneTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getDeviceIdForPhone")
    private val getImeiForSubscriberTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getImeiForSubscriber")

    private val getSubscriberIdTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getSubscriberId")
    private val getSubscriberIdForSubscriberTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getSubscriberIdForSubscriber")

    private val getIccSerialNumberTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getIccSerialNumber")
    private val getIccSerialNumberForSubscriberTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getIccSerialNumberForSubscriber")

    private val getLine1NumberTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getLine1Number")
    private val getLine1NumberForSubscriberTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getLine1NumberForSubscriber")

    private val getMeidForSubscriberTransaction = getTransactCode(IPhoneSubInfo.Stub::class.java, "getMeidForSubscriber")

    private lateinit var iphonesubinfo: IBinder
    @Volatile private var triedCount = 0
    @Volatile private var injected = false

    private val secureRandom = java.security.SecureRandom()

    private val fallbackImei by lazy { generateFallbackImei() }
    private val fallbackImei2 by lazy { generateFallbackImei() }
    private val fallbackImsi by lazy { generateFallbackImsi() }
    private val fallbackIccid by lazy { generateFallbackIccid() }

    private fun generateFallbackImei(): String {
        val sb = StringBuilder()
        sb.append("35")
        for (i in 0 until 12) sb.append(secureRandom.nextInt(10))
        val digits = sb.toString()
        var sum = 0
        for (i in digits.indices) {
            var d = digits[i] - '0'
            if (i % 2 != 0) d *= 2
            sum += d / 10 + d % 10
        }
        val checkDigit = (10 - sum % 10) % 10
        return digits + checkDigit
    }

    private fun generateFallbackImsi(): String {
        val sb = StringBuilder()
        sb.append("310")
        sb.append("260")
        for (i in 0 until 9) sb.append(secureRandom.nextInt(10))
        return sb.toString()
    }

    private fun generateFallbackIccid(): String {
        val sb = StringBuilder()
        sb.append("8901")
        for (i in 0 until 15) sb.append(secureRandom.nextInt(10))
        return sb.toString()
    }

    override fun onPostTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
        reply: Parcel?,
        resultCode: Int
    ): Result {
        if (reply == null) return Skip
        if (!Config.needHack(callingUid)) return Skip

        val pos = reply.dataPosition()
        if (kotlin.runCatching { reply.readException() }.exceptionOrNull() != null) {
            reply.setDataPosition(pos)
            return Skip
        }

        var spoofedVal: String? = null

        when (code) {
            getDeviceIdTransaction -> spoofedVal = Config.getBuildVar("ATTESTATION_ID_IMEI") ?: fallbackImei
            getDeviceIdForPhoneTransaction -> {
                // Read phone index from the original request data to support dual-SIM
                val dataPos = data.dataPosition()
                try {
                    data.setDataPosition(0)
                    data.readString() // skip interface token
                    val phoneId = data.readInt()
                    spoofedVal = if (phoneId > 0) {
                        Config.getBuildVar("ATTESTATION_ID_IMEI2") ?: fallbackImei2
                    } else {
                        Config.getBuildVar("ATTESTATION_ID_IMEI") ?: fallbackImei
                    }
                    Logger.i("Telephony: getDeviceIdForPhone phoneId=$phoneId -> ${if (phoneId > 0) "IMEI2" else "IMEI1"}")
                } catch (e: Exception) {
                    spoofedVal = Config.getBuildVar("ATTESTATION_ID_IMEI") ?: fallbackImei
                } finally {
                    data.setDataPosition(dataPos)
                }
            }
            getImeiForSubscriberTransaction -> {
                // Read subscription ID from the original request data
                val dataPos = data.dataPosition()
                try {
                    data.setDataPosition(0)
                    data.readString() // skip interface token
                    val subId = data.readInt()
                    spoofedVal = if (subId > 0) {
                        Config.getBuildVar("ATTESTATION_ID_IMEI2") ?: fallbackImei2
                    } else {
                        Config.getBuildVar("ATTESTATION_ID_IMEI") ?: fallbackImei
                    }
                    Logger.i("Telephony: getImeiForSubscriber subId=$subId -> ${if (subId > 0) "IMEI2" else "IMEI1"}")
                } catch (e: Exception) {
                    spoofedVal = Config.getBuildVar("ATTESTATION_ID_IMEI") ?: fallbackImei
                } finally {
                    data.setDataPosition(dataPos)
                }
            }
            getSubscriberIdTransaction, getSubscriberIdForSubscriberTransaction -> spoofedVal = Config.getBuildVar("ATTESTATION_ID_IMSI") ?: fallbackImsi
            getIccSerialNumberTransaction, getIccSerialNumberForSubscriberTransaction -> spoofedVal = Config.getBuildVar("ATTESTATION_ID_ICCID") ?: fallbackIccid
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
            Logger.i("Intercepted Telephony: code=$code uid=$callingUid pid=$callingPid -> Spoofed value (len=${spoofedVal.length})")
            val p = Parcel.obtain()
            p.writeNoException()
            p.writeString(spoofedVal)
            return OverrideReply(0, p)
        }

        return Skip
    }

    @Volatile private var cachedPhonePid: Int? = null

    private fun findPhoneProcessPid(): Int? {
        val cachedPid = cachedPhonePid
        if (cachedPid != null) {
            val buf = ByteArray(1024)
            kotlin.runCatching {
                val stream = java.io.FileInputStream("/proc/$cachedPid/cmdline")
                val length = try {
                    stream.read(buf)
                } finally {
                    stream.close()
                }
                if (length <= 0) return@runCatching
                var end = 0
                while (end < length && buf[end] != 0.toByte()) {
                    end++
                }
                val argv0 = String(buf, 0, end)
                if (argv0 == "com.android.phone") {
                    return cachedPid
                }
            }
            cachedPhonePid = null
        }

        val proc = File("/proc")
        if (!proc.exists() || !proc.isDirectory) return null

        val pids = proc.list() ?: return null
        val buf = ByteArray(1024)
        for (pidStr in pids) {
            val c = pidStr[0]
            if (c in '1'..'9') {
                kotlin.runCatching {
                    val stream = java.io.FileInputStream("/proc/$pidStr/cmdline")
                    val length = try {
                        stream.read(buf)
                    } finally {
                        stream.close()
                    }
                    if (length <= 0) return@runCatching
                    var end = 0
                    while (end < length && buf[end] != 0.toByte()) {
                        end++
                    }
                    val argv0 = String(buf, 0, end)
                    if (argv0 == "com.android.phone") {
                        val pid = pidStr.toInt()
                        cachedPhonePid = pid
                        return pid
                    }
                }
            }
        }
        return null
    }

    private fun getModulePath(): String {
        return "/data/adb/modules/cleverestricky"
    }

    fun tryRunTelephonyInterceptor(): Boolean {
        Logger.i("trying to register telephony interceptor ($triedCount) ...")

        val b = ServiceManager.getService("iphonesubinfo")
        if (b == null) {
            Logger.e("iphonesubinfo service not found")
            triedCount += 1
            return false
        }

        val bd = getBinderBackdoor(b)
        if (bd == null) {
             if (triedCount >= 3) {
                Logger.e("Telephony: tried injection but still has no backdoor, skipping")
                return false
            }

            if (!injected) {
                Logger.i("Telephony: trying to inject com.android.phone ...")
                val pid = findPhoneProcessPid()
                if (pid == null) {
                    Logger.e("Telephony: failed to find com.android.phone pid!")
                    triedCount += 1
                    return false
                }

                val modulePath = getModulePath()
                val p = Runtime.getRuntime().exec(
                    arrayOf(
                        "$modulePath/inject",
                        pid.toString(),
                        "$modulePath/libcleverestricky.so",
                        "entry"
                    )
                )
                try {
                    p.inputStream.readBytes()
                } catch (_: Exception) {}
                finally {
                    try { p.errorStream.readBytes() } catch (_: Exception) {}
                }
                if (p.waitFor() != 0) {
                    Logger.e("Telephony: failed to inject!")
                } else {
                    Logger.i("Telephony: injected successfully")
                    injected = true
                }
            }
            triedCount += 1
            return false
        }

        iphonesubinfo = b
        Logger.i("register for iphonesubinfo!")
        registerBinderInterceptor(bd, b, this)
        iphonesubinfo.linkToDeath({
             Logger.e("iphonesubinfo died! Resetting injection state.")
             injected = false
             triedCount = 0
        }, 0)

        return true
    }
}
