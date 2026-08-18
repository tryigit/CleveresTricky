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
    fun `reads a single XML keybox directly`() {
        val xml = "<AndroidAttestation><Keybox/></AndroidAttestation>".toByteArray()

        val selected =
            KeyboxImportReader.read(ByteArrayInputStream(xml), "single-keybox.xml") { bytes ->
                bytes.isNotEmpty() && bytes[0] == '<'.code.toByte()
            }

        assertEquals(1, selected.size)
        assertEquals("single-keybox.xml", selected.single().displayName)
        assertArrayEquals(xml, selected.single().bytes)
    }

    @Test
    fun `single XML import strips path-like display names`() {
        val xml = "<keybox/>".toByteArray()

        val selected = KeyboxImportReader.read(ByteArrayInputStream(xml), "../nested/keybox.xml") { true }

        assertEquals("keybox.xml", selected.single().displayName)
    }

    @Test
    fun `zeroizes rejected single XML buffer`() {
        val observed = mutableListOf<ByteArray>()

        assertThrows(IOException::class.java) {
            KeyboxImportReader.read(ByteArrayInputStream("not xml".toByteArray()), "bad.xml") { bytes ->
                observed += bytes
                false
            }
        }

        assertEquals(1, observed.size)
        assertTrue(observed.single().all { it == 0.toByte() })
    }

    @Test
    fun `rejects oversized single XML before validation`() {
        val oversized = ByteArray(KeyboxZipReader.MAX_XML_BYTES + 1) { 'x'.code.toByte() }
        var validationCalls = 0

        assertThrows(IOException::class.java) {
            KeyboxImportReader.read(ByteArrayInputStream(oversized), "large.xml") {
                validationCalls++
                true
            }
        }

        assertEquals(0, validationCalls)
    }

    @Test
    fun `import reader detects ZIP batches by signature`() {
        val archive =
            zipOf(
                "first.xml" to "<first/>".toByteArray(),
                "second.xml" to "<second/>".toByteArray(),
            )

        val selected = KeyboxImportReader.read(ByteArrayInputStream(archive), "anything.bin") { true }

        assertEquals(listOf("first.xml", "second.xml"), selected.map { it.displayName })
    }

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
    fun `zeroizes current and previous XML buffers when validator throws`() {
        val archive =
            zipOf(
                "first.xml" to "<first/>".toByteArray(),
                "second.xml" to "<second/>".toByteArray(),
            )
        val observed = mutableListOf<ByteArray>()

        assertThrows(IllegalStateException::class.java) {
            KeyboxZipReader.read(ByteArrayInputStream(archive)) { bytes ->
                observed += bytes
                if (observed.size == 2) throw IllegalStateException("validator failure")
                true
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
        val oversized = ByteArray(KeyboxZipReader.MAX_XML_BYTES + 1) { 'x'.code.toByte() }

        assertThrows(IOException::class.java) {
            KeyboxZipReader.read(ByteArrayInputStream(zipOf("large.xml" to oversized))) { true }
        }
    }

    @Test
    fun `rejects oversized unrelated entry expansion before later keyboxes`() {
        val archive =
            zipOf(
                "padding.bin" to ByteArray(1024 * 1024 + 1),
                "keybox.xml" to "<k/>".toByteArray(),
            )

        assertThrows(IOException::class.java) {
            KeyboxZipReader.read(ByteArrayInputStream(archive)) { true }
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
