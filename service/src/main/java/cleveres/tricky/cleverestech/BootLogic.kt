package cleveres.tricky.cleverestech

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Applies explicitly enabled boot-time compatibility settings once per service process. */
object BootLogic {
    private const val CONFIG_PATH = "/data/adb/cleverestricky"
    private const val COMMAND_TIMEOUT_SECONDS = 10L
    private val ran = AtomicBoolean(false)
    private val configDir = File(CONFIG_PATH)

    const val FILE_HIDE_PROPS = "hide_sensitive_props"
    const val FILE_SPOOF_CN = "spoof_region_cn"

    fun run() {
        if (!ran.compareAndSet(false, true)) return

        try {
            val hideSensitive = File(configDir, FILE_HIDE_PROPS).isFile
            val spoofCn = File(configDir, FILE_SPOOF_CN).isFile
            if (hideSensitive || spoofCn) {
                applyPropertyCompatibility(hideSensitive, spoofCn)
            } else {
                Logger.i("Boot property compatibility is disabled")
            }
        } catch (e: Exception) {
            Logger.e("BootLogic failed", e)
        }
    }

    /**
     * Adjusts userspace property views for app compatibility. These values do
     * not alter verified boot, the bootloader state, or hardware attestation.
     */
    private fun applyPropertyCompatibility(
        hideSensitive: Boolean,
        spoofCn: Boolean,
    ) {
        val shamikoExists =
            listOf(
                "/data/adb/modules/zygisk_shamiko",
                "/data/adb/ksu/modules/zygisk_shamiko",
                "/data/adb/ap/modules/zygisk_shamiko",
            ).any { File(it).isDirectory }
        if (shamikoExists && hideSensitive) {
            Logger.i("Shamiko detected; skipping overlapping property overrides")
            if (spoofCn) {
                resetPropBatch(
                    mapOf(
                        "ro.boot.hwc" to "CN",
                        "gsm.operator.iso-country" to "cn",
                    ),
                )
            }
            return
        }

        val properties = linkedMapOf<String, String>()
        if (hideSensitive) {
            properties.putAll(
                mapOf(
                    "ro.boot.vbmeta.device_state" to "locked",
                    "ro.boot.verifiedbootstate" to "green",
                    "ro.boot.flash.locked" to "1",
                    "ro.boot.veritymode" to "enforcing",
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
                    "vendor.boot.vbmeta.device_state" to "locked",
                    "vendor.boot.verifiedbootstate" to "green",
                    "sys.oem_unlock_allowed" to "0",
                    "ro.secureboot.lockstate" to "locked",
                    "ro.oem_unlock_supported" to "0",
                    "ro.boot.realmebootstate" to "green",
                    "ro.boot.realme.lockstate" to "1",
                ),
            )
        }

        if (spoofCn) {
            properties["ro.boot.hwc"] = "CN"
            properties["gsm.operator.iso-country"] = "cn"
            properties["gsm.sim.operator.iso-country"] = "cn"
            properties["ro.boot.hwlevel"] = "MP"
            properties["persist.radio.skhwc_matchres"] = "MATCH"
        }

        resetPropBatch(properties)
        if (hideSensitive) {
            listOf("ro.bootmode", "ro.boot.bootmode", "vendor.boot.bootmode").forEach(::hideBootMode)
        }
        Logger.i("Boot property compatibility applied")
    }

    /** Uses one bounded shell process; all names and values are module constants. */
    private fun resetPropBatch(properties: Map<String, String>) {
        if (properties.isEmpty()) return
        val script =
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

    private fun execChecked(command: Array<String>) {
        val process =
            ProcessBuilder(*command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
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
