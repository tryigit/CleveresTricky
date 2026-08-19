package cleveres.tricky.cleverestech

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Locale

/** One bounded inventory for keybox sources visible to both runtime and WebUI. */
internal object StoredKeyboxInventory {
    enum class Scope(
        val apiValue: String,
        val fileScope: KeyboxLoader.FileScope?,
    ) {
        ROOT("root", KeyboxLoader.FileScope.CONFIG_ROOT),
        KEYBOXES("keyboxes", KeyboxLoader.FileScope.KEYBOX_DIRECTORY),
    }

    data class Source(
        val scope: Scope,
        val filename: String,
        val file: File,
    ) {
        val id: String
            get() = "${scope.apiValue}:$filename"
        val isXml: Boolean
            get() = filename.endsWith(".xml", ignoreCase = true)
        val isCbox: Boolean
            get() = filename.endsWith(".cbox", ignoreCase = true)
    }

    /**
     * Config's hot-path cache historically keyed XML sources by File.lastModified + length.
     * Preserve that API while making lastModified content-aware for inventory-created XML
     * handles, so an equal-size replacement with a restored filesystem timestamp cannot
     * keep stale key material alive in memory.
     */
    private class ContentStampedFile(pathname: String) : File(pathname) {
        private val physicalLastModified = super.lastModified()
        private val contentStamp: Long by lazy(LazyThreadSafetyMode.NONE) { calculateContentStamp() }

        override fun lastModified(): Long = contentStamp

        private fun calculateContentStamp(): Long {
            val path = toPath()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return physicalLastModified
            val digest = MessageDigest.getInstance("SHA-256")
            return runCatching {
                Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    try {
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) digest.update(buffer, 0, count)
                        }
                    } finally {
                        buffer.fill(0)
                    }
                }
                val bytes = digest.digest()
                try {
                    ByteBuffer.wrap(bytes, 0, Long.SIZE_BYTES).long xor physicalLastModified
                } finally {
                    bytes.fill(0)
                }
            }.getOrDefault(physicalLastModified)
        }
    }

    const val MAX_ACTIVE_XML_SOURCES = 64
    const val MAX_STORED_SOURCES = 256
    const val MAX_FILENAME_BYTES = 255
    const val MAX_XML_BYTES = 10L * 1024 * 1024

    fun list(configDir: File): List<Source> {
        val output = ArrayList<Source>()
        scan(configDir, Scope.ROOT, allowCbox = false, output)
        val keyboxDir = File(configDir, "keyboxes")
        if (Files.isDirectory(keyboxDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            scan(keyboxDir, Scope.KEYBOXES, allowCbox = true, output)
        }
        output.sortWith(
            compareBy<Source> { it.filename.lowercase(Locale.ROOT) }
                .thenBy { it.scope.apiValue },
        )
        return output
    }

    fun runtimeXmlSources(configDir: File): List<Source> {
        val sources = list(configDir).filter { it.isXml }
        require(sources.size <= MAX_ACTIVE_XML_SOURCES) { "Too many keybox XML files" }
        // Preserve the V2.6.0 upgrade contract: a legacy XML in the module root and a
        // managed XML in keyboxes/ may share the same basename. Source IDs remain
        // scope-qualified for cache, inventory and deletion, while CertHack intentionally
        // groups same-name keyboxes into one selectable filename pool.
        return sources
    }

    fun resolve(
        configDir: File,
        scopeValue: String,
        filename: String,
    ): Source? {
        val scope = Scope.entries.firstOrNull { it.apiValue == scopeValue } ?: return null
        if (!isSafeStoredName(filename, allowCbox = scope == Scope.KEYBOXES)) return null
        val directory = if (scope == Scope.ROOT) configDir else File(configDir, "keyboxes")
        val candidate = File(directory, filename)
        if (candidate.parentFile?.canonicalFile != directory.canonicalFile) return null
        if (!Files.isRegularFile(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return Source(scope, filename, inventoryFile(candidate, filename))
    }

    private fun scan(
        directory: File,
        scope: Scope,
        allowCbox: Boolean,
        output: MutableList<Source>,
    ) {
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) {
                val filename = entry.fileName.toString()
                if (!isSafeStoredName(filename, allowCbox)) continue
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue
                require(output.size < MAX_STORED_SOURCES) { "Too many stored keybox sources" }
                val file = entry.toFile()
                output += Source(scope, filename, inventoryFile(file, filename))
            }
        }
    }

    private fun inventoryFile(
        file: File,
        filename: String,
    ): File =
        if (filename.endsWith(".xml", ignoreCase = true)) ContentStampedFile(file.path) else file

    private fun isSafeStoredName(
        filename: String,
        allowCbox: Boolean,
    ): Boolean {
        if (filename.isEmpty() || filename.startsWith('.') || filename.contains('/') || filename.contains('\\') || filename.contains('\u0000')) return false
        if (filename.toByteArray(Charsets.UTF_8).size > MAX_FILENAME_BYTES) return false
        if (filename.any { it.code < 0x20 || it.code == 0x7f }) return false
        return filename.endsWith(".xml", ignoreCase = true) ||
            (allowCbox && filename.endsWith(".cbox", ignoreCase = true))
    }
}
