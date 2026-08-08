package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.Logger
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

object ZipProcessor {
    data class ProcessedPack(
        val cboxFiles: List<Pair<String, ByteArray>>,
        val password: String?,
    )

    private const val MAX_ENTRY_SIZE = 5 * 1024 * 1024
    private const val MAX_METADATA_SIZE = 64 * 1024
    private const val MAX_TOTAL_SIZE = 10 * 1024 * 1024
    private const val MAX_ENTRIES = 128
    private const val MAX_CBOX_FILES = 64
    private const val MAX_PASSWORD_CHARS = 1024

    fun process(inputStream: InputStream): ProcessedPack? {
        val cboxFiles = ArrayList<Pair<String, ByteArray>>()
        var password: String? = null
        var configPassword: String? = null
        var totalSize = 0
        var entryCount = 0

        return try {
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (++entryCount > MAX_ENTRIES) throw SecurityException("ZIP has too many entries")
                    val name = entry.name
                    if (name.isBlank() || name.contains("..") || name.contains('/') || name.contains('\\')) {
                        throw SecurityException("ZIP contains an unsafe entry name")
                    }

                    if (!entry.isDirectory) {
                        val entryLimit =
                            if (name == "password.txt" || name == "config.json") {
                                MAX_METADATA_SIZE
                            } else {
                                MAX_ENTRY_SIZE
                            }
                        val content =
                            readEntry(zip, entryLimit)
                                ?: throw SecurityException("ZIP entry exceeds its size limit")
                        totalSize += content.size
                        if (totalSize > MAX_TOTAL_SIZE) {
                            content.fill(0)
                            throw SecurityException("ZIP expands beyond its total size limit")
                        }

                        when {
                            name.endsWith(".cbox", ignoreCase = true) -> {
                                if (cboxFiles.size >= MAX_CBOX_FILES) {
                                    content.fill(0)
                                    throw SecurityException("ZIP has too many CBOX files")
                                }
                                cboxFiles.add(name to content)
                            }
                            name == "password.txt" -> {
                                password = String(content, StandardCharsets.UTF_8).trim()
                                content.fill(0)
                            }
                            name == "config.json" -> {
                                val config = JSONObject(String(content, StandardCharsets.UTF_8))
                                configPassword = config.optString("password").ifEmpty { null }
                                content.fill(0)
                            }
                            else -> content.fill(0)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val effectivePassword = configPassword ?: password
            if ((effectivePassword?.length ?: 0) > MAX_PASSWORD_CHARS || cboxFiles.isEmpty()) {
                cboxFiles.forEach { it.second.fill(0) }
                null
            } else {
                ProcessedPack(cboxFiles, effectivePassword)
            }
        } catch (e: Exception) {
            cboxFiles.forEach { it.second.fill(0) }
            Logger.e("Failed to process ZIP: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun readEntry(
        zip: ZipInputStream,
        maxBytes: Int,
    ): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count > maxBytes - total) return null
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }
}
