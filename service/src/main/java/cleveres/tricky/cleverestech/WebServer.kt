package cleveres.tricky.cleverestech

import android.system.Os
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import cleveres.tricky.cleverestech.util.RandomUtils
import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.CboxDecryptor
import cleveres.tricky.cleverestech.util.ZipProcessor
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

private val WHITESPACE_REGEX = Regex("\\s+")
private val WHITESPACE_FIND_REGEX = Regex("\\s")
private val PKG_NAME_REGEX = Regex("^[a-zA-Z0-9_.*]+$")
private val TEMPLATE_NAME_REGEX = Regex("^[a-zA-Z0-9_-]+$")
private val KEYBOX_FILENAME_REGEX = Regex("^[a-zA-Z0-9_.-]+$")
private val KEY_VALUE_REGEX = Regex("^[a-zA-Z0-9_.]+=.+$")
private val SAFE_BUILD_VAR_VALUE_REGEX = Regex("^[a-zA-Z0-9_\\-\\.\\s/:,+=()@]*$")
private val TARGET_PKG_REGEX = Regex("^[a-zA-Z0-9_.*!]+$")
private val SECURITY_PATCH_REGEX = Regex("^[a-zA-Z0-9_=-]+$")
private val FILENAME_REGEX = Regex("^[a-zA-Z0-9._-]+$")
private val PERMISSIONS_REGEX = Regex("^[a-zA-Z0-9_.,]+$")
private val TELEGRAM_COUNT_PATTERN = java.util.regex.Pattern.compile("tgme_page_extra\">([0-9 ]+) members")

class WebServer(
    port: Int,
    private val configDir: File,
    private val permissionSetter: (File, Int) -> Unit = { f, m ->
        try {
            Os.chmod(f.absolutePath, m)
        } catch (t: Throwable) {
            Logger.e("failed to set permissions for ${f.name}", t)
        }
    }
) : NanoHTTPD("127.0.0.1", port) {

    val token = UUID.randomUUID().toString()
    private val MAX_UPLOAD_SIZE = 10 * 1024 * 1024L // 10MB for ZIPs
    private val MAX_BODY_SIZE = 5 * 1024 * 1024L // 5MB for non-multipart requests

    private class RateLimitEntry(var timestamp: Long, var count: Int)
    private val requestCounts = java.util.concurrent.ConcurrentHashMap<String, RateLimitEntry>()
    private val RATE_LIMIT = 100
    private val RATE_WINDOW = 60 * 1000L

    private val fileLock = Any()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun isRateLimited(ip: String): Boolean {
        if (requestCounts.size > 1000) requestCounts.clear()
        val current = requestCounts.compute(ip) { _, v ->
            val now = System.currentTimeMillis()
            if (v == null || now - v.timestamp > RATE_WINDOW) {
                RateLimitEntry(now, 1)
            } else {
                v.count++
                v
            }
        }
        return current!!.count > RATE_LIMIT
    }

    private fun isSafePath(file: File): Boolean {
        return try {
            val configCanonical = configDir.canonicalPath
            val fileCanonical = file.canonicalPath
            fileCanonical.equals(configCanonical) || fileCanonical.startsWith(configCanonical + File.separator)
        } catch (e: Exception) {
            false
        }
    }

    private fun readFile(filename: String): String {
        synchronized(fileLock) {
            return try {
                if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                    Logger.e("Path traversal attempt detected in filename: $filename")
                    return ""
                }
                val f = File(configDir, filename)
                if (!isSafePath(f)) {
                    Logger.e("Path traversal attempt detected: $filename")
                    return ""
                }
                f.readText()
            } catch (e: Exception) { "" }
        }
    }

    private fun saveFile(filename: String, content: String): Boolean {
        synchronized(fileLock) {
            return try {
                if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                    Logger.e("Path traversal attempt detected in save filename: $filename")
                    return false
                }
                val f = File(configDir, filename)
                if (!isSafePath(f)) {
                    Logger.e("Path traversal attempt detected during save: $filename")
                    return false
                }
                SecureFile.writeText(f, content)
                true
            } catch (e: Exception) {
                Logger.e("Failed to save file: $filename", e)
                false
            }
        }
    }

    private fun fileExists(filename: String): Boolean {
        synchronized(fileLock) {
            val f = File(configDir, filename)
            return isSafePath(f) && f.exists()
        }
    }

    private fun listKeyboxes(): List<String> {
        synchronized(fileLock) {
            val keyboxDir = File(configDir, "keyboxes")
            if (keyboxDir.exists() && keyboxDir.isDirectory) {
                return keyboxDir.listFiles { _, name -> name.endsWith(".xml") }
                    ?.map { it.name }
                    ?.sorted()
                    ?: emptyList()
            } else {
                return emptyList()
            }
        }
    }

    private fun isValidSetting(name: String): Boolean {
        return name in setOf("global_mode", "tee_broken_mode", "rkp_bypass", "auto_beta_fetch", "auto_keybox_check", "random_on_boot", "drm_fix", "random_drm_on_boot", "auto_patch_update", "hide_sensitive_props", "spoof_region_cn", "remove_magisk_32")
    }

    private fun toggleFile(filename: String, enable: Boolean): Boolean {
        if (!isValidSetting(filename)) return false
        synchronized(fileLock) {
            val f = File(configDir, filename)
            return try {
                if (enable) {
                    if (!f.exists()) {
                        if (filename == "drm_fix") {
                            val content = "ro.netflix.bsp_rev=0\ndrm.service.enabled=true\nro.com.google.widevine.level=1\nro.crypto.state=encrypted\n"
                            SecureFile.writeText(f, content)
                        } else {
                            SecureFile.touch(f, 384)
                        }
                    }
                } else {
                    if (f.exists()) f.delete()
                }
                true
            } catch (e: Exception) {
                Logger.e("Failed to toggle setting: $filename", e)
                false
            }
        }
    }

    @Volatile private var cachedTelegramCount: String? = null
    @Volatile private var lastTelegramFetchTime: Long = 0
    @Volatile private var isFetchingTelegram = false
    private val CACHE_DURATION_SUCCESS = 10 * 60 * 1000L
    private val CACHE_DURATION_ERROR = 1 * 60 * 1000L

    @Volatile private var cachedBannedCount: String? = null
    @Volatile private var lastBannedFetchTime: Long = 0
    @Volatile private var isFetchingBanned = false
    private val CACHE_DURATION_BANNED = 1 * 60 * 60 * 1000L // 1 hour

    private fun fetchTelegramCount(): String {
        val now = System.currentTimeMillis()
        val currentCache = cachedTelegramCount
        val lastTime = lastTelegramFetchTime

        if (currentCache != null) {
            val duration = if (currentCache == "Error" || currentCache == "Unknown" || currentCache.startsWith("Error")) CACHE_DURATION_ERROR else CACHE_DURATION_SUCCESS
            if ((now - lastTime) < duration) return currentCache
        }

        if (!isFetchingTelegram) {
            isFetchingTelegram = true
            scope.launch {
                try {
                    val result = doFetchTelegramCount()
                    cachedTelegramCount = result
                    lastTelegramFetchTime = System.currentTimeMillis()
                } finally {
                    isFetchingTelegram = false
                }
            }
        }
        return currentCache ?: "Loading..."
    }

    private fun fetchBannedCount(): String {
        val now = System.currentTimeMillis()
        val currentCache = cachedBannedCount
        val lastTime = lastBannedFetchTime

        if (currentCache != null && (now - lastTime) < CACHE_DURATION_BANNED) {
            return currentCache
        }

        if (!isFetchingBanned) {
            isFetchingBanned = true
            scope.launch {
                try {
                    val count = KeyboxVerifier.countRevokedKeys()
                    cachedBannedCount = if (count >= 0) count.toString() else "Error"
                    lastBannedFetchTime = System.currentTimeMillis()
                } finally {
                    isFetchingBanned = false
                }
            }
        }
        return currentCache ?: "Loading..."
    }

    private fun doFetchTelegramCount(): String {
        return try {
            val url = URL("https://t.me/cleverestech")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) {
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                val matcher = TELEGRAM_COUNT_PATTERN.matcher(html)
                if (matcher.find()) matcher.group(1)?.trim() ?: "Unknown" else "Unknown"
            } else {
                "Error: ${conn.responseCode}"
            }
        } catch (e: Exception) {
            "Error"
        }
    }

    @Suppress("DEPRECATION")


    private fun getEnvironmentInfo(): String {
        if (File("/data/adb/ksu").exists() || File("/data/adb/ksud").exists()) return "KernelSU"
        if (File("/data/adb/apatch").exists()) return "APatch"
        if (File("/sbin/magisk").exists() || File("/data/adb/magisk").exists()) return "Magisk"
        return "Unknown Root"
    }

    private var lastCpuTime: Long = 0
    private var lastSysTime: Long = 0
    private var lastCpuUsage: Double = 0.0

    private fun getCpuUsagePercent(): Double {
        try {
            val selfStat = File("/proc/self/stat").readText().split(" ")
            val sysStat = File("/proc/stat").readLines()[0].split(Regex("\\s+"))

            val uTime = selfStat[13].toLong()
            val sTime = selfStat[14].toLong()
            val procTime = uTime + sTime

            var totalTime = 0L
            for (i in 1 until sysStat.size) {
                totalTime += sysStat[i].toLongOrNull() ?: 0L
            }

            if (lastSysTime > 0 && totalTime > lastSysTime) {
                val deltaProc = procTime - lastCpuTime
                val deltaSys = totalTime - lastSysTime
                if (deltaSys > 0) {
                    lastCpuUsage = (deltaProc.toDouble() / deltaSys.toDouble()) * 100.0 * Runtime.getRuntime().availableProcessors()
                }
            }
            lastCpuTime = procTime
            lastSysTime = totalTime

            return lastCpuUsage
        } catch (e: Exception) {
            return 0.0
        }
    }

    private fun getRamUsageKb(): Long {
        try {
            File("/proc/self/status").useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("VmRSS:")) {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            return parts[1].toLongOrNull() ?: 0L
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val params = session.parms
        val headers = session.headers

        if (!isSafeHost(headers["host"])) return secureResponse(Response.Status.FORBIDDEN, "text/plain", "Invalid Host header")

        var ip = session.remoteIpAddress ?: "unknown"
        if (ip.startsWith("/")) ip = ip.substring(1)
        if (isRateLimited(ip)) return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Too Many Requests")

        val origin = headers["origin"]
        val host = headers["host"]
        if (origin != null && host != null) {
             val allowedOrigin = "http://$host"
             val allowedSecureOrigin = "https://$host"
             if (origin != allowedOrigin && origin != allowedSecureOrigin) return secureResponse(Response.Status.FORBIDDEN, "text/plain", "CSRF Forbidden")
        }

        if (uri == "/" || uri == "/index.html") return secureResponse(Response.Status.OK, "text/html", htmlBytes)

        if (method == Method.POST || method == Method.PUT) {
             val lenStr = headers["content-length"]
             if (lenStr != null) {
                  try {
                      val contentLen = lenStr.toLong()
                      val contentType = headers["content-type"] ?: ""
                      val isMultipart = contentType.contains("multipart/form-data", ignoreCase = true)
                      val maxSize = if (isMultipart) MAX_UPLOAD_SIZE else MAX_BODY_SIZE
                      if (contentLen > maxSize) return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Payload too large")
                  } catch (e: Exception) {}
             } else {
                 return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Content-Length required")
             }
        }

        var authToken = headers["x-auth-token"]
        if (authToken == null) {
            val authHeader = headers["authorization"]
            if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
                authToken = authHeader.substring(7)
            }
        }
        if (authToken == null) authToken = params["token"]

        if (authToken == null || !MessageDigest.isEqual(token.toByteArray(), authToken.toByteArray())) {
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
            json.put("auto_patch_update", fileExists("auto_patch_update"))
            json.put("hide_sensitive_props", fileExists("hide_sensitive_props"))
            json.put("spoof_region_cn", fileExists("spoof_region_cn"))
            json.put("remove_magisk_32", fileExists("remove_magisk_32"))
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
            Config.getTemplateNames().forEach { name -> templates.put(name) }
            json.put("templates", templates)
            return secureResponse(Response.Status.OK, "application/json", json.toString())
        }

        if (uri == "/api/keyboxes" && method == Method.GET) {
            val keyboxes = listKeyboxes()
            val array = JSONArray(keyboxes)
            return secureResponse(Response.Status.OK, "application/json", array.toString())
        }

        if (uri == "/api/cbox_status" && method == Method.GET) {
            val json = JSONObject()
            val locked = JSONArray()
            CboxManager.getLockedFiles().forEach { locked.put(it) }
            json.put("locked", locked)
            val unlocked = JSONArray()
            CboxManager.getUnlockedKeyboxes().forEach { k ->
                // Only show distinct filenames
                if (!k.filename.startsWith("server_")) unlocked.put(k.filename)
            }
            json.put("unlocked", unlocked)

            val servers = JSONArray()
            ServerManager.getServers().forEach { s ->
                val obj = JSONObject()
                obj.put("id", s.id)
                obj.put("status", s.lastStatus)
                servers.put(obj)
            }
            json.put("server_status", servers)

            return secureResponse(Response.Status.OK, "application/json", json.toString())
        }

        if (uri == "/api/unlock_cbox" && method == Method.POST) {
             val map = HashMap<String, String>()
             try { session.parseBody(map) } catch(e:Exception){}
             val filename = session.parms["filename"]
             val password = session.parms["password"]
             val pubKey = session.parms["public_key"]

             if (filename != null && password != null) {
                 if (CboxManager.unlock(filename, password, pubKey)) {
                     Config.updateKeyBoxes()
                     return secureResponse(Response.Status.OK, "text/plain", "Unlocked")
                 } else {
                     return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Unlock failed")
                 }
             }
             return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing params")
        }

        if (uri == "/api/servers" && method == Method.GET) {
            val json = JSONArray()
            ServerManager.getServers().forEach { s ->
                val obj = JSONObject()
                obj.put("id", s.id)
                obj.put("name", s.name)
                obj.put("url", s.url)
                obj.put("priority", s.priority)
                obj.put("enabled", s.enabled)
                obj.put("authType", s.authType)
                obj.put("autoRefresh", s.autoRefresh)
                obj.put("refreshIntervalHours", s.refreshIntervalHours)
                obj.put("lastStatus", s.lastStatus)
                obj.put("lastChecked", s.lastChecked)
                obj.put("lastAuthor", s.lastAuthor)
                json.put(obj)
            }
            return secureResponse(Response.Status.OK, "application/json", json.toString())
        }

        if (uri == "/api/server/add" && method == Method.POST) {
             val map = HashMap<String, String>()
             try { session.parseBody(map) } catch(e:Exception){}
             val jsonStr = session.parms["data"]
             if (jsonStr != null) {
                 try {
                     val obj = JSONObject(jsonStr)
                     val server = ServerManager.ServerConfig(
                         id = obj.optString("id").ifEmpty { UUID.randomUUID().toString() },
                         name = obj.getString("name"),
                         url = obj.getString("url"),
                         priority = obj.optInt("priority", 0),
                         enabled = obj.optBoolean("enabled", true),
                         authType = obj.getString("authType"),
                         authData = obj.optJSONObject("authData") ?: JSONObject(),
                         autoRefresh = obj.optBoolean("autoRefresh", true),
                         refreshIntervalHours = obj.optInt("refreshIntervalHours", 24),
                         contentPassword = obj.optString("contentPassword").ifEmpty { null },
                         contentPublicKey = obj.optString("contentPublicKey").ifEmpty { null }
                     )
                     if (obj.has("id")) {
                         ServerManager.removeServer(server.id)
                     }
                     ServerManager.addServer(server)
                     Config.updateKeyBoxes()
                     return secureResponse(Response.Status.OK, "text/plain", "Saved")
                 } catch(e: Exception) {
                     return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid JSON")
                 }
             }
             return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing data")
        }

        if (uri == "/api/server/delete" && method == Method.POST) {
             val map = HashMap<String, String>()
             try { session.parseBody(map) } catch(e:Exception){}
             val id = session.parms["id"]
             if (id != null) {
                 ServerManager.removeServer(id)
                 Config.updateKeyBoxes()
                 return secureResponse(Response.Status.OK, "text/plain", "Deleted")
             }
             return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing id")
        }

        if (uri == "/api/server/refresh" && method == Method.POST) {
             val map = HashMap<String, String>()
             try { session.parseBody(map) } catch(e:Exception){}
             val id = session.parms["id"]
             if (id != null) {
                 val s = ServerManager.getServers().find { it.id == id }
                 if (s != null) {
                     if (ServerManager.fetchFromServer(s)) {
                         Config.updateKeyBoxes()
                         return secureResponse(Response.Status.OK, "text/plain", "Refreshed")
                     } else {
                         return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Fetch Failed: ${s.lastStatus}")
                     }
                 }
             }
             return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing id")
        }

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
                json.put("imei", RandomUtils.generateLuhn(15))
                json.put("imei2", RandomUtils.generateLuhn(15))
                json.put("serial", RandomUtils.generateRandomSerial(12))
                json.put("androidId", RandomUtils.generateRandomAndroidId())
                json.put("wifiMac", RandomUtils.generateRandomMac())
                json.put("btMac", RandomUtils.generateRandomMac())
                json.put("simCountryIso", RandomUtils.generateRandomSimIso())
                json.put("carrier", RandomUtils.generateRandomCarrier())
                json.put("imsi", RandomUtils.generateLuhn(15))
                json.put("iccid", RandomUtils.generateLuhn(20))
                return secureResponse(Response.Status.OK, "application/json", json.toString())
            }
            return secureResponse(Response.Status.NOT_FOUND, "text/plain", "No templates found")
        }

        if (uri == "/api/packages" && method == Method.GET) {
            return try {
                val pm = Config.getPm()
                val packages = if (pm != null) {
                    try {
                        try {
                            pm.getInstalledPackages(0L, 0).list.map { it.packageName }
                        } catch (e: NoSuchMethodError) {
                            pm.getInstalledPackages(0, 0).list.map { it.packageName }
                        }
                    } catch (t: Throwable) {
                        Logger.e("Failed to list packages via IPC", t)
                        emptyList()
                    }
                } else {
                    emptyList()
                }
                val sortedPackages = packages.sorted()
                val array = JSONArray(sortedPackages)
                secureResponse(Response.Status.OK, "application/json", array.toString())
            } catch (e: Exception) {
                Logger.e("Failed to list packages", e)
                secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to list packages")
            }
        }

        if (uri == "/api/app_config_structured" && method == Method.GET) {
            val file = File(configDir, "app_config")
            val array = JSONArray()
            synchronized(fileLock) {
                if (file.exists()) {
                    file.useLines { lines ->
                        lines.forEach { line ->
                            if (line.isNotBlank() && !line.startsWith("#")) {
                                val parts = line.trim().split(WHITESPACE_REGEX)
                                if (parts.isNotEmpty()) {
                                    val pkg = parts[0]
                                    if (pkg.matches(PKG_NAME_REGEX)) {
                                        val tmpl = if (parts.size > 1 && parts[1] != "null") parts[1] else ""
                                        val kb = if (parts.size > 2 && parts[2] != "null") parts[2] else ""
                                        val perms = if (parts.size > 3 && parts[3] != "null") parts[3] else ""
                                        val isTmplValid = tmpl.isEmpty() || tmpl.matches(TEMPLATE_NAME_REGEX)
                                        val isKbValid = kb.isEmpty() || kb.matches(KEYBOX_FILENAME_REGEX)
                                        val isPermsValid = perms.isEmpty() || perms.matches(PERMISSIONS_REGEX)
                                        if (isTmplValid && isKbValid && isPermsValid) {
                                            val obj = JSONObject()
                                            obj.put("package", pkg)
                                            obj.put("template", tmpl)
                                            obj.put("keybox", kb)
                                            if (perms.isNotEmpty()) {
                                                val permArray = JSONArray()
                                                perms.split(",").forEach { permArray.put(it) }
                                                obj.put("permissions", permArray)
                                            }
                                            array.put(obj)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return secureResponse(Response.Status.OK, "application/json", array.toString())
        }

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
                         val permsArr = obj.optJSONArray("permissions")
                         var permsStr = "null"
                         if (permsArr != null && permsArr.length() > 0) {
                             val list = ArrayList<String>()
                             for (j in 0 until permsArr.length()) {
                                 list.add(permsArr.getString(j))
                             }
                             permsStr = list.joinToString(",")
                         }
                         if (!pkg.matches(PKG_NAME_REGEX)) return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid input: invalid characters")
                         if (tmpl != "null" && !tmpl.matches(TEMPLATE_NAME_REGEX)) return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid input")
                         if (kb != "null" && !kb.matches(KEYBOX_FILENAME_REGEX)) return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid input")
                         if (permsStr != "null" && !permsStr.matches(PERMISSIONS_REGEX)) return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid input")
                         if (pkg.contains(WHITESPACE_FIND_REGEX)) return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid input")
                         sb.append("$pkg $tmpl $kb $permsStr\n")
                     }
                     synchronized(fileLock) {
                         try {
                             val f = File(configDir, "app_config")
                             SecureFile.writeText(f, sb.toString())
                             f.setLastModified(System.currentTimeMillis())
                             return secureResponse(Response.Status.OK, "text/plain", "Saved")
                         } catch (e: Exception) {
                             Logger.e("Failed to save app_config", e)
                             return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
                         }
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
                if (filename == "keybox.xml") {
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Access denied")
                }
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
                     return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid content")
                 }
             }
             return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
        }

        if (uri == "/api/upload_keybox" && method == Method.POST) {
             val map = HashMap<String, String>()
             try { session.parseBody(map) } catch(e:Exception){}
             val filename = session.parms["filename"]
             val content = session.parms["content"] // Raw text content for XML
             // For binary upload (CBOX/ZIP), we might need multipart or read raw body
             // Since WebUI uses multipart or simple body for text...
             // Wait, for binary files, we need better upload handling.

             // Check if this is a binary upload via "file" param (Multipart)
             // NanoHTTPD's parseBody handles multipart and puts temp file path in map
             val tmpFilePath = map["file"]
             if (tmpFilePath != null) {
                 val originalName = params["filename"] ?: "upload.bin"
                 if (originalName.contains("..") || originalName.contains("/") || originalName.contains("\\")) {
                     return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid filename")
                 }
                 val tmpFile = File(tmpFilePath)
                 val bytes = tmpFile.readBytes()

                 // Process as CBOX or ZIP
                 if (originalName.endsWith(".cbox") || originalName.endsWith(".zip")) {
                     val keyboxDir = File(configDir, "keyboxes")
                     SecureFile.mkdirs(keyboxDir, 448)
                     val dest = File(keyboxDir, originalName)
                     if (!dest.canonicalPath.startsWith(keyboxDir.canonicalPath)) {
                         return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Path traversal attempt detected")
                     }
                     SecureFile.writeBytes(dest, bytes)
                     // Trigger refresh
                     CboxManager.refresh()
                     Config.updateKeyBoxes()
                     return secureResponse(Response.Status.OK, "text/plain", "Uploaded")
                 }
             }

             // Legacy XML upload
             if (filename != null && content != null && filename.endsWith(".xml") && filename.matches(FILENAME_REGEX)) {
                 synchronized(fileLock) {
                     try {
                         val keyboxes = CertHack.parseKeyboxXml(StringReader(content), filename)
                         if (keyboxes.isEmpty()) return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid Keybox XML")
                     } catch (e: Exception) {
                         return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid Keybox XML")
                     }
                     val keyboxDir = File(configDir, "keyboxes")
                     SecureFile.mkdirs(keyboxDir, 448)
                     val file = File(keyboxDir, filename)
                     try {
                         SecureFile.writeText(file, content)
                         return secureResponse(Response.Status.OK, "text/plain", "Saved")
                     } catch (e: Exception) {
                         Logger.e("Failed to save keybox", e)
                         return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed: " + e.message)
                     }
                 }
             }
             return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid request")
        }

        if (uri == "/api/verify_keyboxes" && method == Method.POST) {
             try {
                val crl = KeyboxVerifier.fetchCrl()
                synchronized(fileLock) {
                    val results = KeyboxVerifier.verify(configDir) { crl }
                    val json = createKeyboxVerificationJson(results)
                    return secureResponse(Response.Status.OK, "application/json", json)
                }
             } catch(e: Exception) {
                 Logger.e("Failed to verify keyboxes", e)
                 return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
             }
        }

        if (uri == "/api/toggle" && method == Method.POST) {
             val map = HashMap<String, String>()
             try { session.parseBody(map) } catch(e:Exception){}
             val setting = session.parms["setting"]
             val value = session.parms["value"]
             if (setting != null && value != null) {
                 if (toggleFile(setting, value.toBoolean())) return secureResponse(Response.Status.OK, "text/plain", "Toggled")
             }
             return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
        }

        if (uri == "/api/reload" && method == Method.POST) {
             try {
                synchronized(fileLock) {
                    File(configDir, "target.txt").setLastModified(System.currentTimeMillis())
                    return secureResponse(Response.Status.OK, "text/plain", "Reloaded")
                }
             } catch(e: Exception) {
                 Logger.e("Failed to reload", e)
                 return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
             }
        }

        if (uri == "/api/reset_drm" && method == Method.POST) {
             try {
                 synchronized(fileLock) {
                     val dirs = listOf("/data/vendor/mediadrm", "/data/mediadrm")
                     dirs.forEach { path ->
                         try { File(path).walkBottomUp().forEach { if (it.path != path) it.delete() } } catch (e: Exception) {}
                     }
                     val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "killall -9 android.hardware.drm-service.widevine android.hardware.drm-service.clearkey mediadrmserver || true"))
                     p.waitFor()
                     return secureResponse(Response.Status.OK, "text/plain", "DRM ID Regenerated")
                 }
             } catch(e: Exception) {
                 return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
             }
        }

        if (uri == "/api/fetch_beta" && method == Method.POST) {
             try {
                 val result = BetaFetcher.fetchAndApply(null)
                 if (result.success) return secureResponse(Response.Status.OK, "text/plain", "Success: ${result.profile?.model}")
                 else return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed: ${result.error}")
             } catch(e: Exception) {
                 return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
             }
        }

        if (uri == "/api/stats" && method == Method.GET) {
            val count = fetchTelegramCount()
            val banned = fetchBannedCount()
            val json = JSONObject()
            json.put("members", count)
            json.put("banned", banned)
            return secureResponse(Response.Status.OK, "application/json", json.toString())
        }

        if (uri == "/api/backup" && method == Method.GET) {
            return try {
                val zipBytes = createBackupZip(configDir)
                val response = newFixedLengthResponse(Response.Status.OK, "application/zip", ByteArrayInputStream(zipBytes), zipBytes.size.toLong())
                response.addHeader("Content-Disposition", "attachment; filename=\"cleverestricky_backup.zip\"")
                response
            } catch (e: Exception) {
                Logger.e("Failed to create backup", e)
                secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Backup failed")
            }
        }

        if (uri == "/api/language" && method == Method.GET) {
            val langFile = File(configDir, "lang.json")
            if (langFile.exists()) {
                return secureResponse(Response.Status.OK, "application/json", readFile("lang.json"))
            } else {
                return secureResponse(Response.Status.NOT_FOUND, "application/json", "{}")
            }
        }

        if (uri == "/api/resource_usage" && method == Method.GET) {
            val json = JSONObject()
            val keyboxCount = CertHack.getKeyboxCount()
            json.put("keybox_count", keyboxCount)
            val appConfigSize = File(configDir, "app_config").length()
            json.put("app_config_size", appConfigSize)
            json.put("global_mode", fileExists("global_mode"))
            json.put("rkp_bypass", fileExists("rkp_bypass"))
            json.put("tee_broken_mode", fileExists("tee_broken_mode"))
            json.put("real_ram_kb", getRamUsageKb())
            json.put("real_cpu", getCpuUsagePercent())
            json.put("environment", getEnvironmentInfo())
            return secureResponse(Response.Status.OK, "application/json", json.toString())
        }

        if (uri == "/api/restore" && method == Method.POST) {
             val files = HashMap<String, String>()
             try { session.parseBody(files) } catch (e: Exception) { return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body") }
             val tmpFilePath = files["file"]
             if (tmpFilePath != null) {
                 val tmpFile = File(tmpFilePath)
                 return try {
                     synchronized(fileLock) {
                         restoreBackupZip(configDir, tmpFile.inputStream())
                         val target = File(configDir, "target.txt")
                         if (target.exists()) target.setLastModified(System.currentTimeMillis())
                         secureResponse(Response.Status.OK, "text/plain", "Restore Successful")
                     }
                 } catch (e: Exception) {
                     Logger.e("Failed to restore backup", e)
                     secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Restore failed: ${e.message}")
                 } finally { try { tmpFile.delete() } catch(e: Exception) {} }
             }
             return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "No file uploaded")
        }

        return secureResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }

    private fun secureResponse(status: Response.Status, mimeType: String, txt: String): Response {
        val response = newFixedLengthResponse(status, mimeType, txt)
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("X-Frame-Options", "DENY")
        response.addHeader("X-XSS-Protection", "1; mode=block")
        response.addHeader("Content-Security-Policy", "default-src 'self' 'unsafe-inline'")
        response.addHeader("Referrer-Policy", "no-referrer")
        return response
    }

    private fun secureResponse(status: Response.Status, mimeType: String, bytes: ByteArray): Response {
        val response = newFixedLengthResponse(status, mimeType, ByteArrayInputStream(bytes), bytes.size.toLong())
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("X-Frame-Options", "DENY")
        response.addHeader("X-XSS-Protection", "1; mode=block")
        response.addHeader("Content-Security-Policy", "default-src 'self' 'unsafe-inline'")
        response.addHeader("Referrer-Policy", "no-referrer")
        return response
    }

    private fun getAppName(): String {
        return String(charArrayOf(67.toChar(), 108.toChar(), 101.toChar(), 118.toChar(), 101.toChar(), 114.toChar(), 101.toChar(), 115.toChar(), 84.toChar(), 114.toChar(), 105.toChar(), 99.toChar(), 107.toChar(), 121.toChar()))
    }

    private val htmlBytes by lazy { htmlContent.toByteArray() }

    private val htmlContent by lazy {
        """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>${getAppName()}</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        :root { --bg: #0B0B0C; --fg: #E5E7EB; --accent: #D1D5DB; --panel: #161616; --border: #333; --input-bg: #1A1A1A; --success: #34D399; --danger: #EF4444; }
        body { background-color: var(--bg); color: var(--fg); font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; margin: 0; padding: 0; }
        .island-container { display: flex; justify-content: center; position: fixed; top: 10px; width: 100%; z-index: 1000; pointer-events: none; }
        .island { background: #000; color: #fff; border-radius: 30px; height: 35px; width: 120px; display: flex; align-items: center; justify-content: center; transition: all 0.4s cubic-bezier(0.175, 0.885, 0.32, 1.275); box-shadow: 0 4px 15px rgba(0,0,0,0.5); font-size: 0.8em; font-weight: 500; opacity: 0; transform: translateY(-20px); }
        .island.active { width: auto; min-width: 250px; padding: 12px 24px; opacity: 1; transform: translateY(0); font-size: 0.9em; }
        .island.error { background: #330000; border: 1px solid var(--danger); }
        .island.error #islandText { color: #FECACA; }
        .spinner { width: 14px; height: 14px; border: 2px solid #fff; border-top-color: transparent; border-radius: 50%; animation: spin 0.8s linear infinite; margin-right: 10px; display: none; }
        .island.working .spinner { display: block; }
        .error-icon { display: none; margin-right: 10px; color: var(--danger); font-size: 1.2em; }
        .island.error .error-icon { display: block; }
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
        input[type="text"], input[type="password"], textarea, select { background: var(--input-bg); border: 1px solid var(--border); color: #fff; padding: 12px 14px; border-radius: 6px; width: 100%; box-sizing: border-box; font-family: inherit; transition: border-color 0.2s; font-size: 0.95em; min-height: 44px; }
        input[type="text"]:focus, textarea:focus, select:focus { border-color: var(--accent); outline: none; }
        button { background: var(--border); border: none; color: var(--fg); padding: 12px 24px; border-radius: 6px; cursor: pointer; font-family: inherit; font-weight: 500; font-size: 0.95em; transition: all 0.2s; text-transform: uppercase; letter-spacing: 0.5px; min-height: 44px; touch-action: manipulation; }
        button:hover { background: #444; }
        button:active { transform: scale(0.98); }
        button.primary { background: var(--accent); color: #000; }
        button.primary:hover { background: #fff; box-shadow: 0 0 10px rgba(255,255,255,0.2); }
        button.danger { background: rgba(239, 68, 68, 0.2); color: var(--danger); border: 1px solid var(--danger); }
        button.danger:hover { background: var(--danger); color: #fff; }
        input[type="checkbox"].toggle { appearance: none; width: 52px; height: 32px; background: #333; border-radius: 16px; position: relative; cursor: pointer; transition: background 0.3s; }
        input[type="checkbox"].toggle::after { content: ''; position: absolute; top: 3px; left: 3px; width: 26px; height: 26px; background: #fff; border-radius: 50%; transition: transform 0.3s; }
        input[type="checkbox"].toggle:checked { background: var(--accent); }
        input[type="checkbox"].toggle:checked::after { transform: translateX(20px); }
        textarea:disabled, input:disabled, select:disabled, button:disabled { opacity: 0.5; cursor: not-allowed; }
        table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 0.9em; }
        th { text-align: left; padding: 10px; border-bottom: 1px solid var(--border); color: #888; font-weight: 500; }
        td { padding: 10px; border-bottom: 1px solid var(--border); color: #ccc; }
        .tag { display: inline-block; padding: 2px 8px; border-radius: 10px; background: #333; font-size: 0.75em; margin-right: 5px; }
        .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 15px; }
        .section-header { font-size: 0.8em; color: #666; text-transform: uppercase; letter-spacing: 1px; margin: 15px 0 5px 0; }
        .drag-over { border-color: var(--accent) !important; background: rgba(255,255,255,0.05); }
        #dropZone:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
        ::-webkit-scrollbar { width: 8px; }
        ::-webkit-scrollbar-track { background: var(--bg); }
        ::-webkit-scrollbar-thumb { background: #333; border-radius: 4px; }
        ::-webkit-scrollbar-thumb:hover { background: #555; }
        .locked-item { border: 1px solid var(--danger); background: rgba(239, 68, 68, 0.1); padding: 10px; border-radius: 6px; margin-bottom: 10px; }
        .server-item { border: 1px solid var(--border); background: #1a1a1a; padding: 10px; border-radius: 6px; margin-bottom: 10px; display: flex; justify-content: space-between; align-items: center; }
        .status-badge { font-size: 0.75em; padding: 2px 6px; border-radius: 4px; margin-left: 10px; }
        .status-OK { background: rgba(52, 211, 153, 0.2); color: #34D399; }
        .status-ERROR { background: rgba(239, 68, 68, 0.2); color: #EF4444; }
        input[type="checkbox"].toggle:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
        input[type="checkbox"].toggle:disabled { opacity: 0.5; cursor: not-allowed; }
        input.valid { border-color: var(--success); }
        input.invalid { border-color: var(--danger); }
        .error-msg { color: var(--danger); font-size: 0.8em; margin-top: 4px; display: none; }
        .res-desc { display: block; font-size: 0.8em; color: #888; margin-top: 4px; line-height: 1.3; }
        @media screen and (max-width: 600px) {
            .responsive-table thead { display: none; }
            .responsive-table tr { display: block; border: 1px solid var(--border); margin-bottom: 10px; border-radius: 8px; background: #1a1a1a; }
            .responsive-table td { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 1px solid #333; padding: 12px; min-height: 40px; }
            .responsive-table td:last-child { border-bottom: none; }
            .responsive-table td::before { content: attr(data-label); color: #888; font-weight: 500; margin-right: 10px; min-width: 100px; display: inline-block; }
            .responsive-table td > div, .responsive-table td > span { text-align: right; flex: 1; word-break: break-word; }
        }
    </style>
</head>
<body>
    <div class="island-container"><div id="island" class="island" role="status" aria-live="polite"><div class="spinner"></div><div class="error-icon">⚠️</div><span id="islandText">Notification</span></div></div>
    <h1>${getAppName()} <span style="font-size:0.5em; vertical-align:middle; color:var(--accent); opacity:0.7; border: 1px solid var(--accent); border-radius: 4px; padding: 2px 6px; margin-left: 10px;">BETA</span></h1>
    <div class="tabs" role="tablist">
        <div class="tab active" id="tab_dashboard" onclick="switchTab('dashboard')" role="tab" tabindex="0" aria-selected="true" aria-controls="dashboard" onkeydown="handleTabNavigation(event, 'dashboard')">Dashboard</div>
        <div class="tab" id="tab_spoof" onclick="switchTab('spoof')" role="tab" tabindex="-1" aria-selected="false" aria-controls="spoof" onkeydown="handleTabNavigation(event, 'spoof')">Spoofing</div>
        <div class="tab" id="tab_apps" onclick="switchTab('apps')" role="tab" tabindex="-1" aria-selected="false" aria-controls="apps" onkeydown="handleTabNavigation(event, 'apps')">Apps</div>
        <div class="tab" id="tab_keys" onclick="switchTab('keys')" role="tab" tabindex="-1" aria-selected="false" aria-controls="keys" onkeydown="handleTabNavigation(event, 'keys')">Keyboxes</div>
        <div class="tab" id="tab_info" onclick="switchTab('info')" role="tab" tabindex="-1" aria-selected="false" aria-controls="info" onkeydown="handleTabNavigation(event, 'info')">Info & Resources</div> <div class="tab" id="tab_guide" onclick="switchTab('guide')" role="tab" tabindex="-1" aria-selected="false" aria-controls="guide" onkeydown="handleTabNavigation(event, 'guide')">Guide</div>
        <div class="tab" id="tab_editor" onclick="switchTab('editor')" role="tab" tabindex="-1" aria-selected="false" aria-controls="editor" onkeydown="handleTabNavigation(event, 'editor')">Editor</div>
    </div>

    <div id="dashboard" class="content active" role="tabpanel" aria-labelledby="tab_dashboard">
        <div class="panel">
            <h3>System Control</h3>
            <div class="row"><label for="global_mode">Global Mode</label><input type="checkbox" class="toggle" id="global_mode" onchange="toggle('global_mode')"></div>
            <div class="row"><label for="tee_broken_mode">TEE Broken Mode</label><input type="checkbox" class="toggle" id="tee_broken_mode" onchange="toggle('tee_broken_mode')"></div>
            <div class="row"><label for="rkp_bypass">RKP Bypass (Strong)</label><input type="checkbox" class="toggle" id="rkp_bypass" onchange="toggle('rkp_bypass')"></div>
            <div class="row"><label for="auto_beta_fetch">Auto Beta Fetch</label><input type="checkbox" class="toggle" id="auto_beta_fetch" onchange="toggle('auto_beta_fetch')"></div>
            <div class="row"><label for="auto_keybox_check">Auto Keybox Check</label><input type="checkbox" class="toggle" id="auto_keybox_check" onchange="toggle('auto_keybox_check')"></div>
            <div class="row"><label for="auto_patch_update">Auto Patch Update</label><input type="checkbox" class="toggle" id="auto_patch_update" onchange="toggle('auto_patch_update')"></div>
            <div class="row"><label for="random_on_boot">Randomize IMEI on Boot</label><input type="checkbox" class="toggle" id="random_on_boot" onchange="toggle('random_on_boot')"></div>
            <div class="row"><label style="opacity:0.7;">Random Serial on Boot</label><div style="font-size:0.8em; color:var(--accent); border:1px solid var(--accent); padding:2px 8px; border-radius:4px;">Always Enabled (Required for Anti-Fingerprinting & Keybox Protection)</div></div>
            <div class="section-header">Boot Properties</div>
            <div class="row"><label for="hide_sensitive_props">Hide Sensitive Props</label><input type="checkbox" class="toggle" id="hide_sensitive_props" onchange="toggle('hide_sensitive_props')"></div>
            <div class="row"><label for="spoof_region_cn">Spoof Region (CN)</label><input type="checkbox" class="toggle" id="spoof_region_cn" onchange="toggle('spoof_region_cn')"></div>
            <div class="row"><label for="remove_magisk_32" style="color:var(--danger)">Remove Magisk 32-bit</label><input type="checkbox" class="toggle" id="remove_magisk_32" onchange="toggle('remove_magisk_32')"></div>
            <div style="margin-top:20px; border-top: 1px solid var(--border); padding-top: 15px;">
                <div class="row"><span id="keyboxStatus" style="font-size:0.9em; color:var(--success);">Active</span><button onclick="runWithState(this, 'Reloading...', reloadConfig)">Reload Config</button></div>
            </div>
        </div>
        <div class="panel"><h3>Configuration Management</h3><div class="grid-2"><button onclick="backupConfig()">Backup Config</button><button onclick="document.getElementById('restoreInput').click()">Restore Config</button><input type="file" id="restoreInput" style="display:none" onchange="restoreConfig(this)" accept=".zip"></div></div>
        <div class="panel" style="text-align:center;"><h3>Community</h3><div id="communityCount" style="font-size:2em; font-weight:300; margin: 10px 0;">...</div><div id="bannedCount" style="font-size:0.9em; color:#888; margin-bottom:10px;">Global Banned Keys: ...</div><a href="https://t.me/cleverestech" target="_blank" style="display:inline-block; margin-top:10px; color:var(--accent); text-decoration:none; font-size:0.9em; border:1px solid var(--border); padding:5px 15px; border-radius:15px;">Join Channel</a><div style="margin-top:15px;"><div class="section-header">Donate</div><div style="margin-top:5px;"><span style="color:#888; font-size:0.85em;">Binance ID: 114574830</span> <button onclick="copyToClipboard('114574830', 'Copied Binance ID!', this)" style="padding:2px 8px; font-size:0.8em;" title="Click to copy ID" aria-label="Copy Binance ID"><span aria-hidden="true">📋</span></button></div></div></div>
    </div>

    <div id="spoof" class="content" role="tabpanel" aria-labelledby="tab_spoof">
        <div class="panel">
            <h3>DRM / Streaming</h3>
            <div class="row"><label for="drm_fix">Netflix / DRM Fix</label><div style="display:flex; align-items:center; gap:10px;"><button onclick="editDrmConfig()" style="padding:8px 16px; font-size:0.85em; min-height:36px;">Edit</button><input type="checkbox" class="toggle" id="drm_fix" onchange="toggle('drm_fix')"></div></div>
            <div class="row"><label for="random_drm_on_boot">Randomize on Boot</label><input type="checkbox" class="toggle" id="random_drm_on_boot" onchange="toggle('random_drm_on_boot')"></div>
            <div class="row" style="margin-top:10px;"><label style="font-size:0.8em; color:#888;">Reset Identity</label><button onclick="runWithState(this, 'Regenerating...', resetDrmId)" style="padding:8px 16px; font-size:0.85em; min-height:36px;">Regenerate DRM ID</button></div>
        </div>
        <div class="panel"><h3>Beta Profile Fetcher</h3><button onclick="runWithState(this, 'Fetching...', fetchBeta)" style="width:100%">Fetch & Apply Latest Beta</button></div>
        <div class="panel">
            <h3>Identity Manager</h3>
            <label for="templateSelect" style="display:block; font-size:0.85em; color:#888; margin-bottom:8px;">Select a verified device identity to spoof globally.</label>
            <select id="templateSelect" onchange="previewTemplate()" style="margin-bottom:15px;"></select>
            <div id="templatePreview" style="background:var(--input-bg); border-radius:8px; padding:15px; margin-bottom:15px;">
                <div class="grid-2"><div><div class="section-header">Device</div><div id="pModel"></div></div><div><div class="section-header">Manufacturer</div><div id="pManuf"></div></div></div>
                <div class="section-header">Fingerprint <button onclick="copyToClipboard(document.getElementById('pFing').innerText, 'Fingerprint Copied', this)" style="font-size:0.9em; padding:2px 6px; margin-left:5px;" title="Copy fingerprint" aria-label="Copy Fingerprint"><span aria-hidden="true">📋</span></button></div><div style="font-family:monospace; font-size:0.8em; color:#999; word-break:break-all;" id="pFing"></div>
            </div>
            <div class="grid-2"><button onclick="runWithState(this, 'Generating...', generateRandomIdentity)" class="primary">Generate Random</button><button onclick="applySpoofing(this)">Apply Global</button></div>
        </div>
        <div class="panel"><h3>System-Wide Spoofing (Global Hardware)</h3>
            <div class="section-header">Modem</div><div class="grid-2">
                <div><label for="inputImei">IMEI</label><input type="text" id="inputImei" placeholder="35..." style="font-family:monospace;" inputmode="numeric" oninput="validateRealtime(this, 'luhn')"></div>
                <div><label for="inputImsi">IMSI</label><input type="text" id="inputImsi" placeholder="310..." style="font-family:monospace;" inputmode="numeric" oninput="validateRealtime(this, 'imsi')"></div>
            </div>
            <div class="grid-2" style="margin-top:10px;">
                <div><label for="inputIccid">ICCID</label><input type="text" id="inputIccid" placeholder="89..." style="font-family:monospace;" inputmode="numeric" oninput="validateRealtime(this, 'luhn')"></div>
                <div><label for="inputSerial">Serial</label><input type="text" id="inputSerial" placeholder="Alphanumeric..." style="font-family:monospace;" autocapitalize="characters" oninput="validateRealtime(this, 'alphanum')"></div>
            </div>
            <div class="section-header">Network</div><div class="grid-2">
                <div><label for="inputWifiMac">WiFi MAC</label><input type="text" id="inputWifiMac" placeholder="00:11:22:33:44:55" style="font-family:monospace;" autocapitalize="characters" oninput="validateRealtime(this, 'mac')"></div>
                <div><label for="inputBtMac">BT MAC</label><input type="text" id="inputBtMac" placeholder="00:11:22:33:44:55" style="font-family:monospace;" autocapitalize="characters" oninput="validateRealtime(this, 'mac')"></div>
            </div>
            <div class="section-header">Operator</div><div class="grid-2">
                <div><label for="inputSimIso">SIM ISO</label><input type="text" id="inputSimIso" placeholder="ISO" oninput="validateRealtime(this, 'iso')"></div>
                <div><label for="inputSimOp">Operator</label><input type="text" id="inputSimOp" placeholder="Operator"></div>
            </div>
            <div style="margin-top:15px; text-align:right;"><button onclick="applySpoofing(this)" class="danger">Apply System-Wide</button></div>
        </div>
    </div>

    <div id="apps" class="content" role="tabpanel" aria-labelledby="tab_apps">
        <div class="panel">
            <h3>New Rule</h3>
            <div style="margin-bottom:10px;"><label for="appPkg">Package Name</label><input type="text" id="appPkg" list="pkgList" placeholder="Package Name" oninput="toggleAddButton()" onkeydown="if(event.key==='Enter') addAppRule()"><datalist id="pkgList"></datalist></div>
            <div class="grid-2" style="margin-bottom:10px;"><div><label for="appTemplate">Identity Profile</label><select id="appTemplate"><option value="null">No Identity Spoof</option></select></div><div><label for="appKeybox">Custom Keybox</label><input type="text" id="appKeybox" list="keyboxList" placeholder="Custom Keybox" onkeydown="if(event.key==='Enter') addAppRule()"><datalist id="keyboxList"></datalist></div></div>
            <div class="section-header">Blank Permissions (Privacy)</div><div style="display:flex; gap:15px;"><div class="row"><input type="checkbox" id="permContacts" class="toggle"><label for="permContacts">Contacts</label></div><div class="row"><input type="checkbox" id="permMedia" class="toggle"><label for="permMedia">Media</label></div></div>
            <button id="btnAddRule" class="primary" style="width:100%" onclick="addAppRule()" disabled>Add Rule</button>
        </div>
        <div class="panel">
            <h3>Active Rules</h3><input type="search" id="appFilter" placeholder="Filter..." oninput="renderAppTable()" style="width:100%; margin-bottom:10px;" aria-label="Filter rules">
            <table id="appTable" class="responsive-table"><thead><tr><th>Package</th><th>Profile</th><th>Keybox</th><th></th></tr></thead><tbody></tbody></table>
            <div style="margin-top:15px; text-align:right;"><button onclick="runWithState(this, 'Saving...', saveAppConfig)" class="primary">Save Configuration</button></div>
        </div>
    </div>

    <div id="keys" class="content" role="tabpanel" aria-labelledby="tab_keys">
        <div id="lockedSection" style="display:none;">
            <div class="panel" style="border-color:var(--danger);">
                <h3 style="color:var(--danger);">🔐 Encrypted Keyboxes Detected</h3>
                <div id="lockedList"></div>
            </div>
        </div>

        <div class="panel">
            <h3>Remote Servers</h3>
            <div id="serverList"></div>
            <button onclick="document.getElementById('addServerForm').style.display='block'" class="primary" style="width:100%">+ Add Server</button>

            <div id="addServerForm" style="display:none; margin-top:15px; border-top:1px solid var(--border); padding-top:15px;">
                <input type="text" id="srvName" placeholder="Name" style="margin-bottom:5px;">
                <input type="text" id="srvUrl" placeholder="URL (HTTPS)" style="margin-bottom:5px;">
                <select id="srvAuthType" style="margin-bottom:5px;">
                    <option value="NONE">No Auth</option>
                    <option value="BEARER">Bearer Token</option>
                    <option value="BASIC">Basic Auth</option>
                    <option value="API_KEY">API Key</option>
                </select>
                <div id="authFields"></div>
                <button onclick="addServer()" class="primary">Save Server</button>
            </div>
        </div>

        <div class="panel">
            <h3>Upload Keybox / CBOX</h3>
            <div id="dropZone" role="button" tabindex="0" style="border: 2px dashed var(--border); border-radius: 6px; padding: 20px; text-align: center; margin-bottom: 10px; cursor: pointer;" onclick="document.getElementById('kbFilePicker').click()" onkeydown="if(event.key==='Enter'||event.key===' '){event.preventDefault(); document.getElementById('kbFilePicker').click();}">
                <label for="kbFilename" style="display:none">Keybox File</label>
                <input type="file" id="kbFilePicker" style="display:none" onchange="loadFileContent(this)" onclick="event.stopPropagation(); this.value = null" aria-label="Upload Keybox File">
                <label for="kbContent" style="display:block; font-size:0.85em; color:#888; margin-bottom:4px;">Keybox Content (XML)</label>
                <textarea id="kbContent" placeholder="XML Content" style="height:100px; font-family:monospace; font-size:0.8em;" aria-label="Keybox XML Content"></textarea>
                <div id="dropZoneContent"><div style="font-size: 2em; margin-bottom: 10px;">📂</div><div style="font-size: 0.9em; color: #888;">Select .xml, .cbox, or .zip</div></div>
            </div>
        </div>
        <div class="panel">
            <h3>Stored Keyboxes</h3>
            <div id="storedKeyboxesList" style="max-height: 200px; overflow-y: auto;"></div>
        </div>
        <div class="panel">
            <div class="row"><h3>Verification</h3><button onclick="runWithState(this, 'Verifying...', verifyKeyboxes)">Check All</button></div>
            <div id="verifyResult" style="font-family:monospace; font-size:0.85em;"></div>
        </div>
    </div>

    <div id="info" class="content" role="tabpanel" aria-labelledby="tab_info">
        <div class="panel">
            <h3 data-i18n="resource_monitor_title">Resource Monitor</h3>
            <p style="font-size:0.9em; color:#888;">Monitor resource usage and manage feature impact. <span style="color:var(--danger)">Disabling security features may expose your device.</span></p>
            <table id="resourceTable" class="responsive-table">
                <thead>
                    <tr>
                        <th data-i18n="col_feature">Feature</th>
                        <th data-i18n="col_status">Status</th>
                        <th data-i18n="col_ram">Est. RAM</th>
                        <th data-i18n="col_cpu">Est. CPU</th>
                        <th data-i18n="col_security">Security Impact</th>
                    </tr>
                </thead>
                <tbody id="resourceBody">
                </tbody>
            </table>
            <div style="margin-top:10px; font-size:0.8em; color:#666;">
                * RAM estimates are approximate based on loaded objects.
            </div>
        </div>
        <div class="panel">
            <h3>Language Support</h3>
            <p>The module is English-first, but supports community translations.</p>
            <p>To add a language, place a <code>lang.json</code> file in <code>/data/adb/cleverestricky/</code>.</p>
            <div class="grid-2">
                <button onclick="downloadLangTemplate()">Download Template</button>
                <button onclick="loadLanguage()">Reload Language File</button>
            </div>
        </div>
    </div>
    <div id="guide" class="content" role="tabpanel" aria-labelledby="tab_guide">
        <div class="panel">
            <h3>Encrypted Keybox Distribution</h3>
            <p>This module supports secure keybox distribution formats to protect key material.</p>

            <h4>1. .cbox Files</h4>
            <p>Encrypted containers that require a password. Once unlocked, they are cached securely on your device using hardware encryption (if available).</p>

            <h4>2. Remote Servers</h4>
            <p>Fetch keyboxes automatically from community servers. Supports authentication (Tokens, Telegram, etc).</p>

            <h4>3. Creating .cbox Files</h4>
            <p>Use the <b>Encryptor App</b> to create .cbox files from your raw XML keyboxes.</p>
            <ul>
                <li>Generate a signing key in the app.</li>
                <li>Select your keybox.xml.</li>
                <li>Set a password and author name.</li>
                <li>Share the .cbox file and Public Key with users.</li>
            </ul>
        </div>
    </div>

    <div id="editor" class="content" role="tabpanel" aria-labelledby="tab_editor">
        <div class="panel">
            <div class="row"><select id="fileSelector" onchange="loadFile()" style="width:70%;" aria-label="Select file to edit"><option value="target.txt">target.txt</option><option value="security_patch.txt">security_patch.txt</option><option value="spoof_build_vars">spoof_build_vars</option><option value="app_config">app_config</option><option value="drm_fix">drm_fix</option></select><button id="saveBtn" onclick="handleSave(this)" title="Ctrl+S">Save</button></div>
            <textarea id="fileEditor" style="height:500px; font-family:monospace; margin-top:10px; line-height:1.4;" aria-label="File Content" oninput="updateSaveButtonState()" onkeydown="if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='s'){event.preventDefault();handleSave(document.getElementById('saveBtn'));}"></textarea>
        </div>
    </div>

    <script>
        const baseUrl = '/api';
        const urlParams = new URLSearchParams(window.location.search);
        const token = urlParams.get('token');
        function getAuthUrl(path) { return path; }
        async function fetchAuth(url, options = {}) {
            if (!token) throw new Error('No token');
            const headers = options.headers || {};
            headers['X-Auth-Token'] = token;
            return fetch(url, { ...options, headers });
        }
        function copyToClipboard(text, msg, btn) {
            const originalHtml = btn.innerHTML;
            navigator.clipboard.writeText(text).then(() => {
                btn.innerText = '✓ Copied';
                notify(msg);
                setTimeout(() => btn.innerHTML = originalHtml, 2000);
            }).catch(() => { notify('Copy failed', 'error'); });
        }
        let notifyTimeout;
        function notify(msg, type = 'normal') {
            if (notifyTimeout) clearTimeout(notifyTimeout);
            const island = document.getElementById('island');
            let iconHtml = '';
            if (type === 'working') {
                iconHtml = '<div class="spinner"></div>';
            } else if (type === 'error') {
                iconHtml = '<span class="island-icon">❌</span>';
            } else {
                iconHtml = '<span class="island-icon">✅</span>';
            }

            // Escape HTML for message
            const div = document.createElement('div');
            div.innerText = msg;
            const safeMsg = div.innerHTML;

            document.getElementById('islandText').innerHTML = iconHtml + '<span style="vertical-align: middle;">' + safeMsg + '</span>';
            island.className = 'island active ' + type;
            if (type === 'working') {
                // Keep active until cleared manually or by another notify
            } else {
                notifyTimeout = setTimeout(() => island.classList.remove('active'), 3000);
            }
        }
        function validateRealtime(input, type) {
            const val = input.value.trim();
            if (!val) {
                input.classList.remove('valid', 'invalid');
                const next = input.nextElementSibling;
                if (next && next.classList.contains('error-msg')) next.remove();
                return;
            }

            let isValid = false;
            let msg = "";

            if (type === 'luhn') {
                if (!/^\d+${'$'}/.test(val)) {
                    msg = "Must be numeric";
                } else {
                     const len = val.length;
                     if (input.id.includes('Imei') && len !== 15) msg = "Must be 15 digits";
                     else if (input.id.includes('Iccid') && (len < 19 || len > 20)) msg = "Must be 19-20 digits";

                     if (!msg) {
                         let sum = 0;
                         let shouldDouble = false;
                         for (let i = val.length - 1; i >= 0; i--) {
                             let digit = parseInt(val.charAt(i));
                             if (shouldDouble) {
                                 digit *= 2;
                                 if (digit > 9) digit -= 9;
                             }
                             sum += digit;
                             shouldDouble = !shouldDouble;
                         }
                         if (sum % 10 === 0) isValid = true;
                         else msg = "Invalid Checksum";
                     }
                }
            } else if (type === 'imsi') {
                if (!/^\d+${'$'}/.test(val)) {
                    msg = "Must be numeric";
                } else if (val.length !== 15) {
                    msg = "Must be 15 digits";
                } else {
                    isValid = true;
                }
            } else if (type === 'mac') {
                if (/^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})${'$'}/.test(val)) isValid = true;
                else msg = "Invalid MAC (XX:XX:XX:XX:XX:XX)";
            } else if (type === 'iso') {
                if (/^[a-zA-Z]{2}${'$'}/.test(val)) isValid = true;
                else msg = "Must be 2 letters";
            } else if (type === 'alphanum') {
                if (/^[a-zA-Z0-9]*${'$'}/.test(val)) isValid = true;
                else msg = "Alphanumeric only";
            }

            if (isValid) {
                input.classList.add('valid');
                input.classList.remove('invalid');
                const next = input.nextElementSibling;
                if (next && next.classList.contains('error-msg')) next.remove();
            } else {
                input.classList.add('invalid');
                input.classList.remove('valid');
                let next = input.nextElementSibling;
                if (!next || !next.classList.contains('error-msg')) {
                    const span = document.createElement('div');
                    span.className = 'error-msg';
                    input.parentNode.insertBefore(span, input.nextSibling);
                    next = span;
                }
                next.innerText = msg;
                next.style.display = 'block';
            }
        }
        async function runWithState(btn, text, task) {
             const orig = btn.innerText; btn.disabled = true; btn.innerText = text;
             try { await task(); } finally { btn.disabled = false; btn.innerText = orig; }
        }
        function switchTab(id) {
            document.querySelectorAll('.tab').forEach(t => {
                t.classList.remove('active');
                t.setAttribute('aria-selected', 'false');
                t.setAttribute('tabindex', '-1');
            });
            document.querySelectorAll('.content').forEach(c => c.classList.remove('active'));
            const activeTab = document.getElementById('tab_' + id);
            activeTab.classList.add('active');
            activeTab.setAttribute('aria-selected', 'true');
            activeTab.setAttribute('tabindex', '0');
            document.getElementById(id).classList.add('active');
            if (id === 'apps') loadAppConfig();
            if (id === 'keys') loadKeyInfo();
            if (id === 'info') loadResourceUsage();
        }

        function handleTabNavigation(e, id) {
            if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
                e.preventDefault();
                const tabs = ['dashboard', 'spoof', 'apps', 'keys', 'guide', 'editor'];
                let idx = tabs.indexOf(id);
                if (e.key === 'ArrowRight') idx = (idx + 1) % tabs.length;
                else idx = (idx - 1 + tabs.length) % tabs.length;
                const nextId = tabs[idx];
                switchTab(nextId);
                document.getElementById('tab_' + nextId).focus();
            }
        }

        // --- Keys Tab Logic ---
        async function loadKeyInfo() {
            loadKeyboxes(); // existing

            // Load CBOX Status
            try {
                const res = await fetchAuth('/api/cbox_status');
                const data = await res.json();

                // Locked
                const lockedList = document.getElementById('lockedList');
                lockedList.innerHTML = '';
                if (data.locked.length > 0) {
                    document.getElementById('lockedSection').style.display = 'block';
                    data.locked.forEach(f => {
                        const div = document.createElement('div');
                        div.className = 'locked-item';
                        div.innerHTML = `<div style="font-weight:bold; margin-bottom:5px;">${'$'}{f}</div>
                        <input type="password" id="pwd_${'$'}{f}" placeholder="Password" style="margin-bottom:5px;">
                        <textarea id="pk_${'$'}{f}" placeholder="Public Key (Optional)" style="height:60px; font-size:0.8em; margin-bottom:5px;"></textarea>
                        <button onclick="unlockCbox('${'$'}{f}')">Unlock</button>`;
                        lockedList.appendChild(div);
                    });
                } else {
                    document.getElementById('lockedSection').style.display = 'none';
                }

                // Servers
                const srvList = document.getElementById('serverList');
                srvList.innerHTML = '';
                if (data.server_status && data.server_status.length > 0) {
                    data.server_status.forEach(s => {
                       // We reload full server list from /api/servers usually, this just has status
                    });
                }
                loadServers();
            } catch(e) {}
        }

        async function unlockCbox(filename) {
            const pwd = document.getElementById('pwd_' + filename).value;
            const pk = document.getElementById('pk_' + filename).value;
            try {
                const formData = new FormData();
                formData.append('filename', filename);
                formData.append('password', pwd);
                formData.append('public_key', pk);
                const res = await fetchAuth('/api/unlock_cbox', { method: 'POST', body: formData });
                if (res.ok) { notify('Unlocked!'); loadKeyInfo(); } else { notify('Failed', 'error'); }
            } catch(e) { notify('Error', 'error'); }
        }

        async function loadServers() {
            const res = await fetchAuth('/api/servers');
            const servers = await res.json();
            const list = document.getElementById('serverList');
            list.innerHTML = '';
            servers.forEach(s => {
                const div = document.createElement('div');
                div.className = 'server-item';
                div.innerHTML = `<div><div style="font-weight:bold">${'$'}{s.name}</div><div style="font-size:0.8em; color:#888;">${'$'}{s.url}</div></div>
                <div><span class="status-badge status-${'$'}{s.lastStatus.startsWith('OK')?'OK':'ERROR'}">${'$'}{s.lastStatus}</span>
                <button style="padding:4px 8px; margin-left:10px;" onclick="refreshServer('${'$'}{s.id}')">↻</button>
                <button class="danger" style="padding:4px 8px; margin-left:5px;" onclick="deleteServer('${'$'}{s.id}')">×</button></div>`;
                list.appendChild(div);
            });
        }

        async function addServer() {
            const name = document.getElementById('srvName').value;
            const url = document.getElementById('srvUrl').value;
            const authType = document.getElementById('srvAuthType').value;
            // Collect auth data based on type (simplified for now)
            const data = { name, url, authType };

            try {
                const formData = new FormData();
                formData.append('data', JSON.stringify(data));
                const res = await fetchAuth('/api/server/add', { method: 'POST', body: formData });
                if (res.ok) { notify('Server Added'); document.getElementById('addServerForm').style.display='none'; loadServers(); }
                else notify('Failed', 'error');
            } catch(e) { notify('Error', 'error'); }
        }

        async function deleteServer(id) {
            if(!confirm('Delete server?')) return;
            try {
                const formData = new FormData();
                formData.append('id', id);
                await fetchAuth('/api/server/delete', { method: 'POST', body: formData });
                loadServers();
            } catch(e) {}
        }

        async function refreshServer(id) {
            try {
                notify('Refreshing...', 'working');
                const formData = new FormData();
                formData.append('id', id);
                const res = await fetchAuth('/api/server/refresh', { method: 'POST', body: formData });
                if(res.ok) { notify('Refreshed'); loadServers(); } else { notify('Failed', 'error'); }
            } catch(e) {}
        }

        async function loadFileContent(input) {
            if (input.files && input.files[0]) {
                const file = input.files[0];

                // 1. Preview content
                const reader = new FileReader();
                reader.onload = (e) => document.getElementById('kbContent').value = e.target.result;
                reader.readAsText(file);

                // 2. Upload
                const dz = document.getElementById('dropZoneContent');
                dz.innerHTML = '<div style="font-size: 2em; margin-bottom: 10px; color:var(--success);">📄</div>';
                document.getElementById('dropZone').style.borderColor = 'var(--success)';

                const formData = new FormData();
                formData.append('file', file);
                formData.append('filename', file.name);

                notify('Uploading...', 'working');
                try {
                    const res = await fetchAuth('/api/upload_keybox', { method: 'POST', body: formData });
                    if (!res.ok) {
                        const msg = await res.text();
                        notify('Error: ' + msg, 'error');
                        loadKeyboxes();
                        return;
                    }
                    notify('Uploaded');
                    loadKeyInfo();
                } catch(e) { notify('Error', 'error'); } finally {
                    resetDropZone();
                }
            }
        }

        function resetDropZone() {
            const dz = document.getElementById('dropZoneContent');
            dz.innerHTML = '<div style="font-size: 2em; margin-bottom: 10px;">📂</div><div style="font-size: 0.9em; color: #888;">Select .xml, .cbox, or .zip</div>';
            document.getElementById('dropZone').style.borderColor = 'var(--border)';
        }

        // Rest of existing JS (simplified/merged)
        async function init() {
            if (!token) return;
            try {
                const res = await fetchAuth(getAuthUrl('/api/config'));
                const data = await res.json();
                ['global_mode', 'tee_broken_mode', 'rkp_bypass', 'auto_beta_fetch', 'auto_keybox_check', 'random_on_boot', 'drm_fix', 'random_drm_on_boot', 'auto_patch_update', 'hide_sensitive_props', 'spoof_region_cn', 'remove_magisk_32'].forEach(k => {
                    if(document.getElementById(k)) document.getElementById(k).checked = data[k];
                });
                document.getElementById('keyboxStatus').innerText = `${'$'}{data.keybox_count} Keys Loaded`;
            } catch(e) {}

            fetchAuth(getAuthUrl('/api/stats')).then(r => r.json()).then(d => {
                document.getElementById('communityCount').innerText = d.members;
                document.getElementById('bannedCount').innerText = 'Global Banned Keys: ' + d.banned;
            });
            const tRes = await fetchAuth(getAuthUrl('/api/templates'));
            const templates = await tRes.json();
            const sel = document.getElementById('templateSelect');
            const appSel = document.getElementById('appTemplate');
            templates.forEach(t => {
                const opt = document.createElement('option');
                opt.value = t.id; opt.text = `${'$'}{t.model} (${'$'}{t.manufacturer})`; opt.dataset.json = JSON.stringify(t);
                sel.appendChild(opt.cloneNode(true)); appSel.appendChild(opt);
            });
            previewTemplate();
            fetchAuth(getAuthUrl('/api/packages')).then(r => r.json()).then(pkgs => {
                const dl = document.getElementById('pkgList');
                pkgs.forEach(p => { const opt = document.createElement('option'); opt.value = p; dl.appendChild(opt); });
            });
            loadKeyboxes();
            currentFile = document.getElementById('fileSelector').value;
            await loadFile();
        }

        async function toggle(setting) { const el = document.getElementById(setting); try { const res = await fetchAuth('/api/toggle', {method:'POST', body: new URLSearchParams({setting, value: el.checked})}); if (res.ok) { notify('Setting Updated'); } else { throw new Error('Server returned ' + res.status); } } catch(e){ el.checked=!el.checked; notify('Failed', 'error'); } }

        function editDrmConfig() {
            document.getElementById('fileSelector').value = 'drm_fix';
            switchTab('editor');
            loadFile();
        }
        async function resetDrmId() {
            if (!confirm('This will delete downloaded DRM licenses and reset the device ID for streaming apps. Continue?')) return;
            try { await fetchAuth('/api/reset_drm', { method: 'POST' }); notify('DRM ID Reset'); } catch(e) { notify('Failed', 'error'); }
        }
        async function fetchBeta() {
            try {
                const res = await fetchAuth('/api/fetch_beta', { method: 'POST' });
                const text = await res.text();
                if (res.ok) notify(text); else notify(text, 'error');
            } catch(e) { notify('Fetch Failed', 'error'); }
        }

        function previewTemplate() {
            const sel = document.getElementById('templateSelect'); if (!sel.selectedOptions.length) return;
            const t = JSON.parse(sel.selectedOptions[0].dataset.json);
            document.getElementById('pModel').innerText = t.model; document.getElementById('pManuf').innerText = t.manufacturer; document.getElementById('pFing').innerText = t.fingerprint;
            if (!sel.dataset.lockExtras) {
                document.getElementById('inputImei').value = '';
                document.getElementById('inputSerial').value = '';
            }
            delete sel.dataset.lockExtras;
        }

        async function generateRandomIdentity() {
            const res = await fetchAuth('/api/random_identity');
            if (!res.ok) { notify('Failed'); return; }
            const t = await res.json();
            document.getElementById('inputImei').value = t.imei || '';
            document.getElementById('inputImsi').value = t.imsi || '';
            document.getElementById('inputIccid').value = t.iccid || '';
            document.getElementById('inputSerial').value = t.serial || '';
            document.getElementById('inputWifiMac').value = t.wifiMac || '';
            document.getElementById('inputBtMac').value = t.btMac || '';
            document.getElementById('inputSimIso').value = t.simCountryIso || '';
            document.getElementById('inputSimOp').value = t.carrier || '';
            document.getElementById('pModel').innerText = t.model + ' (Randomized)';
            document.getElementById('pManuf').innerText = t.manufacturer;
            document.getElementById('pFing').innerText = t.fingerprint;
            const sel = document.getElementById('templateSelect');
            sel.dataset.generated = JSON.stringify(t);
            notify('Identity Generated');
        }

        async function loadKeyboxes() {
            try {
                const res = await fetchAuth('/api/keyboxes');
                if (res.ok) {
                    const keys = await res.json();
                    const list = document.getElementById('storedKeyboxesList');
                    list.innerHTML = '';
                    keys.forEach(k => {
                        const div = document.createElement('div'); div.className = 'row'; div.style.padding = '10px'; div.style.borderBottom = '1px solid var(--border)';
                        div.innerHTML = `<span>${'$'}{k}</span><span style="font-size:0.8em; color:#666; margin-left:10px;">Stored</span>`;
                        list.appendChild(div);
                    });
                    const dl = document.getElementById('keyboxList');
                    if (dl) { dl.innerHTML = ''; keys.forEach(k => { const opt = document.createElement('option'); opt.value = k; dl.appendChild(opt); }); }
                }
            } catch(e) {}
        }

        async function loadFileContent(input) {
            if (input.files && input.files[0]) {
                const reader = new FileReader();
                reader.onload = (e) => document.getElementById('kbContent').value = e.target.result;
                reader.readAsText(input.files[0]);
            }
        }

        async function saveAdvancedSpoof() { await applySpoofing(document.querySelector('#spoof button.danger')); }

        async function applySpoofing(btn) {
             const inputs = ['inputImei', 'inputImsi', 'inputIccid', 'inputSerial', 'inputWifiMac', 'inputBtMac', 'inputSimIso'];
             for (const id of inputs) {
                 const el = document.getElementById(id);
                 if (el.value && el.classList.contains('invalid')) {
                     notify('Invalid ' + id.replace('input', '').toUpperCase(), 'error');
                     el.focus();
                     return;
                 }
             }

             const orig = btn.innerText; btn.disabled = true; btn.innerText = 'Saving...';
             try {
                 // 1. Fetch current spoof_build_vars content
                 let content = "";
                 try {
                     const res = await fetchAuth('/api/file?filename=spoof_build_vars');
                     if (res.ok) content = await res.text();
                 } catch(e) {}

                 // 2. Parse lines
                 let lines = content.split('\n');
                 const newKeyValues = {};

                 // 3. Get values from UI
                 const sel = document.getElementById('templateSelect');
                 if (sel.value) newKeyValues['TEMPLATE'] = sel.value;

                 const map = {
                     'inputImei': 'ATTESTATION_ID_IMEI',
                     'inputImsi': 'ATTESTATION_ID_IMSI',
                     'inputIccid': 'ATTESTATION_ID_ICCID',
                     'inputSerial': 'ATTESTATION_ID_SERIAL',
                     'inputWifiMac': 'ATTESTATION_ID_WIFI_MAC',
                     'inputBtMac': 'ATTESTATION_ID_BT_MAC',
                     'inputSimIso': 'SIM_COUNTRY_ISO',
                     'inputSimOp': 'SIM_OPERATOR_NAME'
                 };

                 for (const [id, key] of Object.entries(map)) {
                     const el = document.getElementById(id);
                     if (el.value.trim()) {
                         newKeyValues[key] = el.value.trim();
                     } else {
                         // If empty, user wants to remove the override (use template default)
                         newKeyValues[key] = null;
                     }
                 }

                 // 4. Update content
                 const updatedLines = [];
                 const processedKeys = new Set();

                 for (let line of lines) {
                     if (line.trim().startsWith('#') || !line.includes('=')) {
                         updatedLines.push(line);
                         continue;
                     }
                     const parts = line.split('=');
                     const key = parts[0].trim();
                     if (newKeyValues.hasOwnProperty(key)) {
                         if (newKeyValues[key] !== null) {
                             updatedLines.push(key + '=' + newKeyValues[key]);
                         }
                         processedKeys.add(key);
                     } else {
                         updatedLines.push(line);
                     }
                 }

                 // Append new keys
                 for (const [key, val] of Object.entries(newKeyValues)) {
                     if (val !== null && !processedKeys.has(key)) {
                         updatedLines.push(key + '=' + val);
                     }
                 }

                 // 5. Save
                 const newContent = updatedLines.join('\n');
                 const saveRes = await fetchAuth('/api/save', {
                     method: 'POST',
                     body: new URLSearchParams({ filename: 'spoof_build_vars', content: newContent })
                 });

                 if (saveRes.ok) {
                     notify('Configuration Saved');
                 } else {
                     const txt = await saveRes.text();
                     notify('Save Failed: ' + txt, 'error');
                 }

             } catch (e) {
                 notify('Error: ' + e.message, 'error');
             } finally {
                 btn.disabled = false; btn.innerText = orig;
             }
        }

        let appRules = [];
        async function loadAppConfig() {
            const res = await fetchAuth(getAuthUrl('/api/app_config_structured'));
            appRules = await res.json();
            renderAppTable();
        }
        function renderAppTable() {
            const filter = document.getElementById('appFilter') ? document.getElementById('appFilter').value.toLowerCase() : '';
            const tbody = document.querySelector('#appTable tbody');
            tbody.innerHTML = '';
            if (appRules.length === 0) {
                const tr = document.createElement('tr'); tr.innerHTML = '<td colspan="5" style="text-align:center; padding:20px; color:#666;">No active rules.</td>'; tbody.appendChild(tr); return;
            }
            appRules.forEach((rule, idx) => {
                if (filter && !rule.package.toLowerCase().includes(filter)) return;
                const tr = document.createElement('tr');
                tr.innerHTML = `<td data-label="Package">${'$'}{rule.package}</td><td data-label="Profile">${'$'}{rule.template === 'null' ? 'Default' : rule.template}</td><td data-label="Keybox">${'$'}{rule.keybox && rule.keybox !== 'null' ? rule.keybox : ''}</td><td style="text-align:right;"><button class="danger" onclick="removeAppRule(${'$'}{idx})" title="Remove rule" aria-label="Remove rule for ${'$'}{rule.package}">×</button></td>`;
                tbody.appendChild(tr);
            });
        }
        function addAppRule() {
            const pkgInput = document.getElementById('appPkg');
            const pkg = pkgInput.value.trim();
            const tmpl = document.getElementById('appTemplate').value;
            const kb = document.getElementById('appKeybox').value;
            const pContacts = document.getElementById('permContacts').checked;
            const pMedia = document.getElementById('permMedia').checked;
            if (!pkg) { notify('Package required'); pkgInput.focus(); return; }
            const pkgRegex = /^[a-zA-Z0-9_.*]+$/;
            if (!pkgRegex.test(pkg)) { notify('Invalid package'); pkgInput.focus(); return; }
            const permissions = [];
            if (pContacts) permissions.push('CONTACTS');
            if (pMedia) permissions.push('MEDIA');
            appRules.push({ package: pkg, template: tmpl === 'null' ? '' : tmpl, keybox: kb, permissions: permissions });
            renderAppTable(); pkgInput.value = ''; document.getElementById('appKeybox').value = ''; toggleAddButton(); pkgInput.focus(); notify('Rule Added');
        }
        function removeAppRule(idx) {
            if (confirm('Are you sure you want to remove this rule for ' + appRules[idx].package + '?')) { appRules.splice(idx, 1); renderAppTable(); }
        }
        async function saveAppConfig() {
            const res = await fetchAuth(getAuthUrl('/api/app_config_structured'), { method: 'POST', body: new URLSearchParams({ data: JSON.stringify(appRules) }) });
            const txt = await res.text();
            if (res.ok) { notify('App Config Saved'); } else { notify('Save Failed: ' + txt, 'error'); }
        }
        function toggleAddButton() {
            const btn = document.getElementById('btnAddRule'); const input = document.getElementById('appPkg');
            if (btn && input) btn.disabled = !input.value.trim();
        }
        async function reloadConfig() {
            await fetchAuth(getAuthUrl('/api/reload'), { method: 'POST' }); notify('Reloaded'); setTimeout(() => window.location.reload(), 1000);
        }
        async function backupConfig() { window.location.href = getAuthUrl('/api/backup') + '?token=' + token; }
        async function restoreConfig(input) {
            if (input.files && input.files[0]) {
                const formData = new FormData(); formData.append('file', input.files[0]);
                notify('Restoring...', 'working');
                try {
                    const res = await fetchAuth(getAuthUrl('/api/restore'), { method: 'POST', body: formData });
                    if (res.ok) { notify('Success'); setTimeout(() => window.location.reload(), 1000); } else notify('Failed', 'error');
                } catch (e) { notify('Error', 'error'); }
                input.value = '';
            }
        }

        let currentFile = '';
        let originalContent = '';

        async function loadFile() {
            const f = document.getElementById('fileSelector').value;
            const editor = document.getElementById('fileEditor');
            if (currentFile && editor.value !== originalContent) {
                if (!confirm('You have unsaved changes. Discard them?')) {
                    document.getElementById('fileSelector').value = currentFile;
                    return;
                }
            }
            currentFile = f;
            try {
                const res = await fetchAuth('/api/file?filename=' + f);
                if(res.ok) {
                    originalContent = await res.text();
                    editor.value = originalContent;
                    updateSaveButtonState();
                }
            } catch(e){}
        }
        async function handleSave(btn) {
             btn.disabled = true; btn.innerText = 'Saving...';
             const content = document.getElementById('fileEditor').value;
             try {
                 const res = await fetchAuth('/api/save', { method: 'POST', body: new URLSearchParams({ filename: currentFile, content: content }) });
                 const txt = await res.text();
                 if (res.ok) {
                     notify('File Saved');
                     originalContent = content;
                     updateSaveButtonState();
                 } else { notify('Save Failed: ' + txt, 'error'); }
             } finally { btn.disabled = false; updateSaveButtonState(); }
        }
        function updateSaveButtonState() {
            const editor = document.getElementById('fileEditor');
            const btn = document.getElementById('saveBtn');
            if (currentFile && editor.value !== originalContent) {
                btn.innerText = 'Save *';
            } else {
                btn.innerText = 'Save';
            }
        }



        let translations = {};
        async function loadLanguage() {
            try {
                const res = await fetchAuth('/api/language');
                if (res.ok) {
                    translations = await res.json();
                    applyTranslations();
                    notify('Language Loaded');
                }
            } catch(e) {
                // Silent fail
            }
        }

        function t(key, defaultVal) {
            return translations[key] || defaultVal;
        }

        function applyTranslations() {
            document.querySelectorAll('[data-i18n]').forEach(el => {
                const key = el.getAttribute('data-i18n');
                if (translations[key]) el.innerText = translations[key];
            });
            // Update tabs
            if(translations['tab_dashboard']) document.getElementById('tab_dashboard').innerText = translations['tab_dashboard'];
            if(translations['tab_spoof']) document.getElementById('tab_spoof').innerText = translations['tab_spoof'];
            if(translations['tab_apps']) document.getElementById('tab_apps').innerText = translations['tab_apps'];
            if(translations['tab_keys']) document.getElementById('tab_keys').innerText = translations['tab_keys'];
            if(translations['tab_info']) document.getElementById('tab_info').innerText = translations['tab_info'];
            if(translations['tab_guide']) document.getElementById('tab_guide').innerText = translations['tab_guide'];
            if(translations['tab_editor']) document.getElementById('tab_editor').innerText = translations['tab_editor'];
        }

        async function loadResourceUsage() {
             try {
                 const res = await fetchAuth('/api/resource_usage');
                 const data = await res.json();
                 renderResourceTable(data);
             } catch(e) {}
        }

        function renderResourceTable(data) {
            const tbody = document.getElementById('resourceBody');
            if (!tbody) return;
            tbody.innerHTML = '';

            const totalRow = document.createElement('tr');
            const ramMb = (data.real_ram_kb / 1024).toFixed(2);
            const cpu = data.real_cpu ? data.real_cpu.toFixed(1) : "0.0";
            const env = data.environment || "Unknown";

            totalRow.innerHTML = '<td colspan="5" style="background:#222; font-weight:bold; padding:10px;">Env: ' + env + ' | CPU: ' + cpu + '% | RAM: ' + ramMb + ' MB</td>';
            tbody.appendChild(totalRow);

            const keyboxRam = (data.keybox_count * 0.01).toFixed(2);
            const appConfigRam = (data.app_config_size / 1024).toFixed(2);

            const features = [
                { id: 'global_mode', name: 'Global Mode', ram: '~5 MB', cpu: 'High (All Apps)', sec: 'Medium', desc: 'Hooks all apps. Disabling saves RAM but breaks global spoofing.' },
                { id: 'rkp_bypass', name: 'RKP Bypass', ram: '~2 MB', cpu: 'Medium (Crypto)', sec: 'Critical', desc: 'Required for Strong Integrity. Do not disable unless necessary.' },
                { id: 'tee_broken_mode', name: 'TEE Broken Mode', ram: 'Negligible', cpu: 'Low', sec: 'Low', desc: 'Forces software keystore behavior.' },
                { id: 'id_attest_provision', name: 'ID Attestation Provision', ram: 'Negligible', cpu: 'Low', sec: 'Moderate', desc: 'Provisions ID attestation directly to TEE. Fixes CSR Code 20 without Keybox.' },
                { id: 'keybox_storage', name: 'Keybox Storage', ram: '~' + keyboxRam + ' MB', cpu: 'Low', sec: 'Medium', desc: data.keybox_count + ' keyboxes loaded. More keys = more RAM.' },
                { id: 'app_rules', name: 'App Rules', ram: '~' + appConfigRam + ' KB', cpu: 'Low', sec: 'Low', desc: 'Per-app configuration rules.' }
            ];

            features.forEach(f => {
                const tr = document.createElement('tr');
                const isToggleable = ['global_mode', 'rkp_bypass', 'tee_broken_mode'].includes(f.id);
                let statusHtml = '';

                if (isToggleable) {
                    const isChecked = data[f.id] ? 'checked' : '';
                    statusHtml = '<input type="checkbox" class="toggle" id="res_toggle_' + f.id + '" ' + isChecked + ' onchange="toggle(\' + f.id + \')">';
                } else {
                    statusHtml = '<span style="color:#888;">Info Only</span>';
                }

                let secColor = f.sec === 'Critical' ? 'var(--danger)' : (f.sec === 'High' ? 'orange' : 'var(--success)');

                // Single row layout for responsive design
                tr.innerHTML =
                    '<td data-label="' + t('col_feature') + '"><div>' + f.name + '</div><div class="res-desc">' + f.desc + '</div></td>' +
                    '<td data-label="' + t('col_status') + '">' + statusHtml + '</td>' +
                    '<td data-label="' + t('col_ram') + '" style="font-family:monospace;">' + f.ram + '</td>' +
                    '<td data-label="' + t('col_cpu') + '">' + f.cpu + '</td>' +
                    '<td data-label="' + t('col_security') + '" style="color:' + secColor + '; font-weight:bold;">' + f.sec + '</td>';

                tbody.appendChild(tr);
            });
        }

        function downloadLangTemplate() {
            const template = {
                "resource_monitor_title": "Resource Monitor",
                "col_feature": "Feature",
                "col_status": "Status",
                "col_ram": "Est. RAM",
                "col_cpu": "Est. CPU",
                "col_security": "Security Impact",
                "tab_dashboard": "Dashboard",
                "tab_spoof": "Spoofing",
                "tab_apps": "Apps",
                "tab_keys": "Keyboxes",
                "tab_info": "Info & Resources",
                "tab_guide": "Guide",
                "tab_editor": "Editor"
            };
            const blob = new Blob([JSON.stringify(template, null, 2)], {type: "application/json"});
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = "lang.json";
            a.click();
        }

        const dropZone = document.getElementById('dropZone');
        if (dropZone) {
            ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
                dropZone.addEventListener(eventName, preventDefaults, false);
            });
            ['dragenter', 'dragover'].forEach(eventName => {
                dropZone.addEventListener(eventName, highlight, false);
            });
            ['dragleave', 'drop'].forEach(eventName => {
                dropZone.addEventListener(eventName, unhighlight, false);
            });
            dropZone.addEventListener('drop', handleDrop, false);
        }

        function preventDefaults(e) {
            e.preventDefault();
            e.stopPropagation();
        }

        function highlight(e) {
            dropZone.classList.add('drag-over');
        }

        function unhighlight(e) {
            dropZone.classList.remove('drag-over');
        }

        function handleDrop(e) {
            const dt = e.dataTransfer;
            const files = dt.files;
            document.getElementById('kbFilePicker').files = files; // Sync with input
            loadFileContent(document.getElementById('kbFilePicker'));
        }

        loadLanguage();
        init();
    </script>
</body>
</html>
        """.trimIndent()
    }

    companion object {
        fun isSafeHost(host: String?): Boolean {
            if (host == null) return false
            val h = host.split(":")[0].lowercase()
            return h == "localhost" || h == "127.0.0.1" || h == "[::1]"
        }

        fun isValidFilename(name: String): Boolean {
            return name.matches(FILENAME_REGEX) && !name.contains("..") && !name.contains("/") && !name.contains("\\")
        }

        fun validateContent(filename: String, content: String): Boolean {
            // Basic validation based on known file types
            if (filename == "target.txt") {
                val lines = content.split('\n')
                return lines.all { it.isEmpty() || it.startsWith("#") || it.matches(TARGET_PKG_REGEX) }
            }
            if (filename == "security_patch.txt") {
                 val lines = content.split('\n')
                 return lines.all { it.isEmpty() || it.matches(SECURITY_PATCH_REGEX) }
            }
            if (filename == "spoof_build_vars") {
                val lines = content.split('\n')
                return lines.all { line ->
                    if (line.isEmpty() || line.startsWith("#")) return@all true
                    // Must be KEY=VALUE format
                    if (!line.matches(KEY_VALUE_REGEX)) return@all false
                    // Value part security check
                    val parts = line.split("=", limit=2)
                    if (parts.size < 2) return@all false
                    val value = parts[1]
                    // Check for unsafe shell chars
                    value.matches(SAFE_BUILD_VAR_VALUE_REGEX)
                }
            }
            if (filename == "app_config") {
                val lines = content.split('\n')
                return lines.all { line ->
                     if (line.isBlank() || line.startsWith("#")) return@all true
                     val parts = line.trim().split(WHITESPACE_REGEX)
                     if (parts.isEmpty()) return@all true
                     val pkg = parts[0]
                     if (!pkg.matches(PKG_NAME_REGEX)) return@all false
                     if (parts.size > 1 && parts[1] != "null" && !parts[1].matches(TEMPLATE_NAME_REGEX)) return@all false
                     if (parts.size > 2 && parts[2] != "null" && !parts[2].matches(KEYBOX_FILENAME_REGEX)) return@all false
                     if (parts.size > 3 && parts[3] != "null" && !parts[3].matches(PERMISSIONS_REGEX)) return@all false
                     true
                }
            }
            if (filename == "templates.json") {
                try {
                    val json = org.json.JSONTokener(content).nextValue()
                    return json is org.json.JSONObject || json is org.json.JSONArray
                } catch(e: Exception) {
                    return false
                }
            }
            // Allow others with lenient check
            return true
        }

        fun createBackupZip(configDir: File): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                listOf("target.txt", "security_patch.txt", "spoof_build_vars", "app_config", "drm_fix", "global_mode", "tee_broken_mode", "rkp_bypass", "templates.json", "custom_templates").forEach { name ->
                    val f = File(configDir, name)
                    if (f.exists()) {
                        zos.putNextEntry(ZipEntry(name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                val keyboxDir = File(configDir, "keyboxes")
                if (keyboxDir.exists() && keyboxDir.isDirectory) {
                    keyboxDir.listFiles { _, name -> name.endsWith(".xml") }?.forEach { k ->
                        zos.putNextEntry(ZipEntry("keyboxes/${k.name}"))
                        k.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            return bos.toByteArray()
        }

        fun createKeyboxVerificationJson(results: List<KeyboxVerifier.Result>): String {
            val array = JSONArray()
            results.forEach { r ->
                val obj = JSONObject()
                obj.put("filename", r.filename)
                obj.put("status", r.status.name)
                obj.put("details", r.details)
                array.put(obj)
            }
            return array.toString()
        }

        fun restoreBackupZip(configDir: File, inputStream: InputStream) {
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!name.contains("..") && !name.startsWith("/") && !name.contains("\\")) {
                        val file = File(configDir, name)
                        if (name.startsWith("keyboxes/")) {
                            File(configDir, "keyboxes").mkdirs()
                        }
                        if (!entry.isDirectory) {
                            SecureFile.writeStream(file, zis, 50 * 1024 * 1024)
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }
}
