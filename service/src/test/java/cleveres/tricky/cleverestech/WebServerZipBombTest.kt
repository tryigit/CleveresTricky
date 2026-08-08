package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WebServerZipBombTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var configDir: File
    private lateinit var originalImpl: SecureFileOperations
    private var writeStreamCalled = false
    private var limitPassed = -1L

    @Before
    fun setUp() {
        configDir = tempFolder.newFolder("config")
        originalImpl = SecureFile.impl
        SecureFile.impl =
            object : SecureFileOperations {
                override fun writeText(
                    file: File,
                    content: String,
                ) = Unit

                override fun writeStream(
                    file: File,
                    inputStream: InputStream,
                    limit: Long,
                ) {
                    writeStreamCalled = true
                    limitPassed = limit
                    inputStream.readBytes()
                }

                override fun mkdirs(
                    file: File,
                    mode: Int,
                ) = Unit

                override fun touch(
                    file: File,
                    mode: Int,
                ) = Unit
            }
    }

    @After
    fun tearDown() {
        SecureFile.impl = originalImpl
    }

    @Test
    fun restorePassesOneMiBBoundToSecureWriter() {
        val zip = zipOf("target.txt", "com.example.app".toByteArray())

        WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zip))

        assertTrue(writeStreamCalled)
        assertEquals(1024 * 1024L, limitPassed)
    }

    @Test
    fun expandedEntryOverOneMiBIsRejectedBeforeWrite() {
        val oversized = ByteArray(1024 * 1024 + 1) { 'a'.code.toByte() }
        val zip = zipOf("target.txt", oversized)

        assertThrows(IOException::class.java) {
            WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zip))
        }

        assertFalse(writeStreamCalled)
    }

    @Test
    fun malformedCboxBackupEntryIsRejectedBeforeWrite() {
        val zip = zipOf("keyboxes/bad.cbox", "not-a-cbox".toByteArray())

        assertThrows(IOException::class.java) {
            WebServer.restoreBackupZip(configDir, ByteArrayInputStream(zip))
        }

        assertFalse(writeStreamCalled)
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
