package cleveres.tricky.cleverestech.util

import android.util.Base64
import cleveres.tricky.cleverestech.Logger
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CboxDecryptor {
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ITERATION_COUNT = 250_000
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16
    private const val CBOX_MAGIC = "CBOX"
    private const val MAX_CIPHERTEXT_BYTES = 10 * 1024 * 1024
    private const val MAX_XML_CHARS = 10 * 1024 * 1024
    private const val MAX_SIGNATURE_CHARS = 16 * 1024
    private const val MAX_AUTHOR_CHARS = 1024
    private const val MAX_PASSWORD_CHARS = 1024

    data class CboxPayload(
        val author: String,
        val xmlContent: String,
        val signatureBase64: String,
        val signatureVersion: Int,
    )

    fun hasSupportedEnvelopeHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 4 + Int.SIZE_BYTES + SALT_LENGTH + IV_LENGTH + GCM_TAG_LENGTH) return false
        if (!bytes.copyOfRange(0, 4).contentEquals(CBOX_MAGIC.toByteArray(StandardCharsets.US_ASCII))) {
            return false
        }
        return ByteBuffer.wrap(bytes, 4, Int.SIZE_BYTES).int in 1..2
    }

    fun decrypt(
        inputStream: InputStream,
        password: String,
    ): CboxPayload? {
        if (password.length > MAX_PASSWORD_CHARS) return null

        val magic = ByteArray(4)
        val versionBytes = ByteArray(Int.SIZE_BYTES)
        val salt = ByteArray(SALT_LENGTH)
        val iv = ByteArray(IV_LENGTH)
        var ciphertext: ByteArray? = null
        var keyBytes: ByteArray? = null
        try {
            if (!readFully(inputStream, magic) || String(magic, StandardCharsets.US_ASCII) != CBOX_MAGIC) {
                Logger.e("Invalid CBOX header")
                return null
            }
            if (!readFully(inputStream, versionBytes)) return null
            val version = ByteBuffer.wrap(versionBytes).int
            if (version !in 1..2) {
                Logger.e("Unsupported CBOX version")
                return null
            }
            if (!readFully(inputStream, salt) || !readFully(inputStream, iv)) return null

            val encrypted = readLimited(inputStream, MAX_CIPHERTEXT_BYTES) ?: return null
            ciphertext = encrypted
            if (encrypted.size < GCM_TAG_LENGTH) {
                Logger.e("CBOX ciphertext is shorter than its authentication tag")
                return null
            }

            val derivedKey = deriveKey(password, salt)
            keyBytes = derivedKey
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(derivedKey, "AES"),
                GCMParameterSpec(128, iv),
            )
            if (version == 2) {
                cipher.updateAAD(magic)
                cipher.updateAAD(versionBytes)
                cipher.updateAAD(salt)
                cipher.updateAAD(iv)
            }
            val plaintext = cipher.doFinal(encrypted)
            try {
                val json = JSONObject(String(plaintext, StandardCharsets.UTF_8))
                val author = json.getString("author")
                val xmlContent = json.getString("xml_content")
                val signature = json.getString("signature")
                val signatureVersion = json.optInt("signature_version", 1)
                if (author.length > MAX_AUTHOR_CHARS ||
                    xmlContent.length > MAX_XML_CHARS ||
                    signature.length > MAX_SIGNATURE_CHARS ||
                    signatureVersion !in 1..2
                ) {
                    Logger.e("CBOX metadata exceeds safety limits")
                    return null
                }
                return CboxPayload(author, xmlContent, signature, signatureVersion)
            } finally {
                plaintext.fill(0)
            }
        } catch (e: Exception) {
            Logger.e("CBOX decryption failed: ${e.javaClass.simpleName}")
            return null
        } finally {
            magic.fill(0)
            versionBytes.fill(0)
            salt.fill(0)
            iv.fill(0)
            ciphertext?.fill(0)
            keyBytes?.fill(0)
        }
    }

    fun verifySignature(
        payload: CboxPayload,
        publicKeyBase64: String,
    ): Boolean {
        if (publicKeyBase64.length > MAX_SIGNATURE_CHARS ||
            payload.signatureBase64.length > MAX_SIGNATURE_CHARS
        ) {
            return false
        }

        var publicKeyBytes: ByteArray? = null
        var signatureBytes: ByteArray? = null
        val sensitiveParts = ArrayList<ByteArray>()
        return try {
            val decodedPublicKey = Base64.decode(publicKeyBase64, Base64.DEFAULT)
            publicKeyBytes = decodedPublicKey
            val publicKey = parsePublicKey(decodedPublicKey) ?: return false
            val verifier =
                Signature.getInstance(
                    when (publicKey.algorithm.uppercase()) {
                        "EC", "ECDSA" -> "SHA256withECDSA"
                        else -> "SHA256withRSA"
                    },
                )
            verifier.initVerify(publicKey)
            updateSignature(verifier, payload, sensitiveParts)
            val decodedSignature = Base64.decode(payload.signatureBase64, Base64.DEFAULT)
            signatureBytes = decodedSignature
            verifier.verify(decodedSignature)
        } catch (e: Exception) {
            Logger.e("CBOX signature verification failed: ${e.javaClass.simpleName}")
            false
        } finally {
            publicKeyBytes?.fill(0)
            signatureBytes?.fill(0)
            sensitiveParts.forEach { it.fill(0) }
        }
    }

    private fun updateSignature(
        verifier: Signature,
        payload: CboxPayload,
        sensitiveParts: MutableList<ByteArray>,
    ) {
        val author = payload.author.toByteArray(StandardCharsets.UTF_8).also(sensitiveParts::add)
        val xml = payload.xmlContent.toByteArray(StandardCharsets.UTF_8).also(sensitiveParts::add)
        if (payload.signatureVersion == 1) {
            verifier.update(author)
            verifier.update(xml)
            return
        }
        verifier.update(SIGNATURE_V2_DOMAIN)
        verifier.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(author.size).array())
        verifier.update(author)
        verifier.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(xml.size).array())
        verifier.update(xml)
    }

    private fun parsePublicKey(encoded: ByteArray): PublicKey? {
        val spec = X509EncodedKeySpec(encoded)
        return runCatching { KeyFactory.getInstance("RSA").generatePublic(spec) }
            .recoverCatching { KeyFactory.getInstance("EC").generatePublic(spec) }
            .getOrNull()
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

    private fun readFully(
        input: InputStream,
        destination: ByteArray,
    ): Boolean {
        var offset = 0
        while (offset < destination.size) {
            val count = input.read(destination, offset, destination.size - offset)
            if (count < 0) return false
            if (count == 0) continue
            offset += count
        }
        return true
    }

    private fun readLimited(
        input: InputStream,
        maxBytes: Int,
    ): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count > maxBytes - total) return null
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    private val SIGNATURE_V2_DOMAIN =
        "CBOX-SIGNATURE-V2\u0000".toByteArray(StandardCharsets.US_ASCII)
}
