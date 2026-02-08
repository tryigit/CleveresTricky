package cleveres.tricky.cleverestech

import android.system.Os
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import cleveres.tricky.cleverestech.util.RandomUtils
import cleveres.tricky.cleverestech.util.SecureFile
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class WebServer(
    port: Int,
    private val configDir: File = File("/data/adb/cleverestricky"),
    private val permissionSetter: (File, Int) -> Unit = { f, m ->
        try {
            Os.chmod(f.absolutePath, m)
        } catch (t: Throwable) {
            Logger.e("failed to set permissions for ${f.name}", t)
        }
    }
) : NanoHTTPD("127.0.0.1", port) {

    val token = UUID.randomUUID().toString()
    private val MAX_UPLOAD_SIZE = 5 * 1024 * 1024L // 5MB

    private fun readFile(filename: String): String {
        return try {
            File(configDir, filename).readText()
        } catch (e: Exception) {
            ""
        }
    }

    private fun saveFile(filename: String, content: String): Boolean {
        return try {
            val f = File(configDir, filename)
            SecureFile.writeText(f, content)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun fileExists(filename: String): Boolean {
        return File(configDir, filename).exists()
    }

    private fun listKeyboxes(): List<String> {
        val keyboxDir = File(configDir, "keyboxes")
        if (keyboxDir.exists() && keyboxDir.isDirectory) {
            return keyboxDir.listFiles { _, name -> name.endsWith(".xml") }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
        }
        return emptyList()
    }

    private fun isValidSetting(name: String): Boolean {
        return name in setOf("global_mode", "tee_broken_mode", "rkp_bypass", "auto_beta_fetch", "auto_keybox_check", "random_on_boot", "drm_fix", "random_drm_on_boot")
    }

    private fun toggleFile(filename: String, enable: Boolean): Boolean {
        if (!isValidSetting(filename)) return false
        val f = File(configDir, filename)
        return try {
            if (enable) {
                if (!f.exists()) {
                    if (filename == "drm_fix") {
                        val content = "ro.netflix.bsp_rev=0\ndrm.service.enabled=true\nro.com.google.widevine.level=1\nro.crypto.state=encrypted\n"
                        SecureFile.writeText(f, content)
                    } else {
                        f.createNewFile()
                    }
                    permissionSetter(f, 384) // 0600
                }
            } else {
                if (f.exists()) f.delete()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun fetchTelegramCount(): String {
        return try {
            val url = URL("https://t.me/cleverestech")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            if (conn.responseCode == 200) {
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                val regex = java.util.regex.Pattern.compile("tgme_page_extra\">([0-9 ]+) members")
                val matcher = regex.matcher(html)
                if (matcher.find()) {
                    matcher.group(1)?.trim() ?: "Unknown"
                } else {
                    "Unknown"
                }
            } else {
                "Error: ${conn.responseCode}"
            }
        } catch (e: Exception) {
            "Error"
        }
    }

    @Suppress("DEPRECATION")
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val params = session.parms

        // Security: Enforce max upload size to prevent DoS
        if (method == Method.POST || method == Method.PUT) {
             val lenStr = session.headers["content-length"]
             if (lenStr != null) {
                  try {
                      val len = lenStr.toLong()
                      if (len > MAX_UPLOAD_SIZE) {
                           return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Payload too large")
                      }
                  } catch (e: Exception) {}
             } else {
                 return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Content-Length required")
             }
        }

        // Simple Token Auth
        val requestToken = params["token"]
        if (!MessageDigest.isEqual(token.toByteArray(), (requestToken ?: "").toByteArray())) {
             return secureResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized")
        }

        if (uri == "/api/config" && method == Method.GET) {
            val json = JSONObject()
            json.put("global_mode", fileExists("global_mode"))
            json.put("tee_broken_mode", fileExists("tee_broken_mode"))
            json.put("rkp_bypass", fileExists("rkp_bypass"))
            json.put("auto_beta_fetch", fileExists("auto_beta_fetch"))
            json.put("auto_keybox_check", fileExists("auto_keybox_check"))
            json.put("random_on_boot", fileExists("random_on_boot"))
            json.put("drm_fix", fileExists("drm_fix"))
            json.put("random_drm_on_boot", fileExists("random_drm_on_boot"))
            val files = JSONArray()
            files.put("keybox.xml")
            files.put("target.txt")
            files.put("security_patch.txt")
            files.put("spoof_build_vars")
            files.put("app_config")
            files.put("drm_fix")
            json.put("files", files)
            json.put("keybox_count", CertHack.getKeyboxCount())
            val templates = JSONArray()
            Config.getTemplateNames().forEach { name ->
                templates.put(name)
            }
            json.put("templates", templates)
            return secureResponse(Response.Status.OK, "application/json", json.toString())
        }

        // NEW: Get Keybox List
        if (uri == "/api/keyboxes" && method == Method.GET) {
            val keyboxes = listKeyboxes()
            val array = JSONArray(keyboxes)
            return secureResponse(Response.Status.OK, "application/json", array.toString())
        }

        // NEW: Get Templates List
        if (uri == "/api/templates" && method == Method.GET) {
            val templates = DeviceTemplateManager.listTemplates()
            val array = JSONArray()
            templates.forEach { t ->
                val obj = JSONObject()
                obj.put("id", t.id)
                obj.put("model", t.model)
                obj.put("manufacturer", t.manufacturer)
                obj.put("fingerprint", t.fingerprint)
                obj.put("securityPatch", t.securityPatch)
                array.put(obj)
            }
            return secureResponse(Response.Status.OK, "application/json", array.toString())
        }

        // NEW: Generate Random Identity
        if (uri == "/api/random_identity" && method == Method.GET) {
            val templates = DeviceTemplateManager.listTemplates()
            if (templates.isNotEmpty()) {
                val t = templates.random()
                val json = JSONObject()
                json.put("id", t.id)
                json.put("model", t.model)
                json.put("manufacturer", t.manufacturer)
                json.put("fingerprint", t.fingerprint)
                json.put("securityPatch", t.securityPatch)
                // Extras
                json.put("imei", RandomUtils.generateLuhn(15))
                json.put("imei2", RandomUtils.generateLuhn(15))
                json.put("serial", RandomUtils.generateRandomSerial(12))
                json.put("androidId", RandomUtils.generateRandomAndroidId())
                json.put("wifiMac", RandomUtils.generateRandomMac())
                json.put("btMac", RandomUtils.generateRandomMac())
                json.put("simCountryIso", RandomUtils.generateRandomSimIso())
                json.put("carrier", RandomUtils.generateRandomCarrier())
                // For Advanced/Global spoofing
                json.put("imsi", RandomUtils.generateLuhn(15))
                json.put("iccid", RandomUtils.generateLuhn(20))
                return secureResponse(Response.Status.OK, "application/json", json.toString())
            }
            return secureResponse(Response.Status.NOT_FOUND, "text/plain", "No templates found")
        }

        // NEW: Get Installed Packages
        if (uri == "/api/packages" && method == Method.GET) {
            return try {
                val p = Runtime.getRuntime().exec("pm list packages")
                val output = p.inputStream.bufferedReader().readText()
                val packages = output.lines()
                    .filter { it.startsWith("package:") }
                    .map { it.removePrefix("package:").trim() }
                    .sorted()
                val array = JSONArray(packages)
                secureResponse(Response.Status.OK, "application/json", array.toString())
            } catch (e: Exception) {
                secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to list packages")
            }
        }

        // NEW: Get Structured App Config
        if (uri == "/api/app_config_structured" && method == Method.GET) {
            val file = File(configDir, "app_config")
            val array = JSONArray()
            if (file.exists()) {
                file.useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank() && !line.startsWith("#")) {
                            val parts = line.trim().split(Regex("\\s+"))
                            if (parts.isNotEmpty()) {
                                val pkg = parts[0]
                                if (pkg.matches(Regex("^[a-zA-Z0-9_.*]+$"))) {
                                    val tmpl = if (parts.size > 1 && parts[1] != "null") parts[1] else ""
                                    val kb = if (parts.size > 2 && parts[2] != "null") parts[2] else ""

                                    // Security: Validate template and keybox to prevent XSS (Stored via manual file edit)
                                    val isTmplValid = tmpl.isEmpty() || tmpl.matches(Regex("^[a-zA-Z0-9_]+$"))
                                    val isKbValid = kb.isEmpty() || kb.matches(Regex("^[a-zA-Z0-9_.-]+$"))

                                    if (isTmplValid && isKbValid) {
                                        val obj = JSONObject()
                                        obj.put("package", pkg)
                                        obj.put("template", tmpl)
                                        obj.put("keybox", kb)
                                        array.put(obj)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return secureResponse(Response.Status.OK, "application/json", array.toString())
        }

        // NEW: Save Structured App Config
        if (uri == "/api/app_config_structured" && method == Method.POST) {
             val map = HashMap<String, String>()
             try { session.parseBody(map) } catch(e:Exception){}
             val jsonStr = session.parms["data"]

             if (jsonStr != null) {
                 try {
                     val array = JSONArray(jsonStr)
                     val sb = StringBuilder()
                     sb.append("# Generated by WebUI\n")
                     for (i in 0 until array.length()) {
                         val obj = array.getJSONObject(i)
                         val pkg = obj.getString("package")
                         val tmpl = obj.optString("template", "null").ifEmpty { "null" }
                         val kb = obj.optString("keybox", "null").ifEmpty { "null" }

                         // Validate package name (alphanumeric, dots, underscores, wildcards)
                         if (!pkg.matches(Regex("^[a-zA-Z0-9_.*]+$"))) {
                             return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid input: invalid characters")
                         }

                         // Validate template (alphanumeric, underscores)
                         if (tmpl != "null" && !tmpl.matches(Regex("^[a-zA-Z0-9_]+$"))) {
                             return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid input: invalid characters")
                         }

                         // Validate keybox (alphanumeric, dots, underscores, hyphens)
                         if (kb != "null" && !kb.matches(Regex("^[a-zA-Z0-9_.-]+$"))) {
                             return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid input: invalid characters")
                         }

                         if (pkg.contains(Regex("\\s")) || tmpl.contains(Regex("\\s")) || kb.contains(Regex("\\s"))) {
                             return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid input: whitespace not allowed")
                         }

                         sb.append("$pkg $tmpl $kb\n")
                     }
                     if (saveFile("app_config", sb.toString())) {
                         // Trigger reload
                         File(configDir, "app_config").setLastModified(System.currentTimeMillis())
                         return secureResponse(Response.Status.OK, "text/plain", "Saved")
                     }
                 } catch (e: Exception) {
                     return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid JSON")
                 }
             }
             return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
        }

        if (uri == "/api/file" && method == Method.GET) {
            val filename = params["filename"]
            if (filename != null && isValidFilename(filename)) {
                return secureResponse(Response.Status.OK, "text/plain", readFile(filename))
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid filename")
        }

        if (uri == "/api/save" && method == Method.POST) {
             val map = HashMap<String, String>()
             try { session.parseBody(map) } catch(e:Exception){}
             val filename = session.parms["filename"]
             val content = session.parms["content"]

             if (filename != null && isValidFilename(filename) && content != null) {
                 if (validateContent(filename, content)) {
                     if (saveFile(filename, content)) {
                         return secureResponse(Response.Status.OK, "text/plain", "Saved")
                     }
                 } else {
                     return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid content format")
                 }
             }
             return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
        }

        if (uri == "/api/upload_keybox" && method == Method.POST) {
             val map = HashMap<String, String>()
             try { session.parseBody(map) } catch(e:Exception){}
             val filename = session.parms["filename"]
             val content = session.parms["content"]

             // Security: Strict filename validation to prevent path traversal and weird files
             if (filename != null && content != null && filename.endsWith(".xml") && filename.matches(Regex("^[a-zA-Z0-9._-]+$"))) {
                 val keyboxDir = File(configDir, "keyboxes")
                 if (!keyboxDir.exists()) {
                     keyboxDir.mkdirs()
                     permissionSetter(keyboxDir, 448) // 0700
                 }

                 val file = File(keyboxDir, filename)
                 try {
                     SecureFile.writeText(file, content)
                     return secureResponse(Response.Status.OK, "text/plain", "Saved")
                 } catch (e: Exception) {
                     e.printStackTrace()
                     return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed: " + e.message)
                 }
             }
             return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid request")
        }

        if (uri == "/api/verify_keyboxes" && method == Method.POST) {
             try {
                val results = KeyboxVerifier.verify(configDir)
                val json = createKeyboxVerificationJson(results)
                return secureResponse(Response.Status.OK, "application/json", json)
             } catch(e: Exception) {
                 return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
             }
        }

        if (uri == "/api/toggle" && method == Method.POST) {
             val map = HashMap<String, String>()
             try { session.parseBody(map) } catch(e:Exception){}
             val setting = session.parms["setting"]
             val value = session.parms["value"]

             if (setting != null && value != null) {
                 if (toggleFile(setting, value.toBoolean())) {
                     return secureResponse(Response.Status.OK, "text/plain", "Toggled")
                 }
             }
             return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
        }

        if (uri == "/api/reload" && method == Method.POST) {
             try {
                File(configDir, "target.txt").setLastModified(System.currentTimeMillis())
                return secureResponse(Response.Status.OK, "text/plain", "Reloaded")
             } catch(e: Exception) {
                 return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
             }
        }

        if (uri == "/api/reset_drm" && method == Method.POST) {
             try {
                 // Delete DRM provisioning data
                 val dirs = listOf("/data/vendor/mediadrm", "/data/mediadrm")
                 dirs.forEach { path ->
                     try {
                         File(path).walkBottomUp().forEach { if (it.path != path) it.delete() }
                     } catch(e: Exception) { Logger.e("Failed to clear $path", e) }
                 }

                 // Restart DRM services
                 val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "killall -9 android.hardware.drm-service.widevine android.hardware.drm-service.clearkey mediadrmserver || true"))
                 p.waitFor()

                 return secureResponse(Response.Status.OK, "text/plain", "DRM ID Regenerated")
             } catch(e: Exception) {
                 return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
             }
        }

        if (uri == "/api/fetch_beta" && method == Method.POST) {
             try {
                val result = BetaFetcher.fetchAndApply(null)
                if (result.success) {
                    return secureResponse(Response.Status.OK, "text/plain", "Success: ${result.profile?.model}")
                } else {
                    return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed: ${result.error}")
                }
             } catch(e: Exception) {
                 return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
             }
        }

        // NEW: Community Stats
        if (uri == "/api/stats" && method == Method.GET) {
            val count = fetchTelegramCount()
            val json = JSONObject()
            json.put("members", count)
            return secureResponse(Response.Status.OK, "application/json", json.toString())
        }

        if (uri == "/" || uri == "/index.html") {
            return secureResponse(Response.Status.OK, "text/html", getHtml())
        }

        return secureResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }

    private fun secureResponse(status: Response.Status, mimeType: String, txt: String): Response {
        val response = newFixedLengthResponse(status, mimeType, txt)
        response.addHeader("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'")
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("X-Frame-Options", "DENY")
        response.addHeader("Referrer-Policy", "no-referrer")
        return response
    }

    private fun isValidFilename(name: String): Boolean {
        return name in setOf("target.txt", "security_patch.txt", "spoof_build_vars", "app_config", "templates.json", "drm_fix")
    }

    private fun validateContent(filename: String, content: String): Boolean {
        if (content.isBlank()) return true
        val lines = content.lines()

        when (filename) {
            "drm_fix" -> {
                 // Similar to spoof_build_vars, Key=Value
                 val lineRegex = Regex("^[a-zA-Z0-9_.]+=.+$")
                 val unsafeChars = Regex("[\\$|&;<>`]")
                 for (line in lines) {
                    if (line.isBlank() || line.trim().startsWith("#")) continue
                    if (!line.trim().matches(lineRegex)) return false
                    if (line.contains(unsafeChars)) return false
                }
            }
            "app_config" -> {
                val pkgRegex = Regex("^[a-zA-Z0-9_.*]+$")
                val tmplRegex = Regex("^[a-zA-Z0-9_]+$")
                val kbRegex = Regex("^[a-zA-Z0-9_.-]+$")

                for (line in lines) {
                    if (line.isBlank() || line.trim().startsWith("#")) continue
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.isEmpty()) continue

                    // Package is required
                    if (!parts[0].matches(pkgRegex)) return false

                    // Template (optional)
                    if (parts.size > 1 && parts[1] != "null" && !parts[1].matches(tmplRegex)) return false

                    // Keybox (optional)
                    if (parts.size > 2 && parts[2] != "null" && !parts[2].matches(kbRegex)) return false
                }
            }
            "target.txt" -> {
                val pkgRegex = Regex("^[a-zA-Z0-9_.*!]+$")
                for (line in lines) {
                    if (line.isBlank() || line.trim().startsWith("#")) continue
                    if (!line.trim().matches(pkgRegex)) return false
                }
            }
            "spoof_build_vars" -> {
                // Key=Value format. Key can be system prop (lower) or config (upper).
                // Value can be anything, but let's restrict potentially dangerous chars if possible.
                // Actually, allowing '.' in key is important for ro.product...
                val lineRegex = Regex("^[a-zA-Z0-9_.]+=.+$")
                val unsafeChars = Regex("[\\$|&;<>`]")
                for (line in lines) {
                    if (line.isBlank() || line.trim().startsWith("#")) continue
                    if (!line.trim().matches(lineRegex)) return false
                    // Security: Prevent potential command injection chars if file is ever sourced
                    if (line.contains(unsafeChars)) return false
                }
            }
            "security_patch.txt" -> {
                // Accepts YYYYMMDD, YYYY-MM-DD, or key=value
                // Simple regex: alphanumeric, dash, equal
                val safeRegex = Regex("^[a-zA-Z0-9_=-]+$")
                for (line in lines) {
                    if (line.isBlank() || line.trim().startsWith("#")) continue
                    if (!line.trim().matches(safeRegex)) return false
                }
            }
            "templates.json" -> {
                 if (content.trim().isNotEmpty()) {
                     try {
                         JSONArray(content)
                     } catch (e: Exception) {
                         return false
                     }
                 }
            }
        }
        return true
    }

    private fun getAppName(): String {
        return String(charArrayOf(67.toChar(), 108.toChar(), 101.toChar(), 118.toChar(), 101.toChar(), 114.toChar(), 101.toChar(), 115.toChar(), 84.toChar(), 114.toChar(), 105.toChar(), 99.toChar(), 107.toChar(), 121.toChar()))
    }

    private val htmlContent by lazy {
        """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>${getAppName()}</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        :root {
            --bg: #0B0B0C;
            --fg: #E5E7EB;
            --accent: #D1D5DB;
            --panel: #161616;
            --border: #333;
            --input-bg: #1A1A1A;
            --success: #34D399;
            --danger: #EF4444;
        }
        body { background-color: var(--bg); color: var(--fg); font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; margin: 0; padding: 0; }

        /* Dynamic Island Notification */
        .island-container { display: flex; justify-content: center; position: fixed; top: 10px; width: 100%; z-index: 1000; pointer-events: none; }
        .island {
            background: #000;
            color: #fff;
            border-radius: 30px;
            height: 35px;
            width: 120px;
            display: flex;
            align-items: center;
            justify-content: center;
            transition: all 0.4s cubic-bezier(0.175, 0.885, 0.32, 1.275);
            box-shadow: 0 4px 15px rgba(0,0,0,0.5);
            font-size: 0.8em;
            font-weight: 500;
            opacity: 0;
            transform: translateY(-20px);
        }
        .island.active { width: auto; min-width: 200px; padding: 0 20px; opacity: 1; transform: translateY(0); }
        .island.working { /* Spinner mode active */ }

        .spinner {
            width: 14px; height: 14px;
            border: 2px solid #fff;
            border-top-color: transparent;
            border-radius: 50%;
            animation: spin 0.8s linear infinite;
            margin-right: 10px;
            display: none;
        }
        .island.working .spinner { display: block; }
        @keyframes spin { to { transform: rotate(360deg); } }

        h1 { text-align: center; font-weight: 200; letter-spacing: 2px; margin: 25px 0; color: var(--accent); font-size: 1.5em; text-transform: uppercase; }

        .tabs { display: flex; justify-content: center; border-bottom: 1px solid var(--border); background: var(--panel); overflow-x: auto; }
        .tab { padding: 15px 20px; cursor: pointer; border-bottom: 2px solid transparent; opacity: 0.6; transition: all 0.2s; white-space: nowrap; font-size: 0.9em; letter-spacing: 1px; }
        .tab:hover { opacity: 0.9; }
        .tab.active { border-bottom-color: var(--accent); opacity: 1; color: var(--accent); }

        .content { display: none; padding: 20px; max-width: 800px; margin: 0 auto; padding-bottom: 80px; }
        .content.active { display: block; animation: fadeIn 0.3s ease; }

        @keyframes fadeIn { from { opacity: 0; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }

        .panel { background: var(--panel); border: 1px solid var(--border); border-radius: 12px; padding: 20px; margin-bottom: 20px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); }

        h3 { margin-top: 0; font-weight: 500; color: var(--accent); font-size: 1.1em; letter-spacing: 0.5px; border-bottom: 1px solid var(--border); padding-bottom: 10px; margin-bottom: 15px; }

        .row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; min-height: 30px; }
        .row.wrap { flex-wrap: wrap; }

        label { font-size: 0.9em; color: #BBB; cursor: pointer; }

        /* Modern Inputs */
        input[type="text"], textarea, select {
            background: var(--input-bg);
            border: 1px solid var(--border);
            color: #fff;
            padding: 10px 12px;
            border-radius: 6px;
            width: 100%;
            box-sizing: border-box;
            font-family: inherit;
            transition: border-color 0.2s;
            font-size: 0.9em;
        }
        input[type="text"]:focus, textarea:focus, select:focus { border-color: var(--accent); outline: none; }

        /* Modern Buttons */
        button {
            background: var(--border);
            border: none;
            color: var(--fg);
            padding: 10px 20px;
            border-radius: 6px;
            cursor: pointer;
            font-family: inherit;
            font-weight: 500;
            font-size: 0.85em;
            transition: all 0.2s;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }
        button:hover { background: #444; }
        button:active { transform: scale(0.98); }
        button.primary { background: var(--accent); color: #000; }
        button.primary:hover { background: #fff; box-shadow: 0 0 10px rgba(255,255,255,0.2); }
        button.danger { background: rgba(239, 68, 68, 0.2); color: var(--danger); border: 1px solid var(--danger); }
        button.danger:hover { background: var(--danger); color: #fff; }

        /* Toggles (Apple Style) */
        input[type="checkbox"].toggle {
            appearance: none;
            width: 40px;
            height: 22px;
            background: #333;
            border-radius: 20px;
            position: relative;
            cursor: pointer;
            transition: background 0.3s;
        }
        input[type="checkbox"].toggle::after {
            content: '';
            position: absolute;
            top: 2px; left: 2px;
            width: 18px; height: 18px;
            background: #fff;
            border-radius: 50%;
            transition: transform 0.3s;
        }
        input[type="checkbox"].toggle:checked { background: var(--accent); }
        input[type="checkbox"].toggle:checked::after { transform: translateX(18px); }
        input[type="checkbox"].toggle:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
        input[type="checkbox"].toggle:disabled { opacity: 0.5; cursor: not-allowed; }

        textarea:disabled, input:disabled, select:disabled { opacity: 0.5; cursor: not-allowed; }

        table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 0.9em; }
        th { text-align: left; padding: 10px; border-bottom: 1px solid var(--border); color: #888; font-weight: 500; }
        td { padding: 10px; border-bottom: 1px solid var(--border); color: #ccc; }

        .tag { display: inline-block; padding: 2px 8px; border-radius: 10px; background: #333; font-size: 0.75em; margin-right: 5px; }
        .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 15px; }

        .section-header { font-size: 0.8em; color: #666; text-transform: uppercase; letter-spacing: 1px; margin: 15px 0 5px 0; }

        ::-webkit-scrollbar { width: 8px; }
        ::-webkit-scrollbar-track { background: var(--bg); }
        ::-webkit-scrollbar-thumb { background: #333; border-radius: 4px; }
        ::-webkit-scrollbar-thumb:hover { background: #555; }
    </style>
</head>
<body>
    <div class="island-container">
        <div id="island" class="island" role="status" aria-live="polite">
            <div class="spinner"></div>
            <span id="islandText">Notification</span>
        </div>
    </div>

    <h1>${getAppName()} <span style="font-size:0.5em; vertical-align:middle; color:var(--accent); opacity:0.7; border: 1px solid var(--accent); border-radius: 4px; padding: 2px 6px; margin-left: 10px;">BETA</span></h1>

    <div class="tabs" role="tablist" aria-label="Navigation">
        <div class="tab active" id="tab_dashboard" onclick="switchTab('dashboard')" role="tab" tabindex="0" aria-selected="true" aria-controls="dashboard" onkeydown="handleTabKey(event, 'dashboard')">Dashboard</div>
        <div class="tab" id="tab_spoof" onclick="switchTab('spoof')" role="tab" tabindex="0" aria-selected="false" aria-controls="spoof" onkeydown="handleTabKey(event, 'spoof')">Spoofing</div>
        <div class="tab" id="tab_apps" onclick="switchTab('apps')" role="tab" tabindex="0" aria-selected="false" aria-controls="apps" onkeydown="handleTabKey(event, 'apps')">Apps</div>
        <div class="tab" id="tab_keys" onclick="switchTab('keys')" role="tab" tabindex="0" aria-selected="false" aria-controls="keys" onkeydown="handleTabKey(event, 'keys')">Keyboxes</div>
        <div class="tab" id="tab_editor" onclick="switchTab('editor')" role="tab" tabindex="0" aria-selected="false" aria-controls="editor" onkeydown="handleTabKey(event, 'editor')">Editor</div>
    </div>

    <!-- DASHBOARD -->
    <div id="dashboard" class="content active" role="tabpanel" aria-labelledby="tab_dashboard">
        <div class="panel">
            <h3>System Control</h3>
            <div class="row"><label for="global_mode">Global Mode</label><input type="checkbox" class="toggle" id="global_mode" onchange="toggle('global_mode')"></div>
            <div class="row"><label for="tee_broken_mode">TEE Broken Mode</label><input type="checkbox" class="toggle" id="tee_broken_mode" onchange="toggle('tee_broken_mode')"></div>
            <div class="row"><label for="rkp_bypass">RKP Bypass (Strong)</label><input type="checkbox" class="toggle" id="rkp_bypass" onchange="toggle('rkp_bypass')"></div>
            <div class="row"><label for="auto_beta_fetch">Auto Beta Fetch</label><input type="checkbox" class="toggle" id="auto_beta_fetch" onchange="toggle('auto_beta_fetch')"></div>
            <div class="row"><label for="auto_keybox_check">Auto Keybox Check</label><input type="checkbox" class="toggle" id="auto_keybox_check" onchange="toggle('auto_keybox_check')"></div>
            <div class="row"><label for="random_on_boot">Randomize on Boot</label><input type="checkbox" class="toggle" id="random_on_boot" onchange="toggle('random_on_boot')"></div>

            <div style="margin-top:20px; border-top: 1px solid var(--border); padding-top: 15px;">
                <div class="row">
                    <span id="keyboxStatus" style="font-size:0.9em; color:var(--success);">Active</span>
                    <button onclick="runWithState(this, 'Reloading...', reloadConfig)">Reload Config</button>
                </div>
            </div>
        </div>

        <div class="panel" style="text-align:center;">
            <h3>Community</h3>
            <div id="communityCount" style="font-size:2em; font-weight:300; margin: 10px 0;">...</div>
            <div style="font-size:0.8em; color:#666;">Telegram Members</div>
            <a href="https://t.me/cleverestech" target="_blank" rel="noopener noreferrer" style="display:inline-block; margin-top:10px; color:var(--accent); text-decoration:none; font-size:0.9em; border:1px solid var(--border); padding:5px 15px; border-radius:15px;">Join Channel</a>
        </div>

        <div class="panel">
            <h3>Support Project</h3>
            <div style="font-size:0.85em; color:#888; margin-bottom:15px;">Your contributions help maintain and develop new features.</div>

            <div class="section-header">Crypto (Click to Copy)</div>
            <div class="grid-2">
                 <button onclick="copyToClipboard('TQGTsbqawRHhv35UMxjHo14mieUGWXyQzk', 'Copied TRC20!')">USDT (TRC20)</button>
                 <button onclick="copyToClipboard('85m61iuWiwp24g8NRXoMKdW25ayVWFzYf5BoAqvgGpLACLuMsXbzGbWR9mC8asnCSfcyHN3dZgEX8KZh2pTc9AzWGXtrEUv', 'Copied XMR!')">Monero (XMR)</button>
            </div>
            <div class="grid-2" style="margin-top:10px;">
                 <button onclick="copyToClipboard('114574830', 'Copied Binance ID!')">Binance ID</button>
                 <button onclick="copyToClipboard('0x1a4b9e55e268e6969492a70515a5fd9fd4e6ea8b', 'Copied ERC20!')">USDT (ERC20)</button>
            </div>

            <div class="section-header">Platforms</div>
            <div class="grid-2">
                 <button class="primary" onclick="window.open('https://www.paypal.me/tryigitx', '_blank')">PayPal</button>
                 <button class="primary" onclick="window.open('https://buymeacoffee.com/yigitx', '_blank')">BuyMeACoffee</button>
            </div>
        </div>
    </div>

    <!-- SPOOFING (Replacing Lab) -->
    <div id="spoof" class="content" role="tabpanel" aria-labelledby="tab_spoof">
        <div class="panel">
            <h3>DRM / Streaming</h3>
            <p style="color:#888; font-size:0.9em; margin-bottom:15px;">Applies specific system properties to fix playback errors (e.g. Netflix 5.7) on unlocked bootloaders.</p>
            <div class="row">
                <label for="drm_fix">Netflix / DRM Fix</label>
                <div style="display:flex; align-items:center; gap:10px;">
                    <button onclick="editDrmConfig()" style="padding:5px 10px; font-size:0.75em;">Edit</button>
                    <input type="checkbox" class="toggle" id="drm_fix" onchange="toggle('drm_fix')">
                </div>
            </div>
            <div class="row"><label for="random_drm_on_boot">Randomize on Boot</label><input type="checkbox" class="toggle" id="random_drm_on_boot" onchange="toggle('random_drm_on_boot')"></div>
            <div class="row" style="margin-top:10px;">
                <label style="font-size:0.8em; color:#888;">Reset Identity</label>
                <button onclick="runWithState(this, 'Regenerating...', resetDrmId)" style="font-size:0.75em;">Regenerate DRM ID</button>
            </div>
        </div>

        <div class="panel">
            <h3>Identity Manager</h3>
            <p style="color:#888; font-size:0.9em; margin-bottom:15px;">Select a verified device identity to spoof globally.</p>

            <select id="templateSelect" onchange="previewTemplate()" style="margin-bottom:15px;"></select>

            <div id="templatePreview" style="background:var(--input-bg); border-radius:8px; padding:15px; margin-bottom:15px;">
                <div class="grid-2">
                    <div><div class="section-header">Device</div><div id="pModel"></div></div>
                    <div><div class="section-header">Manufacturer</div><div id="pManuf"></div></div>
                </div>
                <div class="section-header">Fingerprint</div>
                <div style="font-family:monospace; font-size:0.8em; color:#999; word-break:break-all;" id="pFing"></div>
            </div>

            <div class="grid-2">
                <button onclick="runWithState(this, 'Generating...', generateRandomIdentity)" class="primary">Generate Random</button>
                <button onclick="runWithState(this, 'Applying...', applyTemplateToGlobal)">Apply Global</button>
            </div>
        </div>

        <div class="panel">
            <h3>System-Wide Spoofing (Global Hardware)</h3>
            <div style="height: 15px;"></div>
            <p style="color:#888; font-size:0.85em; margin-bottom:15px;">
                <span style="color:var(--danger)">WARNING:</span> These settings affect low-level system services (Telephony, Binder).
                Changing IMEI/IMSI may have legal implications or affect network connectivity.
            </p>

            <div class="section-header">Modem / Telephony</div>
            <div class="grid-2">
                <div>
                    <label for="inputImei" style="display:block; font-size:0.8em; margin-bottom:5px; color:#888;">IMEI (Slot 1)</label>
                    <input type="text" id="inputImei" placeholder="35..." style="font-family:monospace;" inputmode="numeric">
                </div>
                <div>
                    <label for="inputImsi" style="display:block; font-size:0.8em; margin-bottom:5px; color:#888;">IMSI (Subscriber ID)</label>
                    <input type="text" id="inputImsi" placeholder="310..." style="font-family:monospace;" inputmode="numeric">
                </div>
            </div>
            <div class="grid-2" style="margin-top:10px;">
                <div>
                    <label for="inputIccid" style="display:block; font-size:0.8em; margin-bottom:5px; color:#888;">ICCID (Sim Serial)</label>
                    <input type="text" id="inputIccid" placeholder="89..." style="font-family:monospace;" inputmode="numeric">
                </div>
                <div>
                    <label for="inputSerial" style="display:block; font-size:0.8em; margin-bottom:5px; color:#888;">Device Serial No</label>
                    <input type="text" id="inputSerial" placeholder="Alphanumeric..." style="font-family:monospace;" autocapitalize="characters">
                </div>
            </div>

            <div class="section-header">Network Interface</div>
            <div class="grid-2">
                <div>
                    <label for="inputWifiMac" style="display:block; font-size:0.8em; margin-bottom:5px; color:#888;">WiFi MAC</label>
                    <input type="text" id="inputWifiMac" placeholder="00:11:22:33:44:55" style="font-family:monospace;" autocapitalize="characters">
                </div>
                <div>
                    <label for="inputBtMac" style="display:block; font-size:0.8em; margin-bottom:5px; color:#888;">BT MAC</label>
                    <input type="text" id="inputBtMac" placeholder="00:11:22:33:44:55" style="font-family:monospace;" autocapitalize="characters">
                </div>
            </div>

            <div class="section-header">Operator Config (MBN Emulation)</div>
            <div class="grid-2">
                 <div>
                    <label for="inputSimIso" style="display:block; font-size:0.8em; margin-bottom:5px; color:#888;">SIM Country ISO</label>
                    <input type="text" id="inputSimIso" placeholder="us">
                 </div>
                 <div>
                    <label for="inputSimOp" style="display:block; font-size:0.8em; margin-bottom:5px; color:#888;">Operator Name</label>
                    <input type="text" id="inputSimOp" placeholder="T-Mobile">
                 </div>
            </div>

            <div style="margin-top:15px; text-align:right;">
                <span style="font-size:0.8em; color:#666; margin-right:10px;">* Applies to System Services</span>
                <button onclick="applyTemplateToGlobal(this)" class="danger">Apply System-Wide</button>
            </div>
        </div>
    </div>

    <!-- APPS -->
    <div id="apps" class="content" role="tabpanel" aria-labelledby="tab_apps">
        <div class="panel">
            <h3>New Rule</h3>
            <div style="margin-bottom:10px;">
                <label for="appPkg" style="display:block; font-size:0.8em; margin-bottom:5px; color:#888;">Target Package</label>
                <input type="text" id="appPkg" list="pkgList" placeholder="Package Name (com.example...)" onchange="this.value=this.value.trim()" onkeydown="if(event.key==='Enter') addAppRule()">
                <datalist id="pkgList"></datalist>
            </div>
            <div class="grid-2" style="margin-bottom:10px;">
                <div>
                    <label for="appTemplate" style="display:block; font-size:0.8em; margin-bottom:5px; color:#888;">Device Identity</label>
                    <select id="appTemplate">
                        <option value="null">No Identity Spoof</option>
                    </select>
                </div>
                <div>
                    <label for="appKeybox" style="display:block; font-size:0.8em; margin-bottom:5px; color:#888;">Keybox XML</label>
                    <select id="appKeybox" onkeydown="if(event.key==='Enter') addAppRule()">
                        <option value="">Default (None)</option>
                    </select>
                </div>
            </div>

            <div class="section-header" style="margin-top:10px;">Blank Permissions (Privacy)</div>
            <div style="display:flex; gap:15px; flex-wrap:wrap; margin-bottom:15px;">
                <div class="row" style="flex:1; min-width:120px; justify-content:flex-start; gap:10px;">
                    <input type="checkbox" id="permContacts" class="toggle" style="transform:scale(0.8)" disabled title="Coming Soon"> <label for="permContacts">Contacts</label>
                </div>
                <div class="row" style="flex:1; min-width:120px; justify-content:flex-start; gap:10px;">
                    <input type="checkbox" id="permMedia" class="toggle" style="transform:scale(0.8)" disabled title="Coming Soon"> <label for="permMedia">Media</label>
                </div>
            </div>

            <button class="primary" style="width:100%" onclick="addAppRule()">Add Rule</button>
        </div>

        <div class="panel">
            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:10px;">
                <h3 style="border:none; margin:0; padding:0;">Active Rules</h3>
                <input type="search" id="appFilter" placeholder="Filter..." oninput="renderAppTable()" aria-label="Filter rules" style="width:150px; padding:5px 10px; font-size:0.85em; background:var(--input-bg); border:1px solid var(--border); color:#fff; border-radius:4px;">
            </div>
            <table id="appTable">
                <thead><tr><th>Package</th><th>Profile</th><th>Flags</th><th></th></tr></thead>
                <tbody></tbody>
            </table>
            <div style="margin-top:15px; text-align:right;">
                <button onclick="runWithState(this, 'Saving...', saveAppConfig)" class="primary">Save Configuration</button>
            </div>
        </div>
    </div>

    <!-- KEYS -->
    <div id="keys" class="content" role="tabpanel" aria-labelledby="tab_keys">
        <div class="panel">
            <h3>Upload Keybox</h3>
            <input type="file" id="kbFilePicker" style="display:none" onchange="loadFileContent(this)" onclick="this.value = null" aria-label="Upload Keybox File">
            <button onclick="document.getElementById('kbFilePicker').click()" style="width:100%; margin-bottom:10px; border-style:dashed;">Select XML File</button>

            <label for="kbFilename" style="display:block; font-size:0.8em; margin-bottom:5px; color:#888;">Target Filename</label>
            <input type="text" id="kbFilename" placeholder="filename.xml" style="margin-bottom:10px;">
            <textarea id="kbContent" placeholder="XML Content" style="height:100px; font-family:monospace; font-size:0.8em;" aria-label="Keybox XML Content"></textarea>
            <button onclick="runWithState(this, 'Uploading...', uploadKeybox)" class="primary" style="margin-top:10px; width:100%;">Upload</button>
        </div>
        <div class="panel">
            <div class="row">
                <h3>Verification</h3>
                <button onclick="runWithState(this, 'Verifying...', verifyKeyboxes)">Check All</button>
            </div>
            <div id="verifyResult" style="font-family:monospace; font-size:0.85em;"></div>
        </div>
    </div>

    <!-- EDITOR -->
    <div id="editor" class="content" role="tabpanel" aria-labelledby="tab_editor">
        <div class="panel">
            <div class="row">
                <select id="fileSelector" onchange="loadFile()" style="width:70%;" aria-label="Select file to edit">
                    <option value="target.txt">target.txt</option>
                    <option value="security_patch.txt">security_patch.txt</option>
                    <option value="spoof_build_vars">spoof_build_vars</option>
                    <option value="app_config">app_config</option>
                    <option value="drm_fix">drm_fix</option>
                </select>
                <button id="saveBtn" onclick="runWithState(this, 'Saving...', saveFile)">Save</button>
            </div>
            <textarea id="fileEditor" style="height:500px; font-family:monospace; margin-top:10px; line-height:1.4;" aria-label="File Content"></textarea>
        </div>
    </div>

    <script>
        const baseUrl = '/api';
        const urlParams = new URLSearchParams(window.location.search);
        const token = urlParams.get('token');

        function getAuthUrl(path) { return path + (path.includes('?') ? '&' : '?') + 'token=' + token; }

        // Clipboard Helper
        function copyToClipboard(text, msg) {
            navigator.clipboard.writeText(text).then(() => {
                notify(msg, 'normal');
            }, (err) => {
                // Fallback for non-secure contexts (http)
                const textArea = document.createElement("textarea");
                textArea.value = text;
                document.body.appendChild(textArea);
                textArea.select();
                try {
                    document.execCommand('copy');
                    notify(msg, 'normal');
                } catch (err) {
                    notify('Copy failed', 'error');
                }
                document.body.removeChild(textArea);
            });
        }

        // Dynamic Island Logic
        let notifyTimeout;
        function notify(msg, type = 'normal') {
            const island = document.getElementById('island');
            const text = document.getElementById('islandText');

            text.innerText = msg;
            island.classList.add('active');

            if (notifyTimeout) clearTimeout(notifyTimeout);

            if (type === 'working') {
                island.classList.add('working');
            } else {
                island.classList.remove('working');
                notifyTimeout = setTimeout(() => {
                    island.classList.remove('active');
                }, 3000);
            }
        }

        async function runWithState(btn, text, task) {
             const orig = btn.innerText;
             btn.disabled = true;
             btn.innerText = text;
             try { await task(); }
             finally { btn.disabled = false; btn.innerText = orig; }
        }

        function switchTab(id) {
            document.querySelectorAll('.tab').forEach(t => {
                t.classList.remove('active');
                t.setAttribute('aria-selected', 'false');
            });
            document.querySelectorAll('.content').forEach(c => c.classList.remove('active'));

            const tab = document.getElementById('tab_' + id);
            tab.classList.add('active');
            tab.setAttribute('aria-selected', 'true');

            document.getElementById(id).classList.add('active');
            if (id === 'apps') loadAppConfig();
        }

        function handleTabKey(e, id) {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                switchTab(id);
            }
        }

        async function init() {
            if (!token) return;

            // Load Settings
            try {
                const res = await fetch(getAuthUrl('/api/config'));
                const data = await res.json();
                ['global_mode', 'tee_broken_mode', 'rkp_bypass', 'auto_beta_fetch', 'auto_keybox_check', 'random_on_boot', 'drm_fix', 'random_drm_on_boot'].forEach(k => {
                    if(document.getElementById(k)) document.getElementById(k).checked = data[k];
                });
                document.getElementById('keyboxStatus').innerText = `${'$'}{data.keybox_count} Keys Loaded`;
            } catch(e) { notify('Connection Failed', 'error'); }

            // Load Stats
            fetch(getAuthUrl('/api/stats')).then(r => r.json()).then(d => {
                document.getElementById('communityCount').innerText = d.members;
            });

            // Load Templates
            const tRes = await fetch(getAuthUrl('/api/templates'));
            const templates = await tRes.json();
            const sel = document.getElementById('templateSelect');
            const appSel = document.getElementById('appTemplate');

            templates.forEach(t => {
                const opt = document.createElement('option');
                opt.value = t.id;
                opt.text = `${'$'}{t.model} (${'$'}{t.manufacturer})`;
                opt.dataset.json = JSON.stringify(t);
                sel.appendChild(opt.cloneNode(true));
                appSel.appendChild(opt);
            });
            previewTemplate();

            // Load Packages
            fetch(getAuthUrl('/api/packages')).then(r => r.json()).then(pkgs => {
                const dl = document.getElementById('pkgList');
                pkgs.forEach(p => {
                    const opt = document.createElement('option');
                    opt.value = p;
                    dl.appendChild(opt);
                });
            });

            // Load Keyboxes
            fetch(getAuthUrl('/api/keyboxes')).then(r => r.json()).then(kbs => {
                const sel = document.getElementById('appKeybox');
                kbs.forEach(k => {
                    const opt = document.createElement('option');
                    opt.value = k;
                    opt.text = k;
                    sel.appendChild(opt);
                });
            });

            // Init Editor
            currentFile = document.getElementById('fileSelector').value;
            await loadFile();
        }

        async function toggle(setting) {
            const el = document.getElementById(setting);
            try {
                await fetch(getAuthUrl('/api/toggle'), {
                    method: 'POST',
                    body: new URLSearchParams({setting, value: el.checked})
                });
                notify('Setting Updated');
            } catch(e) {
                el.checked = !el.checked;
                notify('Update Failed');
            }
        }

        function editDrmConfig() {
            document.getElementById('fileSelector').value = 'drm_fix';
            switchTab('editor');
            loadFile();
        }

        async function resetDrmId() {
            if (!confirm('This will delete downloaded DRM licenses and reset the device ID for streaming apps. Continue?')) return;
            try {
                await fetch(getAuthUrl('/api/reset_drm'), { method: 'POST' });
                notify('DRM ID Reset');
            } catch(e) {
                notify('Failed', 'error');
            }
        }

        function previewTemplate() {
            const sel = document.getElementById('templateSelect');
            if (!sel.selectedOptions.length) return;
            const t = JSON.parse(sel.selectedOptions[0].dataset.json);

            document.getElementById('pModel').innerText = t.model;
            document.getElementById('pManuf').innerText = t.manufacturer;
            document.getElementById('pFing').innerText = t.fingerprint;

            // Clear extras if switching manually
            if (!sel.dataset.lockExtras) {
                document.getElementById('inputImei').value = '';
                document.getElementById('inputSerial').value = '';
            }
            delete sel.dataset.lockExtras;
        }

        async function generateRandomIdentity() {
            const res = await fetch(getAuthUrl('/api/random_identity'));
            if (!res.ok) { notify('Failed'); return; }
            const t = await res.json();

            // Populate Advanced Fields
            document.getElementById('inputImei').value = t.imei || '';
            document.getElementById('inputImsi').value = t.imsi || '';
            document.getElementById('inputIccid').value = t.iccid || '';
            document.getElementById('inputSerial').value = t.serial || '';
            document.getElementById('inputWifiMac').value = t.wifiMac || '';
            document.getElementById('inputBtMac').value = t.btMac || '';
            document.getElementById('inputSimIso').value = t.simCountryIso || '';
            document.getElementById('inputSimOp').value = t.carrier || '';

            // Update preview but visually indicate it's random
            document.getElementById('pModel').innerText = t.model + ' (Randomized)';
            document.getElementById('pManuf').innerText = t.manufacturer;
            document.getElementById('pFing').innerText = t.fingerprint;

            // Store for application
            const sel = document.getElementById('templateSelect');
            sel.dataset.generated = JSON.stringify(t);
            notify('Identity Generated');
        }

        async function applyTemplateToGlobal(btn) {
             if (!confirm('Overwrite current spoofing config?')) return;

             let origText = '';
             if (btn) {
                 origText = btn.innerText;
                 btn.disabled = true;
                 btn.innerText = 'Applying...';
             }

             try {
                 const sel = document.getElementById('templateSelect');
                 let content = "";

                 // Check if we have generated data or advanced inputs
                 const imei = document.getElementById('inputImei').value;
                 const imsi = document.getElementById('inputImsi').value;
                 const iccid = document.getElementById('inputIccid').value;
                 const serial = document.getElementById('inputSerial').value;
                 const wifi = document.getElementById('inputWifiMac').value;
                 const simIso = document.getElementById('inputSimIso').value;
                 const simOp = document.getElementById('inputSimOp').value;

                 let t;
                 if (sel.dataset.generated) {
                     t = JSON.parse(sel.dataset.generated);
                 } else {
                     t = JSON.parse(sel.selectedOptions[0].dataset.json);
                 }

                 content = `TEMPLATE=${'$'}{t.id}\n# Applied via WebUI\n`;
                 if (imei) content += `ATTESTATION_ID_IMEI=${'$'}{imei}\n`;
                 if (imsi) content += `ATTESTATION_ID_IMSI=${'$'}{imsi}\n`;
                 if (iccid) content += `ATTESTATION_ID_ICCID=${'$'}{iccid}\n`;
                 if (serial) content += `ATTESTATION_ID_SERIAL=${'$'}{serial}\n`;
                 if (wifi) content += `ATTESTATION_ID_WIFI_MAC=${'$'}{wifi}\n`;
                 if (simIso) content += `SIM_COUNTRY_ISO=${'$'}{simIso}\n`;
                 if (simOp) content += `SIM_OPERATOR_NAME=${'$'}{simOp}\n`;
                 // Add other fields as needed for build vars

                 await fetch(getAuthUrl('/api/save'), {
                     method: 'POST',
                     body: new URLSearchParams({ filename: 'spoof_build_vars', content })
                 });
                 notify('Applied Globally');
                 setTimeout(reloadConfig, 1000);
             } catch (e) {
                 notify('Error applying');
                 if (btn) {
                     btn.disabled = false;
                     btn.innerText = origText || 'Apply System-Wide';
                 }
             }
        }

        async function saveAdvancedSpoof() {
            // This would ideally save to a dedicated config file
            // For now, we reuse applyTemplateToGlobal logic or just notify
            notify('Use "Apply Global" to save');
        }

        let appRules = [];

        async function loadAppConfig() {
            const res = await fetch(getAuthUrl('/api/app_config_structured'));
            appRules = await res.json();
            renderAppTable();
        }

        function renderAppTable() {
            const filter = document.getElementById('appFilter') ? document.getElementById('appFilter').value.toLowerCase() : '';
            const tbody = document.querySelector('#appTable tbody');
            tbody.innerHTML = '';

            if (appRules.length === 0) {
                const tr = document.createElement('tr');
                tr.innerHTML = '<td colspan="4" style="text-align:center; padding:20px; color:#666;">No active rules. Add a package above to customize spoofing.</td>';
                tbody.appendChild(tr);
                return;
            }

            let visibleCount = 0;
            appRules.forEach((rule, idx) => {
                if (filter && !rule.package.toLowerCase().includes(filter)) return;

                visibleCount++;
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td>${'$'}{rule.package}</td>
                    <td>${'$'}{rule.template === 'null' ? 'Default' : rule.template}</td>
                    <td>${'$'}{rule.keybox && rule.keybox !== 'null' ? rule.keybox : ''}</td>
                    <td style="text-align:right;">
                        <button class="danger" onclick="removeAppRule(${'$'}{idx})" aria-label="Remove rule for ${'$'}{rule.package}">×</button>
                    </td>
                `;
                tbody.appendChild(tr);
            });

            if (visibleCount === 0) {
                const tr = document.createElement('tr');
                tr.innerHTML = '<td colspan="4" style="text-align:center; padding:20px; color:#666;">No matching rules found.</td>';
                tbody.appendChild(tr);
            }
        }

        function addAppRule() {
            const pkgInput = document.getElementById('appPkg');
            const pkg = pkgInput.value.trim();
            const tmpl = document.getElementById('appTemplate').value;
            const kb = document.getElementById('appKeybox').value;
            const pContacts = document.getElementById('permContacts').checked;
            const pMedia = document.getElementById('permMedia').checked;

            if (!pkg) {
                notify('Package required');
                pkgInput.focus();
                return;
            }

            // TODO: Serialize blank permissions into the rule
            // Current backend supports: package template keybox
            // We might need to encode flags in template name or add a new column in future

            appRules.push({ package: pkg, template: tmpl === 'null' ? '' : tmpl, keybox: kb });
            renderAppTable();
            pkgInput.value = '';
            document.getElementById('appKeybox').value = '';
            pkgInput.focus();
            notify('Rule Added');
        }

        function removeAppRule(idx) {
            appRules.splice(idx, 1);
            renderAppTable();
        }

        async function saveAppConfig() {
            await fetch(getAuthUrl('/api/app_config_structured'), {
                method: 'POST',
                body: new URLSearchParams({ data: JSON.stringify(appRules) })
            });
            notify('App Config Saved');
        }

        async function reloadConfig() {
            await fetch(getAuthUrl('/api/reload'), { method: 'POST' });
            notify('Config Reloaded');
            setTimeout(() => window.location.reload(), 1000);
        }

        let currentFile = '';
        async function loadFile() {
            const f = document.getElementById('fileSelector').value;
            const editor = document.getElementById('fileEditor');
            currentFile = f;

            editor.disabled = true;
            editor.value = 'Loading...';

            try {
                const res = await fetch(getAuthUrl('/api/file?filename=' + f));
                if (res.ok) {
                    editor.value = await res.text();
                } else {
                    editor.value = 'Error loading file';
                    notify('Load Failed', 'error');
                }
            } catch (e) {
                editor.value = 'Error loading file';
                notify('Connection Error', 'error');
            } finally {
                editor.disabled = false;
            }
        }

        async function saveFile() {
            const f = document.getElementById('fileSelector').value;
            const c = document.getElementById('fileEditor').value;
            await fetch(getAuthUrl('/api/save'), {
                 method: 'POST',
                 body: new URLSearchParams({ filename: f, content: c })
             });
             notify('File Saved');
        }

        async function uploadKeybox() {
            const f = document.getElementById('kbFilename').value;
            const c = document.getElementById('kbContent').value;
            if (!f || !c) return;
            await fetch(getAuthUrl('/api/upload_keybox'), {
                 method: 'POST',
                 body: new URLSearchParams({ filename: f, content: c })
             });
             notify('Keybox Uploaded');
        }

        function loadFileContent(input) {
            if (input.files && input.files[0]) {
                const file = input.files[0];
                document.getElementById('kbFilename').value = file.name;
                const reader = new FileReader();
                reader.onload = (e) => document.getElementById('kbContent').value = e.target.result;
                reader.readAsText(file);
            }
        }

        async function verifyKeyboxes() {
             notify('Verifying...', 'working');
             const res = await fetch(getAuthUrl('/api/verify_keyboxes'), { method: 'POST' });
             const data = await res.json();
             const container = document.getElementById('verifyResult');
             container.innerHTML = '';
             data.forEach(d => {
                 const div = document.createElement('div');
                 div.style.color = d.status === 'VALID' ? '#34D399' : '#EF4444';
                 div.innerText = `[${'$'}{d.status}] ${'$'}{d.filename}`;
                 container.appendChild(div);
             });
             notify('Check Complete');
        }

        init();
    </script>
</body>
</html>
        """.trimIndent()
    }

    private fun getHtml(): String {
        return htmlContent
    }

    companion object {
        fun createKeyboxVerificationJson(results: List<KeyboxVerifier.Result>): String {
            val array = JSONArray()
            results.forEach { res ->
                val obj = JSONObject()
                obj.put("filename", res.filename)
                obj.put("status", res.status.toString())
                obj.put("details", res.details)
                array.put(obj)
            }
            return array.toString()
        }
    }
}
