package cleveres.tricky.cleverestech

import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.os.SystemClock
import cleveres.tricky.cleverestech.binder.BinderInterceptor
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object DrmInterceptor : BinderInterceptor() {
    private const val DRM_SERVICE_NAME = "media.drm"
    private const val DRM_PROCESS_NAME = "mediadrmserver"
    private const val INJECTION_RETRY_INTERVAL_MS = 15_000L

    private val createPluginTransactionCodes = createPluginCodes().toSet()
    private val directGetPropertyTransactionCodes = getPropertyCodes().toSet()
    private val interceptedCodes =
        validTransactCodes(*(createPluginTransactionCodes + directGetPropertyTransactionCodes).toIntArray())

    private lateinit var drmService: IBinder
    private val triedCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val pluginInterceptor = DrmPluginInterceptor()
    private val registeredPluginBinders =
        Collections.newSetFromMap(ConcurrentHashMap<IBinder, Boolean>())

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

    @Volatile
    private var cachedProcessPid: Int? = null

    private val drmDeathRecipient =
        object : IBinder.DeathRecipient {
            override fun binderDied() {
                Logger.e("DRM service exited; resetting interceptor state")
                registered = false
                deathRecipientLinked = false
                injected = false
                injectedPid = null
                binderBackdoor = null
                cachedProcessPid = null
                lastInjectionAttemptMs = 0L
                triedCount.set(0)
                synchronized(registeredPluginBinders) {
                    registeredPluginBinders.clear()
                }
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
            target == drmService &&
            code in interceptedCodes &&
            (code in createPluginTransactionCodes || Config.needHack(callingUid))
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
        if (target != drmService || reply == null || resultCode != 0) return Skip

        if (code in directGetPropertyTransactionCodes && Config.needHack(callingUid)) {
            val overridden = maybeOverrideSecurityLevelReply(reply)
            if (overridden != null) return overridden
        }

        if (code !in createPluginTransactionCodes) return Skip

        val pluginBinder = readPluginBinder(reply) ?: return Skip
        val control = binderBackdoor ?: return Skip
        synchronized(registeredPluginBinders) {
            if (registeredPluginBinders.contains(pluginBinder)) return Skip
            if (
                registerBinderInterceptor(
                    control,
                    pluginBinder,
                    pluginInterceptor,
                    DrmPluginInterceptor.INTERCEPTED_CODES,
                )
            ) {
                registeredPluginBinders.add(pluginBinder)
                Logger.i("DRM plugin Binder interceptor registered")
            } else {
                Logger.w("Failed to register DRM plugin interceptor")
            }
        }
        return Skip
    }

    private fun readPluginBinder(reply: Parcel): IBinder? {
        val originalPosition = reply.dataPosition()
        return try {
            reply.readException()
            reply.readStrongBinder()
        } catch (_: RuntimeException) {
            null
        } finally {
            reply.setDataPosition(originalPosition)
        }
    }

    private fun maybeOverrideSecurityLevelReply(reply: Parcel): OverrideReply? {
        val replyPosition = reply.dataPosition()
        return try {
            reply.readException()
            val original = reply.readString()?.trim() ?: return null
            if (!original.equals("L3", ignoreCase = true) && !original.equals("L2", ignoreCase = true)) {
                return null
            }
            val replacement = Parcel.obtain()
            replacement.writeNoException()
            replacement.writeString("L1")
            OverrideReply(0, replacement)
        } catch (_: RuntimeException) {
            null
        } finally {
            reply.setDataPosition(replyPosition)
        }
    }

    private fun findDrmProcessPid(): Int? {
        val cachedPid = cachedProcessPid
        if (cachedPid != null) {
            val processName = readProcessName(cachedPid)
            if (processName == DRM_PROCESS_NAME) return cachedPid
            cachedProcessPid = null
        }

        val proc = File("/proc")
        if (!proc.exists() || !proc.isDirectory) return null
        return runCatching {
            java.nio.file.Files.newDirectoryStream(proc.toPath()).use { entries ->
                entries.forEach { entry ->
                    val pidStr = entry.fileName.toString()
                    if (pidStr.isEmpty() || pidStr[0] !in '1'..'9') return@forEach
                    val pid = pidStr.toIntOrNull() ?: return@forEach
                    if (readProcessName(pid) == DRM_PROCESS_NAME) {
                        cachedProcessPid = pid
                        return pid
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun readProcessName(pid: Int): String? {
        val buf = ByteArray(1024)
        return try {
            java.io.FileInputStream("/proc/$pid/cmdline").use { stream ->
                val length = stream.read(buf)
                if (length <= 0) return null
                var end = 0
                var start = 0
                while (end < length && buf[end] != 0.toByte()) {
                    if (buf[end] == 47.toByte()) start = end + 1
                    end++
                }
                String(buf, start, end - start)
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun tryRunDrmInterceptor(): Boolean {
        if (registered && ::drmService.isInitialized && drmService.isBinderAlive) return true
        registered = false
        Logger.d("trying to register DRM interceptor (attempt=${triedCount.get()}) ...")

        val service = ServiceManager.getService(DRM_SERVICE_NAME)
        if (service == null) {
            Logger.e("DRM service not found")
            triedCount.incrementAndGet()
            return false
        }

        val control = getBinderControlEndpoint(service)
        if (control == null) {
            val pid = findDrmProcessPid()
            if (pid == null) {
                Logger.e("failed to find DRM service process pid")
                triedCount.incrementAndGet()
                return false
            }
            val now = SystemClock.elapsedRealtime()
            if (lastInjectionAttemptMs != 0L && now - lastInjectionAttemptMs < INJECTION_RETRY_INTERVAL_MS) {
                return false
            }
            lastInjectionAttemptMs = now
            val symbol = if (injected && injectedPid == pid) "resume" else "entry"
            Logger.i("trying to activate the DRM Binder hook ...")
            try {
                val modulePath = getModuleDir()
                val process =
                    ProcessBuilder(
                        "$modulePath/inject",
                        pid.toString(),
                        "$modulePath/libcleverestricky.so",
                        symbol,
                    ).redirectOutput(File("/dev/null"))
                        .redirectError(File("/dev/null"))
                        .start()

                val completed = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                if (!completed) {
                    Logger.e("DRM inject timed out after 30s, killing process")
                    process.destroyForcibly()
                } else if (process.exitValue() != 0) {
                    Logger.e("failed to activate DRM Binder hook (exit=${process.exitValue()})")
                } else {
                    Logger.i("DRM Binder hook activated successfully")
                    injected = true
                    injectedPid = pid
                }
            } catch (error: Exception) {
                Logger.e("failed to run DRM injector", error)
            }
            triedCount.incrementAndGet()
            return false
        }

        if (interceptedCodes.isEmpty()) {
            Logger.w("DRM transaction map is unavailable on this platform")
            parkBinderHook(control)
            triedCount.incrementAndGet()
            return false
        }

        drmService = service
        binderBackdoor = control
        if (!registerBinderInterceptor(control, service, this, interceptedCodes)) {
            Logger.e("native DRM Binder registration failed")
            parkBinderHook(control)
            triedCount.incrementAndGet()
            return false
        }
        registered = true
        Logger.i("DRM Binder interceptor registered")
        try {
            drmService.linkToDeath(drmDeathRecipient, 0)
            deathRecipientLinked = true
        } catch (_: android.os.RemoteException) {
            Logger.w("DRM service exited before lifecycle monitoring was attached")
            stopDrmInterceptor()
            return false
        }

        triedCount.set(0)
        return true
    }

    fun isRunning(): Boolean = registered && ::drmService.isInitialized && drmService.isBinderAlive

    @Synchronized
    fun stopDrmInterceptor(): Boolean {
        val targetAlive = ::drmService.isInitialized && drmService.isBinderAlive
        val control =
            binderBackdoor
                ?: if (targetAlive) getBinderControlEndpoint(drmService) else null
        var stopped = control?.let(::clearAndParkBinderHook) == true
        if (!stopped && control != null) {
            synchronized(registeredPluginBinders) {
                if (registered) {
                    registeredPluginBinders.forEach { binder ->
                        unregisterBinderInterceptor(control, binder, pluginInterceptor)
                    }
                    unregisterBinderInterceptor(control, drmService, this)
                }
            }
            stopped = parkBinderHook(control)
        }
        if (!targetAlive || (!registered && control == null)) stopped = true
        if (!stopped) {
            binderBackdoor = control
            Logger.d("DRM Binder hook cleanup remains pending")
            return false
        }

        if (deathRecipientLinked && ::drmService.isInitialized) {
            try {
                drmService.unlinkToDeath(drmDeathRecipient, 0)
            } catch (_: java.util.NoSuchElementException) {
            }
            deathRecipientLinked = false
        }
        synchronized(registeredPluginBinders) {
            registeredPluginBinders.clear()
        }
        registered = false
        binderBackdoor = null
        return true
    }

    override fun onInterceptorReplaced() {
        if (deathRecipientLinked && ::drmService.isInitialized) {
            try {
                drmService.unlinkToDeath(drmDeathRecipient, 0)
            } catch (_: java.util.NoSuchElementException) {
            }
        }
        synchronized(registeredPluginBinders) {
            registeredPluginBinders.clear()
        }
        deathRecipientLinked = false
        registered = false
        binderBackdoor = null
        Config.signalRuntimeController()
    }

    private class DrmPluginInterceptor : BinderInterceptor() {
        companion object {
            val INTERCEPTED_CODES = validTransactCodes(*getPropertyCodes())
        }

        override fun onPreTransact(
            target: IBinder,
            code: Int,
            flags: Int,
            callingUid: Int,
            callingPid: Int,
            data: Parcel,
        ): Result = if (code in INTERCEPTED_CODES && Config.needHack(callingUid)) Continue else Skip

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
            if (reply == null || resultCode != 0 || code !in INTERCEPTED_CODES || !Config.needHack(callingUid)) {
                return Skip
            }
            return maybeOverrideSecurityLevelReply(reply) ?: Skip
        }
    }

    private fun maybeResolveStubClass(vararg classNames: String): Class<*>? {
        classNames.forEach { name ->
            runCatching { Class.forName(name) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun readTransactionCode(
        clazz: Class<*>?,
        methodNames: List<String>,
    ): IntArray {
        if (clazz == null) return IntArray(0)
        val codes = methodNames.map { getTransactCode(clazz, it) }
        return validTransactCodes(*codes.toIntArray())
    }

    private fun createPluginCodes(): IntArray {
        val serviceStub =
            maybeResolveStubClass(
                "android.media.IMediaDrmService\$Stub",
                "android.media.IDrmManagerService\$Stub",
            )
        return readTransactionCode(
            serviceStub,
            listOf("createPlugin", "makeDrm", "createDrmPlugin"),
        )
    }

    private fun getPropertyCodes(): IntArray {
        val drmStub =
            maybeResolveStubClass(
                "android.media.IDrm\$Stub",
                "android.hardware.drm.IDrm\$Stub",
            )
        val directCodes =
            readTransactionCode(
                drmStub,
                listOf("getPropertyString"),
            )

        val serviceStub =
            maybeResolveStubClass(
                "android.media.IMediaDrmService\$Stub",
                "android.media.IDrmManagerService\$Stub",
            )
        val serviceCodes =
            readTransactionCode(
                serviceStub,
                listOf("getPropertyString"),
            )
        return validTransactCodes(*(directCodes + serviceCodes))
    }
}
