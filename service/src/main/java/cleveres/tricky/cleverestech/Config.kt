package cleveres.tricky.cleverestech

import android.content.pm.IPackageManager
import android.os.FileObserver
import android.os.ServiceManager
import android.system.Os
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.RandomUtils
import cleveres.tricky.cleverestech.util.SecureFile
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap

class PackageTrie {
    private class Node {
        val children = HashMap<Char, Node>()
        var isLeaf = false
        var isWildcard = false
    }

    private val root = Node()
    var size = 0
        private set

    fun add(rule: String) {
        size++
        var current = root
        var effectiveRule = rule
        var isWildcard = false
        if (rule.endsWith("*")) {
            effectiveRule = rule.dropLast(1)
            isWildcard = true
        }

        for (char in effectiveRule) {
            current = current.children.computeIfAbsent(char) { Node() }
        }
        if (isWildcard) {
            current.isWildcard = true
        } else {
            current.isLeaf = true
        }
    }

    fun matches(pkgName: String): Boolean {
        var current = root
        if (current.isWildcard) return true

        for (i in pkgName.indices) {
            val char = pkgName[i]
            val next = current.children[char] ?: return false
            current = next
            if (current.isWildcard) return true
        }
        return current.isLeaf
    }

    fun isEmpty() = size == 0
}

object Config {
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

    data class AppSpoofConfig(val template: String?, val keyboxFilename: String?)

    @Volatile
    private var hackPackages: PackageTrie = PackageTrie()
    @Volatile
    private var generatePackages: PackageTrie = PackageTrie()
    private var isGlobalMode = false
    private var isTeeBrokenMode = false
    @Volatile
    private var isAutoTeeBroken = false
    private val isTeeBroken get() = isTeeBrokenMode || isAutoTeeBroken
    @Volatile
    private var moduleHash: ByteArray? = null
    private var isRkpBypass = false

    @Volatile
    private var appConfigs: Map<String, AppSpoofConfig> = emptyMap()

    fun shouldBypassRkp() = isRkpBypass

    fun setTeeBroken(broken: Boolean) {
        isAutoTeeBroken = broken
        Logger.i("Auto TEE broken mode is ${if (isAutoTeeBroken) "enabled" else "disabled"}")
    }

    fun getModuleHash(): ByteArray? = moduleHash

    fun getAppConfig(uid: Int): AppSpoofConfig? {
        val pkgs = getPackages(uid)
        for (pkg in pkgs) {
            val config = appConfigs[pkg]
            if (config != null) return config
        }
        return null
    }

    private fun updateAppConfigs(f: File?) = runCatching {
        val newConfigs = mutableMapOf<String, AppSpoofConfig>()
        f?.useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.isNotEmpty()) {
                        val pkg = parts[0]
                        var template: String? = null
                        var keybox: String? = null

                        if (parts.size > 1 && parts[1] != "null") template = parts[1].lowercase()
                        if (parts.size > 2 && parts[2] != "null") keybox = parts[2]

                        if (template != null || keybox != null) {
                            newConfigs[pkg] = AppSpoofConfig(template, keybox)
                        }
                    }
                }
            }
        }
        appConfigs = newConfigs
        Logger.i { "update app configs: ${appConfigs.size}" }
    }.onFailure {
        Logger.e("failed to update app configs", it)
    }

    fun parsePackages(lines: List<String>, isTeeBrokenMode: Boolean): Pair<PackageTrie, PackageTrie> {
        val hackPackages = PackageTrie()
        val generatePackages = PackageTrie()
        lines.forEach {
            if (it.isNotBlank() && !it.startsWith("#")) {
                val n = it.trim()
                if (isTeeBrokenMode || n.endsWith("!"))
                    generatePackages.add(
                        n.removeSuffix("!").trim()
                    )
                else hackPackages.add(n)
            }
        }
        return hackPackages to generatePackages
    }

    private fun updateTargetPackages(f: File?) = runCatching {
        if (isGlobalMode) {
            hackPackages = PackageTrie()
            generatePackages = PackageTrie()
            Logger.i("Global mode is enabled, skipping updateTargetPackages execution.")
            return@runCatching
        }
        val (h, g) = parsePackages(f?.readLines() ?: emptyList(), isTeeBrokenMode)
        hackPackages = h
        generatePackages = g
        Logger.i { "update hack packages: ${hackPackages.size}, generate packages=${generatePackages.size}" }
    }.onFailure {
        Logger.e("failed to update target files", it)
    }

    private var keyboxPoller: FilePoller? = null

    private fun updateKeyBoxes() = runCatching {
        val allKeyboxes = ArrayList<CertHack.KeyBox>()

        // 1. Legacy keybox.xml
        val legacyFile = File(root, KEYBOX_FILE)
        if (legacyFile.exists()) {
             legacyFile.bufferedReader().use { reader ->
                 allKeyboxes.addAll(CertHack.parseKeyboxXml(reader, KEYBOX_FILE))
             }
        }

        // 2. Directory files
        if (keyboxDir.exists() && keyboxDir.isDirectory) {
            val files = keyboxDir.listFiles { _, name -> name.endsWith(".xml") }
            files?.forEach { file ->
                try {
                    file.bufferedReader().use { reader ->
                        allKeyboxes.addAll(CertHack.parseKeyboxXml(reader, file.name))
                    }
                } catch (e: Exception) {
                    Logger.e("Failed to parse keybox file: ${file.name}", e)
                }
            }
        }

        CertHack.setKeyboxes(allKeyboxes)

        // Update poller for legacy file consistency
        keyboxPoller?.updateLastModified()
    }.onFailure {
        Logger.e("failed to update keyboxes", it)
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

    fun getBuildVar(key: String): String? {
        return drmFixVars[key] ?: buildVars[key] ?: spoofedProperties[key]
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
            val mapped = mapPropertyToTemplate(key, template)
            if (mapped != null) return mapped
        }

        // 3. DRM Fix Properties
        drmFixVars[key]?.let { return it }

        // 4. Global build vars or default spoofed properties
        return buildVars[key] ?: spoofedProperties[key]
    }

    private fun mapPropertyToTemplate(key: String, template: Map<String, String>): String? {
        return when {
            // Fingerprint
            key.endsWith("fingerprint") -> template["FINGERPRINT"]
            // Security Patch
            key.endsWith("security_patch") -> template["SECURITY_PATCH"]
            // Model
            key.endsWith("model") -> template["MODEL"]
            // Brand
            key.endsWith("brand") -> template["BRAND"]
            // Manufacturer
            key.endsWith("manufacturer") -> template["MANUFACTURER"]
            // Device
            key.endsWith("device") -> template["DEVICE"]
            // Product
            key.endsWith("product") || key.endsWith("name") -> template["PRODUCT"]
            // ID
            key.endsWith("build.id") || key.endsWith("display.id") -> template["ID"]
            // Release
            key.endsWith("version.release") || key.endsWith("version.release_or_codename") -> template["RELEASE"]
            // Incremental
            key.endsWith("version.incremental") -> template["INCREMENTAL"]
            // Type
            key.endsWith("build.type") -> template["TYPE"]
            // Tags
            key.endsWith("build.tags") -> template["TAGS"]
            else -> null
        }
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

    fun getPatchLevel(callingUid: Int): Int {
        val defaultLevel = patchLevel
        val patchVal = if (securityPatch.isNotEmpty()) {
            // Use cached getPackages to avoid expensive IPC call
            val pkgName = getPackages(callingUid).firstOrNull()
            if (pkgName != null) {
                securityPatch[pkgName] ?: defaultSecurityPatch
            } else {
                defaultSecurityPatch
            }
        } else {
            defaultSecurityPatch
        }

        if (patchVal == null) return defaultLevel

        if (patchVal is Int) return patchVal

        val patchStr = patchVal as String
        val effectiveDate = if (patchStr.equals("today", ignoreCase = true)) {
            java.time.LocalDate.now().toString()
        } else if (patchStr.contains("YYYY") || patchStr.contains("MM") || patchStr.contains("DD")) {
             val now = java.time.LocalDate.now()
             patchStr.replace("YYYY", String.format("%04d", now.year))
                     .replace("MM", String.format("%02d", now.monthValue))
                     .replace("DD", String.format("%02d", now.dayOfMonth))
        } else {
            patchStr
        }

        return effectiveDate.convertPatchLevel(false)
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
    private const val RANDOM_DRM_ON_BOOT_FILE = "random_drm_on_boot"
    private val root = File(CONFIG_PATH)
    private val keyboxDir = File(root, KEYBOX_DIR)

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
        root.mkdirs()
        keyboxDir.mkdirs()
        try {
            Os.chmod(root.absolutePath, 448) // 0700
            Os.chmod(keyboxDir.absolutePath, 448) // 0700
        } catch (t: Throwable) {
            Logger.e("failed to set permissions for config dir", t)
        }

        // Initialize Templates FIRST (needed for randomization)
        DeviceTemplateManager.initialize(root)
        updateCustomTemplates(File(root, CUSTOM_TEMPLATES_FILE))

        // Check Randomization (may overwrite spoof_build_vars)
        checkRandomizeOnBoot()

        updateGlobalMode(File(root, GLOBAL_MODE_FILE))
        updateTeeBrokenMode(File(root, TEE_BROKEN_MODE_FILE))
        updateRkpBypass(File(root, RKP_BYPASS_FILE))
        updateDrmFix(File(root, DRM_FIX_FILE))
        updateBuildVars(File(root, SPOOF_BUILD_VARS_FILE))
        updateModuleHash(File(root, MODULE_HASH_FILE))
        updateSecurityPatch(File(root, SECURITY_PATCH_FILE))
        RemoteKeyManager.update(File(root, REMOTE_KEYS_FILE))
        updateAppConfigs(File(root, APP_CONFIG_FILE))

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

    internal fun matchesPackage(pkgName: String, rules: PackageTrie): Boolean {
        return rules.matches(pkgName)
    }

    // Cache to reduce IPC calls to PackageManager for getPackagesForUid
    // Key: callingUid, Value: Array of package names
    private val packageCache = Collections.synchronizedMap(
        object : LinkedHashMap<Int, Array<String>>(200, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Array<String>>?): Boolean {
                return size > 200
            }
        }
    )

    /**
     * Retrieves the list of packages for a given UID, using a cache to avoid frequent IPC calls.
     * Returns an empty array if the UID has no associated packages or if PackageManager is unavailable.
     */
    fun getPackages(uid: Int): Array<String> {
        packageCache[uid]?.let { return it }
        val pm = getPm() ?: return emptyArray()
        val ps = pm.getPackagesForUid(uid) ?: emptyArray()
        packageCache[uid] = ps
        return ps
    }

    private fun checkPackages(packages: PackageTrie, callingUid: Int) = kotlin.runCatching {
        if (packages.isEmpty()) return false
        val ps = getPackages(callingUid)
        if (ps.isEmpty()) return false
        ps.any { pkgName -> matchesPackage(pkgName, packages) }
    }.onFailure { Logger.e("failed to get packages", it) }.getOrNull() ?: false

    fun needHack(callingUid: Int): Boolean {
        return when {
            isTeeBroken -> false
            isGlobalMode -> true
            else -> checkPackages(hackPackages, callingUid)
        }
    }
    
    fun needGenerate(callingUid: Int): Boolean {
        return when {
            isTeeBroken && isGlobalMode -> true
            isGlobalMode -> false
            isTeeBroken -> checkPackages(generatePackages, callingUid) || checkPackages(hackPackages, callingUid)
            else -> checkPackages(generatePackages, callingUid)
        }
    }
}
