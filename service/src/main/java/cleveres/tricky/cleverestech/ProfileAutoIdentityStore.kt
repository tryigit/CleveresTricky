package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.readUtf8FileSnapshotBounded
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Persisted app-scoped Auto Identity snapshot.
 *
 * The profile scheduler owns this file. Global/manual Auto Identity continues to own
 * spoof_build_vars, so a profile-only refresh cannot alter the next device-wide boot identity.
 */
internal object ProfileAutoIdentityStore {
    const val FILE_NAME = "profile_auto_identity_vars"
    private const val MAX_BYTES = 64L * 1024L
    private const val MAX_ENTRIES = 32
    private val allowedKeys =
        setOf(
            "BRAND",
            "DEVICE",
            "PRODUCT",
            "MANUFACTURER",
            "MODEL",
            "FINGERPRINT",
            "RELEASE",
            "BUILD_ID",
            "INCREMENTAL",
            "TYPE",
            "TAGS",
            "SECURITY_PATCH",
        )

    @Volatile
    private var values: Map<String, String> = emptyMap()

    fun get(key: String): String? = values[key]

    fun save(
        configDir: File,
        result: AutoIdentityManager.Result,
    ): Result<Unit> =
        runCatching {
            requireSafeRoot(configDir)
            val updates = canonicalEntries(result)
            validateEntries(updates)
            val file = File(configDir, FILE_NAME)
            requireSafeFile(file)
            val content = updates.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" }
            require(content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
                "Profile Auto Identity snapshot is too large"
            }
            SecureFile.writeText(file, content)
            values = updates.toMap()
            Logger.i("Profile Auto Identity snapshot updated (${updates.size} Build fields)")
        }.onFailure { error ->
            Logger.e("Failed to persist Profile Auto Identity snapshot", error)
        }

    fun load(configDir: File): Result<Unit> =
        runCatching {
            requireSafeRoot(configDir)
            val file = File(configDir, FILE_NAME)
            val path = file.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                values = emptyMap()
                return@runCatching
            }
            requireSafeFile(file)
            val parsed = LinkedHashMap<String, String>()
            readUtf8FileSnapshotBounded(file, 0, MAX_BYTES).lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                val separator = trimmed.indexOf('=')
                require(separator in 1 until trimmed.lastIndex) { "Invalid Profile Auto Identity entry" }
                val key = trimmed.substring(0, separator).trim()
                val value = trimmed.substring(separator + 1).trim()
                require(key in allowedKeys) { "Unsupported Profile Auto Identity field" }
                require(Config.isValidBuildVarEntry(key, value)) { "Invalid Profile Auto Identity field" }
                require(!parsed.containsKey(key)) { "Duplicate Profile Auto Identity field" }
                require(parsed.size < MAX_ENTRIES) { "Too many Profile Auto Identity fields" }
                parsed[key] = value
            }
            validateEntries(parsed)
            values = parsed.toMap()
        }.onFailure { error ->
            values = emptyMap()
            Logger.e("Failed to load Profile Auto Identity snapshot; profile Auto Identity is unavailable", error)
        }

    private fun canonicalEntries(result: AutoIdentityManager.Result): LinkedHashMap<String, String> {
        val entries = LinkedHashMap(result.buildVars())
        if (!entries.containsKey("RELEASE")) {
            val release = result.fingerprint.substringAfter(':', "").substringBefore('/').trim()
            require(release.isNotEmpty()) { "Auto Identity fingerprint does not contain an Android release" }
            entries["RELEASE"] = release
        }
        return entries
    }

    private fun validateEntries(entries: Map<String, String>) {
        require(entries.keys.containsAll(allowedKeys) && entries.size == allowedKeys.size) {
            "Profile Auto Identity snapshot is incomplete"
        }
        require(entries.size <= MAX_ENTRIES) { "Too many Profile Auto Identity fields" }
        entries.forEach { (key, value) ->
            require(key in allowedKeys) { "Unsupported Profile Auto Identity field" }
            require(Config.isValidBuildVarEntry(key, value)) { "Invalid Profile Auto Identity field" }
        }
    }

    private fun requireSafeRoot(configDir: File) {
        val path = configDir.toPath()
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "Profile Auto Identity configuration root is unsafe"
        }
    }

    private fun requireSafeFile(file: File) {
        val path = file.toPath()
        require(!Files.isSymbolicLink(path)) { "Profile Auto Identity snapshot must not be a symbolic link" }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                "Profile Auto Identity snapshot must be a regular file"
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        values = emptyMap()
    }
}
