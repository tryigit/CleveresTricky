package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Unavoidable Android/JCA adapter for public certificate objects. XML/PEM/private-key parsing and
 * private-key validation live in the unprivileged Rust backend; managed state stores only an opaque
 * key ID, algorithm metadata and public X.509 certificates.
 */
internal object KeyboxJcaAdapter {
    fun materialize(
        document: KeyboxWire.Document,
        filename: String,
        authenticatedRkpProvenance: Boolean = false,
    ): List<CertHack.KeyBox> {
        if (filename.isEmpty() || document.keys.isEmpty()) return emptyList()
        val parsed = ArrayList<CertHack.KeyBox>(document.keys.size)
        for (raw in document.keys) {
            val keybox = materializeKey(raw, filename, authenticatedRkpProvenance) ?: return emptyList()
            parsed += keybox
        }
        return parsed
    }

    private fun materializeKey(
        raw: KeyboxWire.RawKey,
        filename: String,
        authenticatedRkpProvenance: Boolean = false,
    ): CertHack.KeyBox? =
        try {
            val certificates = parseCertificates(raw.certificatesDer) ?: return null
            val leaf = certificates.firstOrNull() as? X509Certificate ?: return null
            val publicAlgorithm = normalizeAlgorithm(leaf.publicKey.algorithm) ?: return null
            val declaredAlgorithm = normalizeAlgorithm(raw.algorithm) ?: return null
            if (publicAlgorithm != declaredAlgorithm) return null
            if (!validChain(certificates)) return null

            val handle = BackendKeyHandle(publicAlgorithm, raw.keyId)
            val isRkp = authenticatedRkpProvenance ||
                RkpProvenanceStore.hasVerifiedRkpCertificates(certificates)
            CertHack.KeyBox(KeyPair(leaf.publicKey, handle), certificates, filename, isRkp)
        } catch (_: Exception) {
            null
        }

    private fun parseCertificates(certificatesDer: List<ByteArray>): List<Certificate>? {
        if (certificatesDer.isEmpty()) return null
        val factory = CertificateFactory.getInstance("X.509")
        val certificates = ArrayList<Certificate>(certificatesDer.size)
        for (der in certificatesDer) {
            if (der.isEmpty()) return null
            val certificate = ByteArrayInputStream(der).use(factory::generateCertificate)
            certificates += certificate
        }
        return certificates
    }

    private fun validChain(certificates: List<Certificate>): Boolean {
        for (index in certificates.indices) {
            val certificate = certificates[index] as? X509Certificate ?: return false
            try {
                certificate.checkValidity()
                if (index + 1 < certificates.size) {
                    certificate.verify(certificates[index + 1].publicKey)
                }
            } catch (_: Exception) {
                return false
            }
        }
        return true
    }

    private fun normalizeAlgorithm(algorithm: String?): String? =
        when {
            algorithm.equals("EC", ignoreCase = true) ||
                algorithm.equals("ECDSA", ignoreCase = true) -> "EC"
            algorithm.equals("RSA", ignoreCase = true) -> "RSA"
            else -> null
        }
}
