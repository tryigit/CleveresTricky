package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ConfigInitializationLogicTest {
    private lateinit var tempDir: File
    private var originalRoot: Any? = null
    private lateinit var originalSecureFileImpl: SecureFileOperations
    private lateinit var originalLoggerImpl: Logger.LogImpl
    private var setupDone = false

    @Before
    fun setup() {
        // Create temp dir
        tempDir = File(System.getProperty("java.io.tmpdir"), "cleveres_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        // Mock Logger
        originalLoggerImpl = getLoggerImpl()
        Logger.setImpl(
            object : Logger.LogImpl {
                override fun d(
                    tag: String,
                    msg: String,
                ) {}

                override fun e(
                    tag: String,
                    msg: String,
                ) {
                    println("E/$tag: $msg")
                }

                override fun e(
                    tag: String,
                    msg: String,
                    t: Throwable?,
                ) {
                    println("E/$tag: $msg")
                }

                override fun i(
                    tag: String,
                    msg: String,
                ) {}
            },
        )

        // Mock SecureFile
        originalSecureFileImpl = SecureFile.impl
        SecureFile.impl =
            object : SecureFileOperations {
                override fun writeText(
                    file: File,
                    content: String,
                ) {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                }

                override fun mkdirs(
                    file: File,
                    mode: Int,
                ) {
                    file.mkdirs()
                }

                override fun touch(
                    file: File,
                    mode: Int,
                ) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                }
            }

        // Set Config.root via reflection
        try {
            val rootField = Config::class.java.getDeclaredField("root")
            rootField.isAccessible = true

            // Get original value
            originalRoot = rootField.get(Config)

            // Set new value
            rootField.set(Config, tempDir)
            setupDone = true
        } catch (e: Exception) {
            println("Reflection failed: $e")
            e.printStackTrace()
            throw e
        }

        // Initialize DeviceTemplateManager
        DeviceTemplateManager.initialize(tempDir)
    }

    @After
    fun tearDown() {
        if (setupDone) {
            // Restore Config.root
            try {
                val rootField = Config::class.java.getDeclaredField("root")
                rootField.isAccessible = true
                rootField.set(Config, originalRoot)
            } catch (e: Exception) {
                println("Failed to restore root: $e")
            }
        }

        // Restore SecureFile
        SecureFile.impl = originalSecureFileImpl

        // Restore Logger
        Logger.setImpl(originalLoggerImpl)

        // Cleanup temp dir
        tempDir.deleteRecursively()
    }

    private fun getLoggerImpl(): Logger.LogImpl {
        val field = Logger::class.java.getDeclaredField("impl")
        field.isAccessible = true
        return field.get(null) as Logger.LogImpl
    }

    @Test
    fun testRandomizeOnBootBug() {
        val randomOnBootFile = File(tempDir, "random_on_boot")
        randomOnBootFile.createNewFile()

        val spoofFile = File(tempDir, "spoof_build_vars")
        spoofFile.writeText("ATTESTATION_ID_IMEI=490154203237518\n")

        try {
            callUpdateBuildVars(spoofFile)
        } catch (e: NoSuchMethodException) {
            println("Methods available in Config:")
            Config::class.java.declaredMethods.forEach { println(it.name) }
            throw e
        }

        assertEquals("490154203237518", Config.getBuildVar("ATTESTATION_ID_IMEI"))

        callEnforceRandomization()

        // Mirror the fix: Call updateBuildVars again to load the new values
        try {
            callUpdateBuildVars(spoofFile)
        } catch (e: NoSuchMethodException) {
            throw e
        }

        val fileContent = spoofFile.readText()
        assertNotEquals("File should have been randomized", "ATTESTATION_ID_IMEI=490154203237518\n", fileContent)
        assertTrue("File should contain ATTESTATION_ID_IMEI", fileContent.contains("ATTESTATION_ID_IMEI="))

        // Assert Fix: Config should have NEW value (randomized)
        assertNotEquals("Config should have NEW value", "490154203237518", Config.getBuildVar("ATTESTATION_ID_IMEI"))

        // Extract IMEI from file content to verify exact match
        val newImei = fileContent.lines().find { it.startsWith("ATTESTATION_ID_IMEI=") }?.split("=")?.get(1)?.trim()
        assertEquals("Config should match file content", newImei, Config.getBuildVar("ATTESTATION_ID_IMEI"))
    }

    private fun callUpdateBuildVars(file: File) {
        // Try finding method starting with updateBuildVars
        val methods = Config::class.java.declaredMethods
        val method =
            methods.find { it.name.startsWith("updateBuildVars") }
                ?: throw NoSuchMethodException("updateBuildVars not found")

        method.isAccessible = true
        method.invoke(Config, file)
    }

    private fun callEnforceRandomization() {
        val method = Config::class.java.getDeclaredMethod("enforceRandomization")
        method.isAccessible = true
        method.invoke(Config)
    }
}
