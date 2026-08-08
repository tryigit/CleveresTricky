package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class ConfigTemplateTest {
    private lateinit var originalImpl: SecureFileOperations
    private lateinit var originalExecutor: ExecutorService

    @Before
    fun setUp() {
        // Mock SecureFile
        originalImpl = SecureFile.impl
        SecureFile.impl = MockSecureFileOperations()

        // Mock ExecutorService
        val mockExecutor = Mockito.mock(ExecutorService::class.java)
        Mockito.`when`(mockExecutor.submit(Mockito.any(Runnable::class.java))).thenAnswer { invocation ->
            (invocation.arguments[0] as Runnable).run()
            Mockito.mock(Future::class.java)
        }
        Mockito.`when`(mockExecutor.submit(Mockito.any(java.util.concurrent.Callable::class.java))).thenAnswer { invocation ->
            (invocation.arguments[0] as java.util.concurrent.Callable<*>).call()
            val f = Mockito.mock(Future::class.java)
            Mockito.`when`(f.get()).thenReturn(null)
            f
        }

        val executorField = DeviceTemplateManager::class.java.getDeclaredField("executor")
        executorField.isAccessible = true
        originalExecutor = executorField.get(DeviceTemplateManager) as ExecutorService
        DeviceTemplateManager.setExecutorForTesting(mockExecutor)

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
