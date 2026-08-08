package cleveres.tricky.encryptor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CryptoUtilsTest {
    @After
    fun tearDown() {
        CryptoUtils.skipSigningForTest = false
    }

    @Test
    fun testEncryptAndWriteCbox() {
        CryptoUtils.skipSigningForTest = true
        // Ensure keys exist (Robolectric mocks KeyStore)
        CryptoUtils.generateSigningKey()

        val xmlContent = "<keybox>test</keybox>"
        val author = "TestAuthor"
        val password = "TestPassword"

        val outputStream = ByteArrayOutputStream()
        CryptoUtils.encryptAndWriteCbox(outputStream, xmlContent, author, password)

        val bytes = outputStream.toByteArray()
        assertTrue("Output should not be empty", bytes.isNotEmpty())

        // Validate Header
        // "CBOX" (4 bytes)
        val magic = String(bytes.copyOfRange(0, 4), StandardCharsets.US_ASCII)
        assertEquals("Magic bytes mismatch", "CBOX", magic)

        // Version (4 bytes, int 1)
        // 00 00 00 02
        assertEquals("Version byte 0 mismatch", 0.toByte(), bytes[4])
        assertEquals("Version byte 1 mismatch", 0.toByte(), bytes[5])
        assertEquals("Version byte 2 mismatch", 0.toByte(), bytes[6])
        assertEquals("Version byte 3 mismatch", 2.toByte(), bytes[7])

        // Salt (16 bytes) + IV (12 bytes) + Header (8 bytes) = 36 bytes minimum overhead
        val headerLength = 4 + 4 + 16 + 12
        assertTrue("Output too short", bytes.size > headerLength)
    }
}
