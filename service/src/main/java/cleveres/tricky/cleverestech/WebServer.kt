package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class WebServer(port: Int, private val configDir: File = File("/data/adb/cleverestricky")) : NanoHTTPD("127.0.0.1", port) {

    val token = UUID.randomUUID().toString()

    private fun readFile(filename: String): String {
        return try {
            File(configDir, filename).readText()
        } catch (e: Exception) {
            ""
        }
    }

    private fun saveFile(filename: String, content: String): Boolean {
        return try {
            File(configDir, filename).writeText(content)
            // Ensure proper permissions
            // File(configDir, filename).setReadable(true, true) // 600 or 644? Customize.sh sets 600.
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun fileExists(filename: String): Boolean {
        return File(configDir, filename).exists()
    }

    private fun toggleFile(filename: String, enable: Boolean): Boolean {
        val f = File(configDir, filename)
        return try {
            if (enable) {
                if (!f.exists()) f.createNewFile()
            } else {
                if (f.exists()) f.delete()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val params = session.parms

        // Simple Token Auth
        val requestToken = params["token"]
        if (!MessageDigest.isEqual(token.toByteArray(), (requestToken ?: "").toByteArray())) {
             return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized")
        }

        if (uri == "/api/config" && method == Method.GET) {
            val config = StringBuilder("{")
            config.append("\"global_mode\": ${fileExists("global_mode")},")
            config.append("\"tee_broken_mode\": ${fileExists("tee_broken_mode")},")
            config.append("\"rkp_bypass\": ${fileExists("rkp_bypass")},")
            config.append("\"auto_beta\": ${fileExists("auto_beta_fetch")},")
            // For file contents, we might want to load them on demand or include them here.
            // Including them might be heavy if large. Let's make separate endpoints or just load them if requested?
            // For simplicity, let's load text files separately or all at once?
            // Let's load them via separate API calls or just embed them in the HTML initial load?
            // Let's return the toggles here.
            config.append("\"files\": [\"keybox.xml\", \"target.txt\", \"security_patch.txt\", \"spoof_build_vars\"],")
            config.append("\"keybox_count\": ${CertHack.getKeyboxCount()},")
            config.append("\"templates\": [")
            Config.getTemplateNames().forEachIndexed { index, name ->
                if (index > 0) config.append(",")
                config.append("\"$name\"")
            }
            config.append("]")
            config.append("}")
            return newFixedLengthResponse(Response.Status.OK, "application/json", config.toString())
        }

        if (uri == "/api/file" && method == Method.GET) {
            val filename = params["filename"]
            if (filename != null && isValidFilename(filename)) {
                return newFixedLengthResponse(Response.Status.OK, "text/plain", readFile(filename))
            }
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid filename")
        }

        if (uri == "/api/save" && method == Method.POST) {
             val map = HashMap<String, String>()
             try { session.parseBody(map) } catch(e:Exception){}
             val filename = session.parms["filename"]
             // nanohttpd puts body content in map["postData"] usually, or mix params.
             // When parsing body, map contains temp file paths for uploads, but for form data it puts in parms?
             // Actually parseBody populates map with temp files, and parms with fields.
             val content = session.parms["content"] // This might be limited in size?
             // For large content (keybox), we might need to read from map if it's treated as file?
             // But usually standard form post puts it in parms.
             // Let's assume text/plain body or form-url-encoded.

             // Better way: read body directly if it's just raw text?
             // Let's stick to simple form post or json.
             // If I use fetch with JSON body, I need to parse it.
             // Let's use simple x-www-form-urlencoded.

             if (filename != null && isValidFilename(filename) && content != null) {
                 if (saveFile(filename, content)) {
                     return newFixedLengthResponse(Response.Status.OK, "text/plain", "Saved")
                 }
             }
             return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
        }

        if (uri == "/api/toggle" && method == Method.POST) {
             val map = HashMap<String, String>()
             try { session.parseBody(map) } catch(e:Exception){}
             val setting = session.parms["setting"]
             val value = session.parms["value"]

             if (setting != null && value != null) {
                 if (toggleFile(setting, value.toBoolean())) {
                     return newFixedLengthResponse(Response.Status.OK, "text/plain", "Toggled")
                 }
             }
             return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
        }

        if (uri == "/api/reload" && method == Method.POST) {
             try {
                File(configDir, "target.txt").setLastModified(System.currentTimeMillis())
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "Reloaded")
             } catch(e: Exception) {
                 return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
             }
        }

        if (uri == "/api/fetch_beta" && method == Method.POST) {
             try {
                // Trigger fetch via shell script or direct call if possible.
                // Since this runs as root/system, we can maybe trigger the service directly or run the script.
                // For simplicity, let's just touch a trigger file or run the command.
                // But BetaFetcher is in the same process potentially? No, this is the service.
                // BetaFetcher is an object in the same package.
                val result = BetaFetcher.fetchAndApply(null)
                if (result.success) {
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", "Success: ${result.profile?.model}")
                } else {
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed: ${result.error}")
                }
             } catch(e: Exception) {
                 return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
             }
        }

        if (uri == "/" || uri == "/index.html") {
            return newFixedLengthResponse(Response.Status.OK, "text/html", getHtml())
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }

    private fun isValidFilename(name: String): Boolean {
        return name in setOf("keybox.xml", "target.txt", "security_patch.txt", "spoof_build_vars")
    }

    private fun getHtml(): String {
        return """
<!DOCTYPE html>
<html>
<head>
    <title>CleveresTricky</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        body { background-color: #121212; color: white; font-family: sans-serif; padding: 20px; max-width: 800px; margin: 0 auto; }
        h1 { text-align: center; }
        .section { margin-bottom: 20px; padding: 15px; background-color: #1e1e1e; border-radius: 8px; }
        .row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
        button { padding: 10px 20px; border-radius: 4px; border: none; cursor: pointer; font-size: 16px; }
        .btn-primary { background-color: white; color: black; }
        .btn-success { background-color: #2e7d32; color: white; }
        textarea { width: 100%; height: 200px; background-color: #2d2d2d; color: white; border: 1px solid #444; border-radius: 4px; padding: 10px; font-family: monospace; }
        select { padding: 10px; background-color: #2d2d2d; color: white; border: 1px solid #444; border-radius: 4px; width: 100%; margin-bottom: 10px; }
        input[type="checkbox"] { transform: scale(1.5); }
        .status { text-align: center; color: #888; margin-top: 10px; }
    </style>
</head>
<body>
    <h1>CleveresTricky <span style="font-size: 0.5em; color: gray;">BETA</span></h1>

    <div class="section">
        <div class="row"><label for="global_mode" style="flex-grow: 1; padding: 10px 0;">Global Mode</label><input type="checkbox" id="global_mode" onchange="toggle('global_mode')"></div>
        <div class="row"><label for="tee_broken_mode" style="flex-grow: 1; padding: 10px 0;">TEE Broken Mode</label><input type="checkbox" id="tee_broken_mode" onchange="toggle('tee_broken_mode')"></div>
        <div class="row"><label for="rkp_bypass" style="flex-grow: 1; padding: 10px 0;">RKP Bypass (Strong Integrity)</label><input type="checkbox" id="rkp_bypass" onchange="toggle('rkp_bypass')"></div>
        <div class="row"><label for="auto_beta_fetch" style="flex-grow: 1; padding: 10px 0;">Auto Pixel Beta Fetch (Daily)</label><input type="checkbox" id="auto_beta_fetch" onchange="toggle('auto_beta_fetch')"></div>
        <div class="row" style="margin-top: 10px; justify-content: flex-end;">
            <button onclick="fetchBetaNow()" class="btn-success" style="font-size: 14px; padding: 8px 16px;">Fetch Beta Fingerprint Now</button>
        </div>
        <div class="status" id="keyboxStatus" style="text-align: left; margin-top: 10px; font-weight: bold;">Keybox Status: Loading...</div>
    </div>

    <div class="section">
        <select id="fileSelector" onchange="loadFile()" aria-label="Select configuration file">
            <option value="keybox.xml">keybox.xml</option>
            <option value="target.txt">target.txt</option>
            <option value="security_patch.txt">security_patch.txt</option>
            <option value="spoof_build_vars">spoof_build_vars</option>
        </select>

        <div id="templateSection" style="display:none; margin-bottom: 10px;">
             <div class="row">
                <select id="templateSelector" style="flex-grow: 1; margin-right: 10px;" aria-label="Select device template">
                    <option value="" disabled selected>Select a device template...</option>
                </select>
                <button onclick="applyTemplate()" class="btn-primary">Load Template</button>
             </div>
        </div>

        <textarea id="editor" aria-label="Configuration editor"></textarea>
        <div class="row" style="margin-top: 10px;">
            <button id="saveBtn" class="btn-primary" onclick="saveFile()">Save File</button>
        </div>
    </div>

    <div class="section" style="text-align: center;">
        <button id="reloadBtn" class="btn-success" onclick="reloadConfig()">Reload Config</button>
    </div>

    <script>
        const baseUrl = '/api';
        const urlParams = new URLSearchParams(window.location.search);
        const token = urlParams.get('token');

        function getAuthUrl(path) {
            return path + (path.includes('?') ? '&' : '?') + 'token=' + token;
        }

        async function init() {
            if (!token) {
                document.body.innerHTML = '<h1>Unauthorized</h1><p>Missing token.</p>';
                return;
            }
            const res = await fetch(getAuthUrl(baseUrl + '/config'));
            if (!res.ok) {
                alert('Failed to load config: ' + res.status);
                return;
            }
            const data = await res.json();
            document.getElementById('global_mode').checked = data.global_mode;
            document.getElementById('tee_broken_mode').checked = data.tee_broken_mode;
            document.getElementById('tee_broken_mode').checked = data.tee_broken_mode;
            document.getElementById('rkp_bypass').checked = data.rkp_bypass;
            document.getElementById('auto_beta_fetch').checked = data.auto_beta;

            const count = data.keybox_count;
            const statusEl = document.getElementById('keyboxStatus');
            if (count > 0) {
                statusEl.innerText = 'Keybox Status: Loaded (' + count + ' keys)';
                statusEl.style.color = '#4caf50';
            } else {
                statusEl.innerText = 'Keybox Status: Not Loaded (Check logs/format)';
                statusEl.style.color = '#f44336';
            }

            const tmplSel = document.getElementById('templateSelector');
            if (data.templates) {
                data.templates.forEach(t => {
                    const opt = document.createElement('option');
                    opt.value = t;
                    opt.innerText = t;
                    tmplSel.appendChild(opt);
                });
            }

            loadFile();
        }

        async function toggle(setting) {
            const val = document.getElementById(setting).checked;
            await fetch(getAuthUrl(baseUrl + '/toggle'), {
                method: 'POST',
                headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                body: 'setting=' + setting + '&value=' + val
            });
        }

        async function loadFile() {
            const filename = document.getElementById('fileSelector').value;

            const tmplSection = document.getElementById('templateSection');
            if (filename === 'spoof_build_vars') {
                tmplSection.style.display = 'block';
            } else {
                tmplSection.style.display = 'none';
            }

            const res = await fetch(getAuthUrl(baseUrl + '/file?filename=' + filename));
            const text = await res.text();
            document.getElementById('editor').value = text;
        }

        function applyTemplate() {
            const tmpl = document.getElementById('templateSelector').value;
            if (!tmpl) return;
            const editor = document.getElementById('editor');
            const current = editor.value;

            if (current.includes('TEMPLATE=')) {
                if (!confirm('This file already contains a TEMPLATE directive. Replace it?')) return;
                editor.value = current.replace(/TEMPLATE=[^\n]*/, 'TEMPLATE=' + tmpl);
            } else {
                editor.value = 'TEMPLATE=' + tmpl + '\n' + current;
            }
        }

        async function saveFile() {
            const btn = document.getElementById('saveBtn');
            const originalText = btn.innerText;
            btn.disabled = true;
            btn.innerText = 'Saving...';

            try {
                const filename = document.getElementById('fileSelector').value;
                const content = document.getElementById('editor').value;
                const params = new URLSearchParams();
                params.append('filename', filename);
                params.append('content', content);

                const res = await fetch(getAuthUrl(baseUrl + '/save'), {
                    method: 'POST',
                    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                    body: params
                });

                if (res.ok) alert('Saved!');
                else alert('Failed to save');
            } catch (e) {
                alert('Error: ' + e);
            } finally {
                btn.disabled = false;
                btn.innerText = originalText;
            }
        }

        async function fetchBetaNow() {
            if (!confirm("This will overwrite your spoof_build_vars with the latest Pixel Beta fingerprint. Continue?")) return;
            const btn = event.target;
            const originalText = btn.innerText;
            btn.disabled = true;
            btn.innerText = 'Fetching...';
            
            try {
                const res = await fetch(getAuthUrl(baseUrl + '/fetch_beta'), { method: 'POST' });
                const text = await res.text();
                if (res.ok) {
                    alert(text);
                    loadFile(); // Reload editor if viewing var file
                } else {
                    alert(text);
                }
            } catch (e) {
                alert('Error: ' + e);
            } finally {
                btn.disabled = false;
                btn.innerText = originalText;
            }
        }

        async function reloadConfig() {
            const btn = document.getElementById('reloadBtn');
            const originalText = btn.innerText;
            btn.disabled = true;
            btn.innerText = 'Reloading...';

            try {
                const res = await fetch(getAuthUrl(baseUrl + '/reload'), { method: 'POST' });
                if (res.ok) {
                    btn.innerText = 'Reloaded!';
                    setTimeout(function() {
                        btn.innerText = originalText;
                        btn.disabled = false;
                    }, 2000);
                } else {
                    btn.innerText = 'Failed';
                    setTimeout(function() {
                        btn.innerText = originalText;
                        btn.disabled = false;
                    }, 2000);
                }
            } catch (e) {
                btn.innerText = 'Error';
                setTimeout(function() {
                    btn.innerText = originalText;
                    btn.disabled = false;
                }, 2000);
            }
        }

        init();
    </script>
</body>
</html>
        """.trimIndent()
    }
}
