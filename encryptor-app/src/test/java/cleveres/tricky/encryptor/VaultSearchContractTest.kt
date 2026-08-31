package cleveres.tricky.encryptor

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSearchContractTest {
    @Test
    fun `vault search filters names and preserves selection across filters`() {
        val source = locate(
            "src/main/java/cleveres/tricky/encryptor/SecureMainActivity.kt",
            "encryptor-app/src/main/java/cleveres/tricky/encryptor/SecureMainActivity.kt",
        ).readText()

        assertTrue(source.contains("var searchQuery by remember { mutableStateOf(\"\") }"))
        assertTrue(source.contains("contains(normalizedQuery, ignoreCase = true)"))
        assertTrue(source.contains("items(filteredFiles"))
        assertTrue(source.contains("selectedNames - filteredNames"))
        assertTrue(source.contains("selectedNames + filteredNames"))
        assertTrue(source.contains("zipTargets = files.mapNotNull { if (it.file.name in selectedNames) it.file else null }"))
        assertTrue(source.contains("files.filter { it.file.name in names }.forEach"))
    }

    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull(File::exists)
            ?: error("Could not locate ${candidates.joinToString()}")
}
