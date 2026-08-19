package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.CrlBackend
import cleveres.tricky.cleverestech.CrlWire
import cleveres.tricky.cleverestech.KeyboxLoader
import cleveres.tricky.cleverestech.Logger
import cleveres.tricky.cleverestech.RustBackendUnavailableException
import cleveres.tricky.cleverestech.StoredKeyboxInventory
import cleveres.tricky.cleverestech.keystore.CertHack
import java.io.ByteArrayOutputStream
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
        val storageId: String = "",
        val certificateSerial: String? = null,
    )

    enum class Status {
        VALID,
        REVOKED,
        INVALID,
        ERROR,
    }

    private sealed interface RevocationSource {
        data class Rust(val handle: CrlWire.Handle) : RevocationSource

        data class Legacy(val entries: Set<String>) : RevocationSource
    }

    private const val DEFAULT_CRL_URL = "https://android.googleapis.com/attestation/status"
    private const val MAX_CRL_BYTES = 8L * 1024 * 1024
    private const val MAX_KEYBOX_XML_BYTES = 10L * 1024 * 1024
    private const val MAX_KEYBOX_FILES = 64
    private const val PERSISTED_CRL_FILE = "attestation_status_cache.json"
    private const val CACHE_TTL = 24 * 60 * 60 * 1000L

    @Volatile
    private var crlUrl = DEFAULT_CRL_URL

    @Volatile
    private var cacheRoot = File("/data/adb/cleverestricky")

    private var cachedCrl: CrlWire.Handle? = null
    private var cachedEtag: String? = null
    private var lastFetchTime: Long = 0
    private val cacheLock = java.util.concurrent.locks.ReentrantLock()

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

    fun configureCacheRoot(configDir: File) {
        cacheLock.lock()
        try {
            cacheRoot = configDir
        } finally {
            cacheLock.unlock()
        }
    }

    @androidx.annotation.VisibleForTesting
    fun setCacheRootForTesting(configDir: File) {
        cacheLock.lock()
        try {
            cacheRoot = configDir
            clearCacheLocked()
        } finally {
            cacheLock.unlock()
        }
    }

    @androidx.annotation.VisibleForTesting
    fun clearMemoryCacheForTesting() {
        cacheLock.lock()
        try {
            clearCacheLocked()
        } finally {
            cacheLock.unlock()
        }
    }

    @androidx.annotation.VisibleForTesting
    fun resetCacheRootForTesting() {
        cacheLock.lock()
        try {
            cacheRoot = File("/data/adb/cleverestricky")
            clearCacheLocked()
        } finally {
            cacheLock.unlock()
        }
    }

    @JvmStatic
    fun verify(configDir: File): List<Result> =
        verifyWithSource(configDir) { fetchCrl()?.let(RevocationSource::Rust) }

    /** JVM test compatibility only; production never models a Rust CRL generation as a Set. */
    @JvmStatic
    @androidx.annotation.VisibleForTesting
    fun verify(
        configDir: File,
        crlFetcher: () -> Set<String>?,
    ): List<Result> = verifyLegacy(configDir, crlFetcher)

    /** Production-source bridge used only when WebServer receives its JVM-test CRL injector. */
    internal fun verifyLegacy(
        configDir: File,
        crlFetcher: () -> Set<String>?,
    ): List<Result> =
        verifyWithSource(configDir) { crlFetcher()?.let(RevocationSource::Legacy) }

    private fun verifyWithSource(
        configDir: File,
        crlFetcher: () -> RevocationSource?,
    ): List<Result> {
        if (!Files.isDirectory(configDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return listOf(Result(File(""), "Global", Status.ERROR, "Config directory not found"))
        }
        val sources =
            try {
                StoredKeyboxInventory.runtimeXmlSources(configDir)
            } catch (error: Exception) {
                Logger.e("Failed to enumerate stored keyboxes", error)
                return listOf(Result(File(""), "Global", Status.ERROR, error.message ?: "Failed to scan keyboxes"))
            }
        val results = ArrayList<Result>(sources.size)
        for (source in sources) {
            results.add(
                checkFile(
                    source.file,
                    requireNotNull(source.scope.fileScope),
                    source.filename,
                    source.id,
                    crlFetcher,
                ),
            )
        }
        return results
    }

    @JvmStatic
    fun fetchCrl(): CrlWire.Handle? {
        val now = System.currentTimeMillis()
        cacheLock.lock()
        try {
            cachedCrl?.let { cached ->
                if (now >= lastFetchTime && now - lastFetchTime < CACHE_TTL) return cached
            }

            loadPersistedCrlLocked(now)?.let { persisted ->
                val (raw, modified) = persisted
                try {
                    val handle = CrlBackend.refresh(raw) ?: return@let
                    cachedCrl = handle
                    lastFetchTime = modified
                    Logger.i("Loaded fresh attestation revocation cache into Rust generation ${handle.generation}")
                    return handle
                } finally {
                    raw.fill(0)
                }
            }

            val requestedUrl = crlUrl
            if (!isAllowedCrlUrl(requestedUrl, allowLoopbackHttp = requestedUrl != DEFAULT_CRL_URL)) {
                Logger.e("Rejected unsafe CRL URL")
                return null
            }
            return fetchNetworkCrlLocked(requestedUrl, now)
        } finally {
            cacheLock.unlock()
        }
    }

    private fun fetchNetworkCrlLocked(
        requestedUrl: String,
        now: Long,
    ): CrlWire.Handle? {
        var conditionalEtag = cachedEtag
        repeat(2) { attempt ->
            val connection = URL(requestedUrl).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Encoding", "identity")
                conditionalEtag?.let { connection.setRequestProperty("If-None-Match", it) }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    cachedCrl?.let { cached ->
                        lastFetchTime = now
                        return cached
                    }
                    if (conditionalEtag != null && attempt == 0) {
                        Logger.w("CRL server returned 304 without usable local state; retrying unconditionally")
                        cachedEtag = null
                        conditionalEtag = null
                        return@repeat
                    }
                    Logger.e("CRL server returned 304 without usable local state")
                    return null
                }
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Logger.e("CRL fetch failed with HTTP $responseCode")
                    return null
                }

                val declaredLength = connection.contentLengthLong
                if (declaredLength > MAX_CRL_BYTES) throw IOException("CRL response is too large")
                val raw = BoundedInputStream(connection.inputStream, MAX_CRL_BYTES).use(::readAllBytesBounded)
                try {
                    val handle = CrlBackend.refresh(raw) ?: return null
                    persistCrlLocked(raw)
                    cachedCrl = handle
                    cachedEtag = connection.getHeaderField("ETag")?.take(512)
                    lastFetchTime = now
                    return handle
                } finally {
                    raw.fill(0)
                }
            } catch (error: Exception) {
                Logger.e("Failed to fetch CRL", error)
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    /** Rebuilds only from the persisted raw cache; restart recovery never performs recursive network work. */
    internal fun refreshPersistedCrlForBackendRecovery(): CrlWire.Handle? {
        val now = System.currentTimeMillis()
        cacheLock.lock()
        try {
            cachedCrl = null
            cachedEtag = null
            lastFetchTime = 0
            val persisted = loadPersistedCrlLocked(now) ?: return null
            val (raw, modified) = persisted
            return try {
                CrlBackend.refresh(raw)?.also { handle ->
                    cachedCrl = handle
                    lastFetchTime = modified
                }
            } finally {
                raw.fill(0)
            }
        } finally {
            cacheLock.unlock()
        }
    }

    @JvmStatic
    fun countRevokedKeys(): Int = fetchCrl()?.normalizedEntryCount ?: -1

    internal fun invalidateBackendGeneration() {
        cacheLock.lock()
        try {
            cachedCrl = null
            cachedEtag = null
            lastFetchTime = 0
        } finally {
            cacheLock.unlock()
        }
    }

    @androidx.annotation.VisibleForTesting
    fun invalidateBackendGenerationForTesting() = invalidateBackendGeneration()

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
        } catch (_: Exception) {
            false
        }

    private fun loadPersistedCrlLocked(now: Long): Pair<ByteArray, Long>? {
        val cacheFile = File(cacheRoot, PERSISTED_CRL_FILE)
        val path = cacheFile.toPath()
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        val size = cacheFile.length()
        val modified = cacheFile.lastModified()
        val age = now - modified
        if (size !in 1..MAX_CRL_BYTES || modified <= 0L || age < 0L || age >= CACHE_TTL) return null
        return runCatching {
            BoundedInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS), MAX_CRL_BYTES)
                .use(::readAllBytesBounded) to modified
        }.onFailure {
            Logger.w("Ignoring invalid persisted attestation revocation cache")
        }.getOrNull()
    }

    private fun persistCrlLocked(rawCrl: ByteArray) {
        runCatching {
            SecureFile.writeBytes(File(cacheRoot, PERSISTED_CRL_FILE), rawCrl)
        }.onFailure {
            Logger.w("Could not persist attestation revocation cache")
        }
    }

    private fun clearCacheLocked() {
        cachedCrl = null
        cachedEtag = null
        lastFetchTime = 0
    }

    private fun checkFile(
        file: File,
        scope: KeyboxLoader.FileScope,
        filename: String,
        storageId: String,
        crlFetcher: () -> RevocationSource?,
    ): Result =
        try {
            if (!isSafeKeyboxFile(file)) {
                return Result(file, file.name, Status.ERROR, "Unsafe or oversized keybox file")
            }
            val keyboxes = KeyboxLoader.parseFile(scope, filename)
            if (keyboxes.isEmpty()) {
                return Result(file, file.name, Status.INVALID, "No valid keybox found or parse error", storageId)
            }
            // parseFile can discover a Rust backend restart and rebuild backend-owned CRL state.
            // Resolve the handle only after that recovery boundary so this request never keeps
            // using the pre-recovery generation on its first manual verification attempt.
            val crl = crlFetcher()
                ?: return Result(file, file.name, Status.ERROR, "Failed to initialize CRL index", storageId)
            val deviceSerial = keyboxes.asSequence().mapNotNull(CertHack::getDeviceCertificateSerial).firstOrNull()

            for (keybox in keyboxes) {
                val status =
                    when (crl) {
                        is RevocationSource.Rust -> verifyKeybox(keybox, crl.handle)
                        is RevocationSource.Legacy -> verifyKeyboxLegacy(keybox, crl.entries)
                    }
                when (status) {
                    Status.REVOKED -> {
                        val chain = keybox.certificates()
                        val serial =
                            if (chain.isNotEmpty() && chain[0] is X509Certificate) {
                                (chain[0] as X509Certificate).serialNumber.toString(16)
                            } else {
                                "unknown"
                            }
                        return Result(file, file.name, Status.REVOKED, "Certificate with SN $serial is revoked", storageId, deviceSerial)
                    }
                    Status.INVALID -> return Result(file, file.name, Status.INVALID, "Keybox structure is invalid", storageId, deviceSerial)
                    Status.ERROR -> return Result(file, file.name, Status.ERROR, "Rust CRL backend unavailable", storageId, deviceSerial)
                    Status.VALID -> Unit
                }
            }
            Result(file, file.name, Status.VALID, "Active keybox", storageId, deviceSerial)
        } catch (_: RustBackendUnavailableException) {
            Result(file, file.name, Status.ERROR, "Rust backend unavailable", storageId)
        } catch (error: Exception) {
            Result(file, file.name, Status.ERROR, "Error: ${error.javaClass.simpleName}", storageId)
        }

    private fun isSafeKeyboxFile(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            file.length() in 1..MAX_KEYBOX_XML_BYTES

    /** Production verifier for opaque Rust revocation state. */
    @JvmStatic
    fun verifyKeybox(
        keybox: CertHack.KeyBox,
        crl: CrlWire.Handle,
    ): Status {
        val certificates = keybox.certificates()
        if (certificates.isEmpty()) return Status.INVALID
        val queries = ArrayList<CrlWire.Query>(certificates.size)
        for (certificate in certificates) {
            val x509 = certificate as? X509Certificate ?: return Status.INVALID
            val serial = x509.serialNumber.toByteArray()
            val spki = x509.publicKey.encoded ?: return Status.INVALID
            if (serial.isEmpty() || spki.isEmpty()) return Status.INVALID
            queries += CrlWire.Query(serial, spki)
        }
        val result = CrlBackend.check(crl.generation, queries) ?: return Status.ERROR
        return if (result.revoked.any { it }) Status.REVOKED else Status.VALID
    }

    /** JVM test/injection compatibility only. */
    @JvmStatic
    @androidx.annotation.VisibleForTesting
    fun verifyKeybox(
        keybox: CertHack.KeyBox,
        revoked: Set<String>,
    ): Status = verifyKeyboxLegacy(keybox, revoked)

    /** Production-source bridge used only by legacy JVM-test injection paths. */
    internal fun verifyKeyboxLegacy(
        keybox: CertHack.KeyBox,
        revoked: Set<String>,
    ): Status {
        val certificates = keybox.certificates()
        if (certificates.isEmpty()) return Status.INVALID
        for (certificate in certificates) {
            val x509 = certificate as? X509Certificate ?: return Status.INVALID
            if (isRevokedLegacySet(x509, revoked)) return Status.REVOKED
        }
        return Status.VALID
    }

    @JvmStatic
    fun isRevoked(
        certificate: X509Certificate,
        crl: CrlWire.Handle,
    ): Boolean {
        val serial = certificate.serialNumber.toByteArray()
        val spki = certificate.publicKey.encoded ?: return false
        val result =
            CrlBackend.check(crl.generation, listOf(CrlWire.Query(serial, spki)))
                ?: throw RustBackendUnavailableException(IOException("CRL generation query failed"))
        return result.revoked.single()
    }

    /** JVM test/injection compatibility only. */
    @JvmStatic
    @androidx.annotation.VisibleForTesting
    fun isRevoked(
        certificate: X509Certificate,
        revoked: Set<String>,
    ): Boolean = isRevokedLegacySet(certificate, revoked)

    private fun isRevokedLegacySet(
        certificate: X509Certificate,
        revoked: Set<String>,
    ): Boolean {
        if (revoked.contains(certificate.serialNumber.toString(16))) return true
        val spki = certificate.publicKey.encoded ?: return false
        for (algorithm in LEGACY_HASH_ALGORITHMS) {
            val digest = runCatching { MessageDigest.getInstance(algorithm).digest(spki) }.getOrNull() ?: continue
            val hex = buildString(digest.size * 2) {
                for (byte in digest) append(HEX[(byte.toInt() ushr 4) and 0xf]).append(HEX[byte.toInt() and 0xf])
            }
            digest.fill(0)
            if (revoked.contains(hex)) return true
        }
        return false
    }

    private fun readAllBytesBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
            output.reset()
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

    private val LEGACY_HASH_ALGORITHMS = arrayOf("SHA-1", "SHA-256", "MD5")
    private const val HEX = "0123456789abcdef"
}
