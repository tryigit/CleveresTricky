package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class StoredKeyboxInventoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `direct root XML and managed XML CBOX are inventoried with explicit scope`() {
        val root = temp.newFolder("config")
        File(root, "A1B2C3.xml").writeText("root")
        val managed = File(root, "keyboxes").also { assertTrue(it.mkdirs()) }
        File(managed, "D4E5F6.xml").writeText("managed")
        File(managed, "LOCKED.cbox").writeText("encrypted")
        File(root, "ignored.txt").writeText("ignored")
        val items = StoredKeyboxInventory.list(root)
        assertEquals(listOf("keyboxes:D4E5F6.xml", "keyboxes:LOCKED.cbox", "root:A1B2C3.xml"), items.map { it.id }.sorted())
        assertEquals(2, StoredKeyboxInventory.runtimeXmlSources(root).size)
    }

    @Test
    fun `duplicate basename across scopes remains runtime compatible`() {
        val root = temp.newFolder("duplicate")
        File(root, "same.xml").writeText("root")
        val managed = File(root, "keyboxes").also { assertTrue(it.mkdirs()) }
        File(managed, "same.xml").writeText("managed")

        assertEquals(
            listOf("keyboxes:same.xml", "root:same.xml"),
            StoredKeyboxInventory.runtimeXmlSources(root).map { it.id }.sorted(),
        )
    }

    @Test
    fun `oversized XML is excluded before runtime parsing`() {
        val root = temp.newFolder("oversized")
        val oversized = File(root, "oversized.xml")
        RandomAccessFile(oversized, "rw").use { file ->
            file.setLength(StoredKeyboxInventory.MAX_XML_BYTES + 1)
        }

        assertTrue(StoredKeyboxInventory.runtimeXmlSources(root).isEmpty())
    }

    @Test
    fun `runtime XML source count is bounded even when entries are oversized`() {
        val root = temp.newFolder("bounded")
        repeat(StoredKeyboxInventory.MAX_ACTIVE_XML_SOURCES + 1) { index ->
            val file = File(root, "cert-$index.xml")
            RandomAccessFile(file, "rw").use { it.setLength(StoredKeyboxInventory.MAX_XML_BYTES + 1) }
        }
        assertThrows(IllegalArgumentException::class.java) { StoredKeyboxInventory.runtimeXmlSources(root) }
    }

    @Test
    fun `directory scan rejects an irrelevant-entry flood instead of scanning without a bound`() {
        val root = temp.newFolder("entry-flood")
        repeat(StoredKeyboxInventory.MAX_SCANNED_ENTRIES_PER_DIRECTORY + 1) { index ->
            File(root, "ignored-$index.txt").writeText("ignored")
        }

        assertThrows(IOException::class.java) { StoredKeyboxInventory.list(root) }
    }

    @Test
    fun `disabled keyboxes are excluded from runtime sources but remain listed`() {
        val root = temp.newFolder("disabled")
        val managed = File(root, "keyboxes").also { assertTrue(it.mkdirs()) }
        File(managed, "active.xml").writeText("a")
        File(managed, "off.xml").writeText("d")
        File(root, "root.xml").writeText("r")

        assertEquals(3, StoredKeyboxInventory.runtimeXmlSources(root).size)

        File(root, "disabled_keyboxes").writeText("keyboxes:off.xml\n")
        assertEquals(
            listOf("keyboxes:active.xml", "root:root.xml"),
            StoredKeyboxInventory.runtimeXmlSources(root).map { it.id }.sorted(),
        )
        assertEquals(3, StoredKeyboxInventory.list(root).size)

        File(root, "disabled_keyboxes").writeText("keyboxes:off.xml\nroot:root.xml\n")
        assertEquals(
            listOf("keyboxes:active.xml"),
            StoredKeyboxInventory.runtimeXmlSources(root).map { it.id },
        )

        File(root, "disabled_keyboxes").delete()
        assertEquals(3, StoredKeyboxInventory.runtimeXmlSources(root).size)
    }

    @Test
    fun `disabled set survives blank lines and does not fail closed on unreadable file`() {
        val root = temp.newFolder("disabled-parse")
        File(root, "a.xml").writeText("a")
        val disabled = File(root, "disabled_keyboxes")
        disabled.writeText("\n  \nroot:a.xml\n\n")
        assertEquals(emptyList<String>(), StoredKeyboxInventory.runtimeXmlSources(root).map { it.id })
    }

    @Test
    fun `disabled sources do not consume the active XML limit`() {
        val root = temp.newFolder("disabled-boundary")
        repeat(StoredKeyboxInventory.MAX_ACTIVE_XML_SOURCES + 1) { index ->
            File(root, "keybox-$index.xml").writeText("keybox")
        }
        File(root, "disabled_keyboxes").writeText("root:keybox-0.xml\n")

        val active = StoredKeyboxInventory.runtimeXmlSources(root)

        assertEquals(StoredKeyboxInventory.MAX_ACTIVE_XML_SOURCES, active.size)
        assertFalse(active.any { it.id == "root:keybox-0.xml" })
    }
}
