package cleveres.tricky.cleverestech

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

object Verification {
    private val MODULE_PATH = getModuleDir()
    private val IGNORED_FILES = setOf("disable", "remove", "update", "system.prop", "tampered", "web_port")

    @OptIn(ExperimentalStdlibApi::class)
    fun check(root: File = File(MODULE_PATH)): Boolean {
        if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Logger.e("Module directory not found: ${root.absolutePath}")
            return false
        }

        val paths = ArrayList<java.nio.file.Path>()
        Files.walk(root.toPath()).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext() && paths.size <= MAX_MODULE_ENTRIES) paths.add(iterator.next())
        }
        if (paths.size > MAX_MODULE_ENTRIES || paths.any { Files.isSymbolicLink(it) }) {
            Logger.e("Module verification rejected an oversized tree or symbolic link")
            return false
        }

        val allFiles = ArrayList<File>(paths.size)
        for (path in paths.drop(1)) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                Logger.e("Module verification rejected a non-regular entry: $path")
                return false
            }
            allFiles.add(path.toFile())
        }

        val checksumMap = LinkedHashMap<String, String>()
        for (checksumFile in allFiles.filter { it.name.endsWith(".sha256") }) {
            if (checksumFile.length() !in 64..MAX_CHECKSUM_FILE_BYTES) {
                Logger.e("Verification failed: Invalid checksum file size: ${checksumFile.path}")
                return false
            }
            val expected = checksumFile.readText().trim()
            if (expected.length != 64 || expected.any { it.digitToIntOrNull(16) == null }) {
                Logger.e("Verification failed: Invalid checksum: ${checksumFile.path}")
                return false
            }
            checksumMap[checksumFile.path.removeSuffix(".sha256")] = expected
        }

        var isTampered = false

        allFiles.forEach { file ->
            // Skip checksum files themselves
            if (file.name.endsWith(".sha256")) return@forEach
            // Skip ignored files
            if (file.parentFile?.absolutePath == root.absolutePath && IGNORED_FILES.contains(file.name)) return@forEach

            if (file.length() !in 0..MAX_MODULE_FILE_BYTES) {
                Logger.e("Verification failed: Invalid module file size: ${file.path}")
                isTampered = true
                return@forEach
            }

            val expected = checksumMap[file.path]
            if (expected == null) {
                Logger.e("Verification failed: Missing checksum for file: ${file.path}")
                isTampered = true
                return@forEach
            }

            val actual = calculateChecksum(file)
            if (!expected.equals(actual, ignoreCase = true)) {
                Logger.e("Verification failed: Checksum mismatch for file: ${file.path}. Expected $expected, got $actual")
                isTampered = true
                return@forEach
            }
        }

        checksumMap.keys.forEach { targetPath ->
            val target = File(targetPath)
            if (!Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                (target.parentFile?.absolutePath == root.absolutePath && target.name in IGNORED_FILES)
            ) {
                Logger.e("Verification failed: Checksum target is missing or invalid: $targetPath")
                isTampered = true
            }
        }

        if (isTampered) {
            Logger.e("Module verification failed. Tampering detected.")
            return false
        }

        Logger.i("Module verification passed.")
        return true
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun calculateChecksum(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.forEachBlock { buffer, bytesRead ->
            md.update(buffer, 0, bytesRead)
        }
        return md.digest().toHexString(HexFormat.Default)
    }

    private const val MAX_MODULE_ENTRIES = 4096
    private const val MAX_CHECKSUM_FILE_BYTES = 1024L
    private const val MAX_MODULE_FILE_BYTES = 128L * 1024 * 1024
}
