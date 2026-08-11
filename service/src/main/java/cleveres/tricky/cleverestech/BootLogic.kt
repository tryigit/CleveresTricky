package cleveres.tricky.cleverestech

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Applies always-on boot protection plus optional identity compatibility once per service process. */
object BootLogic {
    private const val CONFIG_PATH = "/data/adb/cleverestricky"
    private const val COMMAND_TIMEOUT_SECONDS = 10L
    private const val ANDROID_16_SDK = 36
    private const val OEM_UNLOCK_ALLOWED_PROPERTY = "sys.oem_unlock_allowed"
    private val nullDevice = File("/dev/null")
    private val ran = AtomicBoolean(false)

    private val configDir: File
        get() = Config.getConfigRoot().takeIf { it.path.isNotEmpty() } ?: File(CONFIG_PATH)

    /** Legacy flag retained only so upgrades/backups remain readable; core hiding no longer depends on it. */
    const val FILE_HIDE_PROPS = "hide_sensitive_props"
    const val FILE_SPOOF_CN = "spoof_region_cn"
    const val FILE_BOOT_PROPS_MODE = "boot_props_mode"

    private enum class BootPropsMode {
        AUTO,
        FORCE,
        DISABLE,
    }

    fun run() {
        if (!ran.compareAndSet(false, true)) return

        try {
            val mode = readBootPropsMode()
            val requestedBuildIdentity = PolicyState.isFeatureEnabled(PolicyState.Feature.BUILD_IDENTITY)
            val buildIdentity = requestedBuildIdentity && shouldApplyBuildIdentity(mode)
            val spoofCn =
                PolicyState.isFeatureEnabled(PolicyState.Feature.REGION_IDENTITY) &&
                    mode != BootPropsMode.DISABLE

            // Bootloader / verified-boot property protection is a core module feature.
            // It is deliberately not tied to Spoof Engine, hide_sensitive_props, or
            // boot_props_mode. The latter only controls optional identity properties.
            applyPropertyCompatibility(spoofCn, buildIdentity)

            if (requestedBuildIdentity && !buildIdentity) {
                Logger.i("Identity build properties were skipped by the ${mode.name.lowercase()} compatibility policy")
            }
        } catch (e: Exception) {
            Logger.e("BootLogic failed", e)
        }
    }

    private fun readBootPropsMode(): BootPropsMode {
        val file = File(configDir, FILE_BOOT_PROPS_MODE)
        if (!isRegularFile(file) || file.length() !in 1..16) return BootPropsMode.AUTO
        return when (runCatching { file.readText().trim().lowercase() }.getOrDefault("auto")) {
            "force" -> BootPropsMode.FORCE
            "disable" -> BootPropsMode.DISABLE
            else -> BootPropsMode.AUTO
        }
    }

    private fun shouldApplyBuildIdentity(mode: BootPropsMode): Boolean {
        if (mode == BootPropsMode.DISABLE) return false
        if (mode == BootPropsMode.FORCE) return true

        val conflicts =
            listOf(
                "/data/adb/modules",
                "/data/adb/ksu/modules",
                "/data/adb/ap/modules",
            ).any { path ->
                val directory = File(path)
                if (!isDirectory(directory)) return@any false
                runCatching {
                    Files.newDirectoryStream(directory.toPath()).use { entries ->
                        entries.any { entry ->
                            val candidate = entry.toFile()
                            isDirectory(candidate) &&
                                !isRegularFile(File(candidate, "disable")) &&
                                isBuildIdentityProviderModuleId(candidate.name)
                        }
                    }
                }.getOrDefault(false)
            }
        if (conflicts) {
            Logger.i("Another build-identity provider was detected; template properties remain untouched in auto mode")
            return false
        }
        return true
    }

    @androidx.annotation.VisibleForTesting
    internal fun isBuildIdentityProviderModuleId(moduleId: String): Boolean {
        val id = moduleId.trim().lowercase()
        return id.contains("playintegrity") ||
            id.contains("autopif") ||
            id.contains("auto_pif") ||
            id == "pif" ||
            id.startsWith("pif_") ||
            id.contains("playcurl")
    }

    /**
     * Adjusts userspace property views for app compatibility. These values do
     * not alter verified boot, the physical bootloader state, or hardware roots.
     */
    private fun applyPropertyCompatibility(
        spoofCn: Boolean,
        buildIdentity: Boolean,
    ) {
        val properties =
            linkedMapOf(
                "ro.boot.vbmeta.device_state" to "locked",
                "ro.boot.verifiedbootstate" to "green",
                "ro.boot.flash.locked" to "1",
                "ro.boot.warranty_bit" to "0",
                "ro.warranty_bit" to "0",
                "ro.debuggable" to "0",
                "ro.force.debuggable" to "0",
                "ro.secure" to "1",
                "ro.adb.secure" to "1",
                "ro.build.type" to "user",
                "ro.build.tags" to "release-keys",
                "ro.vendor.boot.warranty_bit" to "0",
                "ro.vendor.warranty_bit" to "0",
                "ro.secureboot.lockstate" to "locked",
                "ro.boot.realmebootstate" to "green",
                "ro.boot.realme.lockstate" to "1",
            )
        val sdk = getSystemProperty("ro.build.version.sdk").toIntOrNull()
        if (sdk != null && sdk < ANDROID_16_SDK) {
            properties[OEM_UNLOCK_ALLOWED_PROPERTY] = "0"
        }

        if (spoofCn) {
            properties["ro.boot.hwc"] = "CN"
            properties["gsm.operator.iso-country"] = "cn"
            properties["gsm.sim.operator.iso-country"] = "cn"
            properties["ro.boot.hwlevel"] = "MP"
            properties["persist.radio.skhwc_matchres"] = "MATCH"
        }

        if (buildIdentity) {
            val identity = Config.getBuildIdentity()
            val fingerprint = identity["FINGERPRINT"]
            if (fingerprint.isNullOrBlank()) {
                Logger.w("Build identity spoofing is enabled, but the selected template has no fingerprint")
            } else {
                fun copy(
                    templateKey: String,
                    property: String,
                ) {
                    identity[templateKey]?.takeIf { it.isNotBlank() }?.let { properties[property] = it }
                }
                properties["ro.build.fingerprint"] = fingerprint
                copy("BRAND", "ro.product.brand")
                copy("DEVICE", "ro.product.device")
                copy("PRODUCT", "ro.product.name")
                copy("MANUFACTURER", "ro.product.manufacturer")
                copy("MODEL", "ro.product.model")
                copy("BUILD_ID", "ro.build.id")
                copy("RELEASE", "ro.build.version.release")
                copy("RELEASE", "ro.build.version.release_or_codename")
                copy("INCREMENTAL", "ro.build.version.incremental")
                copy("TYPE", "ro.build.type")
                copy("TAGS", "ro.build.tags")
            }
        }

        resetPropBatch(properties)
        removeLegacyOemUnlockProperty()
        listOf("ro.bootmode", "ro.boot.bootmode", "vendor.boot.bootmode").forEach(::hideBootMode)
        val mismatches = properties.count { (name, value) -> getSystemProperty(name) != value }
        if (mismatches != 0) {
            throw IOException("Could not verify $mismatches boot-property overrides")
        }
        Logger.i("Verified ${properties.size} app-visible boot-property overrides")
    }

    private fun removeLegacyOemUnlockProperty() {
        val sdk = getSystemProperty("ro.build.version.sdk").toIntOrNull() ?: return
        if (sdk < ANDROID_16_SDK) return

        execChecked(arrayOf("resetprop", "--delete", OEM_UNLOCK_ALLOWED_PROPERTY))
        if (getSystemProperty(OEM_UNLOCK_ALLOWED_PROPERTY).isNotEmpty()) {
            throw IOException("Could not remove a legacy OEM-unlock property")
        }
    }

    /** Uses one bounded shell process; all names and values are module constants. */
    private fun resetPropBatch(properties: Map<String, String>) {
        if (properties.isEmpty()) return
        val script =
            "command -v resetprop >/dev/null 2>&1 || exit 127; " +
                properties.entries.joinToString(" ; ") { (name, value) ->
                    "resetprop -n ${shellEscape(name)} ${shellEscape(value)}"
                }
        execChecked(arrayOf("/system/bin/sh", "-c", script))
    }

    private fun shellEscape(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun hideBootMode(name: String) {
        if (getSystemProperty(name).contains("recovery", ignoreCase = true)) {
            execChecked(arrayOf("resetprop", "-n", name, "unknown"))
        }
    }

    private fun getSystemProperty(key: String): String = systemPropertiesGet(key, "").orEmpty()

    private fun isRegularFile(file: File): Boolean = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun isDirectory(file: File): Boolean = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun execChecked(command: Array<String>) {
        val process =
            ProcessBuilder(*command)
                .redirectOutput(nullDevice)
                .redirectError(nullDevice)
                .start()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("Command timed out: ${command.firstOrNull().orEmpty()}")
        }
        if (process.exitValue() != 0) {
            throw IOException("Command failed with exit ${process.exitValue()}: ${command.firstOrNull().orEmpty()}")
        }
    }
}
