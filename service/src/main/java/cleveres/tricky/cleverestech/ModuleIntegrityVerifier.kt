package cleveres.tricky.cleverestech

import android.net.LocalSocket
import android.net.LocalSocketAddress
import cleveres.tricky.cleverestech.util.sha256FileSnapshotBounded
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies the integrity of the module directory against a signed manifest.
 * Supports both full verification and targeted single-file verification.
 */
object ModuleIntegrityVerifier {

    private const val MANIFEST_VERSION = 1
    private const val MANIFEST_FILENAME = "integrity_manifest.json"
    private const val MAX_MANIFEST_BYTES = 64L * 1024
    private const val MAX_PAYLOAD_BYTES = 128L * 1024 * 1024
    private const val MAX_MODULE_ENTRIES = 4096
    private const val MAX_FRAME_BYTES = 1024 * 1024

    private const val OP_INTEGRITY_VERIFY_FULL = 0x30
    private const val OP_INTEGRITY_VERIFY_FILE = 0x31
    private const val FLAG_ERROR = 1
    private const val DAEMON_SOCKET_NAME = "cleverestrickyd.v1"

    const val TRUSTED_PUBLIC_KEY_HEX = "9f9f8b00a8c5e3c9849eed6c465b1d1f46747d3acbd74afb91290ebc40c1873c"

    private sealed interface DaemonQueryResult {
        data class Verdict(val result: IntegrityResult) : DaemonQueryResult

        object OperationalError : DaemonQueryResult
    }

    val fullVerificationCount = AtomicInteger(0)
    val targetedVerificationCount = AtomicInteger(0)

    @Volatile
    var cachedManifest: ParsedManifest? = null
        internal set

    internal var remoteDisabledForTesting = false
    internal var trustedPublicKeyProvider: () -> ByteArray = {
        val keyHex = runCatching { BuildConfig.INTEGRITY_PUBLIC_KEY }.getOrNull()?.trim()
        val validKeyHex = if (!keyHex.isNullOrEmpty() && keyHex.length == 64 && keyHex.all { it.digitToIntOrNull(16) != null }) {
            keyHex
        } else {
            TRUSTED_PUBLIC_KEY_HEX
        }
        hexToBytes(validKeyHex)
    }

    /**
     * Whether unsigned manifests are accepted. Controlled by build variant policy.
     * In production builds, this is false and unsigned manifests are strictly rejected.
     */
    @Volatile
    internal var allowUnsignedManifest: Boolean =
        runCatching { BuildConfig.ALLOW_UNSIGNED_MANIFEST }.getOrDefault(false)

    internal var moduleDirProvider: () -> String = { getModuleDir() }

    /**
     * Performs full integrity verification of all files in the manifest.
     * Queries the daemon for verification if available, otherwise verifies locally.
     */
    fun verifyFull(): IntegrityResult {
        fullVerificationCount.incrementAndGet()
        when (val daemonResult = queryDaemon(OP_INTEGRITY_VERIFY_FULL, trustedPublicKeyProvider())) {
            is DaemonQueryResult.Verdict -> return daemonResult.result
            DaemonQueryResult.OperationalError, null -> Unit
        }
        return verifyFullLocal()
    }

    /**
     * Verifies a single file against the manifest.
     * Queries the daemon if available, otherwise verifies locally using the provided or cached manifest.
     */
    fun verifySingleFile(
        relativePath: String,
        providedManifest: ParsedManifest? = cachedManifest,
    ): IntegrityResult {
        targetedVerificationCount.incrementAndGet()
        val payload = trustedPublicKeyProvider() + relativePath.toByteArray(Charsets.UTF_8)
        when (val daemonResult = queryDaemon(OP_INTEGRITY_VERIFY_FILE, payload)) {
            is DaemonQueryResult.Verdict -> return daemonResult.result
            DaemonQueryResult.OperationalError, null -> Unit
        }
        val manifest = providedManifest ?: cachedManifest ?: loadManifest()
        return verifySingleFileLocal(relativePath, manifest)
    }

    /**
     * Performs full local integrity verification by checking all manifest entries and scanning for unexpected files.
     */
    private fun verifyFullLocal(): IntegrityResult {
        val violations = mutableListOf<String>()
        val moduleDir = File(moduleDirProvider())

        if (!Files.isDirectory(moduleDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return IntegrityResult.Fail(listOf("Module directory does not exist or is a symlink: ${moduleDir.absolutePath}"))
        }

        val manifestFile = File(moduleDir, MANIFEST_FILENAME)
        val manifest = try {
            val loaded = loadAndVerifyManifest(manifestFile)
            cachedManifest = loaded
            loaded
        } catch (error: Exception) {
            return IntegrityResult.Fail(listOf("Manifest verification failed: ${error.message}"))
        }

        for (entry in manifest.files) {
            if (!isPathSafe(entry.path)) {
                violations.add("Path traversal or unsafe path: ${entry.path}")
                continue
            }

            val file = File(moduleDir, entry.path)
            val filePath = file.toPath()

            if (Files.isSymbolicLink(filePath)) {
                violations.add("Symlink detected for critical payload: ${entry.path}")
                continue
            }

            if (!Files.exists(filePath, LinkOption.NOFOLLOW_LINKS)) {
                violations.add("Missing critical payload: ${entry.path}")
                continue
            }

            if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
                violations.add("Wrong file type for critical payload: ${entry.path}")
                continue
            }

            if (!checkFileTypeMode(filePath, entry.type)) {
                violations.add("Wrong file mode for payload: ${entry.path} (expected ${entry.type})")
                continue
            }

            try {
                val actualHash = calculateSha256(file)
                try {
                    if (!MessageDigest.isEqual(hexToBytes(entry.sha256), actualHash)) {
                        violations.add("Hash mismatch for: ${entry.path} (expected ${entry.sha256}, got ${bytesToHex(actualHash)})")
                    }
                } finally {
                    actualHash.fill(0)
                }
            } catch (error: Exception) {
                violations.add("I/O error verifying ${entry.path}: ${error.message}")
            }
        }

        try {
            scanForUnexpectedFiles(moduleDir, manifest, violations)
        } catch (error: Exception) {
            violations.add("Failed to scan for unexpected files: ${error.message}")
        }

        return if (violations.isEmpty()) IntegrityResult.Pass else IntegrityResult.Fail(violations)
    }

    /**
     * Verifies a single file locally against the manifest entry.
     */
    private fun verifySingleFileLocal(relativePath: String, manifest: ParsedManifest?): IntegrityResult {
        if (manifest == null) {
            return IntegrityResult.Fail(listOf("No manifest available for single-file verification"))
        }
        val moduleDir = File(moduleDirProvider())

        val entry = manifest.files.find { it.path == relativePath }
        if (entry == null) {
            if (isIgnoredFile(relativePath)) return IntegrityResult.Pass
            return IntegrityResult.Fail(listOf("Unexpected file in module directory: $relativePath"))
        }

        val file = File(moduleDir, relativePath)
        val filePath = file.toPath()

        if (Files.isSymbolicLink(filePath)) {
            return IntegrityResult.Fail(listOf("Symlink detected for critical payload: $relativePath"))
        }
        if (!Files.exists(filePath, LinkOption.NOFOLLOW_LINKS)) {
            return IntegrityResult.Fail(listOf("Critical payload deleted: $relativePath"))
        }
        if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
            return IntegrityResult.Fail(listOf("Wrong file type for critical payload: $relativePath"))
        }
        if (!checkFileTypeMode(filePath, entry.type)) {
            return IntegrityResult.Fail(listOf("Wrong file mode for payload: $relativePath (expected ${entry.type})"))
        }

        return try {
            val actualHash = calculateSha256(file)
            try {
                if (!MessageDigest.isEqual(hexToBytes(entry.sha256), actualHash)) {
                    IntegrityResult.Fail(listOf("Hash mismatch for: $relativePath"))
                } else {
                    IntegrityResult.Pass
                }
            } finally {
                actualHash.fill(0)
            }
        } catch (error: Exception) {
            IntegrityResult.Fail(listOf("I/O error verifying $relativePath: ${error.message}"))
        }
    }

    /**
     * Validates POSIX file permissions for an entry type.
     * Enforces execute permissions for executables, while tolerating +x on regular files.
     */
    private fun checkFileTypeMode(filePath: java.nio.file.Path, expectedType: String): Boolean {
        val posixView = Files.getFileAttributeView(filePath, java.nio.file.attribute.PosixFileAttributeView::class.java)
        if (posixView != null) {
            val perms = try {
                posixView.readAttributes().permissions()
            } catch (_: Exception) {
                return expectedType != "executable"
            }
            val isExec = perms.any { it.name.endsWith("_EXECUTE") }
            // Only enforce that executables have execute bit set.
            // Do NOT reject regular files with execute bits - Android overlayfs,
            // KernelSU module mount, and ZIP extraction often set +x on all files.
            if (expectedType == "executable" && !isExec) return false
            return true
        }
        // Non-POSIX filesystem (e.g. host JVM on Windows) - skip mode check
        return true
    }

    /**
     * Loads and verifies the integrity manifest, caching it on success.
     * Returns the cached manifest if available, or null if loading fails.
     */
    fun loadManifest(): ParsedManifest? {
        cachedManifest?.let { return it }
        val moduleDir = File(moduleDirProvider())
        val manifestFile = File(moduleDir, MANIFEST_FILENAME)
        return try {
            val loaded = loadAndVerifyManifest(manifestFile)
            cachedManifest = loaded
            loaded
        } catch (error: Exception) {
            Logger.e("Failed to load integrity manifest", error)
            null
        }
    }

    /**
     * Queries the daemon for integrity verification via IPC socket.
     * Returns the verification result if successful, or null if the daemon is unavailable.
     */
    private fun queryDaemon(opcode: Int, payload: ByteArray): DaemonQueryResult? {
        if (remoteDisabledForTesting) return null
        return try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(DAEMON_SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                if (socket.peerCredentials.uid != 0) {
                    throw IOException("Unexpected integrity daemon peer")
                }
                socket.soTimeout = 5000
                val output = socket.outputStream
                val input = socket.inputStream

                val header = ByteArray(16)
                header[0] = 'C'.code.toByte()
                header[1] = 'T'.code.toByte()
                header[2] = 'I'.code.toByte()
                header[3] = 'P'.code.toByte()
                header[4] = 0
                header[5] = 1
                header[6] = (opcode ushr 8).toByte()
                header[7] = opcode.toByte()
                header[12] = (payload.size ushr 24).toByte()
                header[13] = (payload.size ushr 16).toByte()
                header[14] = (payload.size ushr 8).toByte()
                header[15] = payload.size.toByte()

                output.write(header)
                output.write(payload)
                output.flush()

                val respHeader = ByteArray(16)
                readFully(input, respHeader)
                val frameHeader = parseDaemonResponseHeader(opcode, respHeader)
                val respPayload = ByteArray(frameHeader.payloadLength)
                readFully(input, respPayload)
                decodeDaemonResponse(frameHeader.flags, respPayload)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private data class DaemonResponseHeader(
        val flags: Int,
        val payloadLength: Int,
    )

    private fun parseDaemonResponseHeader(
        expectedOpcode: Int,
        header: ByteArray,
    ): DaemonResponseHeader {
        if (header.size != 16 || !header.copyOfRange(0, 4).contentEquals("CTIP".toByteArray())) {
            throw IOException("Invalid integrity daemon response magic")
        }
        val version = readU16(header, 4)
        if (version != 1) throw IOException("Unsupported integrity daemon response version")
        if (readU16(header, 6) != expectedOpcode) throw IOException("Unexpected integrity daemon response opcode")
        val flags = readI32(header, 8)
        val payloadLength = readU32(header, 12)
        if (payloadLength > MAX_FRAME_BYTES.toLong()) {
            throw IOException("Integrity daemon response exceeds size limit")
        }
        return DaemonResponseHeader(flags, payloadLength.toInt())
    }

    private fun decodeDaemonResponse(
        flags: Int,
        payload: ByteArray,
    ): DaemonQueryResult {
        if (flags == FLAG_ERROR) return DaemonQueryResult.OperationalError
        if (flags != 0 || payload.isEmpty()) return DaemonQueryResult.OperationalError
        return when (payload[0].toInt()) {
            0 -> {
                if (payload.size != 1) DaemonQueryResult.OperationalError else DaemonQueryResult.Verdict(IntegrityResult.Pass)
            }
            1 -> {
                val message = String(payload, 1, payload.size - 1, Charsets.UTF_8).trim()
                    .ifEmpty { "Integrity check failed" }
                DaemonQueryResult.Verdict(IntegrityResult.Fail(listOf(message)))
            }
            else -> DaemonQueryResult.OperationalError
        }
    }

    private fun readFully(
        input: java.io.InputStream,
        buffer: ByteArray,
    ) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count <= 0) throw IOException("EOF reading integrity daemon response")
            offset += count
        }
    }

    private fun readU16(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)

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
    ): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    @androidx.annotation.VisibleForTesting
    internal fun decodeDaemonResponseForTesting(
        flags: Int,
        payload: ByteArray,
    ): IntegrityResult? = (decodeDaemonResponse(flags, payload) as? DaemonQueryResult.Verdict)?.result

    @androidx.annotation.VisibleForTesting
    internal fun daemonResponseLengthForTesting(
        expectedOpcode: Int,
        header: ByteArray,
    ): Int = parseDaemonResponseHeader(expectedOpcode, header).payloadLength

    /**
     * Loads the manifest file, parses it, and verifies the HMAC signature.
     */
    private fun loadAndVerifyManifest(manifestFile: File): ParsedManifest {
        if (!Files.isRegularFile(manifestFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw SecurityException("Manifest file is missing or not a regular file")
        }
        if (Files.isSymbolicLink(manifestFile.toPath())) {
            throw SecurityException("Manifest file is a symlink")
        }
        if (manifestFile.length() > MAX_MANIFEST_BYTES) {
            throw SecurityException("Manifest file exceeds size limit")
        }

        val manifestBytes = cleveres.tricky.cleverestech.util.readFileSnapshotBounded(
            manifestFile, 0, MAX_MANIFEST_BYTES
        )
        val manifestText = try {
            String(manifestBytes, Charsets.UTF_8)
        } finally {
            manifestBytes.fill(0)
        }

        val json = try {
            org.json.JSONObject(manifestText)
        } catch (e: Exception) {
            throw SecurityException("Manifest is not valid JSON: ${e.message}")
        }

        val version = json.optInt("version", -1)
        if (version != MANIFEST_VERSION) {
            throw SecurityException("Unsupported manifest version: $version")
        }

        val signature = json.optString("signature", "")

        val filesArray = json.optJSONArray("files")
            ?: throw SecurityException("Manifest has no files array")

        val files = mutableListOf<ManifestFileEntry>()
        val seenPaths = mutableSetOf<String>()
        for (i in 0 until filesArray.length()) {
            val entry = filesArray.getJSONObject(i)
            val path = entry.getString("path")
            val sha256 = entry.getString("sha256")
            val type = entry.optString("type", "regular")

            if (!isPathSafe(path)) {
                throw SecurityException("Unsafe path in manifest: $path")
            }
            if (sha256.length != 64 || sha256.any { it.digitToIntOrNull(16) == null }) {
                throw SecurityException("Invalid SHA-256 hex in manifest for: $path")
            }
            if (!seenPaths.add(path)) {
                throw SecurityException("Duplicate path in manifest: $path")
            }
            if (type != "regular" && type != "executable") {
                throw SecurityException("Invalid file type in manifest for $path: $type")
            }
            files.add(ManifestFileEntry(path, sha256, type))
        }

        if (signature.isNotEmpty()) {
            if (signature.length != 128 || signature.any { it.digitToIntOrNull(16) == null }) {
                throw SecurityException("Invalid manifest signature length or format")
            }
            val canonicalBytes = computeCanonicalData(version, files)
            val trustedPublicKey = trustedPublicKeyProvider()
            val signatureBytes = hexToBytes(signature)
            if (!verifyEd25519(trustedPublicKey, canonicalBytes, signatureBytes)) {
                throw SecurityException("Manifest digital signature verification failed")
            }
        } else {
            if (!allowUnsignedManifest) {
                throw SecurityException("Unsigned integrity manifest is prohibited in production builds")
            }
            Logger.w("Integrity manifest is unsigned (development/PR build) - hash verification only")
        }

        return ParsedManifest(version, files, signature)
    }

    /**
     * Verifies an Ed25519 digital signature over canonical data using the trusted raw 32-byte public key.
     */
    private fun verifyEd25519(publicKeyRaw: ByteArray, data: ByteArray, signatureBytes: ByteArray): Boolean {
        return try {
            val spkiHeader = byteArrayOf(
                0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
            )
            val spkiBytes = spkiHeader + publicKeyRaw
            val keyFactory = java.security.KeyFactory.getInstance("Ed25519")
            val pubKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(spkiBytes))
            val sig = java.security.Signature.getInstance("Ed25519")
            sig.initVerify(pubKey)
            sig.update(data)
            sig.verify(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }


    /**
     * Computes the canonical byte representation of the manifest for digital signing.
     */
    internal fun computeCanonicalData(version: Int, files: List<ManifestFileEntry>): ByteArray {
        val sorted = files.sortedBy { it.path }
        val sb = StringBuilder()
        sb.append(version).append('\n')
        for (entry in sorted) {
            sb.append(entry.path).append('\n')
            sb.append(entry.sha256.lowercase()).append('\n')
            sb.append(entry.type).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Calculates the SHA-256 hash of a file with size bounds enforcement.
     */
    @OptIn(ExperimentalStdlibApi::class)
    private fun calculateSha256(file: File): ByteArray {
        return sha256FileSnapshotBounded(file, 0, MAX_PAYLOAD_BYTES)
    }

    /**
     * Validates that a path is safe: relative, no traversal, no null bytes, and within length limits.
     */
    private fun isPathSafe(path: String): Boolean {
        if (path.isEmpty() || path.length > 512) return false
        if (path.startsWith("/")) return false
        if (path.contains("\\")) return false
        if (path.contains("\u0000")) return false
        val components = path.split("/")
        return components.none { it.isEmpty() || it == ".." || it == "." || it.length > 255 }
    }

    /**
     * Scans the module directory recursively for unexpected files not listed in the manifest.
     */
    private fun scanForUnexpectedFiles(
        moduleDir: File,
        manifest: ParsedManifest,
        violations: MutableList<String>,
    ) {
        val manifestPaths = manifest.files.map { it.path }.toSet()
        val paths = ArrayList<java.nio.file.Path>()
        Files.walk(moduleDir.toPath()).use { stream ->
            val iter = stream.iterator()
            while (iter.hasNext() && paths.size <= MAX_MODULE_ENTRIES) {
                paths.add(iter.next())
            }
        }
        if (paths.size > MAX_MODULE_ENTRIES) {
            violations.add("Module directory has too many entries")
            return
        }
        for (path in paths.drop(1)) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
            val relativePath = moduleDir.toPath().relativize(path).toString().replace("\\", "/")
            if (relativePath in manifestPaths) continue
            if (isIgnoredFile(relativePath)) continue
            if (relativePath.endsWith(".sha256")) continue
            violations.add("Unexpected file in module directory: $relativePath")
        }
    }

    /**
     * Checks if a file should be ignored during integrity verification.
     */
    internal fun isIgnoredFile(relativePath: String): Boolean {
        if (relativePath.endsWith(".sha256")) return true
        if (relativePath.startsWith("keyboxes/") || relativePath.startsWith("logs/") || relativePath.startsWith("system/")) {
            return true
        }
        val isRoot = !relativePath.contains('/')
        if (!isRoot) return false
        return relativePath in IGNORED_FILES ||
            relativePath == MANIFEST_FILENAME ||
            relativePath == "module.prop" ||
            relativePath == "sepolicy.rule" ||
            relativePath in CONFIG_TEMPLATE_FILES
    }

    /**
     * Checks if a file type is critical (e.g., .so, .apk, executables).
     */
    private fun isCriticalFileType(relativePath: String): Boolean {
        val name = relativePath.substringAfterLast("/")
        return name.endsWith(".so") ||
            name.endsWith(".apk") ||
            name in CRITICAL_EXECUTABLE_NAMES ||
            (relativePath.count { it == '/' } == 0 && name.endsWith(".sh"))
    }

    /**
     * Converts a byte array to a lowercase hex string.
     */
    @OptIn(ExperimentalStdlibApi::class)
    internal fun bytesToHex(bytes: ByteArray): String = bytes.toHexString(HexFormat.Default)

    /**
     * Converts a hex string to a byte array.
     */
    internal fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }
    }

    /**
     * Resets verification state and counters for testing.
     */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        cachedManifest = null
        fullVerificationCount.set(0)
        targetedVerificationCount.set(0)
        remoteDisabledForTesting = true
        trustedPublicKeyProvider = { hexToBytes(TRUSTED_PUBLIC_KEY_HEX) }
        allowUnsignedManifest = true
        moduleDirProvider = { getModuleDir() }
    }

    private val IGNORED_FILES = setOf(
        "disable", "remove", "update", "tampered",
        "supervisor.pid", "daemon.pid", "adapter.pid", "backend.pid",
        "skip_mount", ".replace",
    )

    private val CONFIG_TEMPLATE_FILES = setOf(
        "boot_props_mode", "drm_packages.txt", "identity_target.txt",
        "target.txt", "security_patch.txt", "policy_state_v2.json",
        "spoof_build_vars",
    )

    private val CRITICAL_EXECUTABLE_NAMES = setOf(
        "inject", "webui_bridge", "cleverestrickyd", "cleverestricky_backend",
    )
}

sealed class IntegrityResult {
    object Pass : IntegrityResult()
    data class Fail(val violations: List<String>) : IntegrityResult()
}

data class ParsedManifest(
    val version: Int,
    val files: List<ManifestFileEntry>,
    val signature: String,
)

data class ManifestFileEntry(
    val path: String,
    val sha256: String,
    val type: String,
)
