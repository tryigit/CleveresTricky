package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.keystore.ManagedKeyboxOracle
import java.io.InputStreamReader
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class KeyboxJcaAdapterTest {
    @Test
    fun `opaque adapter matches managed EC public oracle`() {
        assertAdapterMatchesLegacy(
            resource = "/keybox/valid_ec.xml",
            filename = "valid_ec.xml",
            declaredAlgorithm = "EC",
            expectedAlgorithm = "EC",
        )
    }

    @Test
    fun `opaque adapter matches managed RSA public oracle`() {
        assertAdapterMatchesLegacy(
            resource = "/keybox/valid_rsa.xml",
            filename = "valid_rsa.xml",
            declaredAlgorithm = "RSA",
            expectedAlgorithm = "RSA",
        )
    }

    @Test
    fun `algorithm mismatch fails closed`() {
        silenceLogger()
        val legacy = readLegacyFixture("/keybox/valid_ec.xml", "valid_ec.xml").single()
        val document =
            KeyboxWire.Document(
                declaredKeyboxes = 1,
                keyboxCount = 1,
                snapshotSha256 = validSnapshotSha256(),
                keys =
                    listOf(
                        KeyboxWire.RawKey(
                            "RSA",
                            validKeyId(),
                            legacy.certificates().map { it.encoded },
                        ),
                    ),
            )

        assertTrue(KeyboxJcaAdapter.materialize(document, "mismatch.xml").isEmpty())
    }

    @Test
    fun `corrupted certificate DER fails closed`() {
        val document =
            KeyboxWire.Document(
                declaredKeyboxes = 1,
                keyboxCount = 1,
                snapshotSha256 = validSnapshotSha256(),
                keys =
                    listOf(
                        KeyboxWire.RawKey(
                            "EC",
                            validKeyId(),
                            listOf(byteArrayOf(0x31, 1, 2, 3)),
                        ),
                    ),
            )

        assertTrue(KeyboxJcaAdapter.materialize(document, "corrupt.xml").isEmpty())
    }

    @Test
    fun `expired certificate makes chain validation fail closed`() {
        val expired = Mockito.mock(X509Certificate::class.java)
        Mockito.doThrow(CertificateExpiredException()).`when`(expired).checkValidity()
        val method = KeyboxJcaAdapter::class.java.getDeclaredMethod("validChain", List::class.java)
        method.isAccessible = true

        assertFalse(method.invoke(KeyboxJcaAdapter, listOf(expired)) as Boolean)
        Mockito.verify(expired).checkValidity()
    }

    @Test
    fun `materialize propagates authenticated RKP provenance when requested`() {
        silenceLogger()
        val legacy = readLegacyFixture("/keybox/valid_ec.xml", "valid_ec.xml").single()
        val document =
            KeyboxWire.Document(
                declaredKeyboxes = 1,
                keyboxCount = 1,
                snapshotSha256 = validSnapshotSha256(),
                keys =
                    listOf(
                        KeyboxWire.RawKey(
                            "EC",
                            validKeyId(),
                            legacy.certificates().map { it.encoded },
                        ),
                    ),
            )

        val authenticated = KeyboxJcaAdapter.materialize(document, "rkp.xml", authenticatedRkpProvenance = true)
        assertEquals(1, authenticated.size)
        assertTrue(authenticated.single().authenticatedRkpProvenance())
        assertTrue(CertHack.isRkpKeybox(authenticated.single()))

        val unauthenticated = KeyboxJcaAdapter.materialize(document, "rkp.xml")
        assertEquals(1, unauthenticated.size)
        assertFalse(unauthenticated.single().authenticatedRkpProvenance())
        assertFalse(CertHack.isRkpKeybox(unauthenticated.single()))
    }

    private fun assertAdapterMatchesLegacy(
        resource: String,
        filename: String,
        declaredAlgorithm: String,
        expectedAlgorithm: String,
    ) {
        silenceLogger()
        val legacy = readLegacyFixture(resource, filename).single()
        val keyId = validKeyId()
        val document =
            KeyboxWire.Document(
                declaredKeyboxes = 1,
                keyboxCount = 1,
                snapshotSha256 = validSnapshotSha256(),
                keys =
                    listOf(
                        KeyboxWire.RawKey(
                            algorithm = declaredAlgorithm,
                            keyId = keyId,
                            certificatesDer = legacy.certificates().map { it.encoded },
                        ),
                    ),
            )

        val migrated = KeyboxJcaAdapter.materialize(document, filename).single()
        assertEquals(expectedAlgorithm, migrated.keyPair().private.algorithm)
        assertEquals("CleveresTricky-KeyId-v1", migrated.keyPair().private.format)
        assertArrayEquals(keyId, migrated.keyPair().private.encoded)
        assertArrayEquals(legacy.keyPair().public.encoded, migrated.keyPair().public.encoded)
        assertEquals(legacy.certificates().size, migrated.certificates().size)
        for (index in legacy.certificates().indices) {
            assertArrayEquals(
                legacy.certificates()[index].encoded,
                migrated.certificates()[index].encoded,
            )
        }
    }

    private fun validKeyId(): ByteArray = ByteArray(16) { index -> (index + 1).toByte() }

    private fun validSnapshotSha256(): String = "00".repeat(32)

    private fun readLegacyFixture(
        resource: String,
        filename: String,
    ): List<CertHack.KeyBox> {
        val stream = requireNotNull(javaClass.getResourceAsStream(resource))
        return InputStreamReader(stream, Charsets.UTF_8).use {
            ManagedKeyboxOracle.parse(it, filename)
        }
    }

    private fun silenceLogger() {
        Logger.setImpl(
            object : Logger.LogImpl {
                override fun d(tag: String, msg: String) = Unit

                override fun e(tag: String, msg: String) = Unit

                override fun e(tag: String, msg: String, t: Throwable?) = Unit

                override fun i(tag: String, msg: String) = Unit
            },
        )
    }
}
