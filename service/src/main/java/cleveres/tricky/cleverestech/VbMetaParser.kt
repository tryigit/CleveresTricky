package cleveres.tricky.cleverestech

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object VbMetaParser {
    // AVB0 Magic (4 bytes)
    private val AVB_MAGIC = "AVB0".toByteArray()
    private const val HEADER_SIZE = 256
    private const val AUTH_DATA_BLOCK_SIZE_LOC = 12
    private const val AUX_DATA_BLOCK_SIZE_LOC = 20
    private const val PUBLIC_KEY_OFFSET_LOC = 64
    private const val PUBLIC_KEY_SIZE_LOC = 72
    private const val MAX_PUBLIC_KEY_BYTES = 64 * 1024

    /**
     * Extracts the public key from the vbmeta image at the given path.
     * Returns null if the file cannot be read or parsing fails.
     */
    fun extractPublicKey(path: String): ByteArray? {
        return try {
            RandomAccessFile(path, "r").use { file ->
                // Read header
                val header = ByteArray(HEADER_SIZE)
                try {
                    file.readFully(header)
                } catch (e: java.io.EOFException) {
                    Logger.e("VbMetaParser: Failed to read header from $path")
                    return null
                }

                // Verify Magic
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

                if (publicKeyOffset < 0 || publicKeySize <= 0 ||
                    authDataBlockSize < 0 || auxiliaryDataBlockSize < 0 ||
                    publicKeySize > MAX_PUBLIC_KEY_BYTES ||
                    publicKeyOffset > auxiliaryDataBlockSize ||
                    publicKeySize > auxiliaryDataBlockSize - publicKeyOffset
                ) {
                    Logger.e("VbMetaParser: Invalid vbmeta public-key bounds")
                    return null
                }

                // Seek to public key location
                // The public key is in the Auxiliary Data block, which follows the Authentication Data block.
                // The Authentication Data block starts at HEADER_SIZE (256).
                // So the Auxiliary Data block starts at HEADER_SIZE + authDataBlockSize.
                // publicKeyOffset is relative to the start of the Auxiliary Data block.

                val absoluteOffset =
                    try {
                        Math.addExact(Math.addExact(HEADER_SIZE.toLong(), authDataBlockSize), publicKeyOffset)
                    } catch (e: ArithmeticException) {
                        Logger.e("VbMetaParser: Public-key offset overflow")
                        return null
                    }
                if (absoluteOffset > file.length() || publicKeySize > file.length() - absoluteOffset) {
                    Logger.e("VbMetaParser: Public key extends past the vbmeta image")
                    return null
                }
                file.seek(absoluteOffset)

                val keyBytes = ByteArray(publicKeySize.toInt())
                try {
                    file.readFully(keyBytes)
                } catch (e: java.io.EOFException) {
                    keyBytes.fill(0)
                    Logger.e("VbMetaParser: Failed to read public key bytes")
                    return null
                }

                Logger.d("VbMetaParser: Successfully extracted public key from $path")
                keyBytes
            }
        } catch (e: Exception) {
            Logger.e("VbMetaParser: Error reading $path", e)
            null
        }
    }
}
