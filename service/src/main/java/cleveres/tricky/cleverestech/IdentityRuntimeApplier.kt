package cleveres.tricky.cleverestech

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit

/** Applies only the optional Build Identity property phase from post-fs-data.sh. */
internal object IdentityRuntimeApplier {
    data class Result(
        val applied: Boolean,
        val rebootRequired: Boolean,
        val processRestartRecommended: Boolean,
        val reason: String,
    )

    private val moduleRoots =
        listOf(
            File("/data/adb/modules/cleverestricky"),
            File("/data/adb/ksu/modules/cleverestricky"),
            File("/data/adb/ap/modules/cleverestricky"),
        )

    fun apply(configDir: File): Result {
        if (!PolicyState.isTopLevelFeatureEnabled(PolicyState.Feature.BUILD_IDENTITY)) {
            return Result(false, false, false, "disabled")
        }
        val expectedFingerprint = Config.getBuildIdentity()["FINGERPRINT"].orEmpty()
        if (expectedFingerprint.isBlank()) return Result(false, false, false, "not_configured")

        val script =
            moduleRoots
                .asSequence()
                .map { File(it, "post-fs-data.sh") }
                .firstOrNull { file ->
                    val path = file.toPath()
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
                } ?: return Result(false, true, false, "script_unavailable")

        if (!commandSucceeds(arrayOf("/system/bin/sh", "-c", "command -v resetprop >/dev/null 2>&1"), 3)) {
            return Result(false, true, false, "resetprop_unavailable")
        }

        val process =
            ProcessBuilder("/system/bin/sh", script.absolutePath)
                .redirectOutput(File("/dev/null"))
                .redirectError(File("/dev/null"))
                .apply {
                    environment()["CLEVERES_TRICKY_IDENTITY_ONLY"] = "1"
                    environment()["CLEVERES_TRICKY_CONFIG_DIR"] = configDir.absolutePath
                }.start()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            Logger.e("Live Build Identity apply timed out")
            return Result(false, true, false, "timeout")
        }
        if (process.exitValue() != 0) {
            Logger.e("Live Build Identity apply failed with exit=${process.exitValue()}")
            return Result(false, true, false, "apply_failed")
        }

        val actualFingerprint = systemPropertiesGet("ro.build.fingerprint", "").orEmpty()
        if (actualFingerprint != expectedFingerprint) {
            Logger.w("Live Build Identity verification did not observe the configured fingerprint")
            return Result(false, true, false, "verification_failed")
        }

        Logger.i("Build Identity properties applied live; existing app processes may need restart")
        return Result(true, false, true, "applied")
    }

    private fun commandSucceeds(
        command: Array<String>,
        timeoutSeconds: Long,
    ): Boolean =
        try {
            val process =
                ProcessBuilder(*command)
                    .redirectOutput(File("/dev/null"))
                    .redirectError(File("/dev/null"))
                    .start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                false
            } else {
                process.exitValue() == 0
            }
        } catch (error: Exception) {
            Logger.d { "Live Build Identity prerequisite check failed: ${error.javaClass.simpleName}" }
            false
        }
}
