package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.Logger
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.security.Principal
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date

class KeyboxVerifierRevocationTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setup() {
            Logger.setImpl(
                object : Logger.LogImpl {
                    override fun d(
                        tag: String,
                        msg: String,
                    ) {}

                    override fun e(
                        tag: String,
                        msg: String,
                    ) {
                        println("E/$tag: $msg")
                    }

                    override fun e(
                        tag: String,
                        msg: String,
                        t: Throwable?,
                    ) {
                        println("E/$tag: $msg")
                    }

                    override fun i(
                        tag: String,
                        msg: String,
                    ) {}
                },
            )
        }
    }

    @Test
    fun testKeyIdRevocation() {
        val cert =
            object : X509Certificate() {
                override fun getSerialNumber(): BigInteger = BigInteger("12345") // Hex: 3039

                override fun getPublicKey(): PublicKey {
                    return object : PublicKey {
                        override fun getAlgorithm(): String = "EC"

                        override fun getFormat(): String = "X.509"

                        override fun getEncoded(): ByteArray = byteArrayOf(0x01, 0x02, 0x03, 0x04)
                    }
                }

                override fun getEncoded(): ByteArray = byteArrayOf(0x00) // Dummy

                override fun hasUnsupportedCriticalExtension(): Boolean = false

                override fun getCriticalExtensionOIDs(): MutableSet<String>? = null

                override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null

                override fun getExtensionValue(oid: String?): ByteArray? = null

                override fun checkValidity() {}

                override fun checkValidity(date: Date?) {}

                override fun getVersion(): Int = 3

                override fun getIssuerDN(): Principal? = null

                override fun getSubjectDN(): Principal? = null

                override fun getNotBefore(): Date? = null

                override fun getNotAfter(): Date? = null

                override fun getTBSCertificate(): ByteArray? = null

                override fun getSignature(): ByteArray? = null

                override fun getSigAlgName(): String? = null

                override fun getSigAlgOID(): String? = null

                override fun getSigAlgParams(): ByteArray? = null

                override fun getIssuerUniqueID(): BooleanArray? = null

                override fun getSubjectUniqueID(): BooleanArray? = null

                override fun getKeyUsage(): BooleanArray? = null

                override fun getBasicConstraints(): Int = -1

                override fun verify(key: PublicKey?) {}

                override fun verify(
                    key: PublicKey?,
                    sigProvider: String?,
                ) {}

                override fun toString(): String = "MockCert"
            }

        // Calculate SHA-1 of public key (Key ID)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(cert.publicKey.encoded)
        // Format as zero-padded hex string
        val keyIdHex = sha1.joinToString("") { "%02x".format(it) }

        // 2. Create Revocation List containing the Key ID
        val revokedSerials = setOf(keyIdHex)

        println("Serial Number (Hex): ${cert.serialNumber.toString(16)}")
        println("Key ID (Hex): $keyIdHex")

        // 3. Verify using NEW Logic
        // This assertion should PASS now.
        assertTrue("Certificate should be revoked by Key ID", KeyboxVerifier.isRevoked(cert, revokedSerials))
    }
}
