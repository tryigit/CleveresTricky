package cleveres.tricky.cleverestech

import java.io.File
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

    private class ContentStampedFile(
        source: File,
        private val contentStamp: Long,
    ) : File(source.path) {
        override fun lastModified(): Long = contentStamp
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
        //
        // Runtime cache metadata historically used only mtime + length, allowing a replaced
        // XML with identical metadata to retain stale parsed keyboxes. Keep the public/stored
        // Source identity stable, but expose a content-derived lastModified stamp only to the
        // runtime scanner so content replacement always invalidates that cache.
        return sources.map { source ->
            source.copy(file = ContentStampedFile(source.file, contentStamp(source.file)))
        }
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
        return Source(scope, filename, candidate)
    }

    private fun contentStamp(file: File): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
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
        return try {
            var value = 0L
            for (index in 0 until Long.SIZE_BYTES) {
                value = (value shl 8) or (bytes[index].toLong() and 0xffL)
            }
            value
        } finally {
            bytes.fill(0)
        }
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
                output += Source(scope, filename, entry.toFile())
            }
        }
    }

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
