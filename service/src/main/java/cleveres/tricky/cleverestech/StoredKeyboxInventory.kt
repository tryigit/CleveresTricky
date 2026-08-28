package cleveres.tricky.cleverestech

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
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

    const val MAX_ACTIVE_XML_SOURCES = 64
    const val MAX_STORED_SOURCES = 256
    const val MAX_FILENAME_BYTES = 255
    const val MAX_XML_BYTES = 10L * 1024 * 1024
    const val MAX_SCANNED_ENTRIES_PER_DIRECTORY = 4_096

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
        val xmlSources = list(configDir).filter { it.isXml }
        require(xmlSources.size <= MAX_ACTIVE_XML_SOURCES) { "Too many keybox XML files" }
        return xmlSources.filter { it.file.length() in 1..MAX_XML_BYTES }
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

    private fun scan(
        directory: File,
        scope: Scope,
        allowCbox: Boolean,
        output: MutableList<Source>,
    ) {
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            var scanned = 0
            for (entry in entries) {
                if (++scanned > MAX_SCANNED_ENTRIES_PER_DIRECTORY) {
                    throw IOException("Keybox directory contains too many entries")
                }
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