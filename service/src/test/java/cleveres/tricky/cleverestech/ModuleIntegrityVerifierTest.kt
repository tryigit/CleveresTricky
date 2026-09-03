package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.security.MessageDigest

class ModuleIntegrityVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testKeyPair by lazy {
        java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    }

    private fun testPublicKeyBytes(): ByteArray {
        val encoded = testKeyPair.public.encoded
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    private fun signManifestForTesting(
        version: Int,
        files: List<ManifestFileEntry>,
        keyPair: java.security.KeyPair = testKeyPair,
    ): String {
        val canonicalBytes = ModuleIntegrityVerifier.computeCanonicalData(version, files)
        val sig = java.security.Signature.getInstance("Ed25519")
        sig.initSign(keyPair.private)
        sig.update(canonicalBytes)
        val sigBytes = sig.sign()
        return sigBytes.joinToString("") { "%02x".format(it) }
    }

    @Before
    fun setUp() {
        ModuleIntegrityVerifier.resetForTesting()
        ModuleIntegrityVerifier.trustedPublicKeyProvider = { testPublicKeyBytes() }
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
        keyPair: java.security.KeyPair = testKeyPair,
    ): File {
        // Create the files
        files.forEach { (path, content) ->
            val file = File(moduleDir, path)
            file.parentFile?.mkdirs()
            file.writeBytes(content)
            if (path.endsWith(".sh") || !path.contains(".")) {
                file.setExecutable(true, false)
            }
        }

        val entries = files.map { (path, content) ->
            ManifestFileEntry(
                path = path,
                sha256 = sha256Hex(content),
                type = if (path.endsWith(".sh") || !path.contains(".")) "executable" else "regular",
            )
        }

        val signatureHex = signManifestForTesting(1, entries, keyPair)

        val filesJson = org.json.JSONArray()
        entries.forEach { entry ->
            val obj = org.json.JSONObject()
            obj.put("path", entry.path)
            obj.put("sha256", entry.sha256)
            obj.put("type", entry.type)
            filesJson.put(obj)
        }

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

        val entry = ManifestFileEntry("missing.so", sha256Hex(content), "regular")
        val signatureHex = signManifestForTesting(1, listOf(entry))

        val filesJson = org.json.JSONArray()
        val obj = org.json.JSONObject()
        obj.put("path", "missing.so")
        obj.put("sha256", sha256Hex(content))
        obj.put("type", "regular")
        filesJson.put(obj)

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
    fun wrongPublicKeyFails() {
        val moduleDir = tempFolder.newFolder("module")
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }

        createManifest(moduleDir, listOf("test.sh" to "content".toByteArray()))

        // Change trusted public key
        ModuleIntegrityVerifier.trustedPublicKeyProvider = { ByteArray(32) { 0xFF.toByte() } }

        val result = ModuleIntegrityVerifier.verifyFull()
        assertTrue(result is IntegrityResult.Fail)
        assertTrue((result as IntegrityResult.Fail).violations.any { it.contains("signature") || it.contains("Manifest") })
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

        // Delete the created file first so symlink creation does not fail with FileAlreadyExistsException
        val symlinkTarget = File(moduleDir, "legit.sh")
        symlinkTarget.delete()

        try {
            Files.createSymbolicLink(
                symlinkTarget.toPath(),
                realFile.toPath(),
            )
        } catch (_: UnsupportedOperationException) {
            // Symlink creation not supported on filesystem; skip test
            return
        } catch (_: FileSystemException) {
            // OS permissions do not allow symlink creation; skip test
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

        val entry = ManifestFileEntry("../../../etc/passwd", "a".repeat(64), "regular")
        val signatureHex = signManifestForTesting(1, listOf(entry))

        val filesJson = org.json.JSONArray()
        val obj = org.json.JSONObject()
        obj.put("path", "../../../etc/passwd")
        obj.put("sha256", "a".repeat(64))
        obj.put("type", "regular")
        filesJson.put(obj)

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
        manifest.put("signature", "a".repeat(128))
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

        // Add ignored files at root
        File(moduleDir, "disable").writeText("")
        File(moduleDir, "supervisor.pid").writeText("12345")
        File(moduleDir, "module.prop").writeText("id=cleverestricky")
        File(moduleDir, "test.sh.sha256").writeText("a".repeat(64))

        val result = ModuleIntegrityVerifier.verifyFull()
        assertTrue("Expected Pass but got $result", result is IntegrityResult.Pass)
    }

    @Test
    fun nestedIgnoredFileNotIgnored() {
        val moduleDir = tempFolder.newFolder("module")
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }

        createManifest(moduleDir, listOf(
            "test.sh" to "content".toByteArray(),
        ))

        // Control files in subdirectories must NOT be ignored
        val subDir = File(moduleDir, "subdir")
        subDir.mkdirs()
        File(subDir, "disable").writeText("")

        val result = ModuleIntegrityVerifier.verifyFull()
        assertTrue(result is IntegrityResult.Fail)
        assertTrue((result as IntegrityResult.Fail).violations.any { it.contains("Unexpected") })
    }

    @Test
    fun daemonOperationalErrorsAreNotIntegrityViolations() {
        val result = ModuleIntegrityVerifier.decodeDaemonResponseForTesting(
            1,
            "module directory unavailable".toByteArray(),
        )

        assertNull(result)
    }

    @Test
    fun daemonViolationPayloadProducesConfirmedFailure() {
        val payload = byteArrayOf(1) + "hash mismatch".toByteArray()
        val result = ModuleIntegrityVerifier.decodeDaemonResponseForTesting(0, payload)

        assertTrue(result is IntegrityResult.Fail)
        assertEquals(listOf("hash mismatch"), (result as IntegrityResult.Fail).violations)
    }

    @Test(expected = java.io.IOException::class)
    fun daemonResponseLengthIsBoundedBeforeAllocation() {
        val header = ByteArray(16)
        "CTIP".toByteArray().copyInto(header)
        header[5] = 1
        header[7] = 0x30
        val overLimit = 1024 * 1024 + 1
        header[12] = (overLimit ushr 24).toByte()
        header[13] = (overLimit ushr 16).toByte()
        header[14] = (overLimit ushr 8).toByte()
        header[15] = overLimit.toByte()

        ModuleIntegrityVerifier.daemonResponseLengthForTesting(0x30, header)
    }
}
