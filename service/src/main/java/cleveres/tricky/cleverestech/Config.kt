package cleveres.tricky.cleverestech

import android.content.pm.IPackageManager
import android.os.FileObserver
import android.os.ServiceManager
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.DeviceKeyManager
import cleveres.tricky.cleverestech.util.KeyboxAutoCleaner
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import cleveres.tricky.cleverestech.util.PackageTrie
import cleveres.tricky.cleverestech.util.RandomUtils
import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.readFileSnapshotBounded
import cleveres.tricky.cleverestech.util.readUtf8FileSnapshotBounded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Config {
    private const val HEX_ALPHABET = "0123456789ABCDEF"
    private const val SERIAL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runtimeControllerSignal = Semaphore(0)
    private const val MAX_UID_CACHE_ENTRIES = 4096
    private const val UID_DECISION_CACHE_TTL_MS = 5 * 1000L
    private const val FIRST_APPLICATION_UID = 10_000
    private val rkpInfrastructurePackages =
        setOf(
            "com.android.rkpd",
            "com.android.rkpdapp",
            "com.android.remoteprovisioner",
            "com.google.android.rkpd",
            "com.google.android.rkpdapp",
            "com.google.android.go.rkpd",
            "com.google.android.remoteprovisioner",
        )

    private fun <T> putBoundedUidCache(
        cache: ConcurrentHashMap<Int, T>,
        uid: Int,
        value: T,
    ) {
        if (cache.size >= MAX_UID_CACHE_ENTRIES && !cache.containsKey(uid)) cache.clear()
        cache[uid] = value
    }

    enum class AppPrivacyMode(val configValue: String) {
        INHERIT("inherit"),
        REDACT("redact"),
        ISOLATE("isolate"),
        ;

        companion object {
            fun parse(value: String): AppPrivacyMode? = entries.firstOrNull { it.configValue.equals(value, ignoreCase = true) }
        }
    }

    data class AppSpoofConfig(
        val template: String?,
        val keyboxFilename: String?,
        val privacyMode: AppPrivacyMode = AppPrivacyMode.INHERIT,
    )

    internal data class IdentityOverrides(
        val template: String? = null,
        val imei: String? = null,
        val imei2: String? = null,
        val imsi: String? = null,
        val imsi2: String? = null,
        val iccid: String? = null,
        val iccid2: String? = null,
        val meid: String? = null,
        val meid2: String? = null,
        val phoneNumber: String? = null,
        val phoneNumber2: String? = null,
        val serial: String? = null,
        val visibleSimCount: Int? = null,
        val visibleCameraCount: Int? = null,
    ) {
        private fun valueForSlot(
            primary: String?,
            secondary: String?,
            slotIndex: Int,
        ): String? =
            when (slotIndex) {
                0 -> primary
                1 -> secondary ?: primary
                else -> null
            }

        fun imeiForSlot(slotIndex: Int): String? = valueForSlot(imei, imei2, slotIndex)

        fun imsiForSlot(slotIndex: Int): String? = valueForSlot(imsi, imsi2, slotIndex)

        fun iccidForSlot(slotIndex: Int): String? = valueForSlot(iccid, iccid2, slotIndex)

        fun meidForSlot(slotIndex: Int): String? = valueForSlot(meid, meid2, slotIndex)

        fun phoneNumberForSlot(slotIndex: Int): String? = valueForSlot(phoneNumber, phoneNumber2, slotIndex)
    }

    private data class CachedDecision(val value: Boolean, val timestamp: Long)

    private data class CachedValue<T>(val value: T, val timestamp: Long)

    private class TargetState(
        val hackPackages: PackageTrie<Boolean>,
    ) {
        val hackCache = ConcurrentHashMap<Int, CachedDecision>()
    }

    @Volatile
    private var targetState = TargetState(PackageTrie())

    private class IdentityTargetState(
        val packages: PackageTrie<Boolean>,
    ) {
        val cache = ConcurrentHashMap<Int, CachedDecision>()
    }

    @Volatile
    private var identityTargetState = IdentityTargetState(PackageTrie())

    private val rkpInfrastructureCache = ConcurrentHashMap<Int, CachedDecision>()

    @Volatile
    var isGlobalMode = false
        private set

    @Volatile
    var isGlobalIdentityMode = false
        private set

    @Volatile
    var isSpoofEnabled = false
        private set

    @Volatile
    var isBuildIdentityEnabled = false
        private set

    @Volatile
    var isTeeBrokenMode = false
        private set

    @Volatile
    private var moduleHash: ByteArray? = null

    @Volatile
    var isTelephonyEnabled = false

    @Volatile
    var isCameraVisibilityEnabled = false
        private set

    @Volatile
    var isRkpPassthroughEnabled = false
        private set

    @Volatile
    var isDrmPassthroughEnabled = false
        private set

    private class DrmState(
        val packages: PackageTrie<Boolean>,
    ) {
        val cache = ConcurrentHashMap<Int, CachedDecision>()
    }

    @Volatile
    private var drmState = DrmState(PackageTrie())

    @Volatile
    private var moduleHashFromVars: ByteArray? = null

    private class AppConfigState(
        val configs: PackageTrie<AppSpoofConfig>,
        val hasPrivacyRules: Boolean = false,
    ) {
        val cache = ConcurrentHashMap<Int, CachedValue<AppSpoofConfig?>>()
        val privacyCache = ConcurrentHashMap<Int, CachedValue<AppPrivacyMode>>()
        val identityCache = ConcurrentHashMap<Int, CachedValue<IdentityOverrides>>()
    }

    @Volatile
    private var appConfigState = AppConfigState(PackageTrie())

    fun getModuleHash(): ByteArray? = moduleHash ?: moduleHashFromVars

    fun getAppConfig(uid: Int): AppSpoofConfig? {
        val state = appConfigState
        if (state.configs.isEmpty()) {
            cacheValue(state.cache, uid, null)
            return PolicyState.resolveAppConfig(uid, null)
        }
        val pkgs = getPackages(uid)
        getCachedValue(state.cache, uid)?.let { return PolicyState.resolveAppConfig(uid, it.value) }
        var result: AppSpoofConfig? = null
        val len = pkgs.size
        for (i in 0 until len) {
            val config = state.configs.get(pkgs[i])
            if (config != null) {
                result = config
                break
            }
        }
        cacheValue(state.cache, uid, result)
        return PolicyState.resolveAppConfig(uid, result)
    }

    fun getAppPrivacyMode(uid: Int): AppPrivacyMode {
        PolicyState.profilePrivacyMode(uid)?.let { return it }
        val state = appConfigState
        if (!state.hasPrivacyRules) {
            cacheValue(state.privacyCache, uid, AppPrivacyMode.INHERIT)
            return AppPrivacyMode.INHERIT
        }
        val packages = getPackages(uid)
        getCachedValue(state.privacyCache, uid)?.let { return it.value }
        var selected = AppPrivacyMode.INHERIT
        for (packageName in packages) {
            when (state.configs.get(packageName)?.privacyMode) {
                AppPrivacyMode.REDACT -> {
                    selected = AppPrivacyMode.REDACT
                    break
                }
                AppPrivacyMode.ISOLATE -> selected = AppPrivacyMode.ISOLATE
                else -> Unit
            }
        }
        cacheValue(state.privacyCache, uid, selected)
        return selected
    }

    val shouldInterceptTelephony: Boolean
        get() =
            PolicyState.isFeatureEnabled(PolicyState.Feature.TELEPHONY_IDENTITY) ||
                (isSpoofEnabled && appConfigState.hasPrivacyRules) ||
                PolicyState.hasTelephonyProfileWork()

    val shouldInterceptDrm: Boolean
        get() = (isSpoofEnabled && appConfigState.hasPrivacyRules) || PolicyState.hasDrmProfileWork()

    val shouldInterceptSubscriptionVisibility: Boolean
        get() = identityOverrides.visibleSimCount != null && shouldInterceptTelephony

    fun getVisibleSimCount(uid: Int): Int? =
        identityOverrides.visibleSimCount.takeIf { shouldApplyTelephonyPrivacy(uid) }

    val shouldInterceptCameraVisibility: Boolean
        get() = shouldRunCameraVisibility(isCameraVisibilityEnabled, identityOverrides.visibleCameraCount)

    fun getVisibleCameraCount(uid: Int): Int? =
        identityOverrides.visibleCameraCount.takeIf { isCameraVisibilityEnabled && isTargetedUid(uid) }

    fun shouldApplyTelephonyPrivacy(uid: Int): Boolean {
        val legacyPrivacy = !PolicyState.usesV2() && isSpoofEnabled && getAppPrivacyMode(uid) != AppPrivacyMode.INHERIT
        val configuredPrivacy = PolicyState.usesV2() && getAppPrivacyMode(uid) != AppPrivacyMode.INHERIT
        return (PolicyState.isFeatureEnabled(PolicyState.Feature.TELEPHONY_IDENTITY, uid) || legacyPrivacy || configuredPrivacy) &&
            isTargetedUid(uid)
    }

    internal fun updateAppConfigs(f: File?) =
        runCatching {
            val newConfigs = PackageTrie<AppSpoofConfig>()
            val seenPackages = HashSet<String>()
            var hasPrivacyRules = false
            if (f != null && Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    "app_config must be a regular file"
                }
                val text = readUtf8FileSnapshotBounded(f, 0, MAX_APP_CONFIG_BYTES)
                var ruleCount = 0
                text.lineSequence().forEach { line ->
                    if (line.isNotBlank() && !line.startsWith("#")) {
                        require(++ruleCount <= MAX_APP_CONFIG_RULES) {
                            "app_config contains too many rules"
                        }
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEach
                        val len = trimmed.length
                        var idx = 0
                        var start = idx
                        while (idx < len && !trimmed[idx].isWhitespace()) idx++
                        val pkg = trimmed.substring(start, idx)
                        var template: String? = null
                        var keybox: String? = null
                        var privacyMode = AppPrivacyMode.INHERIT
                        while (idx < len && trimmed[idx].isWhitespace()) idx++
                        if (idx < len) {
                            start = idx
                            while (idx < len && !trimmed[idx].isWhitespace()) idx++
                            val tStr = trimmed.substring(start, idx)
                            if (tStr != "null") template = tStr.lowercase()
                            while (idx < len && trimmed[idx].isWhitespace()) idx++
                            if (idx < len) {
                                start = idx
                                while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                val kStr = trimmed.substring(start, idx)
                                if (kStr != "null") keybox = kStr
                                while (idx < len && trimmed[idx].isWhitespace()) idx++
                                if (idx < len) {
                                    start = idx
                                    while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                    privacyMode =
                                        AppPrivacyMode.parse(trimmed.substring(start, idx))
                                            ?: throw IllegalArgumentException("Invalid app privacy mode")
                                }
                            }
                        }
                        while (idx < len && trimmed[idx].isWhitespace()) idx++
                        require(idx == len) { "app_config contains too many columns" }
                        require(APP_PACKAGE_PATTERN.matches(pkg)) { "app_config contains an invalid package" }
                        require(seenPackages.add(pkg)) { "app_config contains duplicate packages" }
                        require(template == null || validTemplateName.matches(template)) {
                            "app_config contains an invalid template"
                        }
                        require(keybox == null || isValidAppKeybox(keybox)) {
                            "app_config contains an invalid keybox"
                        }
                        require(template != null || keybox != null || privacyMode != AppPrivacyMode.INHERIT) {
                            "app_config contains an empty rule"
                        }
                        if (privacyMode != AppPrivacyMode.INHERIT) hasPrivacyRules = true
                        newConfigs.add(pkg, AppSpoofConfig(template, keybox, privacyMode))
                    }
                }
            }
            appConfigState = AppConfigState(newConfigs, hasPrivacyRules)
            CertHack.clearCertificateCache()
            signalRuntimeController()
            Logger.i { "update app configs: ${newConfigs.size}" }
        }.onFailure {
            Logger.e("failed to update app configs", it)
        }

    @androidx.annotation.VisibleForTesting
    internal fun setPackagesForTesting(
        uid: Int,
        packages: Array<String>,
    ) {
        putBoundedUidCache(packageCache, uid, CachedPackage(packages.clone(), System.currentTimeMillis()))
        PolicyState.invalidateUid(uid)
    }

    fun parsePackages(lines: Sequence<String>): PackageTrie<Boolean> = parsePackages(lines, Int.MAX_VALUE)

    private fun parsePackages(
        lines: Sequence<String>,
        maxRules: Int,
    ): PackageTrie<Boolean> {
        val hackPackages = PackageTrie<Boolean>()
        var ruleCount = 0
        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            require(++ruleCount <= maxRules) { "target.txt contains too many rules" }
            val packageName = trimmed.removeSuffix("!").trim()
            val valid =
                packageName.isNotEmpty() &&
                    packageName.all { character ->
                        character.isLetterOrDigit() || character == '_' || character == '.' || character == '*'
                    }
            if (valid) {
                hackPackages.add(packageName, true)
            } else {
                Logger.w("Ignoring invalid target package entry")
            }
        }
        return hackPackages
    }

    private fun updateTargetPackages(f: File?) =
        runCatching {
            if (isGlobalMode) {
                targetState = TargetState(PackageTrie())
                Logger.i("Global mode is enabled, skipping updateTargetPackages execution.")
                return@runCatching
            }
            Logger.d("updateTargetPackages: reading ${f?.absolutePath} (exists=${f?.exists()})")
            val packages =
                if (f != null && Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        "target.txt must be a regular file"
                    }
                    val text = readUtf8FileSnapshotBounded(f, 0, MAX_TARGET_FILE_BYTES)
                    parsePackages(text.lineSequence(), MAX_TARGET_PACKAGE_RULES)
                } else {
                    Logger.d("updateTargetPackages: target file missing or null, using empty package list")
                    parsePackages(emptySequence())
                }
            targetState = TargetState(packages)
            Logger.i { "Updated target packages: ${packages.size}" }
        }.onFailure {
            Logger.e("failed to update target files", it)
        }

    private fun updateIdentityTargetPackages(f: File?) =
        runCatching {
            if (isGlobalIdentityMode) {
                identityTargetState = IdentityTargetState(PackageTrie())
                Logger.i("Global Identity mode is enabled, skipping updateIdentityTargetPackages execution.")
                return@runCatching
            }
            Logger.d("updateIdentityTargetPackages: reading ${f?.absolutePath} (exists=${f?.exists()})")
            val packages =
                if (f != null && Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        "identity_target.txt must be a regular file"
                    }
                    val text = readUtf8FileSnapshotBounded(f, 0, MAX_TARGET_FILE_BYTES)
                    parsePackages(text.lineSequence(), MAX_TARGET_PACKAGE_RULES)
                } else {
                    Logger.d("updateIdentityTargetPackages: identity target file missing or null, using empty package list")
                    parsePackages(emptySequence())
                }
            identityTargetState = IdentityTargetState(packages)
            Logger.i { "Updated identity target packages: ${packages.size}" }
        }.onFailure {
            Logger.e("failed to update identity target files", it)
        }

    private data class KeyboxFileCache(
        val snapshotSha256: String,
        val keyboxes: List<CertHack.KeyBox>,
    )

    private class KeyboxAggregateLimitException(message: String) : Exception(message)

    private val storedKeyboxCache = ConcurrentHashMap<String, KeyboxFileCache>()
    private val fullSha256Pattern = Regex("[0-9a-f]{64}")
    private const val KEYBOX_REFRESH_DEBOUNCE_MS = 250L
    private val keyboxRefreshScheduler =
        ConflatedRefreshScheduler(scope, KEYBOX_REFRESH_DEBOUNCE_MS) {
            updateKeyBoxesSync()
        }

    fun updateKeyBoxes(): Job = keyboxRefreshScheduler.submit()

    fun updateKeyBoxesSync(): Boolean =
        updateKeyBoxesSyncWith(
            revocationProvider = { KeyboxVerifier.fetchCrl() },
            verifier = { keybox, crl -> KeyboxVerifier.verifyKeybox(keybox, crl) },
        )

    fun updateKeyBoxesSync(revokedSerials: Set<String>?): Boolean =
        updateKeyBoxesSyncWith(
            revocationProvider = { revokedSerials },
            verifier = { keybox, revoked -> KeyboxVerifier.verifyKeyboxLegacy(keybox, revoked) },
        )

    @androidx.annotation.VisibleForTesting
    internal fun updateKeyBoxesSync(
        revokedSerials: Set<String>?,
        verifier: (CertHack.KeyBox, Set<String>) -> KeyboxVerifier.Status,
    ): Boolean = updateKeyBoxesSyncWith({ revokedSerials }, verifier)

    @androidx.annotation.VisibleForTesting
    internal fun updateKeyBoxesSyncWithoutExternalSourcesForTesting(
        revokedSerials: Set<String>?,
        verifier: (CertHack.KeyBox, Set<String>) -> KeyboxVerifier.Status,
    ): Boolean =
        updateKeyBoxesSyncWith(
            revocationProvider = { revokedSerials },
            verifier = verifier,
            allowRecovery = false,
            refreshExternalSources = false,
        )

    internal fun rebuildBackendKeyboxesAfterRestart(crl: CrlWire.Handle): Boolean {
        storedKeyboxCache.clear()
        return updateKeyBoxesSyncWith(
            revocationProvider = { crl },
            verifier = { keybox, handle -> KeyboxVerifier.verifyKeybox(keybox, handle) },
            allowRecovery = false,
            refreshExternalSources = false,
        )
    }

    private fun <R> updateKeyBoxesSyncWith(
        revocationProvider: () -> R?,
        verifier: (CertHack.KeyBox, R) -> KeyboxVerifier.Status,
        allowRecovery: Boolean = true,
        refreshExternalSources: Boolean = true,
    ): Boolean =
        KeyboxActivation.coordinateRefresh {
            val refreshTicket = KeyboxActivation.beginRefresh()
            runCatching {
                Logger.d("updateKeyBoxes: starting keybox scan (root=${root.absolutePath})")
                val allKeyboxes = ArrayList<CertHack.KeyBox>(KeyboxLoader.MAX_ACTIVE_KEYS)
                fun appendBounded(source: List<CertHack.KeyBox>, owner: String) {
                    if (source.size > KeyboxLoader.MAX_ACTIVE_KEYS - allKeyboxes.size) {
                        throw KeyboxAggregateLimitException(
                            "Keybox aggregate exceeds ${KeyboxLoader.MAX_ACTIVE_KEYS} entries at $owner",
                        )
                    }
                    allKeyboxes.addAll(source)
                }

                val storedSources = StoredKeyboxInventory.runtimeXmlSources(root)
                Logger.d("updateKeyBoxes: scanning ${storedSources.size} stored XML sources")
                val currentFiles = HashSet<String>()
                storedSources.forEach { source ->
                    currentFiles.add(source.id)
                    try {
                        val parsed =
                            KeyboxLoader.parseFileSnapshot(
                                requireNotNull(source.scope.fileScope),
                                source.filename,
                            )
                        val snapshotSha256 = parsed.snapshotSha256
                        if (snapshotSha256 == null || !fullSha256Pattern.matches(snapshotSha256)) {
                            storedKeyboxCache.remove(source.id)
                            Logger.w("Ignoring keybox source without a stable parsed snapshot: ${source.id}")
                            return@forEach
                        }
                        val cached = storedKeyboxCache[source.id]
                        val selected = if (cached != null && cached.snapshotSha256 == snapshotSha256) cached.keyboxes else parsed.keyboxes
                        appendBounded(selected, source.id)
                        if (cached == null || cached.snapshotSha256 != snapshotSha256) {
                            storedKeyboxCache[source.id] = KeyboxFileCache(snapshotSha256, selected)
                            Logger.i("Reloaded keybox source: ${source.id}")
                        }
                    } catch (error: RustBackendUnavailableException) {
                        throw error
                    } catch (error: KeyboxAggregateLimitException) {
                        throw error
                    } catch (error: Exception) {
                        storedKeyboxCache.remove(source.id)
                        Logger.e("Failed to parse keybox source: ${source.id}", error)
                    }
                }
                val cacheIterator = storedKeyboxCache.keys.iterator()
                while (cacheIterator.hasNext()) {
                    if (!currentFiles.contains(cacheIterator.next())) cacheIterator.remove()
                }

                if (refreshExternalSources) CboxManager.refresh()
                appendBounded(CboxManager.getUnlockedKeyboxes(), "CBOX sources")
                appendBounded(ServerManager.getLoadedKeyboxes(), "server sources")

                val verifiedKeyboxes: List<CertHack.KeyBox> =
                    if (allKeyboxes.isEmpty()) {
                        emptyList()
                    } else {
                        val revocation = revocationProvider()
                        if (revocation == null) {
                            Logger.e("Keyboxes remain inactive because the revocation list is unavailable")
                            emptyList()
                        } else {
                            val statuses = allKeyboxes.map { keybox -> verifier(keybox, revocation) }
                            if (statuses.all { it == KeyboxVerifier.Status.VALID }) {
                                allKeyboxes.toList()
                            } else {
                                Logger.e("Keybox pool rejected because it contains an invalid or revoked entry")
                                emptyList()
                            }
                        }
                    }

                if (KeyboxLoader.consumeBackendOutage() || NativeBackend.consumeBackendStateReset()) {
                    Logger.e("Backend state changed while preparing the keybox snapshot")
                    return@runCatching if (allowRecovery) BackendRecovery.recoverOnce(force = true) else false
                }

                when (KeyboxActivation.commitAndPublish(refreshTicket, verifiedKeyboxes)) {
                    KeyboxActivation.PublicationResult.COMMITTED -> {
                        Logger.i(
                            "updateKeyBoxes: ${verifiedKeyboxes.size}/${allKeyboxes.size} verified keyboxes active",
                        )
                        true
                    }
                    KeyboxActivation.PublicationResult.SUPERSEDED -> {
                        Logger.d("Keybox refresh was superseded before publication")
                        false
                    }
                    KeyboxActivation.PublicationResult.FAILED -> {
                        Logger.e("Rust backend rejected the active keybox set; managed snapshot not published")
                        if (allowRecovery) BackendRecovery.recoverOnce(force = true) else false
                    }
                }
            }.getOrElse { error ->
                if (error is KeyboxAggregateLimitException) {
                    storedKeyboxCache.clear()
                    KeyboxActivation.commitAndPublish(emptyList())
                }
                Logger.e("failed to update keyboxes", error)
                if (allowRecovery &&
                    (error is RustBackendUnavailableException || error is RustBackendStateException || NativeBackend.consumeBackendStateReset())
                ) {
                    BackendRecovery.recoverOnce(force = error is RustBackendStateException)
                } else {
                    false
                }
            }
        }

    private fun isRegularFlagFile(f: File?): Boolean = f != null && Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun updateGlobalMode(f: File?) {
        isGlobalMode = isRegularFlagFile(f)
        Logger.i("Global mode is ${if (isGlobalMode) "enabled" else "disabled"}")
    }

    private fun updateGlobalIdentityMode(f: File?) {
        isGlobalIdentityMode = isRegularFlagFile(f)
        identityTargetState.cache.clear()
        Logger.i("Global Identity mode is ${if (isGlobalIdentityMode) "enabled" else "disabled"}")
    }

    private fun updateSpoofEnabled(f: File?) {
        val enabled = isRegularFlagFile(f)
        val changed = isSpoofEnabled != enabled
        if (changed) {
            targetState.hackCache.clear()
            drmState.cache.clear()
            rkpInfrastructureCache.clear()
        }
        isSpoofEnabled = enabled
        PolicyState.onLegacySettingsChanged()
        KeyboxAutoCleaner.setEnabled(isRegularFlagFile(File(root, AUTO_KEYBOX_CHECK_FILE)))
        Logger.i("Identity Spoof Engine is ${if (enabled) "enabled" else "disabled"}; core protection is unchanged")
        if (changed) signalRuntimeController()
    }

    private fun updateBuildIdentity(f: File?) {
        isBuildIdentityEnabled = isRegularFlagFile(f)
        PolicyState.onLegacySettingsChanged()
        Logger.i("Build identity spoofing is ${if (isBuildIdentityEnabled) "enabled" else "disabled"}")
    }

    private fun updateTeeBrokenMode(f: File?) {
        isTeeBrokenMode = isRegularFlagFile(f)
        Logger.i("Legacy TEE safe mode flag is ${if (isTeeBrokenMode) "present" else "absent"}; core protection is unchanged")
    }

    private fun updateTelephony(f: File?) {
        val enabled = isRegularFlagFile(f)
        val changed = isTelephonyEnabled != enabled
        isTelephonyEnabled = enabled
        PolicyState.onLegacySettingsChanged()
        Logger.i("Telephony is ${if (isTelephonyEnabled) "enabled" else "disabled"}")
        if (changed) signalRuntimeController()
    }

    private fun updateCameraVisibility(f: File?) {
        val enabled = isRegularFlagFile(f)
        val changed = isCameraVisibilityEnabled != enabled
        isCameraVisibilityEnabled = enabled
        Logger.i("Camera visibility is ${if (enabled) "enabled" else "disabled"}")
        if (changed) signalRuntimeController()
    }

    private fun updateRkpPassthrough(f: File?) {
        isRkpPassthroughEnabled = isRegularFlagFile(f)
        Logger.i("Legacy RKP passthrough marker is ${if (isRkpPassthroughEnabled) "present" else "absent"}; RKP protection is always active")
    }

    private fun updateDrmPassthrough(f: File?) {
        isDrmPassthroughEnabled = isRegularFlagFile(f)
        drmState.cache.clear()
        targetState.hackCache.clear()
        Logger.i("DRM passthrough is ${if (isDrmPassthroughEnabled) "enabled" else "disabled"}")
    }

    internal fun refreshRuntimeSetting(name: String) {
        val candidate = File(root, name)
        val file = candidate.takeIf { isRegularFlagFile(it) }
        when (name) {
            SPOOF_ENABLED_FILE -> {
                updateSpoofEnabled(file)
                updateRandomOnBoot(File(root, RANDOM_ON_BOOT_FILE))
            }
            BUILD_IDENTITY_FILE -> updateBuildIdentity(file)
            GLOBAL_MODE_FILE -> {
                updateGlobalMode(file)
                updateTargetPackages(File(root, TARGET_FILE))
            }
            GLOBAL_IDENTITY_MODE_FILE -> {
                updateGlobalIdentityMode(file)
                updateIdentityTargetPackages(File(root, IDENTITY_TARGET_FILE))
            }
            IDENTITY_TARGET_FILE -> updateIdentityTargetPackages(file)
            TEE_BROKEN_MODE_FILE -> {
                updateTeeBrokenMode(file)
                updateTargetPackages(File(root, TARGET_FILE))
            }
            TELEPHONY_FILE -> updateTelephony(file)
            CAMERA_VISIBILITY_FILE -> updateCameraVisibility(file)
            RKP_PASSTHROUGH_FILE -> updateRkpPassthrough(file)
            DRM_PASSTHROUGH_FILE -> updateDrmPassthrough(file)
            RANDOM_ON_BOOT_FILE -> {
                PolicyState.onLegacySettingsChanged()
                updateRandomOnBoot(file)
            }
            BootLogic.FILE_SPOOF_CN -> PolicyState.onLegacySettingsChanged()
            PolicyState.STATE_FILE -> {
                PolicyState.reload().getOrThrow()
                updateRandomOnBoot(File(root, RANDOM_ON_BOOT_FILE))
            }
            AUTO_KEYBOX_CHECK_FILE -> KeyboxAutoCleaner.setEnabled(file != null)
        }
    }

    internal fun refreshRestoredConfiguration(): Result<Unit> =
        runCatching {
            fun restoredFile(name: String): File? {
                val file = File(root, name)
                return file.takeIf { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            }
            updateDrmPackages(restoredFile(DRM_PACKAGES_FILE)).getOrThrow()
            updateCustomTemplates(restoredFile(CUSTOM_TEMPLATES_FILE)).getOrThrow()
            updateBuildVars(restoredFile(SPOOF_BUILD_VARS_FILE)).getOrThrow()
            ProfileAutoIdentityStore.load(root)
            updateModuleHash(restoredFile(MODULE_HASH_FILE)).getOrThrow()
            updateSecurityPatch(restoredFile(SECURITY_PATCH_FILE)).getOrThrow()
            updateAppConfigs(restoredFile(APP_CONFIG_FILE)).getOrThrow()
            PolicyState.reload().getOrThrow()
            refreshPrivacySeed().getOrThrow()
            updateTargetPackages(restoredFile(TARGET_FILE)).getOrThrow()
        }

    internal fun signalRuntimeController() {
        if (runtimeControllerSignal.availablePermits() == 0) runtimeControllerSignal.release()
    }

    @Throws(InterruptedException::class)
    internal fun awaitRuntimeController(timeoutMs: Long) {
        require(timeoutMs > 0) { "Runtime controller timeout must be positive" }
        runtimeControllerSignal.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
        runtimeControllerSignal.drainPermits()
    }

    private fun parseDrmPackages(lines: Sequence<String>): PackageTrie<Boolean> {
        val packages = PackageTrie<Boolean>()
        var ruleCount = 0
        lines.forEach { line ->
            val packageName = line.trim()
            if (packageName.isEmpty() || packageName.startsWith("#")) return@forEach
            require(
                packageName.length <= 255 &&
                    packageName.all { character ->
                        character.isLetterOrDigit() || character == '_' || character == '.' || character == '*'
                    },
            ) { "Invalid DRM package rule" }
            require(++ruleCount <= MAX_DRM_PACKAGE_RULES) { "Too many DRM package rules" }
            packages.add(packageName, true)
        }
        return packages
    }

    private fun updateDrmPackages(f: File?) =
        runCatching {
            val packages =
                if (f?.exists() == true) {
                    require(Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        "drm_packages.txt must be a regular file"
                    }
                    val text = readUtf8FileSnapshotBounded(f, 0, MAX_DRM_PACKAGES_BYTES)
                    parseDrmPackages(text.lineSequence())
                } else {
                    PackageTrie()
                }
            drmState = DrmState(packages)
            targetState.hackCache.clear()
            Logger.i { "Updated DRM passthrough packages: ${packages.size}" }
        }.onFailure { failure ->
            if (failure !is IllegalArgumentException) Logger.e("failed to update DRM passthrough packages", failure)
        }

    @Volatile
    private var buildVars: Map<String, String> = emptyMap()

    @Volatile
    private var attestationIds: Map<String, ByteArray> = emptyMap()

    @Volatile
    private var identityOverrides = IdentityOverrides()
    private val REDACTED_IDENTITY =
        IdentityOverrides(
            imei = "",
            imei2 = "",
            imsi = "",
            imsi2 = "",
            iccid = "",
            iccid2 = "",
            meid = "",
            meid2 = "",
            phoneNumber = "",
            phoneNumber2 = "",
            serial = "",
        )
    private val privacySeedLock = Any()

    @Volatile
    private var privacySeed: ByteArray? = null
    private const val MAX_BUILD_VARS_BYTES = 1024 * 1024L
    private const val MAX_BUILD_VAR_ENTRIES = 512
    private const val MAX_BUILD_VAR_VALUE_LENGTH = 512

    private val stringToBytesCache = ConcurrentHashMap<String, ByteArray>()

    fun getAttestationId(tag: String): ByteArray? = attestationIds[tag]

    fun getAttestationId(
        tag: String,
        uid: Int,
    ): ByteArray? {
        if (!PolicyState.isFeatureEnabled(PolicyState.Feature.ATTESTATION_IDENTITY, uid)) return null
        when (getAppPrivacyMode(uid)) {
            AppPrivacyMode.REDACT -> return ByteArray(0)
            AppPrivacyMode.ISOLATE -> {
                val isolated = getIsolatedIdentity(uid)
                val value =
                    when (tag) {
                        "SERIAL" -> isolated.serial
                        "IMEI" -> isolated.imei
                        "MEID" -> isolated.meid
                        else -> null
                    }
                if (value != null) return value.toByteArray(Charsets.UTF_8)
            }
            AppPrivacyMode.INHERIT -> Unit
        }
        val global = attestationIds[tag]
        if (global != null) return global
        val value = getBuildVar(tag, uid) ?: return null
        return stringToBytesCache.getOrPut(value) { value.toByteArray(Charsets.UTF_8) }
    }

    @Volatile
    private var templates: Map<String, Map<String, String>> = emptyMap()
    private const val MAX_CUSTOM_TEMPLATE_BYTES = 1024 * 1024L
    private const val MAX_CUSTOM_TEMPLATES = 128
    private const val MAX_TEMPLATE_PROPERTIES = 64
    private const val MAX_TEMPLATE_VALUE_LENGTH = 512
    private val validTemplateName = Regex("[a-z0-9_-]{1,64}")
    private val supportedTemplateProperties =
        setOf(
            "BRAND",
            "DEVICE",
            "PRODUCT",
            "MANUFACTURER",
            "MODEL",
            "FINGERPRINT",
            "RELEASE",
            "BUILD_ID",
            "INCREMENTAL",
            "TYPE",
            "TAGS",
            "SECURITY_PATCH",
        )

    internal fun updateCustomTemplates(f: File?) =
        runCatching {
            val newTemplates = LinkedHashMap<String, Map<String, String>>()
            DeviceTemplateManager.listTemplates().forEach {
                newTemplates[it.id.lowercase()] = it.toPropMap()
            }
            if (f != null && f.exists()) {
                require(Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    "custom_templates must be a regular file"
                }
                val text = readUtf8FileSnapshotBounded(f, 1, MAX_CUSTOM_TEMPLATE_BYTES)
                var currentTemplate: String? = null
                var currentProps: MutableMap<String, String>? = null
                var sectionCount = 0
                fun commitCurrent() {
                    val name = currentTemplate ?: return
                    val properties = currentProps ?: return
                    newTemplates[name] = properties.toMap()
                }
                text.lineSequence().forEach { line ->
                    val value = line.trim()
                    if (value.isEmpty() || value.startsWith("#")) return@forEach
                    if (value.startsWith("[") && value.endsWith("]")) {
                        commitCurrent()
                        val name = value.substring(1, value.length - 1).trim().lowercase()
                        require(validTemplateName.matches(name)) { "Invalid custom template name" }
                        require(++sectionCount <= MAX_CUSTOM_TEMPLATES) { "Too many custom templates" }
                        currentTemplate = name
                        currentProps = newTemplates[name]?.toMutableMap() ?: LinkedHashMap()
                    } else {
                        require(currentTemplate != null) { "Template property appears before a section" }
                        val separator = value.indexOf('=')
                        require(separator in 1 until value.lastIndex) { "Invalid custom template property" }
                        val key = value.substring(0, separator).trim()
                        val propertyValue = value.substring(separator + 1).trim()
                        require(key in supportedTemplateProperties) { "Unsupported custom template property" }
                        require(
                            propertyValue.isNotEmpty() &&
                                propertyValue.length <= MAX_TEMPLATE_VALUE_LENGTH &&
                                propertyValue.none(Char::isISOControl),
                        ) { "Invalid custom template value" }
                        val properties = requireNotNull(currentProps)
                        require(properties.size < MAX_TEMPLATE_PROPERTIES || properties.containsKey(key)) {
                            "Too many custom template properties"
                        }
                        properties[key] = propertyValue
                    }
                }
                commitCurrent()
            }
            templates = newTemplates
            stringToBytesCache.clear()
            CertHack.clearCertificateCache()
            Logger.i("Updated templates: ${templates.keys}")
        }.onFailure { Logger.e("failed to update custom templates", it) }

    fun getTemplateNames(): Set<String> = templates.keys

    fun getTemplate(name: String): Map<String, String>? = templates[name.lowercase()]

    fun getBuildVar(key: String): String? = buildVars[key]

    internal fun getBuildIdentity(): Map<String, String> {
        val snapshot = buildVars
        return supportedTemplateProperties.mapNotNull { key -> snapshot[key]?.let { value -> key to value } }.toMap()
    }

    internal fun getIdentityOverrides(): IdentityOverrides = identityOverrides

    internal fun getTelephonyIdentityOverrides(uid: Int): IdentityOverrides =
        when (getAppPrivacyMode(uid)) {
            AppPrivacyMode.INHERIT -> identityOverrides
            AppPrivacyMode.REDACT -> REDACTED_IDENTITY
            AppPrivacyMode.ISOLATE -> getIsolatedIdentity(uid)
        }

    private fun getIsolatedIdentity(uid: Int): IdentityOverrides {
        val packages = getPackages(uid).asSequence().distinct().sorted().toList()
        val state = appConfigState
        getCachedValue(state.identityCache, uid)?.let { return it.value }
        val context = if (packages.isEmpty()) "uid:$uid" else packages.joinToString("\u0000")
        fun derived(field: String): ByteArray = derivePrivacyBytes(context, field)
        val identity =
            IdentityOverrides(
                template = getAppConfig(uid)?.template,
                imei = deterministicLuhn(15, "35", derived("imei:0")),
                imei2 = deterministicLuhn(15, "35", derived("imei:1")),
                imsi = deterministicDigits(15, "310260", derived("imsi:0")),
                imsi2 = deterministicDigits(15, "310260", derived("imsi:1")),
                iccid = deterministicLuhn(20, "8901", derived("iccid:0")),
                iccid2 = deterministicLuhn(20, "8901", derived("iccid:1")),
                meid = deterministicHex(14, derived("meid:0")),
                meid2 = deterministicHex(14, derived("meid:1")),
                phoneNumber = "+1${deterministicDigits(10, "", derived("phone:0"))}",
                phoneNumber2 = "+1${deterministicDigits(10, "", derived("phone:1"))}",
                serial = deterministicSerial(12, derived("serial")),
            )
        cacheValue(state.identityCache, uid, identity)
        return identity
    }

    private fun derivePrivacyBytes(context: String, field: String): ByteArray {
        val seed = getPrivacySeed()
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(seed, "HmacSHA256"))
            mac.update(PRIVACY_DERIVATION_DOMAIN.toByteArray(Charsets.UTF_8))
            mac.update(0.toByte())
            mac.update(context.toByteArray(Charsets.UTF_8))
            mac.update(0.toByte())
            mac.doFinal(field.toByteArray(Charsets.UTF_8))
        } finally {
            seed.fill(0)
        }
    }

    private fun deterministicDigits(length: Int, prefix: String, entropy: ByteArray): String {
        require(prefix.length <= length)
        val output = StringBuilder(length).append(prefix)
        var index = 0
        while (output.length < length) {
            output.append((entropy[index % entropy.size].toInt() and 0xff) % 10)
            index++
        }
        entropy.fill(0)
        return output.toString()
    }

    private fun deterministicLuhn(length: Int, prefix: String, entropy: ByteArray): String {
        val partial = deterministicDigits(length - 1, prefix, entropy)
        var sum = 0
        var doubleDigit = true
        for (index in partial.lastIndex downTo 0) {
            var digit = partial[index] - '0'
            if (doubleDigit) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            doubleDigit = !doubleDigit
        }
        return partial + ((10 - sum % 10) % 10)
    }

    private fun deterministicHex(length: Int, entropy: ByteArray): String {
        val output = StringBuilder(length)
        for (index in 0 until length) output.append(HEX_ALPHABET[(entropy[index % entropy.size].toInt() and 0xff) % 16])
        entropy.fill(0)
        return output.toString()
    }

    private fun deterministicSerial(length: Int, entropy: ByteArray): String {
        val output = StringBuilder(length)
        for (index in 0 until length) output.append(SERIAL_ALPHABET[(entropy[index % entropy.size].toInt() and 0xff) % SERIAL_ALPHABET.length])
        entropy.fill(0)
        return output.toString()
    }

    private fun getPrivacySeed(): ByteArray =
        synchronized(privacySeedLock) {
            privacySeed?.let { return@synchronized it.clone() }
            val loaded = loadPrivacySeed(true) ?: throw IOException("Privacy seed is unavailable")
            privacySeed = loaded
            loaded.clone()
        }

    internal fun refreshPrivacySeed(): Result<Unit> =
        runCatching {
            synchronized(privacySeedLock) {
                val loaded = loadPrivacySeed(false)
                val previous = privacySeed
                privacySeed = loaded
                previous?.fill(0)
                appConfigState.identityCache.clear()
                CertHack.clearCertificateCache()
            }
        }.onFailure { Logger.e("Failed to refresh application privacy seed", it) }

    internal fun ensurePrivacySeed(directory: File): Result<Unit> =
        runCatching {
            synchronized(privacySeedLock) {
                val file = File(directory, PRIVACY_SEED_FILE)
                if (directory.canonicalFile != root.canonicalFile) {
                    if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        readPrivacySeed(file).fill(0)
                    } else {
                        val generated = generatePrivacySeed()
                        try {
                            writePrivacySeed(file, generated)
                        } finally {
                            generated.fill(0)
                        }
                    }
                    return@synchronized
                }
                if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    val loaded = readPrivacySeed(file)
                    val previous = privacySeed
                    if (previous == null || !MessageDigest.isEqual(previous, loaded)) {
                        privacySeed = loaded
                        previous?.fill(0)
                        appConfigState.identityCache.clear()
                        CertHack.clearCertificateCache()
                    } else {
                        loaded.fill(0)
                    }
                } else {
                    val current = privacySeed
                    val generated = current ?: generatePrivacySeed()
                    try {
                        writePrivacySeed(file, generated)
                        if (current == null) privacySeed = generated
                    } catch (error: Throwable) {
                        if (current == null) generated.fill(0)
                        throw error
                    }
                }
            }
        }.onFailure { Logger.e("Failed to materialize application privacy seed", it) }

    private fun loadPrivacySeed(createIfMissing: Boolean): ByteArray? {
        val file = File(root, PRIVACY_SEED_FILE)
        val path = file.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return readPrivacySeed(file)
        if (!createIfMissing) return null
        val generated = generatePrivacySeed()
        try {
            writePrivacySeed(file, generated)
        } catch (error: Throwable) {
            generated.fill(0)
            throw error
        }
        return generated
    }

    private fun readPrivacySeed(file: File): ByteArray {
        val path = file.toPath()
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || file.length() !in 64..65) {
            throw IOException("Privacy seed path is invalid")
        }
        val encoded = ByteArray(66)
        var total = 0
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                while (total < encoded.size) {
                    val count = input.read(encoded, total, encoded.size - total)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                }
                if (total == encoded.size || input.read() >= 0) throw IOException("Privacy seed is too large")
            }
            return decodePrivacySeed(encoded, total) ?: throw IOException("Privacy seed is invalid")
        } finally {
            encoded.fill(0)
        }
    }

    private fun generatePrivacySeed(): ByteArray {
        val seed = ByteArray(PRIVACY_SEED_BYTES)
        do {
            SecureRandom().nextBytes(seed)
        } while (isDegeneratePrivacySeed(seed))
        return seed
    }

    private fun writePrivacySeed(file: File, seed: ByteArray) {
        val encoded = encodePrivacySeed(seed)
        try {
            SecureFile.writeStream(file, ByteArrayInputStream(encoded), encoded.size.toLong())
        } finally {
            encoded.fill(0)
        }
    }

    private fun decodePrivacySeed(value: ByteArray, length: Int): ByteArray? {
        var start = 0
        var end = length
        while (start < end && (value[start].toInt() and 0xff) <= 0x20) start++
        while (end > start && (value[end - 1].toInt() and 0xff) <= 0x20) end--
        if (end - start != PRIVACY_SEED_BYTES * 2) return null
        val output = ByteArray(PRIVACY_SEED_BYTES)
        for (index in output.indices) {
            val high = decodeHex(value[start + index * 2])
            val low = decodeHex(value[start + index * 2 + 1])
            if (high < 0 || low < 0) {
                output.fill(0)
                return null
            }
            output[index] = ((high shl 4) or low).toByte()
        }
        if (isDegeneratePrivacySeed(output)) {
            output.fill(0)
            return null
        }
        return output
    }

    internal fun isValidPrivacySeedEncoding(value: ByteArray): Boolean {
        val decoded = decodePrivacySeed(value, value.size) ?: return false
        decoded.fill(0)
        return true
    }

    private fun isDegeneratePrivacySeed(value: ByteArray): Boolean = value.isNotEmpty() && value.all { it == value[0] }

    private fun decodeHex(value: Byte): Int {
        val unsigned = value.toInt() and 0xff
        return when (unsigned) {
            in '0'.code..'9'.code -> unsigned - '0'.code
            in 'a'.code..'f'.code -> unsigned - 'a'.code + 10
            in 'A'.code..'F'.code -> unsigned - 'A'.code + 10
            else -> -1
        }
    }

    private fun encodePrivacySeed(value: ByteArray): ByteArray {
        val alphabet = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
        val output = ByteArray(value.size * 2)
        value.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 0xff
            output[index * 2] = alphabet[unsigned ushr 4]
            output[index * 2 + 1] = alphabet[unsigned and 0x0f]
        }
        return output
    }

    fun getBuildVar(key: String, uid: Int): String? {
        if (PolicyState.isProfileAutoIdentityEnabled(uid)) {
            ProfileAutoIdentityStore.get(key)?.let { return it }
        }
        val appConfig = getAppConfig(uid)
        val template = if (appConfig?.template != null) templates[appConfig.template] else null
        return template?.get(key) ?: buildVars[key]
    }

    private val supportedBuildVarKeys =
        supportedTemplateProperties +
            setOf(
                "TEMPLATE", "SERIAL", "IMEI", "MEID", "MODULE_HASH",
                "ATTESTATION_ID_BRAND", "ATTESTATION_ID_DEVICE", "ATTESTATION_ID_PRODUCT",
                "ATTESTATION_ID_SERIAL", "ATTESTATION_ID_IMEI", "ATTESTATION_ID_IMEI2",
                "ATTESTATION_ID_IMSI", "ATTESTATION_ID_IMSI2", "ATTESTATION_ID_ICCID",
                "ATTESTATION_ID_ICCID2", "ATTESTATION_ID_MEID", "ATTESTATION_ID_MEID2",
                "ATTESTATION_ID_MANUFACTURER", "ATTESTATION_ID_MODEL", "ATTESTATION_ID_PHONE_NUMBER",
                "ATTESTATION_ID_PHONE_NUMBER2", "VISIBLE_SIM_COUNT", "VISIBLE_CAMERA_COUNT",
            )

    internal fun isValidBuildVarEntry(key: String, value: String): Boolean {
        if (key !in supportedBuildVarKeys || value.isEmpty() || value.length > MAX_BUILD_VAR_VALUE_LENGTH) return false
        if (value.any(Char::isISOControl)) return false
        if (key == "TEMPLATE") return value.length <= 64 && templates.containsKey(value.lowercase())
        if (key == "MODULE_HASH") return value.length == 64 && value.all { it.digitToIntOrNull(16) != null }
        if (key == "VISIBLE_SIM_COUNT") return value.length == 1 && value[0] in '0'..'8'
        if (key == "VISIBLE_CAMERA_COUNT") return value.toIntOrNull()?.let { it in 0..16 } == true
        when (key) {
            "FINGERPRINT" -> return value.all { it.isLetterOrDigit() || it in "._:/+-" }
            "RELEASE" -> return value.length <= 64 && value.all { it.isLetterOrDigit() || it in "._-" }
            "BUILD_ID", "INCREMENTAL" -> return value.length <= 128 && value.all { it.isLetterOrDigit() || it in "._+-" }
            "TYPE" -> return value in setOf("user", "userdebug", "eng")
            "TAGS" -> return value.length <= 128 && value.all { it.isLetterOrDigit() || it in "._,-" }
            "SECURITY_PATCH" -> return runCatching { value.convertPatchLevel(false) }.isSuccess
        }
        val identifier = key.removePrefix("ATTESTATION_ID_")
        return when (identifier) {
            "IMEI", "IMEI2" -> value.length == 15 && value.all(Char::isDigit) && isValidLuhn(value)
            "IMSI", "IMSI2" -> value.length in 5..16 && value.all(Char::isDigit)
            "ICCID", "ICCID2" -> value.length in 18..22 && value.all(Char::isDigit) && isValidLuhn(value)
            "MEID", "MEID2" -> value.length == 14 && value.all { it.digitToIntOrNull(16) != null }
            "PHONE_NUMBER", "PHONE_NUMBER2" -> {
                val digits = value.removePrefix("+")
                digits.isNotEmpty() && value.length <= 32 && digits.all(Char::isDigit)
            }
            "SERIAL" -> value.length <= 64 && value.all { it.isLetterOrDigit() || it in "._-" }
            else -> value.length <= 128
        }
    }

    private fun isValidLuhn(value: String): Boolean {
        var sum = 0
        var doubleDigit = false
        for (index in value.indices.reversed()) {
            var digit = value[index] - '0'
            if (doubleDigit) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            doubleDigit = !doubleDigit
        }
        return sum % 10 == 0
    }

    @OptIn(ExperimentalStdlibApi::class)
    internal fun updateBuildVars(f: File?) =
        runCatching {
            if (f == null || f.absoluteFile == File(root, SPOOF_BUILD_VARS_FILE).absoluteFile) discardStagedRandomization()
            val newVars = mutableMapOf<String, String>()
            val newIds = mutableMapOf<String, ByteArray>()
            val text =
                if (f?.exists() == true) {
                    require(Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        "spoof_build_vars must be a regular file"
                    }
                    readUtf8FileSnapshotBounded(f, 0, MAX_BUILD_VARS_BYTES)
                } else {
                    null
                }
            text?.lineSequence()?.forEach { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val eqIdx = line.indexOf('=')
                    if (eqIdx != -1) {
                        val key = line.substring(0, eqIdx).trim()
                        val value = line.substring(eqIdx + 1).trim()
                        require(isValidBuildVarEntry(key, value)) { "Unsupported or invalid build variable" }
                        require(newVars.size < MAX_BUILD_VAR_ENTRIES || newVars.containsKey(key)) { "Too many build variables" }
                        if (key == "TEMPLATE") {
                            val template = templates[value.lowercase()] ?: throw IllegalArgumentException("Unknown template")
                            newVars[key] = value.lowercase()
                            newVars.putAll(template.filterKeys { it in supportedTemplateProperties })
                        } else {
                            newVars[key] = value
                            if (key.startsWith("ATTESTATION_ID_")) {
                                val tag = key.removePrefix("ATTESTATION_ID_")
                                newIds[tag] = value.toByteArray(Charsets.UTF_8)
                            }
                        }
                    }
                }
            }
            val parsedModuleHash =
                newVars["MODULE_HASH"]?.let { value ->
                    require(value.length == 64) { "MODULE_HASH must be a SHA-256 digest" }
                    value.hexToByteArray().also { require(it.size == 32) }
                }
            val previousVisibleSimCount = identityOverrides.visibleSimCount
            val previousVisibleCameraCount = identityOverrides.visibleCameraCount
            val newIdentityOverrides =
                IdentityOverrides(
                    template = newVars["TEMPLATE"], imei = newVars["ATTESTATION_ID_IMEI"],
                    imei2 = newVars["ATTESTATION_ID_IMEI2"], imsi = newVars["ATTESTATION_ID_IMSI"],
                    imsi2 = newVars["ATTESTATION_ID_IMSI2"], iccid = newVars["ATTESTATION_ID_ICCID"],
                    iccid2 = newVars["ATTESTATION_ID_ICCID2"], meid = newVars["ATTESTATION_ID_MEID"],
                    meid2 = newVars["ATTESTATION_ID_MEID2"], phoneNumber = newVars["ATTESTATION_ID_PHONE_NUMBER"],
                    phoneNumber2 = newVars["ATTESTATION_ID_PHONE_NUMBER2"], serial = newVars["ATTESTATION_ID_SERIAL"],
                    visibleSimCount = newVars["VISIBLE_SIM_COUNT"]?.toInt(),
                    visibleCameraCount = newVars["VISIBLE_CAMERA_COUNT"]?.toInt(),
                )
            buildVars = newVars.toMap()
            attestationIds = newIds.toMap()
            identityOverrides = newIdentityOverrides
            moduleHashFromVars = parsedModuleHash
            stringToBytesCache.clear()
            if (previousVisibleSimCount != newIdentityOverrides.visibleSimCount ||
                previousVisibleCameraCount != newIdentityOverrides.visibleCameraCount
            ) signalRuntimeController()
            CertHack.clearCertificateCache()
            updateRandomOnBoot(File(root, RANDOM_ON_BOOT_FILE))
            Logger.i { "update build vars (keys): ${buildVars.keys}, attestation ids: ${attestationIds.keys}" }
        }.onFailure { Logger.e("failed to update build vars", it) }

    private class SecurityPatchState(val patches: Map<String, Any>, val defaultPatch: Any?) {
        val cache = ConcurrentHashMap<Int, Any>()
        var legacyRules = PackageTrie<Any>()
        var globalRules = PatchRules()
        var packageRules = PackageTrie<PatchRules>()
    }

    private data class PatchRules(val all: Any? = null, val system: Any? = null, val vendor: Any? = null, val boot: Any? = null)

    private class MutablePatchRules {
        var all: Any? = null
        var system: Any? = null
        var vendor: Any? = null
        var boot: Any? = null
        fun set(key: String, value: Any) {
            when (key) {
                "all" -> all = value
                "system" -> system = value
                "vendor" -> vendor = value
                "boot" -> boot = value
                else -> throw IllegalArgumentException("Unsupported patch component")
            }
        }
        fun freeze() = PatchRules(all, system, vendor, boot)
    }

    enum class PatchDisposition { KEEP, OMIT, REPLACE }

    data class AttestationPatchComponent(val disposition: PatchDisposition, val value: Int = 0)

    data class AttestationPatchLevels(
        val system: AttestationPatchComponent,
        val vendor: AttestationPatchComponent,
        val boot: AttestationPatchComponent,
    )

    private val NULL_PATCH = Any()
    private val PATCH_DEVICE_DEFAULT = Any()
    private val PATCH_OMIT = Any()
    private val PATCH_PROP = Any()

    @Volatile
    private var securityPatchState = SecurityPatchState(emptyMap(), null)

    private val dynamicPatchCache = ConcurrentHashMap<String, Pair<Long, Int>>()
    private const val DYNAMIC_PATCH_TTL = 3600 * 1000L
    private const val MAX_SECURITY_PATCH_BYTES = 1024 * 1024L
    private const val MAX_SECURITY_PATCH_RULES = 512
    private val validSecurityPatchTarget = Regex("[A-Za-z0-9_.*]{1,255}")
    private val patchComponentNames = setOf("all", "system", "vendor", "boot")

    private fun parsePatchSetting(value: String): Any? {
        if (value.equals("today", ignoreCase = true)) return "today"
        if (value.any { it == 'Y' || it == 'M' || it == 'D' }) {
            val sample = value.replace("YYYY", "2024").replace("MM", "06").replace("DD", "15")
            return runCatching { sample.convertPatchLevel(false) }.map { value }.getOrNull()
        }
        return runCatching { value.convertPatchLevel(false) }.map { value }.onFailure { Logger.w("Ignoring invalid security patch setting") }.getOrNull()
    }

    private fun parseComponentPatchSetting(value: String): Any? =
        when {
            value.equals("no", ignoreCase = true) -> PATCH_OMIT
            value.equals("device_default", ignoreCase = true) -> PATCH_DEVICE_DEFAULT
            value.equals("prop", ignoreCase = true) -> PATCH_PROP
            value.equals("today", ignoreCase = true) -> "today"
            value.any { it == 'Y' || it == 'M' || it == 'D' } -> {
                val sample = value.replace("YYYY", "2024").replace("MM", "06").replace("DD", "15")
                runCatching { sample.convertPatchLevel(false) }.map { value }.getOrNull()
            }
            else -> runCatching { value.convertPatchLevel(false) }.map { value }.getOrNull()
        }

    private fun resolvePatchValue(value: String, long: Boolean): Int {
        val cacheKey = "${if (long) "long" else "short"}:$value"
        val nowMs = clockSource()
        val cachedDyn = dynamicPatchCache[cacheKey]
        if (cachedDyn != null && (nowMs - cachedDyn.first) < DYNAMIC_PATCH_TTL) return cachedDyn.second
        val now = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val effectiveDate =
            if (value.equals("today", ignoreCase = true)) now.toString() else
                value.replace("YYYY", String.format(Locale.ROOT, "%04d", now.year))
                    .replace("MM", String.format(Locale.ROOT, "%02d", now.monthValue))
                    .replace("DD", String.format(Locale.ROOT, "%02d", now.dayOfMonth))
        val result = effectiveDate.convertPatchLevel(long)
        dynamicPatchCache[cacheKey] = nowMs to result
        return result
    }

    private fun findLegacyPatch(state: SecurityPatchState, callingUid: Int): Any? {
        if (state.patches.isEmpty()) return null
        val packages = getPackages(callingUid)
        for (packageName in packages) {
            val value = state.legacyRules.get(packageName) ?: state.patches[packageName]
            if (value != null) return value
        }
        return null
    }

    fun getPatchLevel(callingUid: Int): Int {
        val defaultLevel = patchLevel
        val state = securityPatchState
        val cached = state.cache[callingUid]
        val patchVal =
            if (cached != null) {
                if (cached === NULL_PATCH) null else cached
            } else {
                val found = findLegacyPatch(state, callingUid) ?: state.defaultPatch
                putBoundedUidCache(state.cache, callingUid, found ?: NULL_PATCH)
                found
            }
        if (patchVal == null) return defaultLevel
        if (patchVal is Int) return patchVal
        return runCatching { resolvePatchValue(patchVal as String, false) }
            .onFailure { Logger.e("Could not resolve configured security patch", it) }
            .getOrDefault(defaultLevel)
    }

    private fun resolveAttestationPatch(setting: Any?, long: Boolean, propertyName: String): AttestationPatchComponent =
        when (setting) {
            null, PATCH_DEVICE_DEFAULT -> AttestationPatchComponent(PatchDisposition.KEEP)
            PATCH_OMIT -> AttestationPatchComponent(PatchDisposition.OMIT)
            PATCH_PROP -> {
                val prop = systemPropertiesGet(propertyName, "").orEmpty()
                val value = runCatching { prop.convertPatchLevel(long) }.getOrNull()
                if (value == null) {
                    Logger.w("Could not resolve security patch from $propertyName")
                    AttestationPatchComponent(PatchDisposition.KEEP)
                } else AttestationPatchComponent(PatchDisposition.REPLACE, value)
            }
            is Int -> {
                val value = if (long && setting in 100_000..999_999) setting * 100 + 1 else setting
                AttestationPatchComponent(PatchDisposition.REPLACE, value)
            }
            is String -> runCatching { AttestationPatchComponent(PatchDisposition.REPLACE, resolvePatchValue(setting, long)) }
                .onFailure { Logger.e("Could not resolve dynamic attestation patch", it) }
                .getOrDefault(AttestationPatchComponent(PatchDisposition.KEEP))
            else -> AttestationPatchComponent(PatchDisposition.KEEP)
        }

    private fun findComponentRules(state: SecurityPatchState, callingUid: Int): PatchRules? {
        val packages = getPackages(callingUid)
        for (packageName in packages) state.packageRules.get(packageName)?.let { return it }
        return null
    }

    fun getAttestationPatchLevels(callingUid: Int): AttestationPatchLevels {
        val state = securityPatchState
        val global = state.globalRules
        val app = findComponentRules(state, callingUid)
        fun selected(component: (PatchRules) -> Any?): Any? {
            val appValue = app?.let { component(it) } ?: app?.all
            if (appValue != null) return appValue
            return component(global) ?: global.all
        }
        val systemSetting = if (app == null) findLegacyPatch(state, callingUid) ?: selected { it.system } else selected { it.system }
        val system =
            if (systemSetting == null) AttestationPatchComponent(PatchDisposition.REPLACE, getPatchLevel(callingUid)) else
                resolveAttestationPatch(systemSetting, false, "ro.build.version.security_patch")
        return AttestationPatchLevels(
            system = system,
            vendor = resolveAttestationPatch(selected { it.vendor }, true, "ro.vendor.build.security_patch"),
            boot = resolveAttestationPatch(selected { it.boot }, true, "ro.bootimage.build.security_patch"),
        )
    }

    internal fun updateSecurityPatch(f: File?) =
        runCatching {
            val newPatch = mutableMapOf<String, Any>()
            val legacyRules = PackageTrie<Any>()
            var newDefault: Any? = null
            val globalRules = MutablePatchRules()
            val packageRules = PackageTrie<PatchRules>()
            var currentPackage: String? = null
            var currentRules = globalRules
            var sectionCount = 0
            var ruleCount = 0
            fun commitSection() {
                val packageName = currentPackage ?: return
                packageRules.add(packageName, currentRules.freeze())
            }
            val text =
                f?.let { file ->
                    require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        "security_patch.txt must be a regular file"
                    }
                    readUtf8FileSnapshotBounded(file, 0, MAX_SECURITY_PATCH_BYTES)
                }
            text?.lineSequence()?.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    require(++ruleCount <= MAX_SECURITY_PATCH_RULES) { "Too many security patch rules" }
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        commitSection()
                        val packageName = trimmed.substring(1, trimmed.lastIndex).trim()
                        require(validSecurityPatchTarget.matches(packageName)) { "Invalid security patch package section" }
                        require(++sectionCount <= MAX_SECURITY_PATCH_RULES) { "Too many security patch package sections" }
                        currentPackage = packageName
                        currentRules = MutablePatchRules()
                        return@forEach
                    }
                    val eqIdx = trimmed.indexOf('=')
                    if (eqIdx != -1) {
                        val key = trimmed.substring(0, eqIdx).trim()
                        val value = trimmed.substring(eqIdx + 1).trim()
                        if (key in patchComponentNames) {
                            val parsed = parseComponentPatchSetting(value) ?: throw IllegalArgumentException("Invalid component security patch setting")
                            currentRules.set(key, parsed)
                            if (currentPackage == null && key in setOf("all", "system")) newDefault = parsePatchSetting(value)
                        } else {
                            require(currentPackage == null) { "Only all/system/vendor/boot are valid inside package sections" }
                            require(validSecurityPatchTarget.matches(key)) { "Invalid security patch target" }
                            val parsed = parsePatchSetting(value) ?: throw IllegalArgumentException("Invalid security patch setting")
                            newPatch[key] = parsed
                            legacyRules.add(key, parsed)
                        }
                    } else {
                        val parsed = parseComponentPatchSetting(trimmed) ?: throw IllegalArgumentException("Invalid default security patch setting")
                        currentRules.set("all", parsed)
                        if (currentPackage == null) newDefault = parsePatchSetting(trimmed)
                    }
                }
            }
            commitSection()
            val newState = SecurityPatchState(newPatch, newDefault)
            newState.legacyRules = legacyRules
            newState.globalRules = globalRules.freeze()
            newState.packageRules = packageRules
            securityPatchState = newState
            PolicyState.onLegacySettingsChanged()
            dynamicPatchCache.clear()
            CertHack.clearCertificateCache()
            Logger.i { "update security patch: default=$newDefault, legacy=${newPatch.size}, sections=$sectionCount" }
        }.onFailure { Logger.e("failed to update security patch", it) }

    @OptIn(ExperimentalStdlibApi::class)
    private val hexFormat = HexFormat { upperCase = false }

    private const val MAX_MODULE_HASH_FILE_BYTES = 128

    @OptIn(ExperimentalStdlibApi::class)
    private fun updateModuleHash(f: File?) =
        runCatching {
            moduleHash =
                f?.let { file ->
                    val path = file.toPath()
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@let null
                    val buffer = ByteArray(MAX_MODULE_HASH_FILE_BYTES + 1)
                    try {
                        var total = 0
                        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                            while (total < buffer.size) {
                                val count = input.read(buffer, total, buffer.size - total)
                                if (count < 0) break
                                if (count == 0) continue
                                total += count
                            }
                        }
                        require(total in 1..MAX_MODULE_HASH_FILE_BYTES) { "module_hash exceeds its size limit" }
                        val value = String(buffer, 0, total, Charsets.US_ASCII).trim()
                        require(value.length == 64 && value.all { it.digitToIntOrNull(16) != null }) {
                            "module_hash must contain one SHA-256 digest"
                        }
                        value.hexToByteArray()
                    } finally {
                        buffer.fill(0)
                    }
                }
            CertHack.clearCertificateCache()
            Logger.i("update module hash: ${moduleHash?.toHexString(hexFormat)}")
        }.onFailure {
            moduleHash = null
            CertHack.clearCertificateCache()
            Logger.e("failed to update module hash", it)
        }

    private const val CONFIG_PATH = "/data/adb/cleverestricky"
    private const val KEYBOX_DIR = "keyboxes"
    private const val TARGET_FILE = "target.txt"
    private const val IDENTITY_TARGET_FILE = "identity_target.txt"
    private const val KEYBOX_FILE = "keybox.xml"
    private const val SPOOF_ENABLED_FILE = "spoof_enabled"
    private const val BUILD_IDENTITY_FILE = "spoof_build_identity"
    private const val GLOBAL_MODE_FILE = "global_mode"
    private const val GLOBAL_IDENTITY_MODE_FILE = "global_identity_mode"
    private const val TEE_BROKEN_MODE_FILE = "tee_broken_mode"
    private const val TELEPHONY_FILE = "telephony"
    private const val CAMERA_VISIBILITY_FILE = "camera_visibility"
    private const val RKP_PASSTHROUGH_FILE = "rkp_passthrough"
    private const val DRM_PASSTHROUGH_FILE = "drm_passthrough"
    private const val DRM_PACKAGES_FILE = "drm_packages.txt"
    private const val SPOOF_BUILD_VARS_FILE = "spoof_build_vars"
    private const val STAGED_BUILD_VARS_FILE = "spoof_build_vars.next"
    private const val MODULE_HASH_FILE = "module_hash"
    private const val SECURITY_PATCH_FILE = "security_patch.txt"
    private const val APP_CONFIG_FILE = "app_config"
    private const val PRIVACY_SEED_FILE = "privacy_seed"
    private const val CUSTOM_TEMPLATES_FILE = "custom_templates"
    private const val TEMPLATES_JSON_FILE = "templates.json"
    private const val RANDOM_ON_BOOT_FILE = "random_on_boot"
    private const val AUTO_KEYBOX_CHECK_FILE = "auto_keybox_check"
    private const val APPLY_PROFILE_FILE = "apply_profile"
    private const val RECOMMENDED_DEFAULTS_PENDING_FILE = "recommended_defaults_pending"
    private const val MAX_DRM_PACKAGES_BYTES = 64L * 1024
    private const val MAX_DRM_PACKAGE_RULES = 256
    private const val MAX_TARGET_FILE_BYTES = 1024L * 1024
    private const val MAX_TARGET_PACKAGE_RULES = 2048
    private const val MAX_APP_CONFIG_BYTES = 1024L * 1024
    private const val MAX_APP_CONFIG_RULES = 1024
    private const val PRIVACY_SEED_BYTES = 32
    private const val PRIVACY_DERIVATION_DOMAIN = "CleveresTricky/AppPrivacy/v1"
    private val APP_PACKAGE_PATTERN = Regex("[A-Za-z0-9_.*]{1,255}")
    private val APP_KEYBOX_PATTERN = Regex("[A-Za-z0-9_.-]{1,128}")

    private fun isValidAppKeybox(value: String): Boolean {
        val lowered = value.lowercase()
        return APP_KEYBOX_PATTERN.matches(value) && !value.startsWith('.') &&
            (lowered.endsWith(".xml") || lowered.endsWith(".cbox"))
    }

    private var root = File(CONFIG_PATH)
    private val keyboxDir get() = File(root, KEYBOX_DIR)

    val keyboxDirectory: File get() = keyboxDir

    @androidx.annotation.VisibleForTesting
    fun setRootForTesting(newRoot: File) {
        privacySeed?.fill(0)
        privacySeed = null
        ProfileAutoIdentityStore.resetForTesting()
        root = newRoot
        KeyboxLoader.fileParserOverride = { scope, filename ->
            val file =
                when (scope) {
                    KeyboxLoader.FileScope.CONFIG_ROOT -> File(newRoot, filename)
                    KeyboxLoader.FileScope.KEYBOX_DIRECTORY -> File(File(newRoot, KEYBOX_DIR), filename)
                }
            val snapshot = readFileSnapshotBounded(file, 1, StoredKeyboxInventory.MAX_XML_BYTES)
            try {
                KeyboxLoader.ParsedFile(
                    snapshotSha256 = sha256Hex(snapshot),
                    keyboxes = snapshot.toString(Charsets.UTF_8).reader().use { reader -> CertHack.parseKeyboxXml(reader, filename) },
                )
            } finally {
                snapshot.fill(0)
            }
        }
        PolicyState.setRootForTesting(newRoot)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return try {
            val alphabet = "0123456789abcdef"
            buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(alphabet[value ushr 4])
                    append(alphabet[value and 0x0f])
                }
            }
        } finally {
            digest.fill(0)
        }
    }

    internal fun getConfigRoot(): File = root

    private val packageListLock = Any()

    @Volatile
    private var cachedPackageList: List<String>? = null

    @Volatile
    private var lastPackageFetchTime: Long = 0
    private const val PACKAGE_CACHE_TTL = 30_000L

    fun getInstalledPackages(): List<String> {
        val now = clockSource()
        val cached = cachedPackageList
        val cachedAge = now - lastPackageFetchTime
        if (cached != null && cachedAge >= 0 && cachedAge < PACKAGE_CACHE_TTL) return cached
        return synchronized(packageListLock) {
            val doubleCheck = cachedPackageList
            val doubleCheckAge = now - lastPackageFetchTime
            if (doubleCheck != null && doubleCheckAge >= 0 && doubleCheckAge < PACKAGE_CACHE_TTL) {
                doubleCheck
            } else {
                val pm = getPm()
                val packages =
                    if (pm != null) {
                        try {
                            try {
                                pm.getInstalledPackages(0L, 0).list.map { it.packageName }
                            } catch (e: NoSuchMethodError) {
                                InstalledPackagesCompat.getInstalledPackageNames(pm, 0)
                            }
                        } catch (t: Throwable) {
                            Logger.e("Failed to list packages via IPC", t)
                            emptyList()
                        }
                    } else emptyList()
                if (packages.size > MAX_INSTALLED_PACKAGES) Logger.w("PackageManager returned too many installed packages; truncating")
                val sortedPackages = packages.asSequence().filter(INSTALLED_PACKAGE_PATTERN::matches).distinct()
                    .take(MAX_INSTALLED_PACKAGES).sorted().toList()
                cachedPackageList = sortedPackages
                lastPackageFetchTime = now
                sortedPackages
            }
        }
    }

    private fun applyProfileFromFile(f: File?) =
        runCatching {
            if (f == null || !f.exists()) return@runCatching
            val tmp = File(f.parentFile, f.name + ".processing")
            if (tmp.exists() && !tmp.delete()) throw IOException("Could not clear stale profile request")
            try {
                try {
                    Files.move(f.toPath(), tmp.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(f.toPath(), tmp.toPath())
                }
                val text = readUtf8FileSnapshotBounded(tmp, 1, 64)
                val profileName =
                    text.reader().buffered().use { reader ->
                        val firstLine = reader.readLine().orEmpty().trim()
                        if (reader.readLine() != null) throw IOException("Invalid profile request")
                        firstLine
                    }
                applyProfile(profileName)
            } finally {
                if (tmp.exists() && !tmp.delete()) Logger.w("Could not remove processed profile request")
            }
        }.onFailure { Logger.e("failed to apply profile from file", it) }

    private fun removeConfigFiles(vararg names: String) {
        names.forEach { name ->
            val file = File(root, name)
            if (Files.isSymbolicLink(file.toPath())) throw IOException("Refusing to delete symbolic-link configuration: $name")
            Files.deleteIfExists(file.toPath())
        }
    }

    @Synchronized
    fun applyProfile(profileName: String) {
        val profile =
            when (profileName.trim().lowercase()) {
                "maximum", "godprofile" -> "maximum"
                "daily", "dailyuse" -> "daily"
                "minimal" -> "minimal"
                "default" -> "default"
                else -> throw IllegalArgumentException("Unknown profile")
            }
        Logger.i("Applying profile: $profile")
        removeConfigFiles(CAMERA_VISIBILITY_FILE)
        when (profile) {
            "maximum" -> {
                SecureFile.touch(File(root, SPOOF_ENABLED_FILE), 384)
                SecureFile.touch(File(root, BUILD_IDENTITY_FILE), 384)
                SecureFile.touch(File(root, GLOBAL_MODE_FILE), 384)
                SecureFile.touch(File(root, GLOBAL_IDENTITY_MODE_FILE), 384)
                removeConfigFiles(TEE_BROKEN_MODE_FILE, BootLogic.FILE_HIDE_PROPS, BootLogic.FILE_SPOOF_CN, DRM_PASSTHROUGH_FILE)
                SecureFile.touch(File(root, RANDOM_ON_BOOT_FILE), 384)
                SecureFile.touch(File(root, SPOOF_BUILD_VARS_FILE), 384)
                SecureFile.touch(File(root, AUTO_KEYBOX_CHECK_FILE), 384)
                SecureFile.touch(File(root, TELEPHONY_FILE), 384)
            }
            "daily" -> {
                SecureFile.touch(File(root, SPOOF_ENABLED_FILE), 384)
                removeConfigFiles(GLOBAL_MODE_FILE, GLOBAL_IDENTITY_MODE_FILE, TEE_BROKEN_MODE_FILE, RANDOM_ON_BOOT_FILE, BootLogic.FILE_HIDE_PROPS,
                    BootLogic.FILE_SPOOF_CN, TELEPHONY_FILE, BUILD_IDENTITY_FILE)
                SecureFile.touch(File(root, SPOOF_BUILD_VARS_FILE), 384)
                SecureFile.touch(File(root, AUTO_KEYBOX_CHECK_FILE), 384)
                SecureFile.touch(File(root, DRM_PASSTHROUGH_FILE), 384)
            }
            "minimal" -> {
                removeConfigFiles(SPOOF_ENABLED_FILE, BUILD_IDENTITY_FILE, GLOBAL_MODE_FILE, GLOBAL_IDENTITY_MODE_FILE, TEE_BROKEN_MODE_FILE,
                    RANDOM_ON_BOOT_FILE, BootLogic.FILE_HIDE_PROPS, BootLogic.FILE_SPOOF_CN, AUTO_KEYBOX_CHECK_FILE,
                    TELEPHONY_FILE)
                SecureFile.touch(File(root, DRM_PASSTHROUGH_FILE), 384)
            }
            "default" -> {
                SecureFile.touch(File(root, GLOBAL_MODE_FILE), 384)
                SecureFile.touch(File(root, AUTO_KEYBOX_CHECK_FILE), 384)
                removeConfigFiles(SPOOF_ENABLED_FILE, BUILD_IDENTITY_FILE, GLOBAL_IDENTITY_MODE_FILE, TEE_BROKEN_MODE_FILE, RANDOM_ON_BOOT_FILE,
                    BootLogic.FILE_HIDE_PROPS, BootLogic.FILE_SPOOF_CN, TELEPHONY_FILE, RKP_PASSTHROUGH_FILE, DRM_PASSTHROUGH_FILE)
            }
        }
        updateSpoofEnabled(File(root, SPOOF_ENABLED_FILE))
        updateBuildIdentity(File(root, BUILD_IDENTITY_FILE))
        updateGlobalMode(File(root, GLOBAL_MODE_FILE))
        updateGlobalIdentityMode(File(root, GLOBAL_IDENTITY_MODE_FILE))
        updateTeeBrokenMode(File(root, TEE_BROKEN_MODE_FILE))
        updateTelephony(File(root, TELEPHONY_FILE))
        updateCameraVisibility(File(root, CAMERA_VISIBILITY_FILE))
        updateRkpPassthrough(File(root, RKP_PASSTHROUGH_FILE))
        updateDrmPassthrough(File(root, DRM_PASSTHROUGH_FILE))
        updateBuildVars(File(root, SPOOF_BUILD_VARS_FILE))
        updateTargetPackages(File(root, TARGET_FILE))
        updateIdentityTargetPackages(File(root, IDENTITY_TARGET_FILE))
        if (profile == "default") PolicyState.applyRecommendedDefaults() else PolicyState.synchronizeBuiltInProfile()
        updateRandomOnBoot(File(root, RANDOM_ON_BOOT_FILE))
        KeyboxAutoCleaner.setEnabled(isRegularFlagFile(File(root, AUTO_KEYBOX_CHECK_FILE)))
    }

    private fun discardStagedRandomization() {
        val staged = File(root, STAGED_BUILD_VARS_FILE)
        val path = staged.toPath()
        when {
            Files.isSymbolicLink(path) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> Files.deleteIfExists(path)
            staged.exists() -> Logger.w("Refusing to remove non-regular staged identity file")
        }
    }

    private fun enforceRandomization() {
        try {
            val spoofFile = File(root, SPOOF_BUILD_VARS_FILE)
            val stagedFile = File(root, STAGED_BUILD_VARS_FILE)
            val replacements =
                linkedMapOf(
                    "ATTESTATION_ID_IMEI" to RandomUtils.generateLuhn(15, "35"),
                    "ATTESTATION_ID_IMEI2" to RandomUtils.generateLuhn(15, "35"),
                    "ATTESTATION_ID_SERIAL" to RandomUtils.generateRandomSerial(12),
                    "ATTESTATION_ID_IMSI" to RandomUtils.generateDigits(15, "310260"),
                    "ATTESTATION_ID_IMSI2" to RandomUtils.generateDigits(15, "310260"),
                    "ATTESTATION_ID_ICCID" to RandomUtils.generateLuhn(20, "8901"),
                    "ATTESTATION_ID_ICCID2" to RandomUtils.generateLuhn(20, "8901"),
                    "ATTESTATION_ID_MEID" to RandomUtils.generateHex(14),
                    "ATTESTATION_ID_MEID2" to RandomUtils.generateHex(14),
                    "ATTESTATION_ID_PHONE_NUMBER" to "+1${RandomUtils.generateDigits(10)}",
                    "ATTESTATION_ID_PHONE_NUMBER2" to "+1${RandomUtils.generateDigits(10)}",
                    "VISIBLE_SIM_COUNT" to RandomUtils.generateVisibleSimCount(allowZero = false),
                    "VISIBLE_CAMERA_COUNT" to (RandomUtils.choose(listOf("1", "2", "2", "3", "3", "3", "4", "4", "4", "4")) ?: "2"),
                )
            val currentTemplate = buildVars["TEMPLATE"]
            val templateCandidates = templates.keys.filterNot { it.equals(currentTemplate, ignoreCase = true) }
            RandomUtils.choose(templateCandidates.ifEmpty { templates.keys.toList() })?.let { templateName ->
                replacements["TEMPLATE"] = templateName
                templates[templateName]?.forEach { (key, value) -> if (key in supportedTemplateProperties) replacements[key] = value }
            }
            val retainedLines = mutableListOf<String>()
            if (Files.isRegularFile(spoofFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                val text = readUtf8FileSnapshotBounded(spoofFile, 0, MAX_BUILD_VARS_BYTES)
                text.lineSequence().forEach { line ->
                    val key = line.substringBefore('=', "").trim()
                    if (key !in replacements && line != "# Prepared by random_on_boot") retainedLines += line
                }
            }
            retainedLines += "# Prepared by random_on_boot"
            replacements.forEach { (key, value) -> retainedLines += "$key=$value" }
            SecureFile.writeText(stagedFile, retainedLines.joinToString("\n", postfix = "\n"))
            Logger.i("Prepared a synchronized identity snapshot for the next boot")
        } catch (e: Exception) {
            Logger.e("Failed to enforce randomization", e)
        }
    }

    @Synchronized
    private fun updateRandomOnBoot(f: File?) {
        val enabled = if (PolicyState.usesV2()) PolicyState.isFeatureEnabled(PolicyState.Feature.IDENTITY_REFRESH) else isSpoofEnabled && isRegularFlagFile(f)
        if (!enabled) {
            discardStagedRandomization()
            return
        }
        val stagedPath = File(root, STAGED_BUILD_VARS_FILE).toPath()
        if (Files.isRegularFile(stagedPath, LinkOption.NOFOLLOW_LINKS)) return
        enforceRandomization()
    }

    object ConfigObserver : FileObserver(root, CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO) {
        override fun onEvent(event: Int, path: String?) {
            path ?: return
            val f = when (event) { CLOSE_WRITE, MOVED_TO -> File(root, path); DELETE, MOVED_FROM -> null; else -> return }
            when (path) {
                TARGET_FILE -> updateTargetPackages(f)
                IDENTITY_TARGET_FILE -> updateIdentityTargetPackages(f)
                KEYBOX_FILE -> updateKeyBoxes()
                SPOOF_BUILD_VARS_FILE -> updateBuildVars(f)
                SECURITY_PATCH_FILE -> updateSecurityPatch(f)
                PolicyState.STATE_FILE -> { PolicyState.reload(); updateRandomOnBoot(File(root, RANDOM_ON_BOOT_FILE)) }
                APP_CONFIG_FILE -> updateAppConfigs(f)
                PRIVACY_SEED_FILE -> refreshPrivacySeed()
                CUSTOM_TEMPLATES_FILE -> updateCustomTemplates(f)
                TEMPLATES_JSON_FILE -> { DeviceTemplateManager.initialize(root); updateCustomTemplates(File(root, CUSTOM_TEMPLATES_FILE)) }
                SPOOF_ENABLED_FILE -> { updateSpoofEnabled(f); updateRandomOnBoot(File(root, RANDOM_ON_BOOT_FILE)) }
                BUILD_IDENTITY_FILE -> updateBuildIdentity(f)
                GLOBAL_MODE_FILE -> { updateGlobalMode(f); updateTargetPackages(File(root, TARGET_FILE)) }
                GLOBAL_IDENTITY_MODE_FILE -> { updateGlobalIdentityMode(f); updateIdentityTargetPackages(File(root, IDENTITY_TARGET_FILE)) }
                TEE_BROKEN_MODE_FILE -> { updateTeeBrokenMode(f); updateTargetPackages(File(root, TARGET_FILE)) }
                TELEPHONY_FILE -> updateTelephony(f)
                CAMERA_VISIBILITY_FILE -> updateCameraVisibility(f)
                RKP_PASSTHROUGH_FILE -> updateRkpPassthrough(f)
                DRM_PASSTHROUGH_FILE -> updateDrmPassthrough(f)
                RANDOM_ON_BOOT_FILE -> { PolicyState.onLegacySettingsChanged(); updateRandomOnBoot(f) }
                BootLogic.FILE_SPOOF_CN -> PolicyState.onLegacySettingsChanged()
                DRM_PACKAGES_FILE -> updateDrmPackages(f)
                MODULE_HASH_FILE -> updateModuleHash(f)
                AUTO_KEYBOX_CHECK_FILE -> KeyboxAutoCleaner.setEnabled(isRegularFlagFile(f))
                APPLY_PROFILE_FILE -> applyProfileFromFile(f)
            }
        }
    }

    object KeyboxDirObserver : FileObserver(keyboxDir, CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO) {
        override fun onEvent(event: Int, path: String?) {
            Logger.i("Keybox directory event: $path")
            updateKeyBoxes()
        }
    }

    fun initialize() {
        Logger.i("Config.initialize: starting (root=${root.absolutePath})")
        SecureFile.mkdirs(root, 448)
        SecureFile.mkdirs(keyboxDir, 448)
        KeyboxVerifier.configureCacheRoot(root)
        DeviceKeyManager.initialize(root)
        CboxManager.initialize()
        ServerManager.initialize()
        DeviceTemplateManager.initialize(root)
        updateSpoofEnabled(File(root, SPOOF_ENABLED_FILE))
        updateBuildIdentity(File(root, BUILD_IDENTITY_FILE))
        updateGlobalMode(File(root, GLOBAL_MODE_FILE))
        updateGlobalIdentityMode(File(root, GLOBAL_IDENTITY_MODE_FILE))
        updateTeeBrokenMode(File(root, TEE_BROKEN_MODE_FILE))
        updateTelephony(File(root, TELEPHONY_FILE))
        updateCameraVisibility(File(root, CAMERA_VISIBILITY_FILE))
        updateRkpPassthrough(File(root, RKP_PASSTHROUGH_FILE))
        updateDrmPassthrough(File(root, DRM_PASSTHROUGH_FILE))
        updateDrmPackages(File(root, DRM_PACKAGES_FILE))
        updateCustomTemplates(File(root, CUSTOM_TEMPLATES_FILE))
        updateBuildVars(File(root, SPOOF_BUILD_VARS_FILE))
        ProfileAutoIdentityStore.load(root)
        updateModuleHash(File(root, MODULE_HASH_FILE))
        updateSecurityPatch(File(root, SECURITY_PATCH_FILE))
        updateAppConfigs(File(root, APP_CONFIG_FILE))
        PolicyState.initialize(root).getOrThrow()
        applyPendingRecommendedDefaults()
        refreshPrivacySeed().getOrThrow()
        updateRandomOnBoot(File(root, RANDOM_ON_BOOT_FILE))
        if (!isGlobalMode) {
            val targetFile = File(root, TARGET_FILE)
            Logger.d("Config.initialize: loading target.txt from ${targetFile.absolutePath} (exists=${targetFile.exists()})")
            if (targetFile.exists()) updateTargetPackages(targetFile) else Logger.e("target.txt file not found, please put it to $targetFile !")
        } else {
            Logger.i("Config.initialize: global mode active; all application UIDs are targeted")
            updateTargetPackages(File(root, TARGET_FILE))
        }
        val identityTargetFile = File(root, IDENTITY_TARGET_FILE)
        if (identityTargetFile.exists()) updateIdentityTargetPackages(identityTargetFile) else updateIdentityTargetPackages(null)
        updateKeyBoxesSync()
        ConfigObserver.startWatching()
        KeyboxDirObserver.startWatching()
    }

    private fun applyPendingRecommendedDefaults() {
        val marker = File(root, RECOMMENDED_DEFAULTS_PENDING_FILE)
        val path = marker.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            Logger.e("Refusing unsafe recommended-defaults marker")
            return
        }
        PolicyState.applyRecommendedDefaults()
        if (!marker.delete()) Logger.w("Could not remove recommended-defaults marker; defaults may be re-evaluated on restart")
    }

    @Volatile
    private var iPm: IPackageManager? = null

    fun getPm(): IPackageManager? {
        val cached = iPm
        if (cached != null) return cached
        val resolved = IPackageManager.Stub.asInterface(ServiceManager.getService("package"))
        if (resolved != null) iPm = resolved
        return resolved
    }

    internal fun matchesPackage(pkgName: String, rules: PackageTrie<Boolean>): Boolean = rules.matches(pkgName)

    internal data class CachedPackage(val value: Array<String>, val timestamp: Long)

    private val packageCache = ConcurrentHashMap<Int, CachedPackage>()
    private val uidLocks = Array(64) { Any() }

    internal var clockSource: () -> Long = { System.currentTimeMillis() }
    private const val CACHE_TTL_MS = 5 * 1000L
    private const val MAX_PACKAGES_PER_UID = 128
    private const val MAX_INSTALLED_PACKAGES = 100_000
    private val INSTALLED_PACKAGE_PATTERN = Regex("[A-Za-z0-9_.]{1,255}")
    private val callerPackageDigest = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    fun getPackages(uid: Int): Array<String> {
        val now = clockSource()
        val cached = packageCache[uid]
        val cachedAge = cached?.let { now - it.timestamp }
        if (cached != null && cachedAge != null && cachedAge >= 0 && cachedAge < CACHE_TTL_MS) return cached.value
        val lock = uidLocks[(uid and Int.MAX_VALUE) % uidLocks.size]
        synchronized(lock) {
            val current = packageCache[uid]
            val currentAge = current?.let { now - it.timestamp }
            if (current != null && currentAge != null && currentAge >= 0 && currentAge < CACHE_TTL_MS) return current.value
            val pm = getPm()
            return if (pm == null) emptyArray() else {
                try {
                    val resolved = pm.getPackagesForUid(uid) ?: emptyArray()
                    val normalized = resolved.asSequence().filter(INSTALLED_PACKAGE_PATTERN::matches).distinct().take(MAX_PACKAGES_PER_UID + 1).toList()
                    if (normalized.size > MAX_PACKAGES_PER_UID) Logger.w("PackageManager returned too many packages for one UID; truncating")
                    val packages = normalized.take(MAX_PACKAGES_PER_UID).sorted().toTypedArray()
                    if (current == null || !current.value.contentEquals(packages)) invalidateUidPolicyCaches(uid)
                    putBoundedUidCache(packageCache, uid, CachedPackage(packages, now))
                    packages
                } catch (error: Exception) {
                    if (iPm === pm) iPm = null
                    Logger.e("Failed to resolve packages for uid=$uid", error)
                    emptyArray()
                }
            }
        }
    }

    fun getCallerPackageDigest(uid: Int): ByteArray {
        val digest = requireNotNull(callerPackageDigest.get())
        digest.reset()
        getPackages(uid).forEach { packageName ->
            digest.update(packageName.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest()
    }

    private fun invalidateUidPolicyCaches(uid: Int) {
        val appState = appConfigState
        appState.cache.remove(uid)
        appState.privacyCache.remove(uid)
        appState.identityCache.remove(uid)
        PolicyState.invalidateUid(uid)
        targetState.hackCache.remove(uid)
        drmState.cache.remove(uid)
        rkpInfrastructureCache.remove(uid)
    }

    private fun checkPackages(packages: PackageTrie<Boolean>, callingUid: Int): Boolean {
        try {
            if (packages.isEmpty()) return false
            val ps = getPackages(callingUid)
            if (ps.isEmpty()) return false
            for (i in ps.indices) if (matchesPackage(ps[i], packages)) return true
            return false
        } catch (e: Exception) {
            Logger.e("failed to get packages", e)
            return false
        }
    }

    private fun getCachedDecision(cache: ConcurrentHashMap<Int, CachedDecision>, uid: Int): Boolean? {
        val cached = cache[uid] ?: return null
        val age = clockSource() - cached.timestamp
        if (age >= 0 && age < UID_DECISION_CACHE_TTL_MS) return cached.value
        cache.remove(uid, cached)
        return null
    }

    private fun <T> getCachedValue(cache: ConcurrentHashMap<Int, CachedValue<T>>, uid: Int): CachedValue<T>? {
        val cached = cache[uid] ?: return null
        val age = clockSource() - cached.timestamp
        if (age >= 0 && age < UID_DECISION_CACHE_TTL_MS) return cached
        cache.remove(uid, cached)
        return null
    }

    private fun <T> cacheValue(cache: ConcurrentHashMap<Int, CachedValue<T>>, uid: Int, value: T) {
        putBoundedUidCache(cache, uid, CachedValue(value, clockSource()))
    }

    private fun cacheDecision(cache: ConcurrentHashMap<Int, CachedDecision>, uid: Int, value: Boolean) {
        putBoundedUidCache(cache, uid, CachedDecision(value, clockSource()))
    }

    private fun isProtectedInfrastructureUid(callingUid: Int): Boolean {
        val cached = getCachedDecision(rkpInfrastructureCache, callingUid)
        if (cached != null) return cached
        val packages = getPackages(callingUid)
        val protected = packages.isEmpty() || packages.any(rkpInfrastructurePackages::contains)
        cacheDecision(rkpInfrastructureCache, callingUid, protected)
        return protected
    }

    private fun isTargetedUid(callingUid: Int): Boolean {
        if (callingUid < FIRST_APPLICATION_UID) return false
        if (isProtectedInfrastructureUid(callingUid)) return false
        if (isDrmPassthroughEnabled) {
            val state = drmState
            val cachedDrm = getCachedDecision(state.cache, callingUid)
            val isDrm = cachedDrm ?: checkPackages(state.packages, callingUid).also { cacheDecision(state.cache, callingUid, it) }
            if (isDrm) return false
        }
        if (isGlobalMode) return true
        if (getAppConfig(callingUid) != null) return true
        val state = targetState
        val cached = getCachedDecision(state.hackCache, callingUid)
        if (cached != null) return cached
        val result = checkPackages(state.hackPackages, callingUid)
        cacheDecision(state.hackCache, callingUid, result)
        return result
    }

    fun needHack(callingUid: Int): Boolean = isTargetedUid(callingUid)

    fun isIdentityTargeted(callingUid: Int): Boolean {
        if (callingUid < FIRST_APPLICATION_UID) return false
        if (isProtectedInfrastructureUid(callingUid)) return false
        if (isGlobalIdentityMode) return true
        if (getAppConfig(callingUid) != null) return true
        val state = identityTargetState
        val cached = getCachedDecision(state.cache, callingUid)
        if (cached != null) return cached
        val result = checkPackages(state.packages, callingUid)
        cacheDecision(state.cache, callingUid, result)
        return result
    }

    @androidx.annotation.VisibleForTesting
    fun reset() {
        ConfigObserver.stopWatching()
        KeyboxDirectoryRefreshWatcher.stop()
        KeyboxDirObserver.stopWatching()
        keyboxRefreshScheduler.cancel()
        KeyboxAutoCleaner.setEnabled(false)
        scope.coroutineContext.cancelChildren()
        root = File(CONFIG_PATH)
        packageCache.clear()
        dynamicPatchCache.clear()
        securityPatchState = SecurityPatchState(emptyMap(), null)
        iPm = null
        appConfigState = AppConfigState(PackageTrie())
        targetState = TargetState(PackageTrie())
        identityTargetState = IdentityTargetState(PackageTrie())
        isGlobalMode = false
        isGlobalIdentityMode = false
        rkpInfrastructureCache.clear()
        buildVars = emptyMap()
        attestationIds = emptyMap()
        identityOverrides = IdentityOverrides()
        privacySeed?.fill(0)
        privacySeed = null
        stringToBytesCache.clear()
        templates = emptyMap()
        moduleHash = null
        moduleHashFromVars = null
        cachedPackageList = null
        lastPackageFetchTime = 0
        isGlobalMode = false
        isSpoofEnabled = false
        isBuildIdentityEnabled = false
        isTeeBrokenMode = false
        isTelephonyEnabled = false
        isCameraVisibilityEnabled = false
        isRkpPassthroughEnabled = false
        isDrmPassthroughEnabled = false
        drmState = DrmState(PackageTrie())
        clockSource = { System.currentTimeMillis() }
        storedKeyboxCache.clear()
        KeyboxActivation.resetForTesting()
        KeyboxLoader.resetForTesting()
        BackendRecovery.resetForTesting()
        NativeBackend.resetIdentityForTesting()
        ProfileAutoIdentityStore.resetForTesting()
        PolicyState.resetForTesting()
    }
}
