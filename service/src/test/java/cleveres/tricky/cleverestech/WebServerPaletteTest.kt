@file:Suppress("ktlint:standard:max-line-length")

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

class WebServerPaletteTest {
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
    fun testPaletteImprovements() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        val html = conn.inputStream.bufferedReader().readText()

        assertTrue(
            "CSS should include button:disabled styling",
            html.contains("button:disabled { opacity: 0.5; cursor: not-allowed; }") ||
                html.contains("textarea:disabled, input:disabled, select:disabled, button:disabled { opacity: 0.5; cursor: not-allowed; }"),
        )
        assertTrue(
            "Add Rule button should have ID and be disabled by default",
            html.contains("id=\"btnAddRule\"") && html.contains("disabled") && html.contains(">Add Rule</button>"),
        )
        assertTrue(
            "appPkg input should have oninput handler",
            html.contains("id=\"appPkg\"") && html.contains("toggleAddButton();"),
        )
        assertTrue(
            "toggleAddButton function should exist",
            html.contains("function toggleAddButton()"),
        )
        assertTrue(
            "addAppRule should call toggleAddButton to reset state",
            html.contains("toggleAddButton();"),
        )
    }

    @Test
    fun testSafetyAndReliability() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        val html = conn.inputStream.bufferedReader().readText()

        assertTrue(
            "removeAppRule should trigger requireConfirm dialog",
            html.contains("requireConfirm(removeButton, () => runWithState(removeButton") && html.contains("removeAppRule"),
        )
        assertTrue(
            "deleteKeybox should trigger requireConfirm dialog",
            html.contains("requireConfirm(remove, () => runWithState(remove") && html.contains("deleteKeybox"),
        )
        assertTrue(
            "saveAppConfig should check res.ok",
            html.contains(
                "if (res.ok) {",
            ) && html.contains("notify('App Config Saved');") && html.contains("notify('Save Failed: ' + txt, 'error');"),
        )
        assertTrue(
            "saveFile should check res.ok",
            html.contains(
                "if (res.ok) {",
            ) && html.contains("notify('File Saved');") && html.contains("notify('Save Failed: ' + txt, 'error');"),
        )
        assertTrue(
            "toggle should check res.ok",
            html.contains("if (!res.ok) {") &&
                html.contains("const message = await res.text();") &&
                html.contains("throw new Error('Server returned ' + res.status + ': ' + message);") &&
                html.contains("notify('Setting Updated');"),
        )
        assertTrue(
            "clearSpoofingInputs function should exist to clear all inputs",
            html.contains("function clearSpoofingInputs()"),
        )
        assertTrue(
            "previewTemplate should call clearSpoofingInputs",
            html.contains("clearSpoofingInputs();") && !html.contains("document.getElementById('inputImei').value = '';"),
        )
        assertTrue(
            "fileEditor should reset editorUnsavedBypass on input/click",
            html.contains("id=\"fileEditor\"") && html.contains("onclick=\"editorUnsavedBypass = false;\""),
        )
        assertTrue(
            "addAppRule should contain regex validation",
            html.contains("const pkgRegex = /^[a-zA-Z0-9_.*]+$/;") && html.contains("if (!pkgRegex.test(pkg))"),
        )
    }

    @Test
    fun testDropZoneUX() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        val html = conn.inputStream.bufferedReader().readText()

        assertTrue(
            "HTML should contain dropZoneContent div",
            html.contains("<div id=\"dropZoneContent\">"),
        )
        assertTrue(
            "processFile should update dropZoneContent",
            html.contains("const dz = document.getElementById('dropZoneContent');") &&
                html.contains(
                    "dz.innerHTML = '<div style=\"font-size: 1.2em; margin-bottom: 10px; color:var(--accent); font-weight:bold; display: flex; align-items: center; justify-content: center;\"><div class=\"inline-spinner\"></div>Uploading: ' + safeFileName + '...</div>';",
                ),
        )
        assertTrue(
            "processFile should update border color",
            html.contains("document.getElementById('dropZone').style.borderColor = 'var(--success)';"),
        )
        assertTrue(
            "resetDropZone function should exist",
            html.contains("function resetDropZone()"),
        )
        assertTrue(
            "resetDropZone should restore default content",
            html.contains("dz.innerHTML = '<div style=\"font-size: 1.5em; margin-bottom: 10px; color: #888;\">[ Drag &amp; Drop ]</div>"),
        )
        assertTrue(
            "uploadKeybox should call resetDropZone",
            html.contains("resetDropZone();"),
        )
    }

    @Test
    fun testPaletteUxImprovements() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        val html = conn.inputStream.bufferedReader().readText()

        assertTrue(
            "Clear All should trigger requireConfirm",
            html.contains("requireConfirm(btn, () => clearSpoofingInputs(), 'Confirm Clear')"),
        )
        assertTrue("Revert should trigger requireConfirm", html.contains("requireConfirm(btn, () => revertEditor(), 'Confirm Revert')"))
        assertTrue(
            "verifyKeyboxes should have inline spinner",
            html.contains("<div class=\"inline-spinner\"></div> Verifying... Please wait."),
        )
        assertTrue("Copy Logs button should exist", html.contains("Copy Logs</button>"))
        assertTrue(
            "Dashboard should have RKP and DRM status indicators",
            html.contains("id=\"status_rkp\"") && html.contains("id=\"status_drm\""),
        )
    }
}
