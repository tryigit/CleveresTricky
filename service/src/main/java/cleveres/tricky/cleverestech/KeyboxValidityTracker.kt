package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier

internal object KeyboxValidityTracker {
    private val lock = Any()

    @Volatile
    private var stateMap: Map<String, Entry> = emptyMap()

    data class Entry(
        val validityState: KeyboxVerifier.ValidityState,
        val invalidReason: KeyboxVerifier.InvalidReason?,
    )

    fun update(results: List<KeyboxVerifier.Result>) {
        val newMap = buildMap {
            for (result in results) {
                val key = result.storageId.ifEmpty { result.filename }
                if (result.status == KeyboxVerifier.Status.ERROR) continue
                put(key, Entry(result.validityState, result.invalidReason))
            }
        }
        synchronized(lock) {
            stateMap = newMap
        }
    }

    fun getState(storageId: String): Entry? {
        return stateMap[storageId]
    }

    fun isEligible(storageId: String, blockInvalid: Boolean): Boolean {
        val entry = stateMap[storageId] ?: return true // Unknown state -> eligible
        if (entry.validityState == KeyboxVerifier.ValidityState.VALID) return true
        if (!blockInvalid) {
            // When block is OFF, Verification Failed is always blocked
            return entry.invalidReason != KeyboxVerifier.InvalidReason.VERIFICATION_FAILED
        }
        return false // Block ON -> all Invalid entries excluded
    }

    fun snapshot(): Map<String, Entry> = stateMap

    internal fun clear() {
        synchronized(lock) {
            stateMap = emptyMap()
        }
    }
}
