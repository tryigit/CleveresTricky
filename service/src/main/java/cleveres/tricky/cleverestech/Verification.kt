package cleveres.tricky.cleverestech

import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

object Verification {
    private const val MODULE_PATH = "/data/adb/modules/cleveres_tricky"
    private val IGNORED_FILES = setOf("disable", "remove", "update", "system.prop", "sepolicy.rule")

    fun check() {
        val root = File(MODULE_PATH)
        if (!root.exists()) {
            Logger.e("Module directory not found: $MODULE_PATH")
            return
        }

        root.walk().forEach { file ->
            if (file.isDirectory) return@forEach
            // Skip checksum files themselves
            if (file.name.endsWith(".sha256")) return@forEach
            // Skip ignored files
            if (file.parentFile?.absolutePath == root.absolutePath && IGNORED_FILES.contains(file.name)) return@forEach

            val checksumFile = File(file.path + ".sha256")
            if (!checksumFile.exists()) {
                fail("Missing checksum for file: ${file.path}")
            }

            val expected = checksumFile.readText().trim()
            val actual = calculateChecksum(file)
            if (!expected.equals(actual, ignoreCase = true)) {
                fail("Checksum mismatch for file: ${file.path}. Expected $expected, got $actual")
            }
        }
        Logger.i("Module verification passed.")
    }

    private fun calculateChecksum(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.forEachBlock { buffer, bytesRead ->
            md.update(buffer, 0, bytesRead)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fail(reason: String) {
        Logger.e("Verification failed: $reason")
        File(MODULE_PATH, "disable").createNewFile()
        exitProcess(1)
    }
}
