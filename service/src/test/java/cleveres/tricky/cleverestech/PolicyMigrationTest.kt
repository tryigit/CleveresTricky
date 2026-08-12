package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PolicyMigrationTest {
    private lateinit var tempDir: File
    private lateinit var originalSecureFileImpl: SecureFileOperations

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("cleverestricky-policy-migration").toFile()
        originalSecureFileImpl = SecureFile.impl
        SecureFile.impl =
            object : SecureFileOperations {
                override fun writeText(
                    file: File,
                    content: String,
                ) {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                }

                override fun mkdirs(
                    file: File,
                    mode: Int,
                ) {
                    file.mkdirs()
                }

                override fun touch(
                    file: File,
                    mode: Int,
                ) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                }
            }
    }

    @After
    fun tearDown() {
        SecureFile.impl = originalSecureFileImpl
        tempDir.deleteRecursively()
    }

    private fun validState(
        keybox: String? = null,
        activeProfile: String? = "Travel",
        includeRetiredRkp: Boolean = false,
    ): JSONObject {
        val features =
            JSONObject()
                .put("buildIdentity", false)
                .put("attestationIdentity", false)
                .put("telephonyIdentity", false)
                .put("regionIdentity", false)
                .put("identityRefresh", false)
                .put("securityPatch", false)
        val securityPatch = JSONObject().put("automaticThresholdMonths", 6)
        val profile =
            JSONObject()
                .put("name", "Travel")
                .put("applications", JSONArray())
                .put("template", JSONObject.NULL)
                .put("keybox", keybox ?: JSONObject.NULL)
                .put("privacy", "inherit")
                .put("features", JSONObject())
                .put("securityPatch", JSONObject())
                .put("drmPassthrough", JSONObject.NULL)
        if (includeRetiredRkp) profile.put("rkpPassthrough", true)
        return JSONObject()
            .put("version", 2)
            .put("features", features)
            .put("securityPatch", securityPatch)
            .put("profiles", JSONArray().put(profile))
            .put("activeProfile", activeProfile ?: JSONObject.NULL)
    }

    @Test
    fun staleKeyboxAndRetiredRkpFieldsAreRepairedWithoutResettingProfiles() {
        val stateFile = File(tempDir, "policy_state_v2.json")
        stateFile.writeText(validState(keybox = "missing.xml", activeProfile = "travel", includeRetiredRkp = true).toString())

        assertTrue(PolicyMigration.sanitize(tempDir))

        val repaired = JSONObject(stateFile.readText())
        val profile = repaired.getJSONArray("profiles").getJSONObject(0)
        assertTrue(profile.isNull("keybox"))
        assertFalse(profile.has("rkpPassthrough"))
        assertEquals("travel", repaired.getString("activeProfile"))
        assertTrue(File(tempDir, "policy_state_v2.last_good.json").isFile)
    }

    @Test
    fun validManagedKeyboxReferenceIsPreservedAndSeedsRecoveryCopy() {
        val keyboxDir = File(tempDir, "keyboxes")
        keyboxDir.mkdirs()
        File(keyboxDir, "valid.xml").writeText("placeholder")
        val stateFile = File(tempDir, "policy_state_v2.json")
        val original = validState(keybox = "valid.xml").toString()
        stateFile.writeText(original)

        assertTrue(PolicyMigration.sanitize(tempDir))
        assertEquals(original, stateFile.readText())
        val preserved = JSONObject(stateFile.readText())
        assertEquals("valid.xml", preserved.getJSONArray("profiles").getJSONObject(0).getString("keybox"))
        assertTrue(File(tempDir, "policy_state_v2.last_good.json").isFile)
    }

    @Test
    fun malformedConfiguredStateIsRecoveredFromLastGoodAndPreservedForDiagnostics() {
        val stateFile = File(tempDir, "policy_state_v2.json")
        val lastGoodFile = File(tempDir, "policy_state_v2.last_good.json")
        val malformed = "{not-json"
        stateFile.writeText(malformed)
        lastGoodFile.writeText(validState(keybox = null).toString())

        assertTrue(PolicyMigration.sanitize(tempDir))

        val recovered = JSONObject(stateFile.readText())
        assertEquals(2, recovered.getInt("version"))
        assertEquals("Travel", recovered.getString("activeProfile"))
        assertEquals(malformed, File(tempDir, "policy_state_v2.invalid.json").readText())
    }

    @Test
    fun emptyConfiguredStateIsRecoveredFromLastGood() {
        val stateFile = File(tempDir, "policy_state_v2.json")
        val lastGoodFile = File(tempDir, "policy_state_v2.last_good.json")
        stateFile.writeText("")
        lastGoodFile.writeText(validState().toString())

        assertTrue(PolicyMigration.sanitize(tempDir))
        assertEquals("Travel", JSONObject(stateFile.readText()).getString("activeProfile"))
    }

    @Test
    fun oversizedConfiguredStateIsRecoveredWithoutReadingItIntoMemory() {
        val stateFile = File(tempDir, "policy_state_v2.json")
        val lastGoodFile = File(tempDir, "policy_state_v2.last_good.json")
        stateFile.writeBytes(ByteArray(512 * 1024 + 1) { 'x'.code.toByte() })
        lastGoodFile.writeText(validState().toString())

        assertTrue(PolicyMigration.sanitize(tempDir))
        assertEquals("Travel", JSONObject(stateFile.readText()).getString("activeProfile"))
        assertFalse(File(tempDir, "policy_state_v2.invalid.json").exists())
    }

    @Test
    fun validConfiguredStateRepairsMalformedLastGoodCopy() {
        val stateFile = File(tempDir, "policy_state_v2.json")
        val lastGoodFile = File(tempDir, "policy_state_v2.last_good.json")
        val valid = validState().toString()
        stateFile.writeText(valid)
        lastGoodFile.writeText("{bad")

        assertTrue(PolicyMigration.sanitize(tempDir))
        assertEquals(valid, stateFile.readText())
        assertEquals(valid, lastGoodFile.readText())
    }

    @Test
    fun unsupportedSchemaIsNeverSilentlyDowngraded() {
        val stateFile = File(tempDir, "policy_state_v2.json")
        val lastGoodFile = File(tempDir, "policy_state_v2.last_good.json")
        val future = validState().put("version", 3).toString()
        stateFile.writeText(future)
        lastGoodFile.writeText(validState().toString())

        assertFalse(PolicyMigration.sanitize(tempDir))
        assertEquals(3, JSONObject(stateFile.readText()).getInt("version"))
        assertFalse(File(tempDir, "policy_state_v2.invalid.json").exists())
    }
}
