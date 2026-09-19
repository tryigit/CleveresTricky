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
                        // Only restore the previous verdict when this batch has no
                        // entry for the key yet; otherwise a failed check would
                        // overwrite the worse verdict merged above.
                        if (!containsKey(key)) previousMap[key]?.let { put(key, it) }
                    } else {
                        val entry = Entry(result.validityState, result.invalidReason)
                        val existing = get(key)
                        put(key, if (existing == null) entry else worseOf(existing, entry))
                    }
                }
            }
        }
    }

    // Fail closed when several keyboxes share one tracker key (e.g. multiple keys
    // in a single file): a valid entry must never overwrite a worse verdict, so a
    // revoked keybox can never inherit a clean state from its file sibling.
    private fun worseOf(
        first: Entry,
        second: Entry,
    ): Entry {
        if (first.validityState == KeyboxVerifier.ValidityState.VALID) return second
        if (second.validityState == KeyboxVerifier.ValidityState.VALID) return first
        return if (severity(first.invalidReason) >= severity(second.invalidReason)) first else second
    }

    private fun severity(reason: KeyboxVerifier.InvalidReason?): Int =
        when (reason) {
            KeyboxVerifier.InvalidReason.VERIFICATION_FAILED -> 3
            KeyboxVerifier.InvalidReason.REVOKED -> 2
            KeyboxVerifier.InvalidReason.EXPIRED -> 1
            null -> 0
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
