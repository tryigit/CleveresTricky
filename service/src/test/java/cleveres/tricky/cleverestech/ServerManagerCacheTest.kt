package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.CboxDecryptor
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ServerManagerCacheTest {
    @After
    fun tearDown() {
        CboxDecryptor.resetForTesting()
        KeyboxLoader.resetForTesting()
        ManagedOpaqueKeyOracle.reset()
    }

    @Test
    fun `zip cache preserves source payload and restores after opaque handles are reset`() {
        val sourceXml = TestKeyboxFixtures.validEcKeyboxXml.toByteArray(StandardCharsets.UTF_8)
        CboxDecryptor.backendOpenOverride = { _, _, _ ->
            NativeBackend.CboxPayload(
                author = "cache-test",
                xmlContent = sourceXml.copyOf(),
                hasSignature = false,
            )
        }
        KeyboxLoader.parserOverride = { xml, filename ->
            ManagedOpaqueKeyOracle.parse(
                StringReader(String(xml, StandardCharsets.UTF_8)),
                filename,
            )
        }

        val cbox = supportedCboxEnvelope()
        val archive = zipOf("issuer.cbox", cbox)
        val server = serverConfig()
        val result = ServerManager.processContent(archive.copyOf(), server)
        val cached = requireNotNull(result.second)

        try {
            assertEquals(1, result.first.size)
            assertEquals("CleveresTricky-KeyId-v1", result.first.single().keyPair().private.format)
            assertArrayEquals(archive, cached)

            // Simulate losing every managed mapping to the first Rust-owned opaque handles.
            // A valid cache must reconstruct key material from the original archive payload.
            ManagedOpaqueKeyOracle.reset()
            val restored = ServerManager.parseCachedKeyboxes(cached.copyOf(), server)

            assertEquals(1, restored.size)
            assertEquals("CleveresTricky-KeyId-v1", restored.single().keyPair().private.format)
        } finally {
            cached.fill(0)
            archive.fill(0)
            cbox.fill(0)
            sourceXml.fill(0)
        }
    }

    @Test
    fun `signed direct cbox requires explicit verification key`() {
        val sourceXml = TestKeyboxFixtures.validEcKeyboxXml.toByteArray(StandardCharsets.UTF_8)
        CboxDecryptor.backendOpenOverride = { _, _, _ ->
            NativeBackend.CboxPayload(
                author = "signed-test",
                xmlContent = sourceXml.copyOf(),
                hasSignature = true,
            )
        }
        KeyboxLoader.parserOverride = { xml, filename ->
            ManagedOpaqueKeyOracle.parse(StringReader(String(xml, StandardCharsets.UTF_8)), filename)
        }
        val cbox = supportedCboxEnvelope()

        try {
            val result = ServerManager.processContent(cbox, serverConfig())
            assertTrue(result.first.isEmpty())
            assertNull(result.second)
        } finally {
            cbox.fill(0)
            sourceXml.fill(0)
        }
    }

    @Test
    fun `signed zip cbox requires explicit verification key`() {
        val sourceXml = TestKeyboxFixtures.validEcKeyboxXml.toByteArray(StandardCharsets.UTF_8)
        CboxDecryptor.backendOpenOverride = { _, _, _ ->
            NativeBackend.CboxPayload(
                author = "signed-zip-test",
                xmlContent = sourceXml.copyOf(),
                hasSignature = true,
            )
        }
        KeyboxLoader.parserOverride = { xml, filename ->
            ManagedOpaqueKeyOracle.parse(StringReader(String(xml, StandardCharsets.UTF_8)), filename)
        }
        val cbox = supportedCboxEnvelope()
        val archive = zipOf("issuer.cbox", cbox)

        try {
            val result = ServerManager.processContent(archive, serverConfig())
            assertTrue(result.first.isEmpty())
            assertNull(result.second)
        } finally {
            archive.fill(0)
            cbox.fill(0)
            sourceXml.fill(0)
        }
    }

    @Test
    fun `signed cbox with verification key remains accepted`() {
        val sourceXml = TestKeyboxFixtures.validEcKeyboxXml.toByteArray(StandardCharsets.UTF_8)
        CboxDecryptor.backendOpenOverride = { _, _, _ ->
            NativeBackend.CboxPayload(
                author = "signed-key-test",
                xmlContent = sourceXml.copyOf(),
                hasSignature = true,
            )
        }
        KeyboxLoader.parserOverride = { xml, filename ->
            ManagedOpaqueKeyOracle.parse(StringReader(String(xml, StandardCharsets.UTF_8)), filename)
        }
        val cbox = supportedCboxEnvelope()
        var cached: ByteArray? = null

        try {
            val result = ServerManager.processContent(cbox, serverConfig(contentPublicKey = "test-key"))
            cached = result.second
            assertEquals(1, result.first.size)
            assertTrue(cached != null)
        } finally {
            cached?.fill(0)
            cbox.fill(0)
            sourceXml.fill(0)
        }
    }

    private fun serverConfig(contentPublicKey: String? = null) =
        ServerManager.ServerConfig(
            id = "cache-test",
            name = "Cache Test",
            url = "https://example.com/keyboxes.zip",
            priority = 0,
            enabled = true,
            authType = "NONE",
            authData = JSONObject(),
            autoRefresh = false,
            refreshIntervalHours = 24,
            contentPublicKey = contentPublicKey,
        )

    private fun supportedCboxEnvelope(): ByteArray =
        ByteArray(4 + Int.SIZE_BYTES + 16 + 12 + 16).also { bytes ->
            "CBOX".toByteArray(StandardCharsets.US_ASCII).copyInto(bytes)
            bytes[7] = 1
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
