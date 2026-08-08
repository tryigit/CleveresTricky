package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class ConfigTemplateTest {
    private lateinit var originalImpl: SecureFileOperations
    private lateinit var originalExecutor: ExecutorService

    private class DirectExecutorService : AbstractExecutorService() {
        @Volatile
        private var shutdown = false

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return mutableListOf()
        }

        override fun isShutdown() = shutdown

        override fun isTerminated() = shutdown

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ) = shutdown

        override fun execute(command: Runnable) {
            check(!shutdown) { "Executor is shut down" }
            command.run()
        }
    }

    @Before
    fun setUp() {
        // Mock SecureFile
        originalImpl = SecureFile.impl
        SecureFile.impl = MockSecureFileOperations()

        val executorField = DeviceTemplateManager::class.java.getDeclaredField("executor")
        executorField.isAccessible = true
        originalExecutor = executorField.get(DeviceTemplateManager) as ExecutorService
        DeviceTemplateManager.setExecutorForTesting(DirectExecutorService())

        val tempDir = java.nio.file.Files.createTempDirectory("test_config_template").toFile()
        tempDir.deleteOnExit()

        // Initialize DeviceTemplateManager with built-ins
        DeviceTemplateManager.initialize(tempDir)

        // Force Config to reload templates from Manager
        Config.updateCustomTemplates(null)
    }

    @After
    fun tearDown() {
        SecureFile.impl = originalImpl
        // Restore executor
        DeviceTemplateManager.setExecutorForTesting(originalExecutor)
    }

    @Test
    fun testUpdateBuildVars_withTemplate() {
        // Create a temporary file
        val tempFile = File.createTempFile("spoof_build_vars", ".txt")
        tempFile.deleteOnExit()

        // Write template directive
        tempFile.writeText("TEMPLATE=pixel7pro")

        // Update Config
        Config.updateBuildVars(tempFile)

        // Verify
        assertEquals("Pixel 7 Pro", Config.getBuildVar("MODEL"))
        assertEquals("google", Config.getBuildVar("BRAND"))
        assertEquals(
            "google/cheetah/cheetah:14/AP1A.240305.019.A1/11445699:user/release-keys",
            Config.getBuildVar("FINGERPRINT"),
        )
        assertEquals("AP1A.240305.019.A1", Config.getBuildIdentity()["BUILD_ID"])
        assertEquals("pixel7pro", Config.getIdentityOverrides().template)
    }

    @Test
    fun testUpdateBuildVars_withOverride() {
        val tempFile = File.createTempFile("spoof_build_vars_override", ".txt")
        tempFile.deleteOnExit()

        // Template + Override
        tempFile.writeText("TEMPLATE=pixel7pro\nMODEL=My Custom Pixel")

        Config.updateBuildVars(tempFile)

        assertEquals("My Custom Pixel", Config.getBuildVar("MODEL"))
        assertEquals("google", Config.getBuildVar("BRAND"))
    }
}
