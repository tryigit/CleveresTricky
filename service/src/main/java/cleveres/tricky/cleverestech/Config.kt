package cleveres.tricky.cleverestech

import android.content.pm.IPackageManager
import android.os.FileObserver
import android.os.ServiceManager
import android.system.Os
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.PackageTrie
import cleveres.tricky.cleverestech.util.RandomUtils
import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.DeviceKeyManager
import cleveres.tricky.cleverestech.util.ServerManager
import cleveres.tricky.cleverestech.util.ZipProcessor
import cleveres.tricky.cleverestech.util.CboxDecryptor
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
    private var isGlobalMode = false
    private var isTeeBrokenMode = false
    @Volatile
    private var isAutoTeeBroken = false
    private val isTeeBroken get() = isTeeBrokenMode || isAutoTeeBroken
    @Volatile
    private var moduleHash: ByteArray? = null
    private var isRkpBypass = false

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

    fun setTeeBroken(broken: Boolean) {
        isAutoTeeBroken = broken
        Logger.i("Auto TEE broken mode is ${if (isAutoTeeBroken) "enabled" else "disabled"}")
    }

    fun getModuleHash(): ByteArray? = moduleHash

    fun getAppConfig(uid: Int): AppSpoofConfig? {
        val state = appConfigState
        val cached = state.cache[uid]
        if (cached != null) {
            return if (cached === NULL_CONFIG) null else cached as AppSpoofConfig
        }

        val pkgs = getPackages(uid)
        var result: AppSpoofConfig? = null
        for (pkg in pkgs) {
            val config = state.configs.get(pkg)
            if (config != null) {
                result = config
                break
            }
        }
        state.cache[uid] = result ?: NULL_CONFIG
        return result
    }

    private val SPLIT_REGEX = Regex("\\s+")

    private fun updateAppConfigs(f: File?) = runCatching {
        val newConfigs = PackageTrie<AppSpoofConfig>()
        f?.useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val parts = line.trim().split(SPLIT_REGEX)
                    if (parts.isNotEmpty()) {
                        val pkg = parts[0]
                        var template: String? = null
                        var keybox: String? = null
                        val permissions = HashSet<String>()

                        if (parts.size > 1 && parts[1] != "null") template = parts[1].lowercase()
                        if (parts.size > 2 && parts[2] != "null") keybox = parts[2]
                        if (parts.size > 3 && parts[3] != "null") {
                            val permStr = parts[3]
                            var start = 0
                            val len = permStr.length
                            while (start < len) {
                                var end = permStr.indexOf(',', start)
                                if (end == -1) {
                                    end = len
                                }
                                val part = permStr.substring(start, end)
                                if (part.isNotBlank()) {
                                    permissions.add(part.trim())
                                }
                                start = end + 1
                            }
                        }

                        if (template != null || keybox != null || permissions.isNotEmpty()) {
                            newConfigs.add(pkg, AppSpoofConfig(template, keybox, permissions))
                        }
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
        val (h, g) = if (f != null && f.exists()) {
            f.useLines { parsePackages(it, isTeeBrokenMode) }
        } else {
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

    @Volatile
    var detectedCboxFiles: List<String> = emptyList()
        private set

    private fun updateKeyBoxes() = scope.launch {
        runCatching {
            val allKeyboxes = ArrayList<CertHack.KeyBox>()
            val foundCbox = ArrayList<String>()

            // 0. Device Cached Keyboxes (Highest Priority)
            // Stored in root with prefix local_cache_
            root.listFiles { _, name -> name.startsWith("local_cache_") && name.endsWith(".enc") }?.forEach { file ->
                 try {
                     // Check if we need to decrypt (optimized?)
                     // For now, simple decryption. In prod, maybe cache in memory.
                     val encrypted = file.readBytes()
                     val decrypted = DeviceKeyManager.decryptFromDevice(encrypted)
                     if (decrypted != null) {
                         val xml = String(decrypted, Charsets.UTF_8)
                         val parsed = CertHack.parseKeyboxXml(java.io.StringReader(xml), file.name)
                         allKeyboxes.addAll(parsed)
                         // Logger.i("Loaded cached keybox: ${file.name}")
                     }
                 } catch(e: Exception) {
                     Logger.e("Failed to load cached keybox: ${file.name}", e)
                 }
            }

            // 1. Legacy keybox.xml
            val legacyFile = File(root, KEYBOX_FILE)
            if (legacyFile.exists()) {
                // Security: If we have cached keyboxes, we should technically prioritize them.
                // The requirement: "If plain keybox.xml AND cached version both exist, securely delete the plain XML"
                // But this logic assumes they are the SAME keybox.
                // We don't know if they are the same without comparing.
                // For now, load it.
                val currentModified = legacyFile.lastModified()
                if (currentModified > lastKeyboxModified || cachedLegacyKeyboxes.isEmpty()) {
                    legacyFile.bufferedReader().use { reader ->
                        cachedLegacyKeyboxes = CertHack.parseKeyboxXml(reader, KEYBOX_FILE)
                    }
                    lastKeyboxModified = currentModified
                    Logger.i("Reloaded keybox.xml (modified: $currentModified)")
                }
                allKeyboxes.addAll(cachedLegacyKeyboxes)
            } else {
                cachedLegacyKeyboxes = emptyList()
                lastKeyboxModified = 0
            }

            // 2. Directory files (XML, CBOX, ZIP)
            if (keyboxDir.exists() && keyboxDir.isDirectory) {
                val files = keyboxDir.listFiles() ?: emptyArray()
                val currentFiles = HashSet<String>()

                files.forEach { file ->
                    val filename = file.name

                    if (filename.endsWith(".xml")) {
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
                    } else if (filename.endsWith(".cbox") || filename.endsWith(".zip")) {
                        // Attempt auto-unlock or list as detected
                        foundCbox.add(filename)
                        tryAutoUnlock(file)
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

            detectedCboxFiles = foundCbox
            CertHack.setKeyboxes(allKeyboxes)

            // Update poller for legacy file consistency
            keyboxPoller?.updateLastModified()
        }.onFailure {
            Logger.e("failed to update keyboxes", it)
        }
    }

    private fun tryAutoUnlock(file: File) {
        // Optimization: Only try if not already cached (avoid loops)
        // Check if we already have a cache for this file?
        // Hard to map source file to cache file deterministically without ID.
        // But ServerManager uses ID. Local files don't have ID.
        // We can use hash of filename or something.
        // For now, just try to unlock.

        try {
            val bytes = file.readBytes()
            if (ZipProcessor.isZip(bytes)) {
                val result = ZipProcessor.process(bytes)
                if (result.success) {
                    // Save to cache
                    result.payloads.forEachIndexed { i, p ->
                        val enc = DeviceKeyManager.encryptForDevice(p.xmlContent.toByteArray(Charsets.UTF_8))
                        if (enc != null) {
                            val dest = File(root, "local_cache_${file.name}_$i.enc")
                            SecureFile.writeBytes(dest, enc)
                        }
                    }
                    Logger.i("Auto-unlocked ZIP: ${file.name}")
                    // Trigger reload by touching root/target (actually updateKeyBoxes will be called again by observer if we wrote to root)
                }
            }
        } catch (e: Exception) {
            // Ignore
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

    private fun updateRkpBypass(f: File?) {
        isRkpBypass = f?.exists() == true
        Logger.i("RKP bypass is ${if (isRkpBypass) "enabled" else "disabled"}")
    }

    @Volatile
    private var buildVars: Map<String, String> = emptyMap()
    @Volatile
    private var drmFixVars: Map<String, String> = emptyMap()
    @Volatile
    private var attestationIds: Map<String, ByteArray> = emptyMap()

    fun getAttestationId(tag: String): ByteArray? = attestationIds[tag]

    fun getAttestationId(tag: String, uid: Int): ByteArray? {
        // 1. Explicit override (highest priority)
        val global = attestationIds[tag]
        if (global != null) return global

        // 2. Smart Fallback (Build Var via Template or Global)
        // This leverages getBuildVar which handles "Smart Property Mapping" for templates
        // and falls back to global build vars.
        val value = getBuildVar(tag, uid)
        return value?.toByteArray(Charsets.UTF_8)
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
                         val parts = l.split("=", limit = 2)
                         if (parts.size == 2) {
                             currentProps?.put(parts[0].trim(), parts[1].trim())
                         }
                     }
                 }
             }
             // Save last
             if (currentTemplate != null && currentProps != null) {
                 newTemplates[currentTemplate!!] = currentProps!!
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
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        newVars[parts[0].trim()] = parts[1].trim()
                    }
                }
            }
        }
        drmFixVars = newVars
        Logger.i("update drm fix vars: $drmFixVars")
    }.onFailure {
        Logger.e("failed to update drm fix vars", it)
    }

    internal fun updateBuildVars(f: File?) = runCatching {
        val newVars = mutableMapOf<String, String>()
        val newIds = mutableMapOf<String, ByteArray>()
        f?.useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
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
        Logger.i { "update build vars: $buildVars, attestation ids: ${attestationIds.keys}" }
    }.onFailure {
        Logger.e("failed to update build vars", it)
    }

    @Volatile
    private var securityPatch: Map<String, Any> = emptyMap()
    private var defaultSecurityPatch: Any? = null

    // Cache for dynamic patch levels (e.g. "today", "YYYY-MM-DD")
    // Key: Template String, Value: Pair(Timestamp, CalculatedLevel)
    private val dynamicPatchCache = ConcurrentHashMap<String, Pair<Long, Int>>()
    private const val DYNAMIC_PATCH_TTL = 3600 * 1000L // 1 hour

    fun getPatchLevel(callingUid: Int): Int {
        val defaultLevel = patchLevel
        val patchVal = if (securityPatch.isNotEmpty()) {
            // Use cached getPackages to avoid expensive IPC call
            val pkgs = getPackages(callingUid)
            var found: Any? = null
            for (pkg in pkgs) {
                found = securityPatch[pkg]
                if (found != null) break
            }
            found ?: defaultSecurityPatch
        } else {
            defaultSecurityPatch
        }

        if (patchVal == null) return defaultLevel

        if (patchVal is Int) return patchVal

        val patchStr = patchVal as String

        // Optimization: Check cache for dynamic strings to avoid expensive date/string operations
        val nowMs = clockSource()
        val cached = dynamicPatchCache[patchStr]
        if (cached != null && (nowMs - cached.first) < DYNAMIC_PATCH_TTL) {
            return cached.second
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

    private fun updateSecurityPatch(f: File?) = runCatching {
        val newPatch = mutableMapOf<String, Any>()
        var newDefault: Any? = null
        f?.useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        val preCalc = if (value.contains("today", ignoreCase = true) ||
                                          value.contains("YYYY") ||
                                          value.contains("MM") ||
                                          value.contains("DD")) {
                            null
                        } else {
                            runCatching { value.convertPatchLevel(false) }.getOrNull()
                        }
                        newPatch[key] = preCalc ?: value
                    } else if (parts.size == 1) {
                         // Assume it's the default if it looks like a date or keyword
                         val value = parts[0].trim()
                         val preCalc = if (value.contains("today", ignoreCase = true) ||
                                          value.contains("YYYY") ||
                                          value.contains("MM") ||
                                          value.contains("DD")) {
                            null
                        } else {
                            runCatching { value.convertPatchLevel(false) }.getOrNull()
                        }
                         newDefault = preCalc ?: value
                    }
                }
            }
        }
        securityPatch = newPatch
        defaultSecurityPatch = newDefault
        Logger.i { "update security patch: default=$defaultSecurityPatch, per-app=${securityPatch.size}" }
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
    private var root = File(CONFIG_PATH)
    private val keyboxDir get() = File(root, KEYBOX_DIR)

    val keyboxDirectory: File get() = keyboxDir

    @androidx.annotation.VisibleForTesting
    fun setRootForTesting(newRoot: File) {
        root = newRoot
    }

    @Volatile
    var keyboxSourceUrl: String? = null

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

    private fun checkRandomizeOnBoot() {
        try {
            if (File(root, RANDOM_ON_BOOT_FILE).exists()) {
                val templates = DeviceTemplateManager.listTemplates()
                if (templates.isNotEmpty()) {
                    val t = templates.random()
                    val sb = StringBuilder()
                    sb.append("TEMPLATE=${t.id}\n")
                    sb.append("# Generated by Randomize on Boot\n")
                    sb.append("ATTESTATION_ID_IMEI=${RandomUtils.generateLuhn(15)}\n")
                    sb.append("ATTESTATION_ID_IMEI2=${RandomUtils.generateLuhn(15)}\n")
                    sb.append("ATTESTATION_ID_SERIAL=${RandomUtils.generateRandomSerial(12)}\n")
                    sb.append("ATTESTATION_ID_ANDROID_ID=${RandomUtils.generateRandomAndroidId()}\n")
                    sb.append("ATTESTATION_ID_WIFI_MAC=${RandomUtils.generateRandomMac()}\n")
                    sb.append("ATTESTATION_ID_BT_MAC=${RandomUtils.generateRandomMac()}\n")
                    sb.append("SIM_COUNTRY_ISO=${RandomUtils.generateRandomSimIso()}\n")
                    sb.append("SIM_OPERATOR_NAME=${RandomUtils.generateRandomCarrier()}\n")

                    val f = File(root, SPOOF_BUILD_VARS_FILE)
                    SecureFile.writeText(f, sb.toString())
                    Logger.i("Randomized identity on boot: ${t.id}")
                }
            }
        } catch (e: Exception) {
            Logger.e("Failed to randomize on boot", e)
        }
    }

    object ConfigObserver : FileObserver(root, CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO) {
        override fun onEvent(event: Int, path: String?) {
            path ?: return

            // Optimization: Filter events for local_cache to avoid unnecessary processing
            if (path.startsWith("local_cache_")) {
                 updateKeyBoxes()
                 return
            }

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

                DRM_FIX_FILE -> updateDrmFix(f)

                MODULE_HASH_FILE -> updateModuleHash(f)

                KEYBOX_SOURCE_FILE -> updateKeyboxSource(f)
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
        SecureFile.mkdirs(root, 448) // 0700
        SecureFile.mkdirs(keyboxDir, 448) // 0700
        DeviceTemplateManager.initialize(root)
        ServerManager.initialize(root) // Initialize ServerManager

        updateGlobalMode(File(root, GLOBAL_MODE_FILE))
        updateTeeBrokenMode(File(root, TEE_BROKEN_MODE_FILE))
        updateRkpBypass(File(root, RKP_BYPASS_FILE))
        updateDrmFix(File(root, DRM_FIX_FILE))
        updateBuildVars(File(root, SPOOF_BUILD_VARS_FILE))
        updateModuleHash(File(root, MODULE_HASH_FILE))
        updateSecurityPatch(File(root, SECURITY_PATCH_FILE))
        RemoteKeyManager.update(File(root, REMOTE_KEYS_FILE))
        updateAppConfigs(File(root, APP_CONFIG_FILE))
        updateKeyboxSource(File(root, KEYBOX_SOURCE_FILE))

        updateCustomTemplates(File(root, CUSTOM_TEMPLATES_FILE))

        checkRandomizeOnBoot()
        updateBuildVars(File(root, SPOOF_BUILD_VARS_FILE))
        checkRandomDrm()

        if (!isGlobalMode) {
            val scope = File(root, TARGET_FILE)
            if (scope.exists()) {
                updateTargetPackages(scope)
            } else {
                Logger.e("target.txt file not found, please put it to $scope !")
            }
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
        // Use compute to ensure only one thread performs the update for this UID
        val updatedEntry = packageCache.compute(uid) { _, current ->
            // Double-check inside the lock: another thread might have updated it
            if (current != null && (now - current.timestamp) < CACHE_TTL_MS) {
                current
            } else {
                val pm = getPm()
                if (pm == null) {
                    null
                } else {
                    val pkgs = pm.getPackagesForUid(uid) ?: emptyArray()
                    CachedPackage(pkgs, now)
                }
            }
        }

        return updatedEntry?.value ?: emptyArray()
    }

    private fun checkPackages(packages: PackageTrie<Boolean>, callingUid: Int) = kotlin.runCatching {
        if (packages.isEmpty()) return false
        val ps = getPackages(callingUid)
        if (ps.isEmpty()) return false
        ps.any { pkgName -> matchesPackage(pkgName, packages) }
    }.onFailure { Logger.e("failed to get packages", it) }.getOrNull() ?: false

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
        dynamicPatchCache.clear()
        securityPatch = emptyMap()
        defaultSecurityPatch = null
        iPm = null
        appConfigState = AppConfigState(PackageTrie())
        targetState = TargetState(PackageTrie(), PackageTrie())
        buildVars = emptyMap()
        attestationIds = emptyMap()
        templates = emptyMap()
        templateKeyCache.clear()
        moduleHash = null
        keyboxSourceUrl = null
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
