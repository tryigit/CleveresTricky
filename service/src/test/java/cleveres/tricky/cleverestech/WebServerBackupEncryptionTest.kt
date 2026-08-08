package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.BackupEncryptor
import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import javax.crypto.AEADBadTagException

class WebServerBackupEncryptionTest {
    private lateinit var testDir: File
    private lateinit var configDir: File
    private lateinit var originalSecureFileImpl: SecureFileOperations

    @Before
    fun setUp() {
        testDir = File(System.getProperty("java.io.tmpdir"), "cleverestricky_enc_test_${System.currentTimeMillis()}")
        testDir.mkdirs()
        configDir = File(testDir, "config")
        configDir.mkdirs()
        originalSecureFileImpl = SecureFile.impl

        SecureFile.impl =
            object : SecureFileOperations {
                override fun writeText(
                    file: File,
                    content: String,
                ) {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                }

                override fun writeStream(
                    file: File,
                    inputStream: java.io.InputStream,
                    limit: Long,
                ) {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { out ->
                        var total = 0L
                        val buf = ByteArray(8192)
                        var n: Int
                        while (inputStream.read(buf).also { n = it } != -1) {
                            if (limit > 0 && total + n > limit) throw java.io.IOException("Exceeds limit")
                            out.write(buf, 0, n)
                            total += n
                        }
                    }
                }

                override fun mkdirs(
                    file: File,
                    mode: Int,
                ) {
                    file.mkdirs()
                }

                override fun touch(
                    file: File,
                    mode: Int,
                ) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                }
            }
    }

    @After
    fun tearDown() {
        SecureFile.impl = originalSecureFileImpl
        testDir.deleteRecursively()
    }

    @Test
    fun testEncryptDecryptRoundTrip() {
        val original = "Hello, CTSB backup world!".toByteArray()
        val password = "s3cur3P@ss"
        val encrypted = BackupEncryptor.encrypt(original, password)
        val decrypted = BackupEncryptor.decrypt(encrypted, password)
        assertArrayEquals("Round-trip must produce identical bytes", original, decrypted)
    }

    @Test
    fun testEncryptedOutputStartsWithMagic() {
        val encrypted = BackupEncryptor.encrypt("data".toByteArray(), "pw")
        val magic = String(encrypted.copyOf(4), Charsets.US_ASCII)
        assertEquals("Encrypted backup must start with CTSB magic", BackupEncryptor.MAGIC, magic)
    }

    @Test
    fun testIsEncryptedBackupDetectsCtsbMagic() {
        val encrypted = BackupEncryptor.encrypt("data".toByteArray(), "pw")
        assertTrue("Must detect CTSB header as encrypted", BackupEncryptor.isEncryptedBackup(encrypted))
    }

    @Test
    fun testIsEncryptedBackupReturnsFalseForPlainZip() {
        // ZIP magic is 0x504B0304
        val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)
        assertFalse("Plain ZIP must not be detected as encrypted", BackupEncryptor.isEncryptedBackup(zipHeader))
    }

    @Test
    fun testIsEncryptedBackupReturnsFalseForShortData() {
        assertFalse("Empty bytes must not be detected as encrypted", BackupEncryptor.isEncryptedBackup(ByteArray(0)))
        assertFalse("Short bytes must not be detected as encrypted", BackupEncryptor.isEncryptedBackup(ByteArray(3)))
    }

    @Test
    fun testWrongPasswordThrows() {
        val encrypted = BackupEncryptor.encrypt("sensitive data".toByteArray(), "correctPassword")
        var threw = false
        try {
            BackupEncryptor.decrypt(encrypted, "wrongPassword")
        } catch (e: AEADBadTagException) {
            threw = true
        } catch (e: Exception) {
            // Some JVMs wrap AEADBadTagException in a javax.crypto.BadPaddingException
            threw = true
        }
        assertTrue("Decryption with wrong password must throw", threw)
    }

    @Test
    fun testAuthenticatedHeaderRejectsTampering() {
        val encrypted = BackupEncryptor.encrypt("sensitive data".toByteArray(), "correctPassword")
        encrypted[12] = (encrypted[12].toInt() xor 1).toByte()

        var threw = false
        try {
            BackupEncryptor.decrypt(encrypted, "correctPassword")
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("Changing a v2 CTSB header byte must invalidate authentication", threw)
    }

    @Test
    fun testDecryptInvalidMagicThrows() {
        val notCtsb = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00)
        var threw = false
        try {
            BackupEncryptor.decrypt(notCtsb, "pw")
        } catch (e: java.io.IOException) {
            threw = true
        }
        assertTrue("decrypt() with wrong magic must throw IOException", threw)
    }

    @Test
    fun testDifferentPasswordsProduceDifferentCiphertext() {
        val plaintext = "same data".toByteArray()
        val enc1 = BackupEncryptor.encrypt(plaintext, "password1")
        val enc2 = BackupEncryptor.encrypt(plaintext, "password2")
        assertFalse("Different passwords must produce different ciphertext", enc1.contentEquals(enc2))
    }

    @Test
    fun testTwoEncryptionsOfSamePlaintextDiffer() {
        // Each call uses a fresh random salt + IV
        val plaintext = "same data".toByteArray()
        val enc1 = BackupEncryptor.encrypt(plaintext, "pw")
        val enc2 = BackupEncryptor.encrypt(plaintext, "pw")
        assertFalse("Two encryptions of the same data must produce different ciphertext (random salt/IV)", enc1.contentEquals(enc2))
    }

    @Test
    fun testFullBackupEncryptDecryptRestoreCycle() {
        // Create config files
        File(configDir, "target.txt").writeText("com.example.app")
        File(configDir, "spoof_build_vars").writeText("MODEL=Pixel 9")
        val kbDir = File(configDir, "keyboxes")
        kbDir.mkdirs()
        File(kbDir, "kb1.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)

        // Create plain ZIP backup
        val zipBytes = WebServer.createBackupZip(configDir)
        assertTrue("ZIP backup must not be empty", zipBytes.isNotEmpty())

        // Encrypt the ZIP
        val password = "backupPass123"
        val encryptedBytes = BackupEncryptor.encrypt(zipBytes, password)
        assertTrue("Encrypted backup must start with CTSB", BackupEncryptor.isEncryptedBackup(encryptedBytes))

        // Wipe config dir
        configDir.deleteRecursively()
        configDir.mkdirs()

        // Decrypt then restore
        val decryptedZip = BackupEncryptor.decrypt(encryptedBytes, password)
        WebServer.restoreBackupZip(configDir, ByteArrayInputStream(decryptedZip))

        // Verify restoration
        assertEquals("com.example.app", File(configDir, "target.txt").readText())
        assertEquals("MODEL=Pixel 9", File(configDir, "spoof_build_vars").readText())
        assertEquals(TestKeyboxFixtures.validEcKeyboxXml, File(configDir, "keyboxes/kb1.xml").readText())
    }
}
