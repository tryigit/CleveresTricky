package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ModuleIntegrityVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testHmacKey = ByteArray(32) { (it + 42).toByte() }

    @Before
    fun setUp() {
        ModuleIntegrityVerifier.resetForTesting()
        ModuleIntegrityVerifier.hmacKeyProvider = { testHmacKey.clone() }
    }

    @After
    fun tearDown() {
        ModuleIntegrityVerifier.resetForTesting()
    }

    /**
     * Computes the SHA-256 hash of data and returns it as a lowercase hex string.
     */
    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Creates a valid signed manifest and the corresponding files in the module directory.
     */
    private fun createManifest(
        moduleDir: File,
        files: List<Pair<String, ByteArray>>,
    ): File {
        // Create the files
        files.forEach { (path, content) ->
            val file = File(moduleDir, path)
            file.parentFile?.mkdirs()
            file.writeBytes(content)
        }

        // Build manifest JSON
        val filesJson = org.json.JSONArray()
        files.forEach { (path, content) ->
            val entry = org.json.JSONObject()
            entry.put("path", path)
            entry.put("sha256", sha256Hex(content))
            entry.put("type", if (path.endsWith(".sh") || !path.contains(".")) "executable" else "regular")
            filesJson.put(entry)
        }

        val entries = files.map { (path, content) ->
            ManifestFileEntry(
                path = path,
                sha256 = sha256Hex(content),
                type = if (path.endsWith(".sh") || !path.contains(".")) "executable" else "regular",
            )
        }

        val canonicalBytes = ModuleIntegrityVerifier.computeCanonicalHmacData(1, entries)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(testHmacKey, "HmacSHA256"))
        val signature = mac.doFinal(canonicalBytes)
        val signatureHex = signature.joinToString("") { "%02x".format(it) }

        val manifest = org.json.JSONObject()
        manifest.put("version", 1)
        manifest.put("files", filesJson)
        manifest.put("signature", signatureHex)

        val manifestFile = File(moduleDir, "integrity_manifest.json")
        manifestFile.writeText(manifest.toString())
        return manifestFile
    }

    @Test
    fun validModulePassesVerification() {
        val moduleDir = tempFolder.newFolder("module")
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }

        createManifest(moduleDir, listOf(
            "test.sh" to "#!/bin/sh\necho hello".toByteArray(),
            "lib.so" to "ELF binary content".toByteArray(),
        ))

        val result = ModuleIntegrityVerifier.verifyFull()
        assertTrue("Expected Pass but got: $result", result is IntegrityResult.Pass)
    }

    @Test
    fun missingCriticalPayloadFails() {
        val moduleDir = tempFolder.newFolder("module")
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }

        val content = "test content".toByteArray()
        // Create manifest referencing a file, but don't create the file
        val filesJson = org.json.JSONArray()
        val entry = org.json.JSONObject()
        entry.put("path", "missing.so")
        entry.put("sha256", sha256Hex(content))
        entry.put("type", "regular")
        filesJson.put(entry)

        val canonicalBytes = ModuleIntegrityVerifier.computeCanonicalHmacData(
            1,
            listOf(
                ManifestFileEntry("missing.so", sha256Hex(content), "regular"),
            ),
        )
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(testHmacKey, "HmacSHA256"))
        val signature = mac.doFinal(canonicalBytes)
        val signatureHex = signature.joinToString("") { "%02x".format(it) }

        val manifest = org.json.JSONObject()
        manifest.put("version", 1)
        manifest.put("files", filesJson)
        manifest.put("signature", signatureHex)
        File(moduleDir, "integrity_manifest.json").writeText(manifest.toString())

        val result = ModuleIntegrityVerifier.verifyFull()
        assertTrue(result is IntegrityResult.Fail)
        assertTrue((result as IntegrityResult.Fail).violations.any { it.contains("Missing") })
    }

    @Test
    fun modifiedContentDetected() {
        val moduleDir = tempFolder.newFolder("module")
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }

        val originalContent = "original content".toByteArray()
        createManifest(moduleDir, listOf("payload.so" to originalContent))

        // Modify the file with same-size content
        val modified = "modified_content".toByteArray()
        File(moduleDir, "payload.so").writeBytes(modified)

        val result = ModuleIntegrityVerifier.verifyFull()
        assertTrue(result is IntegrityResult.Fail)
        assertTrue((result as IntegrityResult.Fail).violations.any { it.contains("Hash mismatch") })
    }

    @Test
    fun malformedManifestFails() {
        val moduleDir = tempFolder.newFolder("module")
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }
        File(moduleDir, "integrity_manifest.json").writeText("not json at all")

        val result = ModuleIntegrityVerifier.verifyFull()
        assertTrue(result is IntegrityResult.Fail)
        assertTrue((result as IntegrityResult.Fail).violations.any { it.contains("Manifest") })
    }

    @Test
    fun wrongHmacKeyFails() {
        val moduleDir = tempFolder.newFolder("module")
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }

        createManifest(moduleDir, listOf("test.sh" to "content".toByteArray()))

        // Change HMAC key
        ModuleIntegrityVerifier.hmacKeyProvider = { ByteArray(32) { 0xFF.toByte() } }

        val result = ModuleIntegrityVerifier.verifyFull()
        assertTrue(result is IntegrityResult.Fail)
        assertTrue((result as IntegrityResult.Fail).violations.any { it.contains("HMAC") || it.contains("signature") })
    }

    @Test
    fun symlinkPayloadRejected() {
        val moduleDir = tempFolder.newFolder("module")
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }

        val realFile = tempFolder.newFile("real_target")
        realFile.writeBytes("real content".toByteArray())

        createManifest(moduleDir, listOf(
            "legit.sh" to "legit".toByteArray(),
        ))

        // Create a symlink for the payload
        try {
            java.nio.file.Files.createSymbolicLink(
                File(moduleDir, "legit.sh").toPath(),
                realFile.toPath(),
            )
        } catch (_: Exception) {
            // Symlink creation might fail on some OS/permissions; skip test
            return
        }

        val result = ModuleIntegrityVerifier.verifyFull()
        assertTrue(result is IntegrityResult.Fail)
        assertTrue((result as IntegrityResult.Fail).violations.any { it.contains("Symlink") || it.contains("symlink") })
    }

    @Test
    fun pathTraversalInManifestRejected() {
        val moduleDir = tempFolder.newFolder("module")
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }

        // Create a manifest with path traversal
        val filesJson = org.json.JSONArray()
        val entry = org.json.JSONObject()
        entry.put("path", "../../../etc/passwd")
        entry.put("sha256", "a".repeat(64))
        entry.put("type", "regular")
        filesJson.put(entry)

        val canonicalBytes = ModuleIntegrityVerifier.computeCanonicalHmacData(
            1,
            listOf(
                ManifestFileEntry("../../../etc/passwd", "a".repeat(64), "regular"),
            ),
        )
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(testHmacKey, "HmacSHA256"))
        val signature = mac.doFinal(canonicalBytes)
        val signatureHex = signature.joinToString("") { "%02x".format(it) }

        val manifest = org.json.JSONObject()
        manifest.put("version", 1)
        manifest.put("files", filesJson)
        manifest.put("signature", signatureHex)
        File(moduleDir, "integrity_manifest.json").writeText(manifest.toString())

        val result = ModuleIntegrityVerifier.verifyFull()
        assertTrue(result is IntegrityResult.Fail)
        assertTrue((result as IntegrityResult.Fail).violations.any { it.contains("traversal") || it.contains("Unsafe") || it.contains("path") })
    }

    @Test
    fun wrongManifestVersionRejected() {
        val moduleDir = tempFolder.newFolder("module")
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }

        val manifest = org.json.JSONObject()
        manifest.put("version", 99)
        manifest.put("files", org.json.JSONArray())
        manifest.put("signature", "a".repeat(64))
        File(moduleDir, "integrity_manifest.json").writeText(manifest.toString())

        val result = ModuleIntegrityVerifier.verifyFull()
        assertTrue(result is IntegrityResult.Fail)
    }

    @Test
    fun missingManifestFails() {
        val moduleDir = tempFolder.newFolder("module")
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }

        val result = ModuleIntegrityVerifier.verifyFull()
        assertTrue(result is IntegrityResult.Fail)
    }

    @Test
    fun subdirectoryFilesVerified() {
        val moduleDir = tempFolder.newFolder("module")
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }

        createManifest(moduleDir, listOf(
            "webroot/index.html" to "<html></html>".toByteArray(),
            "webroot/bridge.js" to "// bridge".toByteArray(),
        ))

        val result = ModuleIntegrityVerifier.verifyFull()
        assertTrue("Expected Pass but got $result", result is IntegrityResult.Pass)
    }

    @Test
    fun singleFileVerificationPass() {
        val moduleDir = tempFolder.newFolder("module")
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }

        createManifest(moduleDir, listOf(
            "test.so" to "binary data".toByteArray(),
        ))

        val manifest = ModuleIntegrityVerifier.loadManifest()
        val result = ModuleIntegrityVerifier.verifySingleFile("test.so", manifest)
        assertTrue(result is IntegrityResult.Pass)
    }

    @Test
    fun singleFileVerificationFailOnModified() {
        val moduleDir = tempFolder.newFolder("module")
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }

        createManifest(moduleDir, listOf(
            "test.so" to "original".toByteArray(),
        ))

        val manifest = ModuleIntegrityVerifier.loadManifest()
        File(moduleDir, "test.so").writeBytes("modified".toByteArray())

        val result = ModuleIntegrityVerifier.verifySingleFile("test.so", manifest)
        assertTrue(result is IntegrityResult.Fail)
    }

    @Test
    fun ignoredFilesDoNotTriggerUnexpected() {
        val moduleDir = tempFolder.newFolder("module")
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }

        createManifest(moduleDir, listOf(
            "test.sh" to "content".toByteArray(),
        ))

        // Add ignored files
        File(moduleDir, "disable").writeText("")
        File(moduleDir, "supervisor.pid").writeText("12345")
        File(moduleDir, "module.prop").writeText("id=cleverestricky")
        File(moduleDir, "test.sh.sha256").writeText("a".repeat(64))

        val result = ModuleIntegrityVerifier.verifyFull()
        assertTrue("Expected Pass but got $result", result is IntegrityResult.Pass)
    }
}
