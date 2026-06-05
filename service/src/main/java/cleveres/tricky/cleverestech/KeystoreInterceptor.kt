package cleveres.tricky.cleverestech

import android.annotation.SuppressLint
import android.hardware.security.keymint.SecurityLevel
import android.hardware.security.rkp.IRemotelyProvisionedComponent
import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.system.keystore2.IKeystoreService
import android.system.keystore2.KeyDescriptor
import android.system.keystore2.KeyEntryResponse
import cleveres.tricky.cleverestech.binder.BinderInterceptor
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.keystore.Utils
import kotlin.system.exitProcess

@SuppressLint("BlockedPrivateApi")
object KeystoreInterceptor : BinderInterceptor() {
    private val getKeyEntryTransaction =
        getTransactCode(IKeystoreService.Stub::class.java, "getKeyEntry") // 2

    private lateinit var keystore: IBinder

    private var teeInterceptor: SecurityLevelInterceptor? = null
    private var strongBoxInterceptor: SecurityLevelInterceptor? = null
    private var rkpInterceptor: RkpInterceptor? = null
    private var binderBackdoor: IBinder? = null

    override fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel
    ): Result {
        if (code == getKeyEntryTransaction) {
            if (CertHack.canHack()) {
                Logger.d { "intercept pre  $target uid=$callingUid pid=$callingPid dataSz=${data.dataSize()}" }
                if (Config.needGenerate(callingUid))
                    kotlin.runCatching {
                        data.enforceInterface(IKeystoreService.DESCRIPTOR)
                        val descriptor =
                            data.readTypedObject(KeyDescriptor.CREATOR) ?: return@runCatching
                        val response =
                            SecurityLevelInterceptor.getKeyResponse(callingUid, descriptor.alias)
                            ?: return@runCatching
                        Logger.i("generate key for uid=$callingUid alias=${descriptor.alias}")
                        val p = Parcel.obtain()
                        p.writeNoException()
                        p.writeTypedObject(response, 0)
                        return OverrideReply(0, p)
                    }
                else if (Config.needHack(callingUid)) return Continue
                return Skip
            }
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
        resultCode: Int
    ): Result {
        if (target != keystore || code != getKeyEntryTransaction || reply == null) return Skip
        if (kotlin.runCatching { reply.readException() }.exceptionOrNull() != null) return Skip
        val p = Parcel.obtain()
        Logger.d { "intercept post $target uid=$callingUid pid=$callingPid dataSz=${data.dataSize()} replySz=${reply.dataSize()}" }
        try {
            val response = reply.readTypedObject(KeyEntryResponse.CREATOR)
            // Optimization: Check cache first using raw bytes to avoid expensive certificate parsing
            val leafEncoded = response?.metadata?.certificate
            var newChain = CertHack.getCachedCertificateChain(leafEncoded, callingUid)

            if (newChain == null) {
                val chain = Utils.getCertificateChain(response)
                if (chain != null) {
                    newChain = CertHack.hackCertificateChain(chain, callingUid)
                }
            }

            if (newChain != null) {
                Utils.putCertificateChain(response, newChain)
                Logger.i("hacked cert of uid=$callingUid")
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

    private var triedCount = 0
    private var injected = false

    private fun findKeystore2Pid(): Int? {
        // Optimized directory listing to prevent N+1 File allocations.
        // Process spawning (like pidof) is avoided due to high overhead.
        val proc = java.io.File("/proc")
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
                    if (argv0 == "keystore2" || argv0.endsWith("/keystore2")) {
                        return pidStr.toInt()
                    }
                }
            }
        }
        return null
    }

    fun tryRunKeystoreInterceptor(): Boolean {
        Logger.i("trying to register keystore interceptor (attempt=$triedCount) ...")
        val b = ServiceManager.getService("android.system.keystore2.IKeystoreService/default") ?: run {
            Logger.d("keystore2 service not yet available, will retry")
            return false
        }
        val bd = getBinderBackdoor(b)
        binderBackdoor = bd
        if (bd == null) {
            // no binder hook, try inject
            if (triedCount >= 3) {
                Logger.e("tried injection $triedCount times but still has no backdoor, will keep retrying without exiting daemon")
                return false
            }
            if (!injected) {
                Logger.i("trying to inject keystore (no binder backdoor found) ...")
                val pid = findKeystore2Pid()
                if (pid == null) {
                    Logger.e("failed to find keystore2 pid! will retry (attempt=$triedCount)")
                    triedCount += 1
                    return false
                }
                Logger.i("found keystore2 at pid=$pid, injecting libcleverestricky.so ...")
                val p = Runtime.getRuntime().exec(
                    arrayOf(
                        "/data/adb/modules/cleverestricky/inject",
                        pid.toString(),
                        "libcleverestricky.so",
                        "entry"
                    )
                )
                var stderr = ""
                val stdout = try {
                    p.inputStream.bufferedReader().use { it.readText().trim() }
                } finally {
                    try { stderr = p.errorStream.bufferedReader().use { it.readText().trim() } } catch (_: Exception) {}
                }
                val exitCode = p.waitFor()
                if (stdout.isNotBlank()) {
                    Logger.d("keystore injector stdout: $stdout")
                }
                if (stderr.isNotBlank()) {
                    Logger.d("keystore injector stderr: $stderr")
                }
                if (exitCode != 0) {
                    Logger.e("failed to inject keystore (exit=$exitCode, pid=$pid); possible conflict with another Zygisk/ptrace module. Will retry without exiting daemon")
                    triedCount += 1
                    return false
                }
                Logger.i("keystore injection succeeded for pid=$pid")
                injected = true
            }
            triedCount += 1
            return false
        }
        val ks = IKeystoreService.Stub.asInterface(b)
        val tee = kotlin.runCatching { ks.getSecurityLevel(SecurityLevel.TRUSTED_ENVIRONMENT) }
            .getOrNull()
        if (tee == null) {
            Config.setTeeBroken(true)
        } else {
            Config.setTeeBroken(false)
        }
        val strongBox =
            kotlin.runCatching { ks.getSecurityLevel(SecurityLevel.STRONGBOX) }.getOrNull()
        
        // Register PropertyHiderService with the native layer
        val propertyHiderService = PropertyHiderService()
        registerPropertyService(bd, propertyHiderService) // Assumes registerPropertyService is in BinderInterceptor companion

        keystore = b
        Logger.i("register for Keystore $keystore!")
        val interceptedCodes = intArrayOf(getKeyEntryTransaction)
        registerBinderInterceptor(bd, b, this, interceptedCodes)
        keystore.linkToDeath(Killer, 0)
        if (tee != null) {
            Logger.i("register for TEE SecurityLevel $tee!")
            val interceptor = SecurityLevelInterceptor(tee, SecurityLevel.TRUSTED_ENVIRONMENT)
            registerBinderInterceptor(bd, tee.asBinder(), interceptor, SecurityLevelInterceptor.INTERCEPTED_CODES)
            teeInterceptor = interceptor
        } else {
            Logger.i("no TEE SecurityLevel found!")
        }
        if (strongBox != null) {
            Logger.i("register for StrongBox SecurityLevel $strongBox!")
            val interceptor = SecurityLevelInterceptor(strongBox, SecurityLevel.STRONGBOX)
            registerBinderInterceptor(bd, strongBox.asBinder(), interceptor, SecurityLevelInterceptor.INTERCEPTED_CODES)
            strongBoxInterceptor = interceptor
        } else {
            Logger.i("no StrongBox SecurityLevel found!")
        }
        
        // Register RKP interceptor for STRONG integrity
        if (Config.shouldBypassRkp()) {
            val rkp = findRemotelyProvisionedComponent()
            if (rkp != null) {
                Logger.i("register for RemotelyProvisionedComponent!")
                val interceptor = RkpInterceptor(rkp, SecurityLevel.TRUSTED_ENVIRONMENT)
                registerBinderInterceptor(bd, rkp.asBinder(), interceptor, RkpInterceptor.INTERCEPTED_CODES)
                rkpInterceptor = interceptor
            } else {
                Logger.i("no RemotelyProvisionedComponent found (RKP bypass enabled but HAL not available)")
            }
        }
        
        return true
    }
    
    /**
     * Finds the RemotelyProvisionedComponent HAL service.
     * Required for RKP spoofing to achieve MEETS_STRONG_INTEGRITY.
     */
    private fun findRemotelyProvisionedComponent(): IRemotelyProvisionedComponent? {
        return kotlin.runCatching {
            // Try default instance first
            var b = ServiceManager.getService(
                "android.hardware.security.keymint.IRemotelyProvisionedComponent/default"
            )
            if (b == null) {
                // Try TEE instance
                b = ServiceManager.getService(
                    "android.hardware.security.keymint.IRemotelyProvisionedComponent/strongbox"
                )
            }
            if (b != null) {
                IRemotelyProvisionedComponent.Stub.asInterface(b)
            } else {
                null
            }
        }.onFailure {
            Logger.e("Failed to find RemotelyProvisionedComponent", it)
        }.getOrNull()
    }

    object Killer : IBinder.DeathRecipient {
        override fun binderDied() {
            Logger.d("keystore exit, daemon restart")
            exitProcess(0)
        }
    }
}
