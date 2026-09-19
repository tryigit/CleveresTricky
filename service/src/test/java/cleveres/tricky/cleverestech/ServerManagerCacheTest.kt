package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.keystore.ManagedKeyboxOracle
import cleveres.tricky.cleverestech.TestKeyboxFixtures
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ServerManagerCacheTest {
    @After
    fun tearDown() {
        FusedCboxBackend.resetForTesting()
        KeyboxLoader.resetForTesting()
    }

    @Test
    fun `direct cbox materializes fused wire and preserves encrypted cache`() {
        installPayload(hasSignature = false)
        KeyboxLoader.parserOverride = { _, _ -> error("CBOX metadata must not be reparsed as XML") }
        val cbox = supportedCboxEnvelope()
        val server = serverConfig()
        var cached: ByteArray? = null

        try {
            val result = ServerManager.processContent(cbox, server)
            val cacheBytes = requireNotNull(result.second)
            cached = cacheBytes

            assertEquals(1, result.first.size)
            assertEquals("CleveresTricky-KeyId-v1", result.first.single().keyPair().private.format)
            assertArrayEquals(cbox, cacheBytes)

            val restored = ServerManager.parseCachedKeyboxes(cacheBytes.copyOf(), server)
            assertEquals(1, restored.size)
            assertEquals("CleveresTricky-KeyId-v1", restored.single().keyPair().private.format)
        } finally {
            cached?.fill(0)
            cbox.fill(0)
        }
    }

    @Test
    fun `zip cache preserves source payload and restores fused opaque handles`() {
        installPayload(hasSignature = false)
        KeyboxLoader.parserOverride = { _, _ -> error("ZIP CBOX metadata must not be reparsed as XML") }
        val cbox = supportedCboxEnvelope()
        val archive = zipOf("issuer.cbox", cbox)
        val server = serverConfig()
        val result = ServerManager.processContent(archive.copyOf(), server)
        val cached = requireNotNull(result.second)

        try {
            assertEquals(1, result.first.size)
            assertEquals("CleveresTricky-KeyId-v1", result.first.single().keyPair().private.format)
            assertArrayEquals(archive, cached)

            val restored = ServerManager.parseCachedKeyboxes(cached.copyOf(), server)
            assertEquals(1, restored.size)
            assertEquals("CleveresTricky-KeyId-v1", restored.single().keyPair().private.format)
        } finally {
            cached.fill(0)
            archive.fill(0)
            cbox.fill(0)
        }
    }

    @Test
    fun `signed direct cbox requires explicit verification key`() {
        installPayload(hasSignature = true)
        val cbox = supportedCboxEnvelope()

        try {
            val result = ServerManager.processContent(cbox, serverConfig())
            assertTrue(result.first.isEmpty())
            assertNull(result.second)
        } finally {
            cbox.fill(0)
        }
    }

    @Test
    fun `signed zip cbox requires explicit verification key`() {
        installPayload(hasSignature = true)
        val cbox = supportedCboxEnvelope()
        val archive = zipOf("issuer.cbox", cbox)

        try {
            val result = ServerManager.processContent(archive, serverConfig())
            assertTrue(result.first.isEmpty())
            assertNull(result.second)
        } finally {
            archive.fill(0)
            cbox.fill(0)
        }
    }

    @Test
    fun `signed cbox with verification key remains accepted`() {
        val observedKeys = ArrayList<String?>()
        FusedCboxBackend.openOverride = { _, _, publicKey ->
            observedKeys += publicKey
            if (publicKey == "test-key") fusedPayload(hasSignature = true) else null
        }
        val cbox = supportedCboxEnvelope()
        var cached: ByteArray? = null

        try {
            val result = ServerManager.processContent(cbox, serverConfig(contentPublicKey = "test-key"))
            val cacheBytes = requireNotNull(result.second)
            cached = cacheBytes
            assertEquals(1, result.first.size)
            assertArrayEquals(cbox, cacheBytes)
            assertEquals(listOf("test-key"), observedKeys)
        } finally {
            cached?.fill(0)
            cbox.fill(0)
        }
    }

    @Test
    fun `configured verification key rejects unsigned backend payload`() {
        installPayload(hasSignature = false)
        val cbox = supportedCboxEnvelope()

        try {
            val result = ServerManager.processContent(cbox, serverConfig(contentPublicKey = "test-key"))
            assertTrue(result.first.isEmpty())
            assertNull(result.second)
        } finally {
            cbox.fill(0)
        }
    }

    private fun installPayload(hasSignature: Boolean) {
        FusedCboxBackend.openOverride = { _, _, _ -> fusedPayload(hasSignature) }
    }

    private fun fusedPayload(hasSignature: Boolean): FusedCboxBackend.Payload {
        val stream = requireNotNull(javaClass.getResourceAsStream("/keybox/valid_ec.xml"))
        val legacy =
            InputStreamReader(stream, StandardCharsets.UTF_8).use {
                ManagedKeyboxOracle.parse(it, "valid_ec.xml")
            }.single()
        val document =
            KeyboxWire.Document(
                declaredKeyboxes = 1,
                keyboxCount = 1,
                snapshotSha256 = "00".repeat(32),
                keys =
                    listOf(
                        KeyboxWire.RawKey(
                            algorithm = "EC",
                            keyId = ByteArray(16) { index -> (index + 1).toByte() },
                            certificatesDer = legacy.certificates().map { it.encoded },
                        ),
                    ),
            )
        return FusedCboxBackend.Payload("cache-test", document, hasSignature)
    }

    @Test
    fun `server config preserves keybox counts across serialization and validates bounds`() {
        val server =
            serverConfig().copy(
                keyboxCount = 10,
                rkpCount = 3,
                teeCount = 7,
                rsaCount = 8,
                cboxCount = 7,
            )
        ServerManager.validateServer(server)

        val json = ServerManager.serializeServer(server)
        assertEquals(10, json.getInt("keyboxCount"))
        assertEquals(3, json.getInt("rkpCount"))
        assertEquals(7, json.getInt("teeCount"))
        assertEquals(8, json.getInt("rsaCount"))
        assertEquals(7, json.getInt("cboxCount"))

        val parsed = ServerManager.parseServer(json)
        assertEquals(10, parsed.keyboxCount)
        assertEquals(3, parsed.rkpCount)
        assertEquals(7, parsed.teeCount)
        assertEquals(8, parsed.rsaCount)
        assertEquals(7, parsed.cboxCount)
    }

    @Test
    fun `rkp keybox in cache increments server rkpCount and identifies RKP`() {
        ManagedKeyboxParserOracle.install()
        try {
            RkpProvenanceStore.addTrustedAnchorForTesting(TestKeyboxFixtures.rkpRootCert)
            val rkpXml = TestKeyboxFixtures.validRkpKeyboxXml.toByteArray(StandardCharsets.UTF_8)
            val server = serverConfig()
            val parsed = ServerManager.parseCachedKeyboxes(rkpXml, server)
            assertEquals(1, parsed.size)
            assertTrue(CertHack.isRkpKeybox(parsed.first()) || RkpProvenanceStore.hasVerifiedRkpCertificates(parsed.first()))

            val rkpCount = parsed.count { CertHack.isRkpKeybox(it) || RkpProvenanceStore.hasVerifiedRkpCertificates(it) }
            assertEquals(1, rkpCount)
        } finally {
            ManagedKeyboxParserOracle.reset()
        }
    }

    @Test
    fun `server url validation rejects non-routable hosts without DNS`() {
        fun configWith(url: String) =
            ServerManager.ServerConfig(
                id = "ssrf-test",
                name = "SSRF Test",
                url = url,
                priority = 0,
                enabled = true,
                authType = "NONE",
                authData = JSONObject(),
                autoRefresh = false,
                refreshIntervalHours = 24,
                contentPublicKey = null,
            )
        for (bad in
            listOf(
                "https://127.0.0.1/keyboxes.zip",
                "https://127.1.2.3:8443/x",
                "https://localhost/keyboxes.zip",
                "https://LOCALHOST/keyboxes.zip",
                "https://[::1]/x",
                "https://[::]/x",
                "https://169.254.10.20/x",
                "https://224.0.0.1/x",
                "https://0.0.0.0/x",
            )
        ) {
            assertThrows(IllegalArgumentException::class.java) {
                ServerManager.validateServer(configWith(bad))
            }
        }
        // Ordinary public hosts and private LAN servers stay accepted.
        ServerManager.validateServer(configWith("https://example.com/keyboxes.zip"))
        ServerManager.validateServer(configWith("https://192.168.1.10/keyboxes.zip"))
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
