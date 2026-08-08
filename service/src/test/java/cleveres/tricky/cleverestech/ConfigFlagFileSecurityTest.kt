package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConfigFlagFileSecurityTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Config.reset()
        tempDir = Files.createTempDirectory("config_flags").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `toggle flags require regular files without following symbolic links`() {
        val real = File(tempDir, "real_flag").apply { createNewFile() }
        val link = File(tempDir, "linked_flag")
        Files.createSymbolicLink(link.toPath(), real.toPath())

        invokeUpdater("updateSpoofEnabled", link)
        invokeUpdater("updateBuildIdentity", link)
        invokeUpdater("updateGlobalMode", link)
        invokeUpdater("updateTelephony", link)
        invokeUpdater("updateRkpPassthrough", link)
        assertFalse(Config.isSpoofEnabled)
        assertFalse(Config.isBuildIdentityEnabled)
        assertFalse(Config.isGlobalMode)
        assertFalse(Config.isTelephonyEnabled)
        assertFalse(Config.isRkpPassthroughEnabled)

        invokeUpdater("updateSpoofEnabled", real)
        invokeUpdater("updateBuildIdentity", real)
        invokeUpdater("updateGlobalMode", real)
        invokeUpdater("updateTelephony", real)
        invokeUpdater("updateRkpPassthrough", real)
        assertTrue(Config.isSpoofEnabled)
        assertTrue(Config.isBuildIdentityEnabled)
        assertTrue(Config.isGlobalMode)
        assertTrue(Config.isTelephonyEnabled)
        assertTrue(Config.isRkpPassthroughEnabled)
    }

    private fun invokeUpdater(
        prefix: String,
        file: File?,
    ) {
        val method = Config::class.java.declaredMethods.first { it.name.startsWith(prefix) }
        method.isAccessible = true
        method.invoke(Config, file)
    }
}
