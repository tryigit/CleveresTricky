package cleveres.tricky.cleverestech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CertificateBackendProvenanceTest {
    @Test
    fun `StrongBox provenance is classified before issuer selection but is rewritten normally`() {
        val source =
            File(
                locateRoot(),
                "service/src/main/java/cleveres/tricky/cleverestech/keystore/CertHack.java",
            ).readText()
        val method = source.indexOf("public static Certificate[] hackCertificateChain")
        val inspect = source.indexOf("inspection = CertificateBackend.inspect(leafEncoded)", method)
        val attestationGate =
            source.indexOf("int attLevel = inspection.getAttestationSecurityLevel()", inspect)
        val keymintGate =
            source.indexOf("int kmLevel = inspection.getKeymintSecurityLevel()", attestationGate)
        val checkIsHardware = source.indexOf("boolean isTeeOrStrongbox =", keymintGate)
        val issuerSelection = source.indexOf("selectKeyboxPool(", checkIsHardware)
        val rewrite = source.indexOf("CertificateBackend.rewrite(", issuerSelection)

        assertTrue(method >= 0)
        assertTrue(inspect > method)
        assertTrue(attestationGate > inspect)
        assertTrue(keymintGate > attestationGate)
        assertTrue(checkIsHardware > keymintGate)
        assertTrue(issuerSelection > checkIsHardware)
        assertTrue(rewrite > issuerSelection)
    }

    @Test
    fun `Rust rewrite boundary independently rejects non hardware provenance before issuer access`() {
        val source =
            File(
                locateRoot(),
                "rust/backend/src/certificate_wire.rs",
            ).readText()
        val rewrite = source.indexOf("pub fn rewrite_and_encode")
        val provenance = source.indexOf("inspect_certificate(parsed.genuine_leaf_der)", rewrite)
        val teeGate =
            source.indexOf("provenance.attestation_security_level == SecurityLevel::TrustedEnvironment", provenance)
        val strongboxGate =
            source.indexOf("provenance.attestation_security_level == SecurityLevel::StrongBox", teeGate)
        val issuerAccess = source.indexOf("key_store::with_prepared_key", strongboxGate)

        assertTrue(rewrite >= 0)
        assertTrue(provenance > rewrite)
        assertTrue(teeGate > provenance)
        assertTrue(strongboxGate > teeGate)
        assertTrue(issuerAccess > strongboxGate)
    }

    @Test
    fun `passthrough cache is marker only and adds no background execution`() {
        val source =
            File(
                locateRoot(),
                "service/src/main/java/cleveres/tricky/cleverestech/keystore/CertHack.java",
            ).readText()

        assertTrue(source.contains("this.certificates = null"))
        assertTrue(source.contains("if (passthrough) return;"))
        assertTrue(source.contains("size() > MAX_CERTIFICATE_CACHE_ENTRIES"))
        assertFalse(source.contains("ScheduledExecutor"))
        assertFalse(source.contains("Timer("))
        assertFalse(source.contains("Thread.sleep"))
        assertFalse(source.contains("while (true)"))
    }

    @Test
    fun `certificate backend rewrite does not repeat managed provenance inspection`() {
        val source =
            File(
                locateRoot(),
                "service/src/main/java/cleveres/tricky/cleverestech/CertificateBackend.kt",
            ).readText()
        val rewrite = source.indexOf("fun rewrite(")
        val decode = source.indexOf("internal fun decodeInspection", rewrite)
        val body = source.substring(rewrite, decode)

        assertFalse(body.contains("inspect(genuineLeafDer)"))
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
