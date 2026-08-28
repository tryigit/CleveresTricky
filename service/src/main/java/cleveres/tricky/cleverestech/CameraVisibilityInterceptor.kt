package cleveres.tricky.cleverestech

import android.hardware.CameraStatus
import android.hardware.ICameraService
import android.hardware.ICameraServiceListener
import android.hardware.camera2.utils.ConcurrentCameraIdCombination
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.ServiceManager
import android.os.SystemClock
import cleveres.tricky.cleverestech.binder.BinderInterceptor
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

object CameraVisibilityInterceptor : BinderInterceptor() {
    private const val CAMERA_SERVICE_DESCRIPTOR = "android.hardware.ICameraService"
    private const val CAMERA_LISTENER_DESCRIPTOR = "android.hardware.ICameraServiceListener"
    private const val CAMERA_SERVICE_NAME = "media.camera"
    private const val CAMERA_SERVER_PROCESS = "cameraserver"
    private const val MAX_PROC_SCAN_ENTRIES = 4_096
    private const val MAX_LISTENER_PROXIES = 256
    private const val MAX_BUFFERED_CALLBACKS = 64
    private const val MAX_BUFFERED_CALLBACK_BYTES = 256 * 1024
    private const val MAX_REPLAY_CALLBACKS = 256
    private const val INJECTION_RETRY_INTERVAL_MS = 15_000L
    private const val INJECTION_TIMEOUT_SECONDS = 10L

    private val getNumberOfCamerasTransaction =
        getTransactCode(ICameraService.Stub::class.java, "getNumberOfCameras")
    private val addListenerTransaction =
        getTransactCode(ICameraService.Stub::class.java, "addListener")
    private val removeListenerTransaction =
        getTransactCode(ICameraService.Stub::class.java, "removeListener")
    private val getConcurrentCameraIdsTransaction =
        getTransactCode(ICameraService.Stub::class.java, "getConcurrentCameraIds")
    private val interceptedCodes =
        validTransactCodes(
            getNumberOfCamerasTransaction,
            addListenerTransaction,
            removeListenerTransaction,
            getConcurrentCameraIdsTransaction,
        )

    private val onStatusChangedTransaction =
        getTransactCode(ICameraServiceListener.Stub::class.java, "onStatusChanged")
    private val onPhysicalCameraStatusChangedTransaction =
        getTransactCode(ICameraServiceListener.Stub::class.java, "onPhysicalCameraStatusChanged")
    private val onTorchStatusChangedTransaction =
        getTransactCode(ICameraServiceListener.Stub::class.java, "onTorchStatusChanged")
    private val onTorchStrengthLevelChangedTransaction =
        getTransactCode(ICameraServiceListener.Stub::class.java, "onTorchStrengthLevelChanged")
    private val onCameraOpenedTransaction =
        getTransactCode(ICameraServiceListener.Stub::class.java, "onCameraOpened")
    private val onCameraOpenedInSharedModeTransaction =
        getTransactCode(ICameraServiceListener.Stub::class.java, "onCameraOpenedInSharedMode")
    private val onCameraClosedTransaction =
        getTransactCode(ICameraServiceListener.Stub::class.java, "onCameraClosed")
    private val cameraSpecificCallbackCodes =
        validTransactCodes(
            onStatusChangedTransaction,
            onPhysicalCameraStatusChangedTransaction,
            onTorchStatusChangedTransaction,
            onTorchStrengthLevelChangedTransaction,
            onCameraOpenedTransaction,
            onCameraOpenedInSharedModeTransaction,
            onCameraClosedTransaction,
        )

    private val cameraStatusDeviceIdField by lazy {
        runCatching { CameraStatus::class.java.getField("deviceId") }.getOrNull()
    }

    private lateinit var cameraService: IBinder
    private var binderBackdoor: IBinder? = null
    private val triedCount = AtomicInteger(0)
    private val listenerLock = Any()
    private val listenerProxies = LinkedHashMap<IBinder, CameraListenerProxy>()

    @Volatile
    private var registered = false

    @Volatile
    private var deathRecipientLinked = false

    @Volatile
    private var injected = false

    @Volatile
    private var injectedPid: Int? = null

    @Volatile
    private var lastInjectionAttemptMs = 0L

    @Volatile
    private var cachedCameraServerPid: Int? = null

    private val cameraDeathRecipient =
        object : IBinder.DeathRecipient {
            override fun binderDied() {
                Logger.e("Camera service exited; resetting camera visibility state")
                synchronized(listenerLock) {
                    listenerProxies.values.forEach(CameraListenerProxy::dispose)
                    listenerProxies.clear()
                }
                registered = false
                deathRecipientLinked = false
                injected = false
                injectedPid = null
                binderBackdoor = null
                cachedCameraServerPid = null
                lastInjectionAttemptMs = 0L
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
    ): Result {
        if (!registered || target !== cameraService || code !in interceptedCodes) return Skip

        return when (code) {
            getNumberOfCamerasTransaction,
            getConcurrentCameraIdsTransaction,
            -> if (Config.getVisibleCameraCount(callingUid) != null) Continue else Skip

            addListenerTransaction -> {
                val original = readListenerBinder(data) ?: return Skip
                val existing = synchronized(listenerLock) { listenerProxies[original] }
                if (existing != null && existing.ownerUid != callingUid) {
                    Logger.w("Camera listener Binder reused across different UIDs; rejecting registration")
                    return rejectListenerRegistration()
                }
                if (Config.getVisibleCameraCount(callingUid) == null) {
                    return existing?.let(::rewriteListenerRequest) ?: Skip
                }
                val proxy = existing ?: getOrCreateProxy(original, callingUid) ?: return rejectListenerRegistration()
                rewriteListenerRequest(proxy)
            }

            removeListenerTransaction -> {
                val original = readListenerBinder(data) ?: return Skip
                val proxy = synchronized(listenerLock) { listenerProxies[original] } ?: return Skip
                rewriteListenerRequest(proxy)
            }

            else -> Skip
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
        if (!registered || target !== cameraService || code !in interceptedCodes || resultCode != 0) {
            return Skip
        }

        return when (code) {
            getNumberOfCamerasTransaction -> rewriteCameraCount(callingUid, reply)
            addListenerTransaction -> rewriteListenerSnapshot(callingUid, data, reply)
            removeListenerTransaction -> finishListenerRemoval(data, reply)
            getConcurrentCameraIdsTransaction -> rewriteConcurrentCombinations(callingUid, reply)
            else -> Skip
        }
    }

    private fun rewriteCameraCount(
        callingUid: Int,
        reply: Parcel?,
    ): Result {
        reply ?: return Skip
        val limit = Config.getVisibleCameraCount(callingUid) ?: return Skip
        val position = reply.dataPosition()
        return try {
            reply.readException()
            val original = reply.readInt()
            val visible = boundedVisibleCameraCount(original, limit)
            if (visible == original) return Skip
            Parcel.obtain().also { replacement ->
                replacement.writeNoException()
                replacement.writeInt(visible)
            }.let { OverrideReply(0, it) }
        } catch (_: RuntimeException) {
            Skip
        } finally {
            reply.setDataPosition(position)
        }
    }

    private fun rewriteListenerSnapshot(
        callingUid: Int,
        request: Parcel,
        reply: Parcel?,
    ): Result {
        reply ?: return Skip
        val originalListener = readListenerBinder(request) ?: return Skip
        val proxy = synchronized(listenerLock) { listenerProxies[originalListener] } ?: return Skip
        val limit = Config.getVisibleCameraCount(callingUid)
        val position = reply.dataPosition()
        return try {
            try {
                reply.readException()
                proxy.markRemoteRegistered()
            } catch (_: RuntimeException) {
                if (!proxy.isRemoteRegistered()) removeProxy(originalListener)
                return Skip
            }

            try {
                val statuses = reply.createTypedArray(CameraStatus.CREATOR) ?: emptyArray()
                val visibleKeys = proxy.initializeSnapshot(statuses, limit)
                if (limit == null) return Skip

                val filtered =
                    statuses.filter { status ->
                        val key = cameraStatusKey(status)
                        key == null || key in visibleKeys
                    }
                if (filtered.size == statuses.size) return Skip

                Parcel.obtain().also { replacement ->
                    replacement.writeNoException()
                    replacement.writeTypedArray(filtered.toTypedArray(), 0)
                }.let { OverrideReply(0, it) }
            } catch (_: RuntimeException) {
                proxy.failOpenInitialization()
                Skip
            }
        } finally {
            reply.setDataPosition(position)
        }
    }

    private fun finishListenerRemoval(
        request: Parcel,
        reply: Parcel?,
    ): Result {
        val original = readListenerBinder(request) ?: return Skip
        if (reply != null) {
            val position = reply.dataPosition()
            try {
                reply.readException()
            } catch (_: RuntimeException) {
                return Skip
            } finally {
                reply.setDataPosition(position)
            }
        }
        val becameEmpty = removeProxy(original)
        if (becameEmpty && !Config.shouldInterceptCameraVisibility) Config.signalRuntimeController()
        return Skip
    }

    private fun rewriteConcurrentCombinations(
        callingUid: Int,
        reply: Parcel?,
    ): Result {
        reply ?: return Skip
        val visibleKeys = visibleCameraKeysForUid(callingUid) ?: return Skip
        val position = reply.dataPosition()
        return try {
            reply.readException()
            val combinations =
                reply.createTypedArray(ConcurrentCameraIdCombination.CREATOR) ?: return Skip
            val filtered =
                combinations.filter { combination ->
                    val keys = combinationCameraKeys(combination)
                    keys == null || visibleKeys.containsAll(keys)
                }
            if (filtered.size == combinations.size) return Skip
            Parcel.obtain().also { replacement ->
                replacement.writeNoException()
                replacement.writeTypedArray(filtered.toTypedArray(), 0)
            }.let { OverrideReply(0, it) }
        } catch (_: RuntimeException) {
            Skip
        } finally {
            reply.setDataPosition(position)
        }
    }

    private fun combinationCameraKeys(combination: ConcurrentCameraIdCombination): Set<CameraVisibilityKey>? {
        val raw =
            try {
                combination.getConcurrentCameraIdCombination()
            } catch (_: RuntimeException) {
                return null
            } catch (_: LinkageError) {
                return null
            }
        val result = LinkedHashSet<CameraVisibilityKey>(raw.size)
        for (entry in raw) {
            val key =
                when (entry) {
                    is String -> CameraVisibilityKey(entry)
                    is android.util.Pair<*, *> -> {
                        val cameraId = entry.first as? String ?: return null
                        val deviceId = (entry.second as? Number)?.toInt() ?: DEFAULT_CAMERA_DEVICE_ID
                        CameraVisibilityKey(cameraId, deviceId)
                    }
                    else -> return null
                }
            result += key
        }
        return result
    }

    private fun cameraStatusKey(status: CameraStatus): CameraVisibilityKey? {
        val cameraId = status.cameraId ?: return null
        val deviceId =
            try {
                cameraStatusDeviceIdField?.getInt(status) ?: DEFAULT_CAMERA_DEVICE_ID
            } catch (_: IllegalAccessException) {
                DEFAULT_CAMERA_DEVICE_ID
            } catch (_: IllegalArgumentException) {
                DEFAULT_CAMERA_DEVICE_ID
            }
        return CameraVisibilityKey(cameraId, deviceId)
    }

    private fun readListenerBinder(data: Parcel): IBinder? {
        val position = data.dataPosition()
        return try {
            data.enforceInterface(CAMERA_SERVICE_DESCRIPTOR)
            val listener = data.readStrongBinder()
            listener.takeIf { data.dataAvail() == 0 }
        } catch (_: RuntimeException) {
            null
        } finally {
            data.setDataPosition(position)
        }
    }

    private fun rewriteListenerRequest(proxy: IBinder): Result =
        Parcel.obtain().also { replacement ->
            replacement.writeInterfaceToken(CAMERA_SERVICE_DESCRIPTOR)
            replacement.writeStrongBinder(proxy)
        }.let(::OverrideData)

    private fun rejectListenerRegistration(): Result =
        Parcel.obtain().also { replacement ->
            replacement.writeException(IllegalStateException("Camera visibility listener capacity reached"))
        }.let { OverrideReply(0, it) }

    private fun getOrCreateProxy(
        original: IBinder,
        callingUid: Int,
    ): CameraListenerProxy? =
        synchronized(listenerLock) {
            listenerProxies[original]?.let { return@synchronized it }
            if (listenerProxies.size >= MAX_LISTENER_PROXIES) {
                Logger.w("Camera listener proxy limit reached; rejecting additional filtered listener")
                return@synchronized null
            }
            val proxy = CameraListenerProxy(original, callingUid)
            if (proxy.isDead()) {
                proxy.dispose()
                return@synchronized null
            }
            listenerProxies[original] = proxy
            proxy
        }

    /** Returns true when the final retained proxy was removed. */
    private fun removeProxy(original: IBinder): Boolean {
        val proxy = synchronized(listenerLock) { listenerProxies.remove(original) } ?: return false
        proxy.dispose()
        return synchronized(listenerLock) { listenerProxies.isEmpty() }
    }

    private fun visibleCameraKeysForUid(uid: Int): Set<CameraVisibilityKey>? =
        synchronized(listenerLock) {
            val matching = listenerProxies.values.filter { proxy -> proxy.ownerUid == uid && !proxy.isDead() }
            if (matching.isEmpty() || matching.any { !it.canFilterVisibility() }) return@synchronized null
            matching.flatMapTo(linkedSetOf()) { it.visibleCameraKeysSnapshot() }
        }

    private fun hasDeadProxies(): Boolean =
        synchronized(listenerLock) { listenerProxies.values.any(CameraListenerProxy::isDead) }

    private fun hasStaleProxyLimits(): Boolean =
        synchronized(listenerLock) {
            listenerProxies.values.any { proxy ->
                !proxy.isDead() && !proxy.matchesLimit(Config.getVisibleCameraCount(proxy.ownerUid))
            }
        }

    private fun refreshProxyVisibility(): Boolean {
        val proxies = synchronized(listenerLock) { listenerProxies.values.toList() }
        proxies.forEach { proxy ->
            if (!proxy.isDead()) proxy.refreshVisibility(Config.getVisibleCameraCount(proxy.ownerUid))
        }
        return cleanupDeadProxies()
    }

    private fun cleanupDeadProxies(): Boolean {
        val deadEntries =
            synchronized(listenerLock) {
                listenerProxies.entries
                    .filter { it.value.isDead() }
                    .map { it.key to it.value }
            }
        var clean = true
        deadEntries.forEach { (original, proxy) ->
            if (removeRemoteListener(proxy)) {
                synchronized(listenerLock) {
                    if (listenerProxies[original] === proxy) listenerProxies.remove(original)
                }
                proxy.dispose()
            } else {
                clean = false
            }
        }
        return clean
    }

    private fun removeRemoteListener(proxy: IBinder): Boolean {
        if (removeListenerTransaction <= 0) return false
        if (!::cameraService.isInitialized || !cameraService.isBinderAlive) return true
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CAMERA_SERVICE_DESCRIPTOR)
            data.writeStrongBinder(proxy)
            if (!cameraService.transact(removeListenerTransaction, data, reply, 0)) return false
            reply.readException()
            true
        } catch (_: RemoteException) {
            false
        } catch (_: RuntimeException) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private data class CameraCallbackEvent(
        val key: CameraVisibilityKey,
        val status: Int? = null,
        val secondaryId: String? = null,
    )

    private data class ReplayKey(
        val key: CameraVisibilityKey,
        val code: Int,
        val secondaryId: String?,
    )

    private class StoredCallback(
        val code: Int,
        val flags: Int,
        val data: Parcel,
        val size: Int,
    ) {
        fun copy(): StoredCallback? {
            val clone = Parcel.obtain()
            return try {
                clone.appendFrom(data, 0, size)
                clone.setDataPosition(0)
                StoredCallback(code, flags, clone, size)
            } catch (_: RuntimeException) {
                clone.recycle()
                null
            }
        }

        fun recycle() = data.recycle()
    }

    private class CameraListenerProxy(
        private val original: IBinder,
        val ownerUid: Int,
    ) : Binder() {
        private val stateLock = Any()
        private val ledger = CameraVisibilityLedger()
        private val initialCallbacks = ArrayDeque<StoredCallback>()
        private val replayCallbacks = LinkedHashMap<ReplayKey, StoredCallback>()
        private var initialCallbackBytes = 0
        private var initialized = false
        private var failedInitialization = false
        private var passThrough = false
        private var remoteRegistered = false
        private var activeLimit: Int? = null

        @Volatile
        private var dead = false

        private val deathRecipient =
            object : IBinder.DeathRecipient {
                override fun binderDied() {
                    dead = true
                    Config.signalRuntimeController()
                }
            }

        init {
            try {
                original.linkToDeath(deathRecipient, 0)
            } catch (_: RemoteException) {
                dead = true
            }
        }

        fun isDead(): Boolean = dead

        fun markRemoteRegistered() {
            synchronized(stateLock) { remoteRegistered = true }
        }

        fun isRemoteRegistered(): Boolean = synchronized(stateLock) { remoteRegistered }

        fun matchesLimit(limit: Int?): Boolean =
            synchronized(stateLock) { failedInitialization || (initialized && activeLimit == limit) }

        fun canFilterVisibility(): Boolean =
            synchronized(stateLock) { initialized && !failedInitialization }

        fun initializeSnapshot(
            statuses: Array<CameraStatus>,
            limit: Int?,
        ): Set<CameraVisibilityKey> {
            val entries =
                statuses.mapNotNull { status ->
                    val key = cameraStatusKey(status) ?: return@mapNotNull null
                    CameraVisibilityStatus(key, status.status)
                }
            val buffered: List<StoredCallback>
            val visible: Set<CameraVisibilityKey>
            synchronized(stateLock) {
                ledger.initialize(entries, limit)
                activeLimit = limit
                failedInitialization = false
                passThrough = limit == null
                initialized = true
                visible = ledger.visibleSnapshot()
                buffered = drainInitialCallbacksLocked()
            }
            buffered.forEach { callback ->
                try {
                    dispatchStoredCallback(callback)
                } finally {
                    callback.recycle()
                }
            }
            return visible
        }

        fun failOpenInitialization() {
            val buffered: List<StoredCallback>
            synchronized(stateLock) {
                activeLimit = null
                failedInitialization = true
                passThrough = true
                initialized = true
                buffered = drainInitialCallbacksLocked()
            }
            buffered.forEach { callback ->
                try {
                    forwardStored(callback)
                } finally {
                    callback.recycle()
                }
            }
        }

        fun refreshVisibility(limit: Int?) {
            val delta: CameraVisibilityDelta
            synchronized(stateLock) {
                if (failedInitialization) return
                if (!initialized) {
                    activeLimit = limit
                    return
                }
                delta = ledger.updateLimit(limit)
                activeLimit = limit
                if (limit != null) passThrough = false
            }
            dispatchVisibilityDelta(delta)
            if (limit == null) synchronized(stateLock) { passThrough = true }
        }

        fun visibleCameraKeysSnapshot(): Set<CameraVisibilityKey> =
            synchronized(stateLock) { ledger.visibleSnapshot() }

        fun dispose() {
            val pending: List<StoredCallback>
            synchronized(stateLock) {
                pending = initialCallbacks.toList() + replayCallbacks.values.toList()
                initialCallbacks.clear()
                replayCallbacks.clear()
                initialCallbackBytes = 0
            }
            pending.distinctBy { System.identityHashCode(it) }.forEach(StoredCallback::recycle)
            try {
                original.unlinkToDeath(deathRecipient, 0)
            } catch (_: java.util.NoSuchElementException) {
            }
        }

        public override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code !in cameraSpecificCallbackCodes) return forward(code, data, reply, flags)
            if (bufferInitialCallback(code, data, flags)) return true
            if (synchronized(stateLock) { passThrough }) return forward(code, data, reply, flags)
            return handleFilteredCallback(code, data, reply, flags)
        }

        private fun bufferInitialCallback(
            code: Int,
            data: Parcel,
            flags: Int,
        ): Boolean {
            synchronized(stateLock) {
                if (initialized || passThrough) return false
                val stored = copyCallback(code, data, flags) ?: return true
                while (
                    initialCallbacks.size >= MAX_BUFFERED_CALLBACKS ||
                    initialCallbackBytes + stored.size > MAX_BUFFERED_CALLBACK_BYTES
                ) {
                    val removed = initialCallbacks.pollFirst() ?: break
                    initialCallbackBytes -= removed.size
                    removed.recycle()
                }
                if (stored.size <= MAX_BUFFERED_CALLBACK_BYTES) {
                    initialCallbacks.addLast(stored)
                    initialCallbackBytes += stored.size
                } else {
                    stored.recycle()
                }
                return true
            }
        }

        private fun handleFilteredCallback(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            val event = parseCallback(code, data) ?: return true
            if (code == onStatusChangedTransaction) {
                val status = event.status ?: return true
                val delta: CameraVisibilityDelta
                val wasVisible: Boolean
                val visibleNow: Boolean
                synchronized(stateLock) {
                    wasVisible = ledger.isVisible(event.key)
                    delta = ledger.updateStatus(event.key, status)
                    visibleNow = ledger.isVisible(event.key)
                }
                dispatchVisibilityDelta(delta, event.key)
                return if (wasVisible || visibleNow) forward(code, data, reply, flags) else true
            }

            rememberCallback(event, code, data, flags)
            val visible = synchronized(stateLock) { ledger.isVisible(event.key) }
            return if (visible) forward(code, data, reply, flags) else true
        }

        private fun dispatchStoredCallback(callback: StoredCallback) {
            if (synchronized(stateLock) { passThrough }) {
                forwardStored(callback)
                return
            }
            handleFilteredCallback(callback.code, callback.data, null, callback.flags)
        }

        private fun dispatchVisibilityDelta(
            delta: CameraVisibilityDelta,
            currentStatusKey: CameraVisibilityKey? = null,
        ) {
            delta.hidden.filter { it != currentStatusKey }.forEach { key ->
                sendSyntheticStatus(key, CAMERA_STATUS_NOT_PRESENT)
            }
            delta.shown.filter { it.key != currentStatusKey }.forEach { shown ->
                sendSyntheticStatus(shown.key, shown.status)
                replayLatestCallbacks(shown.key)
            }
        }

        private fun rememberCallback(
            event: CameraCallbackEvent,
            code: Int,
            data: Parcel,
            flags: Int,
        ) {
            val stored = copyCallback(code, data, flags) ?: return
            synchronized(stateLock) {
                val replayKey = ReplayKey(event.key, code, event.secondaryId)
                if (replayKey !in replayCallbacks && replayCallbacks.size >= MAX_REPLAY_CALLBACKS) {
                    stored.recycle()
                    return
                }
                replayCallbacks.put(replayKey, stored)?.recycle()
            }
        }

        private fun replayLatestCallbacks(key: CameraVisibilityKey) {
            val callbacks =
                synchronized(stateLock) {
                    replayCallbacks.entries
                        .asSequence()
                        .filter { it.key.key == key }
                        .mapNotNull { it.value.copy() }
                        .toList()
                }
            callbacks.forEach { callback ->
                try {
                    forwardStored(callback)
                } finally {
                    callback.recycle()
                }
            }
        }

        private fun sendSyntheticStatus(
            key: CameraVisibilityKey,
            status: Int,
        ) {
            if (onStatusChangedTransaction <= 0 || dead) return
            val parcel = Parcel.obtain()
            try {
                parcel.writeInterfaceToken(CAMERA_LISTENER_DESCRIPTOR)
                parcel.writeInt(status)
                parcel.writeString(key.cameraId)
                if (cameraStatusDeviceIdField != null) parcel.writeInt(key.deviceId)
                if (!original.transact(onStatusChangedTransaction, parcel, null, IBinder.FLAG_ONEWAY)) {
                    dead = true
                    Config.signalRuntimeController()
                }
            } catch (_: RemoteException) {
                dead = true
                Config.signalRuntimeController()
            } catch (_: RuntimeException) {
                dead = true
                Config.signalRuntimeController()
            } finally {
                parcel.recycle()
            }
        }

        private fun parseCallback(
            code: Int,
            data: Parcel,
        ): CameraCallbackEvent? {
            val position = data.dataPosition()
            return try {
                data.setDataPosition(0)
                data.enforceInterface(CAMERA_LISTENER_DESCRIPTOR)
                when (code) {
                    onStatusChangedTransaction -> {
                        val status = data.readInt()
                        val cameraId = data.readString() ?: return null
                        CameraCallbackEvent(CameraVisibilityKey(cameraId, readDeviceId(data)), status)
                    }
                    onPhysicalCameraStatusChangedTransaction -> {
                        val status = data.readInt()
                        val cameraId = data.readString() ?: return null
                        val physicalId = data.readString()
                        CameraCallbackEvent(CameraVisibilityKey(cameraId, readDeviceId(data)), status, physicalId)
                    }
                    onTorchStatusChangedTransaction -> {
                        val status = data.readInt()
                        val cameraId = data.readString() ?: return null
                        CameraCallbackEvent(CameraVisibilityKey(cameraId, readDeviceId(data)), status)
                    }
                    onTorchStrengthLevelChangedTransaction -> {
                        val cameraId = data.readString() ?: return null
                        data.readInt()
                        CameraCallbackEvent(CameraVisibilityKey(cameraId, readDeviceId(data)))
                    }
                    onCameraOpenedTransaction -> {
                        val cameraId = data.readString() ?: return null
                        val clientPackage = data.readString()
                        CameraCallbackEvent(CameraVisibilityKey(cameraId, readDeviceId(data)), secondaryId = clientPackage)
                    }
                    onCameraOpenedInSharedModeTransaction -> {
                        val cameraId = data.readString() ?: return null
                        val clientPackage = data.readString()
                        CameraCallbackEvent(CameraVisibilityKey(cameraId, readDeviceId(data)), secondaryId = clientPackage)
                    }
                    onCameraClosedTransaction -> {
                        val cameraId = data.readString() ?: return null
                        CameraCallbackEvent(CameraVisibilityKey(cameraId, readDeviceId(data)))
                    }
                    else -> null
                }
            } catch (_: RuntimeException) {
                null
            } finally {
                data.setDataPosition(position)
            }
        }

        private fun readDeviceId(data: Parcel): Int =
            if (cameraStatusDeviceIdField != null && data.dataAvail() >= Int.SIZE_BYTES) {
                data.readInt()
            } else {
                DEFAULT_CAMERA_DEVICE_ID
            }

        private fun copyCallback(
            code: Int,
            data: Parcel,
            flags: Int,
        ): StoredCallback? {
            val size = data.dataSize()
            if (size <= 0 || size > MAX_BUFFERED_CALLBACK_BYTES) return null
            val copy = Parcel.obtain()
            return try {
                copy.appendFrom(data, 0, size)
                copy.setDataPosition(0)
                StoredCallback(code, flags, copy, size)
            } catch (_: RuntimeException) {
                copy.recycle()
                null
            }
        }

        private fun drainInitialCallbacksLocked(): List<StoredCallback> {
            val result = ArrayList<StoredCallback>(initialCallbacks.size)
            while (initialCallbacks.isNotEmpty()) result += initialCallbacks.removeFirst()
            initialCallbackBytes = 0
            return result
        }

        private fun forwardStored(callback: StoredCallback): Boolean =
            forward(callback.code, callback.data, null, callback.flags)

        private fun forward(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            val position = data.dataPosition()
            return try {
                data.setDataPosition(0)
                original.transact(code, data, reply, flags)
            } catch (_: RemoteException) {
                dead = true
                Config.signalRuntimeController()
                false
            } finally {
                data.setDataPosition(position)
            }
        }
    }

    private fun findCameraServerPid(): Int? {
        val cachedPid = cachedCameraServerPid
        if (cachedPid != null && processMatches(cachedPid, CAMERA_SERVER_PROCESS)) return cachedPid
        cachedCameraServerPid = null

        val proc = File("/proc")
        if (!proc.exists() || !proc.isDirectory) return null
        try {
            java.nio.file.Files.newDirectoryStream(proc.toPath()).use { entries ->
                var scanned = 0
                for (entry in entries) {
                    if (++scanned > MAX_PROC_SCAN_ENTRIES) break
                    val pidString = entry.fileName.toString()
                    if (pidString.isEmpty() || pidString[0] !in '1'..'9') continue
                    val pid = pidString.toIntOrNull() ?: continue
                    if (processMatches(pid, CAMERA_SERVER_PROCESS)) {
                        cachedCameraServerPid = pid
                        return pid
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun processMatches(
        pid: Int,
        expectedBasename: String,
    ): Boolean {
        val buffer = ByteArray(1024)
        return try {
            java.nio.file.Files.newInputStream(File("/proc/$pid/cmdline").toPath()).use { stream ->
                val length = stream.read(buffer)
                if (length <= 0) return@use false
                var end = 0
                var start = 0
                while (end < length && buffer[end] != 0.toByte()) {
                    if (buffer[end] == '/'.code.toByte()) start = end + 1
                    end++
                }
                String(buffer, start, end - start) == expectedBasename
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getModuleDir(): File {
        val paths =
            listOf(
                "/data/adb/modules/cleverestricky",
                "/data/adb/ksu/modules/cleverestricky",
                "/data/adb/ap/modules/cleverestricky",
            )
        return paths.asSequence().map(::File).firstOrNull { it.isDirectory }
            ?: File("/data/adb/modules/cleverestricky")
    }

    private fun activateNativeHook(pid: Int): Boolean {
        return try {
            val modulePath = getModuleDir()
            val symbol = if (injected && injectedPid == pid) "resume" else "entry"
            val process =
                ProcessBuilder(
                    "$modulePath/inject",
                    pid.toString(),
                    "$modulePath/libcleverestricky.so",
                    symbol,
                ).redirectOutput(File("/dev/null"))
                    .redirectError(File("/dev/null"))
                    .start()
            if (!process.waitFor(INJECTION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Logger.e("Camera visibility injector timed out")
                false
            } else {
                val exitCode = process.exitValue()
                if (exitCode != 0) Logger.e("Camera visibility injector failed (exit=$exitCode)")
                exitCode == 0
            }
        } catch (error: Exception) {
            Logger.e("Camera visibility injector failed", error)
            false
        }
    }

    @Synchronized
    fun tryRun(): Boolean {
        if (!Config.shouldInterceptCameraVisibility) return stop()
        if (registered && ::cameraService.isInitialized && cameraService.isBinderAlive) {
            return refreshProxyVisibility()
        }
        registered = false

        val service = ServiceManager.getService(CAMERA_SERVICE_NAME) ?: return false
        val control = getBinderControlEndpoint(service)
        if (control == null) {
            val pid = findCameraServerPid() ?: return false
            val now = SystemClock.elapsedRealtime()
            if (lastInjectionAttemptMs != 0L && now - lastInjectionAttemptMs < INJECTION_RETRY_INTERVAL_MS) {
                return false
            }
            lastInjectionAttemptMs = now
            if (activateNativeHook(pid)) {
                injected = true
                injectedPid = pid
            }
            triedCount.incrementAndGet()
            return false
        }

        if (!Config.shouldInterceptCameraVisibility) {
            parkBinderHook(control)
            return true
        }

        cameraService = service
        binderBackdoor = control
        if (!registerBinderInterceptor(control, service, this, interceptedCodes)) {
            parkBinderHook(control)
            triedCount.incrementAndGet()
            return false
        }
        registered = true
        try {
            cameraService.linkToDeath(cameraDeathRecipient, 0)
            deathRecipientLinked = true
        } catch (_: RemoteException) {
            stop()
            return false
        }
        if (!Config.shouldInterceptCameraVisibility) return stop()
        triedCount.set(0)
        Logger.i("Camera visibility interceptor registered")
        return refreshProxyVisibility()
    }

    fun isRunning(): Boolean =
        registered &&
            ::cameraService.isInitialized &&
            cameraService.isBinderAlive &&
            !hasDeadProxies() &&
            !hasStaleProxyLimits()

    fun isDraining(): Boolean =
        synchronized(listenerLock) { listenerProxies.isNotEmpty() }

    @Synchronized
    fun stop(): Boolean {
        val targetAlive = ::cameraService.isInitialized && cameraService.isBinderAlive
        if (!targetAlive) {
            synchronized(listenerLock) {
                listenerProxies.values.forEach(CameraListenerProxy::dispose)
                listenerProxies.clear()
            }
        } else {
            val proxies = synchronized(listenerLock) { listenerProxies.values.toList() }
            proxies.forEach { proxy -> if (!proxy.isDead()) proxy.refreshVisibility(null) }
            if (!cleanupDeadProxies()) return false
            if (synchronized(listenerLock) { listenerProxies.isNotEmpty() }) {
                Logger.d("Camera visibility disabled; existing listeners are draining in pass-through mode")
                return true
            }
        }

        val control = binderBackdoor ?: if (targetAlive) getBinderControlEndpoint(cameraService) else null
        var stopped = control?.let(::clearAndParkBinderHook) == true
        if (!stopped && control != null) {
            if (registered) unregisterBinderInterceptor(control, cameraService, this)
            stopped = parkBinderHook(control)
        }
        if (!targetAlive || (!registered && control == null)) stopped = true
        if (!stopped) {
            binderBackdoor = control
            return false
        }

        if (deathRecipientLinked && ::cameraService.isInitialized) {
            try {
                cameraService.unlinkToDeath(cameraDeathRecipient, 0)
            } catch (_: java.util.NoSuchElementException) {
            }
            deathRecipientLinked = false
        }
        registered = false
        binderBackdoor = null
        return true
    }

    override fun onInterceptorReplaced() {
        val proxies = synchronized(listenerLock) { listenerProxies.values.toList() }
        proxies.forEach { proxy -> if (!proxy.isDead()) proxy.refreshVisibility(null) }
        registered = false
        binderBackdoor = null
        Config.signalRuntimeController()
    }
}
