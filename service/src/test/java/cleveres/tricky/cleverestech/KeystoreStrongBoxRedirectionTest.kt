package cleveres.tricky.cleverestech

import android.hardware.security.keymint.ErrorCode
import android.system.keystore2.IKeystoreService
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KeystoreStrongBoxRedirectionTest {
    @Before
    fun setUp() {
        Config.reset()
    }

    @After
    fun tearDown() {
        Config.reset()
    }

    @Test
    fun `root keystore leaves StrongBox discovery platform owned`() {
        val source = sourceFile("KeystoreInterceptor.kt").readText()

        assertTrue(source.contains("getKeyEntryTransaction"))
        assertTrue(source.contains("validTransactCodes(getKeyEntryTransaction)"))
        assertFalse(source.contains("getSecurityLevelTransaction"))
        assertFalse(source.contains("requestedSecurityLevel(data) == SecurityLevel.STRONGBOX"))
        assertFalse(
            source.contains(
                "ServiceSpecificException(ErrorCode.HARDWARE_TYPE_UNAVAILABLE)",
            ),
        )
        assertFalse(source.contains("writeStrongBinder(currentTeeTarget)"))
        assertTrue(source.contains("Security-level discovery remains completely platform-owned."))
    }

    @Test
    fun `StrongBox registration rollback and teardown synchronization are preserved`() {
        val source = sourceFile("KeystoreInterceptor.kt").readText()

        assertTrue(source.contains("strongboxInterceptor != null"))
        assertTrue(source.contains("lifecycleEpoch"))
        assertTrue(source.contains("unregisterBinderInterceptor(bd, strongbox.asBinder(), interceptor)"))
    }

    @Test
    fun `stub IKeystoreService declares correct transaction and unavailable codes`() {
        val stub = IKeystoreService.Stub::class.java
        val fieldGetSecLevel = stub.getDeclaredField("TRANSACTION_getSecurityLevel")
        val fieldGetKeyEntry = stub.getDeclaredField("TRANSACTION_getKeyEntry")

        assertEquals(1, fieldGetSecLevel.getInt(null))
        assertEquals(2, fieldGetKeyEntry.getInt(null))
        assertEquals(-68, ErrorCode.HARDWARE_TYPE_UNAVAILABLE)
    }

    private fun sourceFile(name: String): File {
        val relative = "cleveres/tricky/cleverestech/$name"
        return listOf(
            File("src/main/java/$relative"),
            File("service/src/main/java/$relative"),
        ).firstOrNull(File::isFile)
            ?: error("Could not locate $name from ${File(".").absolutePath}")
    }
}
