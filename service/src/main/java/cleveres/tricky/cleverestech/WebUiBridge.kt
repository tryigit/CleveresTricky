package cleveres.tricky.cleverestech

import android.os.FileObserver
import cleveres.tricky.cleverestech.util.SecureFile
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebUiBridge(
    private val server: WebServer,
    configDir: File,
) {
    private val bridgeDir = File(configDir, "webui_bridge")
    private val requestDir = File(bridgeDir, "requests")
    private val responseDir = File(bridgeDir, "responses")
    private val stagingDir = File(bridgeDir, "staging")
    private val scanning = AtomicBoolean(false)
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "CleveresTricky-WebUI").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }

    @Volatile
    private var started = false
    private var observer: FileObserver? = null
    private var fallback: ScheduledFuture<*>? = null

    @Synchronized
    fun start() {
        if (started) return
        ensureLayout()
        cleanupStale()
        val eventMask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE

        @Suppress("DEPRECATION")
        val createdObserver =
            object : FileObserver(requestDir.absolutePath, eventMask) {
                override fun onEvent(
                    event: Int,
                    path: String?,
                ) {
                    if (path != null && REQUEST_NAME.matches(path)) executor.execute(::processPendingRequests)
                }
            }
        createdObserver.startWatching()
        observer = createdObserver
        fallback =
            executor.scheduleWithFixedDelay(
                ::processPendingRequests,
                0,
                FALLBACK_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
        started = true
        Logger.i("Native WebUI bridge is ready")
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        observer?.stopWatching()
        observer = null
        fallback?.cancel(false)
        fallback = null
        executor.shutdownNow()
    }

    internal fun processPendingRequests() {
        if (!scanning.compareAndSet(false, true)) return
        try {
            val requests =
                requestDir.listFiles()
                    ?.asSequence()
                    ?.filter { REQUEST_NAME.matches(it.name) }
                    ?.sortedBy { it.lastModified() }
                    ?.take(MAX_PENDING_REQUESTS)
                    ?.toList()
                    .orEmpty()
            requests.mapNotNull(::claimRequest).forEach(::processRequest)
            cleanupStale()
        } catch (error: Throwable) {
            Logger.e("WebUI bridge scan failed", error)
        } finally {
            scanning.set(false)
        }
    }

    private data class ClaimedRequest(
        val id: String,
        val file: File,
    )

    private fun claimRequest(requestFile: File): ClaimedRequest? {
        val match = REQUEST_NAME.matchEntire(requestFile.name) ?: return null
        val requestId = match.groupValues[1]
        val workingFile = File(requestDir, "$requestId.working")
        return try {
            requireRegularFile(requestFile, 1, MAX_REQUEST_BYTES)
            try {
                Files.move(requestFile.toPath(), workingFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(requestFile.toPath(), workingFile.toPath())
            } catch (_: UnsupportedOperationException) {
                Files.move(requestFile.toPath(), workingFile.toPath())
            }
            ClaimedRequest(requestId, workingFile)
        } catch (_: NoSuchFileException) {
            null
        } catch (error: IllegalArgumentException) {
            deleteRegularFile(requestFile)
            writeErrorResponse(
                File(responseDir, "$requestId.response"),
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "Invalid native WebUI request",
            )
            null
        }
    }

    private fun processRequest(claimed: ClaimedRequest) {
        val requestId = claimed.id
        val requestFile = claimed.file
        val responseFile = File(responseDir, "$requestId.response")
        var uploadFile: File? = null
        try {
            requireRegularFile(requestFile, 1, MAX_REQUEST_BYTES)
            val requestBytes = readLimited(requestFile, MAX_REQUEST_BYTES.toInt())
            val request =
                try {
                    JSONObject(String(requestBytes, Charsets.UTF_8))
                } finally {
                    requestBytes.fill(0)
                }
            uploadFile =
                nullableString(request, "uploadId")
                    ?.takeIf(ID_PATTERN::matches)
                    ?.let { File(stagingDir, "$it.upload") }
            val parsed = parseRequest(request)
            uploadFile = parsed.uploadFile
            val response = server.serveBridge(parsed.session)
            writeResponse(requestId, responseFile, response)
        } catch (error: IllegalArgumentException) {
            writeErrorResponse(responseFile, NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid native WebUI request")
            Logger.w("WebUI bridge rejected request $requestId: ${error.message}")
        } catch (error: Throwable) {
            writeErrorResponse(responseFile, NanoHTTPD.Response.Status.INTERNAL_ERROR, "Native WebUI request failed")
            Logger.e("WebUI bridge request failed", error)
        } finally {
            deleteRegularFile(requestFile)
            uploadFile?.let(::deleteRegularFile)
        }
    }

    private data class ParsedRequest(
        val session: NanoHTTPD.IHTTPSession,
        val uploadFile: File?,
    )

    private fun parseRequest(request: JSONObject): ParsedRequest {
        require(request.optInt("version", -1) == PROTOCOL_VERSION)
        require(request.length() in 4..6)
        val requestKeys = request.keys()
        while (requestKeys.hasNext()) require(requestKeys.next() in REQUEST_FIELDS)
        require(request.has("method") && request.has("path") && request.has("parameters"))
        val methodName = request.getString("method")
        val method =
            when (methodName) {
                "GET" -> NanoHTTPD.Method.GET
                "POST" -> NanoHTTPD.Method.POST
                else -> throw IllegalArgumentException("Unsupported method")
            }
        val path = request.getString("path")
        require(path.length in 5..256 && path.startsWith("/api/") && ".." !in path && '\\' !in path && '\u0000' !in path)
        val parameterObject = request.getJSONObject("parameters")
        require(parameterObject.length() <= MAX_PARAMETER_KEYS)
        val parameters = LinkedHashMap<String, List<String>>()
        var parameterBytes = 0L
        val keys = parameterObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            require(key.length in 1..128)
            val array = parameterObject.get(key) as? JSONArray ?: throw IllegalArgumentException("Invalid parameter values")
            require(array.length() in 1..MAX_PARAMETER_VALUES)
            val values = ArrayList<String>(array.length())
            for (index in 0 until array.length()) {
                val value = array.get(index) as? String ?: throw IllegalArgumentException("Invalid parameter value")
                parameterBytes += key.toByteArray(Charsets.UTF_8).size + value.toByteArray(Charsets.UTF_8).size + 2L
                require(parameterBytes <= MAX_REQUEST_BYTES)
                values += value
            }
            parameters[key] = values
        }
        val uploadId = nullableString(request, "uploadId")
        val uploadField = nullableString(request, "uploadField")
        require((uploadId == null) == (uploadField == null))
        val uploadFile =
            if (uploadId != null && uploadField != null) {
                require(ID_PATTERN.matches(uploadId) && FIELD_PATTERN.matches(uploadField))
                File(stagingDir, "$uploadId.upload").also { requireRegularFile(it, 1, MAX_UPLOAD_BYTES) }
            } else {
                null
            }
        val contentLength = parameterBytes + (uploadFile?.length() ?: 0L)
        require(contentLength <= MAX_REQUEST_BYTES + MAX_UPLOAD_BYTES)
        val headers =
            linkedMapOf(
                "content-length" to contentLength.toString(),
                "content-type" to if (uploadFile == null) "application/x-www-form-urlencoded" else "multipart/form-data",
            )
        return ParsedRequest(
            BridgeSession(method, path, headers, parameters, uploadField, uploadFile),
            uploadFile,
        )
    }

    private fun nullableString(
        objectValue: JSONObject,
        name: String,
    ): String? {
        if (!objectValue.has(name) || objectValue.isNull(name)) return null
        return objectValue.get(name) as? String ?: throw IllegalArgumentException("Invalid $name")
    }

    private fun writeResponse(
        requestId: String,
        responseFile: File,
        response: NanoHTTPD.Response,
    ) {
        response.data.use { input ->
            val prefix = readPrefix(input, INLINE_RESPONSE_BYTES + 1)
            var downloadFile: File? = null
            try {
                val envelope =
                    JSONObject()
                        .put("version", PROTOCOL_VERSION)
                        .put("status", response.status.requestStatus)
                        .put("statusText", response.status.description)
                        .put("mimeType", response.mimeType ?: "application/octet-stream")
                if (prefix.size <= INLINE_RESPONSE_BYTES) {
                    envelope
                        .put("size", prefix.size)
                        .put("body", Base64.getUrlEncoder().withoutPadding().encodeToString(prefix))
                } else {
                    val stagedFile = File(stagingDir, "$requestId.download")
                    downloadFile = stagedFile
                    SequenceInputStream(ByteArrayInputStream(prefix), input).use { combined ->
                        SecureFile.writeStream(stagedFile, combined, MAX_RESPONSE_BYTES.toLong())
                    }
                    requireRegularFile(stagedFile, 1, MAX_RESPONSE_BYTES.toLong())
                    envelope
                        .put("size", stagedFile.length())
                        .put("downloadId", requestId)
                }
                SecureFile.writeText(responseFile, envelope.toString())
            } catch (error: Throwable) {
                downloadFile?.let(::deleteRegularFile)
                throw error
            } finally {
                prefix.fill(0)
            }
        }
    }

    private fun writeErrorResponse(
        responseFile: File,
        status: NanoHTTPD.Response.Status,
        message: String,
    ) {
        runCatching {
            val bytes = message.toByteArray(Charsets.UTF_8)
            try {
                val envelope =
                    JSONObject()
                        .put("version", PROTOCOL_VERSION)
                        .put("status", status.requestStatus)
                        .put("statusText", status.description)
                        .put("mimeType", NanoHTTPD.MIME_PLAINTEXT)
                        .put("size", bytes.size)
                        .put("body", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
                SecureFile.writeText(responseFile, envelope.toString())
            } finally {
                bytes.fill(0)
            }
        }.onFailure { Logger.e("WebUI bridge could not publish an error response", it) }
    }

    private fun ensureLayout() {
        SecureFile.mkdirs(bridgeDir, DIRECTORY_MODE)
        SecureFile.mkdirs(requestDir, DIRECTORY_MODE)
        SecureFile.mkdirs(responseDir, DIRECTORY_MODE)
        SecureFile.mkdirs(stagingDir, DIRECTORY_MODE)
    }

    private fun cleanupStale() {
        val cutoff = System.currentTimeMillis() - STALE_AGE_MS
        listOf(requestDir, responseDir, stagingDir).forEach { directory ->
            directory.listFiles()
                ?.asSequence()
                ?.filter { it.lastModified() in 1 until cutoff }
                ?.take(MAX_CLEANUP_FILES)
                ?.forEach(::deleteRegularFile)
        }
    }

    private fun requireRegularFile(
        file: File,
        minimumBytes: Long,
        maximumBytes: Long,
    ) {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        require(file.length() in minimumBytes..maximumBytes)
    }

    private fun readLimited(
        file: File,
        maximumBytes: Int,
    ): ByteArray {
        val path = file.toPath()
        val length = Files.size(path)
        require(length in 1..maximumBytes.toLong())
        val output = ByteArray(length.toInt())
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                var total = 0
                var emptyReads = 0
                while (total < output.size) {
                    val count = input.read(output, total, output.size - total)
                    if (count < 0) throw IOException("Bridge payload ended early")
                    if (count == 0) {
                        if (++emptyReads > MAX_EMPTY_READS) throw IOException("Bridge payload stream stalled")
                        continue
                    }
                    emptyReads = 0
                    total += count
                }
                if (input.read() >= 0) throw IOException("Bridge payload exceeds its size limit")
            }
            return output
        } catch (error: Throwable) {
            output.fill(0)
            throw error
        }
    }

    private fun readPrefix(
        input: InputStream,
        maximumBytes: Int,
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
        var emptyReads = 0
        try {
            while (output.size() < maximumBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maximumBytes - output.size()))
                if (count < 0) break
                if (count == 0) {
                    if (++emptyReads > MAX_EMPTY_READS) throw IOException("Bridge response stream stalled")
                    continue
                }
                emptyReads = 0
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }

    private fun deleteRegularFile(file: File) {
        runCatching {
            if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(file.toPath())
        }.onFailure { Logger.w("WebUI bridge could not remove ${file.name}") }
    }

    @Suppress("DEPRECATION")
    private class BridgeSession(
        private val requestMethod: NanoHTTPD.Method,
        private val requestUri: String,
        private val requestHeaders: Map<String, String>,
        private val requestParameters: Map<String, List<String>>,
        private val uploadField: String?,
        private val uploadFile: File?,
    ) : NanoHTTPD.IHTTPSession {
        override fun execute() = Unit

        override fun getCookies(): NanoHTTPD.CookieHandler? = null

        @Deprecated("NanoHTTPD API")
        override fun getHeaders(): Map<String, String> = requestHeaders

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getMethod(): NanoHTTPD.Method = requestMethod

        @Deprecated("NanoHTTPD API")
        override fun getParms(): Map<String, String> = requestParameters.mapValues { it.value.first() }

        override fun getParameters(): Map<String, List<String>> = requestParameters

        @Deprecated("NanoHTTPD API")
        override fun getQueryParameterString(): String = ""

        override fun getUri(): String = requestUri

        override fun parseBody(files: MutableMap<String, String>?) {
            if (files != null && uploadField != null && uploadFile != null) files[uploadField] = uploadFile.absolutePath
        }

        @Deprecated("NanoHTTPD API")
        override fun getRemoteIpAddress(): String = "native-webui"

        @Deprecated("NanoHTTPD API")
        override fun getRemoteHostName(): String = "native-webui"
    }

    companion object {
        private const val PROTOCOL_VERSION = 1
        private const val DIRECTORY_MODE = 448
        private const val MAX_REQUEST_BYTES = 1024 * 1024L
        private const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024L
        private const val MAX_RESPONSE_BYTES = 20 * 1024 * 1024
        private const val INLINE_RESPONSE_BYTES = 256 * 1024
        private const val MAX_PARAMETER_KEYS = 128
        private const val MAX_PARAMETER_VALUES = 32
        private const val MAX_PENDING_REQUESTS = 64
        private const val MAX_CLEANUP_FILES = 1024
        private const val MAX_EMPTY_READS = 16
        private const val FALLBACK_INTERVAL_SECONDS = 2L
        private const val STALE_AGE_MS = 10 * 60 * 1000L
        private val REQUEST_FIELDS = setOf("version", "method", "path", "parameters", "uploadId", "uploadField")
        private val REQUEST_NAME = Regex("([0-9a-f]{32})\\.request")
        private val ID_PATTERN = Regex("[0-9a-f]{32}")
        private val FIELD_PATTERN = Regex("[A-Za-z0-9_.-]{1,64}")
    }
}
