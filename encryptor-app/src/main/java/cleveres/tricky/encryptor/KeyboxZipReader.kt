package cleveres.tricky.encryptor

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal data class SelectedKeybox(
    val bytes: ByteArray,
    val displayName: String,
)

internal object KeyboxZipReader {
    internal const val MAX_KEYBOX_FILES = 64
    internal const val MAX_TOTAL_XML_BYTES = 48 * 1024 * 1024
    internal const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 256
    private const val MAX_ENTRY_NAME_CHARS = 1024
    private const val MAX_XML_BYTES = 10 * 1024 * 1024
    private const val MAX_IGNORED_ENTRY_BYTES = 1024 * 1024
    private const val MAX_TOTAL_IGNORED_BYTES = 4 * 1024 * 1024

    fun read(
        input: InputStream,
        validateXml: (ByteArray) -> Boolean,
    ): List<SelectedKeybox> {
        val selected = ArrayList<SelectedKeybox>()
        var totalXmlBytes = 0
        var totalIgnoredBytes = 0
        var archiveEntries = 0
        try {
            ZipInputStream(CountingInputStream(input, MAX_ARCHIVE_BYTES.toLong())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    archiveEntries++
                    if (archiveEntries > MAX_ARCHIVE_ENTRIES) {
                        throw IOException("ZIP contains too many entries")
                    }
                    if (entry.name.length > MAX_ENTRY_NAME_CHARS || entry.name.indexOf('\u0000') >= 0) {
                        throw IOException("ZIP entry name is invalid")
                    }
                    if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
                        throw IOException("ZIP entry compression is unsupported")
                    }

                    if (entry.isDirectory || !entry.name.endsWith(".xml", ignoreCase = true)) {
                        val ignoredBytes = drainIgnoredEntry(zip)
                        if (ignoredBytes > MAX_TOTAL_IGNORED_BYTES - totalIgnoredBytes) {
                            throw IOException("ZIP contains too much unrelated content")
                        }
                        totalIgnoredBytes += ignoredBytes
                        zip.closeEntry()
                        continue
                    }
                    if (entry.size > MAX_XML_BYTES) {
                        throw IOException("XML file exceeds 10 MiB")
                    }
                    if (selected.size == MAX_KEYBOX_FILES) {
                        throw IOException("ZIP contains too many XML files")
                    }

                    val bytes = readBytes(zip)
                    if (bytes.isEmpty()) {
                        bytes.fill(0)
                        throw IOException("XML file is empty")
                    }
                    if (bytes.size > MAX_TOTAL_XML_BYTES - totalXmlBytes) {
                        bytes.fill(0)
                        throw IOException("ZIP XML content exceeds the total size limit")
                    }
                    if (!validateXml(bytes)) {
                        bytes.fill(0)
                        throw IOException("ZIP contains an invalid keybox XML")
                    }

                    totalXmlBytes += bytes.size
                    selected +=
                        SelectedKeybox(
                            bytes = bytes,
                            displayName = safeDisplayName(entry.name),
                        )
                    zip.closeEntry()
                }
            }
            if (selected.isEmpty()) throw IOException("ZIP does not contain keybox XML files")
            return selected
        } catch (error: Exception) {
            selected.forEach { it.bytes.fill(0) }
            throw error
        }
    }

    private fun drainIgnoredEntry(zip: ZipInputStream): Int {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        try {
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count > MAX_IGNORED_ENTRY_BYTES - total) {
                    throw IOException("Unrelated ZIP entry exceeds 1 MiB")
                }
                total += count
            }
            return total
        } finally {
            buffer.fill(0)
        }
    }

    private fun safeDisplayName(entryName: String): String {
        val basename = entryName.substringAfterLast('/').substringAfterLast('\\').take(255)
        return basename.ifBlank { "keybox.xml" }
    }

    private class CountingInputStream(
        input: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(input) {
        private var consumed = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) account(count.toLong())
            return count
        }

        private fun account(count: Long) {
            consumed += count
            if (consumed > maxBytes) throw IOException("ZIP archive exceeds 64 MiB")
        }
    }
}
