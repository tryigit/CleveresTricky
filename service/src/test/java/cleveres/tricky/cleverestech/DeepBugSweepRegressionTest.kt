package cleveres.tricky.cleverestech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files

class DeepBugSweepRegressionTest {
    @Test
    fun `bounded backup copy rejects source growth past entry limit`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        val output = ByteArrayOutputStream()

        assertThrows(IOException::class.java) {
            BackupIo.copyBounded(input, output, entryLimit = 4, remainingTotal = 100)
        }
        assertTrue(output.size() <= 4)
    }

    @Test
    fun `bounded backup copy accounts for actual streamed bytes`() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()

        val copied =
            BackupIo.copyBounded(
                ByteArrayInputStream(expected),
                output,
                entryLimit = 16,
                remainingTotal = 16,
            )

        assertEquals(expected.size.toLong(), copied)
        assertArrayEquals(expected, output.toByteArray())
    }

    @Test
    fun `restore transaction rolls back every earlier mutation after failure`() {
        val root = Files.createTempDirectory("cleveres-restore-rollback").toFile()
        try {
            val first = root.resolve("first.txt").apply { writeText("old-first") }
            val second = root.resolve("second.txt").apply { writeText("old-second") }
            val created = root.resolve("created.txt")

            val mutations =
                listOf(
                    BackupRestoreTransaction.Mutation(first, "new-first".toByteArray()),
                    BackupRestoreTransaction.Mutation(second, null),
                    BackupRestoreTransaction.Mutation(created, "new-created".toByteArray()),
                )

            assertThrows(IOException::class.java) {
                BackupRestoreTransaction.apply(root, mutations) { index, _ ->
                    if (index == 2) throw IOException("injected restore failure")
                }
            }

            assertEquals("old-first", first.readText())
            assertEquals("old-second", second.readText())
            assertFalse(created.exists())
            assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".restore-txn-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `restore transaction commits replacements creations and deletions together`() {
        val root = Files.createTempDirectory("cleveres-restore-commit").toFile()
        try {
            val first = root.resolve("first.txt").apply { writeText("old-first") }
            val second = root.resolve("second.txt").apply { writeText("old-second") }
            val created = root.resolve("created.txt")

            BackupRestoreTransaction.apply(
                root,
                listOf(
                    BackupRestoreTransaction.Mutation(first, "new-first".toByteArray()),
                    BackupRestoreTransaction.Mutation(second, null),
                    BackupRestoreTransaction.Mutation(created, "new-created".toByteArray()),
                ),
            )

            assertEquals("new-first", first.readText())
            assertFalse(second.exists())
            assertEquals("new-created", created.readText())
            assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".restore-txn-") })
        } finally {
            root.deleteRecursively()
        }
    }
}
