package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class SensitiveExposureTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: WebServer
    private lateinit var configDir: File

    @Before
    fun setUp() {
        // Suppress logging
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

        // Create a dummy keybox.xml
        File(configDir, "keybox.xml").writeText("<secret>data</secret>")

        server = WebServer(0, configDir)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun testKeyboxExposure() {
        val port = server.listeningPort
        val token = server.token // Valid token
        val url = URL("http://localhost:$port/api/file?filename=keybox.xml&token=$token")

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        val responseCode = conn.responseCode

        // This assertion expects 400 (Bad Request), meaning access is DENIED.
        // If the vulnerability exists, it will return 200 (OK), failing this test.
        assertEquals("Should deny access to keybox.xml", 400, responseCode)
    }
}
