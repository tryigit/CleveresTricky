package cleveres.tricky.cleverestech

import android.system.Os
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.BackupEncryptor
import cleveres.tricky.cleverestech.util.CboxDecryptor
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import cleveres.tricky.cleverestech.util.RandomUtils
import cleveres.tricky.cleverestech.util.SecureFile
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private fun isValidPkgName(s: String): Boolean {
    if (s.length !in 1..255) return false
    for (i in 0 until s.length) {
        val c = s[i]
        if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '*')) return false
    }
    return true
}

private fun isValidTemplateName(s: String): Boolean {
    if (s.length !in 1..64) return false
    for (i in 0 until s.length) {
        val c = s[i]
        if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-')) return false
    }
    return true
}

private fun isValidKeyboxFilename(s: String): Boolean {
    if (s.length !in 5..128 || s.startsWith('.')) return false
    for (i in 0 until s.length) {
        val c = s[i]
        if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '-')) return false
    }
    val lower = s.lowercase()
    return lower.endsWith(".xml") || lower.endsWith(".cbox")
}

private fun isValidKeyValue(s: String): Boolean {
    if (s.isEmpty()) return false
    val eqIdx = s.indexOf('=')
    if (eqIdx <= 0 || eqIdx == s.length - 1) return false
    for (i in 0 until eqIdx) {
        val c = s[i]
        if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.')) return false
    }
    return true
}

private fun isValidSafeBuildVarValue(s: String): Boolean {
    for (i in 0 until s.length) {
        val c = s[i]
        val isAllowed =
            c.isLetterOrDigit() ||
                c == '_' || c == '-' || c == '.' || c.isWhitespace() ||
                c == '/' || c == ':' || c == ',' || c == '+' ||
                c == '=' || c == '(' || c == ')' || c == '@'
        if (!isAllowed) return false
    }
    return true
}

private fun isValidTargetPkg(s: String): Boolean {
    if (s.length !in 1..255) return false
    for (i in 0 until s.length) {
        val c = s[i]
        if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '*' || c == '!')) return false
    }
    return true
}

private fun isValidSecurityPatchValue(
    value: String,
    allowSpecial: Boolean,
): Boolean {
    if (value.equals("today", ignoreCase = true)) return true
    val isSpecial =
        value.equals("no", ignoreCase = true) ||
            value.equals("device_default", ignoreCase = true) ||
            value.equals("prop", ignoreCase = true)
    if (allowSpecial && isSpecial) return true
    if (value.any { it == 'Y' || it == 'M' || it == 'D' }) {
        val sample = value.replace("YYYY", "2024").replace("MM", "06").replace("DD", "15")
        return runCatching { sample.convertPatchLevel(false) }.isSuccess
    }
    return runCatching { value.convertPatchLevel(false) }.isSuccess
}

private fun isValidFilename(s: String): Boolean {
    if (s.isEmpty()) return false
    for (i in 0 until s.length) {
        val c = s[i]
        if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-')) return false
    }
    return true
}

private const val WEB_UI_READINESS_TIMEOUT_MS = 15_000L
private const val WEB_UI_READINESS_POLL_MS = 100L
private const val WEB_UI_READINESS_CONNECT_TIMEOUT_MS = 250

class WebServer(
    private val requestedPort: Int,
    private val configDir: File,
    private val isTampered: Boolean = false,
    private val crlFetcher: () -> Set<String>? = { KeyboxVerifier.fetchCrl() },
    private val permissionSetter: (File, Int) -> Unit = { f, m ->
        try {
            Os.chmod(f.absolutePath, m)
        } catch (t: Throwable) {
            Logger.e("failed to set permissions for ${f.name}", t)
        }
    },
) : NanoHTTPD(WEB_UI_LOOPBACK_HOST, requestedPort) {
    suspend fun startAsync(
        timeout: Int = 5000,
        daemon: Boolean = true,
    ) {
        Logger.d("WebServer: Starting on $WEB_UI_LOOPBACK_HOST:$requestedPort (timeout=$timeout daemon=$daemon)")
        try {
            super.start(timeout, daemon)
            Logger.d("WebServer: NanoHTTPD start returned (alive=$isAlive port=$listeningPort)")
            waitUntilListeningAsync()
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
    private suspend fun waitUntilListeningAsync(
        timeoutMs: Long = WEB_UI_READINESS_TIMEOUT_MS,
        pollMs: Long = WEB_UI_READINESS_POLL_MS,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var port = listeningPort
        while (port <= 0 && System.nanoTime() < deadline) {
            delay(pollMs)
            port = listeningPort
        }
        if (port <= 0) {
            throw IOException("WebServer: Invalid listening port $port after waiting ${timeoutMs}ms")
        }
        var lastError: IOException? = null
        while (System.nanoTime() < deadline) {
            try {
                withContext(Dispatchers.IO) {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(WEB_UI_LOOPBACK_HOST, port), WEB_UI_READINESS_CONNECT_TIMEOUT_MS)
                    }
                }
                return
            } catch (e: IOException) {
                lastError = e
                Logger.d("WebServer: Waiting for $WEB_UI_LOOPBACK_HOST:$port to accept connections (${e.message})")
                delay(pollMs)
            }
        }
        throw IOException("WebServer: Timed out waiting for $WEB_UI_LOOPBACK_HOST:$port to accept connections", lastError)
    }

    init {
        cleveres.tricky.cleverestech.util.LoggerConfig.disableNanoHttpdLogging()
    }

    val token: String by lazy {
        val tokenFile = File(configDir, "web_token.txt")
        val existing =
            if (
                Files.isRegularFile(tokenFile.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                tokenFile.length() in 32..256
            ) {
                tokenFile.readText().trim()
            } else {
                ""
            }
        if (existing.length in 32..128 && existing.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            existing
        } else {
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            val newToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
            randomBytes.fill(0)
            SecureFile.writeText(tokenFile, newToken)
            newToken
        }
    }

    private class RateLimitEntry(var timestamp: Long, var count: Int)

    private val requestCounts = java.util.concurrent.ConcurrentHashMap<String, RateLimitEntry>()

    private val fileLock = Any()

    @Suppress("DEPRECATION")
    private fun getParam(
        session: IHTTPSession,
        name: String,
    ): String? {
        return session.parms[name]
    }

    private fun isRateLimited(ip: String): Boolean {
        val now = System.currentTimeMillis()
        if (requestCounts.size > 1000) {
            requestCounts.entries.removeIf { now - it.value.timestamp > RATE_WINDOW }
            if (requestCounts.size > 1000) requestCounts.clear() // Fallback
        }
        val current =
            requestCounts.compute(ip) { _, v ->
                if (v == null || now - v.timestamp > RATE_WINDOW) {
                    RateLimitEntry(now, 1)
                } else {
                    v.count++
                    v
                }
            }
        return current!!.count > RATE_LIMIT
    }

    private fun readFile(filename: String): String {
        synchronized(fileLock) {
            return try {
                val f = getSafeFile(configDir, filename)
                if (f == null) {
                    Logger.e("Path traversal attempt detected: $filename")
                    return ""
                }
                if (
                    !Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    f.length() > MAX_CONFIG_FILE_SIZE
                ) {
                    Logger.e("Refusing oversized or non-regular config file: $filename")
                    return ""
                }
                f.readText()
            } catch (e: Exception) {
                ""
            }
        }
    }

    private fun saveFile(
        filename: String,
        content: String,
    ): Boolean {
        synchronized(fileLock) {
            return try {
                val f = getSafeFile(configDir, filename)
                if (f == null) {
                    Logger.e("Path traversal attempt detected during save: $filename")
                    return false
                }
                if (Files.isSymbolicLink(f.toPath())) {
                    Logger.e("Refusing symbolic-link config destination: $filename")
                    return false
                }
                if (content.toByteArray(Charsets.UTF_8).size > MAX_CONFIG_FILE_SIZE) return false
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
            val f = getSafeFile(configDir, filename)
            return f != null && Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)
        }
    }

    private fun listKeyboxes(): List<String> {
        synchronized(fileLock) {
            val keyboxDir = File(configDir, "keyboxes")
            if (Files.isDirectory(keyboxDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return keyboxDir.listFiles { file ->
                    (file.name.endsWith(".xml", ignoreCase = true) || file.name.endsWith(".cbox", ignoreCase = true)) &&
                        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                }
                    ?.map { it.name }
                    ?.sorted()
                    ?: emptyList()
            } else {
                return emptyList()
            }
        }
    }

    private enum class KeyboxUploadValidation {
        VALID,
        INVALID,
        REVOCATION_UNAVAILABLE,
    }

    private fun validateUploadedKeyboxXml(
        content: String,
        filename: String,
    ): KeyboxUploadValidation {
        return try {
            val keyboxes = CertHack.parseKeyboxXml(StringReader(content), filename)
            if (keyboxes.isEmpty()) return KeyboxUploadValidation.INVALID
            val revoked = crlFetcher() ?: return KeyboxUploadValidation.REVOCATION_UNAVAILABLE
            if (keyboxes.all { KeyboxVerifier.verifyKeybox(it, revoked) == KeyboxVerifier.Status.VALID }) {
                KeyboxUploadValidation.VALID
            } else {
                KeyboxUploadValidation.INVALID
            }
        } catch (error: Exception) {
            KeyboxUploadValidation.INVALID
        }
    }

    private fun keyboxValidationError(validation: KeyboxUploadValidation): Response? =
        when (validation) {
            KeyboxUploadValidation.VALID -> null
            KeyboxUploadValidation.INVALID ->
                secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Revoked or invalid keybox")
            KeyboxUploadValidation.REVOCATION_UNAVAILABLE ->
                secureResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    "text/plain",
                    "Revocation service unavailable; keybox was not saved",
                )
        }

    private fun getModuleDir(): File {
        val paths =
            listOf(
                "/data/adb/modules/cleverestricky",
                "/data/adb/ksu/modules/cleverestricky",
                "/data/adb/ap/modules/cleverestricky",
            )
        for (p in paths) {
            val f = File(p)
            if (f.exists() && f.isDirectory) return f
        }
        return File("/data/adb/modules/cleverestricky")
    }

    private fun readTextLimited(
        input: InputStream,
        maxBytes: Int,
    ): String {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count > maxBytes - total) throw IOException("Command output exceeds limit")
            output.write(buffer, 0, count)
            total += count
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun isValidSetting(name: String): Boolean {
        return name in WEB_UI_SETTINGS
    }

    private fun isValidProfile(name: String): Boolean = name.lowercase() in setOf("maximum", "daily", "minimal", "default")

    private fun toggleFile(
        filename: String,
        enable: Boolean,
    ): Boolean {
        if (!isValidSetting(filename)) return false
        synchronized(fileLock) {
            val f = getSafeFile(configDir, filename)
            if (f == null) return false
            return try {
                val path = f.toPath()
                if (Files.isSymbolicLink(path)) return false
                if (enable) {
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
                    } else {
                        SecureFile.touch(f, 384)
                    }
                } else {
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        return false
                    }
                    Files.deleteIfExists(path)
                }
                true
            } catch (e: Exception) {
                Logger.e("Failed to toggle setting: $filename", e)
                false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getEnvironmentInfo(): String {
        if (File("/data/adb/ksu").exists() || File("/data/adb/ksud").exists()) return "KernelSU"
        if (File("/data/adb/apatch").exists()) return "APatch"
        if (File("/sbin/magisk").exists() || File("/data/adb/magisk").exists()) return "Unsupported (Magisk)"
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
                            buffer[pos + 1] == 'm'.code.toByte() &&
                            buffer[pos + 2] == 'R'.code.toByte() &&
                            buffer[pos + 3] == 'S'.code.toByte() &&
                            buffer[pos + 4] == 'S'.code.toByte() &&
                            buffer[pos + 5] == ':'.code.toByte()
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
        } catch (e: Exception) {
        }
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            serveInternal(session)
        } catch (e: Exception) {
            Logger.e("WebServer: Error handling request", e)
            secureResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Internal Server Error")
        }
    }

    private fun serveInternal(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val headers = session.headers

        // Security Firewall: Prevent resource exhaustion DoS by limiting payload size (max 5MB)
        val contentLengthStr = headers["content-length"] ?: headers["Content-Length"]
        if (contentLengthStr != null) {
            val contentLength =
                contentLengthStr.toLongOrNull()
                    ?: return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid Content-Length")
            if (contentLength < 0) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid Content-Length")
            }
            if (contentLength > MAX_UPLOAD_SIZE) {
                Logger.e("WebServer: Request too large, blocking to prevent resource exhaustion (Firewall)")
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Payload Too Large")
            }
        }

        if (!isSafeHost(headers["host"])) return secureResponse(Response.Status.FORBIDDEN, "text/plain", "Invalid Host header")

        var ip = session.remoteIpAddress ?: "unknown"
        if (ip.startsWith("/")) ip = ip.substring(1)
        if (isRateLimited(ip)) return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Too Many Requests")

        val origin = headers["origin"]
        val host = headers["host"]
        if (origin != null && host != null) {
            val allowedOrigin = "http://$host"
            val allowedSecureOrigin = "https://$host"
            if (origin != allowedOrigin && origin != allowedSecureOrigin) {
                return secureResponse(
                    Response.Status.FORBIDDEN,
                    "text/plain",
                    "CSRF Forbidden",
                )
            }
        }

        if (uri == "/" || uri == "/index.html") {
            if (isTampered) {
                val warningHtml =
                    """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Tamper Warning</title></head>
                    <body style="font-family: sans-serif; padding: 20px; background: #fff3f3; color: #d00;">
                        <h1>Module Modified / Tampering Detected</h1>
                        <p>This module has been modified and is therefore dangerous. Please use the official version from GitHub:</p>
                        <p><a href="https://github.com/tryigit/CleveresTricky/">https://github.com/tryigit/CleveresTricky/</a></p>
                    </body>
                    </html>
                    """.trimIndent()
                return secureResponse(Response.Status.FORBIDDEN, "text/html", warningHtml.toByteArray())
            }
            return secureResponse(Response.Status.OK, "text/html", htmlBytes)
        }

        if (method == Method.POST || method == Method.PUT) {
            val lenStr = headers["content-length"]
            if (lenStr != null) {
                try {
                    val contentLen = lenStr.toLong()
                    val contentType = headers["content-type"] ?: ""
                    val isMultipart = contentType.contains("multipart/form-data", ignoreCase = true)
                    val maxSize = if (isMultipart) MAX_UPLOAD_SIZE else MAX_BODY_SIZE
                    if (contentLen > maxSize) return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Payload too large")
                } catch (e: NumberFormatException) {
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid Content-Length")
                }
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

        if (
            authToken == null ||
            authToken.length != token.length ||
            !MessageDigest.isEqual(
                token.toByteArray(Charsets.US_ASCII),
                authToken.toByteArray(Charsets.US_ASCII),
            )
        ) {
            return secureResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized")
        }

        if (uri == "/api/config" && method == Method.GET) {
            val json = JSONObject()
            WEB_UI_SETTINGS.forEach { setting -> json.put(setting, fileExists(setting)) }
            val files = JSONArray()
            files.put("keybox.xml")
            files.put("target.txt")
            files.put("security_patch.txt")
            files.put("spoof_build_vars")
            files.put("app_config")
            files.put("templates.json")
            files.put("drm_packages.txt")
            files.put("boot_props_mode")
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
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val filename = getParam(session, "filename")
            val password = getParam(session, "password")
            val pubKey = getParam(session, "public_key")

            if (filename != null && password != null) {
                if (CboxManager.unlock(filename, password, pubKey)) {
                    Config.updateKeyBoxesSync(crlFetcher())
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
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val jsonStr = getParam(session, "data")
            if (jsonStr != null) {
                try {
                    val obj = JSONObject(jsonStr)
                    val server =
                        ServerManager.ServerConfig(
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
                            contentPublicKey = obj.optString("contentPublicKey").ifEmpty { null },
                        )
                    ServerManager.addServer(server)
                    Config.updateKeyBoxes()
                    return secureResponse(Response.Status.OK, "text/plain", "Saved")
                } catch (e: Exception) {
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid JSON")
                }
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing data")
        }

        if (uri == "/api/server/delete" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val id = getParam(session, "id")
            if (id != null) {
                if (ServerManager.removeServer(id)) {
                    Config.updateKeyBoxesSync(crlFetcher())
                    return secureResponse(Response.Status.OK, "text/plain", "Deleted")
                }
                return secureResponse(Response.Status.NOT_FOUND, "text/plain", "Server not found")
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing id")
        }

        if (uri == "/api/server/refresh" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val id = getParam(session, "id")
            if (id != null) {
                val s = ServerManager.findServer(id)
                if (s != null) {
                    val refreshed = ServerManager.fetchFromServer(s)
                    Config.updateKeyBoxesSync(crlFetcher())
                    if (refreshed) {
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
                json.put("imei", RandomUtils.generateLuhn(15, "35"))
                json.put("imei2", RandomUtils.generateLuhn(15, "35"))
                json.put("serial", RandomUtils.generateRandomSerial(12))
                json.put("imsi", RandomUtils.generateDigits(15, "310260"))
                json.put("iccid", RandomUtils.generateLuhn(20, "8901"))
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
                if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                ) {
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid app configuration")
                }
                if (file.length() > MAX_CONFIG_FILE_SIZE) {
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "App configuration is too large")
                }
                if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    var ruleCount = 0
                    file.useLines { lines ->
                        lines.forEach { line ->
                            if (line.isNotBlank() && !line.startsWith("#")) {
                                if (++ruleCount > MAX_APP_CONFIG_RULES) {
                                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Too many app rules")
                                }
                                val trimmed = line.trim()
                                if (trimmed.isEmpty()) return@forEach

                                val len = trimmed.length
                                var idx = 0

                                var start = idx
                                while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                val pkg = trimmed.substring(start, idx)

                                var tmpl = ""
                                var kb = ""

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
                                    }
                                }

                                if (pkg.isNotEmpty()) {
                                    if (isValidPkg(pkg)) {
                                        val isTmplValid = tmpl.isEmpty() || isValidTemplate(tmpl)
                                        val isKbValid = kb.isEmpty() || isValidKeybox(kb)
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
            }
            return secureResponse(Response.Status.OK, "application/json", array.toString())
        }

        if (uri == "/api/app_config_structured" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val jsonStr = getParam(session, "data")
            if (jsonStr != null) {
                try {
                    val array = JSONArray(jsonStr)
                    if (array.length() > MAX_APP_CONFIG_RULES) {
                        return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Too many app rules")
                    }
                    val sb = StringBuilder()
                    val seenPackages = HashSet<String>()
                    sb.append("# Generated by WebUI\n")
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val pkg = obj.getString("package")
                        val tmpl = obj.optString("template", "null").ifEmpty { "null" }
                        val kb = obj.optString("keybox", "null").ifEmpty { "null" }
                        if (!isValidPkg(
                                pkg,
                            )
                        ) {
                            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid input: invalid characters")
                        }
                        if (tmpl != "null" &&
                            !isValidTemplate(
                                tmpl,
                            )
                        ) {
                            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid input")
                        }
                        if (kb != "null" &&
                            !isValidKeybox(
                                kb,
                            )
                        ) {
                            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid input")
                        }
                        if (pkg.any { it.isWhitespace() }) {
                            return secureResponse(
                                Response.Status.BAD_REQUEST,
                                "text/plain",
                                "Invalid input",
                            )
                        }
                        if (!seenPackages.add(pkg)) {
                            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Duplicate app rule")
                        }
                        sb.append("$pkg $tmpl $kb\n")
                        if (sb.length.toLong() > MAX_CONFIG_FILE_SIZE) {
                            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "App configuration is too large")
                        }
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
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing data")
        }

        if (uri == "/api/file" && method == Method.GET) {
            val filename = getParam(session, "filename")
            if (filename != null && filename in EDITABLE_CONFIG_FILES) {
                return secureResponse(Response.Status.OK, "text/plain", readFile(filename))
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid filename")
        }

        if (uri == "/api/save" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val filename = getParam(session, "filename")
            val content = getParam(session, "content")
            if (filename != null && filename in EDITABLE_CONFIG_FILES && content != null) {
                if (validateContent(filename, content)) {
                    if (saveFile(filename, content)) {
                        return secureResponse(Response.Status.OK, "text/plain", "Saved")
                    }
                } else {
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid content")
                }
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid request")
        }

        if (uri == "/api/upload_keybox" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val filename = getParam(session, "filename")
            val content = getParam(session, "content")
            val tmpFilePath = map["file"]
            if (tmpFilePath != null) {
                val originalName = getParam(session, "filename") ?: "upload.bin"
                val tmpFile = File(tmpFilePath)
                val extension = originalName.substringAfterLast('.', "").lowercase()
                if (!Files.isRegularFile(tmpFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    tmpFile.length() !in 1..MAX_UPLOAD_SIZE
                ) {
                    if (tmpFile.exists()) tmpFile.delete()
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid upload size")
                }
                if (!isValidKeyboxFilename(originalName) || (extension != "xml" && extension != "cbox")) {
                    tmpFile.delete()
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid upload filename")
                }
                val bytes = tmpFile.readBytes()
                try {
                    synchronized(fileLock) {
                        val keyboxDir = File(configDir, "keyboxes")
                        SecureFile.mkdirs(keyboxDir, 448)
                        val dest = getSafeFile(keyboxDir, originalName)
                        if (dest == null) {
                            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid upload path")
                        }
                        if (extension == "cbox") {
                            if (!CboxDecryptor.hasSupportedEnvelopeHeader(bytes)) {
                                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid CBOX envelope")
                            }
                            SecureFile.writeBytes(dest, bytes)
                            CboxManager.refresh()
                        } else {
                            val xml =
                                try {
                                    Charsets.UTF_8.newDecoder()
                                        .onMalformedInput(CodingErrorAction.REPORT)
                                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                                        .decode(ByteBuffer.wrap(bytes))
                                        .toString()
                                } catch (error: Exception) {
                                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Keybox XML is not valid UTF-8")
                                }
                            keyboxValidationError(validateUploadedKeyboxXml(xml, originalName))?.let { return it }
                            SecureFile.writeBytes(dest, bytes)
                        }
                        Config.updateKeyBoxesSync(crlFetcher())
                        val count = CertHack.getKeyboxCount()
                        return secureResponse(Response.Status.OK, "application/json", """{"status":"ok","keybox_count":$count}""")
                    }
                } finally {
                    bytes.fill(0)
                    if (tmpFile.exists() && !tmpFile.delete()) Logger.w("Failed to clean upload temp file")
                }
            }

            if (
                filename != null &&
                content != null &&
                filename.endsWith(".xml", ignoreCase = true) &&
                isValidKeyboxFilename(filename)
            ) {
                synchronized(fileLock) {
                    keyboxValidationError(validateUploadedKeyboxXml(content, filename))?.let { return it }
                    val keyboxDir = File(configDir, "keyboxes")
                    SecureFile.mkdirs(keyboxDir, 448)
                    val file = getSafeFile(keyboxDir, filename)
                    if (file == null) {
                        return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Path traversal attempt detected")
                    }
                    try {
                        SecureFile.writeText(file, content)
                        Config.updateKeyBoxesSync(crlFetcher())
                        val count = CertHack.getKeyboxCount()
                        return secureResponse(Response.Status.OK, "application/json", """{"status":"ok","keybox_count":$count}""")
                    } catch (e: Exception) {
                        Logger.e("Failed to save keybox", e)
                        return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to save keybox")
                    }
                }
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid request")
        }

        if (uri == "/api/delete_keybox" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val filename = getParam(session, "filename")
            if (filename != null && isValidKeyboxFilename(filename)) {
                synchronized(fileLock) {
                    val keyboxDir = File(configDir, "keyboxes")
                    val f = getSafeFile(keyboxDir, filename)
                    if (f != null && Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        if (f.delete()) {
                            if (filename.endsWith(".cbox", ignoreCase = true)) {
                                val cacheFile = File(keyboxDir, "$filename.cache")
                                if (Files.isRegularFile(cacheFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                                    Files.deleteIfExists(cacheFile.toPath())
                                }
                                CboxManager.refresh()
                            }
                            Config.updateKeyBoxesSync(crlFetcher())
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
                val crl = crlFetcher()
                synchronized(fileLock) {
                    val results = KeyboxVerifier.verify(configDir) { crl }
                    val json = createKeyboxVerificationJson(results)
                    return secureResponse(Response.Status.OK, "application/json", json)
                }
            } catch (e: Exception) {
                Logger.e("Failed to verify keyboxes", e)
                return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
            }
        }

        if (uri == "/api/apply_profile" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val profileName = getParam(session, "profile")
            if (profileName != null && isValidProfile(profileName)) {
                synchronized(fileLock) {
                    try {
                        Config.applyProfile(profileName)
                        return secureResponse(Response.Status.OK, "text/plain", "Profile Applied")
                    } catch (e: Exception) {
                        Logger.e("Failed to apply profile", e)
                        return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
                    }
                }
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing profile")
        }

        if (uri == "/api/toggle" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val setting = getParam(session, "setting")
            val value = getParam(session, "value")
            if (setting != null && value != null && value in setOf("true", "false")) {
                if (toggleFile(setting, value.toBooleanStrict())) {
                    return secureResponse(Response.Status.OK, "text/plain", "Toggled")
                }
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid setting")
        }

        if (uri == "/api/reset_environment" && method == Method.POST) {
            try {
                synchronized(fileLock) {
                    val spoofFile = File(configDir, "spoof_build_vars")
                    val replacements =
                        linkedMapOf(
                            "ATTESTATION_ID_IMEI" to RandomUtils.generateLuhn(15, "35"),
                            "ATTESTATION_ID_IMEI2" to RandomUtils.generateLuhn(15, "35"),
                            "ATTESTATION_ID_SERIAL" to RandomUtils.generateRandomSerial(12),
                            "ATTESTATION_ID_IMSI" to RandomUtils.generateDigits(15, "310260"),
                            "ATTESTATION_ID_ICCID" to RandomUtils.generateLuhn(20, "8901"),
                        )
                    val lines =
                        if (Files.isRegularFile(spoofFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            spoofFile.readLines().toMutableList()
                        } else {
                            mutableListOf()
                        }
                    val processed = mutableSetOf<String>()
                    for (index in lines.indices) {
                        val key = lines[index].substringBefore('=', "").trim()
                        replacements[key]?.let { value ->
                            lines[index] = "$key=$value"
                            processed += key
                        }
                    }
                    replacements.filterKeys { it !in processed }.forEach { (key, value) -> lines += "$key=$value" }
                    SecureFile.writeText(spoofFile, lines.joinToString("\n", postfix = "\n"))
                    Config.updateBuildVars(spoofFile)
                    val target = File(configDir, "target.txt")
                    if (Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        target.setLastModified(System.currentTimeMillis())
                    }
                    Config.updateKeyBoxesSync(crlFetcher())
                    return secureResponse(Response.Status.OK, "text/plain", "Environment Reset")
                }
            } catch (e: Exception) {
                Logger.e("Failed to reset environment", e)
                return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Environment reset failed")
            }
        }

        if (uri == "/api/reload" && method == Method.POST) {
            try {
                synchronized(fileLock) {
                    val target = File(configDir, "target.txt")
                    if (Files.isSymbolicLink(target.toPath())) {
                        return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid target file")
                    }
                    if (Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        target.setLastModified(System.currentTimeMillis())
                    }
                    return secureResponse(Response.Status.OK, "text/plain", "Reloaded")
                }
            } catch (e: Exception) {
                Logger.e("Failed to reload", e)
                return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
            }
        }

        if (uri == "/api/logs" && method == Method.GET) {
            return try {
                val type = session.parameters["type"]?.firstOrNull() ?: "cleverestricky"
                val cmd =
                    when (type) {
                        "errors" -> arrayOf("logcat", "-d", "-t", "1000", "*:E")
                        "system" -> arrayOf("logcat", "-d", "-t", "1000")
                        else -> arrayOf("logcat", "-d", "-t", "2000", "-s", "cleverestricky:V")
                    }
                val p = Runtime.getRuntime().exec(cmd)
                val logs =
                    try {
                        p.inputStream.use { readTextLimited(it, MAX_LOG_BYTES) }
                    } catch (e: Exception) {
                        ""
                    } finally {
                        p.errorStream.close()
                    }
                if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
                secureResponse(Response.Status.OK, "text/plain", logs.ifBlank { "No logs found." })
            } catch (e: Exception) {
                Logger.e("Failed to fetch logs", e)
                secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to fetch logs")
            }
        }

        if (uri == "/api/backup" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val password = getParam(session, "pw")
            if (password == null || password.length !in 12..1024) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Backup password must be 12-1024 characters")
            }
            return try {
                val zipBytes = synchronized(fileLock) { createBackupZip(configDir) }
                val encBytes =
                    try {
                        BackupEncryptor.encrypt(zipBytes, password)
                    } finally {
                        zipBytes.fill(0)
                    }
                val response =
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/octet-stream",
                        ByteArrayInputStream(encBytes),
                        encBytes.size.toLong(),
                    )
                response.addHeader("Content-Disposition", "attachment; filename=\"cleverestricky_backup.ctsb\"")
                addSecurityHeaders(response)
                response
            } catch (e: Exception) {
                Logger.e("Failed to create encrypted backup", e)
                secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Encrypted backup failed")
            }
        }

        if (uri == "/api/language" && method == Method.GET) {
            val langFile = File(configDir, "lang.json")
            if (Files.isRegularFile(langFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return secureResponse(Response.Status.OK, "application/json", readFile("lang.json"))
            } else {
                return secureResponse(Response.Status.NOT_FOUND, "application/json", "{}")
            }
        }

        if (uri == "/api/resource_usage" && method == Method.GET) {
            val json = JSONObject()
            val keyboxCount = CertHack.getKeyboxCount()
            json.put("keybox_count", keyboxCount)
            val appConfig = File(configDir, "app_config")
            val appConfigSize =
                if (Files.isRegularFile(appConfig.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    appConfig.length()
                } else {
                    0L
                }
            json.put("app_config_size", appConfigSize)
            WEB_UI_SETTINGS.forEach { setting -> json.put(setting, fileExists(setting)) }
            json.put("real_ram_kb", getRamUsageKb())
            json.put("real_cpu", getCpuUsagePercent())
            json.put("environment", getEnvironmentInfo())
            return secureResponse(Response.Status.OK, "application/json", json.toString())
        }

        if (uri == "/api/restore" && method == Method.POST) {
            val files = HashMap<String, String>()
            try {
                session.parseBody(files)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val tmpFilePath = files["file"]
            if (tmpFilePath != null) {
                val tmpFile = File(tmpFilePath)
                var uploadedBytes: ByteArray? = null
                return try {
                    if (
                        !Files.isRegularFile(tmpFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                        tmpFile.length() !in 1..MAX_UPLOAD_SIZE
                    ) {
                        return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid backup size")
                    }
                    val encryptedBytes = tmpFile.readBytes()
                    uploadedBytes = encryptedBytes
                    if (!BackupEncryptor.isEncryptedBackup(encryptedBytes)) {
                        return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Only encrypted .ctsb backups are accepted")
                    }
                    val pw = getParam(session, "pw")
                    if (pw == null || pw.length !in 12..1024) {
                        return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Valid backup password required")
                    }
                    val decrypted = BackupEncryptor.decrypt(encryptedBytes, pw)
                    try {
                        synchronized(fileLock) {
                            restoreBackupZip(configDir, ByteArrayInputStream(decrypted))
                            val target = File(configDir, "target.txt")
                            if (Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                                target.setLastModified(System.currentTimeMillis())
                            }
                            secureResponse(Response.Status.OK, "text/plain", "Restore Successful")
                        }
                    } finally {
                        decrypted.fill(0)
                    }
                } catch (e: Exception) {
                    Logger.e("Failed to restore backup", e)
                    secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Restore failed")
                } finally {
                    uploadedBytes?.fill(0)
                    if (tmpFile.exists() && !tmpFile.delete()) Logger.w("Failed to clean backup temp file")
                }
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "No file uploaded")
        }

        return secureResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }

    private fun secureResponse(
        status: Response.Status,
        mimeType: String,
        txt: String,
    ): Response {
        val response = newFixedLengthResponse(status, mimeType, txt)
        addSecurityHeaders(response)
        return response
    }

    private fun secureResponse(
        status: Response.Status,
        mimeType: String,
        bytes: ByteArray,
    ): Response {
        val response = newFixedLengthResponse(status, mimeType, ByteArrayInputStream(bytes), bytes.size.toLong())
        addSecurityHeaders(response)
        return response
    }

    private fun addSecurityHeaders(response: Response) {
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("X-Frame-Options", "DENY")
        response.addHeader("X-XSS-Protection", "0")
        response.addHeader(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; " +
                "form-action 'self'; frame-ancestors 'none'",
        )
        response.addHeader("Referrer-Policy", "no-referrer")
        response.addHeader("Cross-Origin-Resource-Policy", "same-origin")
        response.addHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        response.addHeader("Cache-Control", "no-store, max-age=0")
        response.addHeader("Pragma", "no-cache")
    }

    private fun getAppName(): String {
        return String(
            charArrayOf(
                67.toChar(),
                108.toChar(),
                101.toChar(),
                118.toChar(),
                101.toChar(),
                114.toChar(),
                101.toChar(),
                115.toChar(),
                84.toChar(),
                114.toChar(),
                105.toChar(),
                99.toChar(),
                107.toChar(),
                121.toChar(),
            ),
        )
    }

    private val htmlBytes by lazy { buildHtmlContent().toByteArray(Charsets.UTF_8) }

    private fun buildHtmlContent(): String {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>${getAppName()}</title>
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <style>
        :root { --bg: #0B0B0C; --fg: #E5E7EB; --accent: #D1D5DB; --panel: #161616; --border: #333; --input-bg: #1A1A1A; --success: #34D399; --danger: #EF4444; }
        html { color-scheme: dark; background: var(--bg); }
        body { background-color: var(--bg); color: var(--fg); font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; margin: 0; padding: 0; min-height: 100dvh; overscroll-behavior-y: contain; -webkit-tap-highlight-color: transparent; }
        .island-container { display: flex; justify-content: center; position: fixed; top: max(12px, env(safe-area-inset-top)); left: 0; right: 0; padding: 0 max(12px, env(safe-area-inset-right)) 0 max(12px, env(safe-area-inset-left)); box-sizing: border-box; z-index: 1000; pointer-events: none; }
        .island { background: #000; color: #fff; border-radius: 30px; min-height: 35px; width: auto; max-width: 90%; display: flex; align-items: center; justify-content: center; transition: all 0.4s cubic-bezier(0.175, 0.885, 0.32, 1.275); box-shadow: 0 4px 15px rgba(0,0,0,0.5); font-size: 0.8em; font-weight: 500; opacity: 0; transform: translateY(-20px) scale(0.9); pointer-events: auto; padding: 0; white-space: nowrap; }
        .island.active { min-width: 250px; padding: 8px 12px 8px 24px; opacity: 1; transform: translateY(0) scale(1); font-size: 0.9em; min-height: 44px; }
        .island.error { background: #330000; border: 1px solid var(--danger); }
        .island.error #islandText { color: #FECACA; }
        .spinner { width: 14px; height: 14px; border: 2px solid #fff; border-top-color: transparent; border-radius: 50%; animation: spin 0.8s linear infinite; margin-right: 10px; display: none; }
        .island.working .spinner { display: block; }
        .inline-spinner { width: 18px; height: 18px; border: 2px solid var(--accent); border-top-color: transparent; border-radius: 50%; animation: spin 0.8s linear infinite; display: inline-block; vertical-align: middle; margin-right: 10px; }
        .error-icon { display: none; margin-right: 10px; color: var(--danger); font-size: 1.2em; font-weight: bold; }
        .island.error .error-icon { display: block; }
        .success-icon { display: none; margin-right: 10px; color: var(--success); font-size: 1.2em; font-weight: bold; }
        .island.normal .success-icon { display: block; }
        .island-close { background: transparent; border: none; color: #888; font-size: 1.5em; padding: 0; margin-left: 15px; cursor: pointer; min-height: 44px; min-width: 44px; display: flex; align-items: center; justify-content: center; touch-action: manipulation; pointer-events: auto; }
        .island-close:hover { color: #fff; }
        #islandText { flex: 1; }
        @keyframes spin { to { transform: rotate(360deg); } }
        h1 { text-align: center; font-weight: 200; letter-spacing: 2px; margin: 25px 0; color: var(--accent); font-size: 1.5em; text-transform: uppercase; }
        .tabs { display: flex; justify-content: flex-start; border-bottom: 1px solid var(--border); background: var(--panel); overflow-x: auto; position: sticky; top: 0; z-index: 100; -webkit-overflow-scrolling: touch; scrollbar-width: none; scroll-snap-type: x proximity; touch-action: pan-x; padding-top: env(safe-area-inset-top); }
        .tabs::-webkit-scrollbar { display: none; }
        .tab { padding: 15px 20px; cursor: pointer; border-bottom: 2px solid transparent; opacity: 0.6; transition: all 0.2s; white-space: nowrap; font-size: 0.9em; letter-spacing: 1px; min-height: 48px; min-width: 48px; align-items: center; justify-content: center; box-sizing: border-box; display: inline-flex; flex-shrink: 0; }
        .tab:hover { opacity: 0.9; }
        .tab.active { border-bottom-color: var(--accent); opacity: 1; color: var(--accent); }
        .content { display: none; padding: 20px; max-width: 800px; margin: 0 auto; padding-bottom: max(80px, calc(48px + env(safe-area-inset-bottom))); }
        .content.active { display: block; animation: fadeIn 0.3s ease; }
        @keyframes fadeIn { from { opacity: 0; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }
        .panel { background: var(--panel); border: 1px solid var(--border); border-radius: 12px; padding: 20px; margin-bottom: 20px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); }
        h3 { margin-top: 0; font-weight: 500; color: var(--accent); font-size: 1.1em; letter-spacing: 0.5px; border-bottom: 1px solid var(--border); padding-bottom: 10px; margin-bottom: 15px; }
        .row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; min-height: 48px; }
        .row.wrap { flex-wrap: wrap; }
        label { font-size: 0.95em; color: #BBB; cursor: pointer; }
        input[type="text"], input[type="password"], input[type="search"], input[type="number"], textarea, select { background: var(--input-bg); border: 1px solid var(--border); color: #fff; padding: 12px 14px; border-radius: 6px; width: 100%; box-sizing: border-box; font-family: inherit; transition: border-color 0.2s; font-size: 0.95em; min-height: 44px; min-width: 44px; }
        input[type="text"]:focus, input[type="password"]:focus, input[type="search"]:focus, input[type="number"]:focus, textarea:focus, select:focus { border-color: var(--accent); outline: none; }
        button { background: var(--border); border: none; color: var(--fg); padding: 12px 24px; border-radius: 6px; cursor: pointer; font-family: inherit; font-weight: 500; font-size: 0.95em; transition: all 0.2s; text-transform: uppercase; letter-spacing: 0.5px; min-height: 44px; min-width: 44px; touch-action: manipulation; }
        button:hover { background: #444; }
        button:active { transform: scale(0.98); }
        button.primary { background: var(--accent); color: #000; }
        button.primary:hover { background: #fff; box-shadow: 0 0 10px rgba(255,255,255,0.2); }
        button.danger { background: rgba(239, 68, 68, 0.2); color: var(--danger); border: 1px solid var(--danger); }
        button.danger:hover { background: var(--danger); color: #fff; }
        button:focus-visible, input:focus-visible, select:focus-visible, textarea:focus-visible, .tab:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
        input[type="checkbox"].toggle { appearance: none; width: 52px; height: 32px; background: #333; border-radius: 16px; position: relative; cursor: pointer; transition: background 0.3s; border: 6px solid transparent; background-clip: padding-box; box-sizing: content-box; margin: -6px; }
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
        .search-container input[type="search"] { width: 100%; padding-right: 44px; }
        .clear-btn { position: absolute; right: 0; top: 0; bottom: 0; height: 100%; min-height: 44px; min-width: 44px; background: transparent; border: none; color: #888; font-size: 1.2em; padding: 0; cursor: pointer; display: none; touch-action: manipulation; align-items: center; justify-content: center; }



        .clear-btn:hover { color: #fff; background: transparent; }




        .autocomplete-items { position: absolute; border: 1px solid var(--border); border-bottom: none; border-top: none; z-index: 99; top: 100%; left: 0; right: 0; max-height: 200px; overflow-y: auto; background-color: var(--panel); border-radius: 0 0 6px 6px; box-shadow: 0 4px 6px rgba(0,0,0,0.3); }
        .autocomplete-items div { padding: 10px; cursor: pointer; background-color: var(--panel); border-bottom: 1px solid var(--border); color: var(--fg); font-size: 0.9em; min-height: 44px; display: flex; align-items: center; }
        .autocomplete-items div:hover { background-color: #333; }
        .autocomplete-active { background-color: var(--accent) !important; color: #000 !important; }

        .pwd-wrapper { position: relative; display: flex; align-items: center; width: 100%; margin-bottom: 5px; }
        .pwd-wrapper input { margin-bottom: 0 !important; padding-right: 60px; }
        .pwd-toggle { position: absolute; right: 5px; background: transparent; border: none; color: var(--accent); cursor: pointer; font-size: 0.85em; padding: 5px 10px; min-height: 44px; min-width: 44px; text-transform: none; touch-action: manipulation; }
        .pwd-toggle:hover { color: #fff; background: transparent; }
        .resource-summary { display: flex; justify-content: space-around; align-items: center; background: #1a1a1a; padding: 20px; border-radius: 12px; border: 1px solid var(--border); margin-bottom: 20px; box-shadow: 0 4px 6px rgba(0,0,0,0.2); }
        .resource-stat { text-align: center; padding: 10px; flex: 1; display: flex; flex-direction: column; justify-content: center; align-items: center; }
        .resource-stat-mid { border-left: 1px solid var(--border); border-right: 1px solid var(--border); }
        #fileEditor { height: min(500px, 60dvh) !important; resize: vertical; }
        #logViewer { height: min(400px, 55dvh) !important; resize: vertical; }
        @media screen and (max-width: 600px) {
            .grid-2 { grid-template-columns: 1fr; }
            .content { padding: 12px max(12px, env(safe-area-inset-right)) max(100px, calc(64px + env(safe-area-inset-bottom))) max(12px, env(safe-area-inset-left)); }
            .panel { padding: 16px; margin-bottom: 16px; border-radius: 16px; }
            h1 { font-size: 1.2em; margin: 15px 0; }
            .tabs { gap: 0; padding: 0; }
            .tabs { padding-top: env(safe-area-inset-top); }
            .tab { scroll-snap-align: start; padding: 16px; font-size: 0.9em; min-width: 60px; min-height: 52px; border-bottom-width: 3px; }
            .row { flex-wrap: wrap; gap: 10px; }
            .row > label, .row > span, .row > h3 { flex: 1; min-width: 0; line-height: 1.4; }
            .row > input[type="checkbox"].toggle { flex: 0 0 auto; }
            .responsive-table thead { display: none; }
            .responsive-table tr { display: flex; flex-direction: column; border: 1px solid var(--border); margin-bottom: 16px; border-radius: 12px; background: #1a1a1a; overflow: hidden; }
            .responsive-table td { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #2a2a2a; padding: 16px; min-height: 48px; }
            .responsive-table td:last-child { border-bottom: none; background: rgba(0,0,0,0.2); }
            .responsive-table td::before { content: attr(data-label); color: #888; font-weight: 500; margin-right: 12px; min-width: 110px; font-size: 0.9em; }
            .responsive-table td > div, .responsive-table td > span { text-align: right; flex: 1; word-break: break-word; }
            .server-item { flex-direction: column; align-items: flex-start; gap: 12px; padding: 14px; }
            .server-item > div:last-child { width: 100%; display: flex; justify-content: space-between; align-items: center; }
            input[type="text"], input[type="password"], input[type="search"], input[type="number"], textarea, select, button { font-size: 16px; min-height: 48px; } /* Prevents mobile browser zoom */
            .island { max-width: 100%; white-space: normal; overflow-wrap: anywhere; }
            .island.active { min-width: 0; width: 100%; padding-left: 16px; }
            .resource-summary { flex-direction: column; gap: 10px; background: transparent; border: none; padding: 0; box-shadow: none; }
            .resource-stat { width: 100%; padding: 15px; background: #1a1a1a; border-radius: 12px; border: 1px solid var(--border); margin-bottom: 5px; flex-direction: row; justify-content: space-between; }
            .resource-stat-mid { border-left: none; border-right: none; }
        }
        @media screen and (max-height: 520px) and (orientation: landscape) {
            #fileEditor, #logViewer { height: 48dvh !important; }
            .content { padding-bottom: max(64px, env(safe-area-inset-bottom)); }
        }
        @media (prefers-reduced-motion: reduce) {
            *, *::before, *::after { animation-duration: 0.01ms !important; animation-iteration-count: 1 !important; transition-duration: 0.01ms !important; scroll-behavior: auto !important; }
        }
    </style>
</head>
<body>
    <div class="island-container"><div id="island" class="island" role="status" aria-live="polite"><div class="spinner"></div><div class="error-icon">!</div><div class="success-icon">OK</div><span id="islandText">Notification</span><button class="island-close" onclick="document.getElementById('island').classList.remove('active')" aria-label="Close notification">&times;</button></div></div>
    <h1>${getAppName()}</h1>
    <div class="tabs" role="tablist">
        <div class="tab active" id="tab_dashboard" onclick="switchTab('dashboard')" role="tab" tabindex="0" aria-selected="true" aria-controls="dashboard" onkeydown="handleTabNavigation(event, 'dashboard')">Dashboard</div>
        <div class="tab" id="tab_spoof" onclick="switchTab('spoof')" role="tab" tabindex="-1" aria-selected="false" aria-controls="spoof" onkeydown="handleTabNavigation(event, 'spoof')">Identity</div>
        <div class="tab" id="tab_apps" onclick="switchTab('apps')" role="tab" tabindex="-1" aria-selected="false" aria-controls="apps" onkeydown="handleTabNavigation(event, 'apps')">Apps</div>
        <div class="tab" id="tab_keys" onclick="switchTab('keys')" role="tab" tabindex="-1" aria-selected="false" aria-controls="keys" onkeydown="handleTabNavigation(event, 'keys')">Keyboxes</div>
        <div class="tab" id="tab_info" onclick="switchTab('info')" role="tab" tabindex="-1" aria-selected="false" aria-controls="info" onkeydown="handleTabNavigation(event, 'info')">Info & Resources</div> <div class="tab" id="tab_guide" onclick="switchTab('guide')" role="tab" tabindex="-1" aria-selected="false" aria-controls="guide" onkeydown="handleTabNavigation(event, 'guide')">Guide</div>
        <div class="tab" id="tab_log" onclick="switchTab('log')" role="tab" tabindex="-1" aria-selected="false" aria-controls="log" onkeydown="handleTabNavigation(event, 'log')">Logs</div>
        <div class="tab" id="tab_editor" onclick="switchTab('editor')" role="tab" tabindex="-1" aria-selected="false" aria-controls="editor" onkeydown="handleTabNavigation(event, 'editor')">Editor</div>
        <div class="tab" id="tab_donate" onclick="switchTab('donate')" role="tab" tabindex="-1" aria-selected="false" aria-controls="donate" onkeydown="handleTabNavigation(event, 'donate')" style="margin-left:auto; color:var(--accent);">Donate</div>
    </div>

    <div id="dashboard" class="content active" role="tabpanel" aria-labelledby="tab_dashboard">
        <div style="display: flex; gap: 10px; margin-bottom: 20px; flex-wrap: wrap;">
            <div style="flex: 1; min-width: 120px; padding: 15px; border-radius: 8px; background: #1a1a1a; border: 1px solid var(--border); text-align: center;">
                <div style="font-size: 0.8em; color: #888; text-transform: uppercase;">Global Mode</div>
                <div id="status_global" style="font-weight: bold; color: var(--danger); margin-top: 5px; background: rgba(239, 68, 68, 0.1); padding: 5px; border-radius: 4px;">INACTIVE</div>
            </div>
        </div>

        <div class="panel">
            <h3>Quick Profile</h3>
            <div class="row">
                <select id="profileSelect" style="flex: 1; margin-right: 10px; min-height: 44px; padding: 12px 14px; background: var(--input-bg); border: 1px solid var(--border); color: #fff; border-radius: 6px;">
                    <option value="">Select a Profile...</option>
                    <option value="maximum">Maximum Compatibility</option>
                    <option value="daily">Daily Compatibility</option>
                    <option value="minimal">Minimal (substitution off)</option>
                    <option value="default">Default (targeted)</option>
                </select>
                <button onclick="applySelectedProfile(this)" style="min-height: 44px;">Apply</button>
            </div>
            <div style="font-size:0.8em; color:#888; margin-top:5px;">Applying a profile will overwrite current settings below.</div>
        </div>
        <div class="panel">
            <h3>System Control</h3>
            <div class="row"><label for="global_mode">Global Mode</label><input type="checkbox" class="toggle" id="global_mode" data-setting="global_mode" onchange="toggle('global_mode', this)"></div>
            <div class="row"><label for="tee_broken_mode">Disable Certificate Substitution (Safe Mode)</label><input type="checkbox" class="toggle" id="tee_broken_mode" data-setting="tee_broken_mode" onchange="toggle('tee_broken_mode', this)"></div>
            <div class="row"><label for="auto_keybox_check">Auto Keybox Check</label><input type="checkbox" class="toggle" id="auto_keybox_check" data-setting="auto_keybox_check" onchange="toggle('auto_keybox_check', this)"></div>
            <div class="row"><label for="random_on_boot">Refresh Identity on Boot</label><input type="checkbox" class="toggle" id="random_on_boot" data-setting="random_on_boot" onchange="toggle('random_on_boot', this)"></div>
            <div class="row"><label for="telephony">Telephony Identifier Interception</label><input type="checkbox" class="toggle" id="telephony" data-setting="telephony" onchange="toggle('telephony', this)"></div>
            <div class="section-header">Compatibility passthrough</div>
            <div class="row"><label for="rkp_passthrough">RKP Passthrough</label><input type="checkbox" class="toggle" id="rkp_passthrough" data-setting="rkp_passthrough" onchange="toggle('rkp_passthrough', this)"></div>
            <div class="row"><label for="drm_passthrough">DRM App Passthrough</label><input type="checkbox" class="toggle" id="drm_passthrough" data-setting="drm_passthrough" onchange="toggle('drm_passthrough', this)"></div>
            <div style="font-size:0.8em; color:#888; margin-top:5px;">RKP passthrough preserves generated-key responses. DRM passthrough excludes packages in drm_packages.txt from certificate substitution.</div>
            <div class="section-header">Boot Properties</div>
            <div class="row"><label for="hide_sensitive_props">Hide Sensitive Props</label><input type="checkbox" class="toggle" id="hide_sensitive_props" data-setting="hide_sensitive_props" onchange="toggle('hide_sensitive_props', this)"></div>
            <div class="row"><label for="spoof_region_cn">Spoof Region (CN)</label><input type="checkbox" class="toggle" id="spoof_region_cn" data-setting="spoof_region_cn" onchange="toggle('spoof_region_cn', this)"></div>
            <div class="row"><label for="bootPropsMode">Boot Property Policy</label><select id="bootPropsMode" style="width:auto; min-width:150px;" onchange="saveBootPropsMode(this)"><option value="auto">Automatic</option><option value="force">Always apply</option><option value="disable">Disabled</option></select></div>
            <div style="font-size:0.8em; color:#888; margin-top:5px;">Boot-property changes require a reboot. Automatic mode avoids known vendor and overlay conflicts.</div>
            <div style="margin-top:20px; border-top: 1px solid var(--border); padding-top: 15px;">
                <div class="row"><span id="keyboxStatus" style="font-size:0.9em; color:var(--success);">Active</span><button onclick="runWithState(this, 'Reloading...', reloadConfig)">Reload Config</button></div>
            </div>
        </div>
        <div class="panel"><h3>Configuration Management</h3><div style="margin-bottom:10px;"><label for="backupPw">Backup Password (required, at least 12 characters)</label><div class="pwd-wrapper"><input type="password" id="backupPw" placeholder="Enter a strong backup password" minlength="12" maxlength="1024" spellcheck="false" autocomplete="new-password" autocorrect="off" autocapitalize="off"><button type="button" class="pwd-toggle" onclick="togglePassword(this)">Show</button></div></div><div class="grid-2"><button onclick="runWithState(this, 'Exporting...', backupConfig)">Export Encrypted Settings</button><button onclick="document.getElementById('restoreInput').click()">Import Encrypted Settings</button><input type="file" id="restoreInput" style="display:none" onchange="restoreConfig(this)" accept=".ctsb"></div><div style="margin-top:10px;"><button onclick="const btn = this; requireConfirm(btn, () => runWithState(btn, 'Resetting...', resetEnvironment), 'Confirm Reset')" class="danger" style="width:100%;">One-Click Reset (Refresh Environment)</button></div></div>
    </div>

    <div id="spoof" class="content" role="tabpanel" aria-labelledby="tab_spoof">
        <div class="panel">
            <h3>Identity Manager</h3>
            <label for="templateSelect" style="display:block; font-size:0.85em; color:#888; margin-bottom:8px;">Select the attestation identity used for configured target applications.</label>
            <select id="templateSelect" onchange="previewTemplate()" style="margin-bottom:15px;"></select>
            <div id="templatePreview" style="background:var(--input-bg); border-radius:8px; padding:15px; margin-bottom:15px;">
                <div class="grid-2"><div><div class="section-header">Device</div><div id="pModel"></div></div><div><div class="section-header">Manufacturer</div><div id="pManuf"></div></div></div>
                <div class="section-header">Reference fingerprint (display only) <button onclick="copyToClipboard(document.getElementById('pFing').innerText, 'Fingerprint Copied', this)" style="font-size:0.9em; padding:8px 12px; margin-left:5px; min-height:44px;" title="Copy reference fingerprint" aria-label="Copy Reference Fingerprint">Copy</button></div><div style="font-family:monospace; font-size:0.8em; color:#999; word-break:break-all;" id="pFing"></div>
            </div>
            <div class="grid-2"><button onclick="runWithState(this, 'Generating...', generateRandomIdentity)" class="primary">Generate Random</button><button onclick="runWithState(this, 'Saving...', applySpoofing)">Apply Identity</button></div>
        </div>
        <div class="panel"><h3>Attestation and Telephony Identifiers</h3>
            <div style="font-size:0.85em; color:#888; margin-bottom:15px;">IMEI and serial can be included in rewritten attestation records. IMSI and ICCID are used only when Telephony Identifier Interception is enabled.</div>
            <div class="section-header">Identifiers</div><div class="grid-2">
                <div><label for="inputImei">IMEI</label><input type="text" id="inputImei" placeholder="35..." style="font-family:monospace;" inputmode="numeric" oninput="validateRealtime(this, 'luhn')" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></div>
                <div><label for="inputImsi">IMSI</label><input type="text" id="inputImsi" placeholder="310..." style="font-family:monospace;" inputmode="numeric" oninput="validateRealtime(this, 'imsi')" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></div>
            </div>
            <div class="grid-2" style="margin-top:10px;">
                <div><label for="inputIccid">ICCID</label><input type="text" id="inputIccid" placeholder="89..." style="font-family:monospace;" inputmode="numeric" oninput="validateRealtime(this, 'luhn')" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></div>
                <div><label for="inputSerial">Serial</label><input type="text" id="inputSerial" placeholder="Alphanumeric..." style="font-family:monospace;" autocapitalize="characters" oninput="validateRealtime(this, 'alphanum')" spellcheck="false" autocomplete="off" autocorrect="off"></div>
            </div>
            <div style="margin-top:15px; display:flex; justify-content:flex-end; gap:10px;"><button type="button" onclick="const btn = this; requireConfirm(btn, () => clearSpoofingInputs(), 'Confirm Clear')" style="background:transparent; border:1px solid var(--danger); color:var(--danger); min-height:44px; padding:0 20px;">Clear All</button><button onclick="runWithState(this, 'Saving...', applySpoofing)" class="danger">Apply Identity</button></div>
        </div>
    </div>

    <div id="apps" class="content" role="tabpanel" aria-labelledby="tab_apps">
        <div class="panel">
            <h3>New Rule</h3>
            <div style="margin-bottom:10px;"><label for="appPkg">Package Name</label><div class="search-container"><input type="text" id="appPkg" placeholder="Type to search packages..." oninput="toggleAddButton(); document.getElementById('clearPkgBtn').style.display=this.value?'flex':'none';" onkeydown="if(event.key==='Enter') addAppRule()" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off" style="padding-right:44px;"><button id="clearPkgBtn" class="clear-btn" onclick="document.getElementById('appPkg').value=''; this.style.display='none'; toggleAddButton(); document.getElementById('appPkg').focus();" >&times;</button></div></div>
            <div class="grid-2" style="margin-bottom:10px;"><div><label for="appTemplate">Attestation Identity Profile</label><select id="appTemplate"><option value="null">No identity override</option></select></div><div><label for="appKeybox">Custom Keybox</label><div class="search-container"><input type="text" id="appKeybox" placeholder="Custom Keybox" oninput="document.getElementById('clearKbBtn').style.display=this.value?'flex':'none';" onkeydown="if(event.key==='Enter') addAppRule()" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off" style="padding-right:44px;"><button id="clearKbBtn" class="clear-btn" onclick="document.getElementById('appKeybox').value=''; this.style.display='none'; document.getElementById('appKeybox').focus();" >&times;</button></div></div></div>
            <button id="btnAddRule" class="primary" style="width:100%" onclick="addAppRule()" disabled>Add Rule</button>
        </div>
        <div class="panel">
            <h3>Active Rules</h3><div class="search-container"><input type="search" id="appFilter" placeholder="Filter active rules by package name..." oninput="renderAppTable()" aria-label="Filter rules" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"><button onclick="document.getElementById('appFilter').value=''; renderAppTable(); document.getElementById('appFilter').focus();" class="clear-btn" id="clearAppFilterBtn" aria-label="Clear filter">&times;</button></div>
            <table id="appTable" class="responsive-table"><thead><tr><th>Package</th><th>Profile</th><th>Keybox</th><th></th></tr></thead><tbody></tbody></table>
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
                    else if (t === 'BEARER') af.innerHTML = '<div class=\'pwd-wrapper\'><input type=\'password\' id=\'srvAuthToken\' placeholder=\'Bearer Token\' style=\'margin-bottom:5px;\' spellcheck=\'false\' autocomplete=\'off\' autocorrect=\'off\' autocapitalize=\'off\'><button type=\'button\' class=\'pwd-toggle\' onclick=\'togglePassword(this)\'>Show</button></div>';
                    else if (t === 'BASIC') af.innerHTML = '<input type=\'text\' id=\'srvAuthUser\' placeholder=\'Username\' style=\'margin-bottom:5px;\' spellcheck=\'false\' autocomplete=\'off\' autocorrect=\'off\' autocapitalize=\'off\'><div class=\'pwd-wrapper\'><input type=\'password\' id=\'srvAuthPass\' placeholder=\'Password\' style=\'margin-bottom:5px;\' spellcheck=\'false\' autocomplete=\'off\' autocorrect=\'off\' autocapitalize=\'off\'><button type=\'button\' class=\'pwd-toggle\' onclick=\'togglePassword(this)\'>Show</button></div>';
                    else if (t === 'API_KEY') af.innerHTML = '<input type=\'text\' id=\'srvApiKeyName\' placeholder=\'Header Name (e.g. X-API-Key)\' style=\'margin-bottom:5px;\' spellcheck=\'false\' autocomplete=\'off\' autocorrect=\'off\' autocapitalize=\'off\'><div class=\'pwd-wrapper\'><input type=\'password\' id=\'srvApiKeyValue\' placeholder=\'API Key\' style=\'margin-bottom:5px;\' spellcheck=\'false\' autocomplete=\'off\' autocorrect=\'off\' autocapitalize=\'off\'><button type=\'button\' class=\'pwd-toggle\' onclick=\'togglePassword(this)\'>Show</button></div>';
                ">
                    <option value="NONE">No Auth</option>
                    <option value="BEARER">Bearer Token</option>
                    <option value="BASIC">Basic Auth</option>
                    <option value="API_KEY">API Key</option>
                </select>
                <div id="authFields"></div>
                <div class="grid-2" style="margin-top:5px;">
                    <div><label for="srvPriority">Priority</label><input type="number" id="srvPriority" value="0" min="-1000000" max="1000000" inputmode="numeric"></div>
                    <div><label for="srvRefreshHours">Refresh interval (hours)</label><input type="number" id="srvRefreshHours" value="24" min="1" max="720" inputmode="numeric"></div>
                </div>
                <div class="row" style="margin-top:8px;"><label for="srvAutoRefresh">Automatic refresh</label><input type="checkbox" class="toggle" id="srvAutoRefresh" checked></div>
                <div class="pwd-wrapper"><input type="password" id="srvContentPassword" placeholder="CBOX content password (optional)" maxlength="1024" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"><button type="button" class="pwd-toggle" onclick="togglePassword(this)">Show</button></div>
                <textarea id="srvContentPublicKey" placeholder="CBOX signature public key (optional)" maxlength="16384" style="height:90px; margin-bottom:5px;" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></textarea>
                <div style="display: flex; gap: 10px; margin-top: 10px;">
                    <button onclick="runWithState(this, 'Saving...', addServer)" class="primary" style="flex: 1;">Save Server</button>
                    <button onclick="resetServerForm()" style="flex: 1;">Cancel</button>
                </div>
            </div>
        </div>

        <div class="panel">
            <h3>Upload Keybox / CBOX</h3>
            <div class="grid-2">
                <div id="dropZone" role="button" tabindex="0" style="border: 2px dashed var(--border); border-radius: 6px; padding: 20px; text-align: center; margin-bottom: 10px; cursor: pointer;" onclick="document.getElementById('kbFilePicker').click()" onkeydown="if(event.key==='Enter'||event.key===' '){event.preventDefault(); document.getElementById('kbFilePicker').click();}">
                    <label for="kbFilename" style="display:none">Keybox File</label>
                    <input type="file" id="kbFilePicker" style="display:none" onchange="loadFileContent(this)" onclick="event.stopPropagation(); this.value = null" aria-label="Upload Keybox File" accept=".xml,.cbox">
                    <div id="dropZoneContent"><div style="font-size: 1.5em; margin-bottom: 10px; color: #888;">[ Drag &amp; Drop ]</div><div style="font-size: 0.9em; color: #888;">Or click to select .xml or .cbox</div></div>
                </div>
                <div>
                    <label for="kbContent" style="display:block; font-size:0.85em; color:#888; margin-bottom:4px;">Manual Paste (XML)</label>
                    <textarea id="kbContent" placeholder="Paste Keybox XML Content Here" maxlength="5242880" style="height:100px; font-family:monospace; font-size:0.8em; margin-bottom:10px;" aria-label="Keybox XML Content" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"></textarea>
                    <input type="text" id="kbFilenameInput" placeholder="keybox.xml" maxlength="128" style="margin-bottom:10px;" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off">
                    <button id="saveKeyboxBtn" class="primary" style="width:100%;" onclick="runWithState(this, 'Saving...', savePastedKeybox)">Save Pasted XML</button>
                </div>
            </div>
        </div>
        <div class="panel">
            <h3>Stored Keyboxes</h3>
            <div class="search-container"><input type="search" id="keyboxFilter" placeholder="Filter keyboxes by name..." oninput="renderKeyboxes()" aria-label="Filter keyboxes" spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"><button onclick="document.getElementById('keyboxFilter').value=''; renderKeyboxes(); document.getElementById('keyboxFilter').focus();" class="clear-btn" id="clearKeyboxFilterBtn" aria-label="Clear filter">&times;</button></div>
            <div id="storedKeyboxesList" style="max-height: 200px; overflow-y: auto;"></div>
        </div>
        <div class="panel">
            <div class="row"><h3>Verification</h3><button onclick="runWithState(this, 'Verifying...', verifyKeyboxes)">Check All</button></div>
            <div id="verifyResult" style="font-family:monospace; font-size:0.85em;"></div>
        </div>
    </div>

    <div id="info" class="content" role="tabpanel" aria-labelledby="tab_info">
        <div class="panel" style="background:var(--bg); border:none; padding:0; box-shadow:none;">
            <div id="resourceSummary" class="resource-summary">
                <div style="color:#888;"><div class="inline-spinner"></div> Loading resource usage...</div>
            </div>
            
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
                <div style="margin-top:15px; font-size:0.85em; color:#666; text-align:center;">
                    * RAM estimates are approximate based on loaded objects.
                </div>
            </div>
        </div>
    </div>
    <div id="guide" class="content" role="tabpanel" aria-labelledby="tab_guide">
        <div class="panel">
            <h3>Quick Start & Keybox Guide</h3>
            <p>Welcome! CleveresTricky provides attestation compatibility controls and secure keybox management for supported rooted Android devices.</p>

            <h4>1. Using Standard Keybox.xml</h4>
            <p>If you have an authorized <code>keybox.xml</code>, upload it in the Keyboxes tab or place it in <code>/data/adb/cleverestricky/</code> with mode <code>0600</code>, then reboot. The module validates it before use. A keybox cannot replace a device's hardware root of trust or guarantee a Play Integrity verdict.</p>

            <h4>2. Encrypted .cbox Files</h4>
            <p>For better at-rest protection, you can use <code>.cbox</code> files. These authenticated encrypted containers require a password; device caches use AndroidKeyStore when available and otherwise a root-only device key.</p>

            <h4>3. Remote Keybox Servers</h4>
            <p>You can fetch keyboxes from HTTPS servers that you trust. Head to the Keybox tab and enter the server details. Token and custom-header authentication are supported; signed sources fail closed when verification fails.</p>

            <h4>4. Creating .cbox Files</h4>
            <p>If you distribute authorized key material and need encrypted delivery, use the <b>Encryptor App</b>:</p>
            <ul>
                <li>Generate a signing key in the app.</li>
                <li>Select your <code>keybox.xml</code>.</li>
                <li>Set a secure password and add your author name.</li>
                <li>Share the <code>.cbox</code> file along with the Public Key so users can verify it's from you.</li>
            </ul>
        </div>
        <div class="panel">
            <h3>Language Support</h3>
            <p>The module is English-first, but fully supports community translations.</p>
            <p>To add a language, download the template below, translate the values, and place the resulting <code>lang.json</code> file in <code>/data/adb/cleverestricky/</code>. Then click Reload.</p>
            <div class="grid-2">
                <button onclick="runWithState(this, 'Downloading...', downloadLangTemplate)">Download Template</button>
                <button onclick="runWithState(this, 'Loading...', () => { notify('Loading...', 'working'); return loadLanguage().then(() => notify('Language Loaded')); })">Reload Language File</button>
            </div>
        </div>
    </div>

    <div id="log" class="content" role="tabpanel" aria-labelledby="tab_log">
        <div class="panel">
            <h3>Module Logs</h3>
            <p style="font-size:0.9em; color:#888;">View recent logs from the module. You can also download them for sharing.</p>
            <div style="display: flex; gap: 10px; margin-bottom: 10px; align-items: center; flex-wrap: wrap;">
                <select id="logType" style="flex: 1; min-width: 150px; margin: 0; min-height: 44px; padding: 12px 14px; background: var(--input-bg); border: 1px solid var(--border); color: #fff; border-radius: 6px;" aria-label="Select Log Type">
                    <option value="cleverestricky">CleveresTricky Logs</option>
                    <option value="errors">Errors Only</option>
                    <option value="system">Full System (Recent)</option>
                </select>
                <button onclick="runWithState(this, 'Refreshing...', fetchLogs)" class="primary">Refresh Logs</button>
                <button onclick="downloadLogs()">Download Logs</button>
                <button onclick="copyToClipboard(document.getElementById('logViewer').value, 'Logs Copied', this)">Copy Logs</button>
            </div>
            <textarea id="logViewer" style="height:400px; width:100%; font-family:monospace; font-size:0.8em; line-height:1.4; padding: 10px; border: 1px solid var(--border); border-radius: 4px; background: var(--surface); color: var(--text);" readonly aria-label="Module Logs"></textarea>
        </div>
    </div>

    <div id="editor" class="content" role="tabpanel" aria-labelledby="tab_editor">
        <div class="panel">
            <div class="row"><select id="fileSelector" onchange="loadFile()" style="width:70%;" aria-label="Select file to edit"><option value="target.txt">target.txt</option><option value="security_patch.txt">security_patch.txt</option><option value="spoof_build_vars">spoof_build_vars</option><option value="app_config">app_config</option><option value="templates.json">templates.json</option><option value="drm_packages.txt">drm_packages.txt</option><option value="boot_props_mode">boot_props_mode</option></select><button id="revertBtn" class="danger" onclick="const btn = this; requireConfirm(btn, () => revertEditor(), 'Confirm Revert')" style="display:none; margin-right:10px;" title="Revert Changes">Revert</button><button id="saveBtn" onclick="handleSave(this)" title="Ctrl+S">Save</button></div>
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
                    <tr><td data-label="Asset"><strong>USDT</strong></td><td data-label="Network">TRC20</td><td data-label="Address" style="font-family:monospace; font-size:0.85em; word-break:break-all;">TQGTsbqawRHhv35UMxjHo14mieUGWXyQzk</td><td><button onclick="copyToClipboard('TQGTsbqawRHhv35UMxjHo14mieUGWXyQzk','Copied USDT Address',this)" style="padding:8px 16px; font-size:0.85em; min-height:44px;">Copy</button></td></tr>
                    <tr><td data-label="Asset"><strong>XMR</strong></td><td data-label="Network">Monero</td><td data-label="Address" style="font-family:monospace; font-size:0.75em; word-break:break-all;">85m61iuWiwp24g8NRXoMKdW25ayVWFzYf5BoAqvgGpLACLuMsXbzGbWR9mC8asnCSfcyHN3dZgEX8KZh2pTc9AzWGXtrEUv</td><td><button onclick="copyToClipboard('85m61iuWiwp24g8NRXoMKdW25ayVWFzYf5BoAqvgGpLACLuMsXbzGbWR9mC8asnCSfcyHN3dZgEX8KZh2pTc9AzWGXtrEUv','Copied XMR Address',this)" style="padding:8px 16px; font-size:0.85em; min-height:44px;">Copy</button></td></tr>
                    <tr><td data-label="Asset"><strong>USDT / USDC</strong></td><td data-label="Network">ERC20 / BEP20</td><td data-label="Address" style="font-family:monospace; font-size:0.85em; word-break:break-all;">0x1a4b9e55e268e6969492a70515a5fd9fd4e6ea8b</td><td><button onclick="copyToClipboard('0x1a4b9e55e268e6969492a70515a5fd9fd4e6ea8b','Copied ERC20 Address',this)" style="padding:8px 16px; font-size:0.85em; min-height:44px;">Copy</button></td></tr>
                </tbody>
            </table>
        </div>
        <div class="panel">
            <h3>Platforms</h3>
            <div style="display:flex; flex-direction:column; gap:12px;">
                <div class="row"><span style="font-weight:bold;">Binance User ID</span><span style="font-family:monospace;">114574830 <button onclick="copyToClipboard('114574830','Copied Binance ID',this)" style="padding:8px 16px; font-size:0.85em; margin-left:5px; min-height:44px;">Copy</button></span></div>
                <div class="row"><span style="font-weight:bold;">PayPal</span><a href="https://www.paypal.me/tryigitx" target="_blank" rel="noopener noreferrer" style="display:inline-flex; align-items:center; justify-content:center; min-height:44px; min-width:44px; color:var(--accent); text-decoration:none;">paypal.me/tryigitx</a></div>
                <div class="row"><span style="font-weight:bold;">BuyMeACoffee</span><a href="https://buymeacoffee.com/yigitx" target="_blank" rel="noopener noreferrer" style="display:inline-flex; align-items:center; justify-content:center; min-height:44px; min-width:44px; color:var(--accent); text-decoration:none;">buymeacoffee.com/yigitx</a></div>
            </div>
        </div>
        <div class="panel" style="text-align:center;">
            <p style="color:#888;">Thank you for your support!</p>
        </div>
    </div>

    <script>
        window.addEventListener('unhandledrejection', function(event) {
            if (event.reason && event.reason.message && event.reason.message.includes('fetch')) {
                notify('Network error: Failed to reach the server. Is the module running?', 'error');
            }
        });
        const baseUrl = '/api';
        let editorUnsavedBypass = false;
        let currentFile = '';
        let originalContent = '';

        function togglePassword(btn) {
            const input = btn.previousElementSibling;
            if (input.type === 'password') {
                input.type = 'text';
                btn.innerText = 'Hide';
            } else {
                input.type = 'password';
                btn.innerText = 'Show';
            }
        }

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

        let installedPackages = [];
        function setupAutocomplete(inputId, getDataArray) {
            const inp = document.getElementById(inputId);
            if (!inp || inp.dataset.acInitialized) return;
            inp.dataset.acInitialized = 'true';
            let currentFocus;
            inp.addEventListener("input", function(e) {
                let a, b, i, val = this.value;
                closeAllLists();
                if (!val) { return false;}
                currentFocus = -1;
                a = document.createElement("DIV");
                a.setAttribute("id", this.id + "autocomplete-list");
                a.setAttribute("class", "autocomplete-items");
                this.parentNode.appendChild(a);
                const arr = getDataArray();
                let count = 0;
                for (i = 0; i < arr.length; i++) {
                    if (arr[i].toLowerCase().includes(val.toLowerCase())) {
                        if (count > 50) break;
                        b = document.createElement("DIV");
                        b.textContent = String(arr[i]);
                        const hidden = document.createElement('input');
                        hidden.type = 'hidden';
                        hidden.value = String(arr[i]);
                        b.appendChild(hidden);
                        b.addEventListener("click", function(e) {
                            inp.value = this.getElementsByTagName("input")[0].value;
                            closeAllLists();
                            if(inputId === 'appPkg') {
                                toggleAddButton();
                                document.getElementById('clearPkgBtn').style.display = 'block';
                            } else if (inputId === 'appKeybox') {
                                document.getElementById('clearKbBtn').style.display = 'block';
                            }
                        });
                        a.appendChild(b);
                        count++;
                    }
                }
            });
            inp.addEventListener("keydown", function(e) {
                let x = document.getElementById(this.id + "autocomplete-list");
                if (x) x = x.getElementsByTagName("div");
                if (e.keyCode == 40) { currentFocus++; addActive(x); }
                else if (e.keyCode == 38) { currentFocus--; addActive(x); }
                else if (e.keyCode == 13) {
                    e.preventDefault();
                    if (currentFocus > -1) { if (x) x[currentFocus].click(); }
                    else if (this.value && inputId === 'appPkg') { addAppRule(); closeAllLists(); }
                }
            });
            function addActive(x) {
                if (!x) return false;
                removeActive(x);
                if (currentFocus >= x.length) currentFocus = 0;
                if (currentFocus < 0) currentFocus = (x.length - 1);
                x[currentFocus].classList.add("autocomplete-active");
            }
            function removeActive(x) {
                for (let i = 0; i < x.length; i++) { x[i].classList.remove("autocomplete-active"); }
            }
            function closeAllLists(elmnt) {
                let x = document.getElementsByClassName("autocomplete-items");
                for (let i = 0; i < x.length; i++) {
                    if (elmnt != x[i] && elmnt != inp) { x[i].parentNode.removeChild(x[i]); }
                }
            }
            document.addEventListener("click", function (e) { closeAllLists(e.target); });
        }

        let token = urlParams.get('token');
        if (token) sessionStorage.setItem('ct_token', token);
        else token = sessionStorage.getItem('ct_token');
        window.history.replaceState({}, document.title, window.location.pathname);
        if (!token) {
            document.body.innerHTML = '<div style="padding: 20px; text-align: center; color: white; background: #121212; height: 100vh; font-family: sans-serif;"><h2>Missing Token</h2><p>Please open WebUI from the KernelSU or APatch module action menu.</p><button onclick="window.location.reload()" style="padding: 10px 20px; margin-top: 20px; background: #3b82f6; color: white; border: none; border-radius: 4px; min-height: 44px; min-width: 44px;">Retry</button></div>';
            throw new Error('No token');
        }
        function getAuthUrl(path) { return path; }
        function escapeHtml(value) {
            const element = document.createElement('div');
            element.textContent = String(value ?? '');
            return element.innerHTML;
        }
        async function fetchAuth(url, options = {}) {
            if (!token) throw new Error('No token');
            const requestOptions = { ...options };
            const requestedTimeout = Number(requestOptions.timeoutMs ?? 60000);
            delete requestOptions.timeoutMs;
            const timeoutMs = Number.isFinite(requestedTimeout) ? Math.min(Math.max(requestedTimeout, 1000), 120000) : 60000;
            const upstreamSignal = requestOptions.signal;
            const controller = new AbortController();
            const forwardAbort = () => controller.abort();
            if (upstreamSignal) {
                if (upstreamSignal.aborted) controller.abort();
                else upstreamSignal.addEventListener('abort', forwardAbort, { once: true });
            }
            const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
            const headers = new Headers(requestOptions.headers || {});
            headers.set('X-Auth-Token', token);
            try {
                return await fetch(url, {
                    ...requestOptions,
                    headers,
                    signal: controller.signal,
                    credentials: 'same-origin',
                    cache: 'no-store',
                    redirect: 'error'
                });
            } catch (error) {
                if (error && error.name === 'AbortError' && !(upstreamSignal && upstreamSignal.aborted)) {
                    throw new Error('Request timed out');
                }
                throw error;
            } finally {
                clearTimeout(timeoutId);
                if (upstreamSignal) upstreamSignal.removeEventListener('abort', forwardAbort);
            }
        }
        function downloadBlob(blob, filename) {
            const url = URL.createObjectURL(blob);
            const anchor = document.createElement('a');
            anchor.href = url;
            anchor.download = filename;
            anchor.style.display = 'none';
            document.body.appendChild(anchor);
            anchor.click();
            anchor.remove();
            setTimeout(() => URL.revokeObjectURL(url), 1500);
        }

        async function copyToClipboard(text, msg, btn) {
            const originalHtml = btn.innerHTML;
            try {
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    await navigator.clipboard.writeText(text);
                } else {
                    const fallback = document.createElement('textarea');
                    fallback.value = text;
                    fallback.setAttribute('readonly', '');
                    fallback.style.position = 'fixed';
                    fallback.style.opacity = '0';
                    document.body.appendChild(fallback);
                    fallback.select();
                    const copied = document.execCommand('copy');
                    fallback.remove();
                    if (!copied) throw new Error('Clipboard unavailable');
                }
                btn.innerText = 'Copied';
                btn.classList.add('valid');
                notify(msg, 'normal');
                setTimeout(() => btn.innerHTML = originalHtml, 2000);
                setTimeout(() => btn.classList.remove('valid'), 2000);
            } catch (error) {
                notify('Copy failed. Check permissions.', 'error');
            }
        }
        let notifyTimeout;
        function notify(msg, type = 'normal') {
            if (notifyTimeout) clearTimeout(notifyTimeout);
            const island = document.getElementById('island');

            // Escape HTML for message
            const div = document.createElement('div');
            div.innerText = msg;
            const safeMsg = div.innerHTML;

            document.getElementById('islandText').innerHTML = safeMsg;
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
             if (!btn || btn.disabled) return;
             const orig = btn.innerText;
             btn.disabled = true;
             btn.setAttribute('aria-busy', 'true');
             btn.innerText = text;
             notify(text, 'working');
             try {
                 await task();
             } catch (error) {
                 console.error(error);
                 notify('Error: ' + (error && error.message ? error.message : 'Operation failed'), 'error');
             } finally {
                 btn.disabled = false;
                 btn.removeAttribute('aria-busy');
                 btn.innerText = orig;
                 const island = document.getElementById('island');
                 if (island.classList.contains('working')) {
                     island.classList.remove('active');
                 }
             }
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
            const reducedMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
            window.scrollTo({ top: 0, behavior: reducedMotion ? 'auto' : 'smooth' });
        }

        function handleTabNavigation(e, id) {
            if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
                e.preventDefault();
                const tabs = ['dashboard', 'spoof', 'apps', 'keys', 'info', 'guide', 'log', 'editor', 'donate'];
                let idx = tabs.indexOf(id);
                if (e.key === 'ArrowRight') idx = (idx + 1) % tabs.length;
                else idx = (idx - 1 + tabs.length) % tabs.length;
                const nextId = tabs[idx];
                switchTab(nextId);
                document.getElementById('tab_' + nextId).focus();
            }
        }

        async function fetchLogs() {
            try {
                const logTypeEl = document.getElementById('logType');
                const logType = logTypeEl ? logTypeEl.value : 'cleverestricky';
                const res = await fetchAuth('/api/logs?type=' + encodeURIComponent(logType));
                if (!res.ok) throw new Error(await res.text());
                const data = await res.text();
                const viewer = document.getElementById('logViewer');
                viewer.value = data;
                viewer.scrollTop = viewer.scrollHeight;
                notify('Logs refreshed', 'normal');
            } catch (e) {
                console.error(e);
                notify('Failed to load logs: ' + e.message, 'error');
            }
        }

        function downloadLogs() {
            const content = document.getElementById('logViewer').value;
            if (!content || content.trim() === '') {
                notify('No logs to download', 'error');
                return;
            }
            const blob = new Blob([content], {type: "text/plain"});
            downloadBlob(blob, 'cleverestricky_logs.txt');
            notify('Download started', 'normal');
        }

        async function loadKeyInfo() {
            const keyboxListPromise = loadKeyboxes();
            const serverListPromise = loadServers();
            try {
                const [configRes, statusRes] = await Promise.all([
                    fetchAuth('/api/config'),
                    fetchAuth('/api/cbox_status')
                ]);
                if (!configRes.ok) throw new Error(await configRes.text());
                if (!statusRes.ok) throw new Error(await statusRes.text());
                const configData = await configRes.json();
                document.getElementById('keyboxStatus').innerText = `${'$'}{configData.keybox_count} Keys Loaded`;
                const data = await statusRes.json();

                const lockedList = document.getElementById('lockedList');
                lockedList.innerHTML = '';
                if (data.locked.length > 0) {
                    document.getElementById('lockedSection').style.display = 'block';
                    data.locked.forEach((f, index) => {
                        const div = document.createElement('div');
                        div.className = 'locked-item';
                        const controlId = 'locked_' + index;
                        const title = document.createElement('div');
                        title.style.cssText = 'font-weight:bold;margin-bottom:5px;word-break:break-all';
                        title.textContent = String(f);
                        const wrapper = document.createElement('div');
                        wrapper.className = 'pwd-wrapper';
                        const password = document.createElement('input');
                        password.type = 'password';
                        password.id = 'pwd_' + controlId;
                        password.placeholder = 'Password';
                        password.autocomplete = 'off';
                        password.spellcheck = false;
                        const show = document.createElement('button');
                        show.type = 'button';
                        show.className = 'pwd-toggle';
                        show.textContent = 'Show';
                        show.onclick = () => togglePassword(show);
                        wrapper.append(password, show);
                        const publicKey = document.createElement('textarea');
                        publicKey.id = 'pk_' + controlId;
                        publicKey.placeholder = 'Public Key (Optional)';
                        publicKey.style.cssText = 'height:60px;font-size:0.8em;margin-bottom:5px';
                        publicKey.autocomplete = 'off';
                        publicKey.spellcheck = false;
                        const unlock = document.createElement('button');
                        unlock.textContent = 'Unlock';
                        unlock.onclick = () => runWithState(unlock, 'Unlocking...', () => unlockCbox(String(f), controlId));
                        div.append(title, wrapper, publicKey, unlock);
                        lockedList.appendChild(div);
                    });
                } else {
                    document.getElementById('lockedSection').style.display = 'none';
                }

                await Promise.allSettled([keyboxListPromise, serverListPromise]);
            } catch(e) {
                console.error(e);
                notify('Error: ' + e.message, 'error');
            }
        }

        async function unlockCbox(filename, controlId) {
            const pwd = document.getElementById('pwd_' + controlId).value;
            if (!pwd.trim()) { notify('Password required', 'error'); return; }
            const pk = document.getElementById('pk_' + controlId).value;
            try {
                const formData = new FormData();
                formData.append('filename', filename);
                formData.append('password', pwd);
                formData.append('public_key', pk);
                const res = await fetchAuth('/api/unlock_cbox', { method: 'POST', body: formData });
                if (res.ok) { notify('Unlocked!'); loadKeyInfo(); } else { const msg = await res.text(); notify('Error: ' + msg, 'error'); }
            } catch(e) { notify('Error: ' + e.message, 'error'); return; }
        }

        async function loadServers() {
            const list = document.getElementById('serverList');
            if (list) list.innerHTML = '<div style="padding:15px; text-align:center; color:#888;"><div class="inline-spinner"></div> Loading...</div>';
            try {
                const res = await fetchAuth('/api/servers');
                if (!res.ok) throw new Error(await res.text());
                const servers = await res.json();
                if (list) list.innerHTML = '';
                if (servers.length === 0) {
                    if (list) list.innerHTML = '<div style="text-align:center; padding:15px; color:#666;">No servers configured. Add one below to fetch keyboxes automatically.</div>';
                }
                servers.forEach(s => {
                    const div = document.createElement('div');
                    div.className = 'server-item';

                    const info = document.createElement('div');
                    const name = document.createElement('div');
                    name.style.fontWeight = 'bold';
                    name.textContent = String(s.name || '');
                    const url = document.createElement('div');
                    url.style.cssText = 'font-size:0.8em;color:#888;word-break:break-all';
                    url.textContent = String(s.url || '');
                    info.append(name, url);

                    const actions = document.createElement('div');
                    const status = document.createElement('span');
                    const statusText = String(s.lastStatus || 'UNKNOWN');
                    status.className = `status-badge status-${'$'}{statusText.startsWith('OK') ? 'OK' : 'ERROR'}`;
                    status.textContent = statusText;

                    const refresh = document.createElement('button');
                    refresh.style.cssText = 'padding:8px 16px;margin-left:10px;min-height:44px';
                    refresh.textContent = 'Refresh';
                    refresh.onclick = () => runWithState(refresh, 'Refreshing...', () => refreshServer(String(s.id)));

                    const remove = document.createElement('button');
                    remove.className = 'danger';
                    remove.style.cssText = 'padding:8px 16px;margin-left:5px;min-height:44px';
                    remove.textContent = 'Remove';
                    remove.onclick = () => requireConfirm(remove, () => runWithState(remove, 'Removing...', () => deleteServer(String(s.id))), 'Confirm Remove');

                    actions.append(status, refresh, remove);
                    div.append(info, actions);
                    list.appendChild(div);
                });
            } catch(e) {
                console.error(e);
                notify('Error: ' + e.message, 'error');
                if (list) list.innerHTML = '<div style="text-align:center; padding:15px; color:var(--danger);">Failed to load servers.</div>';
            }
        }

        function resetServerForm() {
            document.getElementById('addServerForm').style.display = 'none';
            document.getElementById('srvName').value = '';
            document.getElementById('srvUrl').value = '';
            document.getElementById('srvAuthType').value = 'NONE';
            document.getElementById('authFields').innerHTML = '';
            document.getElementById('srvPriority').value = '0';
            document.getElementById('srvRefreshHours').value = '24';
            document.getElementById('srvAutoRefresh').checked = true;
            document.getElementById('srvContentPassword').value = '';
            document.getElementById('srvContentPublicKey').value = '';
        }

        async function addServer() {
            const name = document.getElementById('srvName').value;
            const url = document.getElementById('srvUrl').value;
            if (!name.trim() || !url.trim()) throw new Error('Name and URL are required');
            let parsedUrl;
            try { parsedUrl = new URL(url); } catch (_) { throw new Error('A valid HTTPS URL is required'); }
            if (parsedUrl.protocol !== 'https:' || parsedUrl.username || parsedUrl.password || parsedUrl.hash) throw new Error('A credential-free HTTPS URL is required');
            const authType = document.getElementById('srvAuthType').value;
            const authData = {};
            if (authType === 'BEARER') authData.token = document.getElementById('srvAuthToken')?.value || '';
            else if (authType === 'BASIC') { authData.username = document.getElementById('srvAuthUser')?.value || ''; authData.password = document.getElementById('srvAuthPass')?.value || ''; }
            else if (authType === 'API_KEY') { authData.headerName = document.getElementById('srvApiKeyName')?.value || 'X-API-Key'; authData.key = document.getElementById('srvApiKeyValue')?.value || ''; }
            const priority = Number.parseInt(document.getElementById('srvPriority').value, 10);
            const refreshIntervalHours = Number.parseInt(document.getElementById('srvRefreshHours').value, 10);
            if (!Number.isInteger(priority) || priority < -1000000 || priority > 1000000) throw new Error('Priority is out of range');
            if (!Number.isInteger(refreshIntervalHours) || refreshIntervalHours < 1 || refreshIntervalHours > 720) throw new Error('Refresh interval is out of range');
            const data = {
                name: name.trim(),
                url: parsedUrl.toString(),
                authType,
                authData,
                priority,
                enabled: true,
                autoRefresh: document.getElementById('srvAutoRefresh').checked,
                refreshIntervalHours,
                contentPassword: document.getElementById('srvContentPassword').value || '',
                contentPublicKey: document.getElementById('srvContentPublicKey').value.trim()
            };

            const formData = new FormData();
            formData.append('data', JSON.stringify(data));
            const res = await fetchAuth('/api/server/add', { method: 'POST', body: formData });
            if (!res.ok) throw new Error(await res.text());
            notify('Server Added');
            resetServerForm();
            await loadServers();
        }

        async function deleteServer(id) {
            try {
                notify('Removing...', 'working');
                const formData = new FormData();
                formData.append('id', id);
                const res = await fetchAuth('/api/server/delete', { method: 'POST', body: formData });
                if (res.ok) { notify('Server Removed'); loadServers(); } else { const msg = await res.text(); notify('Error: ' + msg, 'error'); }
            } catch(e) { notify('Error: ' + e.message, 'error'); return; }
        }

        async function refreshServer(id) {
            try {
                notify('Refreshing...', 'working');
                const formData = new FormData();
                formData.append('id', id);
                const res = await fetchAuth('/api/server/refresh', { method: 'POST', body: formData });
                if(res.ok) { notify('Refreshed'); loadServers(); } else { const msg = await res.text(); notify('Error: ' + msg, 'error'); }
            } catch(e) { notify('Error: ' + e.message, 'error'); return; }
        }

        async function loadFileContent(input) {
            const file = input instanceof File ? input : (input && input.files ? input.files[0] : null);
            if (file) {
                const lowerName = file.name.toLowerCase();
                if ((!lowerName.endsWith('.xml') && !lowerName.endsWith('.cbox')) || file.size <= 0 || file.size > 10 * 1024 * 1024) {
                    notify('Select a non-empty XML or CBOX file up to 10 MB', 'error');
                    if (!(input instanceof File)) input.value = '';
                    resetDropZone();
                    return;
                }

                const dz = document.getElementById('dropZoneContent');
                const tempDiv = document.createElement('div'); tempDiv.innerText = file.name; const safeFileName = tempDiv.innerHTML;
                dz.innerHTML = '<div style="font-size: 1.2em; margin-bottom: 10px; color:var(--accent); font-weight:bold; display: flex; align-items: center; justify-content: center;"><div class="inline-spinner"></div>Uploading: ' + safeFileName + '...</div>';
                document.getElementById('dropZone').style.borderColor = 'var(--success)';

                const formData = new FormData();
                formData.append('file', file);
                formData.append('filename', file.name);

                notify('Uploading...', 'working');
                try {
                    const res = await fetchAuth('/api/upload_keybox', { method: 'POST', body: formData, timeoutMs: 120000 });
                    if (!res.ok) {
                        const msg = await res.text();
                        notify('Error: ' + msg, 'error');
                        loadKeyboxes();
                        resetDropZone();
                        return;
                    }
                    dz.innerHTML = '<div style="font-size: 1.5em; margin-bottom: 10px; color:var(--success); font-weight:bold;">OK - ' + safeFileName + '</div>';
                    notify('Uploaded Successfully', 'normal');
                    document.getElementById('kbContent').value = '';
                    try {
                        const body = await res.clone().json();
                        if (body.keybox_count !== undefined) {
                            document.getElementById('keyboxStatus').innerText = body.keybox_count + ' Keys Loaded';
                        }
                    } catch(e) { console.error(e); notify('Error: ' + e.message, 'error'); resetDropZone(); return; }
                    loadKeyInfo();
                    setTimeout(resetDropZone, 3000);
                } catch(e) { notify('Error: ' + e.message, 'error'); resetDropZone(); return; }
            }
        }

        function resetDropZone() {
            const dz = document.getElementById('dropZoneContent');
            dz.innerHTML = '<div style="font-size: 1.5em; margin-bottom: 10px; color: #888;">[ Drag &amp; Drop ]</div><div style="font-size: 0.9em; color: #888;">Select .xml or .cbox</div>';
            document.getElementById('dropZone').style.borderColor = 'var(--border)';
            document.getElementById('kbFilePicker').value = '';
        }

        async function savePastedKeybox() {
            const content = document.getElementById('kbContent').value.trim();
            if (!content) {
                notify('Please paste XML content first', 'error');
                return;
            }
            let filenameInput = document.getElementById('kbFilenameInput').value.trim();
            let filename = filenameInput || 'keybox.xml';
            if (!filename.toLowerCase().endsWith('.xml')) filename += '.xml';

            notify('Saving...', 'working');
            try {
                const formData = new FormData();
                formData.append('filename', filename);
                formData.append('content', content);
                const res = await fetchAuth('/api/upload_keybox', {
                    method: 'POST',
                    body: formData,
                    timeoutMs: 120000
                });
                if (!res.ok) {
                    const msg = await res.text();
                    notify('Error: ' + msg, 'error');
                } else {
                    notify('Saved Successfully');
                    document.getElementById('kbContent').value = '';
                    document.getElementById('kbFilenameInput').value = '';
                    try {
                        const body = await res.clone().json();
                        if (body.keybox_count !== undefined) {
                            document.getElementById('keyboxStatus').innerText = body.keybox_count + ' Keys Loaded';
                        }
                    } catch(e) { console.error(e); notify('Error: ' + e.message, 'error'); return; }
                    loadKeyInfo();
                }
            } catch(e) {
                notify('Error: ' + e.message, 'error');
            }
        }

        const WEB_UI_SETTINGS = ['global_mode', 'tee_broken_mode', 'auto_keybox_check', 'random_on_boot', 'hide_sensitive_props', 'spoof_region_cn', 'telephony', 'rkp_passthrough', 'drm_passthrough'];

        function updateGlobalStatus(enabled) {
            const status = document.getElementById('status_global');
            if (!status) return;
            status.innerText = enabled ? 'ACTIVE' : 'INACTIVE';
            status.style.color = enabled ? 'var(--success)' : 'var(--danger)';
            status.style.background = enabled ? 'rgba(74, 222, 128, 0.1)' : 'rgba(239, 68, 68, 0.1)';
        }

        function syncSettingControls(setting, enabled, disabled = false) {
            document.querySelectorAll('[data-setting="' + setting + '"]').forEach(control => {
                control.checked = Boolean(enabled);
                control.disabled = disabled;
            });
            if (setting === 'global_mode') updateGlobalStatus(Boolean(enabled));
        }

        async function init() {
            if (!token) return;
            console.log('[CleveresTricky] init: loading config...');
            try {
                const res = await fetchAuth(getAuthUrl('/api/config'));
                    if (!res.ok) throw new Error(await res.text());
                const data = await res.json();
                console.log('[CleveresTricky] config loaded:', JSON.stringify({global_mode: data.global_mode, keybox_count: data.keybox_count, tee_broken_mode: data.tee_broken_mode, telephony: data.telephony}));
                WEB_UI_SETTINGS.forEach(k => syncSettingControls(k, Boolean(data[k])));
                determineActiveProfile(data);
                document.getElementById('keyboxStatus').innerText = `${'$'}{data.keybox_count} Keys Loaded`;
            } catch(e) { console.error(e); notify('Error: ' + e.message, 'error'); }

            try {
                const tRes = await fetchAuth(getAuthUrl('/api/templates'));
                if (!tRes.ok) throw new Error(await tRes.text());
                const templates = await tRes.json();
                const sel = document.getElementById('templateSelect');
                const appSel = document.getElementById('appTemplate');
                templates.forEach(t => {
                    const opt = document.createElement('option');
                    opt.value = t.id; opt.text = `${'$'}{t.model} (${'$'}{t.manufacturer})`; opt.dataset.json = JSON.stringify(t);
                    sel.appendChild(opt.cloneNode(true)); appSel.appendChild(opt);
                });
                previewTemplate();
            } catch(e) { console.error(e); notify('Error loading templates: ' + e.message, 'error'); }
            fetchAuth(getAuthUrl('/api/packages')).then(async r => { if(!r.ok) throw new Error(await r.text()); return r.json(); }).then(pkgs => {
                installedPackages = pkgs;
                setupAutocomplete('appPkg', () => installedPackages);
            }).catch(e => { notify('Error: ' + e.message, 'error'); });
            loadKeyboxes();
            currentFile = document.getElementById('fileSelector').value;
            await Promise.all([loadFile(), loadBootPropsMode()]);

        }

        async function loadBootPropsMode() {
            const select = document.getElementById('bootPropsMode');
            if (!select) return;
            try {
                const res = await fetchAuth('/api/file?filename=boot_props_mode');
                if (!res.ok) throw new Error(await res.text());
                const value = (await res.text()).trim().toLowerCase();
                const mode = ['auto', 'force', 'disable'].includes(value) ? value : 'auto';
                select.value = mode;
                select.dataset.savedValue = mode;
            } catch (error) {
                console.error(error);
                select.value = 'auto';
                select.dataset.savedValue = 'auto';
                notify('Could not load boot property policy', 'error');
            }
        }

        async function saveBootPropsMode(select) {
            const previous = select.dataset.savedValue || 'auto';
            const mode = select.value;
            if (!['auto', 'force', 'disable'].includes(mode)) {
                select.value = previous;
                return;
            }
            select.disabled = true;
            notify('Saving boot property policy...', 'working');
            try {
                const content = mode + '\n';
                const res = await fetchAuth('/api/save', {
                    method: 'POST',
                    body: new URLSearchParams({ filename: 'boot_props_mode', content })
                });
                if (!res.ok) throw new Error(await res.text());
                select.dataset.savedValue = mode;
                const editor = document.getElementById('fileEditor');
                if (currentFile === 'boot_props_mode' && editor && editor.value === originalContent) {
                    editor.value = content;
                    originalContent = content;
                    updateSaveButtonState();
                }
                notify('Boot property policy saved');
            } catch (error) {
                select.value = previous;
                notify('Error: ' + error.message, 'error');
            } finally {
                select.disabled = false;
            }
        }

        async function toggle(setting, sourceElement) {
            if (!WEB_UI_SETTINGS.includes(setting)) {
                notify('Invalid setting', 'error');
                return;
            }
            const el = sourceElement || document.getElementById(setting);
            if (!el) {
                notify('Setting control is unavailable', 'error');
                return;
            }
            const requestedValue = Boolean(el.checked);
            syncSettingControls(setting, requestedValue, true);
            notify('Updating...', 'working');
            try {
                const res = await fetchAuth('/api/toggle', {method:'POST', body: new URLSearchParams({setting, value: requestedValue})});
                if (!res.ok) {
                    const message = await res.text();
                    throw new Error('Server returned ' + res.status + ': ' + message);
                }
                syncSettingControls(setting, requestedValue);
                notify('Setting Updated');
            } catch(e) {
                syncSettingControls(setting, !requestedValue);
                notify('Error: ' + e.message, 'error');
            } finally {
                document.querySelectorAll('[data-setting="' + setting + '"]').forEach(control => {
                    control.disabled = false;
                });
            }
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
            try {
                const res = await fetchAuth('/api/random_identity');
                if (!res.ok) { const msg = await res.text(); notify('Error: ' + msg, 'error'); return; }
                const t = await res.json();
                document.getElementById('inputImei').value = t.imei || '';
                document.getElementById('inputImsi').value = t.imsi || '';
                document.getElementById('inputIccid').value = t.iccid || '';
                document.getElementById('inputSerial').value = t.serial || '';
                document.getElementById('pModel').innerText = t.model + ' (Randomized)';
                document.getElementById('pManuf').innerText = t.manufacturer;
                document.getElementById('pFing').innerText = t.fingerprint;
                const sel = document.getElementById('templateSelect');
                sel.dataset.generated = JSON.stringify(t);
                notify('Identity Generated');
            } catch (e) {
                console.error(e);
                notify('Error generating identity: ' + e.message, 'error');
            }
        }

        async function verifyKeyboxes() {
            const resultDiv = document.getElementById('verifyResult');
            resultDiv.style.color = '';
            resultDiv.innerHTML = '<div style="color:#888;"><div class="inline-spinner"></div> Verifying... Please wait.</div>';
            notify('Verifying...', 'working');
            try {
                const res = await fetchAuth('/api/verify_keyboxes', { method: 'POST' });
                if (!res.ok) {
                    const txt = await res.text();
                    resultDiv.textContent = txt;
                    resultDiv.style.color = 'var(--danger)';
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
                resultDiv.textContent = 'Error: ' + e.message;
                resultDiv.style.color = 'var(--danger)';
                notify('Error: ' + e.message, 'error');
            }
        }

        let cachedKeyboxes = [];
        async function loadKeyboxes() {
            try {
                const list = document.getElementById('storedKeyboxesList');
                if (list) list.innerHTML = '<div style="padding:10px; text-align:center; color:#888;"><div class="inline-spinner"></div> Loading...</div>';
                const res = await fetchAuth('/api/keyboxes');
                if (res.ok) {
                    cachedKeyboxes = await res.json();
                    renderKeyboxes();
                    setupAutocomplete('appKeybox', () => cachedKeyboxes);
                } else { throw new Error(await res.text()); }
            } catch(e) { console.error(e); notify('Error: ' + e.message, 'error'); return; }
        }

        function renderKeyboxes() {
            const list = document.getElementById('storedKeyboxesList');
            const filterInput = document.getElementById('keyboxFilter');
            const clearBtn = document.getElementById('clearKeyboxFilterBtn');
            if (clearBtn) clearBtn.style.display = (filterInput && filterInput.value) ? 'flex' : 'none';
            const filterText = filterInput ? filterInput.value.toLowerCase() : '';
            if (!list) return;
            list.innerHTML = '';
            let matchCount = 0;

            cachedKeyboxes.forEach(k => {
                if (filterText && !k.toLowerCase().includes(filterText)) return;
                matchCount++;
                const div = document.createElement('div'); div.className = 'row'; div.style.padding = '10px'; div.style.borderBottom = '1px solid var(--border)';
                const filename = document.createElement('div');
                filename.style.cssText = 'word-break:break-all;margin-right:10px;flex:1';
                filename.textContent = String(k);
                const actions = document.createElement('div');
                actions.style.flexShrink = '0';
                const stored = document.createElement('span');
                stored.style.cssText = 'font-size:0.8em;color:#666;margin-right:15px';
                stored.textContent = 'Stored';
                const remove = document.createElement('button');
                remove.className = 'danger';
                remove.style.cssText = 'padding:8px 16px;font-size:0.85em;min-height:44px';
                remove.textContent = 'Delete';
                remove.title = 'Delete Keybox';
                remove.setAttribute('aria-label', 'Delete ' + String(k));
                remove.onclick = () => requireConfirm(remove, () => runWithState(remove, 'Deleting...', () => deleteKeybox(String(k))), 'Confirm Delete');
                actions.append(stored, remove);
                div.append(filename, actions);
                list.appendChild(div);
            });

            if (filterText && matchCount === 0) {
                 const div = document.createElement('div');
                 div.style.padding = '10px'; div.style.textAlign = 'center'; div.style.color = '#666';
                 div.innerHTML = 'No keyboxes match your filter. <button onclick="document.getElementById(\'keyboxFilter\').value=\'\'; renderKeyboxes()" style="margin-left:10px; padding:8px 16px; font-size:0.85em; min-height:44px;">Clear Filter</button>';
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
                notify('Error: ' + e.message, 'error');
            }
        }

        function clearSpoofingInputs() {
            ['inputImei', 'inputImsi', 'inputIccid', 'inputSerial'].forEach(id => {
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
                 'inputSerial': 'alphanum'
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
                     if (res.ok) { content = await res.text(); } else { const msg = await res.text(); throw new Error(msg); }
                 } catch(e) { console.error(e); notify('Error loading build vars: ' + e.message, 'error'); return; }

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
                     'inputSerial': 'ATTESTATION_ID_SERIAL'
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
                 notify('Saving Configuration...', 'working');
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
            if(tbody) tbody.innerHTML = '<tr><td colspan="4" style="text-align:center; padding:20px; color:#888;"><div class="inline-spinner"></div> Loading...</td></tr>';
            try {
                const res = await fetchAuth(getAuthUrl('/api/app_config_structured'));
                if (!res.ok) throw new Error(await res.text());
                appRules = await res.json();
                renderAppTable();
            } catch(e) { notify('Error: ' + e.message, 'error'); return; }
        }
        function renderAppTable() {
            const filterInput = document.getElementById('appFilter');
            const clearBtn = document.getElementById('clearAppFilterBtn');
            if (clearBtn) clearBtn.style.display = (filterInput && filterInput.value) ? 'flex' : 'none';
            const filter = filterInput ? filterInput.value.toLowerCase() : '';
            const tbody = document.querySelector('#appTable tbody');
            tbody.innerHTML = '';
            if (appRules.length === 0) {
                const tr = document.createElement('tr'); tr.innerHTML = '<td colspan="4" style="text-align:center; padding:20px; color:#666;">No active rules.</td>'; tbody.appendChild(tr); return;
            }
            let matchCount = 0;
            appRules.forEach((rule, idx) => {
                if (filter && !rule.package.toLowerCase().includes(filter)) return;
                matchCount++;
                const tr = document.createElement('tr');
                tr.innerHTML = `<td data-label="Package">${'$'}{rule.package}</td><td data-label="Profile">${'$'}{rule.template === 'null' ? 'Default' : rule.template}</td><td data-label="Keybox">${'$'}{rule.keybox && rule.keybox !== 'null' ? rule.keybox : ''}</td><td style="text-align:right;"><button style="padding:8px 16px; margin-right:5px; min-height:44px;" onclick="editAppRule(${'$'}{idx})" title="Edit rule" aria-label="Edit rule for ${'$'}{rule.package}">Edit</button><button class="danger" style="padding:8px 16px; min-height:44px;" onclick="const btn = this; requireConfirm(btn, () => runWithState(btn, 'Removing...', () => removeAppRule(${'$'}{idx})), 'Confirm Remove')" title="Remove rule" aria-label="Remove rule for ${'$'}{rule.package}">Remove</button></td>`;
                tbody.appendChild(tr);
            });

            if (filter && matchCount === 0) {
                const tr = document.createElement('tr');
                tr.innerHTML = '<td colspan="4" style="text-align:center; padding:20px; color:#666;">No rules match your filter. <button onclick="document.getElementById(\'appFilter\').value=\'\'; renderAppTable()" style="margin-left:10px; padding:8px 16px; font-size:0.85em; min-height:44px;">Clear Filter</button></td>';
                tbody.appendChild(tr);
            }
        }
        function addAppRule() {
            const pkgInput = document.getElementById('appPkg');
            const pkg = pkgInput.value.trim();
            const tmpl = document.getElementById('appTemplate').value;
            const kb = document.getElementById('appKeybox').value;
            if (!pkg) { notify('Package required', 'error'); pkgInput.focus(); return; }
            const pkgRegex = /^[a-zA-Z0-9_.*]+$/;
            if (!pkgRegex.test(pkg)) { notify('Invalid package', 'error'); pkgInput.focus(); return; }

            const existingIdx = appRules.findIndex(r => r.package === pkg);
            if (existingIdx !== -1) {
                appRules[existingIdx] = { package: pkg, template: tmpl === 'null' ? '' : tmpl, keybox: kb };
            } else {
                appRules.push({ package: pkg, template: tmpl === 'null' ? '' : tmpl, keybox: kb });
            }

            renderAppTable(); pkgInput.value = ''; document.getElementById('appKeybox').value = ''; if(document.getElementById('clearPkgBtn')) document.getElementById('clearPkgBtn').style.display='none'; if(document.getElementById('clearKbBtn')) document.getElementById('clearKbBtn').style.display='none';
            toggleAddButton(); pkgInput.focus();
            notify(existingIdx !== -1 ? 'Rule Updated' : 'Rule Added');
        }

        function editAppRule(idx) {
            const rule = appRules[idx];
            document.getElementById('appPkg').value = rule.package;
            const tmplSel = document.getElementById('appTemplate');
            tmplSel.value = rule.template || 'null';
            if (!tmplSel.value) tmplSel.value = 'null';
            document.getElementById('appKeybox').value = rule.keybox || '';
            document.getElementById('appPkg').focus();
            toggleAddButton();
            document.getElementById('clearPkgBtn').style.display = 'block';
            document.getElementById('clearKbBtn').style.display = rule.keybox ? 'block' : 'none';
        }

        function removeAppRule(idx) {
            appRules.splice(idx, 1); renderAppTable();
        }
        async function saveAppConfig() {
            notify('Saving App Config...', 'working');
            try {
                const res = await fetchAuth(getAuthUrl('/api/app_config_structured'), { method: 'POST', body: new URLSearchParams({ data: JSON.stringify(appRules) }) });
                const txt = await res.text();
                if (res.ok) { notify('App Config Saved'); } else { notify('Save Failed: ' + txt, 'error'); }
            } catch (e) {
                console.error(e);
                notify('Error saving app config: ' + e.message, 'error');
            }
        }
        function toggleAddButton() {
            const btn = document.getElementById('btnAddRule'); const input = document.getElementById('appPkg');
            if (btn && input) {
                const pkg = input.value.trim();
                btn.disabled = !pkg;
                if (typeof appRules !== 'undefined') {
                    const exists = appRules.some(r => r.package === pkg);
                    btn.innerText = exists ? 'Update Rule' : 'Add Rule';
                }
            }
        }

        function applySelectedProfile(btn) {
            const sel = document.getElementById('profileSelect').value;
            if(!sel) { notify('Please select a profile first', 'error'); return; }
            requireConfirm(btn, () => {
                runWithState(btn, 'Applying...', () => applyProfile(sel));
            }, 'Confirm Apply', async () => {
                try {
                    const res = await fetchAuth(getAuthUrl('/api/config'));
                    if (!res.ok) throw new Error(await res.text());
                    const data = await res.json();
                    determineActiveProfile(data);
                } catch (e) {
                    notify('Error: ' + e.message, 'error');
                }
            });
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
                    const msg = await res.text(); notify('Error: ' + msg, 'error');
                }
            } catch (e) {
                notify('Error: ' + e.message, 'error');
            }
        }

        function determineActiveProfile(data) {
            const isMaximum = data.global_mode && !data.tee_broken_mode && data.random_on_boot && data.hide_sensitive_props && data.auto_keybox_check && data.telephony && !data.spoof_region_cn && !data.rkp_passthrough && !data.drm_passthrough;
            const isDaily = !data.global_mode && !data.tee_broken_mode && !data.random_on_boot && data.hide_sensitive_props && data.auto_keybox_check && !data.telephony && !data.spoof_region_cn && data.rkp_passthrough && data.drm_passthrough;
            const isMinimal = !data.global_mode && data.tee_broken_mode && !data.random_on_boot && !data.hide_sensitive_props && !data.auto_keybox_check && !data.telephony && !data.spoof_region_cn && data.rkp_passthrough && data.drm_passthrough;
            const isDefault = !data.global_mode && !data.tee_broken_mode && !data.random_on_boot && !data.hide_sensitive_props && data.auto_keybox_check && !data.telephony && !data.spoof_region_cn && data.rkp_passthrough && data.drm_passthrough;

            const select = document.getElementById('profileSelect');
            if (!select) return;
            if (isMaximum) select.value = 'maximum';
            else if (isDaily) select.value = 'daily';
            else if (isMinimal) select.value = 'minimal';
            else if (isDefault) select.value = 'default';
            else select.value = '';
        }

        async function reloadConfig() {
            try {
                const res = await fetchAuth('/api/reload', { method: 'POST' });
                if (!res.ok) throw new Error(await res.text());
                notify('Reloaded');
                setTimeout(() => window.location.reload(), 1000);
            } catch(e) {
                notify('Error: ' + e.message, 'error');
            }
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
            } catch(e) { notify('Error: ' + e.message, 'error'); return; }
        }
        async function backupConfig() {
            const pw = document.getElementById('backupPw') ? document.getElementById('backupPw').value : '';
            if (pw.length < 12) { notify('Backup password must be at least 12 characters', 'error'); return; }
            notify('Creating encrypted backup...', 'working');
            try {
                const formData = new FormData(); formData.append('pw', pw);
                const res = await fetchAuth('/api/backup', { method: 'POST', body: formData, timeoutMs: 120000 });
                if (res.ok) {
                    const blob = await res.blob();
                    downloadBlob(blob, 'cleverestricky_backup.ctsb');
                    notify('Encrypted backup saved');
                } else { notify('Backup failed: ' + await res.text(), 'error'); }
            } catch(e) { notify('Error: ' + e.message, 'error'); return; }
        }
        async function restoreConfig(input) {
            if (input.files && input.files[0]) {
                const file = input.files[0];
                const pw = document.getElementById('backupPw') ? document.getElementById('backupPw').value : '';
                if (!file.name.endsWith('.ctsb')) { notify('Only encrypted .ctsb backups are accepted', 'error'); input.value = ''; return; }
                if (pw.length < 12) { notify('Enter the backup password (at least 12 characters)', 'error'); input.value = ''; return; }
                const formData = new FormData(); formData.append('file', file);
                if (pw) formData.append('pw', pw);
                notify('Restoring...', 'working');
                try {
                    const res = await fetchAuth('/api/restore', { method: 'POST', body: formData, timeoutMs: 120000 });
                    if (res.ok) { notify('Success'); setTimeout(() => window.location.reload(), 1000); } else notify('Failed: ' + await res.text(), 'error');
                } catch (e) { notify('Error: ' + e.message, 'error'); }
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
            editor.disabled = true;
            editor.value = 'Loading...';
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
                    originalContent = '';
                    editor.value = 'Failed to load file.';
                    notify('Failed to load file', 'error');
                    return;
                }
            } catch(e){
                console.log('[CleveresTricky] loadFile:', f, 'error -', e.message);
                originalContent = '';
                editor.value = 'Error loading file.';
                notify('Error loading file', 'error');
                return;
            } finally {
                // Do not re-enable editor on failure to prevent saving the error message
                if (editor.value !== 'Error loading file.' && editor.value !== 'Failed to load file.') {
                    editor.disabled = false;
                }
            }
        }
        async function handleSave(btn) {
             btn.disabled = true; btn.innerText = 'Saving...'; notify('Saving...', 'working');
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
             } catch (e) {
                 notify('Error: ' + e.message, 'error');
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
            if(translations['tab_log']) document.getElementById('tab_log').innerText = translations['tab_log'];
        }

        async function loadResourceUsage() {
             try {
                 const tbody = document.getElementById('resourceBody');
                 if(tbody) tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; padding:20px; color:#888;"><div class="inline-spinner"></div> Loading...</td></tr>';
                 const res = await fetchAuth('/api/resource_usage');
                 if (!res.ok) throw new Error(await res.text());
                 const data = await res.json();
                 renderResourceTable(data);
             } catch(e) { console.error(e); notify('Error: ' + e.message, 'error'); return; }
        }

        function renderResourceTable(data) {
            const tbody = document.getElementById('resourceBody');
            if (!tbody) return;
            tbody.innerHTML = '';

            const summaryDiv = document.getElementById('resourceSummary');
            const ramMb = (data.real_ram_kb / 1024).toFixed(2);
            const cpu = data.real_cpu ? data.real_cpu.toFixed(1) : "0.0";
            const env = data.environment || "Unknown";

            if (summaryDiv) {
                summaryDiv.innerHTML = 
                    '<div class="resource-stat"><div style="font-size:0.8em; color:#888; text-transform:uppercase;">Environment</div><div style="font-size:1.2em; font-weight:bold; color:var(--accent);">' + escapeHtml(env) + '</div></div>' +
                    '<div class="resource-stat resource-stat-mid"><div style="font-size:0.8em; color:#888; text-transform:uppercase;">Est. CPU</div><div style="font-size:1.2em; font-weight:bold; color:var(--success);">' + escapeHtml(cpu) + '%</div></div>' +
                    '<div class="resource-stat"><div style="font-size:0.8em; color:#888; text-transform:uppercase;">Est. RAM</div><div style="font-size:1.2em; font-weight:bold; color:#60A5FA;">' + escapeHtml(ramMb) + ' MB</div></div>';
            }

            const features = [
                { id: 'global_mode', name: 'Global Mode', ram: 'Negligible', cpu: 'Conditional', sec: 'Medium', desc: 'Applies the attestation policy to every calling UID instead of target.txt rules.' },
                { id: 'tee_broken_mode', name: 'Certificate Safe Mode', ram: 'Negligible', cpu: 'Lower', sec: 'High', desc: 'Disables certificate substitution while leaving genuine KeyMint and RKP behavior intact.' },
                { id: 'auto_keybox_check', name: 'Automatic Keybox Check', ram: 'Small worker', cpu: 'Periodic', sec: 'High', desc: 'Checks active key material and revocation state in the background.' },
                { id: 'random_on_boot', name: 'Identity Refresh on Boot', ram: 'None retained', cpu: 'Boot only', sec: 'Medium', desc: 'Refreshes configured attestation and telephony identifiers during boot.' },
                { id: 'telephony', name: 'Telephony Interception', ram: 'Process dependent', cpu: 'Low', sec: 'Medium', desc: 'Optionally intercepts supported telephony identifier calls; requires a reboot.' },
                { id: 'rkp_passthrough', name: 'RKP Passthrough', ram: 'Negligible', cpu: 'Lower', sec: 'High', desc: 'Leaves generated-key responses on the original platform path.' },
                { id: 'drm_passthrough', name: 'DRM App Passthrough', ram: 'Bounded UID cache', cpu: 'Low', sec: 'High', desc: 'Excludes packages in drm_packages.txt from certificate substitution.' },
                { id: 'hide_sensitive_props', name: 'Hide Sensitive Properties', ram: 'None retained', cpu: 'Boot only', sec: 'Medium', desc: 'Applies the selected boot-property policy after reboot.' },
                { id: 'spoof_region_cn', name: 'CN Region Compatibility', ram: 'None retained', cpu: 'Boot only', sec: 'Medium', desc: 'Applies the optional region property set after reboot.' },
                { id: 'keybox_storage', name: 'Keybox Storage', ram: 'Bounded cache', cpu: 'Low', sec: 'Sensitive', desc: data.keybox_count + ' authorized keyboxes loaded from root-only storage.' },
                { id: 'app_rules', name: 'App Rules', ram: data.app_config_size + ' B config', cpu: 'Low', sec: 'Low', desc: 'Target-specific identity and keybox selection rules.' }
            ];

            features.forEach(f => {
                const tr = document.createElement('tr');
                const isToggleable = WEB_UI_SETTINGS.includes(f.id);
                let statusHtml = '';

                if (isToggleable) {
                    const isChecked = data[f.id] ? 'checked' : '';
                    statusHtml = '<input type="checkbox" class="toggle" id="res_toggle_' + f.id + '" data-setting="' + f.id + '" aria-label="Toggle ' + escapeHtml(f.name) + '" ' + isChecked + ' onchange="toggle(\'' + f.id + '\', this)">';
                } else {
                    statusHtml = '<span style="color:#888;">Info Only</span>';
                }

                let secColor = f.sec === 'Critical' ? 'var(--danger)' : ((f.sec === 'High' || f.sec === 'Sensitive') ? 'orange' : (f.sec === 'Medium' ? '#FACC15' : 'var(--success)'));

                // Single row layout for responsive design
                tr.innerHTML =
                    '<td data-label="' + escapeHtml(t('col_feature', 'Feature')) + '"><div><div>' + escapeHtml(f.name) + '</div><div class="res-desc">' + escapeHtml(f.desc) + '</div></div></td>' +
                    '<td data-label="' + escapeHtml(t('col_status', 'Status')) + '">' + statusHtml + '</td>' +
                    '<td data-label="' + escapeHtml(t('col_ram', 'Est. RAM')) + '" style="font-family:monospace;">' + escapeHtml(f.ram) + '</td>' +
                    '<td data-label="' + escapeHtml(t('col_cpu', 'Est. CPU')) + '">' + escapeHtml(f.cpu) + '</td>' +
                    '<td data-label="' + escapeHtml(t('col_security', 'Security Impact')) + '" style="color:' + secColor + '; font-weight:bold;">' + escapeHtml(f.sec) + '</td>';

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
                "tab_spoof": "Identity",
                "tab_apps": "Apps",
                "tab_keys": "Keyboxes",
                "tab_info": "Info & Resources",
                "tab_guide": "Guide",
                "tab_editor": "Editor",
                "tab_donate": "Donate",
                "tab_log": "Logs"
            };
            const blob = new Blob([JSON.stringify(template, null, 2)], {type: "application/json"});
            downloadBlob(blob, 'lang.json');
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

        window.addEventListener('dragenter', preventDefaults, false);
        window.addEventListener('dragover', preventDefaults, false);
        window.addEventListener('dragleave', preventDefaults, false);
        window.addEventListener('drop', preventDefaults, false);


        function highlight(e) {
            dropZone.classList.add('drag-over');
        }

        function unhighlight(e) {
            dropZone.classList.remove('drag-over');
        }

        function handleDrop(e) {
            const dt = e.dataTransfer;
            const files = dt.files;
            if (files && files[0]) loadFileContent(files[0]);
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
            devFooter.innerHTML = `<span style="color:var(--accent); font-weight:bold;">BETA / DEV BUILD</span><br><br>This module is currently a development build. For the stable version, please download the <a href="https://github.com/tryigit/CleveresTricky/releases" style="display:inline-flex; align-items:center; justify-content:center; min-height:44px; min-width:44px; color:var(--accent);" target="_blank" rel="noopener noreferrer">Stable Build (GitHub Releases)</a>.`;
            document.body.appendChild(devFooter);
        }
    </script>
</body>
</html>
        """.trimIndent()
    }

    companion object {
        private const val MAX_UPLOAD_SIZE = 10 * 1024 * 1024L
        private const val MAX_BODY_SIZE = 5 * 1024 * 1024L
        private const val MAX_CONFIG_FILE_SIZE = 1024 * 1024L
        private const val MAX_LOG_BYTES = 2 * 1024 * 1024
        private const val RATE_LIMIT = 100
        private const val RATE_WINDOW = 60 * 1000L
        private const val MAX_BACKUP_ENTRIES = 128
        private const val MAX_BACKUP_KEYBOXES = 64
        private const val MAX_BACKUP_CONFIG_ENTRY_BYTES = 1024 * 1024
        private const val MAX_BACKUP_KEYBOX_ENTRY_BYTES = 10 * 1024 * 1024
        private const val MAX_BACKUP_UNCOMPRESSED_BYTES = 16 * 1024 * 1024
        private const val MAX_SECURITY_PATCH_RULES = 512
        private const val MAX_DRM_PACKAGE_RULES = 256
        private const val MAX_APP_CONFIG_RULES = 1024
        private const val MAX_TARGET_RULES = 2048
        private const val MAX_TEMPLATES = 128
        private const val MAX_TEMPLATE_FIELD_LENGTH = 512
        private const val MAX_DRM_PACKAGES_BYTES = 64 * 1024
        private val SECURITY_PATCH_COMPONENTS = setOf("all", "system", "vendor", "boot")
        private val VALID_TEMPLATE_ID = Regex("[a-z0-9_-]{1,64}")
        private val CUSTOM_TEMPLATE_PROPERTIES =
            setOf("BRAND", "DEVICE", "PRODUCT", "MANUFACTURER", "MODEL")
        private val WEB_UI_SETTINGS =
            linkedSetOf(
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
        private val EDITABLE_CONFIG_FILES =
            setOf(
                "target.txt",
                "security_patch.txt",
                "spoof_build_vars",
                "app_config",
                "templates.json",
                "drm_packages.txt",
                "boot_props_mode",
            )
        private val BACKUP_CONFIG_FILES =
            setOf(
                "target.txt",
                "security_patch.txt",
                "spoof_build_vars",
                "app_config",
                "templates.json",
                "custom_templates",
                "global_mode",
                "tee_broken_mode",
                "auto_keybox_check",
                "random_on_boot",
                "hide_sensitive_props",
                "spoof_region_cn",
                "telephony",
                "rkp_passthrough",
                "drm_passthrough",
                "drm_packages.txt",
                "boot_props_mode",
            )

        fun getSafeFile(
            baseDir: File,
            requestedPath: String,
        ): File? {
            val targetFile = File(baseDir, requestedPath)
            return try {
                val canonicalBase = baseDir.canonicalPath
                val canonicalTarget = targetFile.canonicalPath
                if (canonicalTarget.equals(canonicalBase) || canonicalTarget.startsWith(canonicalBase + File.separator)) {
                    targetFile
                } else {
                    Logger.e("Path Traversal attempt prevented! Target: $canonicalTarget")
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        fun isValidPkg(s: String): Boolean {
            if (s.length !in 1..255) return false
            for (i in 0 until s.length) {
                val c = s[i]
                if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '*')) {
                    return false
                }
            }
            return true
        }

        fun isValidTemplate(s: String): Boolean {
            if (s.length !in 1..64) return false
            for (i in 0 until s.length) {
                val c = s[i]
                if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-')) {
                    return false
                }
            }
            return true
        }

        fun isValidKeybox(s: String): Boolean {
            if (s.length !in 1..128) return false
            for (i in 0 until s.length) {
                val c = s[i]
                if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '-')) {
                    return false
                }
            }
            return true
        }

        fun isSafeHost(host: String?): Boolean {
            val value = host?.trim()?.lowercase() ?: return false
            val address: String
            val port: String?
            if (value.startsWith("[")) {
                val closingBracket = value.indexOf(']')
                if (closingBracket <= 1) return false
                address = value.substring(1, closingBracket)
                val suffix = value.substring(closingBracket + 1)
                if (suffix.isEmpty()) {
                    port = null
                } else {
                    if (!suffix.startsWith(':')) return false
                    port = suffix.substring(1)
                }
                if (address != "::1" && address != "0:0:0:0:0:0:0:1") return false
            } else {
                val separator = value.indexOf(':')
                if (separator < 0) {
                    address = value
                    port = null
                } else {
                    if (value.indexOf(':', separator + 1) >= 0) return false
                    address = value.substring(0, separator)
                    port = value.substring(separator + 1)
                }
                if (address != "localhost" && address != "127.0.0.1") return false
            }
            return port == null || port.toIntOrNull()?.let { it in 1..65535 } == true
        }

        fun isSafePath(
            configDir: File,
            file: File,
        ): Boolean {
            return try {
                val configCanonical = configDir.canonicalPath
                val fileCanonical = file.canonicalPath
                fileCanonical.equals(configCanonical) || fileCanonical.startsWith(configCanonical + File.separator)
            } catch (e: Exception) {
                false
            }
        }

        fun isValidFilename(name: String): Boolean {
            return cleveres.tricky.cleverestech.isValidFilename(name)
        }

        fun validateContent(
            filename: String,
            content: String,
        ): Boolean {
            // Basic validation based on known file types
            if (filename == "target.txt") {
                var ruleCount = 0
                val lines = content.lineSequence()
                return lines.all {
                    it.isEmpty() || it.startsWith("#") ||
                        (++ruleCount <= MAX_TARGET_RULES && isValidTargetPkg(it))
                }
            }
            if (filename == "drm_packages.txt") {
                if (content.toByteArray(Charsets.UTF_8).size > MAX_DRM_PACKAGES_BYTES) return false
                var ruleCount = 0
                val lines = content.lineSequence()
                return lines.all { line ->
                    val value = line.trim()
                    value.isEmpty() || value.startsWith("#") ||
                        (++ruleCount <= MAX_DRM_PACKAGE_RULES && isValidPkg(value))
                }
            }
            if (filename == "boot_props_mode") {
                return content.trim().lowercase() in setOf("auto", "force", "disable")
            }
            if (filename == "security_patch.txt") {
                var inPackageSection = false
                var ruleCount = 0
                return content.lineSequence().all { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@all true
                    if (++ruleCount > MAX_SECURITY_PATCH_RULES) return@all false

                    if (line.startsWith("[") && line.endsWith("]")) {
                        val packageName = line.substring(1, line.lastIndex).trim()
                        inPackageSection = true
                        return@all packageName.length <= 255 && isValidPkg(packageName)
                    }

                    val separator = line.indexOf('=')
                    if (separator < 0) {
                        return@all isValidSecurityPatchValue(line, allowSpecial = true)
                    }

                    val key = line.substring(0, separator).trim()
                    val value = line.substring(separator + 1).trim()
                    if (key.isEmpty() || value.isEmpty()) return@all false
                    if (key in SECURITY_PATCH_COMPONENTS) {
                        return@all isValidSecurityPatchValue(value, allowSpecial = true)
                    }
                    !inPackageSection &&
                        key.length <= 255 &&
                        isValidPkg(key) &&
                        isValidSecurityPatchValue(value, allowSpecial = false)
                }
            }
            if (filename == "spoof_build_vars") {
                val lines = content.lineSequence()
                return lines.all { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@all true
                    // Must be KEY=VALUE format
                    if (!isValidKeyValue(trimmed)) return@all false
                    // Value part security check
                    val idx = trimmed.indexOf('=')
                    if (idx == -1) return@all false
                    val key = trimmed.substring(0, idx).trim()
                    val value = trimmed.substring(idx + 1).trim()
                    // Check for unsafe shell chars
                    isValidSafeBuildVarValue(value) && Config.isValidBuildVarEntry(key, value)
                }
            }
            if (filename == "app_config") {
                var ruleCount = 0
                val lines = content.lineSequence()
                return lines.all { line ->
                    if (line.isBlank() || line.startsWith("#")) return@all true
                    if (++ruleCount > MAX_APP_CONFIG_RULES) return@all false

                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@all true

                    val len = trimmed.length
                    var idx = 0

                    var start = idx
                    while (idx < len && !trimmed[idx].isWhitespace()) idx++
                    val pkg = trimmed.substring(start, idx)

                    if (!isValidPkg(pkg)) return@all false

                    while (idx < len && trimmed[idx].isWhitespace()) idx++
                    if (idx < len) {
                        start = idx
                        while (idx < len && !trimmed[idx].isWhitespace()) idx++
                        val tmplStr = trimmed.substring(start, idx)
                        if (tmplStr != "null" && !isValidTemplate(tmplStr)) return@all false

                        while (idx < len && trimmed[idx].isWhitespace()) idx++
                        if (idx < len) {
                            start = idx
                            while (idx < len && !trimmed[idx].isWhitespace()) idx++
                            val kbStr = trimmed.substring(start, idx)
                            if (kbStr != "null" && !isValidKeybox(kbStr)) return@all false

                            while (idx < len && trimmed[idx].isWhitespace()) idx++
                            if (idx < len) return@all false
                        }
                    }

                    true
                }
            }
            if (filename == "templates.json") {
                return runCatching {
                    val array = JSONArray(content)
                    require(array.length() <= MAX_TEMPLATES)
                    val requiredFields =
                        listOf(
                            "id",
                            "manufacturer",
                            "model",
                            "fingerprint",
                            "brand",
                            "product",
                            "device",
                            "release",
                            "buildId",
                            "incremental",
                            "securityPatch",
                        )
                    val seenIds = HashSet<String>()
                    for (index in 0 until array.length()) {
                        val template = array.getJSONObject(index)
                        val id = template.getString("id").trim().lowercase()
                        require(VALID_TEMPLATE_ID.matches(id) && seenIds.add(id))
                        requiredFields.forEach { field ->
                            val value = template.getString(field)
                            require(
                                value.isNotBlank() &&
                                    value.length <= MAX_TEMPLATE_FIELD_LENGTH &&
                                    value.none(Char::isISOControl),
                            )
                        }
                        listOf("type", "tags").forEach { field ->
                            val value = template.optString(field, "user")
                            require(
                                value.isNotBlank() &&
                                    value.length <= MAX_TEMPLATE_FIELD_LENGTH &&
                                    value.none(Char::isISOControl),
                            )
                        }
                        template.getString("securityPatch").convertPatchLevel(false)
                    }
                    true
                }.getOrDefault(false)
            }
            if (filename == "custom_templates") {
                var sectionCount = 0
                var hasCurrentTemplate = false
                return content.lineSequence().all { rawLine ->
                    val value = rawLine.trim()
                    if (value.isEmpty() || value.startsWith("#")) return@all true

                    if (value.startsWith("[") && value.endsWith("]")) {
                        val name = value.substring(1, value.lastIndex).trim().lowercase()
                        if (!VALID_TEMPLATE_ID.matches(name) || ++sectionCount > MAX_TEMPLATES) {
                            return@all false
                        }
                        hasCurrentTemplate = true
                        return@all true
                    }

                    if (!hasCurrentTemplate) return@all false
                    val separator = value.indexOf('=')
                    if (separator !in 1 until value.lastIndex) return@all false
                    val key = value.substring(0, separator).trim()
                    val propertyValue = value.substring(separator + 1).trim()
                    key in CUSTOM_TEMPLATE_PROPERTIES &&
                        propertyValue.isNotEmpty() &&
                        propertyValue.length <= MAX_TEMPLATE_FIELD_LENGTH &&
                        propertyValue.none(Char::isISOControl)
                }
            }
            return false
        }

        fun createBackupZip(configDir: File): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                var totalBytes = 0L
                BACKUP_CONFIG_FILES.sorted().forEach { name ->
                    val file = File(configDir, name)
                    if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
                    val size = file.length()
                    if (size !in 0..MAX_BACKUP_CONFIG_ENTRY_BYTES.toLong()) {
                        throw IOException("Backup entry exceeds size limit: $name")
                    }
                    totalBytes += size
                    if (totalBytes > MAX_BACKUP_UNCOMPRESSED_BYTES) {
                        throw IOException("Backup exceeds uncompressed size limit")
                    }
                    zos.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }

                val keyboxDir = File(configDir, "keyboxes")
                if (Files.isDirectory(keyboxDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    val keyboxes =
                        keyboxDir.listFiles { file ->
                            (file.name.endsWith(".xml", ignoreCase = true) ||
                                file.name.endsWith(".cbox", ignoreCase = true)) &&
                                isValidKeyboxBackupPath("keyboxes/${file.name}") &&
                                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                        }?.sortedBy { it.name }.orEmpty()
                    if (keyboxes.size > MAX_BACKUP_KEYBOXES) {
                        throw IOException("Backup contains too many keyboxes")
                    }
                    keyboxes.forEach { keybox ->
                        val size = keybox.length()
                        if (size !in 1..MAX_BACKUP_KEYBOX_ENTRY_BYTES.toLong()) {
                            throw IOException("Keybox exceeds size limit: ${keybox.name}")
                        }
                        totalBytes += size
                        if (totalBytes > MAX_BACKUP_UNCOMPRESSED_BYTES) {
                            throw IOException("Backup exceeds uncompressed size limit")
                        }
                        zos.putNextEntry(ZipEntry("keyboxes/${keybox.name}"))
                        keybox.inputStream().use { it.copyTo(zos) }
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

        fun restoreBackupZip(
            configDir: File,
            inputStream: InputStream,
        ) {
            val staged = LinkedHashMap<String, ByteArray>()
            var totalBytes = 0
            var keyboxCount = 0
            try {
                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (staged.size >= MAX_BACKUP_ENTRIES) {
                            throw IOException("Backup contains too many entries")
                        }
                        val name = entry.name
                        val allowed = name in BACKUP_CONFIG_FILES || isValidKeyboxBackupPath(name)
                        if (entry.isDirectory || !allowed || staged.containsKey(name)) {
                            throw SecurityException("Unsupported or duplicate backup entry: $name")
                        }
                        if (isValidKeyboxBackupPath(name) && ++keyboxCount > MAX_BACKUP_KEYBOXES) {
                            throw IOException("Backup contains too many keyboxes")
                        }

                        val entryLimit =
                            if (isValidKeyboxBackupPath(name)) {
                                MAX_BACKUP_KEYBOX_ENTRY_BYTES
                            } else {
                                MAX_BACKUP_CONFIG_ENTRY_BYTES
                            }
                        val bytes = readZipEntry(zis, entryLimit)
                        totalBytes += bytes.size
                        if (totalBytes > MAX_BACKUP_UNCOMPRESSED_BYTES) {
                            bytes.fill(0)
                            throw IOException("Backup exceeds uncompressed size limit")
                        }
                        validateBackupEntry(name, bytes)
                        staged[name] = bytes
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                if (staged.isEmpty()) throw IOException("Backup is empty")

                staged.forEach { (name, bytes) ->
                    val file =
                        getSafeFile(configDir, name)
                            ?: throw SecurityException("Backup path escaped the configuration directory: $name")
                    if (Files.isSymbolicLink(file.toPath())) {
                        throw SecurityException("Refusing symbolic-link backup destination: $name")
                    }
                    if (name.startsWith("keyboxes/")) {
                        SecureFile.mkdirs(File(configDir, "keyboxes"), 448)
                    }
                    SecureFile.writeStream(
                        file,
                        ByteArrayInputStream(bytes),
                        if (isValidKeyboxBackupPath(name)) {
                            MAX_BACKUP_KEYBOX_ENTRY_BYTES.toLong()
                        } else {
                            MAX_BACKUP_CONFIG_ENTRY_BYTES.toLong()
                        },
                    )
                }
            } finally {
                staged.values.forEach { it.fill(0) }
            }
        }

        private fun isValidKeyboxBackupPath(name: String): Boolean {
            if (!name.startsWith("keyboxes/") || name.count { it == '/' } != 1) return false
            val filename = name.substringAfter('/')
            val lower = filename.lowercase()
            return (lower.endsWith(".xml") || lower.endsWith(".cbox")) &&
                filename.length in 5..128 &&
                !filename.startsWith('.') &&
                filename.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        }

        private fun readZipEntry(
            input: InputStream,
            maxBytes: Int,
        ): ByteArray {
            val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count > maxBytes - total) throw IOException("Backup entry exceeds size limit")
                output.write(buffer, 0, count)
                total += count
            }
            return output.toByteArray()
        }

        private fun validateBackupEntry(
            name: String,
            bytes: ByteArray,
        ) {
            if (name.endsWith(".cbox", ignoreCase = true)) {
                if (!CboxDecryptor.hasSupportedEnvelopeHeader(bytes)) {
                    throw IOException("Backup CBOX has an invalid envelope: $name")
                }
                return
            }
            val content = bytes.toString(Charsets.UTF_8)
            if (!content.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
                throw IOException("Backup entry is not valid UTF-8: $name")
            }
            if (name.startsWith("keyboxes/")) {
                if (CertHack.parseKeyboxXml(StringReader(content), name).isEmpty()) {
                    throw IOException("Backup keybox is empty: $name")
                }
            } else if (!validateContent(name, content)) {
                throw IOException("Backup configuration is invalid: $name")
            }
        }
    }
}
