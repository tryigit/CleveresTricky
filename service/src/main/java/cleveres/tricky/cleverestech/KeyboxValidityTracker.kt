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
        synchronized(lock) {
            val previousMap = stateMap
            stateMap = buildMap {
                for (result in results) {
                    val key = result.storageId.ifEmpty { result.filename }
                    if (result.status == KeyboxVerifier.Status.ERROR) {
                        previousMap[key]?.let { put(key, it) }
                    } else {
                        put(key, Entry(result.validityState, result.invalidReason))
                    }
                }
            }
        }
    }

    fun getState(storageId: String): Entry? {
        return stateMap[storageId]
    }

    fun isEligible(storageId: String, blockInvalid: Boolean): Boolean {
        val entry = stateMap[storageId] ?: return true // Unknown state -> eligible
        return KeyboxVerifier.isEligible(entry.validityState, entry.invalidReason, blockInvalid)
    }

    fun snapshot(): Map<String, Entry> = stateMap

    internal fun clear() {
        synchronized(lock) {
            stateMap = emptyMap()
        }
    }
}
