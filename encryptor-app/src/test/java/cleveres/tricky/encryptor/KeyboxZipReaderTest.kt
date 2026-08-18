package cleveres.tricky.encryptor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboxZipReaderTest {
    @Test
    fun `reads multiple XML entries and ignores unrelated ZIP content`() {
        val first = "<AndroidAttestation><Keybox/></AndroidAttestation>".toByteArray()
        val second = "<AndroidAttestation><Keybox id=\"2\"/></AndroidAttestation>".toByteArray()
        val archive =
            zipOf(
                "nested/first.xml" to first,
                "notes.txt" to "ignore me".toByteArray(),
                "../second.XML" to second,
            )

        val selected =
            KeyboxZipReader.read(ByteArrayInputStream(archive)) { bytes ->
                bytes.isNotEmpty() && bytes[0] == '<'.code.toByte()
            }

        assertEquals(listOf("first.xml", "second.XML"), selected.map { it.displayName })
        assertArrayEquals(first, selected[0].bytes)
        assertArrayEquals(second, selected[1].bytes)
    }

    @Test
    fun `rejects the whole archive and zeroizes parsed XML when one keybox is invalid`() {
        val archive =
            zipOf(
                "good.xml" to "<good/>".toByteArray(),
                "bad.xml" to "not xml".toByteArray(),
            )
        val observed = mutableListOf<ByteArray>()

        assertThrows(IOException::class.java) {
            KeyboxZipReader.read(ByteArrayInputStream(archive)) { bytes ->
                observed += bytes
                bytes[0] == '<'.code.toByte()
            }
        }

        assertEquals(2, observed.size)
        assertTrue(observed.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun `rejects ZIP archives with more than the bounded keybox count`() {
        val entries =
            (0..KeyboxZipReader.MAX_KEYBOX_FILES).map { index ->
                "keybox-$index.xml" to "<k/>".toByteArray()
            }

        assertThrows(IOException::class.java) {
            KeyboxZipReader.read(ByteArrayInputStream(zipOf(*entries.toTypedArray()))) { true }
        }
    }

    @Test
    fun `rejects an XML entry larger than the per-file limit`() {
        val oversized = ByteArray(10 * 1024 * 1024 + 1) { 'x'.code.toByte() }

        assertThrows(IOException::class.java) {
            KeyboxZipReader.read(ByteArrayInputStream(zipOf("large.xml" to oversized))) { true }
        }
    }

    @Test
    fun `rejects archives without XML keyboxes`() {
        val archive = zipOf("readme.txt" to "hello".toByteArray())

        assertThrows(IOException::class.java) {
            KeyboxZipReader.read(ByteArrayInputStream(archive)) { true }
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
