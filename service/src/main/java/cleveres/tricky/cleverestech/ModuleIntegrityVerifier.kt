package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.sha256FileSnapshotBounded
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Runtime module integrity verifier using a signed manifest.
 *
 * The manifest contains SHA-256 hashes of all critical module files.
 * It is signed with HMAC-SHA256 using a key derived from build-time
 * constants baked into the APK, providing a trust root independent
 * of files inside the module directory.
 */
object ModuleIntegrityVerifier {

    private const val MANIFEST_VERSION = 1
    private const val MANIFEST_FILENAME = "integrity_manifest.json"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HMAC_DOMAIN = "INTEGRITY-MANIFEST-V1"
    private const val MAX_MANIFEST_BYTES = 64L * 1024
    private const val MAX_PAYLOAD_BYTES = 128L * 1024 * 1024
    private const val MAX_MODULE_ENTRIES = 4096

    /** Injectable for testing. */
    internal var hmacKeyProvider: () -> ByteArray = ::deriveDefaultHmacKey
    internal var moduleDirProvider: () -> String = { getModuleDir() }

    /**
     * Perform a full integrity verification of all critical module payloads.
     * Returns [IntegrityResult.Pass] only if the manifest is valid and every
     * listed file has the correct SHA-256 digest.
     */
    fun verifyFull(): IntegrityResult {
        val violations = mutableListOf<String>()
        val moduleDir = File(moduleDirProvider())

        if (!Files.isDirectory(moduleDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return IntegrityResult.Fail(listOf("Module directory does not exist or is a symlink: ${moduleDir.absolutePath}"))
        }

        // Step 1: Load and parse the manifest
        val manifestFile = File(moduleDir, MANIFEST_FILENAME)
        val manifest = try {
            loadAndVerifyManifest(manifestFile)
        } catch (error: Exception) {
            return IntegrityResult.Fail(listOf("Manifest verification failed: ${error.message}"))
        }

        // Step 2: Verify each file in the manifest
        for (entry in manifest.files) {
            // Validate path safety
            if (!isPathSafe(entry.path)) {
                violations.add("Path traversal or unsafe path: ${entry.path}")
                continue
            }

            val file = File(moduleDir, entry.path)
            val filePath = file.toPath()

            // Check symlink
            if (Files.isSymbolicLink(filePath)) {
                violations.add("Symlink detected for critical payload: ${entry.path}")
                continue
            }

            // Check existence
            if (!Files.exists(filePath, LinkOption.NOFOLLOW_LINKS)) {
                violations.add("Missing critical payload: ${entry.path}")
                continue
            }

            // Check file type
            if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
                violations.add("Wrong file type for critical payload: ${entry.path}")
                continue
            }

            // Hash verification
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

        // Step 3: Check for unexpected critical files (scan directory)
        try {
            scanForUnexpectedFiles(moduleDir, manifest, violations)
        } catch (error: Exception) {
            violations.add("Failed to scan for unexpected files: ${error.message}")
        }

        return if (violations.isEmpty()) IntegrityResult.Pass else IntegrityResult.Fail(violations)
    }

    /**
     * Verify a single file against the manifest. Used for event-driven runtime checks.
     */
    fun verifySingleFile(relativePath: String, manifest: ParsedManifest?): IntegrityResult {
        if (manifest == null) {
            return IntegrityResult.Fail(listOf("No manifest available for single-file verification"))
        }
        val moduleDir = File(moduleDirProvider())

        // Is this file in the manifest?
        val entry = manifest.files.find { it.path == relativePath }
        if (entry == null) {
            // Not a critical file — check if it's an expected non-critical file
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
     * Load the integrity manifest from the module directory.
     * Returns null if the manifest is absent or invalid.
     */
    fun loadManifest(): ParsedManifest? {
        val moduleDir = File(moduleDirProvider())
        val manifestFile = File(moduleDir, MANIFEST_FILENAME)
        return try {
            loadAndVerifyManifest(manifestFile)
        } catch (error: Exception) {
            Logger.e("Failed to load integrity manifest", error)
            null
        }
    }

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
        if (signature.length != 64) {
            throw SecurityException("Invalid manifest signature length")
        }

        val filesArray = json.optJSONArray("files")
            ?: throw SecurityException("Manifest has no files array")

        // Parse file entries first
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

        // Verify HMAC signature using canonical data serialization
        val canonicalBytes = computeCanonicalHmacData(version, files)
        val hmacKey = hmacKeyProvider()
        try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(hmacKey, HMAC_ALGORITHM))
            val expectedMac = mac.doFinal(canonicalBytes)
            val providedMac = hexToBytes(signature)
            if (!MessageDigest.isEqual(expectedMac, providedMac)) {
                throw SecurityException("Manifest HMAC signature verification failed")
            }
        } finally {
            hmacKey.fill(0)
        }

        return ParsedManifest(version, files, signature)
    }

    internal fun computeCanonicalHmacData(version: Int, files: List<ManifestFileEntry>): ByteArray {
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

    @OptIn(ExperimentalStdlibApi::class)
    private fun calculateSha256(file: File): ByteArray {
        return sha256FileSnapshotBounded(file, 0, MAX_PAYLOAD_BYTES)
    }

    private fun isPathSafe(path: String): Boolean {
        if (path.isEmpty() || path.length > 512) return false
        if (path.startsWith("/")) return false
        if (path.contains("\\" )) return false
        if (path.contains("\u0000")) return false
        val components = path.split("/")
        return components.none { it.isEmpty() || it == ".." || it == "." || it.length > 255 }
    }

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
            // This is an unexpected file that is not in the manifest and not ignored
            // Only flag it if it looks like a critical executable type
            if (isCriticalFileType(relativePath)) {
                violations.add("Unexpected critical file: $relativePath")
            }
        }
    }

    private fun isIgnoredFile(relativePath: String): Boolean {
        val name = relativePath.substringAfterLast("/")
        return name in IGNORED_FILES || relativePath.endsWith(".sha256") ||
            relativePath == MANIFEST_FILENAME || relativePath == "module.prop" ||
            relativePath == "sepolicy.rule" ||
            relativePath in CONFIG_TEMPLATE_FILES
    }

    private fun isCriticalFileType(relativePath: String): Boolean {
        val name = relativePath.substringAfterLast("/")
        // .so files, known binary names, .sh scripts in root
        return name.endsWith(".so") ||
            name.endsWith(".apk") ||
            name in CRITICAL_EXECUTABLE_NAMES ||
            (relativePath.count { it == '/' } == 0 && name.endsWith(".sh"))
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun bytesToHex(bytes: ByteArray): String = bytes.toHexString(HexFormat.Default)

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }
    }

    private fun deriveDefaultHmacKey(): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(BuildConfig.CHECKSUM.toByteArray(Charsets.UTF_8))
        md.update(HMAC_DOMAIN.toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        hmacKeyProvider = ::deriveDefaultHmacKey
        moduleDirProvider = { getModuleDir() }
    }

    private val IGNORED_FILES = setOf(
        "disable", "remove", "update", "tampered",
        "supervisor.pid", "daemon.pid", "adapter.pid", "backend.pid",
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
