package cleveres.tricky.cleverestech

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrongBoxBinderRoutingTest {
    @Test
    fun `root keystore does not intercept or remap StrongBox discovery`() {
        val source = source("KeystoreInterceptor.kt")

        assertFalse(source.contains("getSecurityLevelTransaction"))
        assertFalse(source.contains("returned == strongBoxTarget"))
        assertFalse(source.contains("writeStrongBinder(currentTeeTarget)"))
        assertFalse(source.contains("requestedSecurityLevel(data) == SecurityLevel.STRONGBOX"))
        assertFalse(source.contains("ServiceSpecificException(ErrorCode.HARDWARE_TYPE_UNAVAILABLE)"))
        assertTrue(source.contains("validTransactCodes(getKeyEntryTransaction)"))
    }

    @Test
    fun `StrongBox child binder is hooked for certificate compatibility`() {
        val source = source("KeystoreInterceptor.kt")
        val strongboxLookup = source.indexOf("ks.getSecurityLevel(SecurityLevel.STRONGBOX)")
        val strongboxRegistration = source.indexOf("strongbox.asBinder(),", strongboxLookup)

        assertTrue(strongboxLookup >= 0)
        assertTrue(strongboxRegistration > strongboxLookup)
        assertTrue(source.contains("strongboxInterceptor"))
        assertTrue(source.contains("strongboxTarget"))
    }

    @Test
    fun `StrongBox getKeyEntry does not exit before cache hashing`() {
        val source = source("KeystoreInterceptor.kt")
        val metadataRead = source.indexOf("val metadata = response?.metadata")
        val levelGate =
            source.indexOf(
                "metadata.keySecurityLevel != SecurityLevel.TRUSTED_ENVIRONMENT &&",
                metadataRead,
            )
        val cacheLookup = source.indexOf("CertHack.applyCachedCertificateChain(metadata)", levelGate)
        val chainRead = source.indexOf("val originalChain = Utils.getCertificateChain(response)", cacheLookup)
        val gateBody = source.substring(levelGate, cacheLookup)

        assertTrue(metadataRead >= 0)
        assertTrue(levelGate > metadataRead)
        assertTrue(cacheLookup > levelGate)
        assertTrue(chainRead > cacheLookup)
        assertTrue(gateBody.contains("p.recycle()"))
        assertTrue(gateBody.contains("return Skip"))
        assertTrue(gateBody.contains("metadata.keySecurityLevel != SecurityLevel.STRONGBOX"))
    }

    @Test
    fun `security level interceptor is used for both TEE and StrongBox`() {
        val keystore = source("KeystoreInterceptor.kt")
        val interceptor = source("SecurityLevelInterceptor.kt")
        val teeLookup = keystore.indexOf("ks.getSecurityLevel(SecurityLevel.TRUSTED_ENVIRONMENT)")
        val interceptorCreation = keystore.indexOf("SecurityLevelInterceptor()", teeLookup)
        val strongboxLookup = keystore.indexOf("ks.getSecurityLevel(SecurityLevel.STRONGBOX)")
        val strongboxInterceptorCreation = keystore.indexOf("SecurityLevelInterceptor()", strongboxLookup)
        
        val generateKeyGate = interceptor.indexOf("code == generateKeyTransaction")
        val backendGate = interceptor.indexOf("CertHack.canHack()", generateKeyGate)
        val policyGate = interceptor.indexOf("Config.needHack(callingUid)", backendGate)

        assertTrue(interceptorCreation > teeLookup)
        assertTrue(strongboxInterceptorCreation > strongboxLookup)
        assertTrue(generateKeyGate >= 0)
        assertTrue(backendGate > generateKeyGate)
        assertTrue(policyGate > backendGate)
        assertFalse(interceptor.contains("allowGenericReplacement"))
    }

    private fun source(name: String): String =
        File(
            locateRoot(),
            "service/src/main/java/cleveres/tricky/cleverestech/$name",
        ).readText()

    private fun locateRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(6) {
            if (File(current, "service").isDirectory && File(current, "rust").isDirectory) return current
            current = current.parentFile ?: return@repeat
        }
        error("Repository root not found")
    }
}
