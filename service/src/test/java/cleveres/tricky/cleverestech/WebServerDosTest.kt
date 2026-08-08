package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Socket
import java.net.SocketTimeoutException

class WebServerDosTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: WebServer
    private lateinit var configDir: File

    @Before
    fun setUp() {
        // Mock Logger
        Logger.setImpl(
            object : Logger.LogImpl {
                override fun d(
                    tag: String,
                    msg: String,
                ) {}

                override fun e(
                    tag: String,
                    msg: String,
                ) {}

                override fun e(
                    tag: String,
                    msg: String,
                    t: Throwable?,
                ) {
                    t?.printStackTrace()
                }

                override fun i(
                    tag: String,
                    msg: String,
                ) {}
            },
        )
        configDir = tempFolder.newFolder("config")
        server = WebServer(0, configDir)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun testPayloadTooLarge() {
        val port = server.listeningPort
        val token = server.token
        val socket = Socket("localhost", port)
        socket.soTimeout = 2000 // 2s timeout

        val writer = socket.getOutputStream().writer(Charsets.UTF_8)
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)

        // Claim 11MB payload
        val payloadSize = 11 * 1024 * 1024
        writer.write("POST /api/upload_keybox?token=$token HTTP/1.1\r\n")
        writer.write("Host: localhost:$port\r\n")
        writer.write("Content-Length: $payloadSize\r\n")
        writer.write("Content-Type: application/x-www-form-urlencoded\r\n")
        writer.write("\r\n")
        // Send incomplete body
        writer.write("filename=test.xml&content=start")
        writer.flush()

        // If vulnerable, server waits for the rest of 6MB -> Timeout
        // If fixed, server returns 400 Bad Request immediately.

        try {
            val line = reader.readLine()
            if (line == null) {
                // Success - Server dropped connection without responding, protecting itself
            } else {
                if (line.contains("400") || line.contains("500")) {
                    // Success - Server rejected the payload
                } else {
                    org.junit.Assert.fail("Expected 400 or 500 response but got: $line")
                }
            }
        } catch (e: SocketTimeoutException) {
            org.junit.Assert.fail("Server timed out waiting for data (Vulnerable to DoS)")
        } finally {
            socket.close()
        }
    }

    @Test
    fun testBodyPayloadTooLarge() {
        val port = server.listeningPort
        val token = server.token
        val socket = Socket("localhost", port)
        socket.soTimeout = 2000

        val writer = socket.getOutputStream().writer(Charsets.UTF_8)
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)

        // Claim 6MB payload (bypasses 10MB firewall but fails 5MB MAX_BODY_SIZE)
        val payloadSize = 6 * 1024 * 1024
        writer.write("POST /api/upload_keybox?token=$token HTTP/1.1\r\n")
        writer.write("Host: localhost:$port\r\n")
        writer.write("Content-Length: $payloadSize\r\n")
        writer.write("Content-Type: application/x-www-form-urlencoded\r\n")
        writer.write("\r\n")
        // Send incomplete body
        writer.write("filename=test.xml&content=start")
        writer.flush()

        try {
            val line = reader.readLine()
            if (line == null) {
                // Success - Server dropped connection
            } else {
                if (line.contains("400") || line.contains("500")) {
                    // Success
                } else {
                    org.junit.Assert.fail("Expected 400 or 500 response but got: $line")
                }
            }
        } catch (e: SocketTimeoutException) {
            org.junit.Assert.fail("Server timed out waiting for data (Vulnerable to DoS)")
        } finally {
            socket.close()
        }
    }
}
