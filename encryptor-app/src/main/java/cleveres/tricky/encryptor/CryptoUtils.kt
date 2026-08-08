package cleveres.tricky.encryptor

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val KEY_ALIAS = "cleveres_encryptor_signing_key"
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val AES_ALGORITHM = "AES"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ITERATION_COUNT = 250000
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val CBOX_MAGIC = "CBOX"
    private const val CBOX_VERSION = 2
    private const val SIGNATURE_VERSION = 2
    private const val MIN_PASSWORD_CHARS = 12
    private const val MAX_PASSWORD_CHARS = 1024
    private const val MAX_AUTHOR_CHARS = 1024
    private const val MAX_XML_CHARS = 10 * 1024 * 1024

    internal var keystoreProvider = "AndroidKeyStore"
    internal var skipSigningForTest = false

    fun generateSigningKey() {
        if (skipSigningForTest) return
        val keyStore = KeyStore.getInstance(keystoreProvider)
        keyStore.load(null)
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyPairGenerator =
                java.security.KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA,
                    keystoreProvider,
                )
            keyPairGenerator.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            keyPairGenerator.generateKeyPair()
        }
    }

    fun getPublicKeyBase64(): String? {
        val keyStore = KeyStore.getInstance(keystoreProvider)
        keyStore.load(null)
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry ?: return null
        return Base64.encodeToString(entry.certificate.publicKey.encoded, Base64.NO_WRAP)
    }

    private fun signData(
        author: String,
        xmlContent: String,
    ): String {
        if (skipSigningForTest) return "DUMMY_SIGNATURE_FOR_TESTING"
        val keyStore = KeyStore.getInstance(keystoreProvider)
        keyStore.load(null)
        val entry =
            keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: throw IllegalStateException("Key not found")

        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(entry.privateKey)
        val authorBytes = author.toByteArray(StandardCharsets.UTF_8)
        val xmlBytes = xmlContent.toByteArray(StandardCharsets.UTF_8)
        try {
            signature.update(SIGNATURE_V2_DOMAIN)
            signature.update(java.nio.ByteBuffer.allocate(Int.SIZE_BYTES).putInt(authorBytes.size).array())
            signature.update(authorBytes)
            signature.update(java.nio.ByteBuffer.allocate(Int.SIZE_BYTES).putInt(xmlBytes.size).array())
            signature.update(xmlBytes)
            return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
        } finally {
            authorBytes.fill(0)
            xmlBytes.fill(0)
        }
    }

    fun encryptAndWriteCbox(
        outputStream: OutputStream,
        xmlContent: String,
        author: String,
        password: String,
    ) {
        require(author.isNotBlank() && author.length <= MAX_AUTHOR_CHARS) { "Invalid author" }
        require(xmlContent.length <= MAX_XML_CHARS) { "XML content is too large" }
        require(password.length in MIN_PASSWORD_CHARS..MAX_PASSWORD_CHARS) {
            "Password must contain 12 to 1024 characters"
        }
        // 1. Prepare Data to Sign
        val signatureBase64 = signData(author, xmlContent)

        // 2. Create JSON Payload
        val json = JSONObject()
        json.put("author", author)
        json.put("signature", signatureBase64)
        json.put("signature_version", SIGNATURE_VERSION)
        json.put("xml_content", xmlContent)
        val plaintext = json.toString().toByteArray(StandardCharsets.UTF_8)

        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv)
        val magic = CBOX_MAGIC.toByteArray(StandardCharsets.US_ASCII)
        val versionBytes = java.nio.ByteBuffer.allocate(4).putInt(CBOX_VERSION).array()
        var keyBytes: ByteArray? = null
        var ciphertext: ByteArray? = null
        try {
            val secretKeyFactory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val passwordChars = password.toCharArray()
            val keySpec = PBEKeySpec(passwordChars, salt, ITERATION_COUNT, KEY_LENGTH)
            val derivedKey =
                try {
                    secretKeyFactory.generateSecret(keySpec).encoded
                } finally {
                    keySpec.clearPassword()
                    passwordChars.fill('\u0000')
                }
            keyBytes = derivedKey
            val secretKey = SecretKeySpec(derivedKey, AES_ALGORITHM)

            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(magic)
            cipher.updateAAD(versionBytes)
            cipher.updateAAD(salt)
            cipher.updateAAD(iv)
            val encrypted = cipher.doFinal(plaintext)
            ciphertext = encrypted

            outputStream.write(magic)
            outputStream.write(versionBytes)
            outputStream.write(salt)
            outputStream.write(iv)
            outputStream.write(encrypted)
        } finally {
            plaintext.fill(0)
            keyBytes?.fill(0)
            salt.fill(0)
            iv.fill(0)
            magic.fill(0)
            versionBytes.fill(0)
            ciphertext?.fill(0)
        }
    }

    private val SIGNATURE_V2_DOMAIN =
        "CBOX-SIGNATURE-V2\u0000".toByteArray(StandardCharsets.US_ASCII)
}
