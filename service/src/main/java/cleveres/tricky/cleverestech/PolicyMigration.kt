package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

/**
 * Performs narrowly-scoped, loss-minimizing repairs for persisted policy state
 * after upgrades. The policy parser still owns schema validation; this helper
 * repairs references that can become stale and makes last-known-good recovery
 * durable before Config initializes.
 */
object PolicyMigration {
    private const val STATE_FILE = "policy_state_v2.json"
    private const val LAST_GOOD_FILE = "policy_state_v2.last_good.json"
    private const val INVALID_FILE = "policy_state_v2.invalid.json"
    private const val SCHEMA_VERSION = 2
    private const val MAX_STATE_BYTES = 512L * 1024
    private val keyboxPattern = Regex("[A-Za-z0-9_.-]{5,128}")

    private enum class Status {
        MISSING,
        VALID,
        REPAIRED,
        MALFORMED,
        UNSUPPORTED,
        UNSAFE,
    }

    private data class Outcome(
        val status: Status,
        val text: String? = null,
        val originalText: String? = null,
    ) {
        val usable: Boolean
            get() = status == Status.VALID || status == Status.REPAIRED

        val changed: Boolean
            get() = status == Status.REPAIRED
    }

    fun sanitize(configRoot: File): Boolean {
        val lastGoodFile = File(configRoot, LAST_GOOD_FILE)
        val lastGood = sanitizeFile(configRoot, lastGoodFile)
        var changed = lastGood.changed

        val stateFile = File(configRoot, STATE_FILE)
        var main = sanitizeFile(configRoot, stateFile)
        changed = main.changed || changed

        if (main.status == Status.MALFORMED && lastGood.usable && lastGood.text != null) {
            main.originalText?.let { preserveInvalidState(configRoot, it) }
            if (safeWriteState(stateFile, lastGood.text)) {
                Logger.w("Recovered malformed configured policy state from last-known-good state")
                main = Outcome(Status.REPAIRED, lastGood.text)
                changed = true
            }
        }

        if (main.usable && main.text != null &&
            (lastGood.status == Status.MISSING || lastGood.status == Status.MALFORMED)
        ) {
            if (safeWriteState(lastGoodFile, main.text)) {
                Logger.i("Refreshed last-known-good policy state during upgrade recovery")
                changed = true
            }
        }

        return changed
    }

    private fun sanitizeFile(
        configRoot: File,
        stateFile: File,
    ): Outcome {
        val path = stateFile.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Outcome(Status.MISSING)
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            Logger.w("Refusing unsafe persisted policy state during migration: ${stateFile.name}")
            return Outcome(Status.UNSAFE)
        }
        if (stateFile.length() !in 1..MAX_STATE_BYTES) {
            Logger.w("Persisted policy state has an invalid size: ${stateFile.name}")
            return Outcome(Status.MALFORMED)
        }

        val originalText =
            runCatching { stateFile.readText(Charsets.UTF_8) }
                .getOrElse {
                    Logger.w("Could not read persisted policy state during migration: ${stateFile.name}")
                    return Outcome(Status.MALFORMED)
                }
        if (originalText.toByteArray(Charsets.UTF_8).size !in 1..MAX_STATE_BYTES) {
            return Outcome(Status.MALFORMED)
        }

        val json =
            runCatching { JSONObject(originalText) }
                .getOrElse {
                    Logger.w("Persisted policy state is malformed: ${stateFile.name}")
                    return Outcome(Status.MALFORMED, originalText = originalText)
                }
        val version =
            runCatching {
                if (!json.has("version")) null else json.getInt("version")
            }.getOrNull()
        if (version == null) {
            return Outcome(Status.MALFORMED, originalText = originalText)
        }
        if (version != SCHEMA_VERSION) {
            Logger.w("Leaving unsupported policy schema version $version untouched")
            return Outcome(Status.UNSUPPORTED, originalText = originalText)
        }

        var changed = false
        val profiles = json.optJSONArray("profiles")
        val profileNames = HashSet<String>()
        if (profiles != null) {
            for (index in 0 until profiles.length()) {
                val profile = profiles.optJSONObject(index) ?: continue
                profile
                    .optString("name")
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.lowercase(Locale.ROOT)
                    ?.let(profileNames::add)

                if (profile.has("rkpPassthrough")) {
                    profile.remove("rkpPassthrough")
                    changed = true
                }

                if (!profile.isNull("keybox")) {
                    val keybox = profile.optString("keybox").trim()
                    if (keybox.isNotEmpty() && !isAvailableKeybox(configRoot, keybox)) {
                        profile.put("keybox", JSONObject.NULL)
                        changed = true
                        Logger.w("Removed stale profile keybox reference during policy migration")
                    }
                }
            }
        }

        if (!json.isNull("activeProfile")) {
            val active = json.optString("activeProfile").trim()
            if (active.isNotEmpty() && active.lowercase(Locale.ROOT) !in profileNames) {
                json.put("activeProfile", JSONObject.NULL)
                changed = true
                Logger.w("Removed stale active profile reference during policy migration")
            }
        }

        val repairedText = json.toString()
        if (PolicyState.validateStateJson(repairedText, validateReferences = false).isFailure) {
            Logger.w("Persisted policy state could not be validated after migration: ${stateFile.name}")
            return Outcome(Status.MALFORMED, originalText = originalText)
        }

        if (!changed) return Outcome(Status.VALID, repairedText, originalText)
        if (!safeWriteState(stateFile, repairedText)) {
            return Outcome(Status.UNSAFE, originalText = originalText)
        }
        return Outcome(Status.REPAIRED, repairedText, originalText)
    }

    private fun preserveInvalidState(
        configRoot: File,
        content: String,
    ) {
        if (content.toByteArray(Charsets.UTF_8).size !in 1..MAX_STATE_BYTES) return
        val invalidFile = File(configRoot, INVALID_FILE)
        if (safeWriteState(invalidFile, content)) {
            Logger.w("Preserved malformed configured policy state as $INVALID_FILE")
        }
    }

    private fun safeWriteState(
        file: File,
        content: String,
    ): Boolean {
        if (content.toByteArray(Charsets.UTF_8).size !in 1..MAX_STATE_BYTES) return false
        val path = file.toPath()
        if (
            Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        ) {
            Logger.w("Refusing unsafe policy migration destination: ${file.name}")
            return false
        }
        return runCatching {
            SecureFile.writeText(file, content)
            true
        }.getOrElse {
            Logger.e("Failed to persist policy migration result: ${file.name}", it)
            false
        }
    }

    private fun isAvailableKeybox(
        configRoot: File,
        filename: String,
    ): Boolean {
        if (!keyboxPattern.matches(filename) || filename.startsWith('.')) return false
        val lower = filename.lowercase(Locale.ROOT)
        if (!lower.endsWith(".xml") && !lower.endsWith(".cbox")) return false
        val candidates =
            arrayOf(
                File(configRoot, filename),
                File(File(configRoot, "keyboxes"), filename),
            )
        return candidates.any { candidate ->
            val path = candidate.toPath()
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
        }
    }
}
