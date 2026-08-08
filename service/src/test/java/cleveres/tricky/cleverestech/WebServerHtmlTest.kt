@file:Suppress("ktlint:standard:max-line-length")

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

class WebServerHtmlTest {
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
    fun testHtmlStructure() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        val html = conn.inputStream.bufferedReader().readText()

        // Verify Title and Badge
        assertTrue("Missing Title", html.contains("<h1>CleveresTricky"))

        // Verify Tabs
        assertTrue("Missing Dashboard Tab", html.contains("id=\"tab_dashboard\""))
        assertTrue("Missing Spoof Tab", html.contains("id=\"tab_spoof\""))
        assertTrue("Missing Apps Tab", html.contains("id=\"tab_apps\""))

        // Verify Dynamic Island
        assertTrue("Missing Island Container", html.contains("class=\"island-container\""))
        assertTrue("Missing Island", html.contains("id=\"island\""))
        assertTrue("Missing Island Accessibility", html.contains("role=\"status\" aria-live=\"polite\""))
        assertTrue("Missing notify function", html.contains("function notify(msg, type = 'normal')"))
        assertTrue("Missing Remove Button Accessibility", html.contains("aria-label=\"Remove rule for ${'$'}{rule.package}\""))

        // Verify Random Logic
        assertTrue("Missing Identifier Header", html.contains("<h3>Attestation and Telephony Identifiers</h3>"))
        assertTrue("Missing IMEI Input", html.contains("id=\"inputImei\""))
        assertTrue("Missing IMEI Label", html.contains("<label for=\"inputImei\""))
        assertTrue("Missing IMEI 2 Label", html.contains("<label for=\"inputImei2\""))
        assertTrue("Missing MEID Label", html.contains("<label for=\"inputMeid\""))
        assertTrue("Missing MEID 2 Label", html.contains("<label for=\"inputMeid2\""))
        assertTrue("Missing IMSI Label", html.contains("<label for=\"inputImsi\""))
        assertTrue("Missing IMSI 2 Label", html.contains("<label for=\"inputImsi2\""))
        assertTrue("Missing ICCID Label", html.contains("<label for=\"inputIccid\""))
        assertTrue("Missing ICCID 2 Label", html.contains("<label for=\"inputIccid2\""))
        assertTrue("Missing phone number Label", html.contains("<label for=\"inputPhoneNumber\""))
        assertTrue("Missing phone number 2 Label", html.contains("<label for=\"inputPhoneNumber2\""))
        assertTrue("Missing Serial Label", html.contains("<label for=\"inputSerial\""))
        assertTrue("Missing app-facing scope notice", html.contains("id=\"identityScope\""))
        assertTrue("Missing Generate Random Button", html.contains("generateRandomIdentity"))
        assertTrue("Missing Telephony Toggle", html.contains("id=\"telephony\""))
        assertTrue("Missing master Spoof Engine Toggle", html.contains("id=\"spoof_enabled\""))
        assertTrue("Missing build identity Toggle", html.contains("id=\"spoof_build_identity\""))

        // Verify Apps Logic
        assertTrue("Missing App Package Input", html.contains("id=\"appPkg\""))
        assertTrue("Missing App Package Label", html.contains("<label for=\"appPkg\""))
        assertTrue("Missing App Template Label", html.contains("<label for=\"appTemplate\""))
        assertTrue("Missing App Keybox Label", html.contains("<label for=\"appKeybox\""))
        assertTrue("Missing Remove Button Accessibility", html.contains("aria-label=\"Remove rule for \${rule.package}\""))
        assertTrue("Missing Empty State", html.contains("No active rules"))

        // Verify Apps Search Filter
        assertTrue("Missing App Filter Input", html.contains("id=\"appFilter\""))
        assertTrue("Missing App Filter ARIA Label", html.contains("aria-label=\"Filter rules\""))
        assertTrue("Missing App Filter JS Logic", html.contains("rule.package.toLowerCase().includes(filter)"))

        // Verify Editor
        assertTrue("Missing File Selector", html.contains("id=\"fileSelector\""))

        // Verify Keybox
        assertTrue("Missing Keybox File Picker", html.contains("id=\"kbFilePicker\""))
        assertTrue("Missing Verify Button", html.contains("verifyKeyboxes"))
    }

    @Test
    fun testAccessibilityAttributes() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        val html = conn.inputStream.bufferedReader().readText()

        // Verify Tabs Accessibility
        assertTrue("Missing Tab Role", html.contains("role=\"tab\""))
        assertTrue("Missing Active Tabindex", html.contains("tabindex=\"0\""))
        assertTrue("Missing Inactive Tabindex", html.contains("tabindex=\"-1\""))
        assertTrue("Missing Aria Selected", html.contains("aria-selected=\"true\""))
        assertTrue("Missing Key Handler", html.contains("onkeydown=\"handleTabNavigation"))
        assertTrue("Missing Tab Controls", html.contains("aria-controls=\"dashboard\""))

        // Verify Panels Accessibility
        assertTrue("Missing Tabpanel Role", html.contains("role=\"tabpanel\""))
        assertTrue("Missing Aria Labelledby", html.contains("aria-labelledby=\"tab_dashboard\""))

        // Verify JS helpers
        assertTrue("Missing handleTabNavigation JS", html.contains("function handleTabNavigation(e, id)"))
        assertTrue("Missing aria-selected update in switchTab", html.contains("setAttribute('aria-selected'"))
        assertTrue("Missing tabindex update in switchTab", html.contains("setAttribute('tabindex'"))

        // Verify Numeric Inputs
        assertTrue(
            "IMEI missing inputmode=numeric",
            html.contains("id=\"inputImei\" placeholder=\"35...\" maxlength=\"15\"") &&
                html.contains("inputmode=\"numeric\" enterkeyhint=\"next\""),
        )
        assertTrue(
            "IMSI missing inputmode=numeric",
            html.contains("id=\"inputImsi\" placeholder=\"Subscriber identity\" maxlength=\"16\""),
        )
        assertTrue(
            "ICCID missing inputmode=numeric",
            html.contains("id=\"inputIccid\" placeholder=\"SIM card identity\" maxlength=\"22\""),
        )

        // Verify Autocapitalize Inputs
        assertTrue(
            "Serial missing autocapitalize=characters",
            html.contains(
                "id=\"inputSerial\" placeholder=\"Device serial\" maxlength=\"64\" style=\"font-family:monospace;\" enterkeyhint=\"done\" autocapitalize=\"characters\"",
            ),
        )

        // Verify Accessibility Labels for Textareas
        assertTrue(
            "File Editor missing aria-label",
            html.contains(
                "id=\"fileEditor\" style=\"height:500px; font-family:monospace; margin-top:10px; line-height:1.4;\" aria-label=\"File Content\"",
            ),
        )
        assertTrue(
            "Keybox Content missing aria-label",
            html.contains(
                "id=\"kbContent\" placeholder=\"Paste Keybox XML Content Here\" maxlength=\"5242880\" style=\"height:100px; font-family:monospace; font-size:0.8em; margin-bottom:10px;\" aria-label=\"Keybox XML Content\"",
            ),
        )

        // Verify Keybox Filename Label and File Picker Accessibility
        assertTrue("Keybox Filename missing label", html.contains("<label for=\"kbFilePicker\""))
        assertTrue(
            "Keybox File Picker missing aria-label",
            html.contains(
                "id=\"kbFilePicker\" style=\"display:none\" onchange=\"loadFileContent(this)\" onclick=\"event.stopPropagation(); this.value = null\" aria-label=\"Upload Keybox File\"",
            ),
        )
        assertTrue(
            "File Selector missing aria-label",
            html.contains("id=\"fileSelector\" onchange=\"loadFile()\" style=\"width:70%;\" aria-label=\"Select file to edit\""),
        )

        // Verify Drop Zone Accessibility
        assertTrue("Drop Zone missing accessibility attributes", html.contains("id=\"dropZone\" role=\"button\" tabindex=\"0\""))
        assertTrue(
            "Drop Zone missing keyboard handler",
            html.contains(
                "onkeydown=\"if(event.key==='Enter'||event.key===' '){event.preventDefault(); document.getElementById('kbFilePicker').click();}\"",
            ),
        )
    }

    @Test
    fun testAccessibilityImprovements() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        val html = conn.inputStream.bufferedReader().readText()

        // Verify Focus Visible CSS
        assertTrue(
            "Missing focus-visible CSS",
            html.contains("input[type=\"checkbox\"].toggle:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }"),
        )
        assertTrue(
            "Missing disabled toggle CSS",
            html.contains("input[type=\"checkbox\"].toggle:disabled { opacity: 0.5; cursor: not-allowed; }"),
        )

        // Verify Label Cursor CSS
        assertTrue("Missing label cursor CSS", html.contains("label { font-size: 0.95em; color: #BBB; cursor: pointer; }"))

        assertTrue("Safe mode label is missing", html.contains("Disable Certificate Substitution (Safe Mode)"))
    }

    @Test
    fun testEditorDirtyStateProtection() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/?token=$token")
        val conn = url.openConnection() as HttpURLConnection
        val html = conn.inputStream.bufferedReader().readText()

        // Verify HTML Attributes
        assertTrue("Missing oninput handler", html.contains("oninput=\"editorUnsavedBypass = false; updateSaveButtonState()\""))
        assertTrue("Missing handleSave in onkeydown", html.contains("handleSave(document.getElementById('saveBtn'))"))
        assertTrue("Missing handleSave in onclick", html.contains("onclick=\"handleSave(this)\""))

        // Verify JavaScript Logic
        assertTrue("Missing originalContent variable", html.contains("let originalContent = '';"))
        assertTrue("Missing dirty state check", html.contains("if (currentFile && editor.value !== originalContent)"))
        assertTrue("Missing notify alert", html.contains("notify('You have unsaved changes"))
        assertTrue("Missing updateSaveButtonState function", html.contains("function updateSaveButtonState()"))
        assertTrue("Missing visual indicator logic", html.contains("btn.innerText = 'Save *';"))
    }
}
