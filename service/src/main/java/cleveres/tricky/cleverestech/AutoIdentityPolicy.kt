package cleveres.tricky.cleverestech

/** Resolves whether scheduled Auto Identity has global or profile-scoped work. */
internal object AutoIdentityPolicy {
    data class Decision(
        val shouldRun: Boolean,
        val globalLiveApply: Boolean,
        val profileScoped: Boolean,
    )

    fun evaluate(globalCronEnabled: Boolean): Decision {
        val globalLiveApply =
            globalCronEnabled && PolicyState.isTopLevelFeatureEnabled(PolicyState.Feature.BUILD_IDENTITY)
        val profileScoped = PolicyState.hasProfileAutoIdentityWork()
        return Decision(
            shouldRun = globalLiveApply || profileScoped,
            globalLiveApply = globalLiveApply,
            profileScoped = profileScoped,
        )
    }
}
