package cleveres.tricky.cleverestech

import android.content.pm.IPackageManager
import android.os.FileObserver
import android.os.ServiceManager
import android.system.Os
import cleveres.tricky.cleverestech.keystore.CertHack
import java.io.File

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

    fun setTeeBroken(broken: Boolean) {
        isAutoTeeBroken = broken
        Logger.i("Auto TEE broken mode is ${if (isAutoTeeBroken) "enabled" else "disabled"}")
    }

    fun getModuleHash(): ByteArray? = moduleHash

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

    // Stream file content to avoid large String allocation and explicit GC
    private fun updateKeyBox(f: File?) = runCatching {
        if (f == null) {
            CertHack.readFromXml(null)
        } else {
            f.bufferedReader().use { CertHack.readFromXml(it) }
        }
    }.onFailure {
        Logger.e("failed to update keybox", it)
    }

    private fun updateGlobalMode(f: File?) {
        isGlobalMode = f?.exists() == true
        Logger.i("Global mode is ${if (isGlobalMode) "enabled" else "disabled"}")
    }

    private fun updateTeeBrokenMode(f: File?) {
        isTeeBrokenMode = f?.exists() == true
        Logger.i("TEE broken mode is ${if (isTeeBrokenMode) "enabled" else "disabled"}")
    }

    @Volatile
    private var buildVars: Map<String, String> = emptyMap()

    fun getBuildVar(key: String): String? {
        return buildVars[key]
    }

    private fun updateBuildVars(f: File?) = runCatching {
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
        buildVars = newVars
        Logger.i { "update build vars: $buildVars" }
    }.onFailure {
        Logger.e("failed to update build vars", it)
    }

    @Volatile
    private var securityPatch: Map<String, String> = emptyMap()
    private var defaultSecurityPatch: String? = null

    fun getPatchLevel(callingUid: Int): Int {
        val defaultLevel = patchLevel
        val patchStr = if (securityPatch.isNotEmpty()) {
            val pkgName = getPm()?.getPackagesForUid(callingUid)?.firstOrNull()
            if (pkgName != null) {
                securityPatch[pkgName] ?: defaultSecurityPatch
            } else {
                defaultSecurityPatch
            }
        } else {
            defaultSecurityPatch
        }

        if (patchStr == null) return defaultLevel

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
        val newPatch = mutableMapOf<String, String>()
        var newDefault: String? = null
        f?.useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        newPatch[parts[0].trim()] = parts[1].trim()
                    } else if (parts.size == 1) {
                         // Assume it's the default if it looks like a date or keyword
                         newDefault = parts[0].trim()
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

    private const val CONFIG_PATH = "/data/adb/cleveres_tricky"
    private const val TARGET_FILE = "target.txt"
    private const val KEYBOX_FILE = "keybox.xml"
    private const val GLOBAL_MODE_FILE = "global_mode"
    private const val TEE_BROKEN_MODE_FILE = "tee_broken_mode"
    private const val SPOOF_BUILD_VARS_FILE = "spoof_build_vars"
    private const val MODULE_HASH_FILE = "module_hash"
    private const val SECURITY_PATCH_FILE = "security_patch.txt"
    private val root = File(CONFIG_PATH)

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
                KEYBOX_FILE -> updateKeyBox(f)
                SPOOF_BUILD_VARS_FILE -> updateBuildVars(f)
                SECURITY_PATCH_FILE -> updateSecurityPatch(f)
                GLOBAL_MODE_FILE -> {
                    updateGlobalMode(f)
                    updateTargetPackages(File(root, TARGET_FILE))
                }

                TEE_BROKEN_MODE_FILE -> {
                    updateTeeBrokenMode(f)
                    updateTargetPackages(File(root, TARGET_FILE))
                }

                MODULE_HASH_FILE -> updateModuleHash(f)
            }
        }
    }

    fun initialize() {
        root.mkdirs()
        try {
            Os.chmod(root.absolutePath, 448) // 0700
        } catch (t: Throwable) {
            Logger.e("failed to set permissions for config dir", t)
        }
        updateGlobalMode(File(root, GLOBAL_MODE_FILE))
        updateTeeBrokenMode(File(root, TEE_BROKEN_MODE_FILE))
        updateBuildVars(File(root, SPOOF_BUILD_VARS_FILE))
        updateModuleHash(File(root, MODULE_HASH_FILE))
        updateSecurityPatch(File(root, SECURITY_PATCH_FILE))
        if (!isGlobalMode) {
            val scope = File(root, TARGET_FILE)
            if (scope.exists()) {
                updateTargetPackages(scope)
            } else {
                Logger.e("target.txt file not found, please put it to $scope !")
            }
        }
        val keybox = File(root, KEYBOX_FILE)
        if (!keybox.exists()) {
            Logger.e("keybox file not found, please put it to $keybox !")
        } else {
            updateKeyBox(keybox)
        }
        ConfigObserver.startWatching()
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

    private fun checkPackages(packages: PackageTrie, callingUid: Int) = kotlin.runCatching {
        if (packages.isEmpty()) return false
        val ps = getPm()?.getPackagesForUid(callingUid) ?: return false
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
