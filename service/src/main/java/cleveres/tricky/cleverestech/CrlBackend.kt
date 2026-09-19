package cleveres.tricky.cleverestech

import androidx.annotation.VisibleForTesting
import java.io.IOException

/** Thin managed transport boundary for immutable CRL state owned by the Rust backend. */
internal object CrlBackend {
    @VisibleForTesting
    internal var refreshOverride: ((ByteArray) -> CrlWire.Handle?)? = null

    @VisibleForTesting
    internal var queryOverride: ((Long, List<CrlWire.Query>) -> CrlWire.Result?)? = null

    private data class ActiveCrl(
        val generation: Long,
        val identity: NativeBackend.BackendIdentity?,
    )

    // Generation and backend identity publish and read as one snapshot: two
    // independent volatiles allowed a refresh race to pair a new generation
    // with the previous epoch and report spurious stale generations.
    @Volatile
    private var activeCrl = ActiveCrl(0L, null)

    /**
     * Serialized across all callers: the network path refreshes without
     * holding the caller's cache lock while other paths hold it, so two
     * overlapping refreshes could otherwise publish in reverse generation
     * order and leave a stale handle active. The transact-plus-publish
     * section takes no other lock, so this cannot invert any lock ordering.
     */
    @Synchronized
    fun refresh(crl: ByteArray): CrlWire.Handle? {
        refreshOverride?.let { return it(crl) }
        val payloadLength = CrlWire.refreshLength(crl.size) ?: return null
        val response =
            NativeBackend.transact(
                OP_CRL,
                payloadLength,
                CrlWire.MAX_RESPONSE_BYTES,
                propagateTransportFailure = true,
            ) { output ->
                CrlWire.writeRefresh(output, crl)
            } ?: return null
        val handle =
            CrlWire.decodeRefresh(response)
                ?: throw RustBackendUnavailableException(IOException("Invalid CRL refresh response"))
        activeCrl = ActiveCrl(handle.generation, NativeBackend.currentBackendIdentity())
        return handle
    }

    fun check(
        generation: Long,
        queries: List<CrlWire.Query>,
    ): CrlWire.Result? {
        queryOverride?.let { return it(generation, queries) }
        val snapshot = activeCrl
        val identity = snapshot.identity
        if (generation != snapshot.generation || identity == null || !NativeBackend.isCurrentBackendIdentity(identity)) {
            throw RustBackendStateException(BackendStatus.STALE_GENERATION)
        }
        val payloadLength = CrlWire.queryLength(queries) ?: return null
        val response =
            NativeBackend.transact(
                OP_CRL,
                payloadLength,
                CrlWire.MAX_RESPONSE_BYTES,
                propagateTransportFailure = true,
            ) { output ->
                CrlWire.writeQuery(output, generation, queries)
            } ?: return null
        return CrlWire.decodeQuery(response, generation, queries.size)
            ?: throw RustBackendUnavailableException(IOException("Invalid CRL query response"))
    }

    @VisibleForTesting
    internal fun resetForTesting() {
        refreshOverride = null
        queryOverride = null
        activeCrl = ActiveCrl(0L, null)
    }

    private const val OP_CRL = 27
}
