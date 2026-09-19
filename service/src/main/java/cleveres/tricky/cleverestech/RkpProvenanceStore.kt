package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.readUtf8FileSnapshotBounded
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.Normalizer
import java.security.cert.CertPathValidator
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe persistent storage for authenticated Remote Key Provisioning (RKP) provenance metadata.
 */
object RkpProvenanceStore {
    const val PROVENANCE_FILE_NAME = "rkp_provenance.json"
    private const val MAX_ENTRIES = 256
    private const val MAX_FILE_SIZE = 64 * 1024L
    private const val MAX_IDENTIFIER_LENGTH = 128
    private val lock = Any()

    private val GOOGLE_KEY_ATTESTATION_ROOT_PEM =
        "-----BEGIN CERTIFICATE-----\n" +
            "MIIFHDCCAwSgAwIBAgIJAPHBcqaZ6vUdMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNV\n" +
            "BAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMjIwMzIwMTgwNzQ4WhcNNDIwMzE1MTgw\n" +
            "NzQ4WjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0B\n" +
            "AQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdS\n" +
            "Sxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7\n" +
            "tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggj\n" +
            "nar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGq\n" +
            "C4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQ\n" +
            "oVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+O\n" +
            "JtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/Eg\n" +
            "sTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRi\n" +
            "igHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+M\n" +
            "RPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9E\n" +
            "aDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5Um\n" +
            "AGMCAwEAAaNjMGEwHQYDVR0OBBYEFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMB8GA1Ud\n" +
            "IwQYMBaAFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMA8GA1UdEwEB/wQFMAMBAf8wDgYD\n" +
            "VR0PAQH/BAQDAgIEMA0GCSqGSIb3DQEBCwUAA4ICAQB8cMqTllHc8U+qCrOlg3H7\n" +
            "174lmaCsbo/bJ0C17JEgMLb4kvrqsXZs01U3mB/qABg/1t5Pd5AORHARs1hhqGIC\n" +
            "W/nKMav574f9rZN4PC2ZlufGXb7sIdJpGiO9ctRhiLuYuly10JccUZGEHpHSYM2G\n" +
            "tkgYbZba6lsCPYAAP83cyDV+1aOkTf1RCp/lM0PKvmxYN10RYsK631jrleGdcdkx\n" +
            "oSK//mSQbgcWnmAEZrzHoF1/0gso1HZgIn0YLzVhLSA/iXCX4QT2h3J5z3znluKG\n" +
            "1nv8NQdxei2DIIhASWfu804CA96cQKTTlaae2fweqXjdN1/v2nqOhngNyz1361mF\n" +
            "mr4XmaKH/ItTwOe72NI9ZcwS1lVaCvsIkTDCEXdm9rCNPAY10iTunIHFXRh+7KPz\n" +
            "lHGewCq/8TOohBRn0/NNfh7uRslOSZ/xKbN9tMBtw37Z8d2vvnXq/YWdsm1+JLVw\n" +
            "n6yYD/yacNJBlwpddla8eaVMjsF6nBnIgQOf9zKSe06nSTqvgwUHosgOECZJZ1Eu\n" +
            "zbH4yswbt02tKtKEFhx+v+OTge/06V+jGsqTWLsfrOCNLuA8H++z+pUENmpqnnHo\n" +
            "vaI47gC+TNpkgYGkkBT6B/m/U01BuOBBTzhIlMEZq9qkDWuM2cA5kW5V3FJUcfHn\n" +
            "w1IdYIg2Wxg7yHcQZemFQg==\n" +
            "-----END CERTIFICATE-----"

    private val GOOGLE_KEY_ATTESTATION_CA1_PEM =
        "-----BEGIN CERTIFICATE-----\n" +
            "MIICIjCCAaigAwIBAgIRAISp0Cl7DrWK5/8OgN52BgUwCgYIKoZIzj0EAwMwUjEc\n" +
            "MBoGA1UEAwwTS2V5IEF0dGVzdGF0aW9uIENBMTEQMA4GA1UECwwHQW5kcm9pZDET\n" +
            "MBEGA1UECgwKR29vZ2xlIExMQzELMAkGA1UEBhMCVVMwHhcNMjUwNzE3MjIzMjE4\n" +
            "WhcNMzUwNzE1MjIzMjE4WjBSMRwwGgYDVQQDDBNLZXkgQXR0ZXN0YXRpb24gQ0Ex\n" +
            "MRAwDgYDVQQLDAdBbmRyb2lkMRMwEQYDVQQKDApHb29nbGUgTExDMQswCQYDVQQG\n" +
            "EwJVUzB2MBAGByqGSM49AgEGBSuBBAAiA2IABCPaI3FO3z5bBQo8cuiEas4HjqCt\n" +
            "G/mLFfRT0MsIssPBEEU5Cfbt6sH5yOAxqEi5QagpU1yX4HwnGb7OtBYpDTB57uH5\n" +
            "Eczm34A5FNijV3s0/f0UPl7zbJcTx6xwqMIRq6NCMEAwDwYDVR0TAQH/BAUwAwEB\n" +
            "/zAOBgNVHQ8BAf8EBAMCAQYwHQYDVR0OBBYEFFIyuyz7RkOb3NaBqQ5lZuA0QepA\n" +
            "MAoGCCqGSM49BAMDA2gAMGUCMETfjPO/HwqReR2CS7p0ZWoD/LHs6hDi422opifH\n" +
            "EUaYLxwGlT9SLdjkVpz0UUOR5wIxAIoGyxGKRHVTpqpGRFiJtQEOOTp/+s1GcxeY\n" +
            "uR2zh/80lQyu9vAFCj6E4AXc+osmRg==\n" +
            "-----END CERTIFICATE-----"

    private val defaultTrustedRoots: List<X509Certificate> by lazy {
        val cf = CertificateFactory.getInstance("X.509")
        listOf(
            cf.generateCertificate(ByteArrayInputStream(GOOGLE_KEY_ATTESTATION_ROOT_PEM.toByteArray(Charsets.UTF_8))) as X509Certificate,
            cf.generateCertificate(ByteArrayInputStream(GOOGLE_KEY_ATTESTATION_CA1_PEM.toByteArray(Charsets.UTF_8))) as X509Certificate,
        )
    }

    private val testTrustedAnchors = CopyOnWriteArrayList<X509Certificate>()

    fun addTrustedAnchorForTesting(cert: X509Certificate) {
        testTrustedAnchors.add(cert)
    }

    fun getAllowedTrustAnchors(): List<X509Certificate> =
        defaultTrustedRoots + testTrustedAnchors

    fun normalizeIdentifier(identifier: String): String {
        val stripped = identifier.substringAfterLast(':').substringAfterLast('/').substringAfterLast('\\').trim()
        if (stripped.isEmpty() || stripped.length > MAX_IDENTIFIER_LENGTH) return ""
        // Canonicalize Unicode so visually identical names cannot become distinct
        // provenance keys. Case is intentionally preserved: on case-sensitive
        // filesystems upper and lower case names are different files.
        val canonical = Normalizer.normalize(stripped, Normalizer.Form.NFC)
        if (canonical.isEmpty() || canonical.length > MAX_IDENTIFIER_LENGTH) return ""
        return canonical
    }

    fun isRkp(
        identifier: String,
        baseDir: File = Config.getConfigRoot(),
    ): Boolean {
        val normalized = normalizeIdentifier(identifier)
        if (normalized.isEmpty()) return false
        val entries = load(baseDir)
        return entries.contains(normalized)
    }

    fun recordRkp(
        identifier: String,
        baseDir: File = Config.getConfigRoot(),
    ) {
        val normalized = normalizeIdentifier(identifier)
        if (normalized.isEmpty()) return
        synchronized(lock) {
            val current = load(baseDir).toMutableSet()
            if (current.add(normalized)) {
                save(baseDir, current)
            }
        }
    }

    fun removeRkp(
        identifier: String,
        baseDir: File = Config.getConfigRoot(),
    ) {
        val normalized = normalizeIdentifier(identifier)
        if (normalized.isEmpty()) return
        synchronized(lock) {
            val current = load(baseDir).toMutableSet()
            if (current.remove(normalized)) {
                save(baseDir, current)
            }
        }
    }

    @JvmStatic
    fun hasVerifiedRkpCertificates(certs: List<Certificate>?): Boolean {
        if (certs.isNullOrEmpty()) return false
        val x509Certs = ArrayList<X509Certificate>(certs.size)
        for (cert in certs) {
            val x509 = cert as? X509Certificate ?: return false
            x509Certs.add(x509)
        }

        val anchors = getAllowedTrustAnchors()
        val terminalCert = x509Certs.last()

        val matchingAnchor = anchors.firstOrNull { Arrays.equals(it.encoded, terminalCert.encoded) }
        val (selectedAnchor, pathCerts) =
            when {
                matchingAnchor != null -> {
                    if (x509Certs.size < 2) return false
                    matchingAnchor to x509Certs.subList(0, x509Certs.size - 1)
                }
                else -> {
                    val issuingAnchor =
                        anchors.firstOrNull { anchor ->
                            if (anchor.subjectX500Principal != terminalCert.issuerX500Principal) {
                                false
                            } else {
                                try {
                                    terminalCert.verify(anchor.publicKey)
                                    true
                                } catch (_: Exception) {
                                    false
                                }
                            }
                        } ?: return false
                    issuingAnchor to x509Certs
                }
            }

        try {
            val cf = CertificateFactory.getInstance("X.509")
            val certPath = cf.generateCertPath(pathCerts)
            val validator = CertPathValidator.getInstance("PKIX")
            val params = PKIXParameters(setOf(TrustAnchor(selectedAnchor, null)))
            params.isRevocationEnabled = false
            validator.validate(certPath, params)
        } catch (_: Exception) {
            return false
        }

        // Ensure the chain represents Remote Key Provisioning (RKP)
        return x509Certs.any { cert ->
            val subject = cert.subjectX500Principal?.name?.lowercase(Locale.ROOT) ?: ""
            val issuer = cert.issuerX500Principal?.name?.lowercase(Locale.ROOT) ?: ""
            fun isRkpDn(dn: String): Boolean =
                dn.contains("droid ca") ||
                    dn.contains("remote key provisioning") ||
                    dn.contains("remoteprovisioning") ||
                    dn.contains("key provisioning")
            isRkpDn(subject) || isRkpDn(issuer)
        }
    }

    @JvmStatic
    fun hasVerifiedRkpCertificates(keybox: CertHack.KeyBox?): Boolean =
        if (keybox == null) false else hasVerifiedRkpCertificates(keybox.certificates())

    private fun load(baseDir: File): Set<String> {
        val file = File(baseDir, PROVENANCE_FILE_NAME)
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return emptySet()
        }
        return try {
            // Single snapshot read: the size pre-check alone cannot bind a
            // concurrently growing or swapped file.
            val content = readUtf8FileSnapshotBounded(file, 0, MAX_FILE_SIZE)
            val json = JSONObject(content)
            val array = json.optJSONArray("rkp_keyboxes") ?: return emptySet()
            val result = HashSet<String>(array.length())
            for (i in 0 until array.length()) {
                val normalized = normalizeIdentifier(array.optString(i))
                if (normalized.isNotBlank()) {
                    result.add(normalized)
                }
            }
            result
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun save(
        baseDir: File,
        entries: Set<String>,
    ) {
        require(entries.size <= MAX_ENTRIES) { "Too many RKP provenance entries" }
        val file = File(baseDir, PROVENANCE_FILE_NAME)
        if (entries.isEmpty()) {
            if (file.exists()) file.delete()
            return
        }
        val json = JSONObject()
        val array = JSONArray()
        entries.sorted().forEach { array.put(it) }
        json.put("rkp_keyboxes", array)
        SecureFile.writeText(file, json.toString(2))
    }

    fun resetForTesting(baseDir: File = Config.getConfigRoot()) {
        testTrustedAnchors.clear()
        synchronized(lock) {
            val file = File(baseDir, PROVENANCE_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        }
    }
}
