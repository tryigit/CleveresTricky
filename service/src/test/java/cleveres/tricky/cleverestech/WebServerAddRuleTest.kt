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

class WebServerAddRuleTest {
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
        CertHack.readFromXml(null)
    }

    @After
    fun tearDown() {
        server.stop()
        CertHack.readFromXml(null)
    }

    @Test
    fun testAddRuleEnterKeySupport() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        val html = conn.inputStream.bufferedReader().readText()

        // 1. Verify appPkg has onkeydown for Enter
        assertTrue(
            "appPkg input should handle Enter key",
            html.contains("id=\"appPkg\"") && html.contains("onkeydown=\"if(event.key==='Enter') addAppRule()\""),
        )

        // 2. Verify appKeybox has onkeydown for Enter
        assertTrue(
            "appKeybox input should handle Enter key",
            html.contains("id=\"appKeybox\"") && html.contains("onkeydown=\"if(event.key==='Enter') addAppRule()\""),
        )

        // 3. Verify addAppRule JS has focus logic
        assertTrue(
            "addAppRule should focus input on error",
            html.contains("pkgInput.focus();"),
        )

        // 4. Verify addAppRule JS resets and focuses input on success
        assertTrue(
            "addAppRule should reset and focus input on success",
            html.contains("pkgInput.value = '';") &&
                html.contains("document.getElementById('appKeybox').value = '';") &&
                html.contains("pkgInput.focus();"),
        )

        // 5. Verify addAppRule JS notifies success
        assertTrue(
            "addAppRule should notify success",
            html.contains("notify(existingIdx !== -1 ? 'Rule Updated' : 'Rule Added');"),
        )
    }
}
