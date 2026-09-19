package cleveres.tricky.cleverestech

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RkpProvenanceStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `record and lookup use the same canonical Unicode identifier`() {
        val root = temp.newFolder("unicode")
        val decomposed = "e\u0301.xml"
        val composed = "\u00e9.xml"

        RkpProvenanceStore.recordRkp("keyboxes/$decomposed", root)

        assertTrue(RkpProvenanceStore.isRkp(composed, root))
        assertFalse("case-sensitive filenames must remain distinct", RkpProvenanceStore.isRkp("\u00c9.xml", root))
        val stored = JSONObject(File(root, RkpProvenanceStore.PROVENANCE_FILE_NAME).readText())
        assertEquals(composed, stored.getJSONArray("rkp_keyboxes").getString(0))
    }

    @Test
    fun `empty and overlong identifiers never create provenance state`() {
        val root = temp.newFolder("invalid-identifiers")
        val overlong = "a".repeat(125) + ".xml"

        RkpProvenanceStore.recordRkp("   ", root)
        RkpProvenanceStore.recordRkp(overlong, root)

        assertFalse(RkpProvenanceStore.isRkp(overlong, root))
        assertFalse(File(root, RkpProvenanceStore.PROVENANCE_FILE_NAME).exists())
    }

    @Test
    fun `oversized provenance snapshot fails closed`() {
        val root = temp.newFolder("oversized")
        val provenance = File(root, RkpProvenanceStore.PROVENANCE_FILE_NAME)
        provenance.writeBytes(ByteArray(64 * 1024 + 1) { 'x'.code.toByte() })

        assertFalse(RkpProvenanceStore.isRkp("keybox.xml", root))
    }

    @Test
    fun `symbolic link provenance file is never followed`() {
        val root = temp.newFolder("symlink")
        val outside = temp.newFile("outside-provenance.json")
        outside.writeText("""{"rkp_keyboxes":["keybox.xml"]}""")
        Files.createSymbolicLink(
            File(root, RkpProvenanceStore.PROVENANCE_FILE_NAME).toPath(),
            outside.toPath(),
        )

        assertFalse(RkpProvenanceStore.isRkp("keybox.xml", root))
    }
}
