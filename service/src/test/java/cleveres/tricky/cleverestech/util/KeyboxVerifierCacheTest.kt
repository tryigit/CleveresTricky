package cleveres.tricky.cleverestech.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class KeyboxVerifierCacheTest {
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
        val lastEtag = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val server = ServerSocket(0)
        val port = server.localPort

        val thread =
            Thread {
                try {
                    while (!Thread.interrupted()) {
                        val client = server.accept()
                        requestCount.incrementAndGet()
                        val reader = client.inputStream.bufferedReader()
                        // Read request headers
                        var line = reader.readLine()
                        var ifNoneMatch: String? = null
                        while (line != null && line.isNotEmpty()) {
                            if (line.startsWith("If-None-Match:", ignoreCase = true)) {
                                ifNoneMatch = line.substringAfter(":").trim()
                            }
                            line = reader.readLine()
                        }

                        val writer = client.outputStream.bufferedWriter()
                        if (ifNoneMatch != null && ifNoneMatch == "W/\"test-etag\"") {
                            writer.write("HTTP/1.1 304 Not Modified\r\n")
                            writer.write("\r\n")
                        } else {
                            writer.write("HTTP/1.1 200 OK\r\n")
                            writer.write("Content-Type: application/json\r\n")
                            writer.write("ETag: W/\"test-etag\"\r\n")
                            writer.write("Content-Length: ${crlJson.length}\r\n")
                            writer.write("\r\n")
                            writer.write(crlJson)
                        }
                        writer.flush()
                        client.close()
                    }
                } catch (e: Exception) {
                }
            }
        thread.start()

        try {
            KeyboxVerifier.setCrlUrlForTesting("http://localhost:$port")

            // 1. Initial fetch
            val res1 = KeyboxVerifier.fetchCrl()
            assertEquals(
                setOf(
                    "3039",
                    "0000000000000000000000000000000000003039",
                    "0000000000000000000000000000000000000000000000000000000000003039",
                    "00000000000000000000000000003039",
                ),
                res1,
            )
            assertEquals(1, requestCount.get())

            // 2. Immediate second fetch - should be CACHED in memory (no network)
            val res2 = KeyboxVerifier.fetchCrl()
            assertEquals(
                setOf(
                    "3039",
                    "0000000000000000000000000000000000003039",
                    "0000000000000000000000000000000000000000000000000000000000003039",
                    "00000000000000000000000000003039",
                ),
                res2,
            )
            assertEquals(1, requestCount.get())

            // 3. countRevokedKeys - should also be cached
            assertEquals(4, KeyboxVerifier.countRevokedKeys())
            assertEquals(1, requestCount.get())

            // 4. Force expiration by modifying private field via reflection?
            // Actually let's just wait or use a mock clock if we had one.
            // Since we can't easily mock time, let's use reflection to reset lastFetchTime.
            val lastFetchTimeField = KeyboxVerifier::class.java.getDeclaredField("lastFetchTime")
            lastFetchTimeField.isAccessible = true
            lastFetchTimeField.set(KeyboxVerifier, 0L)

            // 5. Fetch after expiration - should trigger network with If-None-Match
            val res3 = KeyboxVerifier.fetchCrl()
            assertEquals(
                setOf(
                    "3039",
                    "0000000000000000000000000000000000003039",
                    "0000000000000000000000000000000000000000000000000000000000003039",
                    "00000000000000000000000000003039",
                ),
                res3,
            )
            assertEquals(2, requestCount.get()) // Incremented because of network request
        } finally {
            KeyboxVerifier.resetCrlUrlForTesting()
            thread.interrupt()
            server.close()
        }
    }
}
