package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.RestoreFileOperations
import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WebServerZipBombTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var configDir: File
    private lateinit var originalImpl: SecureFileOperations
    private var writeBytesCalled = false

    @Before
    fun setUp() {
        configDir = tempFolder.newFolder("config")
        originalImpl = SecureFile.impl
        SecureFile.impl =
            object : SecureFileOperations, RestoreFileOperations {
                override fun writeText(
                    file: File,
                    content: String,
                ) = Unit

                override fun writeBytes(
                    file: File,
                    content: ByteArray,
                ) {
                    writeBytesCalled = true
                }

                override fun mkdirs(
                    file: File,
                    mode: Int,
                ) = Unit

                override fun touch(
                    file: File,
                    mode: Int,
                ) = Unit

                override fun begin(
                    configDir: File,
                    token: String,
                    maxSnapshotBytes: Long,
                ) = Unit

                override fun snapshot(
                    configDir: File,
                    token: String,
                    target: File,
                ) = Unit

                override fun replace(
                    configDir: File,
                    token: String,
                    target: File,
                    content: ByteArray,
                ) {
                    writeBytesCalled = true
                }

                override fun delete(
                    configDir: File,
                    token: String,
                    target: File,
                ) = Unit

                override fun rollback(
                    configDir: File,
                    token: String,
                ) = Unit

                override fun commit(
                    configDir: File,
                    token: String,
                ) = Unit

                override fun abort(
                    configDir: File,
                    token: String,
                ) = Unit

                override fun exportRecovery(
                    configDir: File,
                    token: String,
                ): String = configDir.resolve("recovery").absolutePath
            }
    }

    @After
    fun tearDown() {
        SecureFile.impl = originalImpl
    }

    @Test
    fun restoreUsesSecureByteWriterAfterBoundedStaging() {
        val zip = zipOf("target.txt", "com.example.app".toByteArray())

        WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zip))

        assertTrue(writeBytesCalled)
    }

    @Test
    fun expandedEntryOverOneMiBIsRejectedBeforeWrite() {
        val oversized = ByteArray(1024 * 1024 + 1) { 'a'.code.toByte() }
        val zip = zipOf("target.txt", oversized)

        assertThrows(IOException::class.java) {
            WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zip))
        }

        assertFalse(writeBytesCalled)
    }

    @Test
    fun existingKeyboxDirectoryEntryFloodIsRejectedBeforeWrite() {
        val keyboxDir = File(configDir, "keyboxes")
        assertTrue(keyboxDir.mkdirs())
        repeat(4_097) { index ->
            File(keyboxDir, "ignored-$index.tmp").writeText("x")
        }
        val zip = zipOf("target.txt", "com.example.app".toByteArray())

        assertThrows(IOException::class.java) {
            WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zip))
        }

        assertFalse(writeBytesCalled)
    }

    @Test
    fun malformedCboxBackupEntryIsRejectedBeforeWrite() {
        val zip = zipOf("keyboxes/bad.cbox", "not-a-cbox".toByteArray())

        assertThrows(IOException::class.java) {
            WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zip))
        }

        assertFalse(writeBytesCalled)
    }

    private fun zipOf(
        name: String,
        content: ByteArray,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
