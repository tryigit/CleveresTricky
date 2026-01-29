package io.github.a13e300.tricky_store

import android.content.pm.IPackageManager
import android.os.FileObserver
import android.os.ServiceManager
import io.github.a13e300.tricky_store.keystore.CertHack
import java.io.File

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
        // Add any other properties from g_target_properties in C++ if they differ
    )

    fun getSpoofedProperty(propertyName: String): String? {
        // For now, global spoofing if property is in the map.
        // Future enhancement: check Config.needHack(callingUid) or similar
        // if per-app spoofing of properties is desired.
        return spoofedProperties[propertyName]
    }

    private val hackPackages = mutableSetOf<String>()
    private val generatePackages = mutableSetOf<String>()
    private var isGlobalMode = false
    private var isTeeBrokenMode = false
    private var moduleHash: ByteArray? = null

    fun getModuleHash(): ByteArray? = moduleHash

    fun parsePackages(lines: List<String>, isTeeBrokenMode: Boolean): Pair<Set<String>, Set<String>> {
        val hackPackages = mutableSetOf<String>()
        val generatePackages = mutableSetOf<String>()
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
        hackPackages.clear()
        generatePackages.clear()
        if (isGlobalMode) {
            Logger.i("Global mode is enabled, skipping updateTargetPackages execution.")
            return@runCatching
        }
        val (h, g) = parsePackages(f?.readLines() ?: emptyList(), isTeeBrokenMode)
        hackPackages.addAll(h)
        generatePackages.addAll(g)
        Logger.i("update hack packages: $hackPackages, generate packages=$generatePackages")
    }.onFailure {
        Logger.e("failed to update target files", it)
    }

    private fun updateKeyBox(f: File?) = runCatching {
        CertHack.readFromXml(f?.readText())
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
        f?.readLines()?.forEach { line ->
            if (line.isNotBlank() && !line.startsWith("#")) {
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    newVars[parts[0].trim()] = parts[1].trim()
                }
            }
        }
        buildVars = newVars
        Logger.i("update build vars: $buildVars")
    }.onFailure {
        Logger.e("failed to update build vars", it)
    @OptIn(ExperimentalStdlibApi::class)
    private fun updateModuleHash(f: File?) = runCatching {
        moduleHash = f?.readText()?.trim()?.hexToByteArray()
        Logger.i("update module hash: ${moduleHash?.joinToString("") { "%02x".format(it) }}")
    }.onFailure {
        moduleHash = null
        Logger.e("failed to update module hash", it)
    }

    private const val CONFIG_PATH = "/data/adb/tricky_store"
    private const val TARGET_FILE = "target.txt"
    private const val KEYBOX_FILE = "keybox.xml"
    private const val GLOBAL_MODE_FILE = "global_mode"
    private const val TEE_BROKEN_MODE_FILE = "tee_broken_mode"
    private const val SPOOF_BUILD_VARS_FILE = "spoof_build_vars"
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
        updateGlobalMode(File(root, GLOBAL_MODE_FILE))
        updateTeeBrokenMode(File(root, TEE_BROKEN_MODE_FILE))
        updateBuildVars(File(root, SPOOF_BUILD_VARS_FILE))
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

    private fun checkPackages(packages: Set<String>, callingUid: Int) = kotlin.runCatching {
        if (packages.isEmpty()) return false
        val ps = getPm()?.getPackagesForUid(callingUid)
        ps?.any { it in packages }
    }.onFailure { Logger.e("failed to get packages", it) }.getOrNull() ?: false

    fun needHack(callingUid: Int): Boolean {
        return when {
            isTeeBrokenMode -> false
            isGlobalMode -> true
            else -> checkPackages(hackPackages, callingUid)
        }
    }
    
    fun needGenerate(callingUid: Int): Boolean {
        return when {
            isTeeBrokenMode && isGlobalMode -> true
            isGlobalMode -> false
            else -> checkPackages(generatePackages, callingUid)
        }
    }
}
