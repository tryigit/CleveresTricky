package cleveres.tricky.encryptor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultStoreBatchNameTest {
    @Test
    fun `batch base names strip archive paths and unsafe characters`() {
        val name = VaultStore.batchBaseName("https://author.example/a", "../../nested/key box?.xml")

        assertTrue(name.startsWith("https___author.example_a-"))
        assertTrue(name.endsWith("key_box_"))
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.startsWith('.'))
    }

    @Test
    fun `batch base names remain within cbox filename budget`() {
        val name = VaultStore.batchBaseName("a".repeat(400), "b".repeat(400) + ".xml")

        assertTrue(name.length <= 123)
        assertTrue("$name.cbox".length <= 128)
    }
}
