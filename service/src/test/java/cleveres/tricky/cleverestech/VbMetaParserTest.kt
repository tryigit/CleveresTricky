package cleveres.tricky.cleverestech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
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
                    println("DEBUG: $tag: $msg")
                }

                override fun e(
                    tag: String,
                    msg: String,
                ) {
                    println("ERROR: $tag: $msg")
                }

                override fun e(
                    tag: String,
                    msg: String,
                    t: Throwable?,
                ) {
                    println("ERROR: $tag: $msg")
                    t?.printStackTrace()
                }

                override fun i(
                    tag: String,
                    msg: String,
                ) {
                    println("INFO: $tag: $msg")
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
            val auxiliaryDataSize = keyOffset + key.size
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
}
