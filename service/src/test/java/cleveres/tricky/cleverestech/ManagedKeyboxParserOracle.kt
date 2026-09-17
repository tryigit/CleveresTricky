package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.readFileSnapshotBounded
import java.io.File
import java.io.StringReader
import java.security.MessageDigest

/** JVM-only legacy oracle for tests whose production path requires the supervised Rust backend. */
internal object ManagedKeyboxParserOracle {
    fun install() {
        ManagedOpaqueKeyOracle.reset()
        KeyboxLoader.parserOverride = null
        KeyboxLoader.parserWithProvenanceOverride = { bytes, filename, authenticatedRkpProvenance ->
            ManagedOpaqueKeyOracle.parse(StringReader(bytes.toString(Charsets.UTF_8)), filename, authenticatedRkpProvenance)
        }
        KeyboxLoader.fileParserOverride = { scope, filename ->
            val file =
                when (scope) {
                    KeyboxLoader.FileScope.CONFIG_ROOT -> File(Config.getConfigRoot(), filename)
                    KeyboxLoader.FileScope.KEYBOX_DIRECTORY -> File(File(Config.getConfigRoot(), "keyboxes"), filename)
                }
            val snapshot = readFileSnapshotBounded(file, 1, StoredKeyboxInventory.MAX_XML_BYTES)
            try {
                val isRkp = RkpProvenanceStore.isRkp(filename)
                KeyboxLoader.ParsedFile(
                    snapshotSha256 = sha256Hex(snapshot),
                    keyboxes = ManagedOpaqueKeyOracle.parse(StringReader(snapshot.toString(Charsets.UTF_8)), filename, isRkp),
                )
            } finally {
                snapshot.fill(0)
            }
        }
        KeyboxLoader.activeSetOverride = { keyIds -> keyIds.all(ManagedOpaqueKeyOracle::contains) }
    }

    fun reset() {
        KeyboxLoader.resetForTesting()
        ManagedOpaqueKeyOracle.reset()
        RkpProvenanceStore.resetForTesting()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return try {
            val alphabet = "0123456789abcdef"
            buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(alphabet[value ushr 4])
                    append(alphabet[value and 0x0f])
                }
            }
        } finally {
            digest.fill(0)
        }
    }
}
