package cleveres.tricky.cleverestech

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit

/** Applies and restores only optional device-wide Identity properties. */
internal object IdentityRuntimeApplier {
    data class Result(
        val applied: Boolean,
        val rebootRequired: Boolean,
        val processRestartRecommended: Boolean,
        val reason: String,
        val buildApplied: Boolean = false,
        val regionApplied: Boolean = false,
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("applied", applied)
                .put("rebootRequired", rebootRequired)
                .put("processRestartRecommended", processRestartRecommended)
                .put("reason", reason)
                .put("buildApplied", buildApplied)
                .put("regionApplied", regionApplied)
    }

    private val moduleRoots =
        listOf(
            File("/data/adb/modules/cleverestricky"),
            File("/data/adb/ksu/modules/cleverestricky"),
            File("/data/adb/ap/modules/cleverestricky"),
        )
    private val expectedRegion =
        linkedMapOf(
            "ro.boot.hwc" to "CN",
            "gsm.operator.iso-country" to "cn",
            "gsm.sim.operator.iso-country" to "cn",
            "ro.boot.hwlevel" to "MP",
            "persist.radio.skhwc_matchres" to "MATCH",
        )

    fun apply(configDir: File): Result {
        val build = PolicyState.isTopLevelFeatureEnabled(PolicyState.Feature.BUILD_IDENTITY)
        val region = PolicyState.isTopLevelFeatureEnabled(PolicyState.Feature.REGION_IDENTITY)
        if (!build && !region) return Result(false, false, false, "disabled")

        val expectedFingerprint = Config.getBuildIdentity()["FINGERPRINT"].orEmpty()
        val buildConfigured = !build || expectedFingerprint.isNotBlank()
        val script = findScript() ?: return Result(false, true, false, "script_unavailable")

        val process =
            try {
                ProcessBuilder("/system/bin/sh", script.absolutePath)
                    .redirectOutput(File("/dev/null"))
                    .redirectError(File("/dev/null"))
                    .apply {
                        environment()["CLEVERES_TRICKY_IDENTITY_ONLY"] = "1"
                        environment()["CLEVERES_TRICKY_CONFIG_DIR"] = configDir.absolutePath
                    }.start()
            } catch (error: Exception) {
                Logger.w("Live Identity shell is unavailable: ${error.javaClass.simpleName}")
                return Result(false, true, false, "shell_unavailable")
            }
        try {
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                Logger.e("Live Identity apply timed out")
                return Result(false, true, false, "timeout")
            }
            if (process.exitValue() != 0) {
                Logger.e("Live Identity apply failed with exit=${process.exitValue()}")
                return Result(false, true, false, "apply_failed")
            }
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }

        val buildApplied =
            build && buildConfigured && systemPropertiesGet("ro.build.fingerprint", "").orEmpty() == expectedFingerprint
        val regionApplied =
            region && expectedRegion.all { (property, expected) -> systemPropertiesGet(property, "").orEmpty() == expected }
        if (build && !buildConfigured) {
            Logger.w("Build Identity is enabled, but no configured fingerprint is available")
        }
        if ((build && buildConfigured && !buildApplied) || (region && !regionApplied)) {
            Logger.w("Live Identity verification did not observe every requested property")
            return Result(
                applied = false,
                rebootRequired = true,
                processRestartRecommended = buildApplied || regionApplied,
                reason = "verification_failed",
                buildApplied = buildApplied,
                regionApplied = regionApplied,
            )
        }
        if (build && !buildConfigured) {
            return Result(
                applied = region && regionApplied,
                rebootRequired = false,
                processRestartRecommended = regionApplied,
                reason = "build_not_configured",
                buildApplied = false,
                regionApplied = regionApplied,
            )
        }

        Logger.i("Identity properties applied live; existing app processes may need restart")
        return Result(true, false, true, "applied", buildApplied, regionApplied)
    }

    fun restore(
        configDir: File,
        restoreBuild: Boolean,
        restoreRegion: Boolean,
    ): Result {
        if (!restoreBuild && !restoreRegion) return Result(false, false, false, "nothing_to_restore")
        val snapshot =
            runCatching { IdentityRuntimeSnapshot.read(configDir) }.getOrElse {
                Logger.w("Live Identity rollback snapshot cannot be read")
                return Result(false, true, false, "snapshot_unavailable")
            } ?: return Result(false, true, false, "snapshot_unavailable")
        if ((restoreBuild && !snapshot.buildCaptured) || (restoreRegion && !snapshot.regionCaptured)) {
            return Result(false, true, false, "snapshot_incomplete")
        }

        val properties = LinkedHashSet<String>()
        if (restoreBuild) properties.addAll(IdentityRuntimeSnapshot.buildProperties)
        if (restoreRegion) properties.addAll(IdentityRuntimeSnapshot.regionProperties)
        var commandFailed = false
        properties.forEach { property ->
            val value = snapshot.values[property]
            if (value == null || !setProperty(property, value)) commandFailed = true
        }
        val verified =
            properties.all { property ->
                snapshot.values[property]?.let { expected ->
                    systemPropertiesGet(property, "").orEmpty() == expected
                } == true
            }
        if (commandFailed || !verified) {
            Logger.w("Live Identity rollback was incomplete; reboot is required")
            return Result(false, true, false, if (commandFailed) "restore_failed" else "restore_verification_failed")
        }

        runCatching { IdentityRuntimeSnapshot.release(configDir, restoreBuild, restoreRegion) }
            .onFailure { Logger.w("Restored Identity properties but could not release rollback snapshot") }
        Logger.i("Identity properties restored live; existing app processes may need restart")
        return Result(
            applied = true,
            rebootRequired = false,
            processRestartRecommended = true,
            reason = "restored",
            buildApplied = restoreBuild,
            regionApplied = restoreRegion,
        )
    }

    private fun findScript(): File? =
        moduleRoots
            .asSequence()
            .map { File(it, "post-fs-data.sh") }
            .firstOrNull { file ->
                val path = file.toPath()
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
            }

    private fun setProperty(
        property: String,
        value: String,
    ): Boolean {
        val process =
            try {
                ProcessBuilder("resetprop", "-n", property, value)
                    .redirectOutput(File("/dev/null"))
                    .redirectError(File("/dev/null"))
                    .start()
            } catch (_: Exception) {
                return false
            }
        return try {
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                false
            } else {
                process.exitValue() == 0
            }
        } catch (_: Exception) {
            false
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}
