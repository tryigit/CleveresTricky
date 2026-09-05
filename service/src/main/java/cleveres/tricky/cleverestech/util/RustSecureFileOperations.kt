package cleveres.tricky.cleverestech.util

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import cleveres.tricky.cleverestech.utf8ByteLength
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Thin Android adapter for descriptor-relative mutations owned by the privileged Rust daemon. */
internal class RustSecureFileOperations : SecureFileOperations, RestoreFileOperations {
    override fun writeText(
        file: File,
        content: String,
    ) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        try {
            writeBytes(file, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    override fun writeBytes(
        file: File,
        content: ByteArray,
    ) {
        require(content.size <= MAX_FILE_BYTES) { "File exceeds the Rust broker size limit" }
        ByteArrayInputStream(content).use { input ->
            transactAtomicWrite(relativePath(file), input, content.size)
        }
    }

    override fun writeStream(
        file: File,
        inputStream: InputStream,
        limit: Long,
    ) {
        require(limit in 0L..MAX_FILE_BYTES.toLong()) { "Invalid Rust broker streaming limit" }
        val relative = relativePath(file)
        if (isWebUiDownloadPath(relative)) {
            transactControl(ACTION_STAGE_CREATE, relative.toByteArray(Charsets.UTF_8))
            val scratch = ByteArray(STREAM_BUFFER_BYTES)
            streamBoundedChunks(inputStream, limit, scratch) { bytes, count ->
                transactStageAppend(relative, bytes, count)
            }
            return
        }

        // Existing config callers use writeStream only when the exact encoded length is already
        // known (for example the fixed-size privacy seed). Keep that path atomic through the
        // descriptor-relative broker. WebUI download staging above is the maximum-bound stream
        // where the final length is intentionally unknown in advance.
        transactAtomicWrite(relative, inputStream, limit.toInt())
    }

    override fun mkdirs(
        file: File,
        mode: Int,
    ) {
        require(mode == DIRECTORY_MODE) { "Rust broker only accepts private config directories" }
        if (file.absolutePath == CONFIG_ROOT) {
            awaitAdapterRegistration()
            return
        }
        transactControl(ACTION_MKDIR, relativePathBytes(file))
    }

    override fun touch(
        file: File,
        mode: Int,
    ) {
        require(mode == FILE_MODE) { "Rust broker only accepts private config files" }
        transactControl(ACTION_TOUCH, relativePathBytes(file))
    }

    override fun begin(
        configDir: File,
        token: String,
        maxSnapshotBytes: Long,
    ) {
        requireConfigRoot(configDir)
        validateRestoreToken(token)
        require(maxSnapshotBytes in 0L..MAX_RESTORE_SNAPSHOT_BYTES) {
            "Restore snapshot limit exceeds the Rust broker bound"
        }
        transactControl(
            ACTION_RESTORE_BEGIN,
            restorePairBytes(token, maxSnapshotBytes.toString()),
        )
    }

    override fun snapshot(
        configDir: File,
        token: String,
        target: File,
    ) {
        requireConfigRoot(configDir)
        validateRestoreToken(token)
        transactControl(
            ACTION_RESTORE_SNAPSHOT,
            restorePairBytes(token, restoreRelativePath(target)),
        )
    }

    override fun replace(
        configDir: File,
        token: String,
        target: File,
        content: ByteArray,
    ) {
        requireConfigRoot(configDir)
        validateRestoreToken(token)
        require(content.size <= MAX_FILE_BYTES) { "File exceeds the Rust broker size limit" }
        val relative = restoreRelativePath(target)
        ByteArrayInputStream(content).use { input ->
            transactAtomicWrite(restorePair(token, relative), input, content.size)
        }
    }

    override fun delete(
        configDir: File,
        token: String,
        target: File,
    ) {
        requireConfigRoot(configDir)
        validateRestoreToken(token)
        transactControl(
            ACTION_DELETE,
            restorePairBytes(token, restoreRelativePath(target)),
        )
    }

    override fun rollback(
        configDir: File,
        token: String,
    ) {
        requireConfigRoot(configDir)
        transactRestoreToken(ACTION_RESTORE_ROLLBACK, token)
    }

    override fun commit(
        configDir: File,
        token: String,
    ) {
        requireConfigRoot(configDir)
        transactRestoreToken(ACTION_RESTORE_COMMIT, token)
    }

    override fun abort(
        configDir: File,
        token: String,
    ) {
        requireConfigRoot(configDir)
        transactRestoreToken(ACTION_RESTORE_ABORT, token)
    }

    override fun exportRecovery(
        configDir: File,
        token: String,
    ): String {
        requireConfigRoot(configDir)
        transactRestoreToken(ACTION_RESTORE_EXPORT, token)
        return File(configDir, ".restore-recovery-$token.manifest").absolutePath
    }

    private fun awaitAdapterRegistration() {
        var delayMs = STARTUP_RETRY_INITIAL_MS
        var lastError: IOException? = null
        repeat(STARTUP_RETRY_ATTEMPTS) { attempt ->
            try {
                transactControl(ACTION_ROOT_VALIDATE, EMPTY_BYTES)
                return
            } catch (error: IOException) {
                lastError = error
            }

            if (attempt + 1 < STARTUP_RETRY_ATTEMPTS) {
                try {
                    Thread.sleep(delayMs)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for Rust daemon adapter registration", error)
                }
                delayMs = minOf(delayMs * 2, STARTUP_RETRY_MAX_MS)
            }
        }
        throw IOException("Rust daemon did not recognize the Android adapter", lastError)
    }

    private fun transactAtomicWrite(
        relativePath: String,
        input: InputStream,
        declaredBodyLength: Int,
    ) {
        val pathBytes = relativePath.toByteArray(Charsets.UTF_8)
        val scratch = ByteArray(STREAM_BUFFER_BYTES)
        try {
            require(pathBytes.size <= MAX_RELATIVE_PATH_BYTES)
            val payloadLength =
                Math.addExact(
                    Math.addExact(REQUEST_PREFIX_BYTES, pathBytes.size),
                    Math.addExact(declaredBodyLength, WRITE_COMMIT_BYTES),
                )
            require(payloadLength <= MAX_REQUEST_BYTES)
            LocalSocket().use { socket ->
                connectVerified(socket)
                val output = socket.outputStream
                writeHeader(output, payloadLength)
                writeRequestPrefix(output, ACTION_WRITE, pathBytes.size, declaredBodyLength)
                output.write(pathBytes)
                copyDeclaredBody(input, output, declaredBodyLength, scratch)
                output.write(WRITE_COMMIT_MARKER)
                output.flush()
                readSuccessResponse(socket.inputStream)
            }
        } catch (error: ArithmeticException) {
            throw IOException("Rust file request size overflow", error)
        } finally {
            pathBytes.fill(0)
            scratch.fill(0)
        }
    }

    private fun transactStageAppend(
        relativePath: String,
        bytes: ByteArray,
        count: Int,
    ) {
        require(count in 1..STREAM_BUFFER_BYTES)
        val pathBytes = relativePath.toByteArray(Charsets.UTF_8)
        try {
            require(pathBytes.size <= MAX_RELATIVE_PATH_BYTES)
            val payloadLength =
                Math.addExact(
                    Math.addExact(REQUEST_PREFIX_BYTES, pathBytes.size),
                    Math.addExact(count, WRITE_COMMIT_BYTES),
                )
            LocalSocket().use { socket ->
                connectVerified(socket)
                val output = socket.outputStream
                writeHeader(output, payloadLength)
                writeRequestPrefix(output, ACTION_STAGE_APPEND, pathBytes.size, count)
                output.write(pathBytes)
                output.write(bytes, 0, count)
                output.write(WRITE_COMMIT_MARKER)
                output.flush()
                readSuccessResponse(socket.inputStream)
            }
        } catch (error: ArithmeticException) {
            throw IOException("Rust staging request size overflow", error)
        } finally {
            pathBytes.fill(0)
        }
    }

    private fun transactRestoreToken(
        action: Int,
        token: String,
    ) {
        validateRestoreToken(token)
        transactControl(action, token.toByteArray(Charsets.UTF_8))
    }

    private fun transactControl(
        action: Int,
        pathBytes: ByteArray,
    ) {
        try {
            require(pathBytes.size <= MAX_RELATIVE_PATH_BYTES)
            require(action == ACTION_ROOT_VALIDATE || pathBytes.isNotEmpty())
            require(action != ACTION_ROOT_VALIDATE || pathBytes.isEmpty())
            val payloadLength = Math.addExact(REQUEST_PREFIX_BYTES, pathBytes.size)
            LocalSocket().use { socket ->
                connectVerified(socket)
                val output = socket.outputStream
                writeHeader(output, payloadLength)
                writeRequestPrefix(output, action, pathBytes.size, 0)
                if (pathBytes.isNotEmpty()) output.write(pathBytes)
                output.flush()
                readSuccessResponse(socket.inputStream)
            }
        } finally {
            pathBytes.fill(0)
        }
    }

    private fun readSuccessResponse(input: InputStream) {
        val header = ByteArray(HEADER_BYTES)
        try {
            readFully(input, header)
            validateResponseHeader(header)
            val responseLength = readU32(header, 12)
            if (responseLength > MAX_RESPONSE_BYTES) throw IOException("Rust file response exceeds bound")
            val response = ByteArray(responseLength.toInt())
            try {
                readFully(input, response)
                if (readI32(header, 8) != 0 || !response.contentEquals(OK_BYTES)) {
                    throw IOException("Rust file operation was rejected")
                }
            } finally {
                response.fill(0)
            }
        } finally {
            header.fill(0)
        }
    }

    private fun writeRequestPrefix(
        output: OutputStream,
        action: Int,
        pathLength: Int,
        bodyLength: Int,
    ) {
        require(pathLength in 0..MAX_RELATIVE_PATH_BYTES)
        require(bodyLength in 0..MAX_FILE_BYTES)
        output.write(action)
        output.write((pathLength ushr 8) and 0xff)
        output.write(pathLength and 0xff)
        output.write((bodyLength ushr 24) and 0xff)
        output.write((bodyLength ushr 16) and 0xff)
        output.write((bodyLength ushr 8) and 0xff)
        output.write(bodyLength and 0xff)
    }

    private fun connectVerified(socket: LocalSocket) {
        socket.connect(LocalSocketAddress(FILE_SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
        val peer = socket.peerCredentials
        val parentPid = Os.getppid()
        if (parentPid <= 1 || peer.uid != 0 || peer.gid != 0 || peer.pid != parentPid) {
            throw IOException("Unexpected privileged Rust daemon peer")
        }
        socket.setSoTimeout(IO_TIMEOUT_MS)
    }

    private fun requireConfigRoot(configDir: File) {
        if (configDir.absoluteFile.toPath().normalize().toString() != CONFIG_ROOT) {
            throw SecurityException("Restore root does not match the Rust config capability")
        }
    }

    private fun restorePair(
        token: String,
        argument: String,
    ): String {
        validateRestoreToken(token)
        if (argument.isEmpty() || '\u0000' in argument) {
            throw SecurityException("Invalid restore transaction argument")
        }
        return "$token\u0000$argument"
    }

    private fun restorePairBytes(
        token: String,
        argument: String,
    ): ByteArray = restorePair(token, argument).toByteArray(Charsets.UTF_8)

    private fun validateRestoreToken(token: String) {
        if (token.length != RESTORE_TOKEN_LENGTH || token.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            throw SecurityException("Invalid restore transaction token")
        }
    }

    private fun relativePathBytes(file: File): ByteArray = relativePath(file).toByteArray(Charsets.UTF_8)

    private fun restoreRelativePath(file: File): String {
        val relative = relativePath(file)
        val components = relative.split('/')
        if (components.size != 1 && !(components.size == 2 && components[0] == KEYBOX_DIRECTORY)) {
            throw IOException("Restore target is outside an allowed capability subtree")
        }
        return relative
    }

    private fun relativePath(file: File): String {
        val absolute = file.absolutePath
        val prefix = "$CONFIG_ROOT/"
        if (!absolute.startsWith(prefix)) throw IOException("File is outside the Rust config root")
        val relative = absolute.substring(prefix.length)
        val components = relative.split('/')
        if (components.isEmpty() || components.size > 3) throw IOException("Config path depth exceeds bound")
        components.forEach(::requireComponent)

        val allowed =
            when (components.size) {
                1 -> true
                2 ->
                    components[0] == KEYBOX_DIRECTORY ||
                        (components[0] == WEBUI_DIRECTORY && components[1] == WEBUI_STAGING_DIRECTORY)
                3 ->
                    components[0] == WEBUI_DIRECTORY &&
                        components[1] == WEBUI_STAGING_DIRECTORY &&
                        isWebUiDownloadName(components[2])
                else -> false
            }
        if (!allowed) throw IOException("Config path is outside an allowed capability subtree")
        return components.joinToString("/")
    }

    private fun isWebUiDownloadPath(relative: String): Boolean {
        val components = relative.split('/')
        return components.size == 3 &&
            components[0] == WEBUI_DIRECTORY &&
            components[1] == WEBUI_STAGING_DIRECTORY &&
            isWebUiDownloadName(components[2])
    }

    private fun isWebUiDownloadName(value: String): Boolean {
        if (!value.endsWith(WEBUI_DOWNLOAD_SUFFIX)) return false
        val id = value.removeSuffix(WEBUI_DOWNLOAD_SUFFIX)
        return id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' }
    }

    private fun requireComponent(component: String) {
        if (component.isEmpty() || component == "." || component == ".." ||
            component.utf8ByteLength() > MAX_COMPONENT_BYTES || '\u0000' in component
        ) {
            throw IOException("Invalid config path component")
        }
    }

    private fun writeHeader(
        output: OutputStream,
        payloadLength: Int,
    ) {
        val header = ByteArray(HEADER_BYTES)
        try {
            IPC_MAGIC.copyInto(header)
            writeU16(header, 4, IPC_VERSION)
            writeU16(header, 6, OP_FILE_WRITE)
            writeI32(header, 8, 0)
            writeI32(header, 12, payloadLength)
            output.write(header)
        } finally {
            header.fill(0)
        }
    }

    private fun validateResponseHeader(header: ByteArray) {
        for (index in IPC_MAGIC.indices) {
            if (header[index] != IPC_MAGIC[index]) throw IOException("Invalid Rust file response magic")
        }
        if (readU16(header, 4) != IPC_VERSION || readU16(header, 6) != OP_FILE_WRITE) {
            throw IOException("Invalid Rust file response header")
        }
        val flags = readI32(header, 8)
        if (flags != 0 && flags != FLAG_ERROR) throw IOException("Invalid Rust file response flags")
    }

    private fun readFully(
        input: InputStream,
        output: ByteArray,
    ) {
        var offset = 0
        var emptyReads = 0
        while (offset < output.size) {
            val count = input.read(output, offset, output.size - offset)
            if (count < 0) throw IOException("Rust file response ended early")
            if (count == 0) {
                if (++emptyReads > MAX_EMPTY_READS) throw IOException("Rust file response stalled")
                continue
            }
            emptyReads = 0
            offset += count
        }
    }

    private fun readU16(
        bytes: ByteArray,
        offset: Int,
    ): Int = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readI32(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun readU32(
        bytes: ByteArray,
        offset: Int,
    ): Long = readI32(bytes, offset).toLong() and 0xffff_ffffL

    private fun writeU16(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun writeI32(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private companion object {
        const val CONFIG_ROOT = "/data/adb/cleverestricky"
        const val KEYBOX_DIRECTORY = "keyboxes"
        const val WEBUI_DIRECTORY = "webui_bridge"
        const val WEBUI_STAGING_DIRECTORY = "staging"
        const val WEBUI_DOWNLOAD_SUFFIX = ".download"
        const val FILE_SOCKET_NAME = "cleverestrickyd.files.v1"
        const val IPC_VERSION = 1
        const val OP_FILE_WRITE = 10
        const val FLAG_ERROR = 1
        const val ACTION_WRITE = 0
        const val ACTION_MKDIR = 1
        const val ACTION_TOUCH = 2
        const val ACTION_ROOT_VALIDATE = 3
        const val ACTION_STAGE_CREATE = 4
        const val ACTION_STAGE_APPEND = 5
        const val ACTION_RESTORE_BEGIN = 6
        const val ACTION_RESTORE_SNAPSHOT = 7
        const val ACTION_RESTORE_ROLLBACK = 8
        const val ACTION_RESTORE_COMMIT = 9
        const val ACTION_RESTORE_ABORT = 10
        const val ACTION_DELETE = 11
        const val ACTION_RESTORE_EXPORT = 12
        const val WRITE_COMMIT_MARKER = 0xa5
        const val HEADER_BYTES = 16
        const val REQUEST_PREFIX_BYTES = 7
        const val WRITE_COMMIT_BYTES = 1
        const val FILE_MODE = 384
        const val DIRECTORY_MODE = 448
        const val MAX_FILE_BYTES = 20 * 1024 * 1024
        const val MAX_RESTORE_SNAPSHOT_BYTES = 32L * 1024L * 1024L
        const val RESTORE_TOKEN_LENGTH = 32
        const val MAX_RELATIVE_PATH_BYTES = 511
        const val MAX_COMPONENT_BYTES = 255
        const val MAX_REQUEST_BYTES = REQUEST_PREFIX_BYTES + MAX_RELATIVE_PATH_BYTES + MAX_FILE_BYTES + WRITE_COMMIT_BYTES
        const val MAX_RESPONSE_BYTES = 512L
        const val IO_TIMEOUT_MS = 30_000
        const val MAX_EMPTY_READS = 16
        const val STREAM_BUFFER_BYTES = 64 * 1024
        const val STARTUP_RETRY_ATTEMPTS = 12
        const val STARTUP_RETRY_INITIAL_MS = 25L
        const val STARTUP_RETRY_MAX_MS = 500L
        val IPC_MAGIC = byteArrayOf('C'.code.toByte(), 'T'.code.toByte(), 'I'.code.toByte(), 'P'.code.toByte())
        val OK_BYTES = byteArrayOf('o'.code.toByte(), 'k'.code.toByte())
        val EMPTY_BYTES = ByteArray(0)
    }
}
