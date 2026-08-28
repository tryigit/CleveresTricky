package cleveres.tricky.cleverestech

import androidx.annotation.VisibleForTesting
import cleveres.tricky.cleverestech.keystore.CertHack
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Transient transport or protocol failure at the unprivileged Rust backend boundary. */
internal class RustBackendUnavailableException(
    cause: Throwable? = null,
) : IOException("Rust backend is unavailable", cause)

/**
 * One managed boundary for keybox material. XML is parsed and private keys are normalized and
 * retained by the unprivileged Rust backend; this layer materializes only public Android/JCA
 * objects plus opaque key handles.
 */
internal object KeyboxLoader {
    internal enum class FileScope(
        val wireValue: Int,
    ) {
        CONFIG_ROOT(0),
        KEYBOX_DIRECTORY(1),
    }

    internal data class ParsedFile(
        val snapshotSha256: String?,
        val keyboxes: List<CertHack.KeyBox>,
    )

    private val backendOutageObserved = AtomicBoolean(false)
    private val activeSetHealthy = AtomicBoolean(true)

    @VisibleForTesting
    internal var parserOverride: ((ByteArray, String) -> List<CertHack.KeyBox>)? = null

    @VisibleForTesting
    internal var fileParserOverride: ((FileScope, String) -> ParsedFile)? = null

    @VisibleForTesting
    internal var activeSetOverride: ((List<ByteArray>) -> Boolean)? = null

    fun parse(
        xml: ByteArray,
        filename: String,
    ): List<CertHack.KeyBox> =
        try {
            val override = parserOverride
            if (override != null) {
                override(xml, filename)
            } else {
                val document = NativeBackend.parseKeybox(xml)
                if (document == null) emptyList() else KeyboxJcaAdapter.materialize(document, filename)
            }
        } catch (error: RustBackendUnavailableException) {
            backendOutageObserved.set(true)
            throw error
        } finally {
            xml.fill(0)
        }

    fun parseFile(
        scope: FileScope,
        filename: String,
    ): List<CertHack.KeyBox> = parseFileSnapshot(scope, filename).keyboxes

    fun parseFileSnapshot(
        scope: FileScope,
        filename: String,
    ): ParsedFile =
        try {
            val override = fileParserOverride
            if (override != null) {
                override(scope, filename)
            } else {
                val document = NativeBackend.parseKeyboxFile(scope.wireValue, filename)
                    ?: return ParsedFile(null, emptyList())
                ParsedFile(
                    snapshotSha256 = document.snapshotSha256,
                    keyboxes = KeyboxJcaAdapter.materialize(document, filename),
                )
            }
        } catch (error: RustBackendUnavailableException) {
            backendOutageObserved.set(true)
            throw error
        }

    /**
     * Replaces the Rust backend's secret-key store with exactly the handles referenced by the
     * validated immutable managed snapshot. Unknown handles fail closed, which detects backend
     * restart before stale managed state becomes active.
     */
    fun commitActive(keyboxes: List<CertHack.KeyBox>): Boolean {
        if (keyboxes.size > MAX_ACTIVE_KEYS) return recordActiveSetResult(false)
        val ids = ArrayList<ByteArray>(keyboxes.size)
        try {
            for (keybox in keyboxes) {
                val privateKey = keybox.keyPair().private
                if (privateKey.format != BACKEND_KEY_FORMAT) return recordActiveSetResult(false)
                val id = privateKey.encoded ?: return recordActiveSetResult(false)
                if (id.size != KEY_ID_BYTES || id.all { it == 0.toByte() }) {
                    id.fill(0)
                    return recordActiveSetResult(false)
                }
                if (ids.any { existing -> existing.contentEquals(id) }) {
                    id.fill(0)
                    continue
                }
                ids += id
            }
            activeSetOverride?.let { return recordActiveSetResult(it(ids.map(ByteArray::copyOf))) }
            val payloadLength = STORE_CONTROL_HEADER_BYTES + ids.size * KEY_ID_BYTES
            val response =
                NativeBackend.transact(
                    OP_KEYBOX_PARSE,
                    payloadLength,
                    MAX_STORE_CONTROL_RESPONSE_BYTES,
                    propagateTransportFailure = true,
                ) { output ->
                    output.write(STORE_CONTROL_MAGIC)
                    output.write(STORE_CONTROL_VERSION)
                    output.write(STORE_ACTION_RETAIN)
                    output.write((ids.size ushr 8) and 0xff)
                    output.write(ids.size and 0xff)
                    ids.forEach(output::write)
                } ?: return recordActiveSetResult(false)
            return try {
                recordActiveSetResult(response.contentEquals(OK_BYTES))
            } finally {
                response.fill(0)
            }
        } catch (error: RustBackendUnavailableException) {
            activeSetHealthy.set(false)
            backendOutageObserved.set(true)
            throw error
        } finally {
            ids.forEach { it.fill(0) }
        }
    }

    private fun recordActiveSetResult(success: Boolean): Boolean {
        activeSetHealthy.set(success)
        return success
    }

    @JvmStatic
    fun isActiveSetHealthy(): Boolean = activeSetHealthy.get()

    internal fun consumeBackendOutage(): Boolean = backendOutageObserved.getAndSet(false)

    @VisibleForTesting
    internal fun resetForTesting() {
        parserOverride = null
        fileParserOverride = null
        activeSetOverride = null
        backendOutageObserved.set(false)
        activeSetHealthy.set(true)
    }

    private const val OP_KEYBOX_PARSE = 23
    internal const val MAX_ACTIVE_KEYS = 256
    private const val KEY_ID_BYTES = 16
    private const val STORE_CONTROL_HEADER_BYTES = 8
    private const val STORE_CONTROL_VERSION = 1
    private const val STORE_ACTION_RETAIN = 1
    private const val MAX_STORE_CONTROL_RESPONSE_BYTES = 16
    private const val BACKEND_KEY_FORMAT = "CleveresTricky-KeyId-v1"
    private val STORE_CONTROL_MAGIC = byteArrayOf('C'.code.toByte(), 'T'.code.toByte(), 'K'.code.toByte(), 'S'.code.toByte())
    private val OK_BYTES = byteArrayOf('o'.code.toByte(), 'k'.code.toByte())
}