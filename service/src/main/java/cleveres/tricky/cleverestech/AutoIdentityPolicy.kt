package cleveres.tricky.cleverestech

import org.json.JSONObject

/** Resolves whether scheduled Auto Identity has global or profile-scoped work. */
internal object AutoIdentityPolicy {
    data class Decision(
        val shouldRun: Boolean,
        val globalLiveApply: Boolean,
        val profileScoped: Boolean,
    )

    fun evaluate(globalCronEnabled: Boolean): Decision {
        val globalLiveApply =
            globalCronEnabled && PolicyState.isFeatureEnabled(PolicyState.Feature.BUILD_IDENTITY)
        val profileScoped = hasProfileScopedRefreshWork()
        return Decision(
            shouldRun = globalLiveApply || profileScoped,
            globalLiveApply = globalLiveApply,
            profileScoped = profileScoped,
        )
    }

    /**
     * A profile opts into Auto Identity through its existing identityRefresh override.
     * The override must be explicitly true so the global boot-randomization setting does not
     * silently turn profile Auto Identity on. Build Identity may be inherited from the global or
     * active-profile base, but an explicit false on the selected profile still wins.
     */
    internal fun hasProfileScopedRefreshWork(): Boolean {
        if (!PolicyState.usesV2()) return false
        val state = PolicyState.stateJson()
        val globalFeatures = state.optJSONObject("features") ?: return false
        val profiles = state.optJSONArray("profiles") ?: return false
        val globalBuild = globalFeatures.optBoolean("buildIdentity", false)

        val activeName =
            if (state.has("activeProfile") && !state.isNull("activeProfile")) {
                (state.opt("activeProfile") as? String)?.trim().orEmpty()
            } else {
                ""
            }
        var activeBuild = globalBuild
        if (activeName.isNotEmpty()) {
            for (index in 0 until profiles.length()) {
                val profile = profiles.optJSONObject(index) ?: continue
                if (!isEnabled(profile) || !profile.optString("name").equals(activeName, ignoreCase = true)) continue
                activeBuild = overriddenBuild(globalBuild, profile)
                if (explicitAutoIdentity(profile) && activeBuild) return true
                break
            }
        }

        for (index in 0 until profiles.length()) {
            val profile = profiles.optJSONObject(index) ?: continue
            if (!isEnabled(profile) || profile.optString("name").equals(activeName, ignoreCase = true) || !hasApplicationScope(profile)) {
                continue
            }
            if (!explicitAutoIdentity(profile)) continue
            if (overriddenBuild(activeBuild, profile)) return true
        }
        return false
    }

    private fun isEnabled(profile: JSONObject): Boolean =
        !profile.has("enabled") || profile.optBoolean("enabled", true)

    private fun hasApplicationScope(profile: JSONObject): Boolean =
        profile.optJSONArray("applications")?.length()?.let { it > 0 } == true

    private fun explicitAutoIdentity(profile: JSONObject): Boolean {
        val features = profile.optJSONObject("features") ?: return false
        return features.has("identityRefresh") && features.optBoolean("identityRefresh", false)
    }

    private fun overriddenBuild(base: Boolean, profile: JSONObject): Boolean {
        val features = profile.optJSONObject("features") ?: return base
        return if (features.has("buildIdentity")) features.optBoolean("buildIdentity", base) else base
    }
}
