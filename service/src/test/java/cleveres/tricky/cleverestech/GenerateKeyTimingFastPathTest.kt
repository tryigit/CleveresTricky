package cleveres.tricky.cleverestech

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateKeyTimingFastPathTest {
    @Test
    fun `generateKey keeps a shared certificate inspection preflight`() {
        val root = locateRoot()
        val source =
            File(
                root,
                "service/src/main/java/cleveres/tricky/cleverestech/SecurityLevelInterceptor.kt",
            ).readText()
        val postTransact = source.indexOf("override fun onPostTransact")
        val leafParse = source.indexOf("Utils.getLeafCertificate(metadata)", postTransact)
        val backendInspection = source.indexOf("CertHack.hackCertificateChain", postTransact)

        assertTrue(postTransact >= 0)
        assertTrue(leafParse > postTransact)
        assertTrue(backendInspection > leafParse)
        assertFalse(source.contains("hasAndroidAttestationExtension"))
    }

    @Test
    fun `getKeyEntry keeps the same bounded certificate preflight`() {
        val root = locateRoot()
        val source =
            File(
                root,
                "service/src/main/java/cleveres/tricky/cleverestech/KeystoreInterceptor.kt",
            ).readText()
        val postTransact = source.indexOf("override fun onPostTransact")
        val chainRead = source.indexOf("val originalChain = Utils.getCertificateChain(response)", postTransact)
        val backendInspection =
            source.indexOf("CertHack.hackCertificateChain(originalChain, callingUid)", chainRead)

        assertTrue(postTransact >= 0)
        assertTrue(chainRead > postTransact)
        assertTrue(backendInspection > chainRead)
        assertFalse(source.contains("hasAndroidAttestationExtension"))
    }

    @Test
    fun `local attestation classifier cannot reintroduce asymmetric hot paths`() {
        val root = locateRoot()
        val utils =
            File(
                root,
                "service/src/main/java/cleveres/tricky/cleverestech/keystore/Utils.java",
            ).readText()

        assertFalse(utils.contains("hasAndroidAttestationExtension"))
        assertFalse(utils.contains("ANDROID_ATTESTATION_EXTENSION_OID"))
    }

    @Test
    fun `timing parity is not implemented with synthetic delay`() {
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
