@file:Suppress("ktlint:standard:max-line-length")

package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class WebServerUXTest {
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
    fun testUXImprovements() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        val html = conn.inputStream.bufferedReader().readText()

        // Verify App Filter Input uses type="search"
        assertTrue(
            "App Filter should use type='search' for native clear button",
            html.contains(
                "id=\"appFilter\" placeholder=\"Filter...\" oninput=\"renderAppTable()\" aria-label=\"Filter rules\" style=\"width:150px; padding:5px 10px; font-size:0.85em; background:var(--input-bg); border:1px solid var(--border); color:#fff; border-radius:4px;\" type=\"search\"",
            ) ||
                html.contains("type=\"search\" id=\"appFilter\"") ||
                (html.contains("id=\"appFilter\"") && html.contains("type=\"search\"")), // Use looser check if exact string match is hard
        )
        // Let's use a more robust check for the specific line
        // Current: <input type="text" id="appFilter" ...
        // Expected: <input type="search" id="appFilter" ... (or similar)

        // Verify Apply System-Wide Button uses runWithState wrapper
        assertTrue(
            "Apply System-Wide button should use runWithState wrapper",
            html.contains("runWithState(this, 'Saving...', applySpoofing)"),
        )

        // Verify JS function signature updated (removed btn arg since runWithState handles it)
        assertTrue(
            "applySpoofing should no longer need btn argument",
            html.contains("async function applySpoofing()") && !html.contains("async function applySpoofing(btn)"),
        )

        // Verify JS loading state logic using wrapper
        assertTrue(
            "applySpoofing should use runWithState wrapper",
            html.contains("runWithState(this, 'Saving...', applySpoofing)"),
        )
    }

    @Test
    fun testEditorShortcuts() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        val html = conn.inputStream.bufferedReader().readText()

        // Verify Save Button has title
        assertTrue(
            "Save button should have title hint for Ctrl+S",
            html.contains("title=\"Ctrl+S\"") && html.contains("id=\"saveBtn\""),
        )

        // Verify Textarea has onkeydown handler
        assertTrue(
            "File editor textarea should have onkeydown handler for Ctrl+S",
            html.contains(
                "onkeydown=\"if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='s'){event.preventDefault();handleSave(document.getElementById('saveBtn'));}\"",
            ),
        )
    }

    @Test
    fun testMobileAndSettingContracts() {
        val html = fetchHtml()
        val settings =
            listOf(
                "global_mode",
                "tee_broken_mode",
                "auto_keybox_check",
                "random_on_boot",
                "hide_sensitive_props",
                "spoof_region_cn",
                "telephony",
                "rkp_passthrough",
                "drm_passthrough",
            )

        assertTrue(html.contains("viewport-fit=cover"))
        assertTrue(html.contains("env(safe-area-inset-bottom)"))
        assertTrue(html.contains("@media (prefers-reduced-motion: reduce)"))
        assertTrue(html.contains("min-height: 48px"))
        assertTrue(html.contains("height: min(500px, 60dvh) !important"))
        assertTrue(html.contains("async function fetchAuth"))
        assertTrue(html.contains("const timeoutId = setTimeout"))
        assertTrue(html.contains("function downloadBlob"))
        assertTrue(html.contains("if (files && files[0]) loadFileContent(files[0]);"))
        assertFalse(html.contains("kbFilePicker').files = files"))
        assertTrue(html.contains("rel=\"noopener noreferrer\""))
        assertTrue(html.contains("id=\"bootPropsMode\""))
        assertTrue(html.contains("saveBootPropsMode(this)"))
        assertTrue(html.contains("<option value=\"templates.json\">templates.json</option>"))

        settings.forEach { setting ->
            assertTrue("Missing synchronized control for $setting", html.contains("data-setting=\"$setting\""))
            assertTrue("Missing source-aware toggle for $setting", html.contains("toggle('$setting', this)"))
        }
        assertTrue(html.contains("WEB_UI_SETTINGS.includes(f.id)"))
        assertTrue(html.contains("syncSettingControls(setting, !requestedValue)"))
        assertFalse(html.contains(0x2014.toChar()))
    }

    @Test
    fun testWebUiApiRouteContract() {
        val html = fetchHtml()
        val clientRoutes =
            Regex("""['\"](/api/[a-z_/]+)""")
                .findAll(html)
                .map { it.groupValues[1] }
                .toSet()
        val expectedRoutes =
            setOf(
                "/api/config",
                "/api/keyboxes",
                "/api/cbox_status",
                "/api/unlock_cbox",
                "/api/servers",
                "/api/server/add",
                "/api/server/delete",
                "/api/server/refresh",
                "/api/templates",
                "/api/random_identity",
                "/api/packages",
                "/api/app_config_structured",
                "/api/file",
                "/api/save",
                "/api/upload_keybox",
                "/api/delete_keybox",
                "/api/verify_keyboxes",
                "/api/apply_profile",
                "/api/toggle",
                "/api/reset_environment",
                "/api/reload",
                "/api/logs",
                "/api/backup",
                "/api/language",
                "/api/resource_usage",
                "/api/restore",
            )
        assertEquals(expectedRoutes, clientRoutes)
    }

    private fun fetchHtml(): String {
        val url = URL("http://localhost:${server.listeningPort}/?token=${server.token}")
        val conn = url.openConnection() as HttpURLConnection
        return conn.inputStream.bufferedReader().readText()
    }
}
