package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.Utils
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateKeyTimingFastPathTest {
    @Test
    fun `ordinary x509 leaf has no Android attestation extension`() {
        val certificate =
            CertificateFactory
                .getInstance("X.509")
                .generateCertificate(
                    ByteArrayInputStream(TestKeyboxFixtures.certificate.toByteArray(Charsets.US_ASCII)),
                )

        assertFalse(Utils.hasAndroidAttestationExtension(certificate))
    }

    @Test
    fun `generateKey rejects non-attested leaf before Rust certificate backend`() {
        val root = locateRoot()
        val source =
            File(
                root,
                "service/src/main/java/cleveres/tricky/cleverestech/SecurityLevelInterceptor.kt",
            ).readText()
        val postTransact = source.indexOf("override fun onPostTransact")
        val localExtensionGuard =
            source.indexOf("!Utils.hasAndroidAttestationExtension(originalLeaf)", postTransact)
        val backendRewrite = source.indexOf("CertHack.hackCertificateChain", postTransact)

        assertTrue(postTransact >= 0)
        assertTrue(localExtensionGuard > postTransact)
        assertTrue(backendRewrite > localExtensionGuard)
    }

    @Test
    fun `fresh attested generateKey performs exactly one dual provenance inspection before issuer selection`() {
        val root = locateRoot()
        val source =
            File(
                root,
                "service/src/main/java/cleveres/tricky/cleverestech/keystore/CertHack.java",
            ).readText()
        val method = source.indexOf("public static Certificate[] hackCertificateChain")
        val localExtensionGuard =
            source.indexOf("!Utils.hasAndroidAttestationExtension(caList[0])", method)
        val backendInspect =
            source.indexOf("inspection = CertificateBackend.inspect(leafEncoded)", localExtensionGuard)
        val nextBackendInspect =
            source.indexOf("inspection = CertificateBackend.inspect(leafEncoded)", backendInspect + 1)
        val attestationGate =
            source.indexOf("inspection.getAttestationSecurityLevel()", backendInspect)
        val keymintGate =
            source.indexOf("inspection.getKeymintSecurityLevel()", attestationGate)
        val issuerSelection = source.indexOf("selectKeyboxPool(", keymintGate)
        val backendRewrite = source.indexOf("byte[] rewrittenDer = CertificateBackend.rewrite", issuerSelection)

        assertTrue(method >= 0)
        assertTrue(localExtensionGuard > method)
        assertTrue(backendInspect > localExtensionGuard)
        assertTrue(nextBackendInspect < 0)
        assertTrue(attestationGate > backendInspect)
        assertTrue(keymintGate > attestationGate)
        assertTrue(issuerSelection > keymintGate)
        assertTrue(backendRewrite > issuerSelection)
    }

    @Test
    fun `measured getKeyEntry serves encoded TEE cache before X509 chain parsing`() {
        val root = locateRoot()
        val source =
            File(
                root,
                "service/src/main/java/cleveres/tricky/cleverestech/KeystoreInterceptor.kt",
            ).readText()
        val postTransact = source.indexOf("override fun onPostTransact")
        val responseRead = source.indexOf("val response = reply.readTypedObject", postTransact)
        val metadataRead = source.indexOf("val metadata = response?.metadata", responseRead)
        val levelGate =
            source.indexOf(
                "metadata.keySecurityLevel != SecurityLevel.TRUSTED_ENVIRONMENT",
                metadataRead,
            )
        val encodedCache =
            source.indexOf("CertHack.applyCachedCertificateChain(metadata)", levelGate)
        val chainRead =
            source.indexOf("val originalChain = Utils.getCertificateChain(response)", encodedCache)

        assertTrue(postTransact >= 0)
        assertTrue(responseRead > postTransact)
        assertTrue(metadataRead > responseRead)
        assertTrue(levelGate > metadataRead)
        assertTrue(encodedCache > levelGate)
        assertTrue(chainRead > encodedCache)
    }

    @Test
    fun `cached getKeyEntry applies raw replacement bytes without certificate reencoding`() {
        val root = locateRoot()
        val source =
            File(
                root,
                "service/src/main/java/cleveres/tricky/cleverestech/keystore/CertHack.java",
            ).readText()
        val method = source.indexOf("public static boolean applyCachedCertificateChain")
        val methodEnd = source.indexOf("public static Certificate[] getCachedCertificateChain", method)
        val body = source.substring(method, methodEnd)

        assertTrue(method >= 0)
        assertTrue(methodEnd > method)
        assertTrue(body.contains("new CacheKey(metadata.certificate)"))
        assertTrue(body.contains("cached.applyTo(metadata)"))
        assertFalse(body.contains("CERTIFICATE_FACTORY"))
        assertFalse(body.contains("getEncoded()"))
        assertFalse(body.contains("CertificateBackend"))
    }

    @Test
    fun `uncached non-attested getKeyEntry leaf is rejected locally after cache lookup`() {
        val root = locateRoot()
        val source =
            File(
                root,
                "service/src/main/java/cleveres/tricky/cleverestech/keystore/CertHack.java",
            ).readText()
        val method = source.indexOf("public static Certificate[] hackCertificateChain")
        val cacheLookup = source.indexOf("CachedCertificateChain cached = cache.get(cacheKey)", method)
        val localExtensionGuard =
            source.indexOf("!Utils.hasAndroidAttestationExtension(caList[0])", cacheLookup)
        val backendInspect =
            source.indexOf("inspection = CertificateBackend.inspect(leafEncoded)", localExtensionGuard)
        val backendRewrite =
            source.indexOf("byte[] rewrittenDer = CertificateBackend.rewrite", localExtensionGuard)

        assertTrue(method >= 0)
        assertTrue(cacheLookup > method)
        assertTrue(localExtensionGuard > cacheLookup)
        assertTrue(backendInspect > localExtensionGuard)
        assertTrue(backendRewrite > backendInspect)
    }

    @Test
    fun `completed rewrite cache retains encoded leaf and issuer bytes`() {
        val root = locateRoot()
        val source =
            File(
                root,
                "service/src/main/java/cleveres/tricky/cleverestech/keystore/CertHack.java",
            ).readText()
        val rewrite = source.indexOf("byte[] rewrittenDer = CertificateBackend.rewrite")
        val issuerEncoding = source.indexOf("byte[] issuerChainEncoded = Utils.encodeIssuerChain(result)", rewrite)
        val completed =
            source.indexOf("new CachedCertificateChain(result, rewrittenDer, issuerChainEncoded", issuerEncoding)
        val cachePut = source.indexOf("cache.put(cacheKey, completed)", completed)

        assertTrue(rewrite >= 0)
        assertTrue(issuerEncoding > rewrite)
        assertTrue(completed > issuerEncoding)
        assertTrue(cachePut > completed)
    }

    @Test
    fun `timing fix cannot use synthetic delay equalization`() {
        val root = locateRoot()
        val sources =
            listOf(
                File(
                    root,
                    "service/src/main/java/cleveres/tricky/cleverestech/SecurityLevelInterceptor.kt",
                ).readText(),
                File(
                    root,
                    "service/src/main/java/cleveres/tricky/cleverestech/KeystoreInterceptor.kt",
                ).readText(),
                File(
                    root,
                    "service/src/main/java/cleveres/tricky/cleverestech/keystore/CertHack.java",
                ).readText(),
            ).joinToString("\n")

        assertFalse(sources.contains("Thread.sleep"))
        assertFalse(sources.contains("parkNanos"))
        assertFalse(sources.contains("busyWait"))
    }

    private fun locateRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(6) {
            if (File(current, "service").isDirectory && File(current, "rust").isDirectory) return current
            current = current.parentFile ?: return@repeat
        }
        error("Repository root not found")
    }
}
