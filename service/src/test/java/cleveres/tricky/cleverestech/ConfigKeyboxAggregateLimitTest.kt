package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ConfigKeyboxAggregateLimitTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        Config.reset()
        root = Files.createTempDirectory("config-keybox-aggregate").toFile()
        Config.setRootForTesting(root)
        ManagedKeyboxParserOracle.install()
    }

    @After
    fun tearDown() {
        ManagedKeyboxParserOracle.reset()
        Config.reset()
        root.deleteRecursively()
    }

    @Test
    fun `aggregate keybox limit rejects oversized parsed source and clears active snapshot`() {
        val source = File(root, "oversized.xml")
        source.writeText(TestKeyboxFixtures.validEcKeyboxXml)
        val keybox =
            requireNotNull(
                ManagedOpaqueKeyOracle.parse(
                    StringReader(TestKeyboxFixtures.validEcKeyboxXml),
                    source.name,
                ).singleOrNull(),
            )
        val oversized = List(KeyboxLoader.MAX_ACTIVE_KEYS + 1) { keybox }
        KeyboxLoader.fileParserOverride = { _, _ ->
            KeyboxLoader.ParsedFile("a".repeat(64), oversized)
        }

        val accepted =
            Config.updateKeyBoxesSyncWithoutExternalSourcesForTesting(
                revokedSerials = emptySet(),
                verifier = { _, _ -> KeyboxVerifier.Status.VALID },
            )

        assertFalse(accepted)
        assertEquals(0, CertHack.getPublishedKeyboxCountForTesting())
    }
}
