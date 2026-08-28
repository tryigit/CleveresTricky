package cleveres.tricky.cleverestech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BootLogicConflictTest {
    @Test
    fun `auto pif module IDs match the early boot conflict policy`() {
        assertTrue(BootLogic.isBuildIdentityProviderModuleId("auto_pif"))
        assertTrue(BootLogic.isBuildIdentityProviderModuleId("Auto_PIF_next"))
        assertTrue(BootLogic.isBuildIdentityProviderModuleId("autopif"))
        assertFalse(BootLogic.isBuildIdentityProviderModuleId("unrelated_module"))
    }

    @Test
    fun `conflict scan is bounded and fails closed when the module directory is oversized`() {
        val root = Files.createTempDirectory("boot-logic-conflict").toFile()
        try {
            repeat(4_097) { index ->
                Files.createDirectory(root.toPath().resolve("unrelated_$index"))
            }
            assertTrue(BootLogic.hasConflictingBuildIdentityModule(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `conflict scan recognizes enabled providers but ignores disabled providers`() {
        val root = Files.createTempDirectory("boot-logic-provider").toFile()
        try {
            val provider = File(root, "autopif")
            assertTrue(provider.mkdirs())
            assertTrue(BootLogic.hasConflictingBuildIdentityModule(root))
            assertTrue(File(provider, "disable").createNewFile())
            assertFalse(BootLogic.hasConflictingBuildIdentityModule(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
