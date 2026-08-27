package cleveres.tricky.cleverestech

import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.os.SystemClock
import cleveres.tricky.cleverestech.binder.BinderInterceptor
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/**
 * Small insertion-ordered PID history used only while the DRM reconciliation lock is held.
 * Failed injection attempts can observe a new service PID on every restart, so this state must not
 * grow with the lifetime of the Android service process.
 */
internal class BoundedPidHistory<V>(
    private val capacity: Int,
) {
    private val entries = LinkedHashMap<Int, V>()

    init {
        require(capacity > 0) { "PID history capacity must be positive" }
    }

    val size: Int
        get() = entries.size

    operator fun get(pid: Int): V? = entries[pid]

    fun containsKey(pid: Int): Boolean = entries.containsKey(pid)

    fun put(pid: Int, value: V) {
        entries.remove(pid)
        entries[pid] = value
        if (entries.size > capacity) {
            entries.entries.iterator().let { iterator ->
                iterator.next()
                iterator.remove()
            }
        }
    }

    fun remove(pid: Int) {
        entries.remove(pid)
    }

    fun clear() {
        entries.clear()
    }
}

/**
 * Privacy-only hook for the modern stable-AIDL DRM HAL.
 *
 * The hook changes only IDrmPlugin.getPropertyByteArray("deviceUniqueId") for
 * applications explicitly configured with privacy=isolate. License exchange,
 * provisioning, content keys, session security level and every string property
 * remain on Android's genuine DRM path.
 */
object DrmInterceptor {
    private const val DRM_FACTORY_DESCRIPTOR = "android.hardware.drm.IDrmFactory"
    private const val DRM_PLUGIN_DESCRIPTOR = "android.hardware.drm.IDrmPlugin"
    private const val DRM_FACTORY_PREFIX = "$DRM_FACTORY_DESCRIPTOR/"
    private const val DEVICE_UNIQUE_ID = "deviceUniqueId"

    // android.hardware.drm is a frozen stable-AIDL interface. createDrmPlugin
    // is the first IDrmFactory method; getPropertyByteArray is the eleventh
    // IDrmPlugin method in the frozen API. Reflection is preferred when a Java
    // Stub is present, and these values are the wire-compatible fallback.
    private val createDrmPluginTransaction =
        resolveTransactionCode(
            "android.hardware.drm.IDrmFactory\$Stub",
            "createDrmPlugin",
            IBinder.FIRST_CALL_TRANSACTION,
        )
    private val getPropertyByteArrayTransaction =
        resolveTransactionCode(
            "android.hardware.drm.IDrmPlugin\$Stub",
            "getPropertyByteArray",
            IBinder.FIRST_CALL_TRANSACTION + 10,
        )

    private const val INJECTION_RETRY_INTERVAL_MS = 15_000L
    private const val RECONCILE_INTERVAL_MS = 30_000L
    private const val INJECT_TIMEOUT_SECONDS = 30L
    private const val PID_LOOKUP_TIMEOUT_SECONDS = 2L
    private const val MAX_PID_OUTPUT_BYTES = 32
    private const val MAX_FACTORY_SERVICES = 16
    private const val MAX_PLUGIN_BINDERS = 256
    private const val MAX_TRACKED_INJECTION_PIDS = 64

    private data class FactoryRegistration(
        val name: String,
        val binder: IBinder,
        val pid: Int,
        val control: IBinder,
        val interceptor: FactoryInterceptor,
        val deathRecipient: IBinder.DeathRecipient,
    )

    private data class PluginRegistration(
        val owner: String,
        val binder: IBinder,
        val control: IBinder,
    )

    private val factories = LinkedHashMap<String, FactoryRegistration>()
    private val plugins = LinkedHashMap<IBinder, PluginRegistration>()
    private val injectedPids = BoundedPidHistory<Unit>(MAX_TRACKED_INJECTION_PIDS)
    private val lastInjectionAttempt = BoundedPidHistory<Long>(MAX_TRACKED_INJECTION_PIDS)
    private val pluginInterceptor = PluginInterceptor()

    @Volatile
    private var lastReconcileMs = 0L

    @Volatile
    private var lastReconcileHealthy = false

    fun isRunning(): Boolean {
        if (!lastReconcileHealthy) return false
        val age = SystemClock.elapsedRealtime() - lastReconcileMs
        if (age !in 0 until RECONCILE_INTERVAL_MS) return false
        return synchronized(this) { factories.values.all { it.binder.isBinderAlive } }
    }

    /**
     * Reconciles currently running stable-AIDL DRM factories. A missing factory
     * is not an error: devices may expose only a legacy HIDL implementation, and
     * a lazy AIDL HAL is discovered on the next scan after Android starts it.
     */
    @Synchronized
    fun tryRunDrmInterceptor(): Boolean {
        pruneDeadPluginsLocked()
        pruneDeadFactoriesLocked()

        val serviceNames = discoverFactoryServices()
        if (serviceNames.isEmpty()) {
            markHealthy()
            return true
        }

        var needsFastRetry = false
        for (name in serviceNames) {
            val existing = factories[name]
            if (existing != null && existing.binder.isBinderAlive) continue

            val service = ServiceManager.checkService(name) ?: ServiceManager.getService(name) ?: continue
            var control = BinderInterceptor.getBinderControlEndpoint(service)
            var pid = 0
            if (control == null) {
                pid = findServicePid(name) ?: 0
                if (pid <= 0) {
                    Logger.d("DRM privacy: PID unavailable for $name; will rescan")
                    continue
                }
                when (injectIfDue(pid)) {
                    InjectionResult.SUCCESS -> {
                        control = BinderInterceptor.getBinderControlEndpoint(service)
                        if (control == null) {
                            needsFastRetry = true
                            continue
                        }
                    }
                    InjectionResult.DEFERRED -> continue
                    InjectionResult.FAILED -> continue
                }
            }

            val interceptor = FactoryInterceptor(name)
            val resolvedControl = requireNotNull(control)
            if (
                !BinderInterceptor.registerBinderInterceptor(
                    resolvedControl,
                    service,
                    interceptor,
                    intArrayOf(createDrmPluginTransaction),
                )
            ) {
                Logger.w("DRM privacy: failed to register factory interceptor for $name")
                continue
            }

            val deathRecipient = IBinder.DeathRecipient { onFactoryDied(name, service) }
            try {
                service.linkToDeath(deathRecipient, 0)
            } catch (_: android.os.RemoteException) {
                BinderInterceptor.unregisterBinderInterceptor(resolvedControl, service, interceptor)
                needsFastRetry = true
                continue
            }

            factories[name] =
                FactoryRegistration(
                    name = name,
                    binder = service,
                    pid = pid,
                    control = resolvedControl,
                    interceptor = interceptor,
                    deathRecipient = deathRecipient,
                )
            Logger.i("DRM privacy: stable-AIDL factory hook registered for $name")
        }

        markHealthy()
        return !needsFastRetry
    }

    @Synchronized
    fun stopDrmInterceptor(): Boolean {
        var success = true

        val pluginSnapshot = plugins.values.toList()
        plugins.clear()
        for (registration in pluginSnapshot) {
            if (registration.binder.isBinderAlive) {
                success =
                    BinderInterceptor.unregisterBinderInterceptor(
                        registration.control,
                        registration.binder,
                        pluginInterceptor,
                    ) && success
            }
        }

        val factorySnapshot = factories.values.toList()
        factories.clear()
        for (registration in factorySnapshot) {
            if (registration.binder.isBinderAlive) {
                success =
                    BinderInterceptor.unregisterBinderInterceptor(
                        registration.control,
                        registration.binder,
                        registration.interceptor,
                    ) && success
                runCatching { registration.binder.unlinkToDeath(registration.deathRecipient, 0) }
            }
        }

        factorySnapshot.map { it.control }.distinct().forEach { control ->
            success = BinderInterceptor.parkBinderHook(control) && success
        }

        injectedPids.clear()
        lastInjectionAttempt.clear()
        lastReconcileHealthy = false
        lastReconcileMs = 0L
        return success
    }

    @Synchronized
    private fun registerPlugin(
        owner: String,
        plugin: IBinder,
    ) {
        if (plugins.containsKey(plugin)) return
        pruneDeadPluginsLocked()
        if (plugins.size >= MAX_PLUGIN_BINDERS) {
            Logger.w("DRM privacy: plugin registration limit reached")
            return
        }

        val factory = factories[owner] ?: return
        if (
            BinderInterceptor.registerBinderInterceptor(
                factory.control,
                plugin,
                pluginInterceptor,
                intArrayOf(getPropertyByteArrayTransaction),
            )
        ) {
            plugins[plugin] = PluginRegistration(owner, plugin, factory.control)
            Logger.d("DRM privacy: plugin hook registered")
        } else {
            Logger.w("DRM privacy: failed to register plugin interceptor")
        }
    }

    @Synchronized
    private fun onFactoryDied(
        name: String,
        expectedBinder: IBinder,
    ) {
        val registration = factories[name]
        if (registration == null || registration.binder !== expectedBinder) return
        factories.remove(name)
        if (registration.pid > 0) injectedPids.remove(registration.pid)
        removePluginsForOwnerLocked(name)
        lastReconcileHealthy = false
        lastReconcileMs = 0L
        Config.signalRuntimeController()
        Logger.i("DRM privacy: factory restarted; hook reconciliation requested")
    }

    private fun removePluginsForOwnerLocked(owner: String) {
        val iterator = plugins.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.owner == owner) iterator.remove()
        }
    }

    private fun pruneDeadPluginsLocked() {
        val iterator = plugins.entries.iterator()
        while (iterator.hasNext()) {
            if (!iterator.next().key.isBinderAlive) iterator.remove()
        }
    }

    private fun pruneDeadFactoriesLocked() {
        val iterator = factories.entries.iterator()
        while (iterator.hasNext()) {
            val registration = iterator.next().value
            if (!registration.binder.isBinderAlive) {
                iterator.remove()
                if (registration.pid > 0) injectedPids.remove(registration.pid)
                removePluginsForOwnerLocked(registration.name)
            }
        }
    }

    private enum class InjectionResult {
        SUCCESS,
        DEFERRED,
        FAILED,
    }

    private fun injectIfDue(pid: Int): InjectionResult {
        val now = SystemClock.elapsedRealtime()
        val previous = lastInjectionAttempt[pid]
        if (previous != null && now - previous in 0 until INJECTION_RETRY_INTERVAL_MS) {
            return InjectionResult.DEFERRED
        }
        lastInjectionAttempt.put(pid, now)

        val symbol = if (injectedPids.containsKey(pid)) "resume" else "entry"
        val modulePath = getModuleDir()
        return try {
            val process =
                ProcessBuilder(
                    "$modulePath/inject",
                    pid.toString(),
                    "$modulePath/libcleverestricky.so",
                    symbol,
                ).redirectOutput(File("/dev/null"))
                    .redirectError(File("/dev/null"))
                    .start()
            val completed = process.waitFor(INJECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                Logger.w("DRM privacy: injector timed out for pid=$pid")
                InjectionResult.FAILED
            } else if (process.exitValue() != 0) {
                Logger.w("DRM privacy: injector failed for pid=$pid (exit=${process.exitValue()})")
                InjectionResult.FAILED
            } else {
                injectedPids.put(pid, Unit)
                Logger.i("DRM privacy: native Binder hook activated for DRM HAL pid=$pid")
                InjectionResult.SUCCESS
            }
        } catch (error: Exception) {
            Logger.e("DRM privacy: failed to run injector for pid=$pid", error)
            InjectionResult.FAILED
        }
    }

    private fun discoverFactoryServices(): List<String> =
        runCatching { ServiceManager.listServices() }
            .getOrNull()
            ?.asSequence()
            ?.filter { it.startsWith(DRM_FACTORY_PREFIX) && isSafeFactoryName(it) }
            ?.sorted()
            ?.take(MAX_FACTORY_SERVICES)
            ?.toList()
            ?: emptyList()

    private fun isSafeFactoryName(name: String): Boolean {
        if (!name.startsWith(DRM_FACTORY_PREFIX) || name.length > 192) return false
        val instance = name.substring(DRM_FACTORY_PREFIX.length)
        return instance.isNotEmpty() &&
            instance.all { character ->
                character.isLetterOrDigit() || character == '_' || character == '-' || character == '.'
            }
    }

    private fun findServicePid(serviceName: String): Int? {
        if (!isSafeFactoryName(serviceName)) return null
        val process =
            try {
                ProcessBuilder("/system/bin/dumpsys", "--pid", serviceName)
                    .redirectError(File("/dev/null"))
                    .start()
            } catch (error: Exception) {
                Logger.d("DRM privacy: dumpsys PID lookup unavailable (${error.javaClass.simpleName})")
                return null
            }

        val output = ByteArray(MAX_PID_OUTPUT_BYTES)
        return try {
            if (!process.waitFor(PID_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null

            var total = 0
            process.inputStream.use { input ->
                while (total < output.size) {
                    val count = input.read(output, total, output.size - total)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                }
                if (total == output.size && input.read() >= 0) return null
            }
            output.copyOf(total).toString(Charsets.UTF_8).trim().toIntOrNull()?.takeIf { it > 0 }
        } catch (error: Exception) {
            Logger.d("DRM privacy: PID lookup failed (${error.javaClass.simpleName})")
            null
        } finally {
            output.fill(0)
            process.destroy()
        }
    }

    private fun markHealthy() {
        lastReconcileMs = SystemClock.elapsedRealtime()
        lastReconcileHealthy = true
    }

    private class FactoryInterceptor(
        private val owner: String,
    ) : BinderInterceptor() {
        override fun onPreTransact(
            target: IBinder,
            code: Int,
            flags: Int,
            callingUid: Int,
            callingPid: Int,
            data: Parcel,
        ): Result = if (code == createDrmPluginTransaction) Continue else Skip

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
            if (code != createDrmPluginTransaction || reply == null || resultCode != 0) return Skip
            val originalPosition = reply.dataPosition()
            try {
                reply.readException()
                val plugin = reply.readStrongBinder() ?: return Skip
                registerPlugin(owner, plugin)
            } catch (_: RuntimeException) {
                // A vendor that is not wire-compatible with the frozen AIDL
                // shape is left completely untouched.
            } finally {
                reply.setDataPosition(originalPosition)
            }
            return Skip
        }
    }

    private class PluginInterceptor : BinderInterceptor() {
        override fun onPreTransact(
            target: IBinder,
            code: Int,
            flags: Int,
            callingUid: Int,
            callingPid: Int,
            data: Parcel,
        ): Result {
            if (code != getPropertyByteArrayTransaction || !shouldProtectUid(callingUid)) return Skip
            return if (readPropertyName(data) == DEVICE_UNIQUE_ID) Continue else Skip
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
                code != getPropertyByteArrayTransaction ||
                reply == null ||
                resultCode != 0 ||
                !shouldProtectUid(callingUid) ||
                readPropertyName(data) != DEVICE_UNIQUE_ID
            ) {
                return Skip
            }

            val originalPosition = reply.dataPosition()
            var original: ByteArray? = null
            var pseudonym: ByteArray? = null
            return try {
                reply.readException()
                original = reply.createByteArray() ?: return Skip
                val length = original.size
                if (length !in DrmPrivacyIdentity.MIN_IDENTIFIER_BYTES..DrmPrivacyIdentity.MAX_IDENTIFIER_BYTES) {
                    return Skip
                }
                pseudonym = DrmPrivacyIdentity.idForUid(callingUid, length) ?: return Skip

                Parcel.obtain().let { replacement ->
                    replacement.writeNoException()
                    replacement.writeByteArray(pseudonym)
                    OverrideReply(0, replacement)
                }
            } catch (_: RuntimeException) {
                Skip
            } finally {
                original?.fill(0)
                pseudonym?.fill(0)
                reply.setDataPosition(originalPosition)
            }
        }

        private fun readPropertyName(data: Parcel): String? {
            val originalPosition = data.dataPosition()
            return try {
                data.enforceInterface(DRM_PLUGIN_DESCRIPTOR)
                data.readString()
            } catch (_: RuntimeException) {
                null
            } finally {
                data.setDataPosition(originalPosition)
            }
        }

        private fun shouldProtectUid(uid: Int): Boolean =
            Config.getAppPrivacyMode(uid) == Config.AppPrivacyMode.ISOLATE &&
                (PolicyState.usesV2() || Config.isSpoofEnabled) &&
                !PolicyState.drmPassthrough(uid)
    }

    private fun resolveTransactionCode(
        stubClassName: String,
        methodName: String,
        fallback: Int,
    ): Int {
        val reflected =
            runCatching {
                val stub = Class.forName(stubClassName)
                getTransactCode(stub, methodName)
            }.getOrNull()
        return reflected?.takeIf { it > 0 } ?: fallback
    }
}
