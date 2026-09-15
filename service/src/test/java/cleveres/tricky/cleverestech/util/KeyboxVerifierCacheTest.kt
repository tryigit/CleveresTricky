package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.CrlBackend
import cleveres.tricky.cleverestech.CrlWire
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class KeyboxVerifierCacheTest {
    private lateinit var tempDir: java.io.File

    @Before
    fun setUp() {
        tempDir = java.nio.file.Files.createTempDirectory("crl_cache_test").toFile()
        KeyboxVerifier.setCacheRootForTesting(tempDir)
        CrlBackend.refreshOverride = { CrlWire.Handle(TEST_GENERATION, rawEntryCount = 1, normalizedEntryCount = 4) }
    }

    @After
    fun tearDown() {
        CrlBackend.resetForTesting()
        KeyboxVerifier.resetCrlUrlForTesting()
        KeyboxVerifier.resetCacheRootForTesting()
        tempDir.deleteRecursively()
    }

    @Test
    fun `fetchCrl caches results and uses ETag`() {
        val crlJson =
            """
            {
              "entries": {
                "12345": "REVOKED"
              }
            }
            """.trimIndent()

        val requestCount = AtomicInteger(0)
        val server = ServerSocket(0)
        val port = server.localPort

        val thread =
            Thread {
                try {
                    while (!Thread.interrupted()) {
                        val client = server.accept()
                        requestCount.incrementAndGet()
                        val reader = client.inputStream.bufferedReader()
                        var line = reader.readLine()
                        var ifNoneMatch: String? = null
                        while (line != null && line.isNotEmpty()) {
                            if (line.startsWith("If-None-Match:", ignoreCase = true)) {
                                ifNoneMatch = line.substringAfter(":").trim()
                            }
                            line = reader.readLine()
                        }

                        val writer = client.outputStream.bufferedWriter()
                        if (ifNoneMatch == "W/\"test-etag\"") {
                            writer.write("HTTP/1.1 304 Not Modified\r\n")
                            writer.write("\r\n")
                        } else {
                            writer.write("HTTP/1.1 200 OK\r\n")
                            writer.write("Content-Type: application/json\r\n")
                            writer.write("ETag: W/\"test-etag\"\r\n")
                            writer.write("Content-Length: ${crlJson.toByteArray().size}\r\n")
                            writer.write("\r\n")
                            writer.write(crlJson)
                        }
                        writer.flush()
                        client.close()
                    }
                } catch (_: Exception) {
                }
            }
        thread.start()

        try {
            KeyboxVerifier.setCrlUrlForTesting("http://localhost:$port")

            val first = requireNotNull(KeyboxVerifier.fetchCrl())
            assertEquals(TEST_GENERATION, first.generation)
            assertEquals(1, first.rawEntryCount)
            assertEquals(4, first.normalizedEntryCount)
            assertEquals(1, requestCount.get())

            val second = requireNotNull(KeyboxVerifier.fetchCrl())
            assertEquals(first, second)
            assertEquals(1, requestCount.get())

            assertEquals(4, KeyboxVerifier.countRevokedKeys())
            assertEquals(1, requestCount.get())

            val lastFetchTimeField = KeyboxVerifier::class.java.getDeclaredField("lastFetchTime")
            lastFetchTimeField.isAccessible = true
            lastFetchTimeField.set(KeyboxVerifier, 0L)
            File(tempDir, "attestation_status_cache.json").delete()

            val third = requireNotNull(KeyboxVerifier.fetchCrl())
            assertEquals(first, third)
            assertEquals(2, requestCount.get())
        } finally {
            thread.interrupt()
            server.close()
        }
    }


    @Test
    fun `failed CRL fetch is backoff protected to avoid repeated network stalls`() {
        val requestCount = AtomicInteger(0)
        val server = ServerSocket(0)
        val port = server.localPort
        val thread =
            Thread {
                try {
                    while (!Thread.interrupted()) {
                        val client = server.accept()
                        requestCount.incrementAndGet()
                        client.getOutputStream().close()
                        client.close()
                    }
                } catch (_: Exception) {
                }
            }
        thread.start()

        try {
            KeyboxVerifier.setCrlUrlForTesting("http://localhost:$port")

            assertEquals(null, KeyboxVerifier.fetchCrl())
            assertEquals("first failed request should reach the CRL server", 1, requestCount.get())

            assertEquals(null, KeyboxVerifier.fetchCrl())
            assertEquals("subsequent requests during backoff must not hit the network again", 1, requestCount.get())
        } finally {
            thread.interrupt()
            server.close()
        }
    }

    @Test
    fun `clearCacheLocked clears cache fields`() {
        // Set dummy values using reflection
        val cachedCrlField = KeyboxVerifier::class.java.getDeclaredField("cachedCrl")
        cachedCrlField.isAccessible = true
        cachedCrlField.set(KeyboxVerifier, CrlWire.Handle(TEST_GENERATION, rawEntryCount = 1, normalizedEntryCount = 4))

        val cachedEtagField = KeyboxVerifier::class.java.getDeclaredField("cachedEtag")
        cachedEtagField.isAccessible = true
        cachedEtagField.set(KeyboxVerifier, "dummy_etag")

        val backoffField = KeyboxVerifier::class.java.getDeclaredField("crlFetchNotBefore")
        backoffField.isAccessible = true
        backoffField.set(KeyboxVerifier, 123456789L)

        val lastFetchTimeField = KeyboxVerifier::class.java.getDeclaredField("lastFetchTime")
        lastFetchTimeField.isAccessible = true
        lastFetchTimeField.set(KeyboxVerifier, 123456789L)

        // Clear cache
        val method = KeyboxVerifier::class.java.getDeclaredMethod("clearCacheLocked")
        method.isAccessible = true
        method.invoke(KeyboxVerifier)

        // Verify cleared values
        org.junit.Assert.assertNull(cachedCrlField.get(KeyboxVerifier))
        org.junit.Assert.assertNull(cachedEtagField.get(KeyboxVerifier))
        org.junit.Assert.assertEquals(0L, backoffField.get(KeyboxVerifier))
        org.junit.Assert.assertEquals(0L, lastFetchTimeField.get(KeyboxVerifier))
    }

    private companion object {
        const val TEST_GENERATION = 41L
    }
}
