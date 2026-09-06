package cleveres.tricky.cleverestech

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object VbMetaParser {
    private val AVB_MAGIC = "AVB0".toByteArray()
    private const val HEADER_SIZE = 256
    private const val AUTH_DATA_BLOCK_SIZE_LOC = 12
    private const val AUX_DATA_BLOCK_SIZE_LOC = 20
    private const val PUBLIC_KEY_OFFSET_LOC = 64
    private const val PUBLIC_KEY_SIZE_LOC = 72

    // libavb/avb_slot_verify.c bounds each complete vbmeta image to 64 KiB.
    private const val MAX_IMAGE_BYTES = 64 * 1024
    private const val BLOCK_ALIGNMENT = 64

    /**
     * Extracts public-key bytes from a bounded vbmeta image. Extraction does not verify the
     * image's signature, the bootloader's trust anchor, or the device's actual boot state.
     */
    fun extractPublicKey(path: String): ByteArray? = extractPublicKey(path) { RandomAccessFile(it, "r") }

    internal fun extractPublicKey(
        path: String,
        openFile: (String) -> RandomAccessFile,
    ): ByteArray? {
        return try {
            openFile(path).use { file ->
                val header = ByteArray(HEADER_SIZE)
                try {
                    file.readFully(header)
                } catch (e: java.io.EOFException) {
                    Logger.e("VbMetaParser: Failed to read header from $path")
                    return null
                }

                for (i in AVB_MAGIC.indices) {
                    if (header[i] != AVB_MAGIC[i]) {
                        Logger.e("VbMetaParser: Invalid magic in $path")
                        return null
                    }
                }

                val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                val authDataBlockSize = buffer.getLong(AUTH_DATA_BLOCK_SIZE_LOC)
                val auxiliaryDataBlockSize = buffer.getLong(AUX_DATA_BLOCK_SIZE_LOC)
                val publicKeyOffset = buffer.getLong(PUBLIC_KEY_OFFSET_LOC)
                val publicKeySize = buffer.getLong(PUBLIC_KEY_SIZE_LOC)

                if (authDataBlockSize < 0 || auxiliaryDataBlockSize < 0 ||
                    authDataBlockSize > MAX_IMAGE_BYTES - HEADER_SIZE ||
                    auxiliaryDataBlockSize > MAX_IMAGE_BYTES - HEADER_SIZE - authDataBlockSize ||
                    authDataBlockSize % BLOCK_ALIGNMENT != 0L ||
                    auxiliaryDataBlockSize % BLOCK_ALIGNMENT != 0L ||
                    publicKeyOffset < 0 || publicKeySize <= 0 ||
                    publicKeyOffset > auxiliaryDataBlockSize ||
                    publicKeySize > auxiliaryDataBlockSize - publicKeyOffset
                ) {
                    Logger.e("VbMetaParser: Invalid vbmeta public-key bounds")
                    return null
                }

                // Android RandomAccessFile.length() uses fstat().st_size, which is zero for
                // block devices. Read the bounded, declared image instead of treating that as EOF.
                // Reading the full body also rejects truncation after an otherwise readable key.
                val body = ByteArray((authDataBlockSize + auxiliaryDataBlockSize).toInt())
                try {
                    file.readFully(body)
                    val keyStart = (authDataBlockSize + publicKeyOffset).toInt()
                    val keyEnd = keyStart + publicKeySize.toInt()
                    Logger.d("VbMetaParser: Successfully extracted public key from $path")
                    body.copyOfRange(keyStart, keyEnd)
                } catch (e: java.io.EOFException) {
                    Logger.e("VbMetaParser: Truncated vbmeta image")
                    null
                } finally {
                    body.fill(0)
                }
            }
        } catch (e: Exception) {
            Logger.e("VbMetaParser: Error reading $path", e)
            null
        }
    }
}
