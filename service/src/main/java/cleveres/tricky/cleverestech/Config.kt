package cleveres.tricky.cleverestech

import android.content.pm.IPackageManager
import android.os.FileObserver
import android.os.ServiceManager
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.DeviceKeyManager
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import cleveres.tricky.cleverestech.util.PackageTrie
import cleveres.tricky.cleverestech.util.RandomUtils
import cleveres.tricky.cleverestech.util.SecureFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

object Config {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private const val MAX_UID_CACHE_ENTRIES = 4096

    private fun <T> putBoundedUidCache(
        cache: ConcurrentHashMap<Int, T>,
        uid: Int,
        value: T,
    ) {
        if (cache.size >= MAX_UID_CACHE_ENTRIES && !cache.containsKey(uid)) cache.clear()
        cache[uid] = value
    }

    data class AppSpoofConfig(val template: String?, val keyboxFilename: String?)

    // Keep the ruleset and its lookup cache in one state holder so readers
    // never observe cached results for an older ruleset.
    private class TargetState(
        val hackPackages: PackageTrie<Boolean>,
    ) {
        val hackCache = ConcurrentHashMap<Int, Boolean>()
    }

    @Volatile
    private var targetState = TargetState(PackageTrie())

    @Volatile
    var isGlobalMode = false
        private set

    @Volatile
    var isTeeBrokenMode = false
        private set

    @Volatile
    private var moduleHash: ByteArray? = null

    @Volatile
    var isTelephonyEnabled = false

    @Volatile
    private var moduleHashFromVars: ByteArray? = null

    // Optimization: Cache results of getAppConfig to avoid repeated Trie lookups.
    // The cache is bundled with the Trie in a state object to ensure consistency during updates.
    private class AppConfigState(
        val configs: PackageTrie<AppSpoofConfig>,
    ) {
        val cache = ConcurrentHashMap<Int, Any>()
    }

    private val NULL_CONFIG = Any()

    @Volatile
    private var appConfigState = AppConfigState(PackageTrie())

    fun getModuleHash(): ByteArray? = moduleHash ?: moduleHashFromVars

    fun getAppConfig(uid: Int): AppSpoofConfig? {
        val state = appConfigState
        val cached = state.cache[uid]
        if (cached != null) {
            return if (cached === NULL_CONFIG) null else cached as AppSpoofConfig
        }

        if (state.configs.isEmpty()) {
            putBoundedUidCache(state.cache, uid, NULL_CONFIG)
            return null
        }

        val pkgs = getPackages(uid)
        var result: AppSpoofConfig? = null
        val len = pkgs.size
        for (i in 0 until len) {
            val config = state.configs.get(pkgs[i])
            if (config != null) {
                result = config
                break
            }
        }
        putBoundedUidCache(state.cache, uid, result ?: NULL_CONFIG)
        return result
    }

    private fun updateAppConfigs(f: File?) =
        runCatching {
            val newConfigs = PackageTrie<AppSpoofConfig>()
            f?.useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank() && !line.startsWith("#")) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEach

                        // Parse without a regex so large rule files do not create avoidable temporary objects.
                        val len = trimmed.length
                        var idx = 0

                        // parse pkg
                        var start = idx
                        while (idx < len && !trimmed[idx].isWhitespace()) idx++
                        val pkg = trimmed.substring(start, idx)

                        var template: String? = null
                        var keybox: String? = null

                        // parse template
                        while (idx < len && trimmed[idx].isWhitespace()) idx++
                        if (idx < len) {
                            start = idx
                            while (idx < len && !trimmed[idx].isWhitespace()) idx++
                            val tStr = trimmed.substring(start, idx)
                            if (tStr != "null") template = tStr.lowercase()

                            // parse keybox
                            while (idx < len && trimmed[idx].isWhitespace()) idx++
                            if (idx < len) {
                                start = idx
                                while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                val kStr = trimmed.substring(start, idx)
                                if (kStr != "null") keybox = kStr
                            }
                        }

                        if (template != null || keybox != null) {
                            newConfigs.add(pkg, AppSpoofConfig(template, keybox))
                        }
                    }
                }
            }
            appConfigState = AppConfigState(newConfigs)
            CertHack.clearCertificateCache()
            Logger.i { "update app configs: ${newConfigs.size}" }
        }.onFailure {
            Logger.e("failed to update app configs", it)
        }

    fun parsePackages(lines: Sequence<String>): PackageTrie<Boolean> {
        val hackPackages = PackageTrie<Boolean>()
        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

            // Migrate the old trailing-'!' software-generation syntax to the
            // working certificate-substitution path.
            val packageName = trimmed.removeSuffix("!").trim()
            val valid =
                packageName.isNotEmpty() &&
                    packageName.all { character ->
                        character.isLetterOrDigit() || character == '_' ||
                            character == '.' || character == '*'
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
                if (f != null && f.exists()) {
                    f.useLines { lines -> parsePackages(lines) }
                } else {
                    Logger.d("updateTargetPackages: target file missing or null, using empty package list")
                    parsePackages(emptySequence())
                }
            targetState = TargetState(packages)
            Logger.i { "Updated target packages: ${packages.size}" }
        }.onFailure {
            Logger.e("failed to update target files", it)
        }

    private var keyboxPoller: FilePoller? = null

    @Volatile
    private var cachedLegacyKeyboxes: List<CertHack.KeyBox> = emptyList()

    @Volatile
    private var lastKeyboxModified: Long = 0

    @Volatile
    private var lastKeyboxLength: Long = 0

    private data class KeyboxFileCache(
        val lastModified: Long,
        val length: Long,
        val keyboxes: List<CertHack.KeyBox>,
    )

    private val directoryKeyboxCache = ConcurrentHashMap<String, KeyboxFileCache>()
    private const val MAX_KEYBOX_XML_BYTES = 10L * 1024 * 1024
    private const val MAX_KEYBOX_FILES = 64

    fun updateKeyBoxes() =
        scope.launch {
            updateKeyBoxesSync()
        }

    fun updateKeyBoxesSync() {
        updateKeyBoxesSyncWith(revocationProvider = { KeyboxVerifier.fetchCrl() })
    }

    fun updateKeyBoxesSync(revokedSerials: Set<String>?) {
        updateKeyBoxesSyncWith(revocationProvider = { revokedSerials })
    }

    @androidx.annotation.VisibleForTesting
    internal fun updateKeyBoxesSync(
        revokedSerials: Set<String>?,
        verifier: (CertHack.KeyBox, Set<String>) -> KeyboxVerifier.Status,
    ) {
        updateKeyBoxesSyncWith({ revokedSerials }, verifier)
    }

    private fun updateKeyBoxesSyncWith(
        revocationProvider: () -> Set<String>?,
        verifier: (CertHack.KeyBox, Set<String>) -> KeyboxVerifier.Status = KeyboxVerifier::verifyKeybox,
    ) {
        runCatching {
            Logger.d("updateKeyBoxes: starting keybox scan (root=${root.absolutePath})")
            val allKeyboxes = ArrayList<CertHack.KeyBox>()

            // 1. Legacy keybox.xml
            val legacyFile = File(root, KEYBOX_FILE)
            Logger.d("updateKeyBoxes: checking legacy ${legacyFile.absolutePath} (exists=${legacyFile.exists()})")
            if (Files.isRegularFile(legacyFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                legacyFile.length() in 1..MAX_KEYBOX_XML_BYTES
            ) {
                val currentModified = legacyFile.lastModified()
                val currentLength = legacyFile.length()
                if (currentModified != lastKeyboxModified || currentLength != lastKeyboxLength) {
                    legacyFile.bufferedReader().use { reader ->
                        cachedLegacyKeyboxes = CertHack.parseKeyboxXml(reader, KEYBOX_FILE)
                    }
                    lastKeyboxModified = currentModified
                    lastKeyboxLength = currentLength
                    Logger.i("Reloaded keybox.xml (modified: $currentModified, keys: ${cachedLegacyKeyboxes.size})")
                }
                allKeyboxes.addAll(cachedLegacyKeyboxes)
            } else {
                Logger.d("updateKeyBoxes: legacy keybox.xml is missing, non-regular, or oversized")
                cachedLegacyKeyboxes = emptyList()
                lastKeyboxModified = 0
                lastKeyboxLength = 0
            }

            // 2. Directory files (Plain XML)
            if (Files.isDirectory(keyboxDir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                val files = keyboxDir.listFiles { _, name -> name.endsWith(".xml") }?.sortedBy { it.name }
                require(files.orEmpty().size <= MAX_KEYBOX_FILES) { "Too many keybox files" }
                Logger.d("updateKeyBoxes: scanning keybox dir ${keyboxDir.absolutePath} (${files?.size ?: 0} xml files)")
                val currentFiles = HashSet<String>()

                files?.forEach { file ->
                    val filename = file.name
                    currentFiles.add(filename)
                    if (!Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                        file.length() !in 1..MAX_KEYBOX_XML_BYTES
                    ) {
                        directoryKeyboxCache.remove(filename)
                        Logger.w("Ignoring non-regular or oversized keybox file: $filename")
                        return@forEach
                    }
                    val lastMod = file.lastModified()
                    val length = file.length()

                    val cached = directoryKeyboxCache[filename]
                    if (cached != null && cached.lastModified == lastMod && cached.length == length) {
                        allKeyboxes.addAll(cached.keyboxes)
                    } else {
                        try {
                            file.bufferedReader().use { reader ->
                                val parsed = CertHack.parseKeyboxXml(reader, filename)
                                directoryKeyboxCache[filename] = KeyboxFileCache(lastMod, length, parsed)
                                allKeyboxes.addAll(parsed)
                                Logger.i("Reloaded keybox file: $filename")
                            }
                        } catch (e: Exception) {
                            Logger.e("Failed to parse keybox file: $filename", e)
                        }
                    }
                }

                // Cleanup removed files from cache
                val iterator = directoryKeyboxCache.keys.iterator()
                while (iterator.hasNext()) {
                    if (!currentFiles.contains(iterator.next())) {
                        iterator.remove()
                    }
                }
            } else {
                directoryKeyboxCache.clear()
            }

            // 3. Local CBOX files
            CboxManager.refresh()
            allKeyboxes.addAll(CboxManager.getUnlockedKeyboxes())

            // 4. Remote Server Keyboxes
            allKeyboxes.addAll(ServerManager.getLoadedKeyboxes())

            val verifiedKeyboxes: List<CertHack.KeyBox> =
                if (allKeyboxes.isEmpty()) {
                    emptyList()
                } else {
                    val revokedSerials = revocationProvider()
                    if (revokedSerials == null) {
                        Logger.e("Keyboxes remain inactive because the revocation list is unavailable")
                        emptyList()
                    } else {
                        val statuses =
                            allKeyboxes.map { keybox ->
                                verifier(keybox, revokedSerials)
                            }
                        if (statuses.all { it == KeyboxVerifier.Status.VALID }) {
                            allKeyboxes.toList()
                        } else {
                            Logger.e("Keybox pool rejected because it contains an invalid or revoked entry")
                            emptyList()
                        }
                    }
                }
            CertHack.setKeyboxes(verifiedKeyboxes)
            Logger.i(
                "updateKeyBoxes: ${verifiedKeyboxes.size}/${allKeyboxes.size} verified keyboxes active",
            )

            // Update poller for legacy file consistency
            keyboxPoller?.updateLastModified()
        }.onFailure {
            Logger.e("failed to update keyboxes", it)
        }
    }

    private fun updateGlobalMode(f: File?) {
        isGlobalMode = f?.exists() == true
        Logger.i("Global mode is ${if (isGlobalMode) "enabled" else "disabled"}")
    }

    private fun updateTeeBrokenMode(f: File?) {
        isTeeBrokenMode = f?.exists() == true
        Logger.i("TEE broken mode is ${if (isTeeBrokenMode) "enabled" else "disabled"}")
    }

    private fun updateTelephony(f: File?) {
        isTelephonyEnabled = f?.exists() == true
        Logger.i("Telephony is ${if (isTelephonyEnabled) "enabled" else "disabled"} (file=${f?.absolutePath}, exists=${f?.exists()})")
    }

    @Volatile
    private var buildVars: Map<String, String> = emptyMap()

    @Volatile
    private var attestationIds: Map<String, ByteArray> = emptyMap()
    private const val MAX_BUILD_VARS_BYTES = 1024 * 1024L
    private const val MAX_BUILD_VAR_ENTRIES = 512
    private const val MAX_BUILD_VAR_VALUE_LENGTH = 512

    // Cache string to ByteArray conversions to prevent massive allocations during attestation requests
    private val stringToBytesCache = ConcurrentHashMap<String, ByteArray>()

    fun getAttestationId(tag: String): ByteArray? = attestationIds[tag]

    fun getAttestationId(
        tag: String,
        uid: Int,
    ): ByteArray? {
        // Explicit attestation-ID overrides take precedence over template values.
        val global = attestationIds[tag]
        if (global != null) return global

        // Template/global values are limited to fields consumed by the KeyMint interceptor.
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
        setOf("BRAND", "DEVICE", "PRODUCT", "MANUFACTURER", "MODEL")

    internal fun updateCustomTemplates(f: File?) =
        runCatching {
            val newTemplates = LinkedHashMap<String, Map<String, String>>()
            DeviceTemplateManager.listTemplates().forEach {
                newTemplates[it.id.lowercase()] = it.toPropMap()
            }

            if (f != null && f.exists()) {
                require(Files.isRegularFile(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    "custom_templates must be a regular file"
                }
                require(f.length() in 1..MAX_CUSTOM_TEMPLATE_BYTES) { "custom_templates has an invalid size" }
                var currentTemplate: String? = null
                var currentProps: MutableMap<String, String>? = null
                var sectionCount = 0

                fun commitCurrent() {
                    val name = currentTemplate ?: return
                    val properties = currentProps ?: return
                    newTemplates[name] = properties.toMap()
                }

                f.useLines { lines ->
                    lines.forEach { line ->
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
                }
                commitCurrent()
            }
            templates = newTemplates
            stringToBytesCache.clear()
            CertHack.clearCertificateCache()
            Logger.i("Updated templates: ${templates.keys}")
        }.onFailure {
            Logger.e("failed to update custom templates", it)
        }

    fun getTemplateNames(): Set<String> {
        return templates.keys
    }

    fun getTemplate(name: String): Map<String, String>? {
        return templates[name.lowercase()]
    }

    fun getBuildVar(key: String): String? = buildVars[key]

    fun getBuildVar(
        key: String,
        uid: Int,
    ): String? {
        val appConfig = getAppConfig(uid)
        val template = if (appConfig?.template != null) templates[appConfig.template] else null

        return template?.get(key) ?: buildVars[key]
    }

    private val supportedBuildVarKeys =
        supportedTemplateProperties +
            setOf(
                "TEMPLATE",
                "SERIAL",
                "IMEI",
                "MEID",
                "MODULE_HASH",
                "ATTESTATION_ID_BRAND",
                "ATTESTATION_ID_DEVICE",
                "ATTESTATION_ID_PRODUCT",
                "ATTESTATION_ID_SERIAL",
                "ATTESTATION_ID_IMEI",
                "ATTESTATION_ID_IMEI2",
                "ATTESTATION_ID_IMSI",
                "ATTESTATION_ID_ICCID",
                "ATTESTATION_ID_MEID",
                "ATTESTATION_ID_MANUFACTURER",
                "ATTESTATION_ID_MODEL",
                "ATTESTATION_ID_PHONE_NUMBER",
            )

    internal fun isValidBuildVarEntry(
        key: String,
        value: String,
    ): Boolean {
        if (key !in supportedBuildVarKeys || value.isEmpty() || value.length > MAX_BUILD_VAR_VALUE_LENGTH) {
            return false
        }
        if (value.any(Char::isISOControl)) return false
        if (key == "TEMPLATE") return value.length <= 64 && templates.containsKey(value.lowercase())
        if (key == "MODULE_HASH") return value.length == 64 && value.all { it.digitToIntOrNull(16) != null }

        val identifier = key.removePrefix("ATTESTATION_ID_")
        return when (identifier) {
            "IMEI", "IMEI2" -> value.length == 15 && value.all(Char::isDigit) && isValidLuhn(value)
            "IMSI" -> value.length in 5..16 && value.all(Char::isDigit)
            "ICCID" -> value.length in 18..22 && value.all(Char::isDigit) && isValidLuhn(value)
            "MEID" -> value.length == 14 && value.all { it.digitToIntOrNull(16) != null }
            "PHONE_NUMBER" -> {
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
            val newVars = mutableMapOf<String, String>()
            val newIds = mutableMapOf<String, ByteArray>()
            if (f?.exists() == true) {
                require(Files.isRegularFile(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    "spoof_build_vars must be a regular file"
                }
                require(f.length() in 0..MAX_BUILD_VARS_BYTES) { "spoof_build_vars has an invalid size" }
            }
            f?.takeIf(File::exists)?.useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank() && !line.startsWith("#")) {
                        val eqIdx = line.indexOf('=')
                        if (eqIdx != -1) {
                            val key = line.substring(0, eqIdx).trim()
                            val value = line.substring(eqIdx + 1).trim()
                            require(isValidBuildVarEntry(key, value)) { "Unsupported or invalid build variable" }
                            require(newVars.size < MAX_BUILD_VAR_ENTRIES || newVars.containsKey(key)) {
                                "Too many build variables"
                            }
                            if (key == "TEMPLATE") {
                                val template =
                                    templates[value.lowercase()]
                                        ?: throw IllegalArgumentException("Unknown template")
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
            }
            val parsedModuleHash =
                newVars["MODULE_HASH"]?.let { value ->
                    require(value.length == 64) { "MODULE_HASH must be a SHA-256 digest" }
                    value.hexToByteArray().also { require(it.size == 32) }
                }
            buildVars = newVars
            attestationIds = newIds
            moduleHashFromVars = parsedModuleHash
            stringToBytesCache.clear()

            CertHack.clearCertificateCache()
            Logger.i { "update build vars (keys): ${buildVars.keys}, attestation ids: ${attestationIds.keys}" }
        }.onFailure {
            Logger.e("failed to update build vars", it)
        }

    private class SecurityPatchState(
        val patches: Map<String, Any>,
        val defaultPatch: Any?,
    ) {
        val cache = ConcurrentHashMap<Int, Any>()
    }

    private val NULL_PATCH = Any()

    @Volatile
    private var securityPatchState = SecurityPatchState(emptyMap(), null)

    // Cache for dynamic patch levels (e.g. "today", "YYYY-MM-DD")
    // Key: Template String, Value: Pair(Timestamp, CalculatedLevel)
    private val dynamicPatchCache = ConcurrentHashMap<String, Pair<Long, Int>>()
    private const val DYNAMIC_PATCH_TTL = 3600 * 1000L // 1 hour
    private const val MAX_SECURITY_PATCH_BYTES = 1024 * 1024L
    private const val MAX_SECURITY_PATCH_RULES = 512
    private val validSecurityPatchTarget = Regex("[A-Za-z0-9_.*]{1,255}")

    private fun parsePatchSetting(value: String): Any? {
        if (value.equals("today", ignoreCase = true)) return "today"
        if (value in setOf("YYYY-MM-DD", "YYYY-MM", "YYYYMMDD", "YYYYMM")) return value
        return runCatching { value.convertPatchLevel(false) }
            .onFailure { Logger.w("Ignoring invalid security patch setting") }
            .getOrNull()
    }

    fun getPatchLevel(callingUid: Int): Int {
        val defaultLevel = patchLevel
        val state = securityPatchState
        val cached = state.cache[callingUid]

        val patchVal =
            if (cached != null) {
                if (cached === NULL_PATCH) null else cached
            } else {
                val patches = state.patches
                val found =
                    if (patches.isNotEmpty()) {
                        // Use cached getPackages to avoid expensive IPC call
                        val pkgs = getPackages(callingUid)
                        var f: Any? = null
                        val len = pkgs.size
                        for (i in 0 until len) {
                            f = patches[pkgs[i]]
                            if (f != null) break
                        }
                        f ?: state.defaultPatch
                    } else {
                        state.defaultPatch
                    }
                putBoundedUidCache(state.cache, callingUid, found ?: NULL_PATCH)
                found
            }

        if (patchVal == null) return defaultLevel

        if (patchVal is Int) return patchVal

        val patchStr = patchVal as String

        // Optimization: Check cache for dynamic strings to avoid expensive date/string operations
        val nowMs = clockSource()
        val cachedDyn = dynamicPatchCache[patchStr]
        if (cachedDyn != null && (nowMs - cachedDyn.first) < DYNAMIC_PATCH_TTL) {
            return cachedDyn.second
        }

        val now = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val effectiveDate =
            if (patchStr.equals("today", ignoreCase = true)) {
                now.toString()
            } else {
                patchStr
                    .replace("YYYY", String.format("%04d", now.year))
                    .replace("MM", String.format("%02d", now.monthValue))
                    .replace("DD", String.format("%02d", now.dayOfMonth))
            }

        val result = effectiveDate.convertPatchLevel(false)
        dynamicPatchCache[patchStr] = nowMs to result
        return result
    }

    private fun updateSecurityPatch(f: File?) =
        runCatching {
            val newPatch = mutableMapOf<String, Any>()
            var newDefault: Any? = null
            if (f != null) {
                require(Files.isRegularFile(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    "security_patch.txt must be a regular file"
                }
                require(f.length() in 0..MAX_SECURITY_PATCH_BYTES) { "security_patch.txt has an invalid size" }
            }
            f?.useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val eqIdx = trimmed.indexOf('=')
                        if (eqIdx != -1) {
                            val key = trimmed.substring(0, eqIdx).trim()
                            val value = trimmed.substring(eqIdx + 1).trim()
                            require(validSecurityPatchTarget.matches(key)) { "Invalid security patch target" }
                            require(newPatch.size < MAX_SECURITY_PATCH_RULES || newPatch.containsKey(key)) {
                                "Too many security patch rules"
                            }
                            val parsed =
                                parsePatchSetting(value)
                                    ?: throw IllegalArgumentException("Invalid security patch setting")
                            newPatch[key] = parsed
                        } else {
                            newDefault = parsePatchSetting(trimmed)
                                ?: throw IllegalArgumentException("Invalid default security patch setting")
                        }
                    }
                }
            }
            securityPatchState = SecurityPatchState(newPatch, newDefault)
            dynamicPatchCache.clear()
            CertHack.clearCertificateCache()
            Logger.i { "update security patch: default=$newDefault, per-app=${newPatch.size}" }
        }.onFailure {
            Logger.e("failed to update security patch", it)
        }

    @OptIn(ExperimentalStdlibApi::class)
    private val hexFormat = HexFormat { upperCase = false }

    @OptIn(ExperimentalStdlibApi::class)
    private fun updateModuleHash(f: File?) =
        runCatching {
            moduleHash = f?.readText()?.trim()?.hexToByteArray()
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
    private const val KEYBOX_FILE = "keybox.xml"
    private const val GLOBAL_MODE_FILE = "global_mode"
    private const val TEE_BROKEN_MODE_FILE = "tee_broken_mode"
    private const val TELEPHONY_FILE = "telephony"
    private const val SPOOF_BUILD_VARS_FILE = "spoof_build_vars"
    private const val MODULE_HASH_FILE = "module_hash"
    private const val SECURITY_PATCH_FILE = "security_patch.txt"
    private const val APP_CONFIG_FILE = "app_config"
    private const val CUSTOM_TEMPLATES_FILE = "custom_templates"
    private const val TEMPLATES_JSON_FILE = "templates.json"
    private const val RANDOM_ON_BOOT_FILE = "random_on_boot"
    private const val AUTO_KEYBOX_CHECK_FILE = "auto_keybox_check"
    private const val APPLY_PROFILE_FILE = "apply_profile"
    private var root = File(CONFIG_PATH)
    private val keyboxDir get() = File(root, KEYBOX_DIR)

    val keyboxDirectory: File get() = keyboxDir

    @androidx.annotation.VisibleForTesting
    fun setRootForTesting(newRoot: File) {
        root = newRoot
    }

    @androidx.annotation.VisibleForTesting
    internal fun getConfigRoot(): File = root

    private val packageListLock = Any()

    @Volatile
    private var cachedPackageList: List<String>? = null

    @Volatile
    private var lastPackageFetchTime: Long = 0
    private const val PACKAGE_CACHE_TTL = 30_000L // 30 seconds

    /**
     * Retrieves the complete list of installed packages, using a TTL cache to avoid
     * frequent, costly IPC and serialization overhead from IPackageManager.
     */
    fun getInstalledPackages(): List<String> {
        val now = clockSource()
        val cached = cachedPackageList
        if (cached != null && (now - lastPackageFetchTime) < PACKAGE_CACHE_TTL) {
            return cached
        }

        return synchronized(packageListLock) {
            val doubleCheck = cachedPackageList
            if (doubleCheck != null && (now - lastPackageFetchTime) < PACKAGE_CACHE_TTL) {
                doubleCheck
            } else {
                val pm = getPm()
                val packages =
                    if (pm != null) {
                        try {
                            try {
                                pm.getInstalledPackages(0L, 0).list.map { it.packageName }
                            } catch (e: NoSuchMethodError) {
                                pm.getInstalledPackages(0, 0).list.map { it.packageName }
                            }
                        } catch (t: Throwable) {
                            Logger.e("Failed to list packages via IPC", t)
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }

                val sortedPackages = packages.sorted()
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
                if (tmp.length() !in 1..64) throw IOException("Invalid profile request size")
                val profileName =
                    tmp.bufferedReader().use { reader ->
                        val firstLine = reader.readLine().orEmpty().trim()
                        if (reader.readLine() != null) throw IOException("Invalid profile request")
                        firstLine
                    }
                applyProfile(profileName)
            } finally {
                if (tmp.exists() && !tmp.delete()) Logger.w("Could not remove processed profile request")
            }
        }.onFailure {
            Logger.e("failed to apply profile from file", it)
        }

    private fun removeConfigFiles(vararg names: String) {
        names.forEach { name ->
            val file = File(root, name)
            if (Files.isSymbolicLink(file.toPath())) {
                throw IOException("Refusing to delete symbolic-link configuration: $name")
            }
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
        when (profile) {
            "maximum" -> {
                SecureFile.touch(File(root, GLOBAL_MODE_FILE), 384)
                removeConfigFiles(TEE_BROKEN_MODE_FILE, BootLogic.FILE_SPOOF_CN)
                SecureFile.touch(File(root, RANDOM_ON_BOOT_FILE), 384)
                SecureFile.touch(File(root, BootLogic.FILE_HIDE_PROPS), 384)
                SecureFile.touch(File(root, SPOOF_BUILD_VARS_FILE), 384)
                SecureFile.touch(File(root, AUTO_KEYBOX_CHECK_FILE), 384)
                SecureFile.touch(File(root, TELEPHONY_FILE), 384)
            }
            "daily" -> {
                removeConfigFiles(
                    GLOBAL_MODE_FILE,
                    TEE_BROKEN_MODE_FILE,
                    RANDOM_ON_BOOT_FILE,
                    BootLogic.FILE_SPOOF_CN,
                    TELEPHONY_FILE,
                )
                SecureFile.touch(File(root, BootLogic.FILE_HIDE_PROPS), 384)
                SecureFile.touch(File(root, SPOOF_BUILD_VARS_FILE), 384)
                SecureFile.touch(File(root, AUTO_KEYBOX_CHECK_FILE), 384)
            }
            "minimal" -> {
                SecureFile.touch(File(root, TEE_BROKEN_MODE_FILE), 384)
                removeConfigFiles(
                    GLOBAL_MODE_FILE,
                    RANDOM_ON_BOOT_FILE,
                    BootLogic.FILE_HIDE_PROPS,
                    BootLogic.FILE_SPOOF_CN,
                    AUTO_KEYBOX_CHECK_FILE,
                    TELEPHONY_FILE,
                )
            }
            "default" -> {
                removeConfigFiles(
                    GLOBAL_MODE_FILE,
                    TEE_BROKEN_MODE_FILE,
                    RANDOM_ON_BOOT_FILE,
                    BootLogic.FILE_HIDE_PROPS,
                    BootLogic.FILE_SPOOF_CN,
                    TELEPHONY_FILE,
                )
                SecureFile.touch(File(root, AUTO_KEYBOX_CHECK_FILE), 384)
            }
        }

        updateGlobalMode(File(root, GLOBAL_MODE_FILE))
        updateTeeBrokenMode(File(root, TEE_BROKEN_MODE_FILE))
        updateTelephony(File(root, TELEPHONY_FILE))
        updateBuildVars(File(root, SPOOF_BUILD_VARS_FILE))
        updateTargetPackages(File(root, TARGET_FILE))
    }

    private fun enforceRandomization() {
        try {
            val spoofFile = File(root, SPOOF_BUILD_VARS_FILE)
            val replacements =
                linkedMapOf(
                    "ATTESTATION_ID_IMEI" to RandomUtils.generateLuhn(15, "35"),
                    "ATTESTATION_ID_IMEI2" to RandomUtils.generateLuhn(15, "35"),
                    "ATTESTATION_ID_SERIAL" to RandomUtils.generateRandomSerial(12),
                    "ATTESTATION_ID_IMSI" to RandomUtils.generateDigits(15, "310260"),
                    "ATTESTATION_ID_ICCID" to RandomUtils.generateLuhn(20, "8901"),
                )
            templates.keys.randomOrNull()?.let { replacements["TEMPLATE"] = it }

            val retainedLines = mutableListOf<String>()
            if (spoofFile.isFile) {
                spoofFile.useLines { lines ->
                    lines.forEach { line ->
                        val key = line.substringBefore('=', "").trim()
                        if (key !in replacements) retainedLines += line
                    }
                }
            }
            retainedLines += "# Refreshed by random_on_boot"
            replacements.forEach { (key, value) -> retainedLines += "$key=$value" }

            SecureFile.writeText(spoofFile, retainedLines.joinToString("\n", postfix = "\n"))
            updateBuildVars(spoofFile)
            Logger.i("Refreshed attestation and telephony identifiers")
        } catch (e: Exception) {
            Logger.e("Failed to enforce randomization", e)
        }
    }

    object ConfigObserver : FileObserver(root, CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO) {
        override fun onEvent(
            event: Int,
            path: String?,
        ) {
            path ?: return
            val f =
                when (event) {
                    CLOSE_WRITE, MOVED_TO -> File(root, path)
                    DELETE, MOVED_FROM -> null
                    else -> return
                }
            when (path) {
                TARGET_FILE -> updateTargetPackages(f)
                KEYBOX_FILE -> updateKeyBoxes()
                SPOOF_BUILD_VARS_FILE -> updateBuildVars(f)
                SECURITY_PATCH_FILE -> updateSecurityPatch(f)
                APP_CONFIG_FILE -> updateAppConfigs(f)
                CUSTOM_TEMPLATES_FILE -> updateCustomTemplates(f)
                TEMPLATES_JSON_FILE -> {
                    DeviceTemplateManager.initialize(root)
                    updateCustomTemplates(File(root, CUSTOM_TEMPLATES_FILE))
                }
                GLOBAL_MODE_FILE -> {
                    updateGlobalMode(f)
                    updateTargetPackages(File(root, TARGET_FILE))
                }

                TEE_BROKEN_MODE_FILE -> {
                    updateTeeBrokenMode(f)
                    updateTargetPackages(File(root, TARGET_FILE))
                }

                TELEPHONY_FILE -> updateTelephony(f)
                MODULE_HASH_FILE -> updateModuleHash(f)

                APPLY_PROFILE_FILE -> applyProfileFromFile(f)
            }
        }
    }

    object KeyboxDirObserver : FileObserver(keyboxDir, CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO) {
        override fun onEvent(
            event: Int,
            path: String?,
        ) {
            Logger.i("Keybox directory event: $path")
            updateKeyBoxes()
        }
    }

    fun initialize() {
        Logger.i("Config.initialize: starting (root=${root.absolutePath})")
        SecureFile.mkdirs(root, 448) // 0700
        SecureFile.mkdirs(keyboxDir, 448) // 0700
        DeviceKeyManager.initialize(root)
        CboxManager.initialize()
        ServerManager.initialize()
        DeviceTemplateManager.initialize(root)

        updateGlobalMode(File(root, GLOBAL_MODE_FILE))
        updateTeeBrokenMode(File(root, TEE_BROKEN_MODE_FILE))
        updateTelephony(File(root, TELEPHONY_FILE))
        updateBuildVars(File(root, SPOOF_BUILD_VARS_FILE))
        updateModuleHash(File(root, MODULE_HASH_FILE))
        updateSecurityPatch(File(root, SECURITY_PATCH_FILE))
        updateAppConfigs(File(root, APP_CONFIG_FILE))
        updateCustomTemplates(File(root, CUSTOM_TEMPLATES_FILE))

        if (File(root, RANDOM_ON_BOOT_FILE).isFile) {
            enforceRandomization()
        }
        updateBuildVars(File(root, SPOOF_BUILD_VARS_FILE))

        if (!isGlobalMode) {
            val scope = File(root, TARGET_FILE)
            Logger.d("Config.initialize: loading target.txt from ${scope.absolutePath} (exists=${scope.exists()})")
            if (scope.exists()) {
                updateTargetPackages(scope)
            } else {
                Logger.e("target.txt file not found, please put it to $scope !")
            }
        } else {
            Logger.i("Config.initialize: global mode active; all application UIDs are targeted")
            updateTargetPackages(File(root, TARGET_FILE))
        }

        updateKeyBoxesSync()

        ConfigObserver.startWatching()
        KeyboxDirObserver.startWatching()
        keyboxPoller?.stop()
        keyboxPoller =
            FilePoller(File(root, KEYBOX_FILE), 5000) {
                Logger.i("Detected keybox change via polling")
                updateKeyBoxes()
            }
        keyboxPoller?.start()
    }

    private var iPm: IPackageManager? = null

    fun getPm(): IPackageManager? {
        if (iPm == null) {
            iPm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"))
        }
        return iPm
    }

    internal fun matchesPackage(
        pkgName: String,
        rules: PackageTrie<Boolean>,
    ): Boolean {
        return rules.matches(pkgName)
    }

    internal data class CachedPackage(val value: Array<String>, val timestamp: Long)

    // Cache PackageManager IPC results while using a fixed lock stripe set so isolated-UID
    // churn cannot grow a second, unbounded lock map.
    private val packageCache = ConcurrentHashMap<Int, CachedPackage>()
    private val uidLocks = Array(64) { Any() }

    internal var clockSource: () -> Long = { System.currentTimeMillis() }
    private const val CACHE_TTL_MS = 60 * 1000L // 1 minute

    /**
     * Retrieves the list of packages for a given UID, using a cache to avoid frequent IPC calls.
     * Returns an empty array if the UID has no associated packages or if PackageManager is unavailable.
     */
    fun getPackages(uid: Int): Array<String> {
        val now = clockSource()
        // Fast path: optimistic read for valid cache
        val cached = packageCache[uid]
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            return cached.value
        }

        // Slow path: update atomically to prevent "thundering herd" on IPC
        // Use a per-UID lock to avoid holding the global map bucket lock during the slow IPC
        val lock = uidLocks[(uid and Int.MAX_VALUE) % uidLocks.size]
        synchronized(lock) {
            val current = packageCache[uid]
            if (current != null && (now - current.timestamp) < CACHE_TTL_MS) {
                return current.value
            }

            val pm = getPm()
            return if (pm == null) {
                emptyArray()
            } else {
                val pkgs = pm.getPackagesForUid(uid) ?: emptyArray()
                putBoundedUidCache(packageCache, uid, CachedPackage(pkgs, now))
                pkgs
            }
        }
    }

    private fun checkPackages(
        packages: PackageTrie<Boolean>,
        callingUid: Int,
    ): Boolean {
        try {
            if (packages.isEmpty()) return false
            val ps = getPackages(callingUid)
            if (ps.isEmpty()) return false
            val len = ps.size
            for (i in 0 until len) {
                if (matchesPackage(ps[i], packages)) return true
            }
            return false
        } catch (e: Exception) {
            Logger.e("failed to get packages", e)
            return false
        }
    }

    fun needHack(callingUid: Int): Boolean {
        if (isTeeBrokenMode) return false
        if (isGlobalMode) return true

        val state = targetState
        val cached = state.hackCache[callingUid]
        if (cached != null) return cached

        val result = checkPackages(state.hackPackages, callingUid)
        putBoundedUidCache(state.hackCache, callingUid, result)
        return result
    }

    @androidx.annotation.VisibleForTesting
    fun reset() {
        ConfigObserver.stopWatching()
        KeyboxDirObserver.stopWatching()
        keyboxPoller?.stop()
        scope.coroutineContext.cancelChildren()

        root = File(CONFIG_PATH)
        packageCache.clear()
        dynamicPatchCache.clear()
        securityPatchState = SecurityPatchState(emptyMap(), null)
        iPm = null
        appConfigState = AppConfigState(PackageTrie())
        targetState = TargetState(PackageTrie())
        buildVars = emptyMap()
        attestationIds = emptyMap()
        stringToBytesCache.clear()
        templates = emptyMap()
        moduleHash = null
        moduleHashFromVars = null
        cachedPackageList = null
        lastPackageFetchTime = 0
        isGlobalMode = false
        isTeeBrokenMode = false
        isTelephonyEnabled = false
        clockSource = { System.currentTimeMillis() }
        cachedLegacyKeyboxes = emptyList()
        lastKeyboxModified = 0
        lastKeyboxLength = 0
        directoryKeyboxCache.clear()
    }
}
