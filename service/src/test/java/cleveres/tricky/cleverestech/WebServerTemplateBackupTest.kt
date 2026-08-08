package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class WebServerTemplateBackupTest {
    private lateinit var testDir: File
    private lateinit var configDir: File
    private lateinit var originalSecureFileImpl: SecureFileOperations

    @Before
    fun setUp() {
        testDir = Files.createTempDirectory("cleverestricky_test").toFile()
        configDir = File(testDir, "config")
        configDir.mkdirs()

        // Save original implementation
        originalSecureFileImpl = SecureFile.impl

        // Mock SecureFile to use standard IO
        SecureFile.impl =
            object : SecureFileOperations {
                override fun writeText(
                    file: File,
                    content: String,
                ) {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                }

                override fun writeStream(
                    file: File,
                    inputStream: java.io.InputStream,
                    limit: Long,
                ) {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        var totalBytes = 0L
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            if (limit > 0 && totalBytes + bytesRead > limit) {
                                throw java.io.IOException("File size exceeds limit of $limit bytes")
                            }
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                        }
                    }
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
    }

    @After
    fun tearDown() {
        // Restore original implementation
        SecureFile.impl = originalSecureFileImpl
        testDir.deleteRecursively()
    }

    @Test
    fun testTemplatesJsonBackup() {
        // Setup: Create templates.json
        val templatesJson =
            """
            [{
              "id":"custom",
              "manufacturer":"Example",
              "model":"Custom Model",
              "fingerprint":"example/custom/custom:14/BUILD/1:user/release-keys",
              "brand":"example",
              "product":"custom",
              "device":"custom",
              "release":"14",
              "buildId":"BUILD",
              "incremental":"1",
              "securityPatch":"2024-01-01"
            }]
            """.trimIndent()
        File(configDir, "templates.json").writeText(templatesJson)

        val customTemplates =
            """
            [custom]
            MANUFACTURER=Example
            MODEL=Example Model
            """.trimIndent()
        File(configDir, "custom_templates").writeText(customTemplates)

        // Create Backup
        val zipBytes = WebServer.createBackupZip(configDir)
        assertTrue("Zip should not be empty", zipBytes.isNotEmpty())

        // Clear config dir
        configDir.deleteRecursively()
        configDir.mkdirs()

        // Restore
        WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zipBytes))

        // Verify: templates.json should exist
        val restoredTemplates = File(configDir, "templates.json")
        assertTrue("templates.json should be restored", restoredTemplates.exists())
        assertEquals(templatesJson, restoredTemplates.readText())

        val restoredCustomTemplates = File(configDir, "custom_templates")
        assertTrue("custom_templates should be restored", restoredCustomTemplates.exists())
        assertEquals(customTemplates, restoredCustomTemplates.readText())
    }

    @Test
    fun testInvalidCustomTemplatesBackupIsRejected() {
        File(configDir, "custom_templates").writeText("property before a section")
        val zipBytes = WebServer.createBackupZip(configDir)

        configDir.deleteRecursively()
        configDir.mkdirs()

        assertThrows(IOException::class.java) {
            WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zipBytes))
        }
    }
}
