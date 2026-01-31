package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class WebServerHtmlTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: WebServer
    private lateinit var configDir: File

    @Before
    fun setUp() {
        Logger.setImpl(object : Logger.LogImpl {
            override fun d(tag: String, msg: String) {}
            override fun e(tag: String, msg: String) {}
            override fun e(tag: String, msg: String, t: Throwable?) {}
            override fun i(tag: String, msg: String) {}
        })
        configDir = tempFolder.newFolder("config")
        server = WebServer(0, configDir)
        server.start()
        CertHack.readFromXml(null)
    }

    @After
    fun tearDown() {
        server.stop()
        CertHack.readFromXml(null)
    }

    @Test
    fun testHtmlAccessibility() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        val html = conn.inputStream.bufferedReader().readText()

        // Verify labels for checkboxes
        assertTrue("Missing label for global_mode", html.contains("<label for=\"global_mode\""))
        assertTrue("Missing label for tee_broken_mode", html.contains("<label for=\"tee_broken_mode\""))
        assertTrue("Missing label for rkp_bypass", html.contains("<label for=\"rkp_bypass\""))

        // Verify aria-labels
        assertTrue("Missing aria-label for fileSelector", html.contains("aria-label=\"Select configuration file\""))
        assertTrue("Missing aria-label for editor", html.contains("aria-label=\"Configuration editor\""))

        // Verify save button ID
        assertTrue("Missing id for saveBtn", html.contains("id=\"saveBtn\""))

        // Verify reload button ID
        assertTrue("Missing id for reloadBtn", html.contains("id=\"reloadBtn\""))
    }
}
