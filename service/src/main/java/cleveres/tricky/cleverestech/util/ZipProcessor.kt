package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.Logger
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ZipProcessor {
    private const val TAG = "ZipProcessor"
    private const val MAX_ENTRY_SIZE = 5 * 1024 * 1024 // 5MB

    data class Result(
        val success: Boolean,
        val payloads: List<CboxDecryptor.CboxPayload> = emptyList(),
        val error: String? = null
    )

    fun isZip(data: ByteArray): Boolean {
        return data.size > 4 &&
               data[0] == 0x50.toByte() &&
               data[1] == 0x4B.toByte() &&
               data[2] == 0x03.toByte() &&
               data[3] == 0x04.toByte()
    }

    fun process(data: ByteArray, providedPassword: String? = null, providedPublicKey: String? = null): Result {
        val cboxFiles = ArrayList<ByteArray>()
        var embeddedPassword: String? = null
        var embeddedPublicKey: String? = null
        var configJson: JSONObject? = null

        try {
            ZipInputStream(ByteArrayInputStream(data)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        val size = entry.size
                        if (size > MAX_ENTRY_SIZE) {
                            Logger.w("$TAG: Skipping large entry: $name")
                            continue
                        }

                        val buffer = ByteArrayOutputStream()
                        val buf = ByteArray(1024)
                        var len: Int
                        var totalRead = 0
                        while (zis.read(buf).also { len = it } > 0) {
                            buffer.write(buf, 0, len)
                            totalRead += len
                            if (totalRead > MAX_ENTRY_SIZE) break
                        }

                        if (totalRead <= MAX_ENTRY_SIZE) {
                            val content = buffer.toByteArray()
                            when {
                                name.endsWith(".cbox") -> cboxFiles.add(content)
                                name == "password.txt" -> embeddedPassword = String(content, StandardCharsets.UTF_8).trim()
                                name == "public_key.txt" -> embeddedPublicKey = String(content, StandardCharsets.UTF_8).trim()
                                name == "config.json" -> {
                                    try {
                                        configJson = JSONObject(String(content, StandardCharsets.UTF_8))
                                    } catch (e: Exception) {
                                        Logger.e("$TAG: Invalid config.json", e)
                                    }
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Logger.e("$TAG: ZIP processing error", e)
            return Result(false, error = "Invalid ZIP file")
        }

        if (cboxFiles.isEmpty()) {
            return Result(false, error = "No .cbox files found in ZIP")
        }

        // Credential Resolution
        val password = configJson?.optString("password", null)
                      ?: embeddedPassword
                      ?: providedPassword

        val publicKey = configJson?.optString("public_key", null)
                       ?: embeddedPublicKey
                       ?: providedPublicKey

        if (password == null) {
            return Result(false, error = "Password required")
        }

        val decryptedPayloads = ArrayList<CboxDecryptor.CboxPayload>()

        for (cboxBytes in cboxFiles) {
            val payload = CboxDecryptor.decrypt(cboxBytes, password)
            if (payload != null) {
                if (publicKey != null) {
                    if (!CboxDecryptor.verifySignature(payload, publicKey)) {
                         Logger.w("$TAG: Signature verification failed for a cbox file")
                         continue // Skip or fail all? Let's skip invalid ones but try others.
                    }
                }
                decryptedPayloads.add(payload)
            } else {
                Logger.w("$TAG: Failed to decrypt a cbox file")
            }
        }

        if (decryptedPayloads.isEmpty()) {
            return Result(false, error = "Decryption failed (Check password/signature)")
        }

        return Result(true, payloads = decryptedPayloads)
    }
}
