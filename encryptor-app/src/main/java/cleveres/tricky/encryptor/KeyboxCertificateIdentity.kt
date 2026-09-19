package cleveres.tricky.encryptor

import java.io.ByteArrayInputStream
import java.nio.charset.CodingErrorAction
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

internal object KeyboxCertificateIdentity {
    private const val MAX_XML_BYTES = 10 * 1024 * 1024
    private val chainOpen = "<CertificateChain".toByteArray(Charsets.US_ASCII)
    private val chainClose = "</CertificateChain>".toByteArray(Charsets.US_ASCII)
    private val pemBegin = "-----BEGIN CERTIFICATE-----".toByteArray(Charsets.US_ASCII)
    private val pemEnd = "-----END CERTIFICATE-----".toByteArray(Charsets.US_ASCII)

    fun leafCertificateSerial(xmlUtf8: ByteArray): String? {
        if (xmlUtf8.size !in 1..MAX_XML_BYTES) return null
        // Reject malformed UTF-8 here as a defense-in-depth naming check. The caller only
        // reaches this function after the Rust keybox validator accepted the same bytes.
        runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(xmlUtf8))
        }.getOrNull() ?: return null

        val chainStart = indexOf(xmlUtf8, chainOpen, 0, xmlUtf8.size)
        if (chainStart < 0) return null
        val chainBody = indexOfByte(xmlUtf8, '>'.code.toByte(), chainStart, xmlUtf8.size)
        if (chainBody < 0) return null
        val chainEnd = indexOf(xmlUtf8, chainClose, chainBody + 1, xmlUtf8.size)
        if (chainEnd < 0) return null

        // Keybox certificate chains are ordered leaf-first: the first block is
        // the device attestation certificate. Later blocks are the shared
        // Google intermediate and root, whose serials are identical across
        // keyboxes and must never identify one file.
        val begin = indexOf(xmlUtf8, pemBegin, chainBody + 1, chainEnd)
        if (begin < 0) return null
        val endMarker = indexOf(xmlUtf8, pemEnd, begin + pemBegin.size, chainEnd)
        if (endMarker < 0) return null
        val end = endMarker + pemEnd.size
        val pem = xmlUtf8.copyOfRange(begin, end)
        return try {
            val certificate = ByteArrayInputStream(pem).use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            } as? X509Certificate ?: return null
            certificate.serialNumber.toString(16).uppercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        } finally {
            pem.fill(0)
        }
    }

    private fun indexOf(
        bytes: ByteArray,
        needle: ByteArray,
        start: Int,
        endExclusive: Int,
    ): Int {
        if (needle.isEmpty() || start < 0 || endExclusive > bytes.size || start >= endExclusive) return -1
        val last = endExclusive - needle.size
        if (last < start) return -1
        for (index in start..last) {
            var matches = true
            for (offset in needle.indices) {
                if (bytes[index + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return index
        }
        return -1
    }

    private fun indexOfByte(
        bytes: ByteArray,
        value: Byte,
        start: Int,
        endExclusive: Int,
    ): Int {
        if (start < 0 || endExclusive > bytes.size || start >= endExclusive) return -1
        for (index in start until endExclusive) if (bytes[index] == value) return index
        return -1
    }
}
