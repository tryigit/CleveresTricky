package cleveres.tricky.cleverestech

import android.util.Base64
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.CboxDecryptor
import cleveres.tricky.cleverestech.util.DeviceKeyManager
import cleveres.tricky.cleverestech.util.FastByteArrayOutputStream
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.ZipProcessor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

object ServerManager {
    data class ServerConfig(
        val id: String,
        val name: String,
        val url: String,
        var priority: Int,
        var enabled: Boolean,
        val authType: String,
        val authData: JSONObject,
        var autoRefresh: Boolean,
        var refreshIntervalHours: Int,
        var lastStatus: String = "OK",
        var lastChecked: Long = 0,
        var lastAuthor: String = "",
        var contentPassword: String? = null,
        var contentPublicKey: String? = null,
    )

    private val serversList = CopyOnWriteArrayList<ServerConfig>()
    private val serversMap = ConcurrentHashMap<String, ServerConfig>()
    private val serverKeyboxes = ConcurrentHashMap<String, List<CertHack.KeyBox>>()
    private val serverFile get() = File(Config.keyboxDirectory.parentFile, "servers.json")
    private val validServerId = Regex("[A-Za-z0-9_-]{1,64}")
    private val validHeaderName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
    private val supportedAuthTypes = setOf("NONE", "BEARER", "BASIC", "API_KEY", "CUSTOM")
    private val restrictedHeaders = setOf("host", "content-length", "connection", "transfer-encoding")
    private val schedulerStarted = AtomicBoolean(false)
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "cleverestricky-server-refresh").apply { isDaemon = true }
        }

    fun initialize() {
        loadServers()
        loadCachedKeyboxes()
        startScheduler()
    }

    private fun loadServers() {
        serversList.clear()
        serversMap.clear()
        serverKeyboxes.clear()
        val file = serverFile
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        try {
            if (file.length() !in 1..MAX_CONFIG_BYTES) {
                throw SecurityException("Server configuration has an invalid size")
            }
            val stored = file.readBytes()
            val wasPlaintext = stored.firstOrNull() == '['.code.toByte()
            val plaintext =
                if (wasPlaintext) {
                    stored
                } else {
                    DeviceKeyManager.decrypt(stored)
                        ?: throw SecurityException("Could not decrypt server configuration")
                }
            try {
                val json = JSONArray(String(plaintext, StandardCharsets.UTF_8))
                require(json.length() <= MAX_SERVERS) { "Too many server configurations" }
                for (i in 0 until json.length()) {
                    try {
                        val server = parseServer(json.getJSONObject(i))
                        validateServer(server)
                        if (serversMap.putIfAbsent(server.id, server) == null) {
                            serversList.add(server)
                        }
                    } catch (e: Exception) {
                        Logger.e("Skipping invalid server configuration at index $i", e)
                    }
                }
            } finally {
                if (!wasPlaintext) stored.fill(0)
                plaintext.fill(0)
            }
            if (wasPlaintext) saveServers()
        } catch (e: Exception) {
            serversList.clear()
            serversMap.clear()
            serverKeyboxes.clear()
            Logger.e("Failed to load servers", e)
        }
    }

    @Synchronized
    fun saveServers() {
        val json = JSONArray()
        serversList.forEach { server ->
            json.put(serializeServer(server))
        }
        val plaintext = json.toString().toByteArray(StandardCharsets.UTF_8)
        val encrypted =
            try {
                require(plaintext.size <= MAX_CONFIG_BYTES) { "Server configuration is too large" }
                DeviceKeyManager.encrypt(plaintext)
                    ?: throw IllegalStateException("Device encryption key is unavailable")
            } finally {
                plaintext.fill(0)
            }
        try {
            SecureFile.writeBytes(serverFile, encrypted)
        } finally {
            encrypted.fill(0)
        }
    }

    private fun parseServer(json: JSONObject): ServerConfig {
        return ServerConfig(
            id = json.getString("id"),
            name = json.getString("name"),
            url = json.getString("url"),
            priority = json.getInt("priority"),
            enabled = json.getBoolean("enabled"),
            authType = json.getString("authType"),
            authData = json.optJSONObject("authData") ?: JSONObject(),
            autoRefresh = json.getBoolean("autoRefresh"),
            refreshIntervalHours = json.getInt("refreshIntervalHours"),
            lastStatus = json.optString("lastStatus", "OK"),
            lastChecked = json.optLong("lastChecked", 0),
            lastAuthor = json.optString("lastAuthor", ""),
            contentPassword = json.optString("contentPassword").ifEmpty { null },
            contentPublicKey = json.optString("contentPublicKey").ifEmpty { null },
        )
    }

    private fun serializeServer(server: ServerConfig): JSONObject {
        val json = JSONObject()
        json.put("id", server.id)
        json.put("name", server.name)
        json.put("url", server.url)
        json.put("priority", server.priority)
        json.put("enabled", server.enabled)
        json.put("authType", server.authType)
        json.put("authData", server.authData)
        json.put("autoRefresh", server.autoRefresh)
        json.put("refreshIntervalHours", server.refreshIntervalHours)
        json.put("lastStatus", server.lastStatus)
        json.put("lastChecked", server.lastChecked)
        json.put("lastAuthor", server.lastAuthor)
        json.put("contentPassword", server.contentPassword ?: "")
        json.put("contentPublicKey", server.contentPublicKey ?: "")
        return json
    }

    internal fun getServers(): List<ServerConfig> =
        serversList
            .sortedBy { it.priority }
            .map {
                it.copy(
                    authData = JSONObject(),
                    contentPassword = null,
                    contentPublicKey = null,
                )
            }

    internal fun findServer(id: String): ServerConfig? = if (validServerId.matches(id)) serversMap[id] else null

    internal fun cacheBindingChanged(
        previous: ServerConfig,
        replacement: ServerConfig,
    ): Boolean {
        val first = serverCacheBinding(previous)
        val second = serverCacheBinding(replacement)
        return try {
            !MessageDigest.isEqual(first, second)
        } finally {
            first.fill(0)
            second.fill(0)
        }
    }

    @Synchronized
    fun addServer(server: ServerConfig) {
        validateServer(server)
        require(serversMap.containsKey(server.id) || serversMap.size < MAX_SERVERS) { "Too many servers" }
        val previous = serversMap.put(server.id, server)
        if (previous != null) {
            serversList.removeIf { it.id == server.id }
        }
        serversList.add(server)
        try {
            saveServers()
        } catch (error: Exception) {
            serversList.removeIf { it.id == server.id }
            serversMap.remove(server.id)
            if (previous != null) {
                serversMap[previous.id] = previous
                serversList.add(previous)
            }
            throw error
        }
        if (previous != null && cacheBindingChanged(previous, server)) {
            deactivateServerContent(server.id, deleteCache = true)
        }
    }

    @Synchronized
    fun removeServer(id: String): Boolean {
        if (!validServerId.matches(id)) return false
        val previous = serversMap.remove(id) ?: return false
        val previousKeyboxes = serverKeyboxes.remove(id)
        serversList.removeIf { it.id == id }
        try {
            saveServers()
        } catch (error: Exception) {
            serversMap[id] = previous
            serversList.add(previous)
            if (previousKeyboxes != null) serverKeyboxes[id] = previousKeyboxes
            throw error
        }
        val cacheFile = File(Config.keyboxDirectory.parentFile, "server_cache_$id.enc")
        deleteCacheFile(cacheFile, "removed")
        return true
    }

    @Synchronized
    fun updateServer(
        id: String,
        block: (ServerConfig) -> Unit,
    ) {
        val s = serversMap[id]
        if (s != null) {
            val previous = s.copy(authData = JSONObject(s.authData.toString()))
            try {
                block(s)
                validateServer(s)
                saveServers()
            } catch (error: Exception) {
                serversMap[id] = previous
                serversList.replaceAll { if (it.id == id) previous else it }
                throw error
            }
            if (cacheBindingChanged(previous, s)) {
                deactivateServerContent(id, deleteCache = true)
            }
        }
    }

    private fun validateServer(server: ServerConfig) {
        require(validServerId.matches(server.id)) { "Invalid server ID" }
        require(server.name.isNotBlank() && server.name.length <= 128) { "Invalid server name" }
        require(server.name.none { it.isISOControl() }) { "Invalid server name" }
        require(server.authType in supportedAuthTypes) { "Unsupported authentication type" }
        require(server.priority in -1_000_000..1_000_000) { "Invalid priority" }
        require(server.refreshIntervalHours in 1..24 * 30) { "Invalid refresh interval" }
        require((server.contentPassword?.length ?: 0) <= 1024) { "Content password is too long" }
        require((server.contentPublicKey?.length ?: 0) <= 16 * 1024) { "Public key is too long" }
        require(server.authData.toString().length <= 64 * 1024) { "Authentication data is too large" }
        require(server.lastStatus.length <= 128 && server.lastStatus.none { it.isISOControl() }) {
            "Invalid server status"
        }
        require(server.lastAuthor.length <= 1024 && server.lastAuthor.none { it.isISOControl() }) {
            "Invalid server author"
        }
        validateAuthentication(server)
        validatedServerUrl(server.url)
    }

    private fun validateAuthentication(server: ServerConfig) {
        when (server.authType) {
            "NONE" -> Unit
            "BEARER" -> {
                val token = server.authData.optString("token")
                if (token.isNotEmpty()) requireSafeHeader("Authorization", "Bearer $token")
            }
            "BASIC" -> {
                val username = server.authData.optString("username")
                val password = server.authData.optString("password")
                require(username.length <= 1024 && password.length <= 1024) {
                    "Basic authentication credentials are too long"
                }
                require('\r' !in username && '\n' !in username && '\r' !in password && '\n' !in password) {
                    "Invalid basic authentication credentials"
                }
            }
            "API_KEY" -> {
                val header = server.authData.optString("headerName", "X-API-Key")
                val key = server.authData.optString("key")
                if (key.isNotEmpty()) requireSafeHeader(header, key)
            }
            "CUSTOM" -> {
                val headers = server.authData.optJSONObject("headers") ?: return
                require(headers.length() <= 32) { "Too many custom authentication headers" }
                val names = headers.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    requireSafeHeader(name, headers.getString(name))
                }
            }
        }
    }

    private fun validatedServerUrl(rawUrl: String): URL {
        require(rawUrl.length in 1..2048) { "Invalid server URL length" }
        val uri = URI(rawUrl)
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host?.isNotBlank() == true &&
                uri.userInfo == null &&
                uri.fragment == null,
        ) { "Server URL must be an absolute HTTPS URL without credentials or a fragment" }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid server port" }
        return uri.toURL()
    }

    private fun requireSafeHeader(
        name: String,
        value: String,
    ) {
        require(validHeaderName.matches(name)) { "Invalid authentication header name" }
        require(name.lowercase() !in restrictedHeaders) { "Restricted authentication header" }
        require(value.length <= 8192 && '\r' !in value && '\n' !in value) {
            "Invalid authentication header value"
        }
    }

    private fun loadCachedKeyboxes() {
        if (serversList.none { it.enabled }) return
        val revoked = KeyboxVerifier.fetchCrl()
        if (revoked == null) {
            Logger.w("Server keybox cache remains inactive because the revocation list is unavailable")
            return
        }
        serversList.forEach { server ->
            if (server.enabled) {
                val cacheFile = File(Config.keyboxDirectory.parentFile, "server_cache_${server.id}.enc")
                if (Files.isRegularFile(cacheFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    var decrypted: ByteArray? = null
                    var cachePayload: ByteArray? = null
                    try {
                        if (cacheFile.length() !in 1..MAX_CACHE_BYTES) {
                            throw SecurityException("Cached keybox file has an invalid size")
                        }
                        val enc = cacheFile.readBytes()
                        try {
                            decrypted = DeviceKeyManager.decrypt(enc)
                                ?: throw SecurityException("Could not decrypt server cache")
                            cachePayload = decodeServerCache(server, decrypted)
                                ?: throw SecurityException("Server cache trust binding does not match current configuration")
                            val parsed = KeyboxLoader.parse(cachePayload, "server_${server.name}")
                            val statuses = parsed.map { KeyboxVerifier.verifyKeybox(it, revoked) }
                            if (parsed.isNotEmpty() && statuses.all { it == KeyboxVerifier.Status.VALID }) {
                                serverKeyboxes[server.id] = parsed
                                Logger.i("Loaded cached keyboxes for server: ${server.name}")
                            } else {
                                deactivateServerContent(server.id, deleteCache = true)
                                Logger.w("Rejected incomplete or invalid server cache: ${server.name}")
                            }
                        } finally {
                            enc.fill(0)
                        }
                    } catch (e: Exception) {
                        deactivateServerContent(server.id, deleteCache = true)
                        Logger.e("Failed to load server cache for ${server.name}", e)
                    } finally {
                        cachePayload?.fill(0)
                        decrypted?.fill(0)
                    }
                }
            }
        }
    }

    @Synchronized
    fun fetchFromServer(server: ServerConfig): Boolean {
        if (!server.enabled) return false

        var conn: HttpsURLConnection? = null
        try {
            validateServer(server)
            server.lastChecked = System.currentTimeMillis()
            conn = validatedServerUrl(server.url).openConnection() as HttpsURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept-Encoding", "identity")

            when (server.authType) {
                "BEARER" -> {
                    val token = server.authData.optString("token")
                    if (token.isNotEmpty()) {
                        requireSafeHeader("Authorization", "Bearer $token")
                        conn.setRequestProperty("Authorization", "Bearer $token")
                    }
                }
                "BASIC" -> {
                    val user = server.authData.optString("username")
                    val pass = server.authData.optString("password")
                    if (user.isNotEmpty() || pass.isNotEmpty()) {
                        val auth = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
                        requireSafeHeader("Authorization", "Basic $auth")
                        conn.setRequestProperty("Authorization", "Basic $auth")
                    }
                }
                "API_KEY" -> {
                    val key = server.authData.optString("key")
                    val header = server.authData.optString("headerName", "X-API-Key")
                    if (key.isNotEmpty()) {
                        requireSafeHeader(header, key)
                        conn.setRequestProperty(header, key)
                    }
                }
                "CUSTOM" -> {
                    val headers = server.authData.optJSONObject("headers")
                    headers?.keys()?.forEach { key ->
                        val value = headers.getString(key)
                        requireSafeHeader(key, value)
                        conn.setRequestProperty(key, value)
                    }
                }
            }

            if (conn.responseCode != 200) {
                server.lastStatus = "HTTP_${conn.responseCode}"
                persistStatusSafely()
                return false
            }

            val maxResponseSize = 10 * 1024 * 1024
            val contentLength = conn.contentLengthLong
            if (contentLength > maxResponseSize) {
                server.lastStatus = "RESPONSE_TOO_LARGE"
                persistStatusSafely()
                return false
            }
            val bytes =
                conn.inputStream.use { input ->
                    val output =
                        FastByteArrayOutputStream(
                            minOf(contentLength.coerceAtLeast(0), 65536L).toInt(),
                        )
                    val chunk = ByteArray(8192)
                    try {
                        var totalRead = 0
                        var count: Int
                        while (input.read(chunk).also { count = it } != -1) {
                            if (count == 0) continue
                            totalRead += count
                            if (totalRead > maxResponseSize) {
                                throw SecurityException("Server response exceeds ${maxResponseSize / 1024 / 1024}MB limit")
                            }
                            output.write(chunk, 0, count)
                        }
                        output.toByteArray()
                    } finally {
                        chunk.fill(0)
                        output.wipe()
                    }
                }

            val result =
                try {
                    processContent(bytes, server)
                } finally {
                    bytes.fill(0)
                }
            val keyboxes = result.first
            val cacheBytes = result.second
            try {
                val crl = KeyboxVerifier.fetchCrl()
                if (crl == null) {
                    server.lastStatus = "CRL_UNAVAILABLE"
                    deactivateServerContent(server.id, deleteCache = false)
                    persistStatusSafely()
                    return false
                }
                val statuses = keyboxes.map { KeyboxVerifier.verifyKeybox(it, crl) }

                if (keyboxes.isNotEmpty() && statuses.all { it == KeyboxVerifier.Status.VALID }) {
                    serverKeyboxes[server.id] = keyboxes
                    server.lastStatus = "OK"
                    val cert = keyboxes.firstOrNull()?.certificates?.firstOrNull()
                    if (cert is X509Certificate) {
                        server.lastAuthor = cert.subjectX500Principal.name.take(1024)
                    } else {
                        server.lastAuthor = "Unknown"
                    }

                    if (cacheBytes != null) {
                        cacheXml(server, cacheBytes)
                    }
                } else {
                    server.lastStatus = "INVALID_CONTENT"
                    deactivateServerContent(server.id, deleteCache = true)
                    persistStatusSafely()
                    return false
                }

                persistStatusSafely()
                return true
            } finally {
                cacheBytes?.fill(0)
            }
        } catch (e: IllegalArgumentException) {
            server.lastStatus = "INVALID_CONFIG"
            Logger.e("Invalid server configuration: ${server.name}", e)
            persistStatusSafely()
            return false
        } catch (e: Exception) {
            server.lastStatus = "NETWORK_ERROR"
            Logger.e("Server fetch failed: ${server.name}", e)
            persistStatusSafely()
            return false
        } finally {
            conn?.disconnect()
        }
    }

    internal fun processContent(
        bytes: ByteArray,
        server: ServerConfig,
    ): Pair<List<CertHack.KeyBox>, ByteArray?> {
        val magic = if (bytes.size >= 4) String(bytes.copyOfRange(0, 4), StandardCharsets.US_ASCII) else ""

        if (magic == "CBOX") {
            val password = server.contentPassword ?: ""
            val payload = CboxDecryptor.decrypt(ByteArrayInputStream(bytes), password)
            if (payload != null) {
                if (!server.contentPublicKey.isNullOrBlank() &&
                    !CboxDecryptor.verifySignature(payload, server.contentPublicKey!!)
                ) {
                    Logger.e("Signature verification failed for server ${server.name}")
                    return Pair(emptyList(), null)
                }
                val xml = payload.takeXmlContentBytes()
                if (xml.isNotEmpty()) {
                    val cacheBytes = xml.copyOf()
                    val keyboxes = KeyboxLoader.parse(xml, "server_${server.name}")
                    if (keyboxes.isNotEmpty()) {
                        return Pair(keyboxes, cacheBytes)
                    }
                    cacheBytes.fill(0)
                }
            }
        } else if (bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            val pack = ZipProcessor.process(ByteArrayInputStream(bytes))
            if (pack != null) {
                try {
                    val allKeys = ArrayList<CertHack.KeyBox>()
                    val password = pack.password ?: server.contentPassword ?: ""
                    val publicKey = server.contentPublicKey

                    for ((name, content) in pack.cboxFiles) {
                        val payload = CboxDecryptor.decrypt(ByteArrayInputStream(content), password)
                        if (payload == null) {
                            Logger.e("Could not decrypt zip entry $name")
                            return Pair(emptyList(), null)
                        }
                        if (!publicKey.isNullOrBlank() &&
                            !CboxDecryptor.verifySignature(payload, publicKey)
                        ) {
                            Logger.e("Signature verification failed for zip entry $name")
                            return Pair(emptyList(), null)
                        }
                        val xml = payload.takeXmlContentBytes()
                        val keyboxes =
                            if (xml.isEmpty()) {
                                emptyList()
                            } else {
                                KeyboxLoader.parse(xml, "server_${server.name}_$name")
                            }
                        if (keyboxes.isEmpty()) {
                            Logger.e("Zip entry contains no valid keybox records: $name")
                            return Pair(emptyList(), null)
                        }
                        if (keyboxes.size > MAX_REMOTE_KEYBOXES - allKeys.size) {
                            Logger.e("Server archive exceeds the keybox-count limit")
                            return Pair(emptyList(), null)
                        }
                        allKeys.addAll(keyboxes)
                    }

                    if (allKeys.isNotEmpty()) {
                        return Pair(
                            allKeys,
                            serializeKeyboxesForCache(allKeys).toByteArray(StandardCharsets.UTF_8),
                        )
                    }
                } finally {
                    pack.cboxFiles.forEach { it.second.fill(0) }
                }
            }
        } else {
            if (!server.contentPublicKey.isNullOrBlank()) {
                Logger.e("Signed server refused unsigned plain XML")
                return Pair(emptyList(), null)
            }
            val cacheBytes = bytes.copyOf()
            val keyboxes = KeyboxLoader.parse(bytes, "server_${server.name}")
            if (keyboxes.isNotEmpty()) {
                return Pair(keyboxes, cacheBytes)
            }
            cacheBytes.fill(0)
        }
        return Pair(emptyList(), null)
    }

    private fun cacheXml(
        server: ServerConfig,
        plaintext: ByteArray,
    ) {
        var boundPlaintext: ByteArray? = null
        var encrypted: ByteArray? = null
        try {
            boundPlaintext = encodeServerCache(server, plaintext)
            encrypted = DeviceKeyManager.encrypt(boundPlaintext)
            if (encrypted != null) {
                val file = File(Config.keyboxDirectory.parentFile, "server_cache_${server.id}.enc")
                SecureFile.writeBytes(file, encrypted)
            }
        } catch (e: Exception) {
            Logger.e("Failed to cache server content", e)
        } finally {
            encrypted?.fill(0)
            boundPlaintext?.fill(0)
            plaintext.fill(0)
        }
    }

    private fun encodeServerCache(
        server: ServerConfig,
        plaintext: ByteArray,
    ): ByteArray {
        val binding = serverCacheBinding(server)
        return try {
            ByteArray(SERVER_CACHE_PREFIX_BYTES + plaintext.size).also { output ->
                SERVER_CACHE_MAGIC.copyInto(output, 0)
                binding.copyInto(output, SERVER_CACHE_MAGIC.size)
                plaintext.copyInto(output, SERVER_CACHE_PREFIX_BYTES)
            }
        } finally {
            binding.fill(0)
        }
    }

    private fun decodeServerCache(
        server: ServerConfig,
        encoded: ByteArray,
    ): ByteArray? {
        if (encoded.size <= SERVER_CACHE_PREFIX_BYTES ||
            !encoded.copyOfRange(0, SERVER_CACHE_MAGIC.size).contentEquals(SERVER_CACHE_MAGIC)
        ) {
            return null
        }
        val expected = serverCacheBinding(server)
        return try {
            val stored = encoded.copyOfRange(SERVER_CACHE_MAGIC.size, SERVER_CACHE_PREFIX_BYTES)
            try {
                if (!MessageDigest.isEqual(stored, expected)) return null
            } finally {
                stored.fill(0)
            }
            encoded.copyOfRange(SERVER_CACHE_PREFIX_BYTES, encoded.size)
        } finally {
            expected.fill(0)
        }
    }

    private fun serverCacheBinding(server: ServerConfig): ByteArray {
        val material =
            buildString {
                append(server.id).append('\n')
                append(server.url).append('\n')
                append(server.authType).append('\n')
                append(canonicalJson(server.authData)).append('\n')
                append(server.contentPassword.orEmpty()).append('\n')
                append(server.contentPublicKey.orEmpty())
            }.toByteArray(StandardCharsets.UTF_8)
        return try {
            MessageDigest.getInstance("SHA-256").digest(material)
        } finally {
            material.fill(0)
        }
    }

    private fun canonicalJson(value: Any?): String =
        when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject ->
                value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { key ->
                    "${JSONObject.quote(key)}:${canonicalJson(value.opt(key))}"
                }
            is JSONArray ->
                (0 until value.length()).joinToString(prefix = "[", postfix = "]") { index ->
                    canonicalJson(value.opt(index))
                }
            is Number, is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }

    internal fun serializeKeyboxesForCache(keyboxes: List<CertHack.KeyBox>): String {
        require(keyboxes.isNotEmpty() && keyboxes.size <= MAX_REMOTE_KEYBOXES) {
            "Invalid keybox cache size"
        }
        return buildString {
            append("<?xml version=\"1.0\"?>\n")
            append("<AndroidAttestation>\n")
            append("  <NumberOfKeyboxes>${keyboxes.size}</NumberOfKeyboxes>\n")
            keyboxes.forEach { keybox ->
                val keyPair = keybox.keyPair
                val algorithm =
                    when (keyPair.public.algorithm.uppercase()) {
                        "EC", "ECDSA" -> "ecdsa"
                        "RSA" -> "rsa"
                        else -> throw IllegalArgumentException("Unsupported keybox algorithm")
                    }
                val privateKey =
                    requireNotNull(keyPair.private.encoded) { "Private key is not exportable" }
                val certificates = keybox.certificates
                require(certificates.isNotEmpty()) { "Keybox certificate chain is empty" }

                append("  <Keybox>\n")
                append("    <Key algorithm=\"$algorithm\">\n")
                append("      <PrivateKey>\n")
                appendPem("PRIVATE KEY", privateKey)
                append("      </PrivateKey>\n")
                append("      <CertificateChain>\n")
                append("        <NumberOfCertificates>${certificates.size}</NumberOfCertificates>\n")
                certificates.forEach { certificate ->
                    append("        <Certificate>\n")
                    appendPem("CERTIFICATE", certificate.encoded)
                    append("        </Certificate>\n")
                }
                append("      </CertificateChain>\n")
                append("    </Key>\n")
                append("  </Keybox>\n")
            }
            append("</AndroidAttestation>\n")
        }
    }

    private fun StringBuilder.appendPem(
        label: String,
        bytes: ByteArray,
    ) {
        val encoded =
            java.util.Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
                .encodeToString(bytes)
        append("-----BEGIN $label-----\n")
        append(encoded)
        append("\n-----END $label-----\n")
    }

    private fun deactivateServerContent(
        serverId: String,
        deleteCache: Boolean,
    ) {
        serverKeyboxes.remove(serverId)
        if (!deleteCache) return
        val cacheFile = File(Config.keyboxDirectory.parentFile, "server_cache_$serverId.enc")
        deleteCacheFile(cacheFile, "rejected")
    }

    private fun deleteCacheFile(
        cacheFile: File,
        reason: String,
    ) {
        val path = cacheFile.toPath()
        try {
            if (Files.isSymbolicLink(path)) {
                Logger.w("Refusing symbolic-link $reason server cache")
                return
            }
            Files.deleteIfExists(path)
        } catch (error: Exception) {
            Logger.w("Could not delete $reason server cache")
        }
    }

    fun getLoadedKeyboxes(): List<CertHack.KeyBox> {
        return serversList
            .asSequence()
            .filter { it.enabled }
            .sortedBy { it.priority }
            .flatMap { serverKeyboxes[it.id].orEmpty().asSequence() }
            .toList()
    }

    fun refreshAll() {
        serversList.filter { it.enabled }.sortedBy { it.priority }.forEach {
            fetchFromServer(it)
        }
        Config.updateKeyBoxesSync()
    }

    private fun startScheduler() {
        if (!schedulerStarted.compareAndSet(false, true)) return
        scheduler.scheduleWithFixedDelay(
            {
                try {
                    val now = System.currentTimeMillis()
                    val dueServers = selectDueServersForRefresh(serversList, now)
                    dueServers.forEach(::fetchFromServer)
                    if (dueServers.isNotEmpty()) Config.updateKeyBoxesSync()
                } catch (e: Exception) {
                    Logger.e("Scheduled server refresh failed", e)
                }
            },
            1,
            60,
            TimeUnit.MINUTES,
        )
    }

    internal fun selectDueServersForRefresh(
        candidates: Iterable<ServerConfig>,
        now: Long,
    ): List<ServerConfig> =
        candidates
            .filter { server ->
                server.enabled &&
                    server.autoRefresh &&
                    (
                        server.lastChecked <= 0L ||
                            server.lastChecked > now ||
                            now - server.lastChecked >=
                            TimeUnit.HOURS.toMillis(server.refreshIntervalHours.toLong())
                    )
            }
            .sortedBy { it.priority }

    private fun persistStatusSafely() {
        try {
            saveServers()
        } catch (e: Exception) {
            Logger.e("Failed to persist server status", e)
        }
    }

    private const val MAX_SERVERS = 64
    private const val MAX_REMOTE_KEYBOXES = 64
    private const val MAX_CONFIG_BYTES = 2L * 1024 * 1024
    private const val MAX_CACHE_BYTES = 16L * 1024 * 1024
    private val SERVER_CACHE_MAGIC = byteArrayOf('C'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte(), 2)
    private const val SERVER_CACHE_BINDING_BYTES = 32
    private val SERVER_CACHE_PREFIX_BYTES = SERVER_CACHE_MAGIC.size + SERVER_CACHE_BINDING_BYTES
}
