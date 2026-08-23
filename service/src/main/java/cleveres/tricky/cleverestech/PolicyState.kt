package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.readUtf8FileSnapshotBounded
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object PolicyState {
    const val STATE_FILE = "policy_state_v2.json"
    const val LAST_GOOD_FILE = "policy_state_v2.last_good.json"
    const val SCHEMA_VERSION = 2

    enum class Feature(val jsonName: String) {
        BUILD_IDENTITY("buildIdentity"),
        ATTESTATION_IDENTITY("attestationIdentity"),
        TELEPHONY_IDENTITY("telephonyIdentity"),
        REGION_IDENTITY("regionIdentity"),
        IDENTITY_REFRESH("identityRefresh"),
        SECURITY_PATCH("securityPatch"),
    }

    enum class PatchMode(val configValue: String) {
        DEVICE_DEFAULT("device_default"),
        PROP("prop"),
        MANUAL("manual"),
        AUTOMATIC("automatic"),
        NO("no"),
        ;

        companion object {
            fun parse(value: String): PatchMode? = entries.firstOrNull { it.configValue == value.lowercase(Locale.ROOT) }
        }
    }

    data class FeatureSet(
        val buildIdentity: Boolean,
        val attestationIdentity: Boolean,
        val telephonyIdentity: Boolean,
        val regionIdentity: Boolean,
        val identityRefresh: Boolean,
        val securityPatch: Boolean,
    ) {
        fun enabled(feature: Feature): Boolean =
            when (feature) {
                Feature.BUILD_IDENTITY -> buildIdentity
                Feature.ATTESTATION_IDENTITY -> attestationIdentity
                Feature.TELEPHONY_IDENTITY -> telephonyIdentity
                Feature.REGION_IDENTITY -> regionIdentity
                Feature.IDENTITY_REFRESH -> identityRefresh
                Feature.SECURITY_PATCH -> securityPatch
            }

        fun withOverrides(overrides: Map<Feature, Boolean>): FeatureSet =
            copy(
                buildIdentity = overrides[Feature.BUILD_IDENTITY] ?: buildIdentity,
                attestationIdentity = overrides[Feature.ATTESTATION_IDENTITY] ?: attestationIdentity,
                telephonyIdentity = overrides[Feature.TELEPHONY_IDENTITY] ?: telephonyIdentity,
                regionIdentity = overrides[Feature.REGION_IDENTITY] ?: regionIdentity,
                identityRefresh = overrides[Feature.IDENTITY_REFRESH] ?: identityRefresh,
                securityPatch = overrides[Feature.SECURITY_PATCH] ?: securityPatch,
            )

        fun toJson(): JSONObject =
            JSONObject()
                .put(Feature.BUILD_IDENTITY.jsonName, buildIdentity)
                .put(Feature.ATTESTATION_IDENTITY.jsonName, attestationIdentity)
                .put(Feature.TELEPHONY_IDENTITY.jsonName, telephonyIdentity)
                .put(Feature.REGION_IDENTITY.jsonName, regionIdentity)
                .put(Feature.IDENTITY_REFRESH.jsonName, identityRefresh)
                .put(Feature.SECURITY_PATCH.jsonName, securityPatch)
    }

    data class PatchPolicy(
        val mode: PatchMode,
        val manualDate: LocalDate? = null,
    ) {
        fun configuredValue(): String =
            when (mode) {
                PatchMode.MANUAL -> requireNotNull(manualDate).toString()
                else -> mode.configValue
            }

        fun toJson(): JSONObject =
            JSONObject().put("mode", mode.configValue).also { objectValue ->
                if (mode == PatchMode.MANUAL) objectValue.put("value", requireNotNull(manualDate).toString())
            }
    }

    data class PatchSet(
        val thresholdMonths: Long,
        val system: PatchPolicy,
        val vendor: PatchPolicy,
        val boot: PatchPolicy,
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("automaticThresholdMonths", thresholdMonths)
                .put("system", system.toJson())
                .put("vendor", vendor.toJson())
                .put("boot", boot.toJson())
    }

    data class Profile(
        val name: String,
        val enabled: Boolean,
        val applications: Set<String>,
        val template: String?,
        val keybox: String?,
        val privacy: Config.AppPrivacyMode,
        val featureOverrides: Map<Feature, Boolean>,
        val systemPatch: PatchPolicy?,
        val vendorPatch: PatchPolicy?,
        val bootPatch: PatchPolicy?,
        val rkpPassthrough: Boolean?,
        val drmPassthrough: Boolean?,
    ) {
        fun toJson(): JSONObject {
            val features = JSONObject()
            Feature.entries.forEach { feature -> featureOverrides[feature]?.let { features.put(feature.jsonName, it) } }
            val patch = JSONObject()
            systemPatch?.let { patch.put("system", it.toJson()) }
            vendorPatch?.let { patch.put("vendor", it.toJson()) }
            bootPatch?.let { patch.put("boot", it.toJson()) }
            return JSONObject()
                .put("name", name)
                .put("enabled", enabled)
                .put("applications", JSONArray(applications.sorted()))
                .put("template", template ?: JSONObject.NULL)
                .put("keybox", keybox ?: JSONObject.NULL)
                .put("privacy", privacy.configValue)
                .put("features", features)
                .put("securityPatch", patch)
                .put("rkpPassthrough", rkpPassthrough ?: JSONObject.NULL)
                .put("drmPassthrough", drmPassthrough ?: JSONObject.NULL)
        }
    }

    private data class Assignment(
        val pattern: String,
        val profileName: String,
        val specificity: Int,
    )

    private data class Snapshot(
        val explicit: Boolean,
        val features: FeatureSet,
        val patch: PatchSet,
        val profiles: Map<String, Profile>,
        val activeProfile: String?,
        val assignments: List<Assignment>,
        val generation: Long,
        val recovery: String,
    ) {
        fun toJson(): JSONObject {
            val profilesJson = JSONArray()
            profiles.values.sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { profilesJson.put(it.toJson()) }
            return JSONObject()
                .put("version", SCHEMA_VERSION)
                .put("features", features.toJson())
                .put("securityPatch", patch.toJson())
                .put("profiles", profilesJson)
                .put("activeProfile", activeProfile ?: JSONObject.NULL)
        }
    }

    private data class SelectedProfile(
        val profile: Profile?,
        val matchedRule: String?,
        val conflict: Boolean,
    )

    private data class UidResolution(
        val generation: Long,
        val packages: Array<String>,
        val selection: SelectedProfile,
        val features: FeatureSet,
        val profileAutoIdentity: Boolean,
    )

    private data class CapturedPatch(
        val system: Int?,
        val vendor: Int?,
        val boot: Int?,
        val timestamp: Long,
    )

    private data class AutoCacheKey(
        val component: String,
        val sourceDate: LocalDate,
        val currentMonth: YearMonth,
        val thresholdMonths: Long,
        val hasCaptured: Boolean,
    )

    private val generationCounter = AtomicLong(0)
    private val automaticCache = ConcurrentHashMap<AutoCacheKey, Config.AttestationPatchComponent>()
    private val capturedByPackage = ConcurrentHashMap<String, CapturedPatch>()
    private val uidResolutionCache = ConcurrentHashMap<Int, UidResolution>()

    @Volatile
    private var initialized = false

    @Volatile
    private var root = File("/data/adb/cleverestricky")

    @Volatile
    private var snapshot =
        Snapshot(
            explicit = false,
            features = FeatureSet(false, false, false, false, false, false),
            patch = defaultPatchSet(),
            profiles = emptyMap(),
            activeProfile = null,
            assignments = emptyList(),
            generation = generationCounter.incrementAndGet(),
            recovery = "bootstrap",
        )

    @Volatile
    internal var currentDateSource: () -> LocalDate = { LocalDate.now() }

    private const val MAX_STATE_BYTES = 512L * 1024
    private const val MAX_PROFILES = 64
    private const val MAX_PROFILE_APPLICATIONS = 256
    private const val MAX_TOTAL_ASSIGNMENTS = 2048
    private const val MAX_CAPTURED_PACKAGES = 512
    private const val MAX_AUTO_CACHE = 128
    private const val MAX_UID_RESOLUTIONS = 1024
    private const val CAPTURE_TTL_MS = 15 * 60 * 1000L
    private const val CAPTURE_REFRESH_MS = 30 * 1000L
    private val profileNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9 _.-]{0,63}")
    private val packagePattern = Regex("""(?:[A-Za-z_][A-Za-z0-9_]*|[*])(?:[.](?:[A-Za-z_][A-Za-z0-9_]*|[*]))*""")
    private val keyboxPattern = Regex("[A-Za-z0-9_.-]{5,128}")
    private val builtInProfiles = setOf("maximum", "daily", "default", "minimal")
    private val isoDate = DateTimeFormatter.ISO_LOCAL_DATE

    @Synchronized
    fun initialize(configRoot: File): Result<Unit> =
        runCatching {
            root = configRoot
            initialized = true
            loadPublishedState()
        }.onFailure {
            initialized = false
            Logger.e("Policy state initialization failed", it)
        }

    @Synchronized
    fun reload(): Result<Unit> =
        runCatching {
            if (!initialized) return@runCatching
            loadPublishedState()
        }.onFailure { Logger.e("Policy state reload failed; retaining current snapshot", it) }

    @Synchronized
    fun onLegacySettingsChanged() {
        if (!initialized || snapshot.explicit) return
        snapshot = legacySnapshot("legacy")
        invalidateResolutionCaches()
    }

    private fun loadPublishedState() {
        val main = File(root, STATE_FILE)
        val mainPath = main.toPath()
        if (!Files.exists(mainPath, LinkOption.NOFOLLOW_LINKS)) {
            snapshot = legacySnapshot("legacy")
            invalidateResolutionCaches()
            return
        }
        try {
            val parsed = parseStateFile(main, "configured")
            publish(parsed, persistPrevious = false)
        } catch (error: Throwable) {
            val lastGood = File(root, LAST_GOOD_FILE)
            val recovered = runCatching { parseStateFile(lastGood, "last_known_good") }.getOrNull()
            if (recovered != null) {
                snapshot = recovered
                invalidateResolutionCaches()
                Logger.e("Configured policy state is invalid; using last-known-good state", error)
            } else {
                snapshot = legacySnapshot("invalid_config_fallback")
                invalidateResolutionCaches()
                Logger.e("Configured policy state is invalid; using legacy-safe state", error)
            }
        }
    }

    private fun parseStateFile(file: File, recovery: String): Snapshot {
        val path = file.toPath()
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Policy state must be a regular file" }
        return parseStateJson(readUtf8FileSnapshotBounded(file, 1, MAX_STATE_BYTES), recovery)
    }

    private fun parseStateJson(
        text: String,
        recovery: String = "configured",
        validateReferences: Boolean = true,
    ): Snapshot {
        require(text.toByteArray(Charsets.UTF_8).size in 1..MAX_STATE_BYTES) { "Policy state has an invalid size" }
        val rootObject = JSONObject(text)
        requireOnlyKeys(rootObject, setOf("version", "features", "securityPatch", "profiles", "activeProfile"))
        require(rootObject.getInt("version") == SCHEMA_VERSION) { "Unsupported policy schema version" }
        val features = parseFeatureSet(rootObject.getJSONObject("features"))
        val patch = parsePatchSet(rootObject.getJSONObject("securityPatch"))
        val profilesArray = rootObject.optJSONArray("profiles") ?: JSONArray()
        require(profilesArray.length() <= MAX_PROFILES) { "Too many profiles" }
        val profiles = LinkedHashMap<String, Profile>()
        val normalizedNames = HashSet<String>()
        var totalAssignments = 0
        for (index in 0 until profilesArray.length()) {
            val profile = parseProfile(profilesArray.getJSONObject(index))
            val normalized = profile.name.lowercase(Locale.ROOT)
            require(normalized !in builtInProfiles) { "Built-in profile names are reserved" }
            require(normalizedNames.add(normalized)) { "Duplicate profile name" }
            totalAssignments += profile.applications.size
            require(totalAssignments <= MAX_TOTAL_ASSIGNMENTS) { "Too many profile assignments" }
            profiles[profile.name] = profile
        }
        val assignments = buildAssignments(profiles.values)
        val activeProfile = nullableString(rootObject, "activeProfile")
        if (activeProfile != null) {
            val active = findProfile(profiles, activeProfile)
            require(active != null) { "Active profile does not exist" }
            require(active.enabled) { "Disabled profile cannot be active" }
        }
        if (validateReferences) validateAssignedProfileReferences(profiles.values)
        return Snapshot(
            explicit = true,
            features = features,
            patch = patch,
            profiles = profiles.toMap(),
            activeProfile = activeProfile,
            assignments = assignments,
            generation = generationCounter.incrementAndGet(),
            recovery = recovery,
        )
    }

    fun validateStateJson(text: String, validateReferences: Boolean): Result<Unit> =
        runCatching { parseStateJson(text, validateReferences = validateReferences) }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(it) },
        )

    private fun parseFeatureSet(value: JSONObject): FeatureSet {
        requireOnlyKeys(value, Feature.entries.mapTo(linkedSetOf()) { it.jsonName })
        Feature.entries.forEach { require(value.has(it.jsonName) && value.opt(it.jsonName) is Boolean) { "Missing feature setting" } }
        return FeatureSet(
            buildIdentity = value.getBoolean(Feature.BUILD_IDENTITY.jsonName),
            attestationIdentity = value.getBoolean(Feature.ATTESTATION_IDENTITY.jsonName),
            telephonyIdentity = value.getBoolean(Feature.TELEPHONY_IDENTITY.jsonName),
            regionIdentity = value.getBoolean(Feature.REGION_IDENTITY.jsonName),
            identityRefresh = value.getBoolean(Feature.IDENTITY_REFRESH.jsonName),
            securityPatch = value.getBoolean(Feature.SECURITY_PATCH.jsonName),
        )
    }

    private fun parseFeatureOverrides(value: JSONObject): Map<Feature, Boolean> {
        requireOnlyKeys(value, Feature.entries.mapTo(linkedSetOf()) { it.jsonName })
        val result = LinkedHashMap<Feature, Boolean>()
        Feature.entries.forEach { feature ->
            if (value.has(feature.jsonName)) {
                require(value.opt(feature.jsonName) is Boolean) { "Invalid profile feature override" }
                result[feature] = value.getBoolean(feature.jsonName)
            }
        }
        return result.toMap()
    }

    private fun parsePatchSet(value: JSONObject): PatchSet {
        requireOnlyKeys(value, setOf("automaticThresholdMonths", "system", "vendor", "boot"))
        val threshold = value.optLong("automaticThresholdMonths", 6)
        require(threshold in 1..24) { "Automatic patch threshold is out of range" }
        return PatchSet(
            thresholdMonths = threshold,
            system = parsePatchPolicy(value.optJSONObject("system") ?: JSONObject().put("mode", "device_default")),
            vendor = parsePatchPolicy(value.optJSONObject("vendor") ?: JSONObject().put("mode", "device_default")),
            boot = parsePatchPolicy(value.optJSONObject("boot") ?: JSONObject().put("mode", "device_default")),
        )
    }

    private fun parsePatchOverrides(value: JSONObject): Triple<PatchPolicy?, PatchPolicy?, PatchPolicy?> {
        requireOnlyKeys(value, setOf("system", "vendor", "boot"))
        return Triple(
            value.optJSONObject("system")?.let(::parsePatchPolicy),
            value.optJSONObject("vendor")?.let(::parsePatchPolicy),
            value.optJSONObject("boot")?.let(::parsePatchPolicy),
        )
    }

    private fun parsePatchPolicy(value: JSONObject): PatchPolicy {
        requireOnlyKeys(value, setOf("mode", "value"))
        val mode = PatchMode.parse(value.getString("mode")) ?: throw IllegalArgumentException("Unknown security patch mode")
        val manual =
            if (mode == PatchMode.MANUAL) {
                val raw = value.getString("value")
                require(raw.length == 10) { "Manual patch date must use YYYY-MM-DD" }
                try {
                    LocalDate.parse(raw, isoDate)
                } catch (error: DateTimeException) {
                    throw IllegalArgumentException("Invalid manual patch date", error)
                }
            } else {
                require(!value.has("value")) { "Patch value is only valid for manual mode" }
                null
            }
        return PatchPolicy(mode, manual)
    }

    private fun parseProfile(value: JSONObject): Profile {
        requireOnlyKeys(
            value,
            setOf(
                "name",
                "enabled",
                "applications",
                "template",
                "keybox",
                "privacy",
                "features",
                "securityPatch",
                "rkpPassthrough",
                "drmPassthrough",
            ),
        )
        val name = value.getString("name").trim()
        require(profileNamePattern.matches(name)) { "Invalid profile name" }
        val enabled =
            if (!value.has("enabled")) {
                true
            } else {
                require(value.opt("enabled") is Boolean) { "Invalid profile enabled state" }
                value.getBoolean("enabled")
            }
        val applicationsArray = value.optJSONArray("applications") ?: JSONArray()
        require(applicationsArray.length() <= MAX_PROFILE_APPLICATIONS) { "Too many applications in profile" }
        val applications = LinkedHashSet<String>()
        for (index in 0 until applicationsArray.length()) {
            val packageName = applicationsArray.getString(index).trim()
            require(packagePattern.matches(packageName)) { "Invalid profile application" }
            require(applications.add(packageName)) { "Duplicate profile application" }
        }
        val template = nullableString(value, "template")?.lowercase(Locale.ROOT)
        template?.let { require(Regex("[a-z0-9_-]{1,64}").matches(it)) { "Invalid profile template" } }
        val keybox = nullableString(value, "keybox")
        keybox?.let { require(isSafeKeyboxReference(it)) { "Invalid profile keybox reference" } }
        val privacy =
            Config.AppPrivacyMode.parse(value.optString("privacy", Config.AppPrivacyMode.INHERIT.configValue))
                ?: throw IllegalArgumentException("Invalid profile privacy mode")
        val features = parseFeatureOverrides(value.optJSONObject("features") ?: JSONObject())
        val patches = parsePatchOverrides(value.optJSONObject("securityPatch") ?: JSONObject())
        val rkp = nullableBoolean(value, "rkpPassthrough")
        val drm = nullableBoolean(value, "drmPassthrough")
        return Profile(
            name = name,
            enabled = enabled,
            applications = applications.toSet(),
            template = template,
            keybox = keybox,
            privacy = privacy,
            featureOverrides = features,
            systemPatch = patches.first,
            vendorPatch = patches.second,
            bootPatch = patches.third,
            rkpPassthrough = rkp,
            drmPassthrough = drm,
        )
    }

    private fun buildAssignments(profiles: Collection<Profile>): List<Assignment> {
        val exactOwners = HashMap<String, String>()
        val assignments = ArrayList<Assignment>()
        profiles.filter { it.enabled }.forEach { profile ->
            profile.applications.forEach { pattern ->
                if ('*' !in pattern) {
                    val previous = exactOwners.putIfAbsent(pattern, profile.name)
                    require(previous == null) { "Application is assigned to multiple profiles" }
                }
                assignments += Assignment(pattern, profile.name, pattern.count { it != '*' })
            }
        }
        return assignments.sortedWith(
            compareByDescending<Assignment> { it.specificity }
                .thenBy { it.pattern }
                .thenBy { it.profileName.lowercase(Locale.ROOT) },
        )
    }

    private fun validateAssignedProfileReferences(profiles: Collection<Profile>) {
        profiles.filter { it.enabled && it.applications.isNotEmpty() }.forEach(::validateProfileReferences)
    }

    private fun validateProfileReferences(profile: Profile) {
        profile.template?.let { require(Config.getTemplate(it) != null) { "Assigned profile references an unknown template" } }
        profile.keybox?.let { reference ->
            val direct = File(root, reference)
            val nested = File(File(root, "keyboxes"), reference)
            require(isRegularSafeReference(direct) || isRegularSafeReference(nested)) {
                "Assigned profile references an unavailable keybox"
            }
        }
    }

    private fun isRegularSafeReference(file: File): Boolean = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun isSafeKeyboxReference(value: String): Boolean {
        if (!keyboxPattern.matches(value) || value.startsWith('.')) return false
        val lower = value.lowercase(Locale.ROOT)
        return lower.endsWith(".xml") || lower.endsWith(".cbox")
    }

    private fun requireOnlyKeys(value: JSONObject, allowed: Set<String>) {
        val keys = value.keys()
        var count = 0
        while (keys.hasNext()) {
            require(++count <= 64) { "Too many object fields" }
            require(keys.next() in allowed) { "Unknown policy field" }
        }
    }

    private fun nullableString(value: JSONObject, name: String): String? {
        if (!value.has(name) || value.isNull(name)) return null
        val result = value.opt(name)
        require(result is String) { "$name must be a string or null" }
        val trimmed = result.trim()
        return trimmed.takeIf { it.isNotEmpty() }
    }

    private fun nullableBoolean(value: JSONObject, name: String): Boolean? {
        if (!value.has(name) || value.isNull(name)) return null
        val result = value.opt(name)
        require(result is Boolean) { "$name must be a boolean or null" }
        return result
    }

    private fun legacySnapshot(recovery: String): Snapshot {
        val spoofEnabled = Config.isSpoofEnabled
        val securityConfigured = hasLegacyPatchRules()
        return Snapshot(
            explicit = false,
            features =
                FeatureSet(
                    buildIdentity = spoofEnabled && Config.isBuildIdentityEnabled,
                    attestationIdentity = spoofEnabled,
                    telephonyIdentity = spoofEnabled && Config.isTelephonyEnabled,
                    regionIdentity = spoofEnabled && isRegularSafeReference(File(root, BootLogic.FILE_SPOOF_CN)),
                    identityRefresh = spoofEnabled && isRegularSafeReference(File(root, "random_on_boot")),
                    securityPatch = securityConfigured,
                ),
            patch = defaultPatchSet(),
            profiles = emptyMap(),
            activeProfile = null,
            assignments = emptyList(),
            generation = generationCounter.incrementAndGet(),
            recovery = recovery,
        )
    }

    private fun hasLegacyPatchRules(): Boolean {
        val file = File(root, "security_patch.txt")
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching {
            readUtf8FileSnapshotBounded(file, 1, 1024L * 1024L).lineSequence().any { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() && !trimmed.startsWith("#")
            }
        }.getOrDefault(false)
    }

    private fun defaultPatchSet(): PatchSet =
        PatchSet(
            thresholdMonths = 6,
            system = PatchPolicy(PatchMode.DEVICE_DEFAULT),
            vendor = PatchPolicy(PatchMode.DEVICE_DEFAULT),
            boot = PatchPolicy(PatchMode.DEVICE_DEFAULT),
        )

    private fun activeProfile(snapshotValue: Snapshot): Profile? =
        snapshotValue.activeProfile?.let { findProfile(snapshotValue.profiles, it) }?.takeIf { it.enabled }

    private fun findProfile(profiles: Map<String, Profile>, name: String): Profile? =
        profiles.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun selectProfile(packages: Array<String>, snapshotValue: Snapshot = snapshot): SelectedProfile {
        if (packages.isEmpty() || snapshotValue.profiles.isEmpty()) {
            return SelectedProfile(activeProfile(snapshotValue), null, false)
        }
        val normalizedPackages = packages.asSequence().filter(packagePattern::matches).distinct().sorted().toList()
        val matches = ArrayList<Pair<Assignment, Profile>>()
        normalizedPackages.forEach { packageName ->
            val assignment = snapshotValue.assignments.firstOrNull { wildcardMatches(it.pattern, packageName) }
            val profile = assignment?.let { findProfile(snapshotValue.profiles, it.profileName) }?.takeIf { it.enabled }
            if (assignment != null && profile != null) matches += assignment to profile
        }
        if (matches.isEmpty()) return SelectedProfile(activeProfile(snapshotValue), null, false)
        val selected = matches.first()
        val conflict = matches.asSequence().map { it.second.name.lowercase(Locale.ROOT) }.distinct().take(2).count() > 1
        return SelectedProfile(selected.second, selected.first.pattern, conflict)
    }

    private fun wildcardMatches(pattern: String, value: String): Boolean {
        if ('*' !in pattern) return pattern == value
        var valueIndex = 0
        var patternIndex = 0
        var starIndex = -1
        var matchIndex = 0
        while (valueIndex < value.length) {
            if (patternIndex < pattern.length && pattern[patternIndex] == value[valueIndex]) {
                patternIndex++
                valueIndex++
            } else if (patternIndex < pattern.length && pattern[patternIndex] == '*') {
                starIndex = patternIndex++
                matchIndex = valueIndex
            } else if (starIndex >= 0) {
                patternIndex = starIndex + 1
                valueIndex = ++matchIndex
            } else {
                return false
            }
        }
        while (patternIndex < pattern.length && pattern[patternIndex] == '*') patternIndex++
        return patternIndex == pattern.length
    }

    private fun resolvedFeatures(packages: Array<String>): FeatureSet {
        val current = snapshot
        val base = activeProfile(current)?.let { current.features.withOverrides(it.featureOverrides) } ?: current.features
        val selected = selectProfile(packages, current).profile
        return selected?.let { base.withOverrides(it.featureOverrides) } ?: base
    }

    private fun profileAutoIdentityEnabled(profile: Profile?, current: Snapshot): Boolean {
        val activeOverride = activeProfile(current)?.featureOverrides?.get(Feature.IDENTITY_REFRESH) ?: false
        return profile?.featureOverrides?.get(Feature.IDENTITY_REFRESH) ?: activeOverride
    }

    private fun featuresForProfile(profile: Profile?, current: Snapshot): FeatureSet {
        val base = activeProfile(current)?.let { current.features.withOverrides(it.featureOverrides) } ?: current.features
        return profile?.let { base.withOverrides(it.featureOverrides) } ?: base
    }

    private fun resolveUid(uid: Int): UidResolution {
        val current = snapshot
        val packages = Config.getPackages(uid)
        uidResolutionCache[uid]?.let { cached ->
            if (cached.generation == current.generation && cached.packages.contentEquals(packages)) return cached
        }
        val selection = selectProfile(packages, current)
        val features = featuresForProfile(selection.profile, current)
        val profileAutoIdentity = profileAutoIdentityEnabled(selection.profile, current)
        val resolved = UidResolution(current.generation, packages.clone(), selection, features, profileAutoIdentity)
        if (uidResolutionCache.size >= MAX_UID_RESOLUTIONS && !uidResolutionCache.containsKey(uid)) uidResolutionCache.clear()
        uidResolutionCache[uid] = resolved
        return resolved
    }

    fun usesV2(): Boolean = snapshot.explicit

    fun isFeatureEnabled(feature: Feature): Boolean = resolvedFeatures(emptyArray()).enabled(feature)

    fun isFeatureEnabled(feature: Feature, uid: Int): Boolean = resolveUid(uid).features.enabled(feature)

    internal fun isTopLevelFeatureEnabled(feature: Feature): Boolean = snapshot.features.enabled(feature)

    internal fun isProfileAutoIdentityEnabled(uid: Int): Boolean {
        if (!snapshot.explicit) return false
        val resolved = resolveUid(uid)
        return resolved.profileAutoIdentity && resolved.features.buildIdentity
    }

    internal fun hasProfileAutoIdentityWork(): Boolean {
        val current = snapshot
        if (!current.explicit) return false
        val active = activeProfile(current)

        fun hasWork(profile: Profile): Boolean {
            val features = featuresForProfile(profile, current)
            return profileAutoIdentityEnabled(profile, current) && features.buildIdentity
        }

        if (active != null && hasWork(active)) return true
        return current.profiles.values.any { profile ->
            profile.enabled &&
                profile.applications.isNotEmpty() &&
                (active == null || !profile.name.equals(active.name, ignoreCase = true)) &&
                hasWork(profile)
        }
    }

    private fun hasRuntimeScope(profile: Profile, current: Snapshot): Boolean =
        profile.enabled &&
            (profile.applications.isNotEmpty() || current.activeProfile?.equals(profile.name, ignoreCase = true) == true)

    fun hasTelephonyProfileWork(): Boolean {
        val current = snapshot
        if (resolvedFeatures(emptyArray()).telephonyIdentity) return true
        return current.profiles.values.any { profile ->
            hasRuntimeScope(profile, current) &&
                (profile.featureOverrides[Feature.TELEPHONY_IDENTITY] == true ||
                    profile.privacy != Config.AppPrivacyMode.INHERIT)
        }
    }

    fun hasDrmProfileWork(): Boolean {
        val current = snapshot
        return current.profiles.values.any { profile ->
            hasRuntimeScope(profile, current) && profile.privacy == Config.AppPrivacyMode.ISOLATE
        }
    }

    private fun mergeAppConfig(
        profile: Profile?,
        legacy: Config.AppSpoofConfig?,
        useAutoIdentitySource: Boolean = false,
    ): Config.AppSpoofConfig? {
        if (profile == null) return legacy
        val privacy =
            profile.privacy.takeUnless { it == Config.AppPrivacyMode.INHERIT }
                ?: legacy?.privacyMode
                ?: Config.AppPrivacyMode.INHERIT
        val merged =
            Config.AppSpoofConfig(
                if (useAutoIdentitySource) null else profile.template ?: legacy?.template,
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
    ): Config.AppSpoofConfig? {
        val resolved = resolveUid(uid)
        val useAutoIdentitySource = resolved.profileAutoIdentity && resolved.features.buildIdentity
        return mergeAppConfig(resolved.selection.profile, legacy, useAutoIdentitySource)
    }

    fun profilePrivacyMode(uid: Int): Config.AppPrivacyMode? =
        resolveUid(uid).selection.profile?.privacy?.takeUnless { it == Config.AppPrivacyMode.INHERIT }

    fun rkpPassthrough(uid: Int): Boolean =
        resolveUid(uid).selection.profile?.rkpPassthrough ?: Config.isRkpPassthroughEnabled

    fun drmPassthrough(uid: Int): Boolean =
        resolveUid(uid).selection.profile?.drmPassthrough ?: Config.isDrmPassthroughEnabled

    fun resolveAttestationPatchLevels(
        uid: Int,
        capturedSystem: Int?,
        capturedVendor: Int?,
        capturedBoot: Int?,
    ): Config.AttestationPatchLevels {
        val resolvedUid = resolveUid(uid)
        val packages = resolvedUid.packages
        val features = resolvedUid.features
        recordCaptured(packages, capturedSystem, capturedVendor, capturedBoot)
        if (!features.securityPatch) return keepPatchLevels()
        val current = snapshot
        if (!current.explicit) return Config.getAttestationPatchLevels(uid)
        val selected = resolvedUid.selection.profile
        val basePatch = current.patch
        val now = currentDateSource()
        return Config.AttestationPatchLevels(
            system = resolvePatchComponent("system", selected?.systemPatch ?: basePatch.system, false, capturedSystem, basePatch.thresholdMonths, now),
            vendor = resolvePatchComponent("vendor", selected?.vendorPatch ?: basePatch.vendor, true, capturedVendor, basePatch.thresholdMonths, now),
            boot = resolvePatchComponent("boot", selected?.bootPatch ?: basePatch.boot, true, capturedBoot, basePatch.thresholdMonths, now),
        )
    }

    private fun keepPatchLevels(): Config.AttestationPatchLevels =
        Config.AttestationPatchLevels(
            Config.AttestationPatchComponent(Config.PatchDisposition.KEEP),
            Config.AttestationPatchComponent(Config.PatchDisposition.KEEP),
            Config.AttestationPatchComponent(Config.PatchDisposition.KEEP),
        )

    private fun resolvePatchComponent(
        component: String,
        policy: PatchPolicy,
        long: Boolean,
        captured: Int?,
        thresholdMonths: Long,
        now: LocalDate? = null,
    ): Config.AttestationPatchComponent =
        when (policy.mode) {
            PatchMode.DEVICE_DEFAULT -> Config.AttestationPatchComponent(Config.PatchDisposition.KEEP)
            PatchMode.NO -> Config.AttestationPatchComponent(Config.PatchDisposition.OMIT)
            PatchMode.MANUAL ->
                Config.AttestationPatchComponent(
                    Config.PatchDisposition.REPLACE,
                    dateToPatch(requireNotNull(policy.manualDate), long),
                )
            PatchMode.PROP -> {
                val propertyDate = readPropertyDate(component)
                if (propertyDate == null) {
                    Config.AttestationPatchComponent(Config.PatchDisposition.KEEP)
                } else {
                    Config.AttestationPatchComponent(Config.PatchDisposition.REPLACE, dateToPatch(propertyDate, long))
                }
            }
            PatchMode.AUTOMATIC -> resolveAutomatic(component, long, captured, thresholdMonths, now ?: currentDateSource())
        }

    private fun resolveAutomatic(
        component: String,
        long: Boolean,
        captured: Int?,
        thresholdMonths: Long,
        now: LocalDate,
    ): Config.AttestationPatchComponent {
        val capturedDate = captured?.let { patchToDate(it, long) }
        val sourceDate = capturedDate ?: readPropertyDate(component)
            ?: return Config.AttestationPatchComponent(Config.PatchDisposition.KEEP)
        val cacheKey = AutoCacheKey(component, sourceDate, YearMonth.from(now), thresholdMonths, capturedDate != null)
        automaticCache[cacheKey]?.let { return it }
        val result =
            if (sourceDate.isBefore(now.minusMonths(thresholdMonths))) {
                val recent = now.withDayOfMonth(1).minusMonths(1).withDayOfMonth(5)
                Config.AttestationPatchComponent(Config.PatchDisposition.REPLACE, dateToPatch(recent, long))
            } else if (capturedDate != null) {
                Config.AttestationPatchComponent(Config.PatchDisposition.KEEP)
            } else {
                Config.AttestationPatchComponent(Config.PatchDisposition.REPLACE, dateToPatch(sourceDate, long))
            }
        if (automaticCache.size >= MAX_AUTO_CACHE && !automaticCache.containsKey(cacheKey)) automaticCache.clear()
        automaticCache[cacheKey] = result
        return result
    }

    private fun readPropertyDate(component: String): LocalDate? {
        val name =
            when (component) {
                "system" -> "ro.build.version.security_patch"
                "vendor" -> "ro.vendor.build.security_patch"
                "boot" -> "ro.bootimage.build.version.security_patch"
                else -> return null
            }
        val value = systemPropertiesGet(name, "").orEmpty().trim()
        if (value.length != 10) return null
        return runCatching { LocalDate.parse(value, isoDate) }.getOrNull()
    }

    private fun dateToPatch(date: LocalDate, long: Boolean): Int =
        if (long) date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth else date.year * 100 + date.monthValue

    private fun patchToDate(value: Int, long: Boolean): LocalDate? =
        runCatching {
            if (long) {
                val year = value / 10_000
                val month = (value / 100) % 100
                val day = value % 100
                LocalDate.of(year, month, day)
            } else {
                val year = value / 100
                val month = value % 100
                LocalDate.of(year, month, 1)
            }
        }.getOrNull()

    private fun recordCaptured(
        packages: Array<String>,
        system: Int?,
        vendor: Int?,
        boot: Int?,
    ) {
        if (system == null && vendor == null && boot == null) return
        val now = System.currentTimeMillis()
        var record: CapturedPatch? = null
        packages.asSequence().filter(packagePattern::matches).distinct().take(16).forEach { packageName ->
            val previous = capturedByPackage[packageName]
            val age = previous?.let { now - it.timestamp }
            if (previous != null && previous.system == system && previous.vendor == vendor && previous.boot == boot && age != null && age >= 0 && age < CAPTURE_REFRESH_MS) {
                return@forEach
            }
            if (capturedByPackage.size >= MAX_CAPTURED_PACKAGES) capturedByPackage.clear()
            val next = record ?: CapturedPatch(system, vendor, boot, now).also { record = it }
            capturedByPackage[packageName] = next
        }
    }

    private fun capturedForPackage(packageName: String): CapturedPatch? {
        val captured = capturedByPackage[packageName] ?: return null
        val age = System.currentTimeMillis() - captured.timestamp
        if (age < 0 || age > CAPTURE_TTL_MS) {
            capturedByPackage.remove(packageName, captured)
            return null
        }
        return captured
    }

    fun stateJson(): JSONObject {
        val current = snapshot
        val result = current.toJson()
        val builtIns = JSONArray()
        builtInProfiles.sorted().forEach { builtIns.put(it) }
        result
            .put("generation", current.generation)
            .put("source", if (current.explicit) "v2" else "legacy")
            .put("recovery", current.recovery)
            .put("builtInProfiles", builtIns)
            .put("runtime", runtimeJson())
        return result
    }

    fun effectiveStateJson(packageName: String): JSONObject {
        require(packagePattern.matches(packageName) && '*' !in packageName) { "Invalid package name" }
        val current = snapshot
        val selected = selectProfile(arrayOf(packageName), current)
        val profile = selected.profile
        val features = resolvedFeatures(arrayOf(packageName))
        val captured = capturedForPackage(packageName)
        val patch = current.patch
        val systemPolicy = profile?.systemPatch ?: patch.system
        val vendorPolicy = profile?.vendorPatch ?: patch.vendor
        val bootPolicy = profile?.bootPatch ?: patch.boot
        val legacyRule = readLegacyAppRule(packageName)
        val profileAutoIdentity = profileAutoIdentityEnabled(profile, current) && features.buildIdentity
        val appConfig = mergeAppConfig(profile, legacyRule, profileAutoIdentity)
        val rkpPassthrough = profile?.rkpPassthrough ?: Config.isRkpPassthroughEnabled
        val drmPassthrough = profile?.drmPassthrough ?: Config.isDrmPassthroughEnabled
        val patchJson = JSONObject()
            .put("system", componentStateJson("system", systemPolicy, false, captured?.system, features.securityPatch, current.explicit))
            .put("vendor", componentStateJson("vendor", vendorPolicy, true, captured?.vendor, features.securityPatch, current.explicit))
            .put("boot", componentStateJson("boot", bootPolicy, true, captured?.boot, features.securityPatch, current.explicit))
        val providerMode = readSmallSetting("boot_props_mode", setOf("auto", "force", "disable"), "auto")
        return JSONObject()
            .put("package", packageName)
            .put("matchedApplicationRule", selected.matchedRule ?: legacyRule?.let { packageName } ?: JSONObject.NULL)
            .put("matchedProfile", profile?.name ?: JSONObject.NULL)
            .put("profileConflict", selected.conflict)
            .put("scope", if (selected.matchedRule != null || legacyRule != null) "targeted" else if (Config.isGlobalMode) "global" else "unmatched")
            .put("identityTemplate", appConfig?.template ?: JSONObject.NULL)
            .put("identitySource", if (profileAutoIdentity) "auto_identity" else if (appConfig?.template != null) "template" else "global")
            .put("keyboxReference", appConfig?.keyboxFilename ?: JSONObject.NULL)
            .put("privacy", appConfig?.privacyMode?.configValue ?: Config.AppPrivacyMode.INHERIT.configValue)
            .put("buildIdentity", features.buildIdentity)
            .put("attestationIdentity", features.attestationIdentity)
            .put("telephonyIdentity", features.telephonyIdentity)
            .put("regionIdentity", features.regionIdentity)
            .put("identityRefresh", features.identityRefresh)
            .put("securityPatchOverride", features.securityPatch)
            .put("securityPatch", patchJson)
            .put("rkp", if (rkpPassthrough) "genuine_passthrough" else "certificate_compatibility")
            .put("drm", if (drmPassthrough) "genuine_passthrough" else "configured_path")
            .put("keyMint", "genuine_platform_keymint_strongbox")
            .put("keystoreCore", if (KeystoreInterceptor.isRunning()) "active" else "waiting")
            .put("providerCoexistence", providerMode)
            .put("rebootRequired", features.regionIdentity)
    }

    private fun componentStateJson(
        component: String,
        policy: PatchPolicy,
        long: Boolean,
        captured: Int?,
        enabled: Boolean,
        explicit: Boolean,
    ): JSONObject {
        if (!enabled) {
            return JSONObject()
                .put("captured", formatPatch(captured, long) ?: JSONObject.NULL)
                .put("configured", "disabled")
                .put("effective", formatPatch(captured, long) ?: "device")
        }
        if (!explicit) {
            return JSONObject()
                .put("captured", formatPatch(captured, long) ?: JSONObject.NULL)
                .put("configured", "legacy security_patch.txt")
                .put("effective", "legacy resolver")
        }
        val resolved = resolvePatchComponent(component, policy, long, captured, snapshot.patch.thresholdMonths)
        val effective =
            when (resolved.disposition) {
                Config.PatchDisposition.KEEP -> formatPatch(captured, long) ?: "device"
                Config.PatchDisposition.OMIT -> "omitted"
                Config.PatchDisposition.REPLACE -> formatPatch(resolved.value, long) ?: resolved.value.toString()
            }
        return JSONObject()
            .put("captured", formatPatch(captured, long) ?: JSONObject.NULL)
            .put("configured", policy.configuredValue())
            .put("effective", effective)
    }

    private fun formatPatch(value: Int?, long: Boolean): String? {
        if (value == null) return null
        val date = patchToDate(value, long) ?: return null
        return if (long) date.toString() else "%04d-%02d".format(Locale.ROOT, date.year, date.monthValue)
    }

    private fun readLegacyAppRule(packageName: String): Config.AppSpoofConfig? {
        val file = File(root, "app_config")
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return runCatching {
            var matched: Config.AppSpoofConfig? = null
            readUtf8FileSnapshotBounded(file, 1, 1024L * 1024L).lineSequence().take(1024).forEach { raw ->
                val line = raw.trim()
                if (matched != null || line.isEmpty() || line.startsWith("#")) return@forEach
                val columns = line.split(Regex("\\s+"))
                if (columns.isEmpty() || !wildcardMatches(columns[0], packageName)) return@forEach
                val template = columns.getOrNull(1)?.takeUnless { it == "null" }
                val keybox = columns.getOrNull(2)?.takeUnless { it == "null" }
                val privacy = columns.getOrNull(3)?.let(Config.AppPrivacyMode::parse) ?: Config.AppPrivacyMode.INHERIT
                matched = Config.AppSpoofConfig(template, keybox, privacy)
            }
            matched
        }.getOrNull()
    }

    private fun readSmallSetting(filename: String, allowed: Set<String>, fallback: String): String {
        val file = File(root, filename)
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return fallback
        return runCatching {
            readUtf8FileSnapshotBounded(file, 1, 64)
                .trim()
                .lowercase(Locale.ROOT)
                .takeIf { it in allowed } ?: fallback
        }.getOrDefault(fallback)
    }

    fun runtimeJson(): JSONObject {
        val current = snapshot
        val global = resolvedFeatures(emptyArray())
        fun state(enabled: Boolean, active: Boolean, reboot: Boolean = false): String =
            when {
                !enabled -> "disabled"
                reboot -> "reboot_required"
                active -> "active"
                else -> "waiting_for_configuration"
            }
        val certReady = cleveres.tricky.cleverestech.keystore.CertHack.canHack()
        return JSONObject()
            .put("buildIdentity", state(global.buildIdentity, global.buildIdentity))
            .put("attestationIdentity", state(global.attestationIdentity, certReady))
            .put("telephonyIdentity", state(global.telephonyIdentity || hasTelephonyProfileWork(), TelephonyInterceptor.isRunning()))
            .put("regionIdentity", state(global.regionIdentity, false, global.regionIdentity))
            .put("identityRefresh", state(global.identityRefresh, global.identityRefresh))
            .put("securityPatch", state(global.securityPatch, certReady))
            .put("keystoreCore", if (KeystoreInterceptor.isRunning()) "active" else "waiting_for_configuration")
            .put("generation", current.generation)
    }

    @Synchronized
    fun replaceFromJson(text: String): Result<JSONObject> =
        runCatching {
            val parsed = parseStateJson(text)
            persistAndPublish(parsed)
            stateJson()
        }.onFailure { Logger.e("Policy state transaction rejected", it) }

    @Synchronized
    fun profileAction(action: String, payload: JSONObject): Result<JSONObject> =
        runCatching {
            val current = ensureExplicitSnapshot()
            val profiles = LinkedHashMap(current.profiles)
            var active = current.activeProfile
            when (action.lowercase(Locale.ROOT)) {
                "create" -> {
                    val profile = parseProfile(payload.getJSONObject("profile"))
                    require(findProfile(profiles, profile.name) == null && profile.name.lowercase(Locale.ROOT) !in builtInProfiles)
                    profiles[profile.name] = profile
                }
                "edit" -> {
                    val originalName = payload.getString("name")
                    val existing = findProfile(profiles, originalName) ?: throw IllegalArgumentException("Profile not found")
                    val profile = parseProfile(payload.getJSONObject("profile"))
                    val conflicting = findProfile(profiles, profile.name)
                    require(conflicting == null || conflicting.name == existing.name) { "Profile name already exists" }
                    profiles.remove(existing.name)
                    profiles[profile.name] = profile
                    if (active?.equals(existing.name, ignoreCase = true) == true) {
                        active = profile.name.takeIf { profile.enabled }
                    }
                }
                "rename" -> {
                    val oldName = payload.getString("name")
                    val newName = payload.getString("newName").trim()
                    require(profileNamePattern.matches(newName) && newName.lowercase(Locale.ROOT) !in builtInProfiles)
                    val existing = findProfile(profiles, oldName) ?: throw IllegalArgumentException("Profile not found")
                    require(findProfile(profiles, newName) == null) { "Profile name already exists" }
                    profiles.remove(existing.name)
                    profiles[newName] = existing.copy(name = newName)
                    if (active?.equals(existing.name, ignoreCase = true) == true) active = newName
                }
                "duplicate" -> {
                    val sourceName = payload.getString("name")
                    val newName = payload.getString("newName").trim()
                    require(profileNamePattern.matches(newName) && newName.lowercase(Locale.ROOT) !in builtInProfiles)
                    require(findProfile(profiles, newName) == null) { "Profile name already exists" }
                    val source = findProfile(profiles, sourceName) ?: throw IllegalArgumentException("Profile not found")
                    profiles[newName] = source.copy(name = newName, enabled = true, applications = emptySet())
                }
                "delete" -> {
                    val existing = findProfile(profiles, payload.getString("name")) ?: throw IllegalArgumentException("Profile not found")
                    profiles.remove(existing.name)
                    if (active?.equals(existing.name, ignoreCase = true) == true) active = null
                }
                "activate" -> {
                    val requested = payload.getString("name")
                    if (requested.lowercase(Locale.ROOT) in builtInProfiles) {
                        Config.applyProfile(requested)
                        val synced = snapshot
                        persistAndPublish(
                            synced.copy(
                                activeProfile = null,
                                generation = generationCounter.incrementAndGet(),
                                recovery = "configured",
                            ),
                        )
                        return@runCatching stateJson()
                    }
                    val profile = findProfile(profiles, requested) ?: throw IllegalArgumentException("Profile not found")
                    require(profile.enabled) { "Disabled profile cannot be activated" }
                    validateProfileReferences(profile)
                    active = profile.name
                }
                "deactivate" -> active = null
                "assign" -> {
                    val profile = findProfile(profiles, payload.getString("name")) ?: throw IllegalArgumentException("Profile not found")
                    if (profile.enabled) validateProfileReferences(profile)
                    val packageName = payload.getString("package").trim()
                    require(packagePattern.matches(packageName)) { "Invalid package assignment" }
                    profiles.entries.forEach { entry ->
                        if (entry.value.applications.contains(packageName)) {
                            entry.setValue(entry.value.copy(applications = entry.value.applications - packageName))
                        }
                    }
                    profiles[profile.name] = profile.copy(applications = profile.applications + packageName)
                }
                "unassign" -> {
                    val packageName = payload.getString("package").trim()
                    require(packagePattern.matches(packageName)) { "Invalid package assignment" }
                    profiles.entries.forEach { entry ->
                        if (entry.value.applications.contains(packageName)) {
                            entry.setValue(entry.value.copy(applications = entry.value.applications - packageName))
                        }
                    }
                }
                else -> throw IllegalArgumentException("Unsupported profile action")
            }
            require(profiles.size <= MAX_PROFILES) { "Too many profiles" }
            val rebuilt = rebuildSnapshot(current, profiles, active)
            persistAndPublish(rebuilt)
            stateJson()
        }.onFailure { Logger.e("Profile transaction rejected", it) }

    private fun ensureExplicitSnapshot(): Snapshot {
        val current = snapshot
        if (current.explicit) return current
        return Snapshot(
            explicit = true,
            features = current.features,
            patch = current.patch,
            profiles = emptyMap(),
            activeProfile = null,
            assignments = emptyList(),
            generation = generationCounter.incrementAndGet(),
            recovery = "configured",
        )
    }

    private fun rebuildSnapshot(current: Snapshot, profiles: Map<String, Profile>, activeProfile: String?): Snapshot {
        val assignments = buildAssignments(profiles.values)
        require(assignments.size <= MAX_TOTAL_ASSIGNMENTS) { "Too many profile assignments" }
        validateAssignedProfileReferences(profiles.values)
        if (activeProfile != null) {
            val active = findProfile(profiles, activeProfile)
            require(active != null) { "Active profile does not exist" }
            require(active.enabled) { "Disabled profile cannot be active" }
        }
        return current.copy(
            explicit = true,
            profiles = profiles.toMap(),
            activeProfile = activeProfile,
            assignments = assignments,
            generation = generationCounter.incrementAndGet(),
            recovery = "configured",
        )
    }

    @Synchronized
    fun applyRecommendedDefaults() {
        val thresholdMonths = 6L
        val automatic = PatchPolicy(PatchMode.AUTOMATIC)
        val securityPatchRecommended =
            readPropertyDate("system")?.isBefore(currentDateSource().minusMonths(thresholdMonths)) == true
        runCatching { Files.deleteIfExists(File(root, CronAutoIdentity.TOGGLE_FILE).toPath()) }
            .onFailure { Logger.e("Could not clear Cron Auto Identity while restoring defaults", it) }
        persistAndPublish(
            Snapshot(
                explicit = true,
                features = FeatureSet(false, false, false, false, false, securityPatchRecommended),
                patch = PatchSet(thresholdMonths, automatic, automatic, automatic),
                profiles = emptyMap(),
                activeProfile = null,
                assignments = emptyList(),
                generation = generationCounter.incrementAndGet(),
                recovery = "default",
            ),
        )
    }

    @Synchronized
    fun synchronizeBuiltInProfile() {
        if (!initialized || !snapshot.explicit) {
            onLegacySettingsChanged()
            return
        }
        val current = snapshot
        val legacy = legacySnapshot("configured")
        val updated = current.copy(
            features = legacy.features.copy(securityPatch = current.features.securityPatch),
            generation = generationCounter.incrementAndGet(),
            recovery = "configured",
        )
        runCatching { persistAndPublish(updated) }.onFailure { Logger.e("Could not synchronize built-in profile state", it) }
    }

    private fun persistAndPublish(parsed: Snapshot) {
        val serialized = parsed.toJson().toString(2)
        require(serialized.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) { "Policy state exceeds its size limit" }
        val previous = snapshot
        if (previous.explicit) {
            val previousText = previous.toJson().toString(2)
            SecureFile.writeText(File(root, LAST_GOOD_FILE), previousText)
        }
        SecureFile.writeText(File(root, STATE_FILE), serialized)
        if (!previous.explicit) SecureFile.writeText(File(root, LAST_GOOD_FILE), serialized)
        publish(parsed, persistPrevious = false)
    }

    private fun publish(parsed: Snapshot, persistPrevious: Boolean) {
        if (persistPrevious && snapshot.explicit) SecureFile.writeText(File(root, LAST_GOOD_FILE), snapshot.toJson().toString(2))
        snapshot = parsed
        invalidateResolutionCaches()
        Config.signalRuntimeController()
        CronAutoIdentity.onPolicyChanged()
        cleveres.tricky.cleverestech.keystore.CertHack.clearCertificateCache()
    }

    private fun invalidateResolutionCaches() {
        automaticCache.clear()
        capturedByPackage.clear()
        uidResolutionCache.clear()
    }

    fun invalidateUid(uid: Int) {
        uidResolutionCache.remove(uid)
    }

    @androidx.annotation.VisibleForTesting
    internal fun installStateForTesting(text: String) {
        snapshot = parseStateJson(text, recovery = "test", validateReferences = false)
        initialized = true
        invalidateResolutionCaches()
    }

    @androidx.annotation.VisibleForTesting
    internal fun setRootForTesting(directory: File) {
        root = directory
        initialized = true
        snapshot = legacySnapshot("legacy")
        invalidateResolutionCaches()
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        initialized = false
        root = File("/data/adb/cleverestricky")
        currentDateSource = { LocalDate.now() }
        snapshot = legacySnapshot("legacy")
        automaticCache.clear()
        capturedByPackage.clear()
        uidResolutionCache.clear()
    }
}
