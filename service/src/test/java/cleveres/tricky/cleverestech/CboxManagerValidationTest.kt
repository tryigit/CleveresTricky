package cleveres.tricky.cleverestech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class CboxManagerValidationTest {
    @Test
    fun `legacy empty password remains decryptable`() {
        assertTrue(CboxManager.isUnlockPasswordWithinLimit(""))
    }

    @Test
    fun `password over backend UTF-16 limit is rejected`() {
        assertFalse(CboxManager.isUnlockPasswordWithinLimit("a".repeat(1025)))
    }

    @Test
    fun `cbox directory scan rejects an irrelevant-entry flood`() {
        val root = Files.createTempDirectory("cbox-entry-flood").toFile()
        try {
            repeat(CboxManager.MAX_SCANNED_ENTRIES_PER_DIRECTORY + 1) { index ->
                Files.createFile(root.toPath().resolve("ignored-$index.txt"))
            }
            assertThrows(IOException::class.java) { CboxManager.listCboxFilesForTesting(root) }
        } finally {
            root.deleteRecursively()
        }
    }
}
