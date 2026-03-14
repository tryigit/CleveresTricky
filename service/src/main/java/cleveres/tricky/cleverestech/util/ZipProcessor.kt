package cleveres.tricky.cleverestech.util

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import org.json.JSONObject
import cleveres.tricky.cleverestech.Logger

object ZipProcessor {
    data class ProcessedPack(
        val cboxFiles: List<Pair<String, ByteArray>>, // filename -> content
        val password: String?,
        val publicKey: String?,
        val config: JSONObject?
    )

    private const val MAX_ENTRY_SIZE = 5 * 1024 * 1024 // 5MB
    private const val MAX_TOTAL_SIZE = 10 * 1024 * 1024 // 10MB

    fun process(inputStream: InputStream): ProcessedPack? {
        val cboxFiles = ArrayList<Pair<String, ByteArray>>()
        var password: String? = null
        var publicKey: String? = null
        var config: JSONObject? = null
        var totalSize = 0

        try {
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.contains("..") || name.contains("/") || name.contains("\\")) {
                        throw SecurityException("Zip entry contains directory traversal: $name")
                    }

                    if (!entry.isDirectory) {
                        // Read content with limits
                        val content = readEntry(zis)
                        if (content == null) {
                            throw SecurityException("Zip entry too large: $name")
                        }
                        totalSize += content.size
                        if (totalSize > MAX_TOTAL_SIZE) {
                            throw SecurityException("Total zip size exceeded limit")
                        }

                        when {
                            name.endsWith(".cbox") -> {
                                cboxFiles.add(name to content)
                            }
                            name == "password.txt" -> {
                                password = String(content, StandardCharsets.UTF_8).trim()
                            }
                            name == "public_key.txt" -> {
                                publicKey = String(content, StandardCharsets.UTF_8).trim()
                            }
                            name == "config.json" -> {
                                try {
                                    config = JSONObject(String(content, StandardCharsets.UTF_8))
                                } catch (e: Exception) {
                                    Logger.e("Invalid config.json in zip")
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (cboxFiles.isEmpty()) {
                Logger.e("No .cbox files found in zip")
                return null
            }

            // Priority: config.json > individual files
            if (config != null) {
                if (config.has("password")) password = config.getString("password")
                if (config.has("public_key")) publicKey = config.getString("public_key")
            }

            return ProcessedPack(cboxFiles, password, publicKey, config)

        } catch (e: Exception) {
            Logger.e("Failed to process zip", e)
            return null
        }
    }

    private fun readEntry(zis: ZipInputStream): ByteArray? {
        val bytes = zis.readBytes()
        if (bytes.size > MAX_ENTRY_SIZE) return null
        return bytes
    }
}
