package cleveres.tricky.cleverestech

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature

class ModuleIntegrityVerifierTrustReloadTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        ModuleIntegrityVerifier.resetForTesting()
        ModuleIntegrityVerifier.allowUnsignedManifest = false
    }

    @After
    fun tearDown() {
        ModuleIntegrityVerifier.resetForTesting()
    }

    @Test
    fun `targeted verification reloads manifest on trusted key rotation`() {
        val moduleDir = tempFolder.newFolder("module_trust_rotation")
        val payload = "signed payload".toByteArray()
        val payloadFile = File(moduleDir, "service.apk").apply { writeBytes(payload) }
        val firstKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val secondKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        ModuleIntegrityVerifier.moduleDirProvider = { moduleDir.absolutePath }
        ModuleIntegrityVerifier.trustedPublicKeyProvider = { rawPublicKey(firstKey) }

        writeManifest(moduleDir, payloadFile.name, payload, firstKey)
        assertTrue("Initial signed manifest should populate the cache", ModuleIntegrityVerifier.loadManifest() != null)

        writeManifest(moduleDir, payloadFile.name, payload, secondKey)
        ModuleIntegrityVerifier.trustedPublicKeyProvider = { rawPublicKey(secondKey) }

        val result = ModuleIntegrityVerifier.verifySingleFile(payloadFile.name)
        assertTrue("First targeted verification should reload the newly trusted manifest: $result", result is IntegrityResult.Pass)
    }

    private fun writeManifest(
        moduleDir: File,
        path: String,
        payload: ByteArray,
        keyPair: KeyPair,
    ) {
        val entry = ManifestFileEntry(path, sha256Hex(payload), "regular")
        val canonical = ModuleIntegrityVerifier.computeCanonicalData(1, listOf(entry))
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(canonical)
        val signature = signer.sign().joinToString("") { "%02x".format(it) }
        val files =
            JSONArray().put(
                JSONObject()
                    .put("path", entry.path)
                    .put("sha256", entry.sha256)
                    .put("type", entry.type),
            )
        File(moduleDir, "integrity_manifest.json").writeText(
            JSONObject()
                .put("version", 1)
                .put("files", files)
                .put("signature", signature)
                .toString(),
        )
    }

    private fun rawPublicKey(keyPair: KeyPair): ByteArray {
        val encoded = keyPair.public.encoded
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    private fun sha256Hex(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
}
