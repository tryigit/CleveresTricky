package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.KeyboxLoader
import cleveres.tricky.cleverestech.RustBackendUnavailableException
import cleveres.tricky.cleverestech.keystore.CertHack
import java.io.IOException
import java.nio.file.Files
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import org.mockito.Mockito
import java.text.SimpleDateFormat
import java.util.TimeZone

class KeyboxVerifierTest {
    @Test
    fun `earliest certificate expiry is used instead of certificate #3 only`() {
        val formatter = SimpleDateFormat("yyyy-MM-dd").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val later = formatter.parse("2029-02-08")
        val earlier = formatter.parse("2027-01-15")

        val first = Mockito.mock(X509Certificate::class.java)
        Mockito.`when`(first.notAfter).thenReturn(later)
        val second = Mockito.mock(X509Certificate::class.java)
        Mockito.`when`(second.notAfter).thenReturn(earlier)

        val keybox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(keybox.certificates()).thenReturn(listOf(first, second))

        assertEquals("2027-01-15", KeyboxVerifier.getEarliestCertificateNotAfter(listOf(keybox)))
    }
    @Test
    fun `clearMemoryCacheForTesting clears cache values`() {
        val etagField = KeyboxVerifier::class.java.getDeclaredField("cachedEtag")
        etagField.isAccessible = true
        etagField.set(KeyboxVerifier, "some-etag")

        val timeField = KeyboxVerifier::class.java.getDeclaredField("lastFetchTime")
        timeField.isAccessible = true
        timeField.set(KeyboxVerifier, 12345L)

        KeyboxVerifier.clearMemoryCacheForTesting()

        assertEquals(null, etagField.get(KeyboxVerifier))
        assertEquals(0L, timeField.get(KeyboxVerifier))
    }
    @Test
    fun `verifyKeybox returns VALID for unrevoked certificate`() {
        val mockCert = Mockito.mock(X509Certificate::class.java)
        Mockito.`when`(mockCert.serialNumber).thenReturn(java.math.BigInteger("123456"))
        val mockPublicKey = Mockito.mock(java.security.PublicKey::class.java)
        Mockito.`when`(mockPublicKey.encoded).thenReturn(ByteArray(0))
        Mockito.`when`(mockCert.publicKey).thenReturn(mockPublicKey)

        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))

        val result = KeyboxVerifier.verifyKeybox(mockKeyBox, setOf("deadbeef", "cafebabe"))

        assertEquals(KeyboxVerifier.Status.VALID, result)
    }

    @Test
    fun `verifyKeybox returns REVOKED for revoked serial`() {
        val mockCert = Mockito.mock(X509Certificate::class.java)
        val revokedSerial = "deadbeef"
        Mockito.`when`(mockCert.serialNumber).thenReturn(java.math.BigInteger(revokedSerial, 16))

        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))

        val result = KeyboxVerifier.verifyKeybox(mockKeyBox, setOf(revokedSerial, "cafebabe"))

        assertEquals(KeyboxVerifier.Status.REVOKED, result)
    }

    @Test
    fun `verifyKeybox returns INVALID for empty chain`() {
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(emptyList())

        val result = KeyboxVerifier.verifyKeybox(mockKeyBox, emptySet())

        assertEquals(KeyboxVerifier.Status.INVALID, result)
    }

    @Test
    fun `parseCrl rejects oversized entry keys`() {
        val key = "1".repeat(129)
        assertThrows(IOException::class.java) {
            KeyboxVerifier.parseCrl("""{"entries":{"$key":"REVOKED"}}""")
        }
    }

    @Test
    fun `verify parses keybox files through Rust loader boundary`() {
        val configDir = Files.createTempDirectory("keybox-verifier-rust-path").toFile()
        val file = configDir.resolve("keybox.xml")
        file.writeText("not legacy XML")
        val mockCert = Mockito.mock(X509Certificate::class.java)
        Mockito.`when`(mockCert.serialNumber).thenReturn(java.math.BigInteger.ONE)
        val publicKey = Mockito.mock(java.security.PublicKey::class.java)
        Mockito.`when`(publicKey.encoded).thenReturn(byteArrayOf(1, 2, 3))
        Mockito.`when`(mockCert.publicKey).thenReturn(publicKey)
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))
        var observedScope: KeyboxLoader.FileScope? = null
        var observedFilename: String? = null
        KeyboxLoader.fileParserOverride = { scope, filename ->
            observedScope = scope
            observedFilename = filename
            KeyboxLoader.ParsedFile(
                snapshotSha256 = "00".repeat(32),
                keyboxes = listOf(mockKeyBox),
            )
        }

        try {
            val result = KeyboxVerifier.verify(configDir) { emptySet() }.single()

            assertEquals(KeyboxVerifier.Status.VALID, result.status)
            assertEquals("00".repeat(32), result.snapshotSha256)
            assertEquals(KeyboxLoader.FileScope.CONFIG_ROOT, observedScope)
            assertEquals("keybox.xml", observedFilename)
        } finally {
            KeyboxLoader.resetForTesting()
            configDir.deleteRecursively()
        }
    }

    @Test
    fun `manual verification retries one transient Rust backend outage`() {
        val configDir = Files.createTempDirectory("keybox-verifier-retry").toFile()
        configDir.resolve("keybox.xml").writeText("backend-owned input")
        val mockCert = Mockito.mock(X509Certificate::class.java)
        Mockito.`when`(mockCert.serialNumber).thenReturn(java.math.BigInteger.ONE)
        val publicKey = Mockito.mock(java.security.PublicKey::class.java)
        Mockito.`when`(publicKey.encoded).thenReturn(byteArrayOf(1, 2, 3))
        Mockito.`when`(mockCert.publicKey).thenReturn(publicKey)
        val mockKeyBox = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(mockKeyBox.certificates()).thenReturn(listOf(mockCert))
        var attempts = 0
        KeyboxLoader.fileParserOverride = { _, _ ->
            attempts++
            if (attempts == 1) throw RustBackendUnavailableException(IOException("backend restart"))
            KeyboxLoader.ParsedFile(
                snapshotSha256 = "11".repeat(32),
                keyboxes = listOf(mockKeyBox),
            )
        }

        try {
            val result = KeyboxVerifier.verifyWithRetryForTesting(configDir) { emptySet() }.single()

            assertEquals(2, attempts)
            assertEquals(KeyboxVerifier.Status.VALID, result.status)
            assertEquals("11".repeat(32), result.snapshotSha256)
        } finally {
            KeyboxLoader.resetForTesting()
            configDir.deleteRecursively()
        }
    }


    @Test
    fun `resetCrlUrlForTesting resets URL and clears cache`() {
        val crlUrlField = KeyboxVerifier::class.java.getDeclaredField("crlUrl")
        crlUrlField.isAccessible = true

        val cachedCrlField = KeyboxVerifier::class.java.getDeclaredField("cachedCrl")
        cachedCrlField.isAccessible = true

        val cachedEtagField = KeyboxVerifier::class.java.getDeclaredField("cachedEtag")
        cachedEtagField.isAccessible = true

        val lastFetchTimeField = KeyboxVerifier::class.java.getDeclaredField("lastFetchTime")
        lastFetchTimeField.isAccessible = true

        try {
            KeyboxVerifier.setCrlUrlForTesting("http://127.0.0.1:1234")

            val dummyHandle = cleveres.tricky.cleverestech.CrlWire.Handle(1L, 1, 1)
            cachedCrlField.set(KeyboxVerifier, dummyHandle)
            cachedEtagField.set(KeyboxVerifier, "dummy-etag")
            lastFetchTimeField.set(KeyboxVerifier, 123456789L)

            KeyboxVerifier.resetCrlUrlForTesting()

            assertEquals(KeyboxVerifier::class.java.getDeclaredField("DEFAULT_CRL_URL").apply { isAccessible = true }.get(null) as String, crlUrlField.get(KeyboxVerifier))
            assertEquals(null, cachedCrlField.get(KeyboxVerifier))
            assertEquals(null, cachedEtagField.get(KeyboxVerifier))
            assertEquals(0L, lastFetchTimeField.get(KeyboxVerifier))
        } finally {
            KeyboxVerifier.resetCrlUrlForTesting()
        }
    }

    @Test
    fun `verify rejects keybox directories above the file limit`() {
        val configDir = Files.createTempDirectory("keybox-verifier-limit").toFile()
        try {
            val keyboxDir = configDir.resolve("keyboxes").apply { mkdirs() }
            repeat(65) { index -> keyboxDir.resolve("keybox-$index.xml").writeText("x") }

            val results = KeyboxVerifier.verify(configDir) { emptySet() }

            assertEquals(1, results.size)
            assertEquals(KeyboxVerifier.Status.ERROR, results.single().status)
            assertEquals("Too many keybox XML files", results.single().details)
        } finally {
            configDir.deleteRecursively()
        }
    }

    @Test
    fun `configureCacheRoot sets cacheRoot directory`() {
        val newCacheDir = File("/test/cache/dir")
        KeyboxVerifier.configureCacheRoot(newCacheDir)

        val cacheRootField = KeyboxVerifier::class.java.getDeclaredField("cacheRoot")
        cacheRootField.isAccessible = true
        val actualCacheRoot = cacheRootField.get(KeyboxVerifier) as File

        assertEquals(newCacheDir, actualCacheRoot)

        KeyboxVerifier.resetCacheRootForTesting()
    }
}