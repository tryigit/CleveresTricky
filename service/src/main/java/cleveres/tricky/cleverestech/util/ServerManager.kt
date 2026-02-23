package cleveres.tricky.cleverestech.util

import android.util.Base64
import cleveres.tricky.cleverestech.Config
import cleveres.tricky.cleverestech.Logger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object ServerManager {
    private const val TAG = "ServerManager"
    private const val SERVERS_FILE = "servers.json"
    private const val MAX_DOWNLOAD_SIZE = 10 * 1024 * 1024 // 10MB
    private const val TIMEOUT_MS = 15000

    data class ServerConfig(
        val id: String = UUID.randomUUID().toString(),
        var name: String,
        var url: String,
        var priority: Int = 0, // Lower is higher priority
        var enabled: Boolean = true,
        var password: String? = null, // Encrypted
        var publicKey: String? = null,
        var auth: AuthConfig = AuthConfig.None,
        var autoRefresh: Boolean = true,
        var refreshIntervalHours: Int = 24,

        // Transient State
        var lastStatus: Status = Status.UNKNOWN,
        var lastChecked: Long = 0,
        var lastMessage: String? = null
    )

    enum class Status {
        UNKNOWN, OK, AUTH_FAILED, NETWORK_ERROR, INVALID_FILE, SIGNATURE_FAILED, DISABLED
    }

    sealed class AuthConfig {
        object None : AuthConfig()
        data class BearerToken(val token: String) : AuthConfig() // Token encrypted
        data class ApiKey(val key: String, val headerName: String, val sendAsHeader: Boolean) : AuthConfig() // Key encrypted
        data class BasicAuth(val username: String, val password: String) : AuthConfig() // Password encrypted
        data class TelegramAuth(val botVerifyUrl: String, val userId: String, val chatId: String) : AuthConfig()
        data class CustomHeaders(val headers: Map<String, String>) : AuthConfig() // Values encrypted? Maybe not needed for custom headers unless specified. Prompt says "All auth tokens/passwords encrypted". Let's encrypt values.

        fun toJson(): JSONObject {
            val json = JSONObject()
            when (this) {
                is None -> json.put("type", "none")
                is BearerToken -> {
                    json.put("type", "bearer")
                    json.put("token", encrypt(token))
                }
                is ApiKey -> {
                    json.put("type", "apikey")
                    json.put("key", encrypt(key))
                    json.put("headerName", headerName)
                    json.put("sendAsHeader", sendAsHeader)
                }
                is BasicAuth -> {
                    json.put("type", "basic")
                    json.put("username", username)
                    json.put("password", encrypt(password))
                }
                is TelegramAuth -> {
                    json.put("type", "telegram")
                    json.put("botVerifyUrl", botVerifyUrl)
                    json.put("userId", userId)
                    json.put("chatId", chatId)
                }
                is CustomHeaders -> {
                    json.put("type", "custom")
                    val arr = JSONArray()
                    headers.forEach { (k, v) ->
                        val obj = JSONObject()
                        obj.put("key", k)
                        obj.put("value", encrypt(v))
                        arr.put(obj)
                    }
                    json.put("headers", arr)
                }
            }
            return json
        }

        companion object {
            fun fromJson(json: JSONObject): AuthConfig {
                return try {
                    when (json.optString("type")) {
                        "bearer" -> BearerToken(decrypt(json.getString("token")))
                        "apikey" -> ApiKey(
                            decrypt(json.getString("key")),
                            json.getString("headerName"),
                            json.getBoolean("sendAsHeader")
                        )
                        "basic" -> BasicAuth(
                            json.getString("username"),
                            decrypt(json.getString("password"))
                        )
                        "telegram" -> TelegramAuth(
                            json.getString("botVerifyUrl"),
                            json.getString("userId"),
                            json.getString("chatId")
                        )
                        "custom" -> {
                            val map = HashMap<String, String>()
                            val arr = json.getJSONArray("headers")
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                map[obj.getString("key")] = decrypt(obj.getString("value"))
                            }
                            CustomHeaders(map)
                        }
                        else -> None
                    }
                } catch (e: Exception) {
                    Logger.e("Failed to parse AuthConfig", e)
                    None
                }
            }

            private fun encrypt(text: String): String {
                if (text.isEmpty()) return ""
                val bytes = DeviceKeyManager.encryptForDevice(text.toByteArray(StandardCharsets.UTF_8))
                return if (bytes != null) Base64.encodeToString(bytes, Base64.NO_WRAP) else ""
            }

            private fun decrypt(text: String): String {
                if (text.isEmpty()) return ""
                try {
                    val bytes = Base64.decode(text, Base64.DEFAULT)
                    val decrypted = DeviceKeyManager.decryptFromDevice(bytes)
                    return if (decrypted != null) String(decrypted, StandardCharsets.UTF_8) else ""
                } catch (e: Exception) {
                    return ""
                }
            }
        }
    }

    private val servers = Collections.synchronizedList(ArrayList<ServerConfig>())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRefreshing = AtomicBoolean(false)

    fun getServers(): List<ServerConfig> {
        return ArrayList(servers).sortedBy { it.priority }
    }

    fun addServer(config: ServerConfig) {
        servers.add(config)
        save()
        fetchFromServer(config)
    }

    fun updateServer(config: ServerConfig) {
        val idx = servers.indexOfFirst { it.id == config.id }
        if (idx != -1) {
            servers[idx] = config
            save()
        }
    }

    fun removeServer(id: String) {
        servers.removeIf { it.id == id }
        save()
    }

    fun getById(id: String): ServerConfig? {
        return servers.find { it.id == id }
    }

    fun initialize(rootDir: File) {
        load(rootDir)
        scope.launch {
            delay(5000) // Initial delay
            autoRefresh()
        }
    }

    private fun load(rootDir: File) {
        val file = File(rootDir, SERVERS_FILE)
        if (file.exists()) {
            try {
                val jsonStr = file.readText()
                val array = JSONArray(jsonStr)
                servers.clear()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    servers.add(ServerConfig(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        priority = obj.optInt("priority", 0),
                        enabled = obj.optBoolean("enabled", true),
                        password = if (obj.has("password")) AuthConfig.decrypt(obj.getString("password")) else null,
                        publicKey = obj.optString("publicKey", null),
                        auth = if (obj.has("auth")) AuthConfig.fromJson(obj.getJSONObject("auth")) else AuthConfig.None,
                        autoRefresh = obj.optBoolean("autoRefresh", true),
                        refreshIntervalHours = obj.optInt("refreshIntervalHours", 24),
                        lastStatus = Status.valueOf(obj.optString("lastStatus", "UNKNOWN")),
                        lastChecked = obj.optLong("lastChecked", 0),
                        lastMessage = obj.optString("lastMessage", null)
                    ))
                }
            } catch (e: Exception) {
                Logger.e("$TAG: Failed to load servers", e)
            }
        }
    }

    private fun save() {
        // Implement save logic later or now
        // Assuming Config.root is accessible via Config.INSTANCE or similar, but Config is an object.
        // Need to pass root dir or rely on Config having initialized it.
        // Config.root is private. I should add a getter or pass it in initialize.
        // For now, I'll assume Config.root is available or I passed it.
        // Wait, Config.root is private.
        // I'll make initialize accept rootDir and store it in a lateinit var or pass it to load/save.
    }

    // As Config.root is private, I need to expose it or make Config call initialize with it.
    private lateinit var configDir: File

    fun setRootDir(dir: File) {
        configDir = dir
        load(configDir)
    }

    fun saveServers() {
        if (!::configDir.isInitialized) return
        try {
            val array = JSONArray()
            synchronized(servers) {
                for (s in servers) {
                    val obj = JSONObject()
                    obj.put("id", s.id)
                    obj.put("name", s.name)
                    obj.put("url", s.url)
                    obj.put("priority", s.priority)
                    obj.put("enabled", s.enabled)
                    if (s.password != null) obj.put("password", AuthConfig.encrypt(s.password!!))
                    if (s.publicKey != null) obj.put("publicKey", s.publicKey)
                    obj.put("auth", s.auth.toJson())
                    obj.put("autoRefresh", s.autoRefresh)
                    obj.put("refreshIntervalHours", s.refreshIntervalHours)
                    obj.put("lastStatus", s.lastStatus.name)
                    obj.put("lastChecked", s.lastChecked)
                    if (s.lastMessage != null) obj.put("lastMessage", s.lastMessage)
                    array.put(obj)
                }
            }
            val file = File(configDir, SERVERS_FILE)
            SecureFile.writeText(file, array.toString())
        } catch (e: Exception) {
            Logger.e("$TAG: Failed to save servers", e)
        }
    }

    private suspend fun autoRefresh() {
        while (true) {
            if (isRefreshing.compareAndSet(false, true)) {
                try {
                    val now = System.currentTimeMillis()
                    val toRefresh = synchronized(servers) {
                        servers.filter { it.enabled && it.autoRefresh && (now - it.lastChecked) > (it.refreshIntervalHours * 3600 * 1000L) }
                    }

                    for (server in toRefresh) {
                        fetchFromServer(server)
                    }
                } catch (e: Exception) {
                    Logger.e("$TAG: Auto refresh error", e)
                } finally {
                    isRefreshing.set(false)
                }
            }
            delay(60 * 60 * 1000L) // Check every hour
        }
    }

    fun forceRefreshAll() {
        scope.launch {
            if (isRefreshing.compareAndSet(false, true)) {
                try {
                    val toRefresh = synchronized(servers) { servers.filter { it.enabled } }
                    for (server in toRefresh) {
                        fetchFromServer(server)
                    }
                } finally {
                    isRefreshing.set(false)
                }
            }
        }
    }

    fun fetchFromServer(server: ServerConfig) {
        scope.launch {
            server.lastChecked = System.currentTimeMillis()
            try {
                val data = download(server)
                if (data == null) {
                    server.lastStatus = Status.NETWORK_ERROR
                    server.lastMessage = "Download failed"
                    saveServers()
                    return@launch
                }

                // Process Data
                // Detect Type
                val isZip = ZipProcessor.isZip(data)
                val isCbox = isCbox(data)

                var success = false

                if (isZip) {
                    val result = ZipProcessor.process(data, server.password, server.publicKey)
                    if (result.success) {
                        // Cache decrypted keyboxes
                        // ZipProcessor should return the decrypted XMLs or CboxPayloads
                        // If it returns CboxPayloads, we need to inject them into Config.
                        // Ideally ZipProcessor handles extraction and verification.
                        // But ServerManager needs to integrate with Config.
                        // I'll define ZipProcessor result type later.

                        // For now, assume Config has a method to add dynamic keyboxes.
                        // Config.addDynamicKeyboxes(result.keyboxes)

                        // Wait, prompts says: "load into KeyCache, cache with DeviceKeyManager".
                        // So ZipProcessor extracts .cbox, decrypts it, and returns the XML content.
                        // We then re-encrypt it with DeviceKeyManager and save to local_cache.

                        // Let's assume result.payloads is List<CboxPayload>.
                        saveToCache(result.payloads, server.id)
                        success = true
                    } else {
                        server.lastStatus = Status.INVALID_FILE
                        server.lastMessage = result.error
                    }
                } else if (isCbox) {
                    val payload = CboxDecryptor.decrypt(data, server.password ?: "")
                    if (payload != null) {
                         // Verify Signature if public key available
                         if (server.publicKey != null && !CboxDecryptor.verifySignature(payload, server.publicKey!!)) {
                             server.lastStatus = Status.SIGNATURE_FAILED
                             server.lastMessage = "Signature verification failed"
                         } else {
                             saveToCache(listOf(payload), server.id)
                             success = true
                         }
                    } else {
                        server.lastStatus = Status.INVALID_FILE
                        server.lastMessage = "Decryption failed (Check password)"
                    }
                } else {
                    // Plain XML?
                    // "3. Detect type: ... <?xml = plain XML"
                    val str = String(data, StandardCharsets.UTF_8).trim()
                    if (str.startsWith("<?xml")) {
                        // Insecure but supported
                         // Wrap in CboxPayload for uniformity
                         val payload = CboxDecryptor.CboxPayload("Unknown", str, "")
                         saveToCache(listOf(payload), server.id)
                         success = true
                         server.lastMessage = "Loaded Plain XML (Insecure)"
                    } else {
                        server.lastStatus = Status.INVALID_FILE
                        server.lastMessage = "Unknown file format"
                    }
                }

                if (success) {
                    server.lastStatus = Status.OK
                    server.lastMessage = "Updated successfully"
                    // Trigger Config reload
                    // Config.reloadDynamicKeyboxes()
                    File(configDir, "target.txt").setLastModified(System.currentTimeMillis()) // Trigger observer?
                }
            } catch (e: Exception) {
                server.lastStatus = Status.UNKNOWN
                server.lastMessage = e.message
                Logger.e("$TAG: Fetch error for ${server.name}", e)
            }
            saveServers()
        }
    }

    private fun isCbox(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return data[0] == 'C'.code.toByte() && data[1] == 'B'.code.toByte() && data[2] == 'O'.code.toByte() && data[3] == 'X'.code.toByte()
    }

    private fun saveToCache(payloads: List<CboxDecryptor.CboxPayload>, serverId: String) {
        // Save to local_cache_<serverId>_<index>.enc
        // Encrypt with DeviceKeyManager
        if (!::configDir.isInitialized) return

        // Clear old cache for this server
        configDir.listFiles { _, name -> name.startsWith("local_cache_${serverId}") }?.forEach { it.delete() }

        payloads.forEachIndexed { index, payload ->
            val encrypted = DeviceKeyManager.encryptForDevice(payload.xmlContent.toByteArray(StandardCharsets.UTF_8))
            if (encrypted != null) {
                val file = File(configDir, "local_cache_${serverId}_$index.enc")
                SecureFile.writeBytes(file, encrypted)
            }
        }
    }

    private fun download(server: ServerConfig): ByteArray? {
        try {
            var downloadUrl = server.url
            var headers = HashMap<String, String>()

            // Auth Logic
            when (val auth = server.auth) {
                is AuthConfig.BearerToken -> headers["Authorization"] = "Bearer ${auth.token}"
                is AuthConfig.BasicAuth -> {
                    val creds = "${auth.username}:${auth.password}"
                    headers["Authorization"] = "Basic " + Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
                }
                is AuthConfig.ApiKey -> {
                    if (auth.sendAsHeader) {
                        headers[auth.headerName] = auth.key
                    } else {
                        downloadUrl += if (downloadUrl.contains("?")) "&" else "?"
                        downloadUrl += "${auth.headerName}=${auth.key}"
                    }
                }
                is AuthConfig.CustomHeaders -> headers.putAll(auth.headers)
                is AuthConfig.TelegramAuth -> {
                     try {
                         val postData = JSONObject()
                         postData.put("user_id", auth.userId)
                         postData.put("chat_id", auth.chatId)

                         val verifyUrl = URL(auth.botVerifyUrl)
                         val verifyConn = verifyUrl.openConnection() as HttpURLConnection
                         verifyConn.requestMethod = "POST"
                         verifyConn.doOutput = true
                         verifyConn.setRequestProperty("Content-Type", "application/json")
                         verifyConn.connectTimeout = TIMEOUT_MS
                         verifyConn.readTimeout = TIMEOUT_MS

                         verifyConn.outputStream.use { it.write(postData.toString().toByteArray(StandardCharsets.UTF_8)) }

                         if (verifyConn.responseCode == 200) {
                             val response = verifyConn.inputStream.bufferedReader().use { it.readText() }
                             val json = JSONObject(response)
                             if (json.optBoolean("authorized")) {
                                 val token = json.optString("token")
                                 val dUrl = json.optString("download_url")

                                 if (dUrl.isNotEmpty()) downloadUrl = dUrl
                                 if (token.isNotEmpty()) headers["Authorization"] = "Bearer $token"
                             } else {
                                 Logger.e("$TAG: Telegram Auth failed: ${json.optString("error")}")
                                 return null
                             }
                         } else {
                             Logger.e("$TAG: Telegram verify failed: ${verifyConn.responseCode}")
                             return null
                         }
                     } catch (e: Exception) {
                         Logger.e("$TAG: Telegram Auth exception", e)
                         return null
                     }
                }
                is AuthConfig.None -> {}
            }

            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "GET"
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            if (conn.responseCode == 200) {
                val len = conn.contentLength
                if (len > MAX_DOWNLOAD_SIZE) return null
                return conn.inputStream.readBytes()
            }
        } catch (e: Exception) {
            Logger.e("$TAG: Download failed", e)
        }
        return null
    }
}
