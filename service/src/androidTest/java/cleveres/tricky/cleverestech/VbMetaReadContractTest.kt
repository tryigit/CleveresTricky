package cleveres.tricky.cleverestech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class VbMetaReadContractTest {
    @Test
    fun `Android reader accepts zero stat size but rejects a truncated declared image`() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val image = ByteArray(384)
        ByteBuffer.wrap(image).order(ByteOrder.BIG_ENDIAN).apply {
            put("AVB0".toByteArray(Charsets.US_ASCII))
            putLong(12, 64)
            putLong(20, 64)
            putLong(64, 16)
            putLong(72, key.size.toLong())
        }
        key.copyInto(image, 336)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("vbmeta-contract", ".img", cacheDir)
        try {
            file.writeBytes(image)
            assertArrayEquals(key, VbMetaParser.extractPublicKey(file.path))
            for (length in listOf(image.size, image.size - 1)) {
                RandomAccessFile(file, "rw").use { it.setLength(length.toLong()) }
                var closed = false
                // Fault injection models block-device st_size while retaining Android's real
                // RandomAccessFile readFully/EOF/close implementation on the platform oracle.
                val result =
                    VbMetaParser.extractPublicKey(file.path) {
                        object : RandomAccessFile(file, "r") {
                            override fun length(): Long = 0L

                            override fun close() {
                                closed = true
                                super.close()
                            }
                        }
                    }
                if (length == image.size) assertArrayEquals(key, result) else assertNull(result)
                assertTrue(closed)
            }
        } finally {
            file.delete()
        }
    }
}
