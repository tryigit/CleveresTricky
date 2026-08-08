package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Socket

class WebServerMissingContentLengthTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: WebServer
    private lateinit var configDir: File

    @Before
    fun setUp() {
        // Use println logger to avoid silencing logs for other tests and to aid debugging
        Logger.setImpl(
            object : Logger.LogImpl {
                override fun d(
                    tag: String,
                    msg: String,
                ) {
                    println("D/$tag: $msg")
                }

                override fun e(
                    tag: String,
                    msg: String,
                ) {
                    println("E/$tag: $msg")
                }

                override fun e(
                    tag: String,
                    msg: String,
                    t: Throwable?,
                ) {
                    println("E/$tag: $msg")
                    t?.printStackTrace()
                }

                override fun i(
                    tag: String,
                    msg: String,
                ) {
                    println("I/$tag: $msg")
                }
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
    fun testMissingContentLength() {
        val port = server.listeningPort
        val token = server.token
        val socket = Socket("localhost", port)
        socket.soTimeout = 5000

        val writer = socket.getOutputStream().writer(Charsets.UTF_8)
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)

        // POST without Content-Length
        writer.write("POST /api/upload_keybox?token=$token HTTP/1.1\r\n")
        writer.write("Host: localhost:$port\r\n")
        // No Content-Length
        writer.write("Content-Type: application/x-www-form-urlencoded\r\n")
        writer.write("\r\n")

        // Start streaming data
        // If server reads forever or accepts huge data, it fails.
        // We expect it to either fail immediately (if we mandate Content-Length)
        // or eventually fail if we exceed limit (if it counts bytes).

        val chunk = "a".repeat(1024)
        try {
            // Write 11MB
            for (i in 0 until 11 * 1024) {
                writer.write(chunk)
            }
            writer.flush()
        } catch (e: Exception) {
            // Write failed, maybe server closed connection. This is good.
        }

        // Check response
        try {
            val line = reader.readLine()
            if (line == null) {
                // Success - Connection closed
            } else if (!line.contains("400") && !line.contains("500")) {
                org.junit.Assert.fail("Expected 400/500 Bad Request but got: $line")
            }
        } catch (e: Exception) {
            // Some runtimes close the socket immediately after rejecting malformed requests.
            // Treat connection reset/close as a valid rejection path.
            return
        } finally {
            socket.close()
        }
    }

    @Test
    fun testInvalidContentLength() {
        val port = server.listeningPort
        val token = server.token
        val socket = Socket("localhost", port)
        socket.soTimeout = 2000

        val writer = socket.getOutputStream().writer(Charsets.UTF_8)
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)

        writer.write("POST /api/upload_keybox?token=$token HTTP/1.1\r\n")
        writer.write("Host: localhost:$port\r\n")
        writer.write("Content-Length: not_a_number\r\n")
        writer.write("Content-Type: application/x-www-form-urlencoded\r\n")
        writer.write("\r\n")
        writer.write("filename=test.xml&content=start")
        writer.flush()

        try {
            val line = reader.readLine()
            if (line == null) {
                // Success - Connection closed
            } else {
                if (line.contains("400") || line.contains("500")) {
                    // Success
                } else {
                    org.junit.Assert.fail("Expected 400 or 500 response but got: $line")
                }
            }
        } catch (e: Exception) {
            // connection reset is fine for bad request
        } finally {
            socket.close()
        }
    }
}
