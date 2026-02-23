package cleveres.tricky.cleverestech.util

import android.util.Base64
import cleveres.tricky.cleverestech.Logger
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CboxDecryptor {
    private const val TAG = "CboxDecryptor"
    private const val HEADER = "CBOX"
    private const val VERSION = 1

    data class CboxPayload(
        val author: String,
        val xmlContent: String,
        val signatureBase64: String
    )

    fun decrypt(cboxBytes: ByteArray, password: String): CboxPayload? {
        try {
            val input = DataInputStream(ByteArrayInputStream(cboxBytes))

            // 1. Header Check
            val header = ByteArray(4)
            input.readFully(header)
            if (!header.contentEquals(HEADER.toByteArray(StandardCharsets.US_ASCII))) {
                Logger.e("$TAG: Invalid CBOX header")
                return null
            }

            // 2. Version Check
            val version = input.readInt()
            if (version != VERSION) {
                Logger.e("$TAG: Unsupported CBOX version: $version")
                return null
            }

            // 3. Read Salt & IV
            val salt = ByteArray(16)
            input.readFully(salt)
            val iv = ByteArray(12)
            input.readFully(iv)

            // 4. Read Ciphertext
            val ciphertext = ByteArray(input.available())
            input.readFully(ciphertext)

            // 5. Derive Key
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(password.toCharArray(), salt, 250000, 256)
            val secretKey = factory.generateSecret(spec)
            val key = SecretKeySpec(secretKey.encoded, "AES")

            // 6. Decrypt
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plaintext = cipher.doFinal(ciphertext)

            // 7. Parse JSON
            val jsonStr = String(plaintext, StandardCharsets.UTF_8)
            val json = JSONObject(jsonStr)

            return CboxPayload(
                author = json.getString("author"),
                xmlContent = json.getString("xml_content"),
                signatureBase64 = json.getString("signature")
            )

        } catch (e: Exception) {
            Logger.e("$TAG: Decryption failed: ${e.message}")
            return null
        }
    }

    fun verifySignature(payload: CboxPayload, publicKeyBase64: String): Boolean {
        try {
            val keyBytes = Base64.decode(publicKeyBase64, Base64.DEFAULT)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(keySpec)

            val signer = Signature.getInstance("SHA256withRSA")
            signer.initVerify(publicKey)

            // Signature Input = author + xmlContent (concat strings)
            val signatureInput = payload.author + payload.xmlContent
            signer.update(signatureInput.toByteArray(StandardCharsets.UTF_8))

            val signatureBytes = Base64.decode(payload.signatureBase64, Base64.DEFAULT)
            return signer.verify(signatureBytes)

        } catch (e: Exception) {
            Logger.e("$TAG: Signature verification failed: ${e.message}")
            return false
        }
    }

    fun verifyFilename(payload: CboxPayload, filename: String): Boolean {
        // Filename should match sanitized author (without extension)
        val sanitizedAuthor = payload.author.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val nameWithoutExt = filename.substringBeforeLast(".")
        return sanitizedAuthor == nameWithoutExt
    }
}
