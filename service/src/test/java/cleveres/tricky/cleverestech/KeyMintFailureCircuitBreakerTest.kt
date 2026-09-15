package cleveres.tricky.cleverestech

import android.hardware.security.keymint.ErrorCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KeyMintFailureCircuitBreakerTest {

    private class FakeServiceSpecificException(@JvmField val errorCode: Int, message: String? = null) :
        Exception(message ?: "ServiceSpecificException($errorCode)")

    private var origErr: PrintStream? = null
    private var origOut: PrintStream? = null
    private val errCapture = ByteArrayOutputStream()
    private val outCapture = ByteArrayOutputStream()

    @Before
    fun setUp() {
        origErr = System.err
        origOut = System.out
        errCapture.reset()
        outCapture.reset()
        System.setErr(PrintStream(errCapture, true))
        System.setOut(PrintStream(outCapture, true))
        KeystoreInterceptor.resetTeeCircuitBreaker()
    }

    @After
    fun tearDown() {
        KeystoreInterceptor.resetTeeCircuitBreaker()
        origErr?.let { System.setErr(it) }
        origOut?.let { System.setOut(it) }
    }

    @Test
    fun `isSecureHwCommunicationFailure identifies KeyMint error code -49`() {
        val ex = FakeServiceSpecificException(ErrorCode.SECURE_HW_COMMUNICATION_FAILED)
        assertTrue(KeystoreInterceptor.isSecureHwCommunicationFailure(ex))
    }

    @Test
    fun `isSecureHwCommunicationFailure identifies public error code 10`() {
        val ex = FakeServiceSpecificException(KeystoreInterceptor.ERROR_SECURE_HW_COMMUNICATION_FAILED)
        assertTrue(KeystoreInterceptor.isSecureHwCommunicationFailure(ex))
    }

    @Test
    fun `isSecureHwCommunicationFailure identifies exception message containing SECURE_HW_COMMUNICATION_FAILED`() {
        val ex = RuntimeException("Transaction failed: SECURE_HW_COMMUNICATION_FAILED at HAL")
        assertTrue(KeystoreInterceptor.isSecureHwCommunicationFailure(ex))
    }

    @Test
    fun `isSecureHwCommunicationFailure identifies exception message containing -49`() {
        val ex = RuntimeException("keystore2 returned error -49")
        assertTrue(KeystoreInterceptor.isSecureHwCommunicationFailure(ex))
    }

    @Test
    fun `isSecureHwCommunicationFailure identifies cause wrapped in another exception`() {
        val root = FakeServiceSpecificException(-49)
        val wrapped = IllegalStateException("Wrapper failure", root)
        assertTrue(KeystoreInterceptor.isSecureHwCommunicationFailure(wrapped))
    }

    @Test
    fun `isSecureHwCommunicationFailure rejects unrelated errors`() {
        val hwUnavailable = FakeServiceSpecificException(ErrorCode.HARDWARE_TYPE_UNAVAILABLE)
        assertFalse(KeystoreInterceptor.isSecureHwCommunicationFailure(hwUnavailable))

        val ioException = java.io.IOException("Connection reset by peer")
        assertFalse(KeystoreInterceptor.isSecureHwCommunicationFailure(ioException))

        assertFalse(KeystoreInterceptor.isSecureHwCommunicationFailure(null))
    }

    @Test
    fun `tripTeeCircuitBreaker activates fail-closed circuit breaker and reset clears it`() {
        assertFalse(KeystoreInterceptor.isTeeBroken())

        KeystoreInterceptor.tripTeeCircuitBreaker()
        assertTrue(KeystoreInterceptor.isTeeBroken())

        // When circuit breaker is tripped, tryRunKeystoreInterceptor must immediately return false
        assertFalse(KeystoreInterceptor.tryRunKeystoreInterceptor())

        KeystoreInterceptor.resetTeeCircuitBreaker()
        assertFalse(KeystoreInterceptor.isTeeBroken())
    }

    @Test
    fun `logSecureHwCommunicationFailure emits exact required warning and info lines`() {
        KeystoreInterceptor.logSecureHwCommunicationFailure()

        val errString = errCapture.toString()
        val outString = outCapture.toString()

        assertTrue(
            "stderr must contain the exact WARN line",
            errString.contains(KeystoreInterceptor.WARN_KEYMINT_TEE_BROKEN),
        )
        assertEquals(
            "[WARN] Platform KeyMint HAL unreachable or TEE broken (SECURE_HW_COMMUNICATION_FAILED).",
            KeystoreInterceptor.WARN_KEYMINT_TEE_BROKEN,
        )
        assertTrue(
            "stdout must contain the exact INFO line",
            outString.contains(KeystoreInterceptor.INFO_KEYMINT_ABORT_INJECTION),
        )
        assertEquals(
            "[INFO] CleveresTricky requires a functional hardware KeyMint; aborting injection to prevent framework deadlock.",
            KeystoreInterceptor.INFO_KEYMINT_ABORT_INJECTION,
        )
        assertTrue(
            "stdout must contain the documentation guidance line",
            outString.contains(KeystoreInterceptor.INFO_KEYMINT_SEE_DOCS),
        )
        assertEquals(
            "[INFO] Please consult documentation (docs/security/Attestation.md or docs/LOG.md) for TEE recovery guidance.",
            KeystoreInterceptor.INFO_KEYMINT_SEE_DOCS,
        )
    }

    @Test
    fun `source contract verifies KeyMint precheck and SecurityLevelInterceptor integration`() {
        val root = locateRoot()
        val keystoreSource = File(root, "service/src/main/java/cleveres/tricky/cleverestech/KeystoreInterceptor.kt").readText()
        val secLevelSource = File(root, "service/src/main/java/cleveres/tricky/cleverestech/SecurityLevelInterceptor.kt").readText()

        assertTrue(keystoreSource.contains("validateKeyMintHardware(ksPrecheck)"))
        assertTrue(keystoreSource.contains("isSecureHwCommunicationFailure(e)"))
        assertTrue(keystoreSource.contains("tripTeeCircuitBreaker()"))
        assertTrue(secLevelSource.contains("KeystoreInterceptor.isSecureHwCommunicationFailure(e)"))
        assertTrue(secLevelSource.contains("KeystoreInterceptor.tripTeeCircuitBreaker()"))
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
