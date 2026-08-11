package cleveres.tricky.cleverestech.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cleveres.tricky.cleverestech.Logger
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object DeviceKeyManager {
    private const val KEY_ALIAS = "cleveres_device_cache_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val TAG = "DeviceKeyManager"

    @Volatile
    private var fallbackKey: SecretKey? = null

    @Volatile
    private var useFallback = false

    @Volatile
    private var cachedKey: SecretKey? = null

    fun initialize(rootDir: File) {
        cachedKey = null
        fallbackKey = null
        useFallback = false
        val fallbackFile = File(rootDir, "device_secret.key")
        if (Files.isRegularFile(fallbackFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            useFallback = true
            loadFallbackKey(rootDir)
            Logger.i("$TAG: Using the existing root-only fallback key")
            return
        }
        if (fallbackFile.exists() || Files.isSymbolicLink(fallbackFile.toPath())) {
            throw IOException("Fallback key path is not a regular file")
        }
        try {
            initializeAndroidKeyStore()
        } catch (error: Exception) {
            Logger.e("$TAG: AndroidKeyStore failed, using fallback file key: ${error.message}")
            useFallback = true
            loadFallbackKey(rootDir)
        } catch (error: LinkageError) {
            Logger.e("$TAG: AndroidKeyStore API unavailable, using fallback file key: ${error.message}")
            useFallback = true
            loadFallbackKey(rootDir)
        }
    }

    private fun initializeAndroidKeyStore() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        if (!ks.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            keyGenerator.generateKey()
        }
        require(ks.getEntry(KEY_ALIAS, null) is KeyStore.SecretKeyEntry) {
            "AndroidKeyStore did not return the generated AES key"
        }
    }

    private fun loadFallbackKey(rootDir: File) {
        val keyFile = File(rootDir, "device_secret.key")
        if (Files.isSymbolicLink(keyFile.toPath())) {
            throw IOException("Refusing symbolic-link fallback key")
        }
        if (Files.isRegularFile(keyFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (keyFile.length() != FALLBACK_KEY_BYTES.toLong()) {
                throw IOException("Fallback key has an invalid size")
            }
            val bytes = keyFile.readBytes()
            if (bytes.size == FALLBACK_KEY_BYTES) {
                fallbackKey = SecretKeySpec(bytes, "AES")
                bytes.fill(0)
                return
            }
            bytes.fill(0)
            throw IOException("Fallback key could not be read completely")
        }
        val bytes = ByteArray(FALLBACK_KEY_BYTES)
        SecureRandom().nextBytes(bytes)
        fallbackKey = SecretKeySpec(bytes, "AES")
        SecureFile.writeBytes(keyFile, bytes)
        bytes.fill(0)
    }

    private fun getKey(): SecretKey? {
        if (useFallback) return fallbackKey
        cachedKey?.let { return it }

        synchronized(this) {
            cachedKey?.let { return it }
            return try {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
                ks.load(null)
                val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                cachedKey = entry?.secretKey
                cachedKey
            } catch (e: Exception) {
                null
            }
        }
    }

    fun encrypt(data: ByteArray): ByteArray? {
        if (data.size > MAX_PLAINTEXT_BYTES) return null
        try {
            val key = getKey() ?: return null
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            var pendingOutput: ByteArray? = null
            try {
                if (iv.size != GCM_IV_LENGTH) return null
                val encryptedSize = cipher.getOutputSize(data.size)
                val result = ByteArray(1 + iv.size + encryptedSize)
                pendingOutput = result
                result[0] = iv.size.toByte()
                System.arraycopy(iv, 0, result, 1, iv.size)

                val written = cipher.doFinal(data, 0, data.size, result, 1 + iv.size)
                check(written == encryptedSize) { "Unexpected AES-GCM output size" }
                pendingOutput = null
                return result
            } finally {
                iv.fill(0)
                pendingOutput?.fill(0)
            }
        } catch (e: Exception) {
            Logger.e("$TAG: Encrypt failed", e)
            return null
        }
    }

    fun decrypt(data: ByteArray): ByteArray? {
        if (data.size > MAX_PLAINTEXT_BYTES + 1 + GCM_IV_LENGTH + GCM_TAG_LENGTH) return null
        try {
            val key = getKey() ?: return null
            if (data.size < 1 + GCM_IV_LENGTH + GCM_TAG_LENGTH) return null

            val ivLen = data[0].toInt() and 0xFF
            if (ivLen != GCM_IV_LENGTH) return null

            val iv = ByteArray(ivLen)
            System.arraycopy(data, 1, iv, 0, ivLen)

            val ciphertextOffset = 1 + ivLen
            val ciphertextLen = data.size - ciphertextOffset
            if (ciphertextLen < GCM_TAG_LENGTH) return null

            val cipher = Cipher.getInstance(AES_MODE)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            return try {
                cipher.doFinal(data, ciphertextOffset, ciphertextLen)
            } finally {
                iv.fill(0)
            }
        } catch (e: Exception) {
            Logger.e("$TAG: Decrypt failed", e)
            return null
        }
    }

    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16
    private const val FALLBACK_KEY_BYTES = 32
    private const val MAX_PLAINTEXT_BYTES = 16 * 1024 * 1024
}
