package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class WebServerHeadersTest {
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
    fun testSecurityHeadersPresent() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connect()

        assertEquals(200, conn.responseCode)

        // Verify Headers
        val csp = conn.getHeaderField("Content-Security-Policy")
        val contentTypeOptions = conn.getHeaderField("X-Content-Type-Options")
        val frameOptions = conn.getHeaderField("X-Frame-Options")
        val referrerPolicy = conn.getHeaderField("Referrer-Policy")

        assertNotNull("CSP header missing", csp)
        assertTrue("CSP incorrect: $csp", csp.contains("default-src 'self'"))

        assertEquals("nosniff", contentTypeOptions)
        assertEquals("DENY", frameOptions)
        assertEquals("no-referrer", referrerPolicy)
    }

    private fun assertTrue(
        message: String,
        condition: Boolean,
    ) {
        if (!condition) throw AssertionError(message)
    }
}
