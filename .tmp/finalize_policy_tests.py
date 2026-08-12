from pathlib import Path

policy = Path("service/src/main/java/cleveres/tricky/cleverestech/PolicyState.kt")
text = policy.read_text()
old = '''    fun hasTelephonyProfileWork(): Boolean {
        val current = snapshot
        if (current.features.telephonyIdentity) return true
'''
new = '''    fun hasTelephonyProfileWork(): Boolean {
        val current = snapshot
        if (resolvedFeatures(emptyArray()).telephonyIdentity) return true
'''
if text.count(old) != 1:
    raise SystemExit("PolicyState telephony runtime gate mismatch")
policy.write_text(text.replace(old, new, 1))

test = Path("service/src/test/java/cleveres/tricky/cleverestech/PolicyStateTest.kt")
text = test.read_text()
old_import = 'package cleveres.tricky.cleverestech\n\n'
new_import = 'package cleveres.tricky.cleverestech\n\nimport cleveres.tricky.cleverestech.util.SecureFile\nimport cleveres.tricky.cleverestech.util.SecureFileOperations\n'
if text.count(old_import) != 1:
    raise SystemExit("PolicyStateTest import anchor mismatch")
text = text.replace(old_import, new_import, 1)
old_fields = '''    private lateinit var root: File
    private lateinit var originalSystemPropertiesGet: (String, String?) -> String?
'''
new_fields = '''    private lateinit var root: File
    private lateinit var originalSystemPropertiesGet: (String, String?) -> String?
    private lateinit var originalSecureFileImpl: SecureFileOperations
'''
if text.count(old_fields) != 1:
    raise SystemExit("PolicyStateTest field anchor mismatch")
text = text.replace(old_fields, new_fields, 1)
old_setup = '''        originalSystemPropertiesGet = systemPropertiesGet
        root = Files.createTempDirectory("policy-state-test").toFile()
        Config.setRootForTesting(root)
'''
new_setup = '''        originalSystemPropertiesGet = systemPropertiesGet
        originalSecureFileImpl = SecureFile.impl
        root = Files.createTempDirectory("policy-state-test").toFile()
        Config.setRootForTesting(root)
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
'''
if text.count(old_setup) != 1:
    raise SystemExit("PolicyStateTest setup anchor mismatch")
text = text.replace(old_setup, new_setup, 1)
old_teardown = '''        PolicyState.currentDateSource = { LocalDate.now() }
        root.deleteRecursively()
        Config.reset()
'''
new_teardown = '''        PolicyState.currentDateSource = { LocalDate.now() }
        SecureFile.impl = originalSecureFileImpl
        root.deleteRecursively()
        Config.reset()
'''
if text.count(old_teardown) != 1:
    raise SystemExit("PolicyStateTest teardown anchor mismatch")
text = text.replace(old_teardown, new_teardown, 1)
anchor = '''    @Test
    fun `invalid transaction does not replace active snapshot`() {
'''
addition = '''    @Test
    fun `policy transaction invalidates cached uid resolution`() {
        install(features = featureJson(build = false, patch = false))
        cachePackages(10_013, arrayOf("com.example.app"))
        assertFalse(PolicyState.isFeatureEnabled(PolicyState.Feature.BUILD_IDENTITY, 10_013))

        val replacement = stateJson(features = featureJson(build = true, patch = false))
        assertTrue(PolicyState.replaceFromJson(replacement.toString()).isSuccess)

        assertTrue(PolicyState.isFeatureEnabled(PolicyState.Feature.BUILD_IDENTITY, 10_013))
    }

    @Test
    fun `unassigned profiles do not keep optional interceptors active`() {
        val profile =
            JSONObject()
                .put("name", "Dormant")
                .put("applications", JSONArray())
                .put("privacy", "isolate")
                .put("features", JSONObject().put("telephonyIdentity", true))
                .put("securityPatch", JSONObject())
        install(
            features = featureJson(telephony = false, patch = false),
            profiles = JSONArray().put(profile),
        )

        assertFalse(PolicyState.hasTelephonyProfileWork())
        assertFalse(PolicyState.hasDrmProfileWork())
        assertFalse(Config.shouldInterceptTelephony)
        assertFalse(Config.shouldInterceptDrm)
    }

    @Test
    fun `assigned profiles activate only required optional interceptors`() {
        val profile =
            JSONObject()
                .put("name", "Assigned")
                .put("applications", JSONArray().put("com.example.app"))
                .put("privacy", "isolate")
                .put("features", JSONObject().put("telephonyIdentity", true))
                .put("securityPatch", JSONObject())
        install(
            features = featureJson(telephony = false, patch = false),
            profiles = JSONArray().put(profile),
        )
        cachePackages(10_014, arrayOf("com.example.app"))

        assertTrue(PolicyState.hasTelephonyProfileWork())
        assertTrue(PolicyState.hasDrmProfileWork())
        assertTrue(Config.shouldInterceptTelephony)
        assertTrue(Config.shouldInterceptDrm)
        assertTrue(PolicyState.isFeatureEnabled(PolicyState.Feature.TELEPHONY_IDENTITY, 10_014))
    }

    @Test
    fun `active profile can park global telephony work`() {
        val profile =
            JSONObject()
                .put("name", "Quiet")
                .put("applications", JSONArray())
                .put("privacy", "inherit")
                .put("features", JSONObject().put("telephonyIdentity", false))
                .put("securityPatch", JSONObject())
        val state =
            stateJson(
                features = featureJson(telephony = true, patch = false),
                profiles = JSONArray().put(profile),
            ).put("activeProfile", "Quiet")
        PolicyState.installStateForTesting(state.toString())

        assertFalse(PolicyState.isFeatureEnabled(PolicyState.Feature.TELEPHONY_IDENTITY))
        assertFalse(PolicyState.hasTelephonyProfileWork())
        assertFalse(Config.shouldInterceptTelephony)
    }

    @Test
    fun `duplicate exact profile assignments are rejected`() {
        val first =
            JSONObject()
                .put("name", "First")
                .put("applications", JSONArray().put("com.example.app"))
                .put("privacy", "inherit")
                .put("features", JSONObject())
                .put("securityPatch", JSONObject())
        val second =
            JSONObject()
                .put("name", "Second")
                .put("applications", JSONArray().put("com.example.app"))
                .put("privacy", "inherit")
                .put("features", JSONObject())
                .put("securityPatch", JSONObject())
        val invalid = stateJson(profiles = JSONArray().put(first).put(second))

        assertTrue(PolicyState.validateStateJson(invalid.toString(), false).isFailure)
    }

    @Test
    fun `effective state reports the matched profile rule`() {
        val profile =
            JSONObject()
                .put("name", "Wildcard")
                .put("applications", JSONArray().put("com.example.*"))
                .put("privacy", "inherit")
                .put("features", JSONObject())
                .put("securityPatch", JSONObject())
        install(profiles = JSONArray().put(profile))

        val effective = PolicyState.effectiveStateJson("com.example.bank")

        assertEquals("com.example.*", effective.getString("matchedApplicationRule"))
        assertEquals("Wildcard", effective.getString("matchedProfile"))
        assertEquals("targeted", effective.getString("scope"))
    }

    @Test
    fun `region and identity refresh paths are explicitly gated`() {
        install(features = featureJson(region = false, refresh = false, patch = false))
        assertFalse(PolicyState.isFeatureEnabled(PolicyState.Feature.REGION_IDENTITY))
        assertFalse(PolicyState.isFeatureEnabled(PolicyState.Feature.IDENTITY_REFRESH))

        val bootSource = sourceFile("BootLogic.kt").readText()
        val configSource = sourceFile("Config.kt").readText()
        assertTrue(bootSource.contains("PolicyState.isFeatureEnabled(PolicyState.Feature.REGION_IDENTITY)"))
        assertTrue(configSource.contains("PolicyState.isFeatureEnabled(PolicyState.Feature.IDENTITY_REFRESH)"))
    }

'''
if text.count(anchor) != 1:
    raise SystemExit("PolicyStateTest insertion anchor mismatch")
text = text.replace(anchor, addition + anchor, 1)
helper_anchor = '''    private fun cachePackages(
        uid: Int,
        packages: Array<String>,
    ) {
        Config.setPackagesForTesting(uid, packages)
    }
'''
helper_replacement = '''    private fun sourceFile(name: String): File {
        val relative = "cleveres/tricky/cleverestech/$name"
        return listOf(
            File("src/main/java/$relative"),
            File("service/src/main/java/$relative"),
        ).firstOrNull(File::isFile)
            ?: error("Could not locate $name from ${File(".").absolutePath}")
    }

''' + helper_anchor
if text.count(helper_anchor) != 1:
    raise SystemExit("PolicyStateTest helper anchor mismatch")
test.write_text(text.replace(helper_anchor, helper_replacement, 1))

doc = Path("docs/BackupRestore.md")
text = doc.read_text()
anchor = "\n## Recovery guidance\n"
addition = "\n## Policy state\n\nBackups include the validated version two policy state, including optional feature controls, independent System, Vendor, and Boot patch policies, and named profile configuration. Profile keybox entries remain references to validated keybox files rather than embedded private key material. Restore validates the policy state before publishing it and reloads one complete snapshot.\n"
if addition.strip().splitlines()[0] not in text:
    if text.count(anchor) != 1:
        raise SystemExit("BackupRestore documentation anchor mismatch")
    text = text.replace(anchor, addition + anchor, 1)
    doc.write_text(text)
