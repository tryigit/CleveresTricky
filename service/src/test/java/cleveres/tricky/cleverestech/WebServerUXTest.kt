@file:Suppress("ktlint:standard:max-line-length")

package cleveres.tricky.cleverestech

import org.json.JSONObject
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
        ManagedOpaqueKeyOracle.readFromXml(null)
    }

    @After
    fun tearDown() {
        server.stop()
        ManagedOpaqueKeyOracle.readFromXml(null)
    }

    @Test
    fun testUXImprovements() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        val html = conn.inputStream.bufferedReader().readText()

        assertTrue(
            "App Filter should use type='search' for native clear button",
            html.contains(
                "id=\"appFilter\" placeholder=\"Filter...\" oninput=\"renderAppTable()\" aria-label=\"Filter rules\" style=\"width:150px; padding:5px 10px; font-size:0.85em; background:var(--input-bg); border:1px solid var(--border); color:#fff; border-radius:4px;\" type=\"search\"",
            ) ||
                html.contains("type=\"search\" id=\"appFilter\"") ||
                (html.contains("id=\"appFilter\"") && html.contains("type=\"search\"")),
        )
        assertTrue(
            "Apply System-Wide button should use runWithState wrapper",
            html.contains("runWithState(this, 'Saving...', applySpoofing)"),
        )
        assertTrue(
            "applySpoofing should no longer need btn argument",
            html.contains("async function applySpoofing()") && !html.contains("async function applySpoofing(btn)"),
        )
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

        assertTrue(
            "Save button should have title hint for Ctrl+S",
            html.contains("title=\"Ctrl+S\"") && html.contains("id=\"saveBtn\""),
        )
        assertTrue(
            "File editor textarea should have onkeydown handler for Ctrl+S",
            html.contains(
                "onkeydown=\"if((event.ctrlKey||event.metaKey)&amp;&amp;event.key.toLowerCase()==='s'){event.preventDefault();handleSave(document.getElementById('saveBtn'));}\"",
            ),
        )
    }

    @Test
    fun testMobileAndSettingContracts() {
        val html = fetchHtml()
        val retiredIdentitySettings =
            listOf(
                "spoof_enabled",
                "spoof_build_identity",
                "random_on_boot",
                "spoof_region_cn",
                "telephony",
            )
        val featureCenterSettings = listOf("global_mode", "auto_keybox_check", "drm_passthrough")
        val monitoredSettings = retiredIdentitySettings + featureCenterSettings

        assertTrue(html.contains("viewport-fit=cover"))
        assertTrue(html.contains("env(safe-area-inset-bottom)"))
        assertTrue(html.contains("@media (prefers-reduced-motion: reduce)"))
        assertTrue(html.contains("min-height: 48px"))
        assertTrue(html.contains("height: min(500px, 60dvh) !important"))
        assertTrue(html.contains("async function fetchAuth"))
        assertTrue(html.contains("window.CleveresBridge.fetch(url, options)"))
        assertTrue(html.contains("<script src=\"bridge.js?revision=14\"></script>"))
        assertTrue(html.contains("<script src=\"policy.js?revision=5\"></script>"))
        assertTrue(html.contains("function downloadBlob"))
        assertTrue(html.contains("if (files && files[0]) loadFileContent(files[0]);"))
        assertFalse(html.contains("kbFilePicker').files = files"))
        assertTrue(html.contains("rel=\"noopener noreferrer\""))
        assertFalse(html.contains("id=\"bootPropsMode\""))
        assertFalse(html.contains("data-setting=\"tee_broken_mode\""))
        assertFalse(html.contains("data-setting=\"hide_sensitive_props\""))
        assertFalse(html.contains("data-setting=\"rkp_passthrough\""))
        assertTrue(html.contains(".tabs { position: fixed; top: auto; bottom: 0;"))
        assertTrue(html.contains("<option value=\"templates.json\">templates.json</option>"))

        retiredIdentitySettings.forEach { setting ->
            assertFalse("Retired Identity toggle must not be rendered for $setting", html.contains("data-setting=\"$setting\""))
            assertFalse("Retired source-aware toggle must not be rendered for $setting", html.contains("toggle('$setting', this)"))
        }
        assertFalse("Retired Identity Controls panel must not be rendered", html.contains("<h3>Identity Controls</h3>"))
        assertFalse("Legacy toggle class must not be rendered", html.contains("class=\"toggle\""))
        assertTrue("Remote Server automatic refresh must use the modern switch", html.contains("class=\"ct-switch\" id=\"srvAutoRefresh\""))
        featureCenterSettings.forEach { setting ->
            assertFalse("Duplicate legacy Feature Center control for $setting", html.contains("data-setting=\"$setting\""))
        }
        monitoredSettings.forEach { setting ->
            assertTrue("Missing resource monitor entry for $setting", html.contains("{ id: '$setting'"))
        }
        assertTrue(html.contains("WEB_UI_SETTINGS.includes(f.id)"))
        assertTrue(html.contains("syncSettingControls(setting, !requestedValue)"))
        assertTrue(html.contains("resourceUsageController.abort()"))
        assertTrue(html.contains("signal: controller.signal"))
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
                "/api/identity",
                "/api/random_identity",
                "/api/auto_identity",
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

    @Test
    fun testDiagnosticsResourceContract() {
        val url = URL("http://localhost:${server.listeningPort}/api/resource_usage?token=${server.token}")
        val conn = url.openConnection() as HttpURLConnection
        val resource = JSONObject(conn.inputStream.bufferedReader().readText())

        assertEquals(BuildConfig.VERSION_NAME, resource.getString("version_name"))
        assertEquals(BuildConfig.VERSION_CODE, resource.getInt("version_code"))
        assertTrue(resource.has("native_runtime"))
        assertTrue(resource.has("keystore_interceptor_running"))
        assertTrue(resource.has("telephony_interceptor_running"))
        listOf("logs", "packages", "keyboxes", "servers", "identity").forEach { sensitiveCollection ->
            assertFalse(resource.has(sensitiveCollection))
        }
    }

    @Test
    fun testNativeFailureStageContract() {
        File(configDir, "native_runtime_status").writeText(
            """
            version=2
            state=failed
            pid=2147483647
            start_ticks=1
            entry=entry
            failure=symbol_resolution
            timestamp_ms=123
            """.trimIndent(),
        )
        val url = URL("http://localhost:${server.listeningPort}/api/resource_usage?token=${server.token}")
        val conn = url.openConnection() as HttpURLConnection
        val runtime = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONObject("native_runtime")

        assertEquals("failed", runtime.getString("state"))
        assertEquals("symbol_resolution", runtime.getString("failure"))
        assertFalse(runtime.getBoolean("alive"))
    }

    private fun fetchHtml(): String {
        val url = URL("http://localhost:${server.listeningPort}/?token=${server.token}")
        val conn = url.openConnection() as HttpURLConnection
        return conn.inputStream.bufferedReader().readText()
    }
}
