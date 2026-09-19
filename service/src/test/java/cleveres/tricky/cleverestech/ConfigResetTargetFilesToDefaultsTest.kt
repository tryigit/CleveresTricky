package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConfigResetTargetFilesToDefaultsTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        Config.reset()
        root = Files.createTempDirectory("config-reset-defaults-test").toFile()
        Config.setRootForTesting(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `resetTargetFilesToDefaults restores target and template files to default contents`() {
        val targetFile = File(root, "target.txt")
        val identityTargetFile = File(root, "identity_target.txt")
        val securityPatchFile = File(root, "security_patch.txt")
        val bootPropsModeFile = File(root, "boot_props_mode")
        val drmPackagesFile = File(root, "drm_packages.txt")

        // Write modified/custom contents
        targetFile.writeText("custom.package.one\ncustom.package.two\n")
        identityTargetFile.writeText("custom.identity.pkg\n")
        securityPatchFile.writeText("2026-01-01\n")
        bootPropsModeFile.writeText("manual\n")
        drmPackagesFile.writeText("com.custom.drm\n")

        // Reset to defaults
        Config.resetTargetFilesToDefaults()

        assertEquals(Config.DEFAULT_TARGET_CONTENT, targetFile.readText())
        assertEquals(Config.DEFAULT_TARGET_CONTENT, identityTargetFile.readText())
        assertEquals("", securityPatchFile.readText())
        assertEquals("auto\n", bootPropsModeFile.readText())
        assertEquals(Config.DEFAULT_DRM_PACKAGES_CONTENT, drmPackagesFile.readText())

        // Verify that target and identity packages match expected defaults via uid checks
        Config.setPackagesForTesting(10_001, arrayOf("com.android.vending"))
        Config.setPackagesForTesting(10_002, arrayOf("com.unrelated.app"))
        assertTrue(Config.needHack(10_001))
        org.junit.Assert.assertFalse(Config.needHack(10_002))
    }

    @Test
    fun `applyProfile default restores target and template files to default contents`() {
        val targetFile = File(root, "target.txt")
        val identityTargetFile = File(root, "identity_target.txt")
        targetFile.writeText("custom.app.bank\n")
        identityTargetFile.writeText("custom.app.bank\n")

        Config.applyProfile("default")

        assertEquals(Config.DEFAULT_TARGET_CONTENT, targetFile.readText())
        assertEquals(Config.DEFAULT_TARGET_CONTENT, identityTargetFile.readText())
        assertTrue(Config.isGlobalMode)
    }

    @Test
    fun `applyProfile default removes configured remote servers and their caches`() {
        val serversFile = File(root, ServerManager.SERVERS_FILE_NAME)
        val cacheFile = File(root, "server_cache_srv1.enc")
        val unrelatedFile = File(root, "server_cache_notes.txt")
        serversFile.writeText("[]")
        cacheFile.writeBytes(ByteArray(32) { 1 })
        unrelatedFile.writeText("keep")

        Config.applyProfile("default")

        org.junit.Assert.assertFalse("default profile must remove the server configuration", serversFile.exists())
        org.junit.Assert.assertFalse("default profile must remove server caches", cacheFile.exists())
        assertTrue("unrelated files must survive the reset", unrelatedFile.exists())
    }

    @Test
    fun `non-default profiles preserve configured remote servers`() {
        val serversFile = File(root, ServerManager.SERVERS_FILE_NAME)
        serversFile.writeText("[]")

        Config.applyProfile("minimal")

        assertTrue("only the default profile clears remote servers", serversFile.exists())
    }
}
