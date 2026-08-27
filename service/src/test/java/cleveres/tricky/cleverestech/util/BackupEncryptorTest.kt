package cleveres.tricky.cleverestech.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class BackupEncryptorTest {



    @Test
    fun `isEncryptedBackup returns true for valid magic`() {
        val bytes = "CTSB_some_data".toByteArray()
        assertTrue(BackupEncryptor.isEncryptedBackup(bytes))
    }

    @Test
    fun `isEncryptedBackup returns false for invalid magic`() {
        val bytes = "CTSA_some_data".toByteArray()
        assertFalse(BackupEncryptor.isEncryptedBackup(bytes))
    }

    @Test
    fun `isEncryptedBackup returns false for too short array`() {
        val bytes = "CTS".toByteArray()
        assertFalse(BackupEncryptor.isEncryptedBackup(bytes))
    }

    @Test
    fun `encrypt throws when plaintext is too large`() {
        val largePlaintext = ByteArray(32 * 1024 * 1024 + 1)
        val exception = assertThrows(IllegalArgumentException::class.java) {
            BackupEncryptor.encrypt(largePlaintext, "password")
        }
        assertEquals("Backup exceeds 33554432 bytes", exception.message)
    }

    @Test
    fun `encrypt throws when password is too long`() {
        val longPassword = "a".repeat(1025)
        val exception = assertThrows(IllegalArgumentException::class.java) {
            BackupEncryptor.encrypt("hello".toByteArray(), longPassword)
        }
        assertEquals("Backup password exceeds 1024 characters", exception.message)
    }

    @Test
    fun `decrypt throws when data is too small`() {
        val exception = assertThrows(IOException::class.java) {
            BackupEncryptor.decrypt(ByteArray(47), "password") // HEADER_LENGTH + TAG_LENGTH is 48
        }
        assertEquals("Invalid CTSB backup size", exception.message)
    }

    @Test
    fun `decrypt throws when data is too large`() {
        // We use HEADER_LENGTH (36) + TAG_LENGTH (16)
        val largeData = ByteArray(32 * 1024 * 1024 + 36 + 16 + 1)
        val exception = assertThrows(IOException::class.java) {
            BackupEncryptor.decrypt(largeData, "password")
        }
        assertEquals("Invalid CTSB backup size", exception.message)
    }

    @Test
    fun `decrypt throws when missing magic`() {
        // Make sure it passes size check
        val data = ByteArray(52) // 36 + 16 = 52
        val exception = assertThrows(IOException::class.java) {
            BackupEncryptor.decrypt(data, "password")
        }
        assertEquals("Not a CTSB encrypted backup", exception.message)
    }

    @Test
    fun `decrypt throws when unsupported version`() {
        val magic = "CTSB".toByteArray(Charsets.US_ASCII)
        val version = 3
        val header = ByteArray(magic.size + 4 + 16 + 12)
        magic.copyInto(header)
        header[magic.size] = (version ushr 24).toByte()
        header[magic.size + 1] = (version ushr 16).toByte()
        header[magic.size + 2] = (version ushr 8).toByte()
        header[magic.size + 3] = version.toByte()

        val data = ByteArray(header.size + 16)
        header.copyInto(data)

        val exception = assertThrows(IOException::class.java) {
            BackupEncryptor.decrypt(data, "password")
        }
        assertEquals("Unsupported CTSB version: 3", exception.message)
    }

    @Test
    fun `decrypt throws when password is too long`() {
        val magic = "CTSB".toByteArray(Charsets.US_ASCII)
        val version = 2
        val header = ByteArray(magic.size + 4 + 16 + 12)
        magic.copyInto(header)
        header[magic.size] = (version ushr 24).toByte()
        header[magic.size + 1] = (version ushr 16).toByte()
        header[magic.size + 2] = (version ushr 8).toByte()
        header[magic.size + 3] = version.toByte()

        val data = ByteArray(header.size + 16)
        header.copyInto(data)

        val longPassword = "a".repeat(1025)

        val exception = assertThrows(IOException::class.java) {
            BackupEncryptor.decrypt(data, longPassword)
        }
        assertEquals("Backup password exceeds 1024 characters", exception.message)
    }
}
