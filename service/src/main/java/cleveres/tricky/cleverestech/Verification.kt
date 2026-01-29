package cleveres.tricky.cleverestech

import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

object Verification {
    private const val MODULE_PATH = "/data/adb/modules/cleveres_tricky"
    private val IGNORED_FILES = setOf("disable", "remove", "update", "system.prop", "sepolicy.rule")

    var exitProcessImpl: (Int) -> Unit = { exitProcess(it) }

    @OptIn(ExperimentalStdlibApi::class)
    fun check(root: File = File(MODULE_PATH)) {
        if (!root.exists()) {
            Logger.e("Module directory not found: ${root.absolutePath}")
            return
        }

        val allFiles = root.walk().filter { !it.isDirectory }.toList()
        val checksumFiles = allFiles
            .filter { it.name.endsWith(".sha256") }
            .map { it.path }
            .toSet()

        allFiles.forEach { file ->
            // Skip checksum files themselves
            if (file.name.endsWith(".sha256")) return@forEach
            // Skip ignored files
            if (file.parentFile?.absolutePath == root.absolutePath && IGNORED_FILES.contains(file.name)) return@forEach

            val expectedChecksumPath = file.path + ".sha256"
            if (!checksumFiles.contains(expectedChecksumPath)) {
                fail(root, "Missing checksum for file: ${file.path}")
            }

            val checksumFile = File(expectedChecksumPath)
            val expected = checksumFile.readText().trim()
            val actual = calculateChecksum(file)
            if (!expected.equals(actual, ignoreCase = true)) {
                fail(root, "Checksum mismatch for file: ${file.path}. Expected $expected, got $actual")
            }
        }
        Logger.i("Module verification passed.")
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun calculateChecksum(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.forEachBlock { buffer, bytesRead ->
            md.update(buffer, 0, bytesRead)
        }
        return md.digest().toHexString(HexFormat.Default)
    }

    private fun fail(root: File, reason: String) {
        Logger.e("Verification failed: $reason")
        File(root, "disable").createNewFile()
        exitProcessImpl(1)
    }
}
