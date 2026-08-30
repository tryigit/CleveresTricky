package cleveres.tricky.cleverestech

import android.system.keystore2.IKeystoreService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

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
    fun `keystore interceptor declares getSecurityLevel and getKeyEntry codes`() {
        val source = sourceFile("KeystoreInterceptor.kt").readText()

        assertTrue(source.contains("getSecurityLevelTransaction"))
        assertTrue(source.contains("getKeyEntryTransaction"))
        assertTrue(source.contains("validTransactCodes"))
        assertTrue(source.contains("getSecurityLevelTransaction, getKeyEntryTransaction"))
    }

    @Test
    fun `stub IKeystoreService declares correct transaction codes`() {
        val stub = IKeystoreService.Stub::class.java
        val fieldGetSecLevel = stub.getDeclaredField("TRANSACTION_getSecurityLevel")
        val fieldGetKeyEntry = stub.getDeclaredField("TRANSACTION_getKeyEntry")

        assertEquals(1, fieldGetSecLevel.getInt(null))
        assertEquals(2, fieldGetKeyEntry.getInt(null))
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
