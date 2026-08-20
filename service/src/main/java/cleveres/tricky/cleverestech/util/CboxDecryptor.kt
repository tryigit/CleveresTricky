package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.NativeBackend
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Compatibility seam for legacy callers. CBOX cryptography and signature verification live exclusively
 * in the unprivileged Rust backend; this object only classifies envelopes and bounds stream ingestion.
 */
object CboxDecryptor {
    private const val CBOX_MAGIC = "CBOX"
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16
    private const val HEADER_BYTES = 4 + Int.SIZE_BYTES + SALT_LENGTH + IV_LENGTH + GCM_TAG_LENGTH
    private const val MAX_CBOX_BYTES = 10 * 1024 * 1024 + 36
    private const val MAX_PASSWORD_CHARS = 1024
    private const val READ_BUFFER_BYTES = 16 * 1024
    private const val MAX_EMPTY_READS = 16

    private val magicBytes = CBOX_MAGIC.toByteArray(StandardCharsets.US_ASCII)

    @Volatile
    internal var backendOpenOverride: ((ByteArray, String, String?) -> NativeBackend.CboxPayload?)? = null

    class CboxPayload internal constructor(
        encrypted: ByteArray,
        private val password: String,
    ) {
        private var encryptedBytes: ByteArray? = encrypted
        private var opened: NativeBackend.CboxPayload? = null
        private var attemptedWithoutKey = false
        private var verifiedPublicKey: String? = null

        val author: String
            get() = openWithoutVerification()?.author.orEmpty()

        internal val hasSignature: Boolean
            get() = openWithoutVerification()?.hasSignature == true

        @Synchronized
        internal fun discard() {
            opened?.xmlContent?.fill(0)
            opened = null
            encryptedBytes?.fill(0)
            encryptedBytes = null
        }

        /** Temporary legacy accessor. Prefer [takeXmlContentBytes] for production parsing. */
        val xmlContent: String
            get() {
                val payload = openWithoutVerification() ?: return ""
                if (payload.hasSignature && verifiedPublicKey == null) return ""
                return String(payload.xmlContent, StandardCharsets.UTF_8)
            }

        /** Mutable copy for compatibility callers. The caller owns and must clear the returned bytes. */
        internal val xmlContentBytes: ByteArray
            get() {
                val payload = openWithoutVerification() ?: return ByteArray(0)
                if (payload.hasSignature && verifiedPublicKey == null) return ByteArray(0)
                return payload.xmlContent.copyOf()
            }

        /**
         * Transfer plaintext XML to a byte-oriented caller and wipe the backend response buffer.
         * The caller owns and must clear the returned bytes.
         */
        @Synchronized
        internal fun takeXmlContentBytes(): ByteArray {
            val payload = openWithoutVerification() ?: return ByteArray(0)
            if (payload.hasSignature && verifiedPublicKey == null) {
                discard()
                return ByteArray(0)
            }
            val copy = payload.xmlContent.copyOf()
            payload.xmlContent.fill(0)
            return copy
        }

        @Synchronized
        internal fun verify(publicKey: String): Boolean {
            val normalizedKey = publicKey.takeUnless { it.isBlank() } ?: return false
            if (verifiedPublicKey != null) return verifiedPublicKey == normalizedKey

            val encrypted = encryptedBytes ?: return false
            val verified = openBackend(encrypted, password, normalizedKey) ?: return false
            if (!verified.hasSignature) {
                verified.xmlContent.fill(0)
                return false
            }

            opened?.takeIf { it !== verified }?.xmlContent?.fill(0)
            opened = verified
            verifiedPublicKey = normalizedKey
            encrypted.fill(0)
            encryptedBytes = null
            return true
        }

        @Synchronized
        private fun openWithoutVerification(): NativeBackend.CboxPayload? {
            opened?.let { return it }
            if (attemptedWithoutKey) return null
            attemptedWithoutKey = true
            val encrypted = encryptedBytes ?: return null
            val payload = openBackend(encrypted, password, null)
            if (payload == null) {
                encrypted.fill(0)
                encryptedBytes = null
                return null
            }
            opened = payload
            if (!payload.hasSignature) {
                encrypted.fill(0)
                encryptedBytes = null
            }
            return payload
        }
    }

    fun hasSupportedEnvelopeHeader(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_BYTES) return false
        for (index in magicBytes.indices) {
            if (bytes[index] != magicBytes[index]) return false
        }
        val versionOffset = magicBytes.size
        val version =
            ((bytes[versionOffset].toInt() and 0xff) shl 24) or
                ((bytes[versionOffset + 1].toInt() and 0xff) shl 16) or
                ((bytes[versionOffset + 2].toInt() and 0xff) shl 8) or
                (bytes[versionOffset + 3].toInt() and 0xff)
        return version in 1..2
    }

    fun decrypt(
        inputStream: InputStream,
        password: String,
    ): CboxPayload? {
        if (password.length > MAX_PASSWORD_CHARS) return null
        val encrypted = readBounded(inputStream) ?: return null
        if (!hasSupportedEnvelopeHeader(encrypted)) {
            encrypted.fill(0)
            return null
        }
        return CboxPayload(encrypted, password)
    }

    fun verifySignature(
        payload: CboxPayload,
        publicKeyBase64: String,
    ): Boolean = publicKeyBase64.isNotBlank() && payload.verify(publicKeyBase64)

    internal fun resetForTesting() {
        backendOpenOverride = null
    }

    private fun openBackend(
        encrypted: ByteArray,
        password: String,
        publicKey: String?,
    ): NativeBackend.CboxPayload? {
        val override = backendOpenOverride
        return if (override != null) {
            override(encrypted, password, publicKey)
        } else {
            NativeBackend.openCbox(encrypted, password, publicKey)
        }
    }

    private fun readBounded(input: InputStream): ByteArray? {
        val initialCapacity = input.available().coerceIn(0, MAX_CBOX_BYTES).coerceAtLeast(HEADER_BYTES)
        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var total = 0
        var emptyReads = 0
        return try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    if (++emptyReads > MAX_EMPTY_READS) return null
                    continue
                }
                emptyReads = 0
                total = Math.addExact(total, count)
                if (total > MAX_CBOX_BYTES) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } catch (_: ArithmeticException) {
            null
        } finally {
            buffer.fill(0)
            output.reset()
        }
    }
}
