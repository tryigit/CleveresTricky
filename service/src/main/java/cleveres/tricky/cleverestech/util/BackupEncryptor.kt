package cleveres.tricky.cleverestech.util

import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-encrypted settings backups using PBKDF2-HMAC-SHA256 and AES-256-GCM. */
object BackupEncryptor {
    internal const val MAGIC = "CTSB"

    private const val LEGACY_VERSION = 1
    private const val VERSION = 2
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ITERATION_COUNT = 250_000
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 16
    private const val HEADER_LENGTH = 4 + Int.SIZE_BYTES + SALT_LENGTH + IV_LENGTH
    private const val MAX_BACKUP_BYTES = 32 * 1024 * 1024

    private val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)
    private val secureRandom = SecureRandom()

    /**
     * CTSB v2 format: magic, version, salt, IV, then ciphertext and its GCM tag.
     * The complete header is authenticated as AAD so its version and KDF inputs
     * cannot be modified without detection.
     */
    fun encrypt(
        plaintext: ByteArray,
        password: String,
    ): ByteArray {
        require(plaintext.size <= MAX_BACKUP_BYTES) { "Backup exceeds $MAX_BACKUP_BYTES bytes" }

        val salt = ByteArray(SALT_LENGTH).also(secureRandom::nextBytes)
        val iv = ByteArray(IV_LENGTH).also(secureRandom::nextBytes)
        val keyBytes = deriveKey(password, salt)
        var pendingOutput: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))

            val encryptedSize = cipher.getOutputSize(plaintext.size)
            val result = ByteArray(HEADER_LENGTH + encryptedSize)
            pendingOutput = result
            ByteBuffer.wrap(result)
                .put(magicBytes)
                .putInt(VERSION)
                .put(salt)
                .put(iv)
            cipher.updateAAD(result, 0, HEADER_LENGTH)

            val written = cipher.doFinal(plaintext, 0, plaintext.size, result, HEADER_LENGTH)
            check(written == encryptedSize) { "Unexpected AES-GCM output size" }
            pendingOutput = null
            return result
        } finally {
            keyBytes.fill(0)
            pendingOutput?.fill(0)
            salt.fill(0)
            iv.fill(0)
        }
    }

    /** Decrypts v2 backups and retains read compatibility with CTSB v1. */
    fun decrypt(
        data: ByteArray,
        password: String,
    ): ByteArray {
        if (data.size < HEADER_LENGTH + TAG_LENGTH || data.size > MAX_BACKUP_BYTES + HEADER_LENGTH + TAG_LENGTH) {
            throw IOException("Invalid CTSB backup size")
        }

        val buffer = ByteBuffer.wrap(data)
        val magic = ByteArray(magicBytes.size).also(buffer::get)
        if (!magic.contentEquals(magicBytes)) throw IOException("Not a CTSB encrypted backup")

        val version = buffer.int
        if (version != LEGACY_VERSION && version != VERSION) {
            throw IOException("Unsupported CTSB version: $version")
        }

        val salt = ByteArray(SALT_LENGTH).also(buffer::get)
        val iv = ByteArray(IV_LENGTH).also(buffer::get)
        val keyBytes = deriveKey(password, salt)
        try {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            if (version == VERSION) cipher.updateAAD(data, 0, HEADER_LENGTH)
            return cipher.doFinal(data, HEADER_LENGTH, data.size - HEADER_LENGTH)
        } finally {
            keyBytes.fill(0)
            salt.fill(0)
            iv.fill(0)
            magic.fill(0)
        }
    }

    fun isEncryptedBackup(bytes: ByteArray): Boolean {
        if (bytes.size < magicBytes.size) return false
        for (index in magicBytes.indices) {
            if (bytes[index] != magicBytes[index]) return false
        }
        return true
    }

    private fun deriveKey(
        password: String,
        salt: ByteArray,
    ): ByteArray {
        val passwordChars = password.toCharArray()
        val spec = PBEKeySpec(passwordChars, salt, ITERATION_COUNT, KEY_LENGTH)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            passwordChars.fill('\u0000')
        }
    }
}
