package cleveres.tricky.cleverestech

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
        ManagedOpaqueKeyOracle.readFromXml(null)
    }

    @After
    fun tearDown() {
        server.stop()
        ManagedOpaqueKeyOracle.readFromXml(null)
    }

    @Test
    fun testKeyboxListUX() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        val uxSource =
        listOf(
            File("module/template/webroot/ux.js"),
            File("../module/template/webroot/ux.js"),
        ).first { it.isFile }.readText()
    val html = conn.inputStream.bufferedReader().readText() + "\n" + uxSource

        assertTrue(
            "HTML should contain Stored Keyboxes panel",
            html.contains("<h3>Stored Keyboxes</h3>") &&
                html.contains("<div id=\"storedKeyboxesList\""),
        )
        assertTrue(
            "loadKeyboxes function should exist",
            html.contains("async function loadKeyboxes(options = {})"),
        )
        assertTrue(
            "init function should call loadKeyboxes",
            html.contains("loadKeyboxes();"),
        )
        assertTrue(
            "uploadKeybox should check res.ok and call loadKeyboxes",
            html.contains("if (!res.ok) {") &&
                html.contains("loadKeyboxes();") &&
                html.contains("notify('Error: ' + msg, 'error');"),
        )
        assertTrue(
            "verifyKeyboxes should check res.ok",
            html.contains("if (!res.ok) throw new Error(await res.text());") ||
                html.contains("if (!res.ok) {"),
        )
        assertTrue("Stored list must use source-aware inventory", html.contains("/api/keybox_inventory"))
        assertTrue("Stored list must support bulk deletion", html.contains("/api/delete_keyboxes"))
        assertTrue("Stored list must page five entries at a time", html.contains("const PAGE_SIZE = 5;"))
        assertTrue("Verification must display certificate #3 serial", html.contains("Certificate #3 serial"))
    }
}
