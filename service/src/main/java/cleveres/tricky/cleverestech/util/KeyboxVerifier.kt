package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.Logger
import cleveres.tricky.cleverestech.keystore.CertHack
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.security.cert.X509Certificate

object KeyboxVerifier {
    data class Result(
        val file: File,
        val filename: String,
        val status: Status,
        val details: String,
    )

    enum class Status {
        VALID,
        REVOKED,
        INVALID,
        ERROR,
    }

    private const val DEFAULT_CRL_URL = "https://android.googleapis.com/attestation/status"
    private const val MAX_CRL_BYTES = 8L * 1024 * 1024
    private const val MAX_CRL_ENTRIES = 1_000_000
    private const val MAX_CRL_KEY_CHARS = 128
    private const val MAX_KEYBOX_XML_BYTES = 10L * 1024 * 1024
    private const val MAX_KEYBOX_FILES = 64

    @Volatile
    private var crlUrl = DEFAULT_CRL_URL
    private val HASH_LENGTHS = listOf(32, 40, 64)
    private val ZEROS = "0".repeat(64)

    @androidx.annotation.VisibleForTesting
    fun setCrlUrlForTesting(url: String) {
        require(isAllowedCrlUrl(url, allowLoopbackHttp = true)) { "CRL URL must use HTTPS or loopback HTTP" }
        cacheLock.lock()
        try {
            crlUrl = url
            clearCacheLocked()
        } finally {
            cacheLock.unlock()
        }
    }

    @androidx.annotation.VisibleForTesting
    fun resetCrlUrlForTesting() {
        cacheLock.lock()
        try {
            crlUrl = DEFAULT_CRL_URL
            clearCacheLocked()
        } finally {
            cacheLock.unlock()
        }
    }

    private var cachedCrl: Set<String>? = null
    private var cachedEtag: String? = null
    private var lastFetchTime: Long = 0
    private const val CACHE_TTL = 24 * 60 * 60 * 1000L
    private val cacheLock = java.util.concurrent.locks.ReentrantLock()

    private fun isHex(str: String): Boolean {
        if (str.isEmpty()) return false
        for (i in 0 until str.length) {
            val c = str[i]
            if (!(c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F')) {
                return false
            }
        }
        return true
    }

    @JvmStatic
    @JvmOverloads
    fun verify(
        configDir: File,
        crlFetcher: () -> Set<String>? = { fetchCrl() },
    ): List<Result> {
        val results = ArrayList<Result>()
        val revokedSerials = crlFetcher()

        if (revokedSerials == null) {
            return listOf(Result(File(""), "Global", Status.ERROR, "Failed to fetch CRL from Google"))
        }

        if (!Files.isDirectory(configDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return listOf(Result(File(""), "Global", Status.ERROR, "Config directory not found"))
        }

        val legacyFile = File(configDir, "keybox.xml")
        if (isSafeKeyboxFile(legacyFile)) {
            results.add(checkFile(legacyFile, revokedSerials))
        }

        val keyboxDir = File(configDir, "keyboxes")
        if (Files.isDirectory(keyboxDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            val files = ArrayList<File>(MAX_KEYBOX_FILES)
            try {
                Files.newDirectoryStream(keyboxDir.toPath()).use { entries ->
                    for (path in entries) {
                        val file = path.toFile()
                        if (!file.name.endsWith(".xml", ignoreCase = true) || !isSafeKeyboxFile(file)) continue
                        if (files.size >= MAX_KEYBOX_FILES) {
                            return listOf(Result(File(""), "Global", Status.ERROR, "Too many keybox files"))
                        }
                        files.add(file)
                    }
                }
            } catch (error: IOException) {
                Logger.e("Failed to scan keybox directory", error)
                return listOf(Result(File(""), "Global", Status.ERROR, "Failed to scan keybox directory"))
            }
            files.sortBy { it.name }
            for (file in files) {
                results.add(checkFile(file, revokedSerials))
            }
        }

        return results
    }

    @JvmStatic
    fun fetchCrl(): Set<String>? {
        val now = System.currentTimeMillis()
        cacheLock.lock()
        try {
            if (
                cachedCrl != null &&
                now >= lastFetchTime &&
                now - lastFetchTime < CACHE_TTL
            ) {
                return cachedCrl
            }

            val requestedUrl = crlUrl
            if (!isAllowedCrlUrl(requestedUrl, allowLoopbackHttp = requestedUrl != DEFAULT_CRL_URL)) {
                Logger.e("Rejected unsafe CRL URL")
                return null
            }

            val connection = URL(requestedUrl).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Encoding", "identity")
                cachedEtag?.let {
                    connection.setRequestProperty("If-None-Match", it)
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED && cachedCrl != null) {
                    lastFetchTime = now
                    return cachedCrl
                }
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Logger.e("CRL fetch failed with HTTP $responseCode")
                    return null
                }

                val declaredLength = connection.contentLengthLong
                if (declaredLength > MAX_CRL_BYTES) throw IOException("CRL response is too large")
                val newCrl = BoundedInputStream(connection.inputStream, MAX_CRL_BYTES).bufferedReader().use(::parseCrl)
                cachedCrl = newCrl
                cachedEtag = connection.getHeaderField("ETag")?.take(512)
                lastFetchTime = now
                return newCrl
            } catch (e: Exception) {
                Logger.e("Failed to fetch CRL", e)
                return null
            } finally {
                connection.disconnect()
            }
        } finally {
            cacheLock.unlock()
        }
    }

    private fun isAllowedCrlUrl(
        value: String,
        allowLoopbackHttp: Boolean,
    ): Boolean =
        try {
            val uri = URI(value)
            val loopback = uri.host == "localhost" || uri.host == "127.0.0.1" || uri.host == "::1"
            uri.isAbsolute &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null &&
                (
                    uri.scheme.equals("https", ignoreCase = true) ||
                        (allowLoopbackHttp && loopback && uri.scheme.equals("http", ignoreCase = true))
                )
        } catch (e: Exception) {
            false
        }

    private fun clearCacheLocked() {
        cachedCrl = null
        cachedEtag = null
        lastFetchTime = 0
    }

    @JvmStatic
    fun parseCrl(jsonStr: String): Set<String> {
        return parseCrl(java.io.StringReader(jsonStr))
    }

    @JvmStatic
    fun countRevokedKeys(): Int {
        return fetchCrl()?.size ?: -1
    }

    @JvmStatic
    fun countCrlEntries(reader: java.io.Reader): Int {
        var count = 0
        val jsonReader = android.util.JsonReader(reader)
        var entriesFound = false
        try {
            jsonReader.beginObject()
            while (jsonReader.hasNext()) {
                val name = jsonReader.nextName()
                if (name == "entries") {
                    entriesFound = true
                    jsonReader.beginObject()
                    while (jsonReader.hasNext()) {
                        val key = jsonReader.nextName()
                        if (key.length > MAX_CRL_KEY_CHARS) throw IOException("CRL entry key is too long")
                        jsonReader.skipValue()
                        count++
                        if (count > MAX_CRL_ENTRIES) throw IOException("CRL has too many entries")
                    }
                    jsonReader.endObject()
                } else {
                    jsonReader.skipValue()
                }
            }
            jsonReader.endObject()
        } catch (e: Exception) {
            Logger.e("Failed to count CRL JSON entries", e)
            return -1
        } finally {
            try {
                jsonReader.close()
            } catch (_: Exception) {
            }
        }
        return if (entriesFound) count else -1
    }

    @JvmStatic
    fun parseCrl(reader: java.io.Reader): Set<String> {
        val set = HashSet<String>()
        val jsonReader = android.util.JsonReader(reader)
        var entriesFound = false
        var entriesProcessed = 0
        try {
            jsonReader.beginObject()
            while (jsonReader.hasNext()) {
                val name = jsonReader.nextName()
                if (name == "entries") {
                    entriesFound = true
                    jsonReader.beginObject()
                    while (jsonReader.hasNext()) {
                        if (++entriesProcessed > MAX_CRL_ENTRIES) throw IOException("CRL has too many entries")
                        val decStr = jsonReader.nextName()
                        if (decStr.length > MAX_CRL_KEY_CHARS) throw IOException("CRL entry key is too long")
                        jsonReader.skipValue()
                        processEntry(decStr, set)
                    }
                    jsonReader.endObject()
                } else {
                    jsonReader.skipValue()
                }
            }
            jsonReader.endObject()

            if (!entriesFound) {
                throw IOException("Invalid CRL: 'entries' object missing")
            }
        } catch (e: Exception) {
            Logger.e("Failed to parse CRL JSON", e)
            throw IOException("Failed to parse CRL", e)
        } finally {
            try {
                jsonReader.close()
            } catch (_: Exception) {
            }
        }

        if (!entriesFound) {
            throw IOException("CRL missing 'entries' field")
        }
        return set
    }

    private fun processEntry(
        decStr: String,
        set: HashSet<String>,
    ) {
        if (decStr.isEmpty() || decStr.length > MAX_CRL_KEY_CHARS) {
            Logger.e("Rejected invalid CRL entry key length")
            return
        }

        var added = false
        val digitStart = if (decStr[0] == '-') 1 else 0
        var isDecimal = digitStart < decStr.length
        if (isDecimal && decStr.length - digitStart > 1 && decStr[digitStart] == '0') {
            isDecimal = false
        } else if (isDecimal) {
            for (i in digitStart until decStr.length) {
                if (!Character.isDigit(decStr[i])) {
                    isDecimal = false
                    break
                }
            }
        }

        if (isDecimal) {
            try {
                val number = java.math.BigInteger(decStr)
                val hexStr = number.toString(16)
                set.add(hexStr)
                if (number.signum() >= 0) {
                    val hexLen = hexStr.length
                    for (targetLen in HASH_LENGTHS) {
                        if (hexLen < targetLen) {
                            set.add(ZEROS.substring(0, targetLen - hexLen) + hexStr)
                        }
                    }
                }
                added = true
            } catch (_: Exception) {
            }
        }

        if (decStr.length == 32 || decStr.length == 40 || decStr.length == 64) {
            if (isHex(decStr)) {
                set.add(decStr.lowercase())
            }
        }

        if (!added && isHex(decStr)) {
            try {
                val hexStr = java.math.BigInteger(decStr, 16).toString(16)
                set.add(hexStr)
                added = true
            } catch (_: Exception) {
            }
        }

        if (!added) {
            Logger.e("Failed to parse CRL entry key")
        }
    }

    private fun checkFile(
        file: File,
        revokedSerials: Set<String>,
    ): Result {
        return try {
            if (!isSafeKeyboxFile(file)) {
                return Result(file, file.name, Status.ERROR, "Unsafe or oversized keybox file")
            }
            val keyboxes =
                file.bufferedReader().use { reader ->
                    CertHack.parseKeyboxXml(reader)
                }

            if (keyboxes.isEmpty()) {
                return Result(file, file.name, Status.INVALID, "No valid keyboxes found or parse error")
            }

            for (kb in keyboxes) {
                val status = verifyKeybox(kb, revokedSerials)
                if (status == Status.REVOKED) {
                    val chain = kb.certificates()
                    val sn =
                        if (chain.isNotEmpty() && chain[0] is X509Certificate) {
                            (chain[0] as X509Certificate).serialNumber.toString(16)
                        } else {
                            "unknown"
                        }
                    return Result(file, file.name, Status.REVOKED, "Certificate with SN $sn is revoked")
                } else if (status == Status.INVALID) {
                    return Result(file, file.name, Status.INVALID, "Keybox structure is invalid")
                }
            }

            Result(file, file.name, Status.VALID, "Active (${keyboxes.size} keys)")
        } catch (e: Exception) {
            Result(file, file.name, Status.ERROR, "Error: ${e.javaClass.simpleName}")
        }
    }

    private fun isSafeKeyboxFile(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            file.length() in 1..MAX_KEYBOX_XML_BYTES

    @JvmStatic
    fun verifyKeybox(
        kb: CertHack.KeyBox,
        revokedSerials: Set<String>,
    ): Status {
        val chain = kb.certificates()
        if (chain.isEmpty()) return Status.INVALID

        for (cert in chain) {
            if (cert is X509Certificate && isRevoked(cert, revokedSerials)) {
                return Status.REVOKED
            }
        }
        return Status.VALID
    }

    @JvmStatic
    fun isRevoked(
        cert: X509Certificate,
        revokedSerials: Set<String>,
    ): Boolean {
        val sn = cert.serialNumber.toString(16)
        if (revokedSerials.contains(sn)) return true

        val publicKeyEncoded = cert.publicKey.encoded
        if (checkHash(publicKeyEncoded, "SHA-1", revokedSerials)) return true
        if (checkHash(publicKeyEncoded, "SHA-256", revokedSerials)) return true
        if (checkHash(publicKeyEncoded, "MD5", revokedSerials)) return true
        return false
    }

    @OptIn(ExperimentalStdlibApi::class)
    private val hexFormat = HexFormat { upperCase = false }

    private val digestCache =
        object : ThreadLocal<HashMap<String, MessageDigest>>() {
            override fun initialValue(): HashMap<String, MessageDigest> {
                return HashMap()
            }
        }

    @OptIn(ExperimentalStdlibApi::class)
    private fun checkHash(
        data: ByteArray,
        algorithm: String,
        set: Set<String>,
    ): Boolean {
        try {
            val cache = digestCache.get()!!
            var md = cache[algorithm]
            if (md == null) {
                md = MessageDigest.getInstance(algorithm)
                cache[algorithm] = md
            } else {
                md.reset()
            }
            val digest = md.digest(data)
            val hex = digest.toHexString(hexFormat)
            return set.contains(hex)
        } catch (_: Exception) {
            return false
        }
    }

    private class BoundedInputStream(input: InputStream, private val maxBytes: Long) :
        FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int =
            super.read().also { value ->
                if (value >= 0) increment(1)
            }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int =
            super.read(buffer, offset, length).also { bytesRead ->
                if (bytesRead > 0) increment(bytesRead)
            }

        private fun increment(bytesRead: Int) {
            count += bytesRead
            if (count > maxBytes) throw IOException("CRL response exceeds $maxBytes bytes")
        }
    }
}
