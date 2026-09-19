package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.json.JSONArray
import org.json.JSONObject

enum class KeyboxPriorityCategory {
    VALID_RKP,
    VALID_STRONGBOX,
    VALID_TEE,
    VALID_UNKNOWN,
    INVALID_EXPIRED_RKP,
    INVALID_EXPIRED_STRONGBOX,
    INVALID_EXPIRED_TEE,
    INVALID_EXPIRED_UNKNOWN,
    INVALID_REVOKED_RKP,
    INVALID_REVOKED_STRONGBOX,
    INVALID_REVOKED_TEE,
    INVALID_REVOKED_UNKNOWN,
    INVALID_VERIFICATION_FAILED_RKP,
    INVALID_VERIFICATION_FAILED_STRONGBOX,
    INVALID_VERIFICATION_FAILED_TEE,
    INVALID_VERIFICATION_FAILED_UNKNOWN,
    ;

    companion object {
        val DEFAULT_ORDER: List<KeyboxPriorityCategory> = entries.toList()

        fun fromValidityAndLevel(
            validityState: KeyboxVerifier.ValidityState,
            invalidReason: KeyboxVerifier.InvalidReason?,
            securityLevel: String,
        ): KeyboxPriorityCategory {
            val levelSuffix = when (securityLevel) {
                "RKP" -> "RKP"
                "StrongBox" -> "STRONGBOX"
                "TEE" -> "TEE"
                else -> "UNKNOWN"
            }
            return when {
                validityState == KeyboxVerifier.ValidityState.VALID -> {
                    valueOf("VALID_$levelSuffix")
                }
                invalidReason == KeyboxVerifier.InvalidReason.EXPIRED -> {
                    valueOf("INVALID_EXPIRED_$levelSuffix")
                }
                invalidReason == KeyboxVerifier.InvalidReason.REVOKED -> {
                    valueOf("INVALID_REVOKED_$levelSuffix")
                }
                else -> {
                    valueOf("INVALID_VERIFICATION_FAILED_$levelSuffix")
                }
            }
        }
    }
}

data class KeyboxPriorityPreference(
    val mode: Mode = Mode.DEFAULT,
    val customOrder: List<KeyboxPriorityCategory> = emptyList(),
) {
    enum class Mode {
        DEFAULT,
        CUSTOM,
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode.name.lowercase())
        if (mode == Mode.CUSTOM && customOrder.isNotEmpty()) {
            put("customOrder", JSONArray(customOrder.map { it.name }))
        }
    }

    fun effectiveOrder(): List<KeyboxPriorityCategory> =
        if (mode == Mode.CUSTOM && customOrder.isNotEmpty()) {
            customOrder
        } else {
            KeyboxPriorityCategory.DEFAULT_ORDER
        }

    companion object {
        val DEFAULT = KeyboxPriorityPreference()

        fun fromJson(json: JSONObject?): KeyboxPriorityPreference {
            if (json == null) return DEFAULT
            return try {
                val modeStr = json.optString("mode", "default")
                val mode = when (modeStr.lowercase()) {
                    "custom" -> Mode.CUSTOM
                    else -> Mode.DEFAULT
                }
                val orderArray = json.optJSONArray("customOrder")
                val customOrder = if (mode == Mode.CUSTOM) {
                    if (orderArray == null || orderArray.length() != KeyboxPriorityCategory.DEFAULT_ORDER.size) {
                        Logger.w("Invalid custom priority order: incomplete; falling back to default")
                        return DEFAULT
                    }
                    val parsed = mutableListOf<KeyboxPriorityCategory>()
                    for (i in 0 until orderArray.length()) {
                        val name = orderArray.optString(i)
                        try {
                            parsed.add(KeyboxPriorityCategory.valueOf(name))
                        } catch (_: IllegalArgumentException) {
                            Logger.w("Invalid custom priority order: unknown category; falling back to default")
                            return DEFAULT
                        }
                    }
                    if (parsed.toSet() != KeyboxPriorityCategory.DEFAULT_ORDER.toSet()) {
                        Logger.w("Invalid custom priority order: duplicate or missing category; falling back to default")
                        return DEFAULT
                    }
                    parsed.toList()
                } else {
                    emptyList()
                }
                KeyboxPriorityPreference(mode, customOrder)
            } catch (_: Exception) {
                Logger.w("Failed to parse keybox priority preference; using default")
                DEFAULT
            }
        }
    }
}

object KeyboxPriorityOrder {
    internal fun <T> filterTopPriorityTier(
        candidates: List<T>,
        order: List<KeyboxPriorityCategory>,
        categorySelector: (T) -> KeyboxPriorityCategory,
    ): List<T> {
        if (candidates.size <= 1) return candidates
        val rankMap = order.mapIndexed { index, cat -> cat to index }.toMap()
        var minRank = Int.MAX_VALUE
        val ranked = ArrayList<Pair<T, Int>>(candidates.size)
        for (item in candidates) {
            val cat = categorySelector(item)
            val rank = rankMap[cat] ?: Int.MAX_VALUE
            if (rank < minRank) minRank = rank
            ranked.add(item to rank)
        }
        if (minRank == Int.MAX_VALUE) return candidates
        val topTier = ArrayList<T>()
        for ((item, rank) in ranked) {
            if (rank == minRank) {
                topTier.add(item)
            }
        }
        return if (topTier.isEmpty()) candidates else topTier
    }

    @JvmStatic
    fun filterEligibleCandidates(candidates: List<CertHack.KeyBox>?): List<CertHack.KeyBox> {
        if (candidates.isNullOrEmpty()) return emptyList()
        val blockInvalid = Config.isBlockInvalidKeyboxesEnabled
        return candidates.filter { box ->
            KeyboxValidityTracker.isEligible(box.filename, blockInvalid)
        }
    }

    @JvmStatic
    fun filterTopPriorityTier(candidates: List<CertHack.KeyBox>): List<CertHack.KeyBox> {
        val eligibleCandidates = filterEligibleCandidates(candidates)
        if (eligibleCandidates.size <= 1) return eligibleCandidates
        val preference = Config.keyboxPriorityPreference
        if (preference.mode != KeyboxPriorityPreference.Mode.CUSTOM || preference.customOrder.isEmpty()) {
            return eligibleCandidates
        }
        return filterTopPriorityTier(eligibleCandidates, preference.effectiveOrder()) { box ->
            val entry = KeyboxValidityTracker.getState(box.filename())
            val validity = entry?.validityState ?: KeyboxVerifier.ValidityState.VALID
            val reason = entry?.invalidReason
            // Publish-cached level: no PKIX validation or native inspection per call.
            val level = CertHack.cachedPriorityLevel(box)
            KeyboxPriorityCategory.fromValidityAndLevel(validity, reason, level)
        }
    }
}
