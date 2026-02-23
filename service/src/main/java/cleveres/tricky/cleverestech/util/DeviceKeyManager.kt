package cleveres.tricky.cleverestech.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cleveres.tricky.cleverestech.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object DeviceKeyManager {
    private const val TAG = "DeviceKeyManager"
    private const val KEY_ALIAS = "cleveres_device_cache_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_LENGTH = 128

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }

        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    fun encryptForDevice(plaintext: ByteArray): ByteArray? {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())

            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)

            // Format: [IV Length (1 byte)] [IV] [Ciphertext]
            // Actually GCM IV is typically 12 bytes. Just prefix IV.
            val output = ByteArrayOutputStream()
            output.write(iv.size)
            output.write(iv)
            output.write(ciphertext)
            return output.toByteArray()

        } catch (e: Exception) {
            Logger.e("$TAG: Encryption failed: ${e.message}")
            return null
        }
    }

    fun decryptFromDevice(encrypted: ByteArray): ByteArray? {
        try {
            val ivSize = encrypted[0].toInt()
            if (ivSize != 12) throw IllegalArgumentException("Invalid IV size: $ivSize")

            val iv = ByteArray(ivSize)
            System.arraycopy(encrypted, 1, iv, 0, ivSize)

            val ciphertext = ByteArray(encrypted.size - 1 - ivSize)
            System.arraycopy(encrypted, 1 + ivSize, ciphertext, 0, ciphertext.length)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

            return cipher.doFinal(ciphertext)

        } catch (e: Exception) {
            Logger.e("$TAG: Decryption failed: ${e.message}")
            return null
        }
    }
}
