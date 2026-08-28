package cleveres.tricky.cleverestech

import android.net.LocalSocket
import android.net.LocalSocketAddress
import cleveres.tricky.cleverestech.util.FastByteArrayOutputStream
import cleveres.tricky.cleverestech.util.SecureFile
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class WebUiBridge(
    private val server: WebServer,
    configDir: File,
    private val startupReady: CountDownLatch = CountDownLatch(0),
    private val startupWaitMs: Long = STARTUP_WAIT_MS,
) {
    private val bridgeDir = File(configDir, "webui_bridge")
    private val stagingDir = File(bridgeDir, "staging")
    private val stagingLockFile = File(stagingDir, STAGING_LOCK_NAME)
    private val secureRandom = SecureRandom()

    @Volatile
    private var started = false
    private var socket: LocalSocket? = null
    private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (started) return
        ensureLayout()
        withStagingLock { cleanupStale() }

        val connected = LocalSocket()
        try {
            connected.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            connected.setSoTimeout(REGISTER_TIMEOUT_MS)
            val input = connected.inputStream
            val output = connected.outputStream
            val readHeaderBuffer = ByteArray(HEADER_BYTES)
            val writeHeaderBuffer = ByteArray(HEADER_BYTES)
            writeFrame(output, OP_ADAPTER_REGISTER, 0, EMPTY_BYTES, writeHeaderBuffer)
            val acknowledgement = readHeader(input, readHeaderBuffer)
            require(
                acknowledgement.opcode == OP_ADAPTER_REGISTER &&
                    acknowledgement.flags == 0 &&
                    acknowledgement.payloadLength == REGISTER_ACK.size,
            )
            val acknowledgementPayload = ByteArray(acknowledgement.payloadLength)
            try {
                readFully(input, acknowledgementPayload)
                require(acknowledgementPayload.contentEquals(REGISTER_ACK))
            } finally {
                acknowledgementPayload.fill(0)
            }
            connected.setSoTimeout(0)
        } catch (error: Throwable) {
            runCatching { connected.close() }
            throw error
        }

        socket = connected
        started = true
        worker =
            Thread({ serveLoop(connected) }, "CleveresTricky-WebUI").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
                start()
            }
        Logger.i("Native WebUI bridge is ready over bounded UDS IPC")
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        val activeSocket = socket
        socket = null
        runCatching { activeSocket?.close() }
        worker?.interrupt()
        worker = null
    }

    private fun serveLoop(connected: LocalSocket) {
        val readHeaderBuffer = ByteArray(HEADER_BYTES)
        val writeHeaderBuffer = ByteArray(HEADER_BYTES)
        try {
            val input = connected.inputStream
            val output = connected.outputStream
            while (started) {
                val header = readHeader(input, readHeaderBuffer)
                if (header.opcode != OP_WEB_REQUEST || header.flags != 0) {
                    throw IOException("Unexpected native WebUI IPC operation")
                }
                if (header.payloadLength !in 1..MAX_REQUEST_BYTES) {
                    throw IOException("Native WebUI request exceeds its size limit")
                }

                val requestBytes = ByteArray(header.payloadLength)
                var responseBytes: ByteArray? = null
                try {
                    readFully(input, requestBytes)
                    responseBytes = processRequestBytes(requestBytes)
                    writeFrame(output, OP_WEB_REQUEST, 0, responseBytes, writeHeaderBuffer)
                } finally {
                    requestBytes.fill(0)
                    responseBytes?.fill(0)
                }
            }
        } catch (error: Throwable) {
            if (started) {
                Logger.e("Native WebUI UDS transport failed; terminating adapter for supervisor recovery", error)
                exitProcess(1)
            }
        } finally {
            runCatching { connected.close() }
        }
    }

    internal fun processRequestBytes(requestBytes: ByteArray): ByteArray {
        require(requestBytes.size in 1..MAX_REQUEST_BYTES)
        if (!awaitStartupReady()) {
            return encodeErrorResponse(
                NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "Native WebUI runtime is starting; retry shortly",
            )
        }
        var uploadFile: File? = null
        return try {
            val request = JSONObject(decodeUtf8Strict(requestBytes))
            uploadFile =
                nullableString(request, "uploadId")
                    ?.takeIf(ID_PATTERN::matches)
                    ?.let { File(stagingDir, "$it.upload") }
            val parsed = parseRequest(request)
            uploadFile = parsed.uploadFile
            encodeResponse(server.serveBridge(parsed.session))
        } catch (error: IllegalArgumentException) {
            rejectRequest(error)
        } catch (error: JSONException) {
            rejectRequest(error)
        } catch (error: CharacterCodingException) {
            rejectRequest(error)
        } catch (error: Exception) {
            Logger.e("WebUI bridge request failed", error)
            encodeErrorResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "Native WebUI request failed")
        } finally {
            uploadFile?.let { file -> withStagingLock { deleteRegularFile(file) } }
        }
    }

    private fun awaitStartupReady(): Boolean =
        try {
            startupReady.await(startupWaitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun decodeUtf8Strict(requestBytes: ByteArray): String =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(requestBytes))
            .toString()

    private fun rejectRequest(error: Exception): ByteArray {
        Logger.w("WebUI bridge rejected a native request: ${error.message}")
        return encodeErrorResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid native WebUI request")
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
            val keyBytes = key.toByteArray(Charsets.UTF_8).size
            for (index in 0 until array.length()) {
                val value = array.get(index) as? String ?: throw IllegalArgumentException("Invalid parameter value")
                parameterBytes += keyBytes + value.toByteArray(Charsets.UTF_8).size + 2L
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
        require(contentLength <= MAX_REQUEST_BYTES.toLong() + MAX_UPLOAD_BYTES)
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

    internal fun encodeResponse(response: NanoHTTPD.Response): ByteArray {
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
                    val downloadId = randomId()
                    val stagedFile = File(stagingDir, "$downloadId.download")
                    downloadFile = stagedFile
                    val stagedSize = withStagingLock {
                        val (existingFiles, existingBytes) = stagingUsage()
                        ensureStagingCapacity(existingFiles, existingBytes, 1, 1)
                        val remainingBytes = MAX_STAGING_BYTES - existingBytes
                        SequenceInputStream(ByteArrayInputStream(prefix), input).use { combined ->
                            QuotaInputStream(combined, remainingBytes).use { bounded ->
                                SecureFile.writeStream(
                                    stagedFile,
                                    bounded,
                                    minOf(MAX_RESPONSE_BYTES.toLong(), remainingBytes),
                                )
                            }
                        }
                        requireRegularFile(stagedFile, 1, MAX_RESPONSE_BYTES.toLong())
                        stagedFile.length()
                    }
                    envelope
                        .put("size", stagedSize)
                        .put("downloadId", downloadId)
                }
                return encodeEnvelope(envelope)
            } catch (error: Throwable) {
                downloadFile?.let(::deleteRegularFile)
                throw error
            } finally {
                prefix.fill(0)
            }
        }
    }

    private fun randomId(): String {
        val random = ByteArray(16)
        secureRandom.nextBytes(random)
        return try {
            buildString(32) {
                random.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }
        } finally {
            random.fill(0)
        }
    }

    private fun encodeErrorResponse(
        status: NanoHTTPD.Response.Status,
        message: String,
    ): ByteArray {
        val bytes = message.toByteArray(Charsets.UTF_8)
        return try {
            encodeEnvelope(
                JSONObject()
                    .put("version", PROTOCOL_VERSION)
                    .put("status", status.requestStatus)
                    .put("statusText", status.description)
                    .put("mimeType", NanoHTTPD.MIME_PLAINTEXT)
                    .put("size", bytes.size)
                    .put("body", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)),
            )
        } finally {
            bytes.fill(0)
        }
    }

    private fun encodeEnvelope(envelope: JSONObject): ByteArray {
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_RESPONSE_ENVELOPE_BYTES) { "Native WebUI response envelope is too large" }
        return bytes
    }

    private fun ensureLayout() {
        SecureFile.mkdirs(bridgeDir, DIRECTORY_MODE)
        SecureFile.mkdirs(stagingDir, DIRECTORY_MODE)
    }

    private fun <T> withStagingLock(action: () -> T): T = synchronized(STAGING_LOCK_MONITOR) {
        FileChannel.open(
            stagingLockFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { lockFile ->
            lockFile.lock().use { action() }
        }
    }

    private fun stagingUsage(): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        Files.newDirectoryStream(stagingDir.toPath()).use { entries ->
            var scanned = 0
            for (path in entries) {
                if (++scanned > MAX_STAGING_SCAN_ENTRIES) {
                    throw IOException("WebUI staging directory contains too many entries")
                }
                val name = path.fileName.toString()
                if (!STAGING_FILE_PATTERN.matches(name)) continue
                val size =
                    Files.newByteChannel(
                        path,
                        StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS,
                    ).use { channel -> channel.size() }
                files = Math.addExact(files, 1)
                bytes = Math.addExact(bytes, size)
            }
        }
        return files to bytes
    }

    private fun ensureStagingCapacity(
        existingFiles: Int,
        existingBytes: Long,
        additionalFiles: Int,
        additionalBytes: Long,
    ) {
        require(existingFiles >= 0 && existingBytes >= 0L)
        require(additionalFiles >= 0 && additionalBytes >= 0L)
        require(Math.addExact(existingFiles, additionalFiles) <= MAX_STAGING_FILES) {
            "WebUI staging file quota exceeded"
        }
        require(Math.addExact(existingBytes, additionalBytes) <= MAX_STAGING_BYTES) {
            "WebUI staging byte quota exceeded"
        }
    }

    internal fun cleanupStale() {
        val cutoff = System.currentTimeMillis() - STALE_AGE_MS
        try {
            Files.newDirectoryStream(stagingDir.toPath()).use { entries ->
                var inspected = 0
                for (path in entries) {
                    if (inspected++ >= MAX_CLEANUP_FILES) break
                    if (path.fileName.toString() == STAGING_LOCK_NAME) continue
                    val lastModified =
                        try {
                            Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
                        } catch (_: Exception) {
                            continue
                        }
                    if (lastModified in 1 until cutoff) deleteRegularFile(path.toFile())
                }
            }
        } catch (error: Exception) {
            Logger.w(
                "WebUI bridge could not inspect stale staging entries: " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
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

    private class QuotaInputStream(
        private val delegate: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining == 0L) {
                if (delegate.read() >= 0) throw IOException("WebUI staging byte quota exceeded")
                return -1
            }
            val value = delegate.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            if (remaining == 0L) {
                if (delegate.read() >= 0) throw IOException("WebUI staging byte quota exceeded")
                return -1
            }
            val count = delegate.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count > 0) remaining -= count.toLong()
            return count
        }

        override fun close() {
            delegate.close()
        }
    }

    private fun readPrefix(
        input: InputStream,
        maximumBytes: Int,
    ): ByteArray {
        val output = FastByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
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
            output.wipe()
        }
    }

    private fun deleteRegularFile(file: File) {
        runCatching {
            if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(file.toPath())
        }.onFailure { Logger.w("WebUI bridge could not remove ${file.name}") }
    }

    private data class FrameHeader(
        val opcode: Int,
        val flags: Int,
        val payloadLength: Int,
    )

    private fun readHeader(
        input: InputStream,
        buffer: ByteArray,
    ): FrameHeader {
        readFully(input, buffer)
        for (index in IPC_MAGIC.indices) {
            if (buffer[index] != IPC_MAGIC[index]) throw IOException("Invalid native WebUI IPC magic")
        }
        val version = readU16(buffer, 4)
        if (version != IPC_VERSION) throw IOException("Unsupported native WebUI IPC version")
        val opcode = readU16(buffer, 6)
        if (opcode == 0) throw IOException("Invalid native WebUI IPC opcode")
        val flags = readI32(buffer, 8)
        val payloadLength = readU32(buffer, 12)
        if (payloadLength > MAX_FRAME_BYTES.toLong()) throw IOException("Native WebUI IPC frame is too large")
        return FrameHeader(opcode, flags, payloadLength.toInt())
    }

    private fun writeFrame(
        output: OutputStream,
        opcode: Int,
        flags: Int,
        payload: ByteArray,
        buffer: ByteArray,
    ) {
        require(opcode in 1..0xffff)
        require(payload.size <= MAX_FRAME_BYTES)
        buffer.fill(0)
        IPC_MAGIC.copyInto(buffer, 0)
        writeU16(buffer, 4, IPC_VERSION)
        writeU16(buffer, 6, opcode)
        writeI32(buffer, 8, flags)
        writeI32(buffer, 12, payload.size)
        output.write(buffer)
        if (payload.isNotEmpty()) output.write(payload)
        output.flush()
    }

    private fun readFully(
        input: InputStream,
        output: ByteArray,
    ) {
        var offset = 0
        var emptyReads = 0
        while (offset < output.size) {
            val count = input.read(output, offset, output.size - offset)
            if (count < 0) throw IOException("Native WebUI IPC frame ended early")
            if (count == 0) {
                if (++emptyReads > MAX_EMPTY_READS) throw IOException("Native WebUI IPC stream stalled")
                continue
            }
            emptyReads = 0
            offset += count
        }
    }

    private fun readU16(
        buffer: ByteArray,
        offset: Int,
    ): Int = ((buffer[offset].toInt() and 0xff) shl 8) or (buffer[offset + 1].toInt() and 0xff)

    private fun readI32(
        buffer: ByteArray,
        offset: Int,
    ): Int =
        ((buffer[offset].toInt() and 0xff) shl 24) or
            ((buffer[offset + 1].toInt() and 0xff) shl 16) or
            ((buffer[offset + 2].toInt() and 0xff) shl 8) or
            (buffer[offset + 3].toInt() and 0xff)

    private fun readU32(
        buffer: ByteArray,
        offset: Int,
    ): Long =
        ((buffer[offset].toLong() and 0xffL) shl 24) or
            ((buffer[offset + 1].toLong() and 0xffL) shl 16) or
            ((buffer[offset + 2].toLong() and 0xffL) shl 8) or
            (buffer[offset + 3].toLong() and 0xffL)

    private fun writeU16(
        buffer: ByteArray,
        offset: Int,
        value: Int,
    ) {
        buffer[offset] = (value ushr 8).toByte()
        buffer[offset + 1] = value.toByte()
    }

    private fun writeI32(
        buffer: ByteArray,
        offset: Int,
        value: Int,
    ) {
        buffer[offset] = (value ushr 24).toByte()
        buffer[offset + 1] = (value ushr 16).toByte()
        buffer[offset + 2] = (value ushr 8).toByte()
        buffer[offset + 3] = value.toByte()
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
        private const val MAX_REQUEST_BYTES = 1024 * 1024
        private const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 20 * 1024 * 1024
        private const val INLINE_RESPONSE_BYTES = 256 * 1024
        private const val MAX_RESPONSE_ENVELOPE_BYTES = 512 * 1024
        private const val MAX_PARAMETER_KEYS = 128
        private const val MAX_PARAMETER_VALUES = 32
        private const val MAX_CLEANUP_FILES = 1024
        private const val MAX_STAGING_FILES = 32
        private const val MAX_STAGING_BYTES = 64L * 1024 * 1024
        private const val MAX_STAGING_SCAN_ENTRIES = 1024
        private const val STAGING_LOCK_NAME = ".staging.lock"
        private val STAGING_LOCK_MONITOR = Any()
        private val STAGING_FILE_PATTERN = Regex("[0-9a-f]{32}\\.(request|upload|download|export)")
        private const val MAX_EMPTY_READS = 16

        private const val STALE_AGE_MS = 10 * 60 * 1000L
        private const val STARTUP_WAIT_MS = 5_000L
        private const val SOCKET_NAME = "cleverestrickyd.v1"
        private const val IPC_VERSION = 1
        private const val HEADER_BYTES = 16
        private const val MAX_FRAME_BYTES = MAX_REQUEST_BYTES
        private const val OP_ADAPTER_REGISTER = 2
        private const val OP_WEB_REQUEST = 3
        private const val REGISTER_TIMEOUT_MS = 5_000
        private const val HEX = "0123456789abcdef"
        private val IPC_MAGIC = byteArrayOf('C'.code.toByte(), 'T'.code.toByte(), 'I'.code.toByte(), 'P'.code.toByte())
        private val REGISTER_ACK = byteArrayOf('o'.code.toByte(), 'k'.code.toByte())
        private val EMPTY_BYTES = ByteArray(0)
        private val REQUEST_FIELDS = setOf("version", "method", "path", "parameters", "uploadId", "uploadField")
        private val ID_PATTERN = Regex("[0-9a-f]{32}")
        private val FIELD_PATTERN = Regex("[A-Za-z0-9_.-]{1,64}")
    }
}
