package cleveres.tricky.cleverestech

import android.system.Os
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import cleveres.tricky.cleverestech.util.RandomUtils
import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.BackupEncryptor
import cleveres.tricky.cleverestech.util.CboxDecryptor
import cleveres.tricky.cleverestech.util.ZipProcessor
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Socket
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
private const val WEB_UI_READINESS_TIMEOUT_MS = 5_000L
private const val WEB_UI_READINESS_POLL_MS = 100L
private const val WEB_UI_READINESS_CONNECT_TIMEOUT_MS = 250

class WebServer(
    private val requestedPort: Int,
    private val configDir: File,
    private val permissionSetter: (File, Int) -> Unit = { f, m ->
        try {
            Os.chmod(f.absolutePath, m)
        } catch (t: Throwable) {
            Logger.e("failed to set permissions for ${f.name}", t)
        }
    }
) : NanoHTTPD(WEB_UI_LOOPBACK_HOST, requestedPort) {

    override fun start(timeout: Int, daemon: Boolean) {
        Logger.d("WebServer: Starting on $WEB_UI_LOOPBACK_HOST:$requestedPort (timeout=$timeout daemon=$daemon)")
        try {
            super.start(timeout, daemon)
            Logger.d("WebServer: NanoHTTPD start returned (alive=$isAlive port=$listeningPort)")
            waitUntilListening()
            Logger.d("WebServer: Readiness probe succeeded on $WEB_UI_LOOPBACK_HOST:$listeningPort")
        } catch (e: Exception) {
            Logger.e("WebServer: Failed to start", e)
            throw e
        }
    }

    /**
     * Polls the loopback socket until the server accepts connections or the timeout elapses.
     *
     * @param timeoutMs total amount of time to wait for the loopback socket to accept connections
     * @param pollMs delay between readiness probes while the server is still binding
     */
    private fun waitUntilListening(
        timeoutMs: Long = WEB_UI_READINESS_TIMEOUT_MS,
        pollMs: Long = WEB_UI_READINESS_POLL_MS
    ) {
        val port = listeningPort
        if (port <= 0) {
            throw IOException("WebServer: Invalid listening port $port after start")
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var lastError: IOException? = null
        while (System.nanoTime() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(WEB_UI_LOOPBACK_HOST, port), WEB_UI_READINESS_CONNECT_TIMEOUT_MS)
                }
                return
            } catch (e: IOException) {
                lastError = e
                Logger.d("WebServer: Waiting for $WEB_UI_LOOPBACK_HOST:$port to accept connections (${e.message})")
                Thread.sleep(pollMs)
            }
        }
        throw IOException("WebServer: Timed out waiting for $WEB_UI_LOOPBACK_HOST:$port to accept connections", lastError)
    }

    init { cleveres.tricky.cleverestech.util.LoggerConfig.disableNanoHttpdLogging() }
    val token = UUID.randomUUID().toString()
    private val MAX_UPLOAD_SIZE = 10 * 1024 * 1024L // 10MB for ZIPs
    private val MAX_BODY_SIZE = 5 * 1024 * 1024L // 5MB for non-multipart requests

    private class RateLimitEntry(var timestamp: Long, var count: Int)
    private val requestCounts = java.util.concurrent.ConcurrentHashMap<String, RateLimitEntry>()
    private val RATE_LIMIT = 100
    private val RATE_WINDOW = 60 * 1000L

    private val fileLock = Any()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Suppress("DEPRECATION")
    private fun getParam(session: IHTTPSession, name: String): String? {
        return session.parms[name]
    }

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
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return false
        }
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
        return name in setOf("global_mode", "tee_broken_mode", "rkp_bypass", "auto_beta_fetch", "auto_keybox_check", "random_on_boot", "drm_fix", "random_drm_on_boot", "auto_patch_update", "hide_sensitive_props", "spoof_region_cn", "remove_magisk_32", "spoof_build", "spoof_build_ps", "spoof_props", "spoof_provider", "spoof_signature", "spoof_sdk_ps", "spoof_location", "imei_global", "network_global")
    }

    private fun toggleFile(filename: String, enable: Boolean): Boolean {
        if (!isValidSetting(filename)) return false
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return false
        }
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
            val statBuffer = ByteArray(8192)
            var uTime = 0L
            var sTime = 0L
            java.io.FileInputStream("/proc/self/stat").use { fis ->
                val read = fis.read(statBuffer)
                if (read > 0) {
                    var pos = 0
                    var spaceCount = 0
                    while (pos < read && spaceCount < 15) {
                        if (statBuffer[pos] == ' '.code.toByte()) {
                            spaceCount++
                        } else if (spaceCount == 13) {
                            uTime = uTime * 10 + (statBuffer[pos] - '0'.code.toByte())
                        } else if (spaceCount == 14) {
                            sTime = sTime * 10 + (statBuffer[pos] - '0'.code.toByte())
                        }
                        pos++
                    }
                }
            }
            val procTime = uTime + sTime

            var totalTime = 0L
            java.io.FileInputStream("/proc/stat").use { fis ->
                val read = fis.read(statBuffer)
                if (read > 0) {
                    var pos = 0
                    // skip "cpu" prefix
                    while (pos < read && statBuffer[pos] != ' '.code.toByte() && statBuffer[pos] != '\n'.code.toByte()) {
                        pos++
                    }
                    while (pos < read && statBuffer[pos] != '\n'.code.toByte()) {
                        while (pos < read && statBuffer[pos] == ' '.code.toByte()) {
                            pos++
                        }
                        if (pos >= read || statBuffer[pos] == '\n'.code.toByte()) break
                        var currentVal = 0L
                        while (pos < read && statBuffer[pos] >= '0'.code.toByte() && statBuffer[pos] <= '9'.code.toByte()) {
                            currentVal = currentVal * 10 + (statBuffer[pos] - '0'.code.toByte())
                            pos++
                        }
                        totalTime += currentVal
                    }
                }
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
            val buffer = ByteArray(8192)
            java.io.FileInputStream("/proc/self/status").use { fis ->
                val read = fis.read(buffer)
                if (read > 0) {
                    var pos = 0
                    while (pos < read) {
                        // Check if line starts with "VmRSS:"
                        if (pos + 6 < read &&
                            buffer[pos] == 'V'.code.toByte() &&
                            buffer[pos+1] == 'm'.code.toByte() &&
                            buffer[pos+2] == 'R'.code.toByte() &&
                            buffer[pos+3] == 'S'.code.toByte() &&
                            buffer[pos+4] == 'S'.code.toByte() &&
                            buffer[pos+5] == ':'.code.toByte()
                        ) {
                            pos += 6
                            // Skip spaces
                            while (pos < read && (buffer[pos] == ' '.code.toByte() || buffer[pos] == '\t'.code.toByte())) {
                                pos++
                            }
                            var kb = 0L
                            while (pos < read && buffer[pos] >= '0'.code.toByte() && buffer[pos] <= '9'.code.toByte()) {
                                kb = kb * 10 + (buffer[pos] - '0'.code.toByte())
                                pos++
                            }
                            return kb
                        }
                        // Skip to next line
                        while (pos < read && buffer[pos] != '\n'.code.toByte()) {
                            pos++
                        }
                        pos++
                    }
                }
            }
        } catch (e: Exception) {}
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            serveInternal(session)
        } catch (e: Exception) {
            Logger.e("WebServer: Error handling request", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Internal Server Error")
        }
    }

    private fun serveInternal(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
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
        if (authToken == null) authToken = getParam(session, "token")

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
            json.put("spoof_build", fileExists("spoof_build"))
            json.put("spoof_build_ps", fileExists("spoof_build_ps"))
            json.put("spoof_props", fileExists("spoof_props"))
            json.put("spoof_provider", fileExists("spoof_provider"))
            json.put("spoof_signature", fileExists("spoof_signature"))
            json.put("spoof_sdk_ps", fileExists("spoof_sdk_ps"))
            json.put("spoof_location", fileExists("spoof_location"))
            json.put("imei_global", fileExists("imei_global"))
            json.put("network_global", fileExists("network_global"))
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
             val filename = getParam(session, "filename")
             val password = getParam(session, "password")
             val pubKey = getParam(session, "public_key")

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
             val jsonStr = getParam(session, "data")
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
             val id = getParam(session, "id")
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
             val id = getParam(session, "id")
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
                val sortedPackages = Config.getInstalledPackages()
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
                                val trimmed = line.trim()
                                if (trimmed.isEmpty()) return@forEach

                                val len = trimmed.length
                                var idx = 0

                                var start = idx
                                while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                val pkg = trimmed.substring(start, idx)

                                var tmpl = ""
                                var kb = ""
                                var perms = ""

                                while (idx < len && trimmed[idx].isWhitespace()) idx++
                                if (idx < len) {
                                    start = idx
                                    while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                    val tmplStr = trimmed.substring(start, idx)
                                    if (tmplStr != "null") tmpl = tmplStr

                                    while (idx < len && trimmed[idx].isWhitespace()) idx++
                                    if (idx < len) {
                                        start = idx
                                        while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                        val kbStr = trimmed.substring(start, idx)
                                        if (kbStr != "null") kb = kbStr

                                        while (idx < len && trimmed[idx].isWhitespace()) idx++
                                        if (idx < len) {
                                            start = idx
                                            while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                            val permStr = trimmed.substring(start, idx)
                                            if (permStr != "null") perms = permStr
                                        }
                                    }
                                }

                                if (pkg.isNotEmpty()) {
                                    if (pkg.matches(PKG_NAME_REGEX)) {
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
             val jsonStr = getParam(session, "data")
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
            val filename = getParam(session, "filename")
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
             val filename = getParam(session, "filename")
             val content = getParam(session, "content")
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
             val filename = getParam(session, "filename")
             val content = getParam(session, "content") // Raw text content for XML
             // For binary upload (CBOX/ZIP), we might need multipart or read raw body
             // Since WebUI uses multipart or simple body for text...
             // Wait, for binary files, we need better upload handling.

             // Check if this is a binary upload via "file" param (Multipart)
             // NanoHTTPD's parseBody handles multipart and puts temp file path in map
             val tmpFilePath = map["file"]
             if (tmpFilePath != null) {
                 val originalName = getParam(session, "filename") ?: "upload.bin"
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
                     if (!dest.canonicalPath.startsWith(keyboxDir.canonicalPath + File.separator) && dest.canonicalPath != keyboxDir.canonicalPath) {
                         return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Path traversal attempt detected")
                     }
                     SecureFile.writeBytes(dest, bytes)
                     // Trigger refresh and wait for completion
                     CboxManager.refresh()
                     runBlocking { Config.updateKeyBoxes().join() }
                     val count = CertHack.getKeyboxCount()
                     return secureResponse(Response.Status.OK, "application/json", """{"status":"ok","keybox_count":$count}""")
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
                     if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                         return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid filename")
                     }
                     val file = File(keyboxDir, filename)
                     if (!isSafePath(file) || !file.canonicalPath.startsWith(keyboxDir.canonicalPath)) {
                         return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Path traversal attempt detected")
                     }
                     try {
                         SecureFile.writeText(file, content)
                         runBlocking { Config.updateKeyBoxes().join() }
                         val count = CertHack.getKeyboxCount()
                         return secureResponse(Response.Status.OK, "application/json", """{"status":"ok","keybox_count":$count}""")
                     } catch (e: Exception) {
                         Logger.e("Failed to save keybox", e)
                         return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed: " + e.message)
                     }
                 }
             }
             return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid request")
        }

        if (uri == "/api/delete_keybox" && method == Method.POST) {
             val map = HashMap<String, String>()
             try { session.parseBody(map) } catch(e:Exception){}
             val filename = getParam(session, "filename")
             if (filename != null && isValidFilename(filename)) {
                 if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                     return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid filename")
                 }
                 synchronized(fileLock) {
                     val keyboxDir = File(configDir, "keyboxes")
                     val f = File(keyboxDir, filename)
                     if (isSafePath(f) && f.canonicalPath.startsWith(keyboxDir.canonicalPath) && f.exists()) {
                         if (f.delete()) {
                             if (filename.endsWith(".cbox")) {
                                 val cacheFile = File(keyboxDir, "$filename.cache")
                                 if (cacheFile.exists()) cacheFile.delete()
                                 CboxManager.refresh()
                             }
                             Config.updateKeyBoxes()
                             return secureResponse(Response.Status.OK, "text/plain", "Deleted")
                         } else {
                             return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to delete file")
                         }
                     }
                 }
             }
             return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid filename")
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


        if (uri == "/api/apply_profile" && method == Method.POST) {
             val map = HashMap<String, String>()
             try { session.parseBody(map) } catch(e:Exception){}
             val profileName = getParam(session, "profile")
             if (profileName != null) {
                 synchronized(fileLock) {
                     try {
                         SecureFile.writeText(File(configDir, "apply_profile"), profileName)
                         return secureResponse(Response.Status.OK, "text/plain", "Profile Applied")
                     } catch (e: Exception) {
                         Logger.e("Failed to apply profile via file", e)
                         return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
                     }
                 }
             }
             return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing profile")
        }

        if (uri == "/api/toggle" && method == Method.POST) {
             val map = HashMap<String, String>()
             try { session.parseBody(map) } catch(e:Exception){}
             val setting = getParam(session, "setting")
             val value = getParam(session, "value")
             if (setting != null && value != null) {
                 if (toggleFile(setting, value.toBoolean())) return secureResponse(Response.Status.OK, "text/plain", "Toggled")
             }
             return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
        }

        if (uri == "/api/reset_environment" && method == Method.POST) {
             try {
                 synchronized(fileLock) {
                     val imei = RandomUtils.generateLuhn(15)
                     val serial = RandomUtils.generateRandomSerial(12)
                     val wifiMac = RandomUtils.generateRandomMac()
                     val btMac = RandomUtils.generateRandomMac()
                     val imsi = RandomUtils.generateLuhn(15)
                     val iccid = RandomUtils.generateLuhn(20)
                     val spoofFile = File(configDir, "spoof_build_vars")
                     if (spoofFile.exists()) {
                         var content = spoofFile.readText()
                         val replacements = mapOf(
                             "ATTESTATION_ID_IMEI" to imei,
                             "ATTESTATION_ID_SERIAL" to serial,
                             "ATTESTATION_ID_WIFI_MAC" to wifiMac,
                             "ATTESTATION_ID_BT_MAC" to btMac,
                             "ATTESTATION_ID_IMSI" to imsi,
                             "ATTESTATION_ID_ICCID" to iccid
                         )
                         val lines = content.lines().toMutableList()
                         val foundKeys = mutableSetOf<String>()
                         for (i in lines.indices) {
                             val line = lines[i]
                             val eqIdx = line.indexOf('=')
                             if (eqIdx != -1) {
                                 val key = line.substring(0, eqIdx)
                                 if (replacements.containsKey(key)) {
                                     lines[i] = "$key=${replacements[key]}"
                                     foundKeys.add(key)
                                 }
                             }
                         }
                         for ((key, value) in replacements) {
                             if (!foundKeys.contains(key)) {
                                 lines.add("$key=$value")
                             }
                         }
                         content = lines.joinToString("\n")

                         SecureFile.writeText(spoofFile, content)
                     }
                     val target = File(configDir, "target.txt")
                     if (target.exists()) target.setLastModified(System.currentTimeMillis())
                     runBlocking { Config.updateKeyBoxes().join() }
                     return secureResponse(Response.Status.OK, "text/plain", "Environment Reset")
                 }
             } catch(e: Exception) {
                 Logger.e("Failed to reset environment", e)
                 return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
             }
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
                         try {
                             var cleaned = 0
                             File(path).walkBottomUp().forEach {
                                 if (it.path != path) {
                                     if (!it.delete()) Logger.e("DRM reset: failed to delete ${it.path}")
                                     else cleaned++
                                 }
                             }
                             if (cleaned > 0) Logger.i("DRM reset: cleaned $cleaned files from $path")
                         } catch (e: Exception) {
                             Logger.e("DRM reset: failed to clean $path", e)
                         }
                     }
                     val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "killall -9 android.hardware.drm-service.widevine android.hardware.drm-service.clearkey mediadrmserver || true"))
                     try { p.inputStream.readBytes() } catch (_: Exception) {} finally { try { p.errorStream.readBytes() } catch (_: Exception) {} }
                     p.waitFor()
                     Logger.i("DRM ID regenerated successfully")
                     return secureResponse(Response.Status.OK, "text/plain", "DRM ID Regenerated")
                 }
             } catch(e: Exception) {
                 Logger.e("DRM reset failed", e)
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
                val zipBytes = synchronized(fileLock) { createBackupZip(configDir) }
                val response = newFixedLengthResponse(Response.Status.OK, "application/zip", ByteArrayInputStream(zipBytes), zipBytes.size.toLong())
                response.addHeader("Content-Disposition", "attachment; filename=\"cleverestricky_backup.zip\"")
                response
            } catch (e: Exception) {
                Logger.e("Failed to create backup", e)
                secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Backup failed")
            }
        }

        if (uri == "/api/backup" && method == Method.POST) {
            val map = HashMap<String, String>()
            try { session.parseBody(map) } catch (e: Exception) { return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body") }
            val password = getParam(session, "pw")
            if (password.isNullOrBlank()) return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Password required for encrypted backup")
            return try {
                val zipBytes = synchronized(fileLock) { createBackupZip(configDir) }
                val encBytes = BackupEncryptor.encrypt(zipBytes, password)
                val response = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", ByteArrayInputStream(encBytes), encBytes.size.toLong())
                response.addHeader("Content-Disposition", "attachment; filename=\"cleverestricky_backup.ctsb\"")
                response
            } catch (e: Exception) {
                Logger.e("Failed to create encrypted backup", e)
                secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Encrypted backup failed")
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
                     val uploadedBytes = tmpFile.readBytes()
                     val zipStream: java.io.InputStream = if (BackupEncryptor.isEncryptedBackup(uploadedBytes)) {
                         val pw = getParam(session, "pw")
                         if (pw.isNullOrBlank()) return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Password required for encrypted backup")
                         ByteArrayInputStream(BackupEncryptor.decrypt(uploadedBytes, pw))
                     } else {
                         ByteArrayInputStream(uploadedBytes)
                     }
                     synchronized(fileLock) {
                         restoreBackupZip(configDir, zipStream)
                         val target = File(configDir, "target.txt")
                         if (target.exists()) target.setLastModified(System.currentTimeMillis())
                         secureResponse(Response.Status.OK, "text/plain", "Restore Successful")
                     }
                 } catch (e: Exception) {
                     Logger.e("Failed to restore backup", e)
                     secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Restore failed: ${e.message}")
                 } finally {
                     if (!tmpFile.delete()) Logger.e("Failed to clean up temp file: ${tmpFile.absolutePath}")
                 }
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
        .row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; min-height: 44px; }
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
        button.confirm-active { background: var(--danger) !important; color: #fff !important; font-weight: bold; border-color: var(--danger) !important; }
        .res-desc { display: block; font-size: 0.8em; color: #888; margin-top: 4px; line-height: 1.3; }
        .search-container { position: relative; margin-bottom: 10px; }
        .search-container input[type="search"] { width: 100%; padding-right: 30px; }
        .clear-btn { position: absolute; right: 5px; top: 50%; transform: translateY(-50%); background: transparent; border: none; color: #888; font-size: 1.2em; padding: 0; min-height: auto; width: auto; cursor: pointer; display: none; touch-action: manipulation; }
        .clear-btn:hover { color: #fff; background: transparent; }
        @media screen and (max-width: 600px) {
            .grid-2 { grid-template-columns: 1fr; }
            .content { padding: 12px; padding-bottom: 80px; }
            .panel { padding: 14px; margin-bottom: 14px; }
            h1 { font-size: 1.2em; margin: 15px 0; }
            .tabs { gap: 0; -webkit-overflow-scrolling: touch; scroll-snap-type: x mandatory; padding: 0 4px; }
            .tab { scroll-snap-align: start; padding: 12px 14px; font-size: 0.82em; }
            .row { flex-wrap: wrap; gap: 8px; }
            .responsive-table thead { display: none; }
            .responsive-table tr { display: block; border: 1px solid var(--border); margin-bottom: 10px; border-radius: 8px; background: #1a1a1a; }
            .responsive-table td { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 1px solid #333; padding: 12px; min-height: 40px; }
            .responsive-table td:last-child { border-bottom: none; }
            .responsive-table td::before { content: attr(data-label); color: #888; font-weight: 500; margin-right: 10px; min-width: 100px; display: inline-block; }
            .responsive-table td > div, .responsive-table td > span { text-align: right; flex: 1; word-break: break-word; }
            .server-item { flex-direction: column; align-items: flex-start; gap: 8px; }
            .server-item > div:last-child { width: 100%; display: flex; justify-content: flex-end; }
        }
    </style>
</head>
<body>
    <div class="island-container"><div id="island" class="island" role="status" aria-live="polite"><div class="spinner"></div><div class="error-icon" style="color:var(--danger);font-weight:bold;font-size:1.2em;">!</div><span id="islandText">Notification</span></div></div>
    <h1>${getAppName()}</h1>
    <div class="tabs" role="tablist">
        <div class="tab active" id="tab_dashboard" onclick="switchTab('dashboard')" role="tab" tabindex="0" aria-selected="true" aria-controls="dashboard" onkeydown="handleTabNavigation(event, 'dashboard')">Dashboard</div>
        <div class="tab" id="tab_spoof" onclick="switchTab('spoof')" role="tab" tabindex="-1" aria-selected="false" aria-controls="spoof" onkeydown="handleTabNavigation(event, 'spoof')">Spoofing</div>
        <div class="tab" id="tab_apps" onclick="switchTab('apps')" role="tab" tabindex="-1" aria-selected="false" aria-controls="apps" onkeydown="handleTabNavigation(event, 'apps')">Apps</div>
        <div class="tab" id="tab_keys" onclick="switchTab('keys')" role="tab" tabindex="-1" aria-selected="false" aria-controls="keys" onkeydown="handleTabNavigation(event, 'keys')">Keyboxes</div>
        <div class="tab" id="tab_info" onclick="switchTab('info')" role="tab" tabindex="-1" aria-selected="false" aria-controls="info" onkeydown="handleTabNavigation(event, 'info')">Info & Resources</div> <div class="tab" id="tab_guide" onclick="switchTab('guide')" role="tab" tabindex="-1" aria-selected="false" aria-controls="guide" onkeydown="handleTabNavigation(event, 'guide')">Guide</div>
        <div class="tab" id="tab_editor" onclick="switchTab('editor')" role="tab" tabindex="-1" aria-selected="false" aria-controls="editor" onkeydown="handleTabNavigation(event, 'editor')">Editor</div>
        <div class="tab" id="tab_donate" onclick="switchTab('donate')" role="tab" tabindex="-1" aria-selected="false" aria-controls="donate" onkeydown="handleTabNavigation(event, 'donate')" style="margin-left:auto; color:var(--accent);">Donate</div>
    </div>

    <div id="dashboard" class="content active" role="tabpanel" aria-labelledby="tab_dashboard">
        <div style="display: flex; gap: 10px; margin-bottom: 20px;">
            <div style="flex: 1; padding: 15px; border-radius: 8px; background: #1a1a1a; border: 1px solid var(--border); text-align: center;">
                <div style="font-size: 0.8em; color: #888; text-transform: uppercase;">RKP Bypass</div>
                <div id="status_rkp" style="font-weight: bold; color: var(--danger); margin-top: 5px;">Inactive</div>
            </div>
            <div style="flex: 1; padding: 15px; border-radius: 8px; background: #1a1a1a; border: 1px solid var(--border); text-align: center;">
                <div style="font-size: 0.8em; color: #888; text-transform: uppercase;">DRM Fix</div>
                <div id="status_drm" style="font-weight: bold; color: var(--danger); margin-top: 5px;">Inactive</div>
            </div>
        </div>

        <div class="panel">
            <h3>Quick Profile</h3>
            <div class="row">
                <select id="profileSelect" style="flex: 1; margin-right: 10px; min-height: 44px; padding: 12px 14px; background: var(--input-bg); border: 1px solid var(--border); color: #fff; border-radius: 6px;">
                    <option value="">Select a Profile...</option>
                    <option value="GodProfile">God Mode (Max Spoofing)</option>
                    <option value="DailyUse">Daily Use (Standard Spoofing)</option>
                    <option value="Minimal">Minimal (Clean state)</option>
                </select>
                <button onclick="const sel = document.getElementById('profileSelect').value; if(!sel) { notify('Please select a profile first', 'error'); return; } requireConfirm(this, () => applyProfile(sel), 'Confirm Apply', async () => { const res = await fetchAuth(getAuthUrl('/api/config')); const data = await res.json(); determineActiveProfile(data); })" style="min-height: 44px;">Apply</button>
            </div>
            <div style="font-size:0.8em; color:#888; margin-top:5px;">Applying a profile will overwrite current settings below.</div>
        </div>
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
            <div class="section-header">Spoof Mode</div>
            <div class="row"><label for="imei_global">IMEI Global (All Apps)</label><input type="checkbox" class="toggle" id="imei_global" onchange="toggle('imei_global')"></div>
            <div class="row"><label for="network_global">Network Global (MAC/WiFi)</label><input type="checkbox" class="toggle" id="network_global" onchange="toggle('network_global')"></div>
            <div style="font-size:0.8em; color:#888; margin-top:5px;">Per-feature global modes apply even without Global Mode enabled. Most features only affect apps in target.txt unless a global toggle is active.</div>
            <div class="section-header">Boot Properties</div>
            <div class="row"><label for="hide_sensitive_props">Hide Sensitive Props</label><input type="checkbox" class="toggle" id="hide_sensitive_props" onchange="toggle('hide_sensitive_props')"></div>
            <div class="row"><label for="spoof_region_cn">Spoof Region (CN)</label><input type="checkbox" class="toggle" id="spoof_region_cn" onchange="toggle('spoof_region_cn')"></div>
            <div class="row"><label for="remove_magisk_32" style="color:var(--danger)">Remove Magisk 32-bit</label><input type="checkbox" class="toggle" id="remove_magisk_32" onchange="toggle('remove_magisk_32')"></div>
            <div style="margin-top:20px; border-top: 1px solid var(--border); padding-top: 15px;">
                <div class="row"><span id="keyboxStatus" style="font-size:0.9em; color:var(--success);">Active</span><button onclick="runWithState(this, 'Reloading...', reloadConfig)">Reload Config</button></div>
            </div>
        </div>
        <div class="panel"><h3>Configuration Management</h3><div style="margin-bottom:10px;"><label for="backupPw">Encryption Password (optional - leave blank for unencrypted export)</label><input type="password" id="backupPw" placeholder="Leave blank to skip encryption" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></div><div class="grid-2"><button onclick="runWithState(this, 'Exporting...', backupConfig)">Export Settings</button><button onclick="document.getElementById('restoreInput').click()">Import Settings</button><input type="file" id="restoreInput" style="display:none" onchange="restoreConfig(this)" accept=".zip,.ctsb"></div><div style="margin-top:10px;"><button onclick="requireConfirm(this, () => resetEnvironment(), 'Confirm Reset')" class="danger" style="width:100%;">One-Click Reset (Refresh Environment)</button></div></div>
        <div class="panel" style="text-align:center;"><h3>Community</h3><div id="communityCount" style="font-size:2em; font-weight:300; margin: 10px 0;">...</div><div id="bannedCount" style="font-size:0.9em; color:#888; margin-bottom:10px;">Global Banned Keys: ...</div><a href="https://t.me/cleverestech" target="_blank" style="display:inline-block; margin-top:10px; color:var(--accent); text-decoration:none; font-size:0.9em; border:1px solid var(--border); padding:5px 15px; border-radius:15px;">Join Channel</a></div>
    </div>

    <div id="spoof" class="content" role="tabpanel" aria-labelledby="tab_spoof">
        <div class="panel">
            <h3>Custom ROM Spoofing (PIF Features)</h3>
            <div style="font-size:0.85em; color:var(--danger); border:1px solid var(--danger); padding:8px; border-radius:6px; margin-bottom:15px;">
                These features are generally useful for Custom ROMs. Do not use them on Stock ROMs unless necessary.
            </div>
            <div class="row"><label for="spoof_build">Spoof Build</label><input type="checkbox" class="toggle" id="spoof_build" onchange="toggle('spoof_build')"></div>
            <div class="row"><label for="spoof_build_ps">Spoof Build (Play Store)</label><input type="checkbox" class="toggle" id="spoof_build_ps" onchange="toggle('spoof_build_ps')"></div>
            <div class="row"><label for="spoof_props">Spoof Props</label><input type="checkbox" class="toggle" id="spoof_props" onchange="toggle('spoof_props')"></div>
            <div class="row"><label for="spoof_provider">Spoof Provider</label><input type="checkbox" class="toggle" id="spoof_provider" onchange="toggle('spoof_provider')"></div>
            <div class="row"><label for="spoof_signature">Spoof Signature</label><input type="checkbox" class="toggle" id="spoof_signature" onchange="toggle('spoof_signature')"></div>
            <div class="row"><label for="spoof_sdk_ps">Spoof Sdk (Play Store)</label><input type="checkbox" class="toggle" id="spoof_sdk_ps" onchange="toggle('spoof_sdk_ps')"></div>
        </div>
        <div class="panel">
            <h3>DRM / Streaming</h3>
            <div class="row"><label for="drm_fix">Netflix / DRM Fix</label><div style="display:flex; align-items:center; gap:10px;"><button onclick="editDrmConfig()" style="padding:8px 16px; font-size:0.85em; min-height:44px;">Edit</button><input type="checkbox" class="toggle" id="drm_fix" onchange="toggle('drm_fix')"></div></div>
            <div class="row"><label for="random_drm_on_boot">Randomize on Boot</label><input type="checkbox" class="toggle" id="random_drm_on_boot" onchange="toggle('random_drm_on_boot')"></div>
            <div class="row" style="margin-top:10px;"><label style="font-size:0.8em; color:#888;">Reset Identity</label><button onclick="requireConfirm(this, () => resetDrmId(), 'Confirm Regen')" style="padding:8px 16px; font-size:0.85em; min-height:44px;">Regenerate DRM ID</button></div>
        </div>
        <div class="panel"><h3>Beta Profile Fetcher</h3><button onclick="runWithState(this, 'Fetching...', fetchBeta)" style="width:100%">Fetch & Apply Latest Beta</button></div>
        <div class="panel">
            <h3>Identity Manager</h3>
            <label for="templateSelect" style="display:block; font-size:0.85em; color:#888; margin-bottom:8px;">Select a verified device identity to spoof globally.</label>
            <select id="templateSelect" onchange="previewTemplate()" style="margin-bottom:15px;"></select>
            <div id="templatePreview" style="background:var(--input-bg); border-radius:8px; padding:15px; margin-bottom:15px;">
                <div class="grid-2"><div><div class="section-header">Device</div><div id="pModel"></div></div><div><div class="section-header">Manufacturer</div><div id="pManuf"></div></div></div>
                <div class="section-header">Fingerprint <button onclick="copyToClipboard(document.getElementById('pFing').innerText, 'Fingerprint Copied', this)" style="font-size:0.9em; padding:2px 6px; margin-left:5px;" title="Copy fingerprint" aria-label="Copy Fingerprint">Copy</button></div><div style="font-family:monospace; font-size:0.8em; color:#999; word-break:break-all;" id="pFing"></div>
            </div>
            <div class="grid-2"><button onclick="runWithState(this, 'Generating...', generateRandomIdentity)" class="primary">Generate Random</button><button onclick="runWithState(this, 'Saving...', applySpoofing)">Apply Global</button></div>
        </div>
        <div class="panel"><h3>System-Wide Spoofing (Global Hardware)</h3>
            <div class="section-header">Modem</div><div class="grid-2">
                <div><label for="inputImei">IMEI</label><input type="text" id="inputImei" placeholder="35..." style="font-family:monospace;" inputmode="numeric" oninput="validateRealtime(this, 'luhn')" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></div>
                <div><label for="inputImsi">IMSI</label><input type="text" id="inputImsi" placeholder="310..." style="font-family:monospace;" inputmode="numeric" oninput="validateRealtime(this, 'imsi')" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></div>
            </div>
            <div class="grid-2" style="margin-top:10px;">
                <div><label for="inputIccid">ICCID</label><input type="text" id="inputIccid" placeholder="89..." style="font-family:monospace;" inputmode="numeric" oninput="validateRealtime(this, 'luhn')" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></div>
                <div><label for="inputSerial">Serial</label><input type="text" id="inputSerial" placeholder="Alphanumeric..." style="font-family:monospace;" autocapitalize="characters" oninput="validateRealtime(this, 'alphanum')" spellcheck="false" autocomplete="off" autocorrect="off"></div>
            </div>
            <div class="section-header">Network</div><div class="grid-2">
                <div><label for="inputWifiMac">WiFi MAC</label><input type="text" id="inputWifiMac" placeholder="00:11:22:33:44:55" style="font-family:monospace;" autocapitalize="characters" oninput="validateRealtime(this, 'mac')" spellcheck="false" autocomplete="off" autocorrect="off"></div>
                <div><label for="inputBtMac">BT MAC</label><input type="text" id="inputBtMac" placeholder="00:11:22:33:44:55" style="font-family:monospace;" autocapitalize="characters" oninput="validateRealtime(this, 'mac')" spellcheck="false" autocomplete="off" autocorrect="off"></div>
            </div>
            <div class="section-header">Operator</div><div class="grid-2">
                <div><label for="inputSimIso">SIM ISO</label><input type="text" id="inputSimIso" placeholder="ISO" oninput="validateRealtime(this, 'iso')" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></div>
                <div><label for="inputSimOp">Operator</label><input type="text" id="inputSimOp" placeholder="Operator" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></div>
            </div>
            <div style="margin-top:15px; display:flex; justify-content:flex-end; gap:10px;"><button type="button" onclick="clearSpoofingInputs()" style="background:transparent; border:1px solid var(--danger); color:var(--danger); min-height:44px; padding:0 20px;">Clear All</button><button onclick="runWithState(this, 'Saving...', applySpoofing)" class="danger">Apply System-Wide</button></div>
        </div>
        <div class="panel"><h3>Location Spoofing (Privacy Suite)</h3>
            <div class="row"><label for="spoof_location">Enable Location Spoofing</label><input type="checkbox" class="toggle" id="spoof_location" onchange="toggle('spoof_location')"></div>
            <div style="font-size:0.85em; color:#888; margin-bottom:15px;">Simulates GPS coordinates for target apps. Qualcomm and MediaTek devices supported.</div>
            <div class="grid-2">
                <div><label for="inputLatitude">Latitude</label><input type="text" id="inputLatitude" placeholder="41.0082" style="font-family:monospace;" inputmode="decimal" oninput="validateRealtime(this, 'lat')" aria-label="Latitude (-90 to 90)" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></div>
                <div><label for="inputLongitude">Longitude</label><input type="text" id="inputLongitude" placeholder="28.9784" style="font-family:monospace;" inputmode="decimal" oninput="validateRealtime(this, 'lng')" aria-label="Longitude (-180 to 180)" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></div>
            </div>
            <div class="grid-2" style="margin-top:10px;">
                <div><label for="inputAltitude">Altitude (m)</label><input type="text" id="inputAltitude" placeholder="0" style="font-family:monospace;" inputmode="decimal" aria-label="Altitude in meters" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></div>
                <div><label for="inputAccuracy">Accuracy (m)</label><input type="text" id="inputAccuracy" placeholder="1.0" style="font-family:monospace;" inputmode="decimal" aria-label="GPS accuracy in meters" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></div>
            </div>
            <div class="section-header" style="margin-top:15px;">Random Location Mode</div>
            <div style="font-size:0.85em; color:#888; margin-bottom:10px;">Periodically changes location within a radius of the center coordinates above. Optimized for low CPU/RAM usage.</div>
            <div class="row"><label for="chkLocationRandom">Enable Random Location</label><input type="checkbox" class="toggle" id="chkLocationRandom" aria-label="Enable random location changes"></div>
            <div class="grid-2" style="margin-top:10px;">
                <div><label for="inputLocationRadius">Radius (m)</label><input type="text" id="inputLocationRadius" placeholder="500" value="500" style="font-family:monospace;" inputmode="numeric" aria-label="Random location radius in meters" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></div>
                <div><label for="inputLocationInterval">Interval (sec)</label><input type="text" id="inputLocationInterval" placeholder="30" value="30" style="font-family:monospace;" inputmode="numeric" aria-label="Random location update interval in seconds" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></div>
            </div>
            <div style="margin-top:15px;"><button onclick="runWithState(this, 'Saving...', applyLocationSpoof)" class="primary" style="width:100%;">Save Location Settings</button></div>
        </div>
    </div>

    <div id="apps" class="content" role="tabpanel" aria-labelledby="tab_apps">
        <div class="panel">
            <h3>New Rule</h3>
            <div style="margin-bottom:10px; position:relative;"><label for="appPkg">Package Name</label><input type="text" id="appPkg" list="pkgList" placeholder="Type to search packages..." oninput="toggleAddButton(); document.getElementById('clearPkgBtn').style.display=this.value?'block':'none';" onkeydown="if(event.key==='Enter') addAppRule()" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off" style="padding-right:30px;"><button id="clearPkgBtn" class="clear-btn" onclick="document.getElementById('appPkg').value=''; this.style.display='none'; toggleAddButton(); document.getElementById('appPkg').focus();" style="top:auto; bottom:6px; transform:none;">&times;</button><datalist id="pkgList"></datalist></div>
            <div class="grid-2" style="margin-bottom:10px;"><div><label for="appTemplate">Identity Profile</label><select id="appTemplate"><option value="null">No Identity Spoof</option></select></div><div style="position:relative;"><label for="appKeybox">Custom Keybox</label><input type="text" id="appKeybox" list="keyboxList" placeholder="Custom Keybox" oninput="document.getElementById('clearKbBtn').style.display=this.value?'block':'none';" onkeydown="if(event.key==='Enter') addAppRule()" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off" style="padding-right:30px;"><button id="clearKbBtn" class="clear-btn" onclick="document.getElementById('appKeybox').value=''; this.style.display='none'; document.getElementById('appKeybox').focus();" style="top:auto; bottom:6px; transform:none;">&times;</button><datalist id="keyboxList"></datalist></div></div>
            <div class="section-header">Blank Permissions (Privacy)</div><div style="display:flex; gap:15px;"><div class="row"><input type="checkbox" id="permContacts" class="toggle"><label for="permContacts">Contacts</label></div><div class="row"><input type="checkbox" id="permMedia" class="toggle"><label for="permMedia">Media</label></div><div class="row"><input type="checkbox" id="permMicrophone" class="toggle"><label for="permMicrophone">Microphone</label></div></div>
            <button id="btnAddRule" class="primary" style="width:100%" onclick="addAppRule()" disabled>Add Rule</button>
        </div>
        <div class="panel">
            <h3>Active Rules</h3><div class="search-container"><input type="search" id="appFilter" placeholder="Filter active rules by package name..." oninput="renderAppTable()" aria-label="Filter rules" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"><button onclick="document.getElementById('appFilter').value=''; renderAppTable();" class="clear-btn" id="clearAppFilterBtn" aria-label="Clear filter">&times;</button></div>
            <table id="appTable" class="responsive-table"><thead><tr><th>Package</th><th>Profile</th><th>Keybox</th><th>Permissions</th><th></th></tr></thead><tbody></tbody></table>
            <div style="margin-top:15px; text-align:right;"><button onclick="runWithState(this, 'Saving...', saveAppConfig)" class="primary">Save Configuration</button></div>
        </div>
    </div>

    <div id="keys" class="content" role="tabpanel" aria-labelledby="tab_keys">
        <div id="lockedSection" style="display:none;">
            <div class="panel" style="border-color:var(--danger);">
                <h3 style="color:var(--danger);">Encrypted Keyboxes Detected</h3>
                <div id="lockedList"></div>
            </div>
        </div>

        <div class="panel">
            <h3>Remote Servers</h3>
            <div id="serverList"></div>
            <button onclick="document.getElementById('addServerForm').style.display='block'" class="primary" style="width:100%">+ Add Server</button>

            <div id="addServerForm" style="display:none; margin-top:15px; border-top:1px solid var(--border); padding-top:15px;">
                <input type="text" id="srvName" placeholder="Name" style="margin-bottom:5px;" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off">
                <input type="text" id="srvUrl" placeholder="URL (HTTPS)" style="margin-bottom:5px;" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off">
                <select id="srvAuthType" style="margin-bottom:5px;" onchange="
                    const t = this.value;
                    const af = document.getElementById('authFields');
                    if (t === 'NONE') af.innerHTML = '';
                    else if (t === 'BEARER') af.innerHTML = '<input type=\'text\' id=\'srvAuthToken\' placeholder=\'Bearer Token\' style=\'margin-bottom:5px;\' spellcheck=\'false\' autocomplete=\'off\' autocorrect=\'off\' autocapitalize=\'off\'>';
                    else if (t === 'BASIC') af.innerHTML = '<input type=\'text\' id=\'srvAuthUser\' placeholder=\'Username\' style=\'margin-bottom:5px;\' spellcheck=\'false\' autocomplete=\'off\' autocorrect=\'off\' autocapitalize=\'off\'><input type=\'password\' id=\'srvAuthPass\' placeholder=\'Password\' style=\'margin-bottom:5px;\' spellcheck=\'false\' autocomplete=\'off\' autocorrect=\'off\' autocapitalize=\'off\'>';
                    else if (t === 'API_KEY') af.innerHTML = '<input type=\'text\' id=\'srvApiKeyName\' placeholder=\'Header Name (e.g. X-API-Key)\' style=\'margin-bottom:5px;\' spellcheck=\'false\' autocomplete=\'off\' autocorrect=\'off\' autocapitalize=\'off\'><input type=\'password\' id=\'srvApiKeyValue\' placeholder=\'API Key\' style=\'margin-bottom:5px;\' spellcheck=\'false\' autocomplete=\'off\' autocorrect=\'off\' autocapitalize=\'off\'>';
                ">
                    <option value="NONE">No Auth</option>
                    <option value="BEARER">Bearer Token</option>
                    <option value="BASIC">Basic Auth</option>
                    <option value="API_KEY">API Key</option>
                </select>
                <div id="authFields"></div>
                <div style="display: flex; gap: 10px; margin-top: 10px;">
                    <button onclick="runWithState(this, 'Saving...', addServer)" class="primary" style="flex: 1;">Save Server</button>
                    <button onclick="document.getElementById('addServerForm').style.display='none'; document.getElementById('srvName').value=''; document.getElementById('srvUrl').value=''; document.getElementById('srvAuthType').value='NONE'; document.getElementById('authFields').innerHTML='';" style="flex: 1;">Cancel</button>
                </div>
            </div>
        </div>

        <div class="panel">
            <h3>Upload Keybox / CBOX</h3>
            <div class="grid-2">
                <div id="dropZone" role="button" tabindex="0" style="border: 2px dashed var(--border); border-radius: 6px; padding: 20px; text-align: center; margin-bottom: 10px; cursor: pointer;" onclick="document.getElementById('kbFilePicker').click()" onkeydown="if(event.key==='Enter'||event.key===' '){event.preventDefault(); document.getElementById('kbFilePicker').click();}">
                    <label for="kbFilename" style="display:none">Keybox File</label>
                    <input type="file" id="kbFilePicker" style="display:none" onchange="loadFileContent(this)" onclick="event.stopPropagation(); this.value = null" aria-label="Upload Keybox File">
                    <div id="dropZoneContent"><div style="font-size: 1.5em; margin-bottom: 10px; color: #888;">[ Drag &amp; Drop ]</div><div style="font-size: 0.9em; color: #888;">Or click to select .xml, .cbox, or .zip</div></div>
                </div>
                <div>
                    <label for="kbContent" style="display:block; font-size:0.85em; color:#888; margin-bottom:4px;">Manual Paste (XML)</label>
                    <textarea id="kbContent" placeholder="Paste Keybox XML Content Here" style="height:100px; font-family:monospace; font-size:0.8em; margin-bottom:10px;" aria-label="Keybox XML Content" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></textarea>
                    <input type="text" id="kbFilenameInput" placeholder="keybox.xml" style="margin-bottom:10px;" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off">
                    <button id="saveKeyboxBtn" class="primary" style="width:100%;" onclick="runWithState(this, 'Saving...', savePastedKeybox)">Save Pasted XML</button>
                </div>
            </div>
        </div>
        <div class="panel">
            <h3>Stored Keyboxes</h3>
            <div class="search-container"><input type="search" id="keyboxFilter" placeholder="Filter keyboxes by name..." oninput="renderKeyboxes()" aria-label="Filter keyboxes" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"><button onclick="document.getElementById('keyboxFilter').value=''; renderKeyboxes();" class="clear-btn" id="clearKeyboxFilterBtn" aria-label="Clear filter">&times;</button></div>
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
        <div class="panel">
            <h3>Language Support</h3>
            <p>The module is English-first, but supports community translations.</p>
            <p>To add a language, place a <code>lang.json</code> file in <code>/data/adb/cleverestricky/</code>.</p>
            <div class="grid-2">
                <button onclick="runWithState(this, 'Downloading...', downloadLangTemplate)">Download Template</button>
                <button onclick="runWithState(this, 'Loading...', loadLanguage)">Reload Language File</button>
            </div>
        </div>
    </div>

    <div id="editor" class="content" role="tabpanel" aria-labelledby="tab_editor">
        <div class="panel">
            <div class="row"><select id="fileSelector" onchange="loadFile()" style="width:70%;" aria-label="Select file to edit"><option value="target.txt">target.txt</option><option value="security_patch.txt">security_patch.txt</option><option value="spoof_build_vars">spoof_build_vars</option><option value="app_config">app_config</option><option value="drm_fix">drm_fix</option></select><button id="revertBtn" class="danger" onclick="revertEditor()" style="display:none; margin-right:10px;" title="Revert Changes">Revert</button><button id="saveBtn" onclick="handleSave(this)" title="Ctrl+S">Save</button></div>
            <textarea id="fileEditor" style="height:500px; font-family:monospace; margin-top:10px; line-height:1.4;" aria-label="File Content" onclick="editorUnsavedBypass = false;" oninput="editorUnsavedBypass = false; updateSaveButtonState()" onkeydown="if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='s'){event.preventDefault();handleSave(document.getElementById('saveBtn'));}" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></textarea>
        </div>
    </div>

    <div id="donate" class="content" role="tabpanel" aria-labelledby="tab_donate">
        <div class="panel">
            <h3>Support the Development</h3>
            <p style="color:#888; margin-bottom:15px;">If you find this project helpful, consider supporting the development. Your contributions help maintain the project and develop new features.</p>
        </div>
        <div class="panel">
            <h3>Crypto Addresses</h3>
            <table class="responsive-table">
                <thead><tr><th>Asset</th><th>Network</th><th>Address</th><th></th></tr></thead>
                <tbody>
                    <tr><td data-label="Asset"><strong>USDT</strong></td><td data-label="Network">TRC20</td><td data-label="Address" style="font-family:monospace; font-size:0.85em; word-break:break-all;">TQGTsbqawRHhv35UMxjHo14mieUGWXyQzk</td><td><button onclick="copyToClipboard('TQGTsbqawRHhv35UMxjHo14mieUGWXyQzk','Copied USDT Address',this)" style="padding:4px 8px; font-size:0.8em;">Copy</button></td></tr>
                    <tr><td data-label="Asset"><strong>XMR</strong></td><td data-label="Network">Monero</td><td data-label="Address" style="font-family:monospace; font-size:0.75em; word-break:break-all;">85m61iuWiwp24g8NRXoMKdW25ayVWFzYf5BoAqvgGpLACLuMsXbzGbWR9mC8asnCSfcyHN3dZgEX8KZh2pTc9AzWGXtrEUv</td><td><button onclick="copyToClipboard('85m61iuWiwp24g8NRXoMKdW25ayVWFzYf5BoAqvgGpLACLuMsXbzGbWR9mC8asnCSfcyHN3dZgEX8KZh2pTc9AzWGXtrEUv','Copied XMR Address',this)" style="padding:4px 8px; font-size:0.8em;">Copy</button></td></tr>
                    <tr><td data-label="Asset"><strong>USDT / USDC</strong></td><td data-label="Network">ERC20 / BEP20</td><td data-label="Address" style="font-family:monospace; font-size:0.85em; word-break:break-all;">0x1a4b9e55e268e6969492a70515a5fd9fd4e6ea8b</td><td><button onclick="copyToClipboard('0x1a4b9e55e268e6969492a70515a5fd9fd4e6ea8b','Copied ERC20 Address',this)" style="padding:4px 8px; font-size:0.8em;">Copy</button></td></tr>
                </tbody>
            </table>
        </div>
        <div class="panel">
            <h3>Platforms</h3>
            <div style="display:flex; flex-direction:column; gap:12px;">
                <div class="row"><span style="font-weight:bold;">Binance User ID</span><span style="font-family:monospace;">114574830 <button onclick="copyToClipboard('114574830','Copied Binance ID',this)" style="padding:2px 8px; font-size:0.8em; margin-left:5px;">Copy</button></span></div>
                <div class="row"><span style="font-weight:bold;">PayPal</span><a href="https://www.paypal.me/tryigitx" target="_blank" style="color:var(--accent); text-decoration:none;">paypal.me/tryigitx</a></div>
                <div class="row"><span style="font-weight:bold;">BuyMeACoffee</span><a href="https://buymeacoffee.com/yigitx" target="_blank" style="color:var(--accent); text-decoration:none;">buymeacoffee.com/yigitx</a></div>
            </div>
        </div>
        <div class="panel" style="text-align:center;">
            <p style="color:#888;">Thank you for your support!</p>
        </div>
    </div>

    <script>
        const baseUrl = '/api';
        let editorUnsavedBypass = false;
        let currentFile = '';
        let originalContent = '';

        function requireConfirm(btn, action, confirmText = 'Click again to confirm', onCancel = null) {
            if (btn.dataset.confirming === "true") {
                btn.dataset.confirming = "false";
                btn.innerText = btn.dataset.origText;
                btn.classList.remove('confirm-active');
                // Execute and clear cancel function
                if (btn._onCancel) delete btn._onCancel;
                action();
            } else {
                btn.dataset.origText = btn.innerText;
                btn.dataset.confirming = "true";
                btn.innerText = confirmText;
                btn.classList.add('confirm-active');
                if (onCancel) btn._onCancel = onCancel;

                const timeoutId = setTimeout(() => resetConfirm(btn), 3000);
                btn.dataset.confirmTimeout = timeoutId;

                const abortHandler = (e) => {
                    if (e.target !== btn) {
                        resetConfirm(btn);
                        document.removeEventListener('click', abortHandler);
                        document.removeEventListener('input', abortHandler);
                    }
                };
                setTimeout(() => {
                    if (btn.dataset.confirming === "true") {
                        document.addEventListener('click', abortHandler);
                        document.addEventListener('input', abortHandler);
                    }
                }, 50);
            }
        }

        function resetConfirm(btn) {
            if (btn.dataset.confirming === "true") {
                btn.dataset.confirming = "false";
                btn.innerText = btn.dataset.origText;
                btn.classList.remove('confirm-active');
                if (btn.dataset.confirmTimeout) clearTimeout(parseInt(btn.dataset.confirmTimeout));
                if (btn._onCancel) {
                    btn._onCancel();
                    delete btn._onCancel;
                }
            }
        }

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
                btn.innerText = 'Copied';
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
                iconHtml = '<span class="island-icon" style="color:var(--danger); font-weight:bold;">!</span>';
            } else {
                iconHtml = '<span class="island-icon" style="color:var(--success); font-weight:bold;">OK</span>';
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
            } else if (type === 'lat') {
                const num = parseFloat(val);
                if (!isNaN(num) && num >= -90 && num <= 90) isValid = true;
                else msg = "Must be -90 to 90";
            } else if (type === 'lng') {
                const num = parseFloat(val);
                if (!isNaN(num) && num >= -180 && num <= 180) isValid = true;
                else msg = "Must be -180 to 180";
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
            const editor = document.getElementById('fileEditor');
            if (currentFile && editor && editor.value !== originalContent) {
                if (!editorUnsavedBypass) {
                    notify('You have unsaved changes. Click tab again to discard.', 'error');
                    editorUnsavedBypass = true;
                    return;
                }
                editor.value = originalContent;
                updateSaveButtonState();
            }
            editorUnsavedBypass = false;
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
                const tabs = ['dashboard', 'spoof', 'apps', 'keys', 'info', 'guide', 'editor', 'donate'];
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

            // Refresh keybox count on dashboard
            try {
                const configRes = await fetchAuth(getAuthUrl('/api/config'));
                const configData = await configRes.json();
                document.getElementById('keyboxStatus').innerText = `${'$'}{configData.keybox_count} Keys Loaded`;
            } catch(e) {}

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
                        <input type="password" id="pwd_${'$'}{f}" placeholder="Password" style="margin-bottom:5px;" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off">
                        <textarea id="pk_${'$'}{f}" placeholder="Public Key (Optional)" style="height:60px; font-size:0.8em; margin-bottom:5px;" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></textarea>
                        <button onclick="runWithState(this, 'Unlocking...', () => unlockCbox('${'$'}{f}'))">Unlock</button>`;
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
            if (!pwd.trim()) { notify('Password required', 'error'); return; }
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
            const list = document.getElementById('serverList');
            if (list) list.innerHTML = '<div style="padding:15px; text-align:center; color:#888;">Loading...</div>';
            const res = await fetchAuth('/api/servers');
            const servers = await res.json();
            if (list) list.innerHTML = '';
            if (servers.length === 0) {
                if (list) list.innerHTML = '<div style="text-align:center; padding:15px; color:#666;">No servers configured. Add one below to fetch keyboxes automatically.</div>';
            }
            servers.forEach(s => {
                const div = document.createElement('div');
                div.className = 'server-item';
                div.innerHTML = `<div><div style="font-weight:bold">${'$'}{s.name}</div><div style="font-size:0.8em; color:#888;">${'$'}{s.url}</div></div>
                <div><span class="status-badge status-${'$'}{s.lastStatus.startsWith('OK')?'OK':'ERROR'}">${'$'}{s.lastStatus}</span>
                <button style="padding:4px 8px; margin-left:10px;" onclick="refreshServer('${'$'}{s.id}')">Refresh</button>
                <button class="danger" style="padding:4px 8px; margin-left:5px;" onclick="requireConfirm(this, () => deleteServer('${'$'}{s.id}'), 'Confirm Remove')">Remove</button></div>`;
                list.appendChild(div);
            });
        }

        async function addServer() {
            const name = document.getElementById('srvName').value;
            const url = document.getElementById('srvUrl').value;
            if (!name.trim() || !url.trim()) { notify('Name and URL required', 'error'); throw new Error('Validation failed'); }
            if (!url.startsWith('http://') && !url.startsWith('https://')) { notify('URL must start with http/https', 'error'); throw new Error('Validation failed'); }
            const authType = document.getElementById('srvAuthType').value;
            const authData = {};
            if (authType === 'BEARER') authData.token = document.getElementById('srvAuthToken')?.value || '';
            else if (authType === 'BASIC') { authData.username = document.getElementById('srvAuthUser')?.value || ''; authData.password = document.getElementById('srvAuthPass')?.value || ''; }
            else if (authType === 'API_KEY') { authData.keyName = document.getElementById('srvApiKeyName')?.value || ''; authData.keyValue = document.getElementById('srvApiKeyValue')?.value || ''; }
            const data = { name, url, authType, authData };

            try {
                const formData = new FormData();
                formData.append('data', JSON.stringify(data));
                const res = await fetchAuth('/api/server/add', { method: 'POST', body: formData });
                if (res.ok) { notify('Server Added'); document.getElementById('addServerForm').style.display='none'; document.getElementById('srvName').value=''; document.getElementById('srvUrl').value=''; document.getElementById('srvAuthType').value='NONE'; document.getElementById('authFields').innerHTML=''; loadServers(); }
                else notify('Failed', 'error');
            } catch(e) { notify('Error', 'error'); throw e; }
        }

        async function deleteServer(id) {
            try {
                notify('Removing...', 'working');
                const formData = new FormData();
                formData.append('id', id);
                const res = await fetchAuth('/api/server/delete', { method: 'POST', body: formData });
                if (res.ok) { notify('Server Removed'); loadServers(); } else { notify('Failed', 'error'); }
            } catch(e) { notify('Error', 'error'); }
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
                if (!file.name.endsWith('.cbox') && !file.name.endsWith('.zip')) {
                    const reader = new FileReader();
                    reader.onload = (e) => document.getElementById('kbContent').value = e.target.result;
                    reader.readAsText(file);
                } else {
                    document.getElementById('kbContent').value = 'Binary file (' + file.name + ') selected. Preview unavailable.';
                }

                // 2. Upload
                const dz = document.getElementById('dropZoneContent');
                dz.innerHTML = '<div style="font-size: 1.5em; margin-bottom: 10px; color:var(--success); font-weight:bold;">OK</div>';
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
                    document.getElementById('kbContent').value = '';
                    try {
                        const body = await res.clone().json();
                        if (body.keybox_count !== undefined) {
                            document.getElementById('keyboxStatus').innerText = body.keybox_count + ' Keys Loaded';
                        }
                    } catch(e) {}
                    loadKeyInfo();
                } catch(e) { notify('Error', 'error'); } finally {
                    resetDropZone();
                }
            }
        }

        function resetDropZone() {
            const dz = document.getElementById('dropZoneContent');
            dz.innerHTML = '<div style="font-size: 1.5em; margin-bottom: 10px; color: #888;">[ Upload ]</div><div style="font-size: 0.9em; color: #888;">Select .xml, .cbox, or .zip</div>';
            document.getElementById('dropZone').style.borderColor = 'var(--border)';
        }

        async function savePastedKeybox() {
            const content = document.getElementById('kbContent').value.trim();
            if (!content) {
                notify('Please paste XML content first', 'error');
                return;
            }
            let filenameInput = document.getElementById('kbFilenameInput').value.trim();
            let filename = filenameInput || 'keybox.xml';
            if (!filename.endsWith('.xml')) filename += '.xml';

            try {
                const res = await fetchAuth('/api/upload_keybox', {
                    method: 'POST',
                    body: new URLSearchParams({ filename: filename, content: content })
                });
                if (!res.ok) {
                    const msg = await res.text();
                    notify('Error: ' + msg, 'error');
                } else {
                    notify('Saved Successfully');
                    document.getElementById('kbContent').value = '';
                    try {
                        const body = await res.clone().json();
                        if (body.keybox_count !== undefined) {
                            document.getElementById('keyboxStatus').innerText = body.keybox_count + ' Keys Loaded';
                        }
                    } catch(e) {}
                    loadKeyInfo();
                }
            } catch(e) {
                notify('Error', 'error');
            }
        }

        // Rest of existing JS (simplified/merged)
        async function init() {
            if (!token) return;
            console.log('[CleveresTricky] init: loading config...');
            try {
                const res = await fetchAuth(getAuthUrl('/api/config'));
                const data = await res.json();
                console.log('[CleveresTricky] config loaded:', JSON.stringify({rkp_bypass: data.rkp_bypass, global_mode: data.global_mode, keybox_count: data.keybox_count, tee_broken_mode: data.tee_broken_mode}));
                ['global_mode', 'tee_broken_mode', 'rkp_bypass', 'auto_beta_fetch', 'auto_keybox_check', 'random_on_boot', 'drm_fix', 'random_drm_on_boot', 'auto_patch_update', 'hide_sensitive_props', 'spoof_region_cn', 'remove_magisk_32', 'spoof_location', 'imei_global', 'network_global'].forEach(k => {
                    if(document.getElementById(k)) document.getElementById(k).checked = data[k];
                });
                determineActiveProfile(data);
                document.getElementById('keyboxStatus').innerText = `${'$'}{data.keybox_count} Keys Loaded`;

                const rkpStatus = document.getElementById('status_rkp');
                if (rkpStatus) {
                    if (data.rkp_bypass) { rkpStatus.innerText = 'Active'; rkpStatus.style.color = 'var(--success)'; }
                    else { rkpStatus.innerText = 'Inactive'; rkpStatus.style.color = 'var(--danger)'; }
                }
                const drmStatus = document.getElementById('status_drm');
                if (drmStatus) {
                    if (data.drm_fix) { drmStatus.innerText = 'Active'; drmStatus.style.color = 'var(--success)'; }
                    else { drmStatus.innerText = 'Inactive'; drmStatus.style.color = 'var(--danger)'; }
                }
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

            // Load location settings from spoof_build_vars
            try {
                const locRes = await fetchAuth('/api/file?filename=spoof_build_vars');
                if (locRes.ok) {
                    const spoofContent = await locRes.text();
                    const locMap = {};
                    spoofContent.split('\n').forEach(line => {
                        if (line.trim().startsWith('#') || !line.includes('=')) return;
                        const parts = line.split('=');
                        locMap[parts[0].trim()] = parts.slice(1).join('=').trim();
                    });
                    if (locMap['SPOOF_LATITUDE']) document.getElementById('inputLatitude').value = locMap['SPOOF_LATITUDE'];
                    if (locMap['SPOOF_LONGITUDE']) document.getElementById('inputLongitude').value = locMap['SPOOF_LONGITUDE'];
                    if (locMap['SPOOF_ALTITUDE']) document.getElementById('inputAltitude').value = locMap['SPOOF_ALTITUDE'];
                    if (locMap['SPOOF_ACCURACY']) document.getElementById('inputAccuracy').value = locMap['SPOOF_ACCURACY'];
                    if (locMap['SPOOF_LOCATION_RANDOM'] === 'true') document.getElementById('chkLocationRandom').checked = true;
                    if (locMap['SPOOF_LOCATION_RADIUS']) document.getElementById('inputLocationRadius').value = locMap['SPOOF_LOCATION_RADIUS'];
                    if (locMap['SPOOF_LOCATION_INTERVAL']) document.getElementById('inputLocationInterval').value = locMap['SPOOF_LOCATION_INTERVAL'];
                }
            } catch(e) { console.log('[CleveresTricky] Location settings load failed (expected if no file)'); }
        }

        async function toggle(setting) { const el = document.getElementById(setting); try { const res = await fetchAuth('/api/toggle', {method:'POST', body: new URLSearchParams({setting, value: el.checked})}); if (res.ok) { notify('Setting Updated'); if (setting === 'rkp_bypass') { const s = document.getElementById('status_rkp'); if(s) { if(el.checked) { s.innerText='Active'; s.style.color='var(--success)'; } else { s.innerText='Inactive'; s.style.color='var(--danger)'; } } } else if (setting === 'drm_fix') { const s = document.getElementById('status_drm'); if(s) { if(el.checked) { s.innerText='Active'; s.style.color='var(--success)'; } else { s.innerText='Inactive'; s.style.color='var(--danger)'; } } } } else { throw new Error('Server returned ' + res.status); } } catch(e){ el.checked=!el.checked; notify('Failed', 'error'); } }

        function editDrmConfig() {
            document.getElementById('fileSelector').value = 'drm_fix';
            switchTab('editor');
            loadFile();
        }
        async function resetDrmId() {
            notify('Regenerating...', 'working');
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
                clearSpoofingInputs();
            }
            delete sel.dataset.lockExtras;
        }

        async function generateRandomIdentity() {
            const res = await fetchAuth('/api/random_identity');
            if (!res.ok) { notify('Failed', 'error'); return; }
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

        async function verifyKeyboxes() {
            const resultDiv = document.getElementById('verifyResult');
            resultDiv.innerHTML = '<div style="color:#888;">Verifying... Please wait.</div>';
            try {
                const res = await fetchAuth('/api/verify_keyboxes', { method: 'POST' });
                if (!res.ok) {
                    const txt = await res.text();
                    resultDiv.innerHTML = '<div style="color:var(--danger);">' + txt + '</div>';
                    notify('Verification Failed', 'error');
                    return;
                }
                const results = await res.json();
                resultDiv.innerHTML = '';
                if (results.length === 0) {
                    resultDiv.innerHTML = '<div style="color:#888;">No keyboxes found.</div>';
                    notify('No keyboxes to verify');
                    return;
                }
                results.forEach(r => {
                    const div = document.createElement('div');
                    div.style.padding = '8px';
                    div.style.marginBottom = '5px';
                    div.style.border = '1px solid var(--border)';
                    div.style.borderRadius = '4px';
                    const isSuccess = r.status === 'VALID' || r.status === 'OK';
                    const color = isSuccess ? 'var(--success)' : 'var(--danger)';
                    div.style.borderLeft = '4px solid ' + color;

                    const titleDiv = document.createElement('div');
                    titleDiv.style.fontWeight = 'bold';
                    titleDiv.textContent = r.filename;

                    const statusDiv = document.createElement('div');
                    statusDiv.style.color = color;
                    statusDiv.style.fontSize = '0.9em';
                    statusDiv.style.marginTop = '2px';
                    statusDiv.textContent = r.status;

                    const detailsDiv = document.createElement('div');
                    detailsDiv.style.color = '#888';
                    detailsDiv.style.fontSize = '0.8em';
                    detailsDiv.style.marginTop = '2px';
                    detailsDiv.style.wordBreak = 'break-all';
                    detailsDiv.textContent = r.details;

                    div.appendChild(titleDiv);
                    div.appendChild(statusDiv);
                    div.appendChild(detailsDiv);
                    resultDiv.appendChild(div);
                });
                notify('Verification Complete');
            } catch(e) {
                resultDiv.innerHTML = '<div style="color:var(--danger);">Error: ' + e.message + '</div>';
                notify('Error', 'error');
            }
        }

        let cachedKeyboxes = [];
        async function loadKeyboxes() {
            try {
                const list = document.getElementById('storedKeyboxesList');
                if (list) list.innerHTML = '<div style="padding:10px; text-align:center; color:#888;">Loading...</div>';
                const res = await fetchAuth('/api/keyboxes');
                if (res.ok) {
                    cachedKeyboxes = await res.json();
                    renderKeyboxes();
                    const dl = document.getElementById('keyboxList');
                    if (dl) { dl.innerHTML = ''; cachedKeyboxes.forEach(k => { const opt = document.createElement('option'); opt.value = k; dl.appendChild(opt); }); }
                }
            } catch(e) {}
        }

        function renderKeyboxes() {
            const list = document.getElementById('storedKeyboxesList');
            const filterInput = document.getElementById('keyboxFilter');
            const clearBtn = document.getElementById('clearKeyboxFilterBtn');
            if (clearBtn) clearBtn.style.display = (filterInput && filterInput.value) ? 'block' : 'none';
            const filterText = filterInput ? filterInput.value.toLowerCase() : '';
            if (!list) return;
            list.innerHTML = '';
            let matchCount = 0;

            cachedKeyboxes.forEach(k => {
                if (filterText && !k.toLowerCase().includes(filterText)) return;
                matchCount++;
                const div = document.createElement('div'); div.className = 'row'; div.style.padding = '10px'; div.style.borderBottom = '1px solid var(--border)';
                div.innerHTML = `<span>${'$'}{k}</span><div><span style="font-size:0.8em; color:#666; margin-right:15px;">Stored</span><button class="danger" style="padding:4px 8px; font-size:0.8em;" onclick="requireConfirm(this, () => deleteKeybox('${'$'}{k}'), 'Confirm Delete')" title="Delete Keybox" aria-label="Delete ${'$'}{k}">Delete</button></div>`;
                list.appendChild(div);
            });

            if (filterText && matchCount === 0) {
                 const div = document.createElement('div');
                 div.style.padding = '10px'; div.style.textAlign = 'center'; div.style.color = '#666';
                 div.innerHTML = 'No keyboxes match your filter. <button onclick="document.getElementById(\'keyboxFilter\').value=\'\'; renderKeyboxes()" style="margin-left:10px; padding:4px 8px; font-size:0.85em;">Clear Filter</button>';
                 list.appendChild(div);
            } else if (cachedKeyboxes.length === 0) {
                 const div = document.createElement('div');
                 div.style.padding = '10px'; div.style.textAlign = 'center'; div.style.color = '#666';
                 div.innerText = 'No keyboxes stored.';
                 list.appendChild(div);
            }
        }

        async function deleteKeybox(filename) {
            notify('Deleting...', 'working');
            try {
                const formData = new URLSearchParams();
                formData.append('filename', filename);
                const res = await fetchAuth('/api/delete_keybox', { method: 'POST', body: formData });
                if (res.ok) {
                    notify('Deleted');
                    loadKeyInfo();
                } else {
                    const txt = await res.text();
                    notify('Failed: ' + txt, 'error');
                }
            } catch (e) {
                notify('Error', 'error');
            }
        }

        function clearSpoofingInputs() {
            ['inputImei', 'inputImsi', 'inputIccid', 'inputSerial', 'inputWifiMac', 'inputBtMac', 'inputSimIso', 'inputSimOp'].forEach(id => {
                const el = document.getElementById(id);
                if (el) {
                    el.value = '';
                    el.classList.remove('valid', 'invalid');
                    const next = el.nextElementSibling;
                    if (next && next.classList.contains('error-msg')) next.remove();
                }
            });
        }

        async function saveAdvancedSpoof() { await applySpoofing(); }

        async function applySpoofing() {
             const inputTypes = {
                 'inputImei': 'luhn', 'inputImsi': 'imsi', 'inputIccid': 'luhn',
                 'inputSerial': 'alphanum', 'inputWifiMac': 'mac', 'inputBtMac': 'mac', 'inputSimIso': 'iso'
             };
             for (const [id, type] of Object.entries(inputTypes)) {
                 const el = document.getElementById(id);
                 if (el.value) {
                     validateRealtime(el, type);
                     if (el.classList.contains('invalid')) {
                         notify('Invalid ' + id.replace('input', '').toUpperCase(), 'error');
                         el.focus();
                         return;
                     }
                 }
             }

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
             }
        }

        let appRules = [];
        async function loadAppConfig() {
            const tbody = document.querySelector('#appTable tbody');
            if(tbody) tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; padding:20px; color:#888;">Loading...</td></tr>';
            const res = await fetchAuth(getAuthUrl('/api/app_config_structured'));
            appRules = await res.json();
            renderAppTable();
        }
        function renderAppTable() {
            const filterInput = document.getElementById('appFilter');
            const clearBtn = document.getElementById('clearAppFilterBtn');
            if (clearBtn) clearBtn.style.display = (filterInput && filterInput.value) ? 'block' : 'none';
            const filter = filterInput ? filterInput.value.toLowerCase() : '';
            const tbody = document.querySelector('#appTable tbody');
            tbody.innerHTML = '';
            if (appRules.length === 0) {
                const tr = document.createElement('tr'); tr.innerHTML = '<td colspan="5" style="text-align:center; padding:20px; color:#666;">No active rules.</td>'; tbody.appendChild(tr); return;
            }
            let matchCount = 0;
            appRules.forEach((rule, idx) => {
                if (filter && !rule.package.toLowerCase().includes(filter)) return;
                matchCount++;
                const tr = document.createElement('tr');
                const permStr = (rule.permissions && rule.permissions.length > 0) ? rule.permissions.join(', ') : '';
                tr.innerHTML = `<td data-label="Package">${'$'}{rule.package}</td><td data-label="Profile">${'$'}{rule.template === 'null' ? 'Default' : rule.template}</td><td data-label="Keybox">${'$'}{rule.keybox && rule.keybox !== 'null' ? rule.keybox : ''}</td><td data-label="Permissions">${'$'}{permStr}</td><td style="text-align:right;"><button style="padding:4px 8px; margin-right:5px;" onclick="editAppRule(${'$'}{idx})" title="Edit rule" aria-label="Edit rule for ${'$'}{rule.package}">Edit</button><button class="danger" style="padding:4px 8px;" onclick="requireConfirm(this, () => removeAppRule(${'$'}{idx}), 'Confirm Remove')" title="Remove rule" aria-label="Remove rule for ${'$'}{rule.package}">Remove</button></td>`;
                tbody.appendChild(tr);
            });

            if (filter && matchCount === 0) {
                const tr = document.createElement('tr');
                tr.innerHTML = '<td colspan="5" style="text-align:center; padding:20px; color:#666;">No rules match your filter. <button onclick="document.getElementById(\'appFilter\').value=\'\'; renderAppTable()" style="margin-left:10px; padding:4px 8px; font-size:0.85em;">Clear Filter</button></td>';
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
            const pMicrophone = document.getElementById('permMicrophone').checked;
            if (!pkg) { notify('Package required', 'error'); pkgInput.focus(); return; }
            const pkgRegex = /^[a-zA-Z0-9_.*]+$/;
            if (!pkgRegex.test(pkg)) { notify('Invalid package', 'error'); pkgInput.focus(); return; }
            const permissions = [];
            if (pContacts) permissions.push('CONTACTS');
            if (pMedia) permissions.push('MEDIA');
            if (pMicrophone) permissions.push('MICROPHONE');

            const existingIdx = appRules.findIndex(r => r.package === pkg);
            if (existingIdx !== -1) {
                appRules[existingIdx] = { package: pkg, template: tmpl === 'null' ? '' : tmpl, keybox: kb, permissions: permissions };
            } else {
                appRules.push({ package: pkg, template: tmpl === 'null' ? '' : tmpl, keybox: kb, permissions: permissions });
            }

            renderAppTable(); pkgInput.value = ''; document.getElementById('appKeybox').value = ''; if(document.getElementById('clearPkgBtn')) document.getElementById('clearPkgBtn').style.display='none'; if(document.getElementById('clearKbBtn')) document.getElementById('clearKbBtn').style.display='none';
            document.getElementById('permContacts').checked = false; document.getElementById('permMedia').checked = false; document.getElementById('permMicrophone').checked = false;
            toggleAddButton(); pkgInput.focus();
            notify(existingIdx !== -1 ? 'Rule Updated' : 'Rule Added');
            const btn = document.getElementById('btnAddRule');
            if (btn) btn.innerText = 'Add Rule';
        }

        function editAppRule(idx) {
            const rule = appRules[idx];
            document.getElementById('appPkg').value = rule.package;
            const tmplSel = document.getElementById('appTemplate');
            tmplSel.value = rule.template || 'null';
            if (!tmplSel.value) tmplSel.value = 'null';
            document.getElementById('appKeybox').value = rule.keybox || '';
            document.getElementById('permContacts').checked = rule.permissions.includes('CONTACTS');
            document.getElementById('permMedia').checked = rule.permissions.includes('MEDIA');
            document.getElementById('permMicrophone').checked = rule.permissions.includes('MICROPHONE');
            document.getElementById('appPkg').focus();
            toggleAddButton();
            document.getElementById('clearPkgBtn').style.display = 'block';
            document.getElementById('clearKbBtn').style.display = rule.keybox ? 'block' : 'none';
            const btn = document.getElementById('btnAddRule');
            if (btn) btn.innerText = 'Update Rule';
        }

        function removeAppRule(idx) {
            appRules.splice(idx, 1); renderAppTable();
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

        async function applyProfile(profileName) {
            if (!profileName) return;
            try {
                const formData = new URLSearchParams();
                formData.append('profile', profileName);
                const res = await fetchAuth('/api/apply_profile', { method: 'POST', body: formData });
                if (res.ok) {
                    notify(`Profile ${"$"}{profileName} Applied`);
                    setTimeout(() => window.location.reload(), 1000);
                } else {
                    notify('Failed to apply profile', 'error');
                }
            } catch (e) {
                notify('Error', 'error');
            }
        }

        function determineActiveProfile(data) {
            const isGod = data.global_mode && data.rkp_bypass && !data.tee_broken_mode && data.random_on_boot && data.hide_sensitive_props && data.drm_fix && data.auto_patch_update && data.spoof_location;
            const isDaily = !data.global_mode && data.rkp_bypass && !data.tee_broken_mode && !data.random_on_boot && data.hide_sensitive_props && !data.drm_fix && data.auto_patch_update;
            const isMinimal = !data.global_mode && data.rkp_bypass && !data.tee_broken_mode && !data.random_on_boot && !data.hide_sensitive_props && !data.drm_fix && !data.auto_patch_update && !data.spoof_location;

            const select = document.getElementById('profileSelect');
            if (!select) return;
            if (isGod) select.value = 'GodProfile';
            else if (isDaily) select.value = 'DailyUse';
            else if (isMinimal) select.value = 'Minimal';
            else select.value = '';
        }

        async function reloadConfig() {
            await fetchAuth(getAuthUrl('/api/reload'), { method: 'POST' }); notify('Reloaded'); setTimeout(() => window.location.reload(), 1000);
        }
        async function resetEnvironment() {
            notify('Resetting...', 'working');
            try {
                const res = await fetchAuth('/api/reset_environment', { method: 'POST' });
                if (res.ok) {
                    notify('Environment Reset - New identity generated');
                    setTimeout(() => window.location.reload(), 1000);
                } else {
                    const txt = await res.text();
                    notify('Reset Failed: ' + txt, 'error');
                }
            } catch(e) { notify('Error', 'error'); }
        }
        async function applyLocationSpoof() {
            const lat = document.getElementById('inputLatitude').value.trim();
            const lng = document.getElementById('inputLongitude').value.trim();
            const alt = document.getElementById('inputAltitude').value.trim() || '0';
            const acc = document.getElementById('inputAccuracy').value.trim() || '1.0';
            if (!lat || !lng) { notify('Latitude and Longitude are required', 'error'); return; }
            const latNum = parseFloat(lat);
            const lngNum = parseFloat(lng);
            if (isNaN(latNum) || latNum < -90 || latNum > 90) { notify('Invalid Latitude', 'error'); return; }
            if (isNaN(lngNum) || lngNum < -180 || lngNum > 180) { notify('Invalid Longitude', 'error'); return; }
            const altNum = parseFloat(alt);
            if (isNaN(altNum)) { notify('Invalid Altitude (must be numeric)', 'error'); return; }
            const accNum = parseFloat(acc);
            if (isNaN(accNum) || accNum <= 0) { notify('Invalid Accuracy (must be a positive number)', 'error'); return; }
            const randomEnabled = document.getElementById('chkLocationRandom').checked;
            const radius = document.getElementById('inputLocationRadius').value.trim() || '500';
            const interval = document.getElementById('inputLocationInterval').value.trim() || '30';
            const radiusNum = parseInt(radius, 10);
            const intervalNum = parseInt(interval, 10);
            if (randomEnabled && (isNaN(radiusNum) || radiusNum < 1 || radiusNum > 100000)) { notify('Radius must be 1-100000 meters', 'error'); return; }
            if (randomEnabled && (isNaN(intervalNum) || intervalNum < 5 || intervalNum > 86400)) { notify('Interval must be 5-86400 seconds', 'error'); return; }
            try {
                let content = '';
                try {
                    const res = await fetchAuth('/api/file?filename=spoof_build_vars');
                    if (res.ok) content = await res.text();
                } catch(e) {}
                const locationKeys = {
                    'SPOOF_LATITUDE': lat,
                    'SPOOF_LONGITUDE': lng,
                    'SPOOF_ALTITUDE': alt,
                    'SPOOF_ACCURACY': acc,
                    'SPOOF_LOCATION_RANDOM': randomEnabled ? 'true' : 'false',
                    'SPOOF_LOCATION_RADIUS': radius,
                    'SPOOF_LOCATION_INTERVAL': interval
                };
                let lines = content.split('\n');
                const updatedLines = [];
                const processedKeys = new Set();
                for (let line of lines) {
                    if (line.trim().startsWith('#') || !line.includes('=')) { updatedLines.push(line); continue; }
                    const key = line.split('=')[0].trim();
                    if (locationKeys.hasOwnProperty(key)) { updatedLines.push(key + '=' + locationKeys[key]); processedKeys.add(key); }
                    else { updatedLines.push(line); }
                }
                for (const [key, val] of Object.entries(locationKeys)) {
                    if (!processedKeys.has(key)) updatedLines.push(key + '=' + val);
                }
                const saveRes = await fetchAuth('/api/save', {
                    method: 'POST',
                    body: new URLSearchParams({ filename: 'spoof_build_vars', content: updatedLines.join('\n') + '\n' })
                });
                if (saveRes.ok) notify('Location Settings Saved');
                else notify('Save Failed', 'error');
            } catch(e) { notify('Error: ' + e.message, 'error'); }
        }
        async function backupConfig() {
            const pw = document.getElementById('backupPw') ? document.getElementById('backupPw').value : '';
            if (pw) {
                notify('Creating encrypted backup...', 'working');
                try {
                    const formData = new FormData(); formData.append('pw', pw);
                    const res = await fetchAuth(getAuthUrl('/api/backup'), { method: 'POST', body: formData });
                    if (res.ok) {
                        const blob = await res.blob();
                        const url = URL.createObjectURL(blob);
                        const a = document.createElement('a'); a.href = url; a.download = 'cleverestricky_backup.ctsb'; a.click();
                        URL.revokeObjectURL(url); notify('Encrypted backup saved');
                    } else { notify('Backup failed', 'error'); }
                } catch(e) { notify('Error: ' + e.message, 'error'); }
            } else {
                window.location.href = getAuthUrl('/api/backup') + '?token=' + token;
            }
        }
        async function restoreConfig(input) {
            if (input.files && input.files[0]) {
                const file = input.files[0];
                const isEncrypted = file.name.endsWith('.ctsb');
                const pw = document.getElementById('backupPw') ? document.getElementById('backupPw').value : '';
                if (isEncrypted && !pw) { notify('Enter password in the field above before importing an encrypted backup', 'error'); input.value = ''; return; }
                const formData = new FormData(); formData.append('file', file);
                if (pw) formData.append('pw', pw);
                notify('Restoring...', 'working');
                try {
                    const res = await fetchAuth(getAuthUrl('/api/restore'), { method: 'POST', body: formData });
                    if (res.ok) { notify('Success'); setTimeout(() => window.location.reload(), 1000); } else notify('Failed: ' + await res.text(), 'error');
                } catch (e) { notify('Error', 'error'); }
                input.value = '';
            }
        }

        async function loadFile() {
            const f = document.getElementById('fileSelector').value;
            const editor = document.getElementById('fileEditor');
            if (currentFile && editor.value !== originalContent) {
                if (!editorUnsavedBypass) {
                    notify('You have unsaved changes. Select file again to discard.', 'error');
                    editorUnsavedBypass = true;
                    document.getElementById('fileSelector').value = currentFile;
                    return;
                }
                editor.value = originalContent;
                updateSaveButtonState();
            }
            editorUnsavedBypass = false;
            currentFile = f;
            console.log('[CleveresTricky] loadFile: loading', f);
            try {
                const res = await fetchAuth('/api/file?filename=' + f);
                if(res.ok) {
                    originalContent = await res.text();
                    editor.value = originalContent;
                    console.log('[CleveresTricky] loadFile:', f, 'loaded (' + originalContent.length + ' bytes)');
                    updateSaveButtonState();
                } else {
                    console.log('[CleveresTricky] loadFile:', f, 'failed (status=' + res.status + ')');
                }
            } catch(e){
                console.log('[CleveresTricky] loadFile:', f, 'error -', e.message);
            }
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
                     editorUnsavedBypass = false;
                     updateSaveButtonState();
                 } else { notify('Save Failed: ' + txt, 'error'); }
             } finally { btn.disabled = false; updateSaveButtonState(); }
        }
        function updateSaveButtonState() {
            const editor = document.getElementById('fileEditor');
            const btn = document.getElementById('saveBtn');
            const revertBtn = document.getElementById('revertBtn');
            if (currentFile && editor.value !== originalContent) {
                btn.innerText = 'Save *';
                btn.classList.add('primary');
                if (revertBtn) revertBtn.style.display = 'inline-block';
                editorUnsavedBypass = false;
            } else {
                btn.innerText = 'Save';
                btn.classList.remove('primary');
                if (revertBtn) revertBtn.style.display = 'none';
            }
        }
        function revertEditor() {
            const editor = document.getElementById('fileEditor');
            if (originalContent !== undefined) {
                editor.value = originalContent;
                updateSaveButtonState();
                notify('Changes reverted');
            }
        }



        let translations = {};
        async function loadLanguage() {
            console.log('[CleveresTricky] loadLanguage: fetching /api/language...');
            try {
                const res = await fetchAuth('/api/language');
                if (res.ok) {
                    translations = await res.json();
                    console.log('[CleveresTricky] loadLanguage: loaded', Object.keys(translations).length, 'keys');
                    applyTranslations();
                    notify('Language Loaded');
                } else {
                    console.log('[CleveresTricky] loadLanguage: no language file (status=' + res.status + ')');
                }
            } catch(e) {
                console.log('[CleveresTricky] loadLanguage: failed -', e.message);
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
            if(translations['tab_donate']) document.getElementById('tab_donate').innerText = translations['tab_donate'];
        }

        async function loadResourceUsage() {
             try {
                 const tbody = document.getElementById('resourceBody');
                 if(tbody) tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; padding:20px; color:#888;">Loading...</td></tr>';
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
                    statusHtml = '<input type="checkbox" class="toggle" id="res_toggle_' + f.id + '" ' + isChecked + ' onchange="toggle(\'' + f.id + '\')">';
                } else {
                    statusHtml = '<span style="color:#888;">Info Only</span>';
                }

                let secColor = f.sec === 'Critical' ? 'var(--danger)' : (f.sec === 'High' ? 'orange' : 'var(--success)');

                // Single row layout for responsive design
                tr.innerHTML =
                    '<td data-label="' + t('col_feature', 'Feature') + '"><div>' + f.name + '</div><div class="res-desc">' + f.desc + '</div></td>' +
                    '<td data-label="' + t('col_status', 'Status') + '">' + statusHtml + '</td>' +
                    '<td data-label="' + t('col_ram', 'Est. RAM') + '" style="font-family:monospace;">' + f.ram + '</td>' +
                    '<td data-label="' + t('col_cpu', 'Est. CPU') + '">' + f.cpu + '</td>' +
                    '<td data-label="' + t('col_security', 'Security Impact') + '" style="color:' + secColor + '; font-weight:bold;">' + f.sec + '</td>';

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
                "tab_editor": "Editor",
                "tab_donate": "Donate"
            };
            const blob = new Blob([JSON.stringify(template, null, 2)], {type: "application/json"});
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = "lang.json";
            a.click();
        }

        ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
            window.addEventListener(eventName, preventDefaults, false);
        });

        const dropZone = document.getElementById('dropZone');
        if (dropZone) {
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

        window.addEventListener('beforeunload', function (e) {
            const editor = document.getElementById('fileEditor');
            if (currentFile && editor && editor.value !== originalContent) {
                e.preventDefault();
                e.returnValue = '';
            }
        });

        loadLanguage();
        init();

        const isRelease = !("${BuildConfig.DEBUG}" === "true");
        if (!isRelease) {
            const devFooter = document.createElement("div");
            devFooter.style.textAlign = "center";
            devFooter.style.marginTop = "30px";
            devFooter.style.padding = "15px";
            devFooter.style.backgroundColor = "var(--panel-bg)";
            devFooter.style.borderRadius = "var(--radius)";
            devFooter.style.border = "1px solid var(--accent)";
            devFooter.innerHTML = `<span style="color:var(--accent); font-weight:bold;">BETA / DEV BUILD</span><br><br>This module is currently a development build. For the stable version, please download the <a href="https://github.com/tryigit/CleveresTricky/releases" style="color:var(--accent);" target="_blank">Stable Build (GitHub Releases)</a>.`;
            document.body.appendChild(devFooter);
        }
    </script>
</body>
</html>
        """.trimIndent()
    }

    companion object {
        fun isSafeHost(host: String?): Boolean {
            if (host == null) return false
            val h = host.substringBefore(":").lowercase()
            return h == "localhost" || h == "127.0.0.1" || h == "[::1]"
        }

        fun isSafePath(configDir: File, file: File): Boolean {
            return try {
                val configCanonical = configDir.canonicalPath
                val fileCanonical = file.canonicalPath
                fileCanonical.equals(configCanonical) || fileCanonical.startsWith(configCanonical + File.separator)
            } catch (e: Exception) {
                false
            }
        }

        fun isValidFilename(name: String): Boolean {
            return name.matches(FILENAME_REGEX) && !name.contains("..") && !name.contains("/") && !name.contains("\\")
        }

        fun validateContent(filename: String, content: String): Boolean {
            // Basic validation based on known file types
            if (filename == "target.txt") {
                return content.lineSequence().all { it.isEmpty() || it.startsWith("#") || it.matches(TARGET_PKG_REGEX) }
            }
            if (filename == "security_patch.txt") {
                 return content.lineSequence().all { it.isEmpty() || it.matches(SECURITY_PATCH_REGEX) }
            }
            if (filename == "spoof_build_vars") {
                return content.lineSequence().all { line ->
                    if (line.isEmpty() || line.startsWith("#")) return@all true
                    // Must be KEY=VALUE format
                    if (!line.matches(KEY_VALUE_REGEX)) return@all false
                    // Value part security check
                    val eqIdx = line.indexOf('=')
                    if (eqIdx == -1) return@all false
                    val value = line.substring(eqIdx + 1)
                    // Check for unsafe shell chars
                    value.matches(SAFE_BUILD_VAR_VALUE_REGEX)
                }
            }
            if (filename == "app_config") {
                return content.lineSequence().all { line ->
                     if (line.isBlank() || line.startsWith("#")) return@all true

                     val trimmed = line.trim()
                     if (trimmed.isEmpty()) return@all true

                     val len = trimmed.length
                     var idx = 0

                     var start = idx
                     while (idx < len && !trimmed[idx].isWhitespace()) idx++
                     val pkg = trimmed.substring(start, idx)

                     if (!pkg.matches(PKG_NAME_REGEX)) return@all false

                     while (idx < len && trimmed[idx].isWhitespace()) idx++
                     if (idx < len) {
                         start = idx
                         while (idx < len && !trimmed[idx].isWhitespace()) idx++
                         val tmplStr = trimmed.substring(start, idx)
                         if (tmplStr != "null" && !tmplStr.matches(TEMPLATE_NAME_REGEX)) return@all false

                         while (idx < len && trimmed[idx].isWhitespace()) idx++
                         if (idx < len) {
                             start = idx
                             while (idx < len && !trimmed[idx].isWhitespace()) idx++
                             val kbStr = trimmed.substring(start, idx)
                             if (kbStr != "null" && !kbStr.matches(KEYBOX_FILENAME_REGEX)) return@all false

                             while (idx < len && trimmed[idx].isWhitespace()) idx++
                             if (idx < len) {
                                 start = idx
                                 while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                 val permStr = trimmed.substring(start, idx)
                                 if (permStr != "null" && !permStr.matches(PERMISSIONS_REGEX)) return@all false
                             }
                         }
                     }

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
                listOf("target.txt", "security_patch.txt", "spoof_build_vars", "app_config", "drm_fix", "global_mode", "tee_broken_mode", "rkp_bypass", "templates.json", "custom_templates", "spoof_location", "imei_global", "network_global").forEach { name ->
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
                    if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                        throw SecurityException("Zip entry contains path traversal: $name")
                    }
                    val file = File(configDir, name)
                    if (file.canonicalPath.equals(configDir.canonicalPath) || file.canonicalPath.startsWith(configDir.canonicalPath + File.separator)) {
                        if (name.startsWith("keyboxes/")) {
                            File(configDir, "keyboxes").mkdirs()
                        }
                        if (!entry.isDirectory) {
                            SecureFile.writeStream(file, zis, 50 * 1024 * 1024)
                        }
                    } else {
                        throw SecurityException("Zip entry path traversal detected via canonical path: $name")
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }
}
