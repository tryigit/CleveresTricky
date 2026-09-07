package cleveres.tricky.cleverestech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VbMetaParserTest {
    @Before
    fun setup() {
        Logger.setImpl(
            object : Logger.LogImpl {
                override fun d(
                    tag: String,
                    msg: String,
                ) {
                    // no-op
                }

                override fun e(
                    tag: String,
                    msg: String,
                ) {
                    // no-op
                }

                override fun e(
                    tag: String,
                    msg: String,
                    t: Throwable?,
                ) {
                    // no-op
                    // no-op
                }

                override fun i(
                    tag: String,
                    msg: String,
                ) {
                    // no-op
                }
            },
        )
    }

    @Test
    fun testExtractPublicKey() {
        val tempFile = File.createTempFile("vbmeta", ".img")
        try {
            val key = "dummy_public_key".toByteArray()
            val authDataSize: Long = 64
            val keyOffset: Long = 10 // Relative to Aux Block start
            val auxiliaryDataSize = 64L
            val header = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN)
            header.put("AVB0".toByteArray())

            // Set Auth Data Block Size at 12
            header.position(12)
            header.putLong(authDataSize)

            // Set Auxiliary Data Block Size at 20
            header.position(20)
            header.putLong(auxiliaryDataSize)

            // Set Public Key Offset at 64
            header.position(64)
            header.putLong(keyOffset)

            // Set Public Key Size at 72
            header.position(72)
            header.putLong(key.size.toLong())

            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.write(header.array())

                // Write dummy Auth Data (64 bytes)
                val authData = ByteArray(authDataSize.toInt())
                // Fill with something to ensure we skip it
                for (i in authData.indices) authData[i] = 0xAA.toByte()
                raf.write(authData)

                // Now at offset 256 + 64.
                // We need to write key at keyOffset (10) from here.
                // So seek to 256 + 64 + 10 = 330
                raf.seek(256 + authDataSize + keyOffset)
                raf.write(key)
                raf.setLength(256 + authDataSize + auxiliaryDataSize)
            }

            val extracted = VbMetaParser.extractPublicKey(tempFile.absolutePath)
            assertArrayEquals(key, extracted)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testExtractPublicKey_InvalidMagic() {
        val tempFile = File.createTempFile("vbmeta_invalid", ".img")
        try {
            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.write(ByteArray(256)) // Zero header
            }

            val extracted = VbMetaParser.extractPublicKey(tempFile.absolutePath)
            assertNull(extracted)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testExtractPublicKey_FileNotFound() {
        val extracted = VbMetaParser.extractPublicKey("/path/to/non/existent/file")
        assertNull(extracted)
    }

    @Test
    fun `readable block device with zero stat size still yields its public key`() {
        withImage(image()) { file ->
            val handle = ZeroStatSizeFile(file)
            assertEquals(0L, handle.length())
            assertArrayEquals(KEY, VbMetaParser.extractPublicKey(file.path) { handle })
            assertTrue(handle.closed)
        }
    }

    @Test
    fun `complete image at libavb limit is accepted without reading partition padding`() {
        val data = image(auxiliarySize = 65_216, keyOffset = 65_184)
        withImage(data + ByteArray(4096) { 0x7e }) { file ->
            val handle = ZeroStatSizeFile(file)
            assertArrayEquals(KEY, VbMetaParser.extractPublicKey(file.path) { handle })
            assertEquals(65_536, handle.bytesRead)
            assertTrue(handle.closed)
        }
    }

    @Test
    fun `invalid bounds reject after the header without body reads`() {
        val invalidFields =
            listOf(
                12 to -1L,
                12 to Long.MAX_VALUE,
                12 to 65_536L,
                12 to 65L,
                20 to -1L,
                20 to Long.MAX_VALUE,
                20 to 65_280L,
                20 to 63L,
                64 to -1L,
                64 to Long.MAX_VALUE,
                64 to 65L,
                72 to -1L,
                72 to 0L,
                72 to Long.MAX_VALUE,
                72 to 49L,
            )
        for ((offset, value) in invalidFields) {
            val data = image()
            ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).putLong(offset, value)
            withImage(data) { file ->
                val handle = ZeroStatSizeFile(file)
                assertNull("offset=$offset value=$value", VbMetaParser.extractPublicKey(file.path) { handle })
                assertEquals(256, handle.bytesRead)
                assertTrue(handle.closed)
            }
        }
    }

    @Test
    fun `truncated header body and bytes after key fail without leaking a partial key`() {
        val data = image()
        for (length in listOf(0, 255, 256, 336, 367, 368, 383)) {
            withImage(data.copyOf(length)) { file ->
                val handle = ZeroStatSizeFile(file)
                assertNull("length=$length", VbMetaParser.extractPublicKey(file.path) { handle })
                assertTrue(handle.closed)
            }
        }
    }

    @Test
    fun `short successful reads are assembled before extracting the key`() {
        withImage(image()) { file ->
            val handle = ZeroStatSizeFile(file, maxRead = 7)
            assertArrayEquals(KEY, VbMetaParser.extractPublicKey(file.path) { handle })
            assertEquals(384, handle.bytesRead)
            assertTrue(handle.closed)
        }
    }

    @Test
    fun `body IO errors close the handle and do not return a key`() {
        withImage(image()) { file ->
            val handle =
                object : ZeroStatSizeFile(file) {
                    override fun read(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (bytesRead >= 256) throw java.io.IOException("injected body read failure")
                        return super.read(bytes, offset, length)
                    }
                }
            assertNull(VbMetaParser.extractPublicKey(file.path) { handle })
            assertTrue(handle.closed)
        }
    }

    private fun image(
        auxiliarySize: Int = 64,
        keyOffset: Int = 16,
    ): ByteArray {
        val data = ByteArray(256 + 64 + auxiliarySize)
        ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).apply {
            put("AVB0".toByteArray(Charsets.US_ASCII))
            putLong(12, 64L)
            putLong(20, auxiliarySize.toLong())
            putLong(64, keyOffset.toLong())
            putLong(72, KEY.size.toLong())
        }
        KEY.copyInto(data, 256 + 64 + keyOffset)
        return data
    }

    private fun withImage(
        data: ByteArray,
        block: (File) -> Unit,
    ) {
        val file = File.createTempFile("vbmeta-input", ".img")
        try {
            file.writeBytes(data)
            block(file)
        } finally {
            file.delete()
        }
    }

    private open class ZeroStatSizeFile(
        file: File,
        private val maxRead: Int = Int.MAX_VALUE,
    ) : RandomAccessFile(file, "r") {
        var closed = false
        var bytesRead = 0

        override fun length(): Long = 0L

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val count = super.read(bytes, offset, minOf(length, maxRead))
            if (count > 0) bytesRead += count
            return count
        }

        override fun close() {
            closed = true
            super.close()
        }
    }

    companion object {
        private val KEY = ByteArray(32) { (it + 1).toByte() }
    }
}
