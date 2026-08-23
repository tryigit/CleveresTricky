package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.readUtf8FileSnapshotBounded
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Persists an Auto Identity result without disturbing telephony/attestation-only fields.
 *
 * Auto Identity owns the build-identity keys, so an existing TEMPLATE declaration and its
 * managed block are removed before the resolved Pixel values are written. This prevents the
 * template loader from silently overwriting a freshly fetched build identity on the next reload.
 */
internal object AutoIdentityPersistence {
    private const val MAX_BUILD_VARS_BYTES = 1024L * 1024L
    private const val BUILD_IDENTITY_BLOCK_START = "# BEGIN CLEVERESTRICKY BUILD IDENTITY"
    private const val BUILD_IDENTITY_BLOCK_END = "# END CLEVERESTRICKY BUILD IDENTITY"

    fun save(
        configDir: File,
        result: AutoIdentityManager.Result,
    ): Result<Unit> =
        runCatching {
            val updates = LinkedHashMap(result.buildVars())
            require(updates.isNotEmpty()) { "Auto Identity returned no build fields" }
            updates.forEach { (key, value) ->
                require(Config.isValidBuildVarEntry(key, value)) { "Auto Identity returned an invalid build field" }
            }

            synchronized(ManagedFileCoordinator.monitor) {
                val file = File(configDir, "spoof_build_vars")
                val path = file.toPath()
                require(!Files.isSymbolicLink(path)) { "Identity configuration must not be a symbolic link" }
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        "Identity configuration must be a regular file"
                    }
                }

                val source =
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        readUtf8FileSnapshotBounded(file, 0, MAX_BUILD_VARS_BYTES)
                    } else {
                        ""
                    }
                val rewritten = ArrayList<String>()
                val processed = HashSet<String>()
                var insideManagedBuildIdentity = false

                source.lineSequence().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed == BUILD_IDENTITY_BLOCK_START) {
                        require(!insideManagedBuildIdentity) { "Nested managed build identity block" }
                        insideManagedBuildIdentity = true
                        return@forEach
                    }
                    if (insideManagedBuildIdentity) {
                        if (trimmed == BUILD_IDENTITY_BLOCK_END) insideManagedBuildIdentity = false
                        return@forEach
                    }
                    if (trimmed == BUILD_IDENTITY_BLOCK_END) {
                        throw IllegalArgumentException("Unexpected managed build identity terminator")
                    }
                    val separator = if (trimmed.startsWith("#")) -1 else trimmed.indexOf('=')
                    val key = if (separator > 0) trimmed.substring(0, separator).trim() else ""
                    if (key == "TEMPLATE") return@forEach
                    if (key in updates) {
                        if (processed.add(key)) rewritten += "$key=${updates.getValue(key)}"
                    } else {
                        rewritten += line
                    }
                }
                require(!insideManagedBuildIdentity) { "Unterminated managed build identity block" }

                updates.forEach { (key, value) ->
                    if (processed.add(key)) rewritten += "$key=$value"
                }
                while (rewritten.isNotEmpty() && rewritten.last().isBlank()) rewritten.removeAt(rewritten.lastIndex)
                val content = rewritten.joinToString("\n", postfix = if (rewritten.isEmpty()) "" else "\n")
                require(content.toByteArray(Charsets.UTF_8).size <= MAX_BUILD_VARS_BYTES) {
                    "Identity configuration is too large"
                }

                SecureFile.writeText(file, content)
                Config.updateBuildVars(file).getOrThrow()
            }
        }.onFailure { error ->
            Logger.e("Failed to persist Auto Identity", error)
        }
}
