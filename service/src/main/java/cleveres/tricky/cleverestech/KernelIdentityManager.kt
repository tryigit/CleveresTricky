package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.readUtf8FileSnapshotBounded
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

object KernelIdentityManager {
    data class Preset(val id: String, val label: String, val release: String, val version: String)
    data class Config(val enabled: Boolean, val preset: String, val release: String, val version: String)

    private const val FILE_NAME = "kernel_identity.json"
    private const val MAX_BYTES = 4096L
    private const val MAX_UTS_FIELD = 64
    private val presets = listOf(
        Preset("android14-5.15", "Android 14 GKI 5.15", "5.15.208-android14", "#1 SMP PREEMPT_DYNAMIC"),
        Preset("android14-6.1", "Android 14 GKI 6.1", "6.1.172-android14", "#1 SMP PREEMPT_DYNAMIC"),
        Preset("android15-6.6", "Android 15 GKI 6.6", "6.6.139-android15", "#1 SMP PREEMPT_DYNAMIC"),
        Preset("android16-6.12", "Android 16 GKI 6.12", "6.12.81-android16", "#1 SMP PREEMPT_DYNAMIC"),
    )
    private val presetIds = presets.mapTo(HashSet()) { it.id }

    @Volatile private var file: File? = null
    @Volatile private var state = Config(false, "android15-6.6", presets[2].release, presets[2].version)

    @Synchronized
    fun initialize(configDir: File) {
        file = File(configDir, FILE_NAME)
        state = readValidated(file!!)
    }

    fun current(): Config = state

    fun activationPayload(): String {
        val current = state
        return if (current.enabled) "1|${current.release}|${current.version}" else "0||"
    }

    fun json(): JSONObject {
        val current = state
        val catalog = JSONArray()
        presets.forEach { preset ->
            catalog.put(JSONObject().put("id", preset.id).put("label", preset.label).put("release", preset.release).put("version", preset.version))
        }
        return JSONObject()
            .put("enabled", current.enabled)
            .put("preset", current.preset)
            .put("release", current.release)
            .put("version", current.version)
            .put("presets", catalog)
    }

    @Synchronized
    fun save(json: String): Config {
        val obj = JSONObject(json)
        require(obj.length() in 1..4) { "Invalid kernel identity request" }
        val enabled = obj.optBoolean("enabled", false)
        val preset = obj.optString("preset", "custom").trim()
        require(preset == "custom" || preset in presetIds) { "Invalid GKI preset" }
        val selected = presets.firstOrNull { it.id == preset }
        val release = obj.optString("release", selected?.release ?: "").trim()
        val version = obj.optString("version", selected?.version ?: "").trim()
        require(isValidField(release) && isValidField(version)) { "Invalid kernel identity field" }
        val next = Config(enabled, preset, release, version)
        val target = requireNotNull(file) { "Kernel identity manager is not initialized" }
        val stored = JSONObject().put("enabled", enabled).put("preset", preset).put("release", release).put("version", version).toString(2)
        require(stored.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Kernel identity configuration is too large" }
        SecureFile.writeText(target, stored)
        state = next
        return next
    }

    private fun readValidated(target: File): Config {
        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return state
        if (!Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) || target.length() !in 1..MAX_BYTES) {
            Logger.w("Ignoring invalid kernel identity configuration file")
            return state.copy(enabled = false)
        }
        return runCatching {
            val obj = JSONObject(readUtf8FileSnapshotBounded(target, 1, MAX_BYTES))
            val preset = obj.optString("preset", "custom").trim()
            require(preset == "custom" || preset in presetIds)
            val selected = presets.firstOrNull { it.id == preset }
            val release = obj.optString("release", selected?.release ?: "").trim()
            val version = obj.optString("version", selected?.version ?: "").trim()
            require(isValidField(release) && isValidField(version))
            Config(obj.optBoolean("enabled", false), preset, release, version)
        }.getOrElse {
            Logger.w("Ignoring malformed kernel identity configuration")
            state.copy(enabled = false)
        }
    }

    private fun isValidField(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_UTS_FIELD) return false
        return value.none { it.isISOControl() || it == '|' } && value.all {
            it.isLetterOrDigit() || it in " ._+-/#():=@"
        }
    }
}
