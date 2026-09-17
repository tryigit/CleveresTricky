package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.keystore.ManagedKeyboxOracle
import java.io.Reader
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate

/** Test-only bridge that models Rust-owned key material with deterministic opaque handles. */
internal object ManagedOpaqueKeyOracle {
    data class Material(
        val privateKey: PrivateKey,
        val issuerCertificate: X509Certificate,
    )

    private val materials = LinkedHashMap<String, Material>()

    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun parse(
        reader: Reader,
        filename: String,
        authenticatedRkpProvenance: Boolean = false,
    ): List<CertHack.KeyBox> {
        val legacy = ManagedKeyboxOracle.parse(reader, filename, authenticatedRkpProvenance)
        if (legacy.isEmpty()) return emptyList()
        val opaque = ArrayList<CertHack.KeyBox>(legacy.size)
        for (box in legacy) {
            opaque += wrap(box.keyPair(), box.certificates(), filename, authenticatedRkpProvenance)
        }
        return opaque
    }

    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun wrap(
        keyPair: KeyPair,
        certificates: List<Certificate>,
        filename: String,
        authenticatedRkpProvenance: Boolean = false,
    ): CertHack.KeyBox {
        val leaf = certificates.firstOrNull() as? X509Certificate
            ?: throw IllegalArgumentException("Opaque key fixture requires an X.509 issuer certificate")
        val privateKey = keyPair.private
        val privateDer = privateKey.encoded
            ?: throw IllegalArgumentException("Opaque key fixture requires encodable test key material")
        val leafDer = leaf.encoded
        val digest = MessageDigest.getInstance("SHA-256")
        val keyId =
            try {
                digest.update(privateKey.algorithm.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                digest.update(privateDer)
                digest.digest(leafDer).copyOf(KEY_ID_BYTES).also { id ->
                    if (id.all { it == 0.toByte() }) id[0] = 1
                }
            } finally {
                privateDer.fill(0)
                leafDer.fill(0)
            }
        return try {
            materials[key(keyId)] = Material(privateKey, leaf)
            CertHack.KeyBox(
                KeyPair(
                    keyPair.public,
                    BackendKeyHandle(privateKey.algorithm, keyId),
                ),
                certificates,
                filename,
                authenticatedRkpProvenance,
            )
        } finally {
            keyId.fill(0)
        }
    }

    @Synchronized
    fun readFromXml(reader: Reader?) {
        materials.clear()
        if (reader == null) {
            CertHack.setKeyboxes(emptyList())
            return
        }
        CertHack.setKeyboxes(parse(reader, "keybox.xml"))
    }

    @Synchronized
    fun lookup(keyId: ByteArray): Material? = materials[key(keyId)]

    @Synchronized
    fun contains(keyId: ByteArray): Boolean = materials.containsKey(key(keyId))

    @Synchronized
    fun reset() {
        materials.clear()
        CertHack.setKeyboxes(emptyList())
    }

    private fun key(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private const val KEY_ID_BYTES = 16
    private const val HEX = "0123456789abcdef"
}
