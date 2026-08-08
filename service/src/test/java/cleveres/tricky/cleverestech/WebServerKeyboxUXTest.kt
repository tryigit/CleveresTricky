package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class WebServerKeyboxUXTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: WebServer
    private lateinit var configDir: File

    @Before
    fun setUp() {
        // Mock Logger to prevent spam
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
        CertHack.readFromXml(null)
    }

    @After
    fun tearDown() {
        server.stop()
        CertHack.readFromXml(null)
    }

    @Test
    fun testKeyboxListUX() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        val html = conn.inputStream.bufferedReader().readText()

        // 1. Verify "Stored Keyboxes" panel exists
        assertTrue(
            "HTML should contain Stored Keyboxes panel",
            html.contains("<h3>Stored Keyboxes</h3>") &&
                html.contains("<div id=\"storedKeyboxesList\""),
        )

        // 2. Verify loadKeyboxes function exists
        assertTrue(
            "loadKeyboxes function should exist",
            html.contains("async function loadKeyboxes()"),
        )

        // 3. Verify init calls loadKeyboxes
        assertTrue(
            "init function should call loadKeyboxes",
            html.contains("loadKeyboxes();"),
        )

        // 4. Verify uploadKeybox handles errors and reloads list
        assertTrue(
            "uploadKeybox should check res.ok and call loadKeyboxes",
            html.contains("if (!res.ok) {") &&
                html.contains("loadKeyboxes();") &&
                html.contains("notify('Error: ' + msg, 'error');"),
        )

        // 5. Verify verifyKeyboxes handles errors
        assertTrue(
            "verifyKeyboxes should check res.ok",
            html.contains("if (!res.ok) throw new Error(await res.text());") ||
                html.contains("if (!res.ok) {"),
        )
    }
}
