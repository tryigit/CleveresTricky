from pathlib import Path


def swap(path, old, new, count=1):
    p = Path(path)
    text = p.read_text()
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f"{path}: expected {count}, found {actual}: {old[:100]!r}")
    p.write_text(text.replace(old, new, count))


policy = "service/src/main/java/cleveres/tricky/cleverestech/PolicyState.kt"
swap(
    policy,
    '    private val packagePattern = Regex("(?:[A-Za-z_][A-Za-z0-9_]*|\\*)(?:\\.(?:[A-Za-z_][A-Za-z0-9_]*|\\*))*")\n',
    '    private val packagePattern = Regex("""(?:[A-Za-z_][A-Za-z0-9_]*|[*])(?:[.](?:[A-Za-z_][A-Za-z0-9_]*|[*]))*""")\n',
)
swap(
    policy,
    '''    fun profileAppConfig(uid: Int): Config.AppSpoofConfig? {
        val profile = resolveUid(uid).selection.profile ?: return null
        if (profile.template == null && profile.keybox == null && profile.privacy == Config.AppPrivacyMode.INHERIT) return null
        return Config.AppSpoofConfig(profile.template, profile.keybox, profile.privacy)
    }

    fun profilePrivacyMode(uid: Int): Config.AppPrivacyMode? = resolveUid(uid).selection.profile?.privacy

    fun rkpPassthrough(uid: Int): Boolean =
        resolveUid(uid).selection.profile?.rkpPassthrough ?: Config.isRkpPassthroughEnabled

    fun profileDrmPassthrough(uid: Int): Boolean? = resolveUid(uid).selection.profile?.drmPassthrough
''',
    '''    private fun mergeAppConfig(
        profile: Profile?,
        legacy: Config.AppSpoofConfig?,
    ): Config.AppSpoofConfig? {
        if (profile == null) return legacy
        val privacy =
            profile.privacy.takeUnless { it == Config.AppPrivacyMode.INHERIT }
                ?: legacy?.privacyMode
                ?: Config.AppPrivacyMode.INHERIT
        val merged =
            Config.AppSpoofConfig(
                profile.template ?: legacy?.template,
                profile.keybox ?: legacy?.keyboxFilename,
                privacy,
            )
        return merged.takeUnless {
            it.template == null &&
                it.keyboxFilename == null &&
                it.privacyMode == Config.AppPrivacyMode.INHERIT
        }
    }

    fun resolveAppConfig(
        uid: Int,
        legacy: Config.AppSpoofConfig?,
    ): Config.AppSpoofConfig? = mergeAppConfig(resolveUid(uid).selection.profile, legacy)

    fun profilePrivacyMode(uid: Int): Config.AppPrivacyMode? =
        resolveUid(uid).selection.profile?.privacy?.takeUnless { it == Config.AppPrivacyMode.INHERIT }

    fun rkpPassthrough(uid: Int): Boolean =
        resolveUid(uid).selection.profile?.rkpPassthrough ?: Config.isRkpPassthroughEnabled

    fun drmPassthrough(uid: Int): Boolean =
        resolveUid(uid).selection.profile?.drmPassthrough ?: Config.isDrmPassthroughEnabled
''',
)
swap(
    policy,
    '''        val appConfig =
            profile?.let {
                Config.AppSpoofConfig(it.template, it.keybox, it.privacy)
            } ?: legacyRule
        val patchJson = JSONObject()
''',
    '''        val appConfig = mergeAppConfig(profile, legacyRule)
        val rkpPassthrough = profile?.rkpPassthrough ?: Config.isRkpPassthroughEnabled
        val drmPassthrough = profile?.drmPassthrough ?: Config.isDrmPassthroughEnabled
        val patchJson = JSONObject()
''',
)
swap(
    policy,
    '''            .put("rkp", if (profile?.rkpPassthrough ?: Config.isRkpPassthroughEnabled) "genuine_passthrough" else "certificate_compatibility")
            .put("drm", if (profile?.drmPassthrough == true) "genuine_passthrough" else if (profile?.drmPassthrough == false) "configured_path" else "inherit")
''',
    '''            .put("rkp", if (rkpPassthrough) "genuine_passthrough" else "certificate_compatibility")
            .put("drm", if (drmPassthrough) "genuine_passthrough" else "configured_path")
''',
)

config = "service/src/main/java/cleveres/tricky/cleverestech/Config.kt"
swap(
    config,
    '''    fun getAppConfig(uid: Int): AppSpoofConfig? {
        PolicyState.profileAppConfig(uid)?.let { return it }
        val state = appConfigState
        if (state.configs.isEmpty()) {
            cacheValue(state.cache, uid, null)
            return null
        }
        val pkgs = getPackages(uid)
        getCachedValue(state.cache, uid)?.let { return it.value }
        var result: AppSpoofConfig? = null
        val len = pkgs.size
        for (i in 0 until len) {
            val config = state.configs.get(pkgs[i])
            if (config != null) {
                result = config
                break
            }
        }
        cacheValue(state.cache, uid, result)
        return result
    }
''',
    '''    fun getAppConfig(uid: Int): AppSpoofConfig? {
        val state = appConfigState
        if (state.configs.isEmpty()) {
            cacheValue(state.cache, uid, null)
            return PolicyState.resolveAppConfig(uid, null)
        }
        val pkgs = getPackages(uid)
        getCachedValue(state.cache, uid)?.let { return PolicyState.resolveAppConfig(uid, it.value) }
        var result: AppSpoofConfig? = null
        val len = pkgs.size
        for (i in 0 until len) {
            val config = state.configs.get(pkgs[i])
            if (config != null) {
                result = config
                break
            }
        }
        cacheValue(state.cache, uid, result)
        return PolicyState.resolveAppConfig(uid, result)
    }
''',
)

swap(
    "service/src/main/java/cleveres/tricky/cleverestech/DrmInterceptor.kt",
    '''        private fun shouldProtectUid(uid: Int): Boolean =
            Config.getAppPrivacyMode(uid) == Config.AppPrivacyMode.ISOLATE &&
                (PolicyState.usesV2() || Config.isSpoofEnabled) &&
                PolicyState.profileDrmPassthrough(uid) != true
''',
    '''        private fun shouldProtectUid(uid: Int): Boolean =
            Config.getAppPrivacyMode(uid) == Config.AppPrivacyMode.ISOLATE &&
                (PolicyState.usesV2() || Config.isSpoofEnabled) &&
                !PolicyState.drmPassthrough(uid)
''',
)

test = "service/src/test/java/cleveres/tricky/cleverestech/PolicyStateTest.kt"
p = Path(test)
text = p.read_text()
anchor = '''    @Test
    fun `invalid transaction does not replace active snapshot`() {
'''
addition = '''    @Test
    fun `profile inherit keeps legacy app configuration fields`() {
        val appConfig = File(root, "app_config")
        appConfig.writeText("com.example.app null legacy.xml isolate")
        Config.updateAppConfigs(appConfig).getOrThrow()
        val profile =
            JSONObject()
                .put("name", "Overlay")
                .put("applications", JSONArray().put("com.example.app"))
                .put("privacy", "inherit")
                .put("features", JSONObject().put("buildIdentity", false))
                .put("securityPatch", JSONObject())
        install(profiles = JSONArray().put(profile))
        cachePackages(10_015, arrayOf("com.example.app"))

        val runtime = Config.getAppConfig(10_015)
        val effective = PolicyState.effectiveStateJson("com.example.app")

        assertEquals("legacy.xml", runtime?.keyboxFilename)
        assertEquals(Config.AppPrivacyMode.ISOLATE, Config.getAppPrivacyMode(10_015))
        assertEquals("legacy.xml", effective.getString("keyboxReference"))
        assertEquals("isolate", effective.getString("privacy"))
    }

'''
if text.count(anchor) != 1:
    raise SystemExit("PolicyStateTest insertion anchor mismatch")
p.write_text(text.replace(anchor, addition + anchor, 1))
