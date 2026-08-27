package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class WebServerRateLimitTest {
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
                ) {}

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
    fun testRateLimit() {
        val port = server.listeningPort
        val token = server.token
        val limit = 100 // Hardcoded limit we intend to implement

        // Debug first request
        for (i in 1..limit + 5) {
            val url = URL("http://localhost:$port/api/config?token=$token")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000

            val code =
                try {
                    conn.responseCode
                } catch (e: Exception) {
                    fail("Request $i failed with exception: ${e.message}")
                    return
                } finally {
                    conn.disconnect()
                }

            if (i <= limit) {
                if (code != 200) {
                    fail("Request $i failed. Code: $code. Expected 200.")
                }
            } else {
                if (code != 400) {
                    fail("Request $i passed limit. Code: $code. Expected 400.")
                }
            }
        }
    }
}
