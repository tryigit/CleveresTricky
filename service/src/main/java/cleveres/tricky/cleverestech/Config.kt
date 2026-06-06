package cleveres.tricky.cleverestech

import android.content.pm.IPackageManager
import android.os.FileObserver
import android.os.ServiceManager
import android.system.Os
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.DeviceKeyManager
import cleveres.tricky.cleverestech.util.PackageTrie
import cleveres.tricky.cleverestech.util.RandomUtils
import cleveres.tricky.cleverestech.util.SecureFile
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

object Config {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val spoofedProperties = mapOf(
        "ro.boot.verifiedbootstate" to "green",
        "ro.boot.flash.locked" to "1",
        "ro.boot.veritymode" to "enforcing",
        "ro.boot.vbmeta.device_state" to "locked",
        "ro.boot.warranty_bit" to "0",
        "ro.secure" to "1",
        "ro.debuggable" to "0",
        "ro.oem_unlock_supported" to "0"
    )

    data class AppSpoofConfig(val template: String?, val keyboxFilename: String?, val permissions: Set<String> = emptySet())

    // Optimization: Cache results of needHack/needGenerate to avoid repeated Trie lookups.
    // The cache is bundled with the Trie in a state object to ensure consistency during updates.
    private class TargetState(
        val hackPackages: PackageTrie<Boolean>,
        val generatePackages: PackageTrie<Boolean>
    ) {
        val hackCache = ConcurrentHashMap<Int, Boolean>()
        val generateCache = ConcurrentHashMap<Int, Boolean>()
    }

    @Volatile
    private var targetState = TargetState(PackageTrie(), PackageTrie())
    @Volatile
    private var isGlobalMode = false
    @Volatile
    private var isTeeBrokenMode = false
    @Volatile
    private var isAutoTeeBroken = false
    private val isTeeBroken get() = isTeeBrokenMode || isAutoTeeBroken
    @Volatile
    private var moduleHash: ByteArray? = null
    @Volatile
    private var isRkpBypass = false
    @Volatile
    private var isSpoofBuild = true
    @Volatile
    private var isSpoofBuildPs = true
    @Volatile
    private var isSpoofProps = false
    @Volatile
    private var isSpoofProvider = false
    @Volatile
    private var isSpoofSignature = false
    @Volatile
    private var isSpoofSdkPs = false
    @Volatile
    private var moduleHashFromVars: ByteArray? = null

    // Optimization: Cache results of getAppConfig to avoid repeated Trie lookups.
    // The cache is bundled with the Trie in a state object to ensure consistency during updates.
    private class AppConfigState(
        val configs: PackageTrie<AppSpoofConfig>
    ) {
        val cache = ConcurrentHashMap<Int, Any>()
    }

    private val NULL_CONFIG = Any()

    @Volatile
    private var appConfigState = AppConfigState(PackageTrie())

    fun shouldBypassRkp() = isRkpBypass

    fun isDrmFixEnabled() = drmFixVars.isNotEmpty()

    fun isSpoofBuild() = isSpoofBuild
    fun isSpoofBuildPs() = isSpoofBuildPs
    fun isSpoofProps() = isSpoofProps
    fun isSpoofProvider() = isSpoofProvider
    fun isSpoofSignature() = isSpoofSignature
    fun isSpoofSdkPs() = isSpoofSdkPs

    fun setTeeBroken(broken: Boolean) {
        isAutoTeeBroken = broken
        Logger.i("Auto TEE broken mode is ${if (isAutoTeeBroken) "enabled" else "disabled"}")
    }

    fun getModuleHash(): ByteArray? = moduleHash ?: moduleHashFromVars

    fun getAppConfig(uid: Int): AppSpoofConfig? {
        val state = appConfigState
        val cached = state.cache[uid]
        if (cached != null) {
            return if (cached === NULL_CONFIG) null else cached as AppSpoofConfig
        }

        var result: AppSpoofConfig? = null
        if (!state.configs.isEmpty()) {
            val pkgs = getPackages(uid)
            for (pkg in pkgs) {
                val config = state.configs.get(pkg)
                if (config != null) {
                    result = config
                    break
                }
            }
        }
        state.cache[uid] = result ?: NULL_CONFIG
        return result
    }

    private fun updateAppConfigs(f: File?) = runCatching {
        val newConfigs = PackageTrie<AppSpoofConfig>()
        f?.useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEach

                    // OPTIMIZATION: Replaced String.split(Regex) with manual index-based parsing
                    // to avoid intermediate List and String allocations during high-frequency file reads.
                    val len = trimmed.length
                    var idx = 0

                    // parse pkg
                    var start = idx
                    while (idx < len && !trimmed[idx].isWhitespace()) idx++
                    val pkg = trimmed.substring(start, idx)

                    var template: String? = null
                    var keybox: String? = null
                    val permissions = HashSet<String>()

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

                            // parse permissions
                            while (idx < len && trimmed[idx].isWhitespace()) idx++
                            if (idx < len) {
                                start = idx
                                while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                val permStr = trimmed.substring(start, idx)
                                if (permStr != "null") {
                                    var pStart = 0
                                    val pLen = permStr.length
                                    while (pStart < pLen) {
                                        var pEnd = permStr.indexOf(',', pStart)
                                        if (pEnd == -1) pEnd = pLen
                                        val part = permStr.substring(pStart, pEnd)
                                        if (part.isNotBlank()) permissions.add(part.trim())
                                        pStart = pEnd + 1
                                    }
                                }
                            }
                        }
                    }

                    if (template != null || keybox != null || permissions.isNotEmpty()) {
                        newConfigs.add(pkg, AppSpoofConfig(template, keybox, permissions))
                    }
                }
            }
        }
        appConfigState = AppConfigState(newConfigs)
        Logger.i { "update app configs: ${newConfigs.size}" }
    }.onFailure {
        Logger.e("failed to update app configs", it)
    }

    fun parsePackages(lines: Sequence<String>, isTeeBrokenMode: Boolean): Pair<PackageTrie<Boolean>, PackageTrie<Boolean>> {
        val hackPackages = PackageTrie<Boolean>()
        val generatePackages = PackageTrie<Boolean>()
        lines.forEach {
            if (it.isNotBlank() && !it.startsWith("#")) {
                val n = it.trim()
                if (isTeeBrokenMode || n.endsWith("!"))
                    generatePackages.add(
                        n.removeSuffix("!").trim(), true
                    )
                else hackPackages.add(n, true)
            }
        }
        return hackPackages to generatePackages
    }

    private fun updateTargetPackages(f: File?) = runCatching {
        if (isGlobalMode) {
            targetState = TargetState(PackageTrie(), PackageTrie())
            Logger.i("Global mode is enabled, skipping updateTargetPackages execution.")
            return@runCatching
        }
        Logger.d("updateTargetPackages: reading ${f?.absolutePath} (exists=${f?.exists()})")
        val (h, g) = if (f != null && f.exists()) {
            f.useLines { parsePackages(it, isTeeBrokenMode) }
        } else {
            Logger.d("updateTargetPackages: target file missing or null, using empty package list")
            parsePackages(emptySequence(), isTeeBrokenMode)
        }
        targetState = TargetState(h, g)
        Logger.i { "update hack packages: ${h.size}, generate packages=${g.size}" }
    }.onFailure {
        Logger.e("failed to update target files", it)
    }

    private var keyboxPoller: FilePoller? = null

    @Volatile
    private var cachedLegacyKeyboxes: List<CertHack.KeyBox> = emptyList()
    @Volatile
    private var lastKeyboxModified: Long = 0

    // Cache parsed keyboxes from directory to avoid re-parsing on every update
    // Key: filename, Value: Pair(lastModified, List<KeyBox>)
    private val directoryKeyboxCache = ConcurrentHashMap<String, Pair<Long, List<CertHack.KeyBox>>>()

    fun updateKeyBoxes() = scope.launch {
        updateKeyBoxesSync()
    }

    /**
     * Performs a synchronous update of the keyboxes.
     * This avoids runBlocking overhead when called from synchronous handlers.
     */
    fun updateKeyBoxesSync() {
        runCatching {
            Logger.d("updateKeyBoxes: starting keybox scan (root=${root.absolutePath})")
            val allKeyboxes = ArrayList<CertHack.KeyBox>()

            // 1. Legacy keybox.xml
            val legacyFile = File(root, KEYBOX_FILE)
            Logger.d("updateKeyBoxes: checking legacy ${legacyFile.absolutePath} (exists=${legacyFile.exists()})")
            if (legacyFile.exists()) {
                val currentModified = legacyFile.lastModified()
                // Optimization: Cache parsed keybox.xml data in memory to avoid disk I/O
                if (currentModified > lastKeyboxModified || cachedLegacyKeyboxes.isEmpty()) {
                    legacyFile.bufferedReader().use { reader ->
                        cachedLegacyKeyboxes = CertHack.parseKeyboxXml(reader, KEYBOX_FILE)
                    }
                    lastKeyboxModified = currentModified
                    Logger.i("Reloaded keybox.xml (modified: $currentModified, keys: ${cachedLegacyKeyboxes.size})")
                }
                allKeyboxes.addAll(cachedLegacyKeyboxes)
            } else {
                Logger.d("updateKeyBoxes: legacy keybox.xml not found at ${legacyFile.absolutePath}")
                cachedLegacyKeyboxes = emptyList()
                lastKeyboxModified = 0
            }

            // 2. Directory files (Plain XML)
            if (keyboxDir.exists() && keyboxDir.isDirectory) {
                val files = keyboxDir.listFiles { _, name -> name.endsWith(".xml") }
                Logger.d("updateKeyBoxes: scanning keybox dir ${keyboxDir.absolutePath} (${files?.size ?: 0} xml files)")
                val currentFiles = HashSet<String>()

                files?.forEach { file ->
                    val filename = file.name
                    currentFiles.add(filename)
                    val lastMod = file.lastModified()

                    val cached = directoryKeyboxCache[filename]
                    if (cached != null && cached.first == lastMod) {
                        allKeyboxes.addAll(cached.second)
                    } else {
                        try {
                            file.bufferedReader().use { reader ->
                                val parsed = CertHack.parseKeyboxXml(reader, filename)
                                directoryKeyboxCache[filename] = lastMod to parsed
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

            CertHack.setKeyboxes(allKeyboxes)
            Logger.i("updateKeyBoxes: total ${allKeyboxes.size} keyboxes loaded and active")

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

    private fun updateSpoofBuild(f: File?) { isSpoofBuild = f?.exists() == true; Logger.i("Spoof Build is ${if (isSpoofBuild) "enabled" else "disabled"}") }
    private fun updateSpoofBuildPs(f: File?) { isSpoofBuildPs = f?.exists() == true; Logger.i("Spoof Build PS is ${if (isSpoofBuildPs) "enabled" else "disabled"}") }
    private fun updateSpoofProps(f: File?) { isSpoofProps = f?.exists() == true; Logger.i("Spoof Props is ${if (isSpoofProps) "enabled" else "disabled"}") }
    private fun updateSpoofProvider(f: File?) { isSpoofProvider = f?.exists() == true; Logger.i("Spoof Provider is ${if (isSpoofProvider) "enabled" else "disabled"}") }
    private fun updateSpoofSignature(f: File?) { isSpoofSignature = f?.exists() == true; Logger.i("Spoof Signature is ${if (isSpoofSignature) "enabled" else "disabled"}") }
    private fun updateSpoofSdkPs(f: File?) { isSpoofSdkPs = f?.exists() == true; Logger.i("Spoof Sdk PS is ${if (isSpoofSdkPs) "enabled" else "disabled"}") }

    private fun updateRkpBypass(f: File?) {
        val previousValue = isRkpBypass
        isRkpBypass = f?.exists() == true
        Logger.i("RKP bypass is ${if (isRkpBypass) "enabled" else "disabled"} (file=${f?.absolutePath}, exists=${f?.exists()})")
        if (previousValue != isRkpBypass) {
            Logger.i("RKP bypass state changed: $previousValue -> $isRkpBypass")
        }
    }

    @Volatile
    private var buildVars: Map<String, String> = emptyMap()
    @Volatile
    private var drmFixVars: Map<String, String> = emptyMap()
    @Volatile
    private var attestationIds: Map<String, ByteArray> = emptyMap()

    // Cache string to ByteArray conversions to prevent massive allocations during attestation requests
    private val stringToBytesCache = ConcurrentHashMap<String, ByteArray>()

    fun getAttestationId(tag: String): ByteArray? = attestationIds[tag]

    fun getAttestationId(tag: String, uid: Int): ByteArray? {
        // 1. Explicit override (highest priority)
        val global = attestationIds[tag]
        if (global != null) return global

        // 2. Smart Fallback (Build Var via Template or Global)
        // This leverages getBuildVar which handles "Smart Property Mapping" for templates
        // and falls back to global build vars.
        val value = getBuildVar(tag, uid) ?: return null
        return stringToBytesCache.getOrPut(value) { value.toByteArray(Charsets.UTF_8) }
    }

    @Volatile
    private var templates: Map<String, Map<String, String>> = emptyMap()

    internal fun updateCustomTemplates(f: File?) = runCatching {
        // 1. Get base templates from Manager (JSON)
        val newTemplates = LinkedHashMap<String, Map<String, String>>()
        DeviceTemplateManager.listTemplates().forEach {
            newTemplates[it.id.lowercase()] = it.toPropMap()
        }

        // 2. Override/Extend with custom_templates file (INI format)
        if (f != null && f.exists()) {
             var currentTemplate: String? = null
             var currentProps: MutableMap<String, String>? = null

             f.useLines { lines ->
                 lines.forEach { line ->
                     val l = line.trim()
                     if (l.isEmpty() || l.startsWith("#")) return@forEach

                     if (l.startsWith("[") && l.endsWith("]")) {
                         // Save previous
                         if (currentTemplate != null && currentProps != null) {
                             newTemplates[currentTemplate!!] = currentProps!!
                         }
                         currentTemplate = l.substring(1, l.length - 1).lowercase()
                         // Extend existing or create new
                         currentProps = newTemplates[currentTemplate]?.toMutableMap() ?: HashMap()
                     } else if (currentTemplate != null) {
                         // OPTIMIZATION: Replaced split("=", limit = 2) with indexOf('=') to avoid array allocations
                         val eqIdx = l.indexOf('=')
                         if (eqIdx != -1) {
                             currentProps?.put(l.substring(0, eqIdx).trim(), l.substring(eqIdx + 1).trim())
                         }
                     }
                 }
             }
             // Save last
             if (currentTemplate != null && currentProps != null) {
                newTemplates[currentTemplate] = currentProps
             }
        }
        templates = newTemplates
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

    private const val NULL_TEMPLATE_KEY = "__NULL__"
    // OPTIMIZATION: Cache template key lookups to avoid repeated string suffix checks (approx 12 checks per call).
    // This reduces CPU overhead for high-frequency property access.
    private val templateKeyCache = ConcurrentHashMap<String, String>()

    private fun getTemplateKey(key: String): String? {
        val cached = templateKeyCache[key]
        if (cached != null) {
            return if (cached == NULL_TEMPLATE_KEY) null else cached
        }

        val computed = computeTemplateKey(key)
        templateKeyCache[key] = computed ?: NULL_TEMPLATE_KEY
        return computed
    }

    private fun computeTemplateKey(key: String): String? {
        return when {
            // Codename (must be before 'name' check)
            key.endsWith("version.codename") -> "CODENAME"
            // Fingerprint
            key.endsWith("fingerprint") -> "FINGERPRINT"
            // Security Patch
            key.endsWith("security_patch") -> "SECURITY_PATCH"
            // Model
            key.endsWith("model") -> "MODEL"
            // Brand
            key.endsWith("brand") -> "BRAND"
            // Manufacturer
            key.endsWith("manufacturer") -> "MANUFACTURER"
            // Device
            key.endsWith("device") -> "DEVICE"
            // Product
            key.endsWith("product") || key.endsWith("name") -> "PRODUCT"
            // ID
            key.endsWith("build.id") -> "ID"
            key.endsWith("display.id") -> "DISPLAY"
            // Release
            key.endsWith("version.release") || key.endsWith("version.release_or_codename") -> "RELEASE"
            // Incremental
            key.endsWith("version.incremental") -> "INCREMENTAL"
            // Type
            key.endsWith("build.type") -> "TYPE"
            // Tags
            key.endsWith("build.tags") -> "TAGS"
            // Bootloader
            key.endsWith("bootloader") -> "BOOTLOADER"
            // Board
            key.endsWith("board") || key.endsWith("platform") -> "BOARD"
            // Hardware
            key.endsWith("hardware") -> "HARDWARE"
            // Host
            key.endsWith("host") -> "HOST"
            // User
            key.endsWith("user") -> "USER"
            // Timestamp
            key.endsWith("date.utc") -> "TIMESTAMP"
            // SDK
            key.endsWith("version.sdk") -> "SDK_INT"
            key.endsWith("preview_sdk") -> "PREVIEW_SDK"
            else -> null
        }
    }

    fun getBuildVar(key: String): String? {
        drmFixVars[key]?.let { return it }
        buildVars[key]?.let { return it }

        val templateKey = getTemplateKey(key)
        if (templateKey != null) {
            buildVars[templateKey]?.let { return it }
            if (templateKey == "DISPLAY") {
                buildVars["ID"]?.let { return it }
            }
        }

        return spoofedProperties[key]
    }

    fun getBuildVar(key: String, uid: Int): String? {
        val appConfig = getAppConfig(uid)
        val template = if (appConfig?.template != null) templates[appConfig.template] else null

        if (template != null) {
            // 1. Direct match in template
            if (template.containsKey(key)) {
                return template[key]
            }
            // 2. Smart mapping to template keys
            val templateKey = getTemplateKey(key)
            if (templateKey != null && template.containsKey(templateKey)) {
                return template[templateKey]
            }
            if (templateKey == "DISPLAY" && template.containsKey("ID")) {
                return template["ID"]
            }
        }

        // 3. DRM Fix Properties
        drmFixVars[key]?.let { return it }

        // 4. Global build vars (including global template mapping)
        buildVars[key]?.let { return it }

        val templateKey = getTemplateKey(key)
        if (templateKey != null) {
            buildVars[templateKey]?.let { return it }
            if (templateKey == "DISPLAY") {
                buildVars["ID"]?.let { return it }
            }
        }

        // 5. Default spoofed properties
        return spoofedProperties[key]
    }

    internal fun updateDrmFix(f: File?) = runCatching {
        val newVars = mutableMapOf<String, String>()
        f?.useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val eqIdx = line.indexOf('=')
                    if (eqIdx != -1) {
                        newVars[line.substring(0, eqIdx).trim()] = line.substring(eqIdx + 1).trim()
                    }
                }
            }
        }
        drmFixVars = newVars
        Logger.i("update drm fix vars: $drmFixVars")
    }.onFailure {
        Logger.e("failed to update drm fix vars", it)
    }

    @OptIn(ExperimentalStdlibApi::class)
    internal fun updateBuildVars(f: File?) = runCatching {
        val newVars = mutableMapOf<String, String>()
        val newIds = mutableMapOf<String, ByteArray>()
        f?.useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val eqIdx = line.indexOf('=')
                    if (eqIdx != -1) {
                        val key = line.substring(0, eqIdx).trim()
                        val value = line.substring(eqIdx + 1).trim()
                        if (key == "TEMPLATE") {
                            templates[value.lowercase()]?.let { newVars.putAll(it) }
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
        buildVars = newVars
        attestationIds = newIds

        // Optimize: Cache MODULE_HASH directly to avoid repeated parsing
        val mh = newVars["MODULE_HASH"]
        if (mh != null) {
             try {
                 moduleHashFromVars = mh.hexToByteArray()
             } catch (e: Exception) {
                 Logger.e("Failed to parse MODULE_HASH from vars", e)
                 moduleHashFromVars = null
             }
        } else {
            moduleHashFromVars = null
        }

        Logger.i { "update build vars: $buildVars, attestation ids: ${attestationIds.keys}" }
    }.onFailure {
        Logger.e("failed to update build vars", it)
    }

    private class SecurityPatchState(
        val patches: Map<String, Any>,
        val defaultPatch: Any?
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

    private fun calculateDynamicPatchLevel(patchStr: String): Int {
        val nowMs = clockSource()
        val cachedDyn = dynamicPatchCache[patchStr]
        if (cachedDyn != null && (nowMs - cachedDyn.first) < DYNAMIC_PATCH_TTL) {
            return cachedDyn.second
        }

        val effectiveDate = if (patchStr.equals("today", ignoreCase = true)) {
            Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        } else if (patchStr.contains("YYYY") || patchStr.contains("MM") || patchStr.contains("DD")) {
             val now = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
             patchStr.replace("YYYY", String.format("%04d", now.year))
                     .replace("MM", String.format("%02d", now.monthValue))
                     .replace("DD", String.format("%02d", now.dayOfMonth))
        } else {
            patchStr
        }

        val result = effectiveDate.convertPatchLevel(false)
        dynamicPatchCache[patchStr] = nowMs to result
        return result
    }

    fun getPatchLevel(callingUid: Int): Int {
        val defaultLevel = patchLevel
        val state = securityPatchState
        val cached = state.cache[callingUid]

        if (cached != null) {
            val patchVal = if (cached === NULL_PATCH) null else cached
            if (patchVal == null) return defaultLevel
            if (patchVal is Int) return patchVal
            return calculateDynamicPatchLevel(patchVal as String)
        }

        val patches = state.patches
        val found = if (patches.isNotEmpty()) {
            // Use cached getPackages to avoid expensive IPC call
            val pkgs = getPackages(callingUid)
            var f: Any? = null
            for (pkg in pkgs) {
                f = patches[pkg]
                if (f != null) break
            }
            f ?: state.defaultPatch
        } else {
            state.defaultPatch
        }
        state.cache[callingUid] = found ?: NULL_PATCH

        if (found == null) return defaultLevel
        if (found is Int) return found
        return calculateDynamicPatchLevel(found as String)
    }

    private fun updateSecurityPatch(f: File?) = runCatching {
        val newPatch = mutableMapOf<String, Any>()
        var newDefault: Any? = null
        f?.useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val eqIdx = line.indexOf('=')
                    if (eqIdx != -1) {
                        val key = line.substring(0, eqIdx).trim()
                        val value = line.substring(eqIdx + 1).trim()
                        val preCalc = if (value.contains("today", ignoreCase = true) ||
                                          value.contains("YYYY") ||
                                          value.contains("MM") ||
                                          value.contains("DD")) {
                            null
                        } else {
                            runCatching { value.convertPatchLevel(false) }.getOrNull() ?: value
                        }
                        newPatch[key] = preCalc ?: value
                    } else {
                         // Assume it's the default if it looks like a date or keyword
                         val value = line.trim()
                         val preCalc = if (value.contains("today", ignoreCase = true) ||
                                          value.contains("YYYY") ||
                                          value.contains("MM") ||
                                          value.contains("DD")) {
                            null
                        } else {
                            runCatching { value.convertPatchLevel(false) }.getOrNull() ?: value
                        }
                         newDefault = preCalc ?: value
                    }
                }
            }
        }
        securityPatchState = SecurityPatchState(newPatch, newDefault)
        Logger.i { "update security patch: default=$newDefault, per-app=${newPatch.size}" }
    }.onFailure {
        Logger.e("failed to update security patch", it)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private val hexFormat = HexFormat { upperCase = false }

    @OptIn(ExperimentalStdlibApi::class)
    private fun updateModuleHash(f: File?) = runCatching {
        moduleHash = f?.readText()?.trim()?.hexToByteArray()
        Logger.i("update module hash: ${moduleHash?.toHexString(hexFormat)}")
    }.onFailure {
        moduleHash = null
        Logger.e("failed to update module hash", it)
    }

    private const val CONFIG_PATH = "/data/adb/cleverestricky"
    private const val KEYBOX_DIR = "keyboxes"
    private const val TARGET_FILE = "target.txt"
    private const val KEYBOX_FILE = "keybox.xml"
    private const val GLOBAL_MODE_FILE = "global_mode"
    private const val TEE_BROKEN_MODE_FILE = "tee_broken_mode"
    private const val RKP_BYPASS_FILE = "rkp_bypass"
    private const val SPOOF_BUILD_FILE = "spoof_build"
    private const val SPOOF_BUILD_PS_FILE = "spoof_build_ps"
    private const val SPOOF_PROPS_FILE = "spoof_props"
    private const val SPOOF_PROVIDER_FILE = "spoof_provider"
    private const val SPOOF_SIGNATURE_FILE = "spoof_signature"
    private const val SPOOF_SDK_PS_FILE = "spoof_sdk_ps"
    private const val DRM_FIX_FILE = "drm_fix"
    private const val SPOOF_BUILD_VARS_FILE = "spoof_build_vars"
    private const val MODULE_HASH_FILE = "module_hash"
    private const val SECURITY_PATCH_FILE = "security_patch.txt"
    private const val REMOTE_KEYS_FILE = "remote_keys.xml"
    private const val APP_CONFIG_FILE = "app_config"
    private const val CUSTOM_TEMPLATES_FILE = "custom_templates"
    private const val TEMPLATES_JSON_FILE = "templates.json"
    private const val RANDOM_ON_BOOT_FILE = "random_on_boot"
    private const val KEYBOX_SOURCE_FILE = "keybox_source.txt"
    private const val RANDOM_DRM_ON_BOOT_FILE = "random_drm_on_boot"
    private const val HIDE_SENSITIVE_PROPS_FILE = "hide_sensitive_props"
    private const val APPLY_PROFILE_FILE = "apply_profile"
    private const val SPOOF_LOCATION_FILE = "spoof_location"
    private const val AUTO_PATCH_UPDATE_FILE = "auto_patch_update"
    private var root = File(CONFIG_PATH)
    private val keyboxDir get() = File(root, KEYBOX_DIR)

    val keyboxDirectory: File get() = keyboxDir

    @androidx.annotation.VisibleForTesting
    fun setRootForTesting(newRoot: File) {
        root = newRoot
    }

    @androidx.annotation.VisibleForTesting
    internal fun getConfigRoot(): File = root

    @Volatile
    var keyboxSourceUrl: String? = null

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
                val packages = if (pm != null) {
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

    private fun updateKeyboxSource(f: File?) = runCatching {
        val url = f?.readText()?.trim()
        if (!url.isNullOrBlank()) {
            keyboxSourceUrl = url
            Logger.i("update keybox source: $url")
        } else {
            keyboxSourceUrl = null
            Logger.i("update keybox source: default")
        }
    }.onFailure {
        keyboxSourceUrl = null
        Logger.e("failed to update keybox source", it)
    }


    private fun applyProfileFromFile(f: File?) = runCatching {
        if (f == null || !f.exists()) return@runCatching
        val profileName = f.readText().trim()
        if (profileName.isNotEmpty()) {
            applyProfile(profileName)
        }
        f.delete() // One-shot trigger
    }.onFailure {
        Logger.e("failed to apply profile from file", it)
    }

    fun applyProfile(profileName: String) {
        Logger.i("Applying profile: $profileName")
        when (profileName.lowercase()) {
            "godprofile" -> {
                SecureFile.touch(File(root, GLOBAL_MODE_FILE), 384)
                SecureFile.touch(File(root, RKP_BYPASS_FILE), 384)
                File(root, TEE_BROKEN_MODE_FILE).delete()
                SecureFile.touch(File(root, RANDOM_ON_BOOT_FILE), 384)
                SecureFile.touch(File(root, HIDE_SENSITIVE_PROPS_FILE), 384)
                SecureFile.touch(File(root, SPOOF_BUILD_VARS_FILE), 384)
                SecureFile.touch(File(root, RANDOM_DRM_ON_BOOT_FILE), 384)
                SecureFile.touch(File(root, AUTO_PATCH_UPDATE_FILE), 384)
                SecureFile.touch(File(root, SPOOF_LOCATION_FILE), 384)

                // Set DRM fix content
                val drmContent = "ro.netflix.bsp_rev=0\ndrm.service.enabled=true\nro.com.google.widevine.level=1\nro.crypto.state=encrypted\n"
                SecureFile.writeText(File(root, DRM_FIX_FILE), drmContent)
            }
            "dailyuse" -> {
                File(root, GLOBAL_MODE_FILE).delete()
                SecureFile.touch(File(root, RKP_BYPASS_FILE), 384)
                File(root, TEE_BROKEN_MODE_FILE).delete()
                File(root, RANDOM_ON_BOOT_FILE).delete()
                SecureFile.touch(File(root, HIDE_SENSITIVE_PROPS_FILE), 384)
                SecureFile.touch(File(root, SPOOF_BUILD_VARS_FILE), 384)
                SecureFile.touch(File(root, AUTO_PATCH_UPDATE_FILE), 384)
                File(root, DRM_FIX_FILE).delete()
                File(root, RANDOM_DRM_ON_BOOT_FILE).delete()
                File(root, SPOOF_LOCATION_FILE).delete()
            }
            "minimal" -> {
                File(root, GLOBAL_MODE_FILE).delete()
                SecureFile.touch(File(root, RKP_BYPASS_FILE), 384)
                File(root, TEE_BROKEN_MODE_FILE).delete()
                File(root, RANDOM_ON_BOOT_FILE).delete()
                File(root, HIDE_SENSITIVE_PROPS_FILE).delete()
                File(root, DRM_FIX_FILE).delete()
                File(root, SPOOF_BUILD_VARS_FILE).delete()
                File(root, RANDOM_DRM_ON_BOOT_FILE).delete()
                File(root, AUTO_PATCH_UPDATE_FILE).delete()
                File(root, SPOOF_LOCATION_FILE).delete()
            }
            "default" -> {
                File(root, GLOBAL_MODE_FILE).delete()
                SecureFile.touch(File(root, RKP_BYPASS_FILE), 384)
                File(root, TEE_BROKEN_MODE_FILE).delete()
                File(root, RANDOM_ON_BOOT_FILE).delete()
                File(root, HIDE_SENSITIVE_PROPS_FILE).delete()
                File(root, DRM_FIX_FILE).delete()
                File(root, SPOOF_BUILD_VARS_FILE).delete()
                File(root, RANDOM_DRM_ON_BOOT_FILE).delete()
                File(root, AUTO_PATCH_UPDATE_FILE).delete()
                File(root, SPOOF_LOCATION_FILE).delete()
            }
            else -> {
                Logger.e("Unknown profile: $profileName")
            }
        }
    }

    private fun checkRandomDrm() {
        if (File(root, RANDOM_DRM_ON_BOOT_FILE).exists()) {
            Logger.i("Random DRM on boot: cleaning provisioning data")
            val dirs = listOf("/data/vendor/mediadrm", "/data/mediadrm")
            dirs.forEach { path ->
                try {
                    File(path).walkBottomUp().forEach { if (it.path != path) it.delete() }
                } catch(e: Exception) {
                    Logger.e("Failed to clear DRM data on boot: $path", e)
                }
            }
        }
    }

    private fun enforceRandomization() {
        try {
            val randomImei = File(root, RANDOM_ON_BOOT_FILE).exists()
            val spoofFile = File(root, SPOOF_BUILD_VARS_FILE)

            // Generate mandatory random values
            val serial = RandomUtils.generateRandomSerial(12)
            val androidId = RandomUtils.generateRandomAndroidId()
            val wifiMac = RandomUtils.generateRandomMac()
            val btMac = RandomUtils.generateRandomMac()
            val simIso = RandomUtils.generateRandomSimIso()
            val simCarrier = RandomUtils.generateRandomCarrier()
            val gsfId = RandomUtils.generateRandomGsfId()

            val sb = StringBuilder()

            if (randomImei) {
                // Full randomization (Template + IMEI)
                val templates = DeviceTemplateManager.listTemplates()
                if (templates.isNotEmpty()) {
                    val t = templates.random()
                    sb.append("TEMPLATE=${t.id}\n")
                    sb.append("# Generated by Randomize on Boot (Full)\n")
                    sb.append("ATTESTATION_ID_IMEI=${RandomUtils.generateLuhn(15)}\n")
                    sb.append("ATTESTATION_ID_IMEI2=${RandomUtils.generateLuhn(15)}\n")
                }
            } else {
                // Partial randomization (Keep Template + IMEI)
                if (spoofFile.exists()) {
                    spoofFile.useLines { lines ->
                        lines.forEach { line ->
                            val l = line.trim()
                            if (l.isEmpty() || l.startsWith("#")) {
                                sb.append(line).append("\n")
                                return@forEach
                            }
                            // Filter out keys we are about to randomize
                            if (l.startsWith("ATTESTATION_ID_SERIAL") ||
                                l.startsWith("ro.serialno") ||
                                l.startsWith("ro.boot.serialno") ||
                                l.startsWith("ATTESTATION_ID_ANDROID_ID") ||
                                l.startsWith("ATTESTATION_ID_WIFI_MAC") ||
                                l.startsWith("ATTESTATION_ID_BT_MAC") ||
                                l.startsWith("SIM_COUNTRY_ISO") ||
                                l.startsWith("GSF_ID") ||
                                l.startsWith("SIM_OPERATOR_NAME")) {
                                return@forEach
                            }
                            sb.append(line).append("\n")
                        }
                    }
                    sb.append("# Updated by Randomize on Boot (Partial)\n")
                } else {
                    sb.append("# Generated by Randomize on Boot (Partial - No previous config)\n")
                }
            }

            // Append mandatory values
            sb.append("ATTESTATION_ID_SERIAL=$serial\n")
            sb.append("ro.serialno=$serial\n")
            sb.append("ro.boot.serialno=$serial\n")
            sb.append("ATTESTATION_ID_ANDROID_ID=$androidId\n")
            sb.append("ATTESTATION_ID_WIFI_MAC=$wifiMac\n")
            sb.append("ATTESTATION_ID_BT_MAC=$btMac\n")
            sb.append("SIM_COUNTRY_ISO=$simIso\n")
            sb.append("SIM_OPERATOR_NAME=$simCarrier\n")
            sb.append("GSF_ID=$gsfId\n")

            // Random location if enabled
            if (File(root, SPOOF_LOCATION_FILE).exists()) {
                val locationRandom = buildVars["SPOOF_LOCATION_RANDOM"]?.equals("true", ignoreCase = true) == true
                if (locationRandom) {
                    val baseLat = buildVars["SPOOF_LATITUDE"]?.toDoubleOrNull() ?: 0.0
                    val baseLng = buildVars["SPOOF_LONGITUDE"]?.toDoubleOrNull() ?: 0.0
                    val radius = buildVars["SPOOF_LOCATION_RADIUS"]?.toIntOrNull() ?: 500
                    val randomLoc = RandomUtils.generateRandomLocationOffset(baseLat, baseLng, radius)
                    sb.append("SPOOF_LATITUDE=${randomLoc.first}\n")
                    sb.append("SPOOF_LONGITUDE=${randomLoc.second}\n")
                    Logger.i("Random location set: ${randomLoc.first}, ${randomLoc.second} (radius: ${radius}m)")
                }
            }

            SecureFile.writeText(spoofFile, sb.toString())
            updateBuildVars(spoofFile)
            Logger.i("Enforced identity randomization (IMEI random: $randomImei)")

        } catch (e: Exception) {
            Logger.e("Failed to enforce randomization", e)
        }
    }

    object ConfigObserver : FileObserver(root, CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO) {
        override fun onEvent(event: Int, path: String?) {
            path ?: return
            val f = when (event) {
                CLOSE_WRITE, MOVED_TO -> File(root, path)
                DELETE, MOVED_FROM -> null
                else -> return
            }
            when (path) {
                TARGET_FILE -> updateTargetPackages(f)
                KEYBOX_FILE -> updateKeyBoxes()
                SPOOF_BUILD_VARS_FILE -> updateBuildVars(f)
                SECURITY_PATCH_FILE -> updateSecurityPatch(f)
                REMOTE_KEYS_FILE -> RemoteKeyManager.update(f)
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

                RKP_BYPASS_FILE -> updateRkpBypass(f)
                SPOOF_BUILD_FILE -> updateSpoofBuild(f)
                SPOOF_BUILD_PS_FILE -> updateSpoofBuildPs(f)
                SPOOF_PROPS_FILE -> updateSpoofProps(f)
                SPOOF_PROVIDER_FILE -> updateSpoofProvider(f)
                SPOOF_SIGNATURE_FILE -> updateSpoofSignature(f)
                SPOOF_SDK_PS_FILE -> updateSpoofSdkPs(f)

                DRM_FIX_FILE -> updateDrmFix(f)

                MODULE_HASH_FILE -> updateModuleHash(f)

                KEYBOX_SOURCE_FILE -> updateKeyboxSource(f)
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
        SecureFile.mkdirs(root, 448) // 0700
        SecureFile.mkdirs(keyboxDir, 448) // 0700
        DeviceKeyManager.initialize(root)
        CboxManager.initialize()
        ServerManager.initialize()
        DeviceTemplateManager.initialize(root)

        updateGlobalMode(File(root, GLOBAL_MODE_FILE))
        updateTeeBrokenMode(File(root, TEE_BROKEN_MODE_FILE))
        updateRkpBypass(File(root, RKP_BYPASS_FILE))
        updateSpoofBuild(File(root, SPOOF_BUILD_FILE))
        updateSpoofBuildPs(File(root, SPOOF_BUILD_PS_FILE))
        updateSpoofProps(File(root, SPOOF_PROPS_FILE))
        updateSpoofProvider(File(root, SPOOF_PROVIDER_FILE))
        updateSpoofSignature(File(root, SPOOF_SIGNATURE_FILE))
        updateSpoofSdkPs(File(root, SPOOF_SDK_PS_FILE))
        updateDrmFix(File(root, DRM_FIX_FILE))
        updateBuildVars(File(root, SPOOF_BUILD_VARS_FILE))
        updateModuleHash(File(root, MODULE_HASH_FILE))
        updateSecurityPatch(File(root, SECURITY_PATCH_FILE))
        RemoteKeyManager.update(File(root, REMOTE_KEYS_FILE))
        updateAppConfigs(File(root, APP_CONFIG_FILE))
        updateKeyboxSource(File(root, KEYBOX_SOURCE_FILE))

        updateCustomTemplates(File(root, CUSTOM_TEMPLATES_FILE))

        enforceRandomization()
        updateBuildVars(File(root, SPOOF_BUILD_VARS_FILE))
        checkRandomDrm()

        if (!isGlobalMode) {
            val scope = File(root, TARGET_FILE)
            Logger.d("Config.initialize: loading target.txt from ${scope.absolutePath} (exists=${scope.exists()})")
            if (scope.exists()) {
                updateTargetPackages(scope)
            } else {
                Logger.e("target.txt file not found, please put it to $scope !")
            }
        } else {
            Logger.i("Config.initialize: global mode active, all apps targeted (target.txt used as exclusion list)")
            updateTargetPackages(File(root, TARGET_FILE))
        }

        updateKeyBoxes()

        ConfigObserver.startWatching()
        KeyboxDirObserver.startWatching()
        keyboxPoller?.stop()
        keyboxPoller = FilePoller(File(root, KEYBOX_FILE), 5000) {
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

    internal fun matchesPackage(pkgName: String, rules: PackageTrie<Boolean>): Boolean {
        return rules.matches(pkgName)
    }

    internal data class CachedPackage(val value: Array<String>, val timestamp: Long)

    // Cache to reduce IPC calls to PackageManager for getPackagesForUid
    // Key: callingUid, Value: CachedPackage
    // OPTIMIZATION: Use ConcurrentHashMap to allow lock-free reads and better concurrency.
    // The map is unbounded but limited by the number of installed apps (~hundreds, max ~50k UIDs),
    // which fits well within memory limits compared to synchronized access overhead.
    private val packageCache = ConcurrentHashMap<Int, CachedPackage>()
    private val uidLocks = ConcurrentHashMap<Int, Any>()

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
        val lock = uidLocks.computeIfAbsent(uid) { Any() }
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
                packageCache[uid] = CachedPackage(pkgs, now)
                pkgs
            }
        }
    }

    private fun checkPackages(packages: PackageTrie<Boolean>, callingUid: Int): Boolean {
        return kotlin.runCatching {
            if (packages.isEmpty()) return@runCatching false
            val ps = getPackages(callingUid)
            if (ps.isEmpty()) return@runCatching false
            ps.any { pkgName -> matchesPackage(pkgName, packages) }
        }.onFailure { Logger.e("failed to get packages", it) }.getOrNull() ?: false
    }

    fun needHack(callingUid: Int): Boolean {
        if (isTeeBroken) return false
        if (isGlobalMode) return true

        val state = targetState
        val cached = state.hackCache[callingUid]
        if (cached != null) return cached

        val result = checkPackages(state.hackPackages, callingUid)
        state.hackCache[callingUid] = result
        return result
    }

    fun needGenerate(callingUid: Int): Boolean {
        if (isTeeBroken && isGlobalMode) return true
        if (isGlobalMode) return false

        val state = targetState

        val cachedGen = state.generateCache[callingUid]
        val genResult = if (cachedGen != null) cachedGen else {
            val r = checkPackages(state.generatePackages, callingUid)
            state.generateCache[callingUid] = r
            r
        }

        return if (isTeeBroken) {
            if (genResult) return true

            val cachedHack = state.hackCache[callingUid]
            val hackResult = if (cachedHack != null) cachedHack else {
                val r = checkPackages(state.hackPackages, callingUid)
                state.hackCache[callingUid] = r
                r
            }
            hackResult
        } else {
            genResult
        }
    }

    @androidx.annotation.VisibleForTesting
    fun reset() {
        ConfigObserver.stopWatching()
        KeyboxDirObserver.stopWatching()
        keyboxPoller?.stop()
        scope.coroutineContext.cancelChildren()

        root = File(CONFIG_PATH)
        packageCache.clear()
        uidLocks.clear()
        dynamicPatchCache.clear()
        securityPatchState = SecurityPatchState(emptyMap(), null)
        iPm = null
        appConfigState = AppConfigState(PackageTrie())
        targetState = TargetState(PackageTrie(), PackageTrie())
        buildVars = emptyMap()
        attestationIds = emptyMap()
        stringToBytesCache.clear()
        templates = emptyMap()
        templateKeyCache.clear()
        moduleHash = null
        moduleHashFromVars = null
        keyboxSourceUrl = null
        cachedPackageList = null
        lastPackageFetchTime = 0
        isGlobalMode = false
        isTeeBrokenMode = false
        isAutoTeeBroken = false
        isRkpBypass = false
        drmFixVars = emptyMap()
        clockSource = { System.currentTimeMillis() }
        cachedLegacyKeyboxes = emptyList()
        lastKeyboxModified = 0
        directoryKeyboxCache.clear()
    }
}
