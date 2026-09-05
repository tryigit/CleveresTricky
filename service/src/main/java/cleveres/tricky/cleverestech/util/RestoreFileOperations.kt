package cleveres.tricky.cleverestech.util

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Descriptor-bound filesystem operations used by backup restore transactions. */
internal interface RestoreFileOperations {
    fun begin(
        configDir: File,
        token: String,
        maxSnapshotBytes: Long,
    )

    fun snapshot(
        configDir: File,
        token: String,
        target: File,
    )

    fun replace(
        configDir: File,
        token: String,
        target: File,
        content: ByteArray,
    )

    fun delete(
        configDir: File,
        token: String,
        target: File,
    )

    fun rollback(
        configDir: File,
        token: String,
    )

    fun commit(
        configDir: File,
        token: String,
    )

    fun abort(
        configDir: File,
        token: String,
    )

    fun exportRecovery(
        configDir: File,
        token: String,
    ): String
}

/** Selects the privileged Android broker or a fail-closed descriptor-relative JVM backend. */
internal object RestoreFiles {
    private val jvmBackend: RestoreFileOperations = JvmSecureRestoreFileOperations()

    fun current(): RestoreFileOperations =
        (SecureFile.impl as? RestoreFileOperations) ?: jvmBackend
}

internal class JvmSecureRestoreFileOperations(
    private val nowNanos: () -> Long = System::nanoTime,
) : RestoreFileOperations {
    private data class Original(
        val relativePath: String,
        val bytes: ByteArray?,
    )

    private class Transaction(
        val rootPath: Path,
        val root: SecureDirectoryStream<Path>,
        val keyboxes: SecureDirectoryStream<Path>?,
        val maxSnapshotBytes: Long,
        var touchedNanos: Long,
    ) {
        var snapshotBytes: Long = 0L
        var keyboxesVerified: Boolean = false
        val originals = ArrayList<Original>()

        fun closeAndWipe() {
            originals.forEach { original -> original.bytes?.fill(0) }
            originals.clear()
            var closeFailure: Throwable? = null
            try {
                keyboxes?.close()
            } catch (error: Throwable) {
                closeFailure = error
            }
            try {
                root.close()
            } catch (error: Throwable) {
                val first = closeFailure
                if (first == null) {
                    closeFailure = error
                } else {
                    first.addSuppressed(error)
                }
            }
            closeFailure?.let { throw IOException("Could not close secure restore directory capabilities", it) }
        }
    }

    private val lock = Any()
    private val transactions = HashMap<String, Transaction>()

    override fun begin(
        configDir: File,
        token: String,
        maxSnapshotBytes: Long,
    ) {
        validateToken(token)
        require(maxSnapshotBytes in 0L..MAX_RESTORE_SNAPSHOT_BYTES) {
            "Restore snapshot limit exceeds the secure backend bound"
        }
        val rootPath = normalizedRoot(configDir)
        val root = openAbsoluteSecureDirectory(rootPath)
        val keyboxes =
            try {
                root.newDirectoryStream(
                    FileSystems.getDefault().getPath(KEYBOX_DIRECTORY),
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: NoSuchFileException) {
                null
            } catch (error: Throwable) {
                root.close()
                throw error
            }
        try {
            verifyReplacementSemantics(root)
        } catch (error: Throwable) {
            runCatching { keyboxes?.close() }
            runCatching { root.close() }
            throw error
        }
        synchronized(lock) {
            pruneExpiredTransactions()
            if (transactions.containsKey(token)) {
                keyboxes?.close()
                root.close()
                throw IOException("Restore transaction token is already active")
            }
            if (transactions.size >= MAX_ACTIVE_RESTORE_TRANSACTIONS) {
                keyboxes?.close()
                root.close()
                throw IOException("Restore transaction capacity exhausted")
            }
            transactions[token] =
                Transaction(
                    rootPath = rootPath,
                    root = root,
                    keyboxes = keyboxes,
                    maxSnapshotBytes = maxSnapshotBytes,
                    touchedNanos = nowNanos(),
                )
        }
    }

    override fun snapshot(
        configDir: File,
        token: String,
        target: File,
    ) {
        synchronized(lock) {
            val transaction = requireTransaction(configDir, token)
            val relative = relativeTarget(transaction.rootPath, target)
            if (transaction.originals.any { it.relativePath == relative }) {
                throw IOException("Restore target was already snapshotted")
            }
            if (transaction.originals.size >= MAX_RESTORE_TARGETS) {
                throw IOException("Restore transaction target count exceeds bound")
            }

            val globalUsed = transactions.values.sumOf { it.snapshotBytes }
            val ownRemaining = transaction.maxSnapshotBytes - transaction.snapshotBytes
            val globalRemaining = MAX_GLOBAL_RESTORE_SNAPSHOT_BYTES - globalUsed
            if (ownRemaining < 0L || globalRemaining < 0L) {
                throw IOException("Restore snapshot accounting is inconsistent")
            }
            val bytes = readOptional(transaction, relative, minOf(ownRemaining, globalRemaining))
            val added = bytes?.size?.toLong() ?: 0L
            transaction.snapshotBytes = Math.addExact(transaction.snapshotBytes, added)
            transaction.originals += Original(relative, bytes)
        }
    }

    override fun replace(
        configDir: File,
        token: String,
        target: File,
        content: ByteArray,
    ) {
        synchronized(lock) {
            val transaction = requireTransaction(configDir, token)
            val relative = requireSnapshotted(transaction, target)
            withParent(transaction, relative) { parent, leaf ->
                atomicWrite(parent, leaf, content)
            }
        }
    }

    override fun delete(
        configDir: File,
        token: String,
        target: File,
    ) {
        synchronized(lock) {
            val transaction = requireTransaction(configDir, token)
            val relative = requireSnapshotted(transaction, target)
            try {
                withParent(transaction, relative) { parent, leaf ->
                    try {
                        parent.deleteFile(leaf)
                    } catch (_: NoSuchFileException) {
                        // Already absent is the requested state.
                    }
                }
            } catch (_: NoSuchFileException) {
                // A parent that was absent at begin also means the target is absent.
            }
        }
    }

    override fun rollback(
        configDir: File,
        token: String,
    ) {
        synchronized(lock) {
            val transaction = requireTransaction(configDir, token)
            var failure: Throwable? = null
            transaction.originals.asReversed().forEach { original ->
                try {
                    restoreOriginal(transaction, original)
                } catch (error: Throwable) {
                    val first = failure
                    if (first == null) {
                        failure = error
                    } else {
                        first.addSuppressed(error)
                    }
                }
            }
            failure?.let { throw IOException("Secure restore rollback was incomplete", it) }
            removeTransaction(token)
        }
    }

    override fun commit(
        configDir: File,
        token: String,
    ) {
        synchronized(lock) {
            requireTransaction(configDir, token)
            removeTransaction(token)
        }
    }

    override fun abort(
        configDir: File,
        token: String,
    ) {
        synchronized(lock) {
            requireTransaction(configDir, token)
            removeTransaction(token)
        }
    }

    override fun exportRecovery(
        configDir: File,
        token: String,
    ): String {
        synchronized(lock) {
            val transaction = requireTransaction(configDir, token)
            val created = ArrayList<Path>()
            val manifest = StringBuilder()
            try {
                transaction.originals.forEachIndexed { index, original ->
                    val encodedPath = original.relativePath.toByteArray(Charsets.UTF_8).toHex()
                    val bytes = original.bytes
                    if (bytes != null) {
                        val name = ".restore-recovery-$token-${index.toString().padStart(4, '0')}.bak"
                        val path = FileSystems.getDefault().getPath(name)
                        atomicWrite(transaction.root, path, bytes)
                        created.add(path)
                        manifest.append(index.toString().padStart(4, '0'))
                            .append("\tpresent\t")
                            .append(encodedPath)
                            .append('\n')
                    } else {
                        manifest.append(index.toString().padStart(4, '0'))
                            .append("\tabsent\t")
                            .append(encodedPath)
                            .append('\n')
                    }
                }
                val manifestName = ".restore-recovery-$token.manifest"
                val manifestPath = FileSystems.getDefault().getPath(manifestName)
                val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
                try {
                    atomicWrite(transaction.root, manifestPath, manifestBytes)
                } finally {
                    manifestBytes.fill(0)
                }
                removeTransaction(token)
                return transaction.rootPath.resolve(manifestName).toString()
            } catch (error: Throwable) {
                created.forEach { path -> runCatching { transaction.root.deleteFile(path) } }
                throw IOException("Could not export restore recovery data", error)
            }
        }
    }

    private fun requireTransaction(
        configDir: File,
        token: String,
    ): Transaction {
        validateToken(token)
        pruneExpiredTransactions()
        val transaction = transactions[token] ?: throw IOException("Restore transaction is not active")
        if (transaction.rootPath != normalizedRoot(configDir)) {
            throw SecurityException("Restore transaction root changed")
        }
        transaction.touchedNanos = nowNanos()
        return transaction
    }

    private fun pruneExpiredTransactions() {
        val now = nowNanos()
        val expired =
            transactions.entries
                .asSequence()
                .filter { (_, transaction) ->
                    now - transaction.touchedNanos >= RESTORE_TRANSACTION_TTL_NANOS
                }
                .map { it.key }
                .toList()
        expired.forEach { token ->
            val transaction = transactions.remove(token) ?: return@forEach
            runCatching { transaction.closeAndWipe() }
        }
    }

    private fun removeTransaction(token: String) {
        transactions.remove(token)?.closeAndWipe()
    }

    private fun requireSnapshotted(
        transaction: Transaction,
        target: File,
    ): String {
        val relative = relativeTarget(transaction.rootPath, target)
        if (transaction.originals.none { it.relativePath == relative }) {
            throw SecurityException("Restore mutation was not snapshotted")
        }
        return relative
    }

    private fun restoreOriginal(
        transaction: Transaction,
        original: Original,
    ) {
        val bytes = original.bytes
        if (bytes == null) {
            try {
                withParent(transaction, original.relativePath) { parent, leaf ->
                    try {
                        parent.deleteFile(leaf)
                    } catch (_: NoSuchFileException) {
                        // The target was absent originally and remains absent.
                    }
                }
            } catch (_: NoSuchFileException) {
                // A parent absent at begin is equivalent to the original absent state.
            }
        } else {
            withParent(transaction, original.relativePath) { parent, leaf ->
                atomicWrite(parent, leaf, bytes)
            }
        }
    }

    private fun readOptional(
        transaction: Transaction,
        relative: String,
        maxBytes: Long,
    ): ByteArray? {
        if (maxBytes < 0L) throw IOException("Restore snapshot exceeds its size limit")
        return try {
            withParent(transaction, relative) { parent, leaf ->
                val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                parent.newByteChannel(leaf, options).use { channel ->
                    val size = channel.size()
                    if (size < 0L || size > maxBytes || size > Int.MAX_VALUE.toLong()) {
                        throw IOException("Restore snapshot exceeds its size limit")
                    }
                    val bytes = ByteArray(size.toInt())
                    try {
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) {
                            val count = channel.read(buffer)
                            if (count < 0) throw IOException("Restore snapshot ended early")
                            if (count == 0) continue
                        }
                        val trailing = ByteBuffer.allocate(1)
                        if (channel.read(trailing) > 0 || channel.size() != size) {
                            throw IOException("Restore source changed while snapshotting")
                        }
                        bytes
                    } catch (error: Throwable) {
                        bytes.fill(0)
                        throw error
                    }
                }
            }
        } catch (_: NoSuchFileException) {
            null
        }
    }

    private fun atomicWrite(
        parent: SecureDirectoryStream<Path>,
        leaf: Path,
        content: ByteArray,
    ) {
        val temporary = FileSystems.getDefault().getPath(".restore-${UUID.randomUUID()}.tmp")
        val options =
            setOf<OpenOption>(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        var created = false
        try {
            val attribute = PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS)
            parent.newByteChannel(temporary, options, attribute).use { channel ->
                created = true
                val fileChannel = channel as? FileChannel
                    ?: throw IOException("Secure restore provider does not expose a durable file channel")
                val buffer = ByteBuffer.wrap(content)
                while (buffer.hasRemaining()) fileChannel.write(buffer)
                fileChannel.force(true)
            }
            parent.move(temporary, parent, leaf)
            created = false
        } finally {
            if (created) runCatching { parent.deleteFile(temporary) }
        }
    }

    /**
     * Verifies that this exact secure-directory provider replaces an existing regular target.
     * SecureDirectoryStream.move has provider-specific replacement semantics and exposes no
     * REPLACE_EXISTING option, so unsupported providers are rejected before any restore mutation.
     */
    private fun verifyReplacementSemantics(directory: SecureDirectoryStream<Path>) {
        val source = FileSystems.getDefault().getPath(".restore-probe-${UUID.randomUUID()}.src")
        val destination = FileSystems.getDefault().getPath(".restore-probe-${UUID.randomUUID()}.dst")
        val sourceBytes = byteArrayOf(0x51, 0x52, 0x53)
        val destinationBytes = byteArrayOf(0x21, 0x22)
        try {
            createProbeFile(directory, source, sourceBytes)
            createProbeFile(directory, destination, destinationBytes)
            try {
                directory.move(source, directory, destination)
            } catch (error: FileAlreadyExistsException) {
                throw IOException(
                    "Secure restore provider cannot replace an existing target through a pinned directory",
                    error,
                )
            }
            requireProbeSourceGone(directory, source)
            verifyProbeContent(directory, destination, sourceBytes)
        } finally {
            sourceBytes.fill(0)
            destinationBytes.fill(0)
            runCatching { directory.deleteFile(source) }
            runCatching { directory.deleteFile(destination) }
        }
    }

    private fun createProbeFile(
        directory: SecureDirectoryStream<Path>,
        name: Path,
        content: ByteArray,
    ) {
        val options =
            setOf<OpenOption>(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        val attribute = PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS)
        directory.newByteChannel(name, options, attribute).use { channel ->
            val fileChannel = channel as? FileChannel
                ?: throw IOException("Secure restore provider does not expose a durable file channel")
            val buffer = ByteBuffer.wrap(content)
            while (buffer.hasRemaining()) fileChannel.write(buffer)
            fileChannel.force(true)
        }
    }

    private fun requireProbeSourceGone(
        directory: SecureDirectoryStream<Path>,
        source: Path,
    ) {
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        try {
            directory.newByteChannel(source, options).use { }
        } catch (_: NoSuchFileException) {
            return
        }
        throw IOException("Secure restore provider did not move the replacement source")
    }

    private fun verifyProbeContent(
        directory: SecureDirectoryStream<Path>,
        destination: Path,
        expected: ByteArray,
    ) {
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        val actual = ByteArray(expected.size)
        try {
            directory.newByteChannel(destination, options).use { channel ->
                val buffer = ByteBuffer.wrap(actual)
                while (buffer.hasRemaining()) {
                    val count = channel.read(buffer)
                    if (count < 0) throw IOException("Secure restore replacement probe ended early")
                    if (count == 0) continue
                }
                val trailing = ByteBuffer.allocate(1)
                if (channel.read(trailing) > 0 || channel.size() != expected.size.toLong()) {
                    throw IOException("Secure restore replacement probe produced an unexpected target size")
                }
            }
            if (!actual.contentEquals(expected)) {
                throw IOException("Secure restore provider did not replace the existing target")
            }
        } finally {
            actual.fill(0)
        }
    }

    private inline fun <T> withParent(
        transaction: Transaction,
        relative: String,
        block: (SecureDirectoryStream<Path>, Path) -> T,
    ): T {
        val components = relative.split('/')
        val leaf = FileSystems.getDefault().getPath(components.last())
        return if (components.size == 1) {
            block(transaction.root, leaf)
        } else {
            val parent = transaction.keyboxes ?: throw NoSuchFileException(KEYBOX_DIRECTORY)
            if (!transaction.keyboxesVerified) {
                verifyReplacementSemantics(parent)
                transaction.keyboxesVerified = true
            }
            block(parent, leaf)
        }
    }

    private fun relativeTarget(
        rootPath: Path,
        target: File,
    ): String {
        val normalized = target.absoluteFile.toPath().normalize()
        if (normalized == rootPath || !normalized.startsWith(rootPath)) {
            throw SecurityException("Restore target escaped configuration directory")
        }
        val relative = rootPath.relativize(normalized)
        val components = relative.map(Path::toString)
        val allowed =
            components.size == 1 ||
                (components.size == 2 && components.first() == KEYBOX_DIRECTORY)
        if (!allowed || components.any { !isSafeComponent(it) }) {
            throw SecurityException("Restore target is outside an allowed capability subtree")
        }
        return components.joinToString("/")
    }

    private fun normalizedRoot(configDir: File): Path =
        configDir.absoluteFile.toPath().normalize()

    private fun openAbsoluteSecureDirectory(path: Path): SecureDirectoryStream<Path> {
        val absolute = path.toAbsolutePath().normalize()
        val filesystemRoot = absolute.root ?: throw IOException("Restore root has no filesystem root")
        var current: DirectoryStream<Path> = java.nio.file.Files.newDirectoryStream(filesystemRoot)
        var secure = current as? SecureDirectoryStream<Path>
            ?: run {
                current.close()
                throw IOException("Filesystem provider does not support secure directory streams")
            }
        try {
            for (component in filesystemRoot.relativize(absolute)) {
                val next = secure.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS)
                secure.close()
                secure = next
            }
            return secure
        } catch (error: Throwable) {
            runCatching { secure.close() }
            throw error
        }
    }

    private fun validateToken(token: String) {
        if (token.length != RESTORE_TOKEN_LENGTH || token.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            throw SecurityException("Invalid restore transaction token")
        }
    }

    private fun isSafeComponent(value: String): Boolean =
        value.isNotEmpty() && value != "." && value != ".." && '/' !in value && '\u0000' !in value

    private fun ByteArray.toHex(): String {
        val output = CharArray(size * 2)
        val alphabet = "0123456789abcdef"
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = alphabet[value ushr 4]
            output[index * 2 + 1] = alphabet[value and 0x0f]
        }
        fill(0)
        return output.concatToString()
    }

    private companion object {
        const val KEYBOX_DIRECTORY = "keyboxes"
        const val RESTORE_TOKEN_LENGTH = 32
        const val MAX_RESTORE_SNAPSHOT_BYTES = 32L * 1024L * 1024L
        const val MAX_GLOBAL_RESTORE_SNAPSHOT_BYTES = 64L * 1024L * 1024L
        const val MAX_ACTIVE_RESTORE_TRANSACTIONS = 4
        const val MAX_RESTORE_TARGETS = 512
        const val RESTORE_TRANSACTION_TTL_NANOS = 15L * 60L * 1_000_000_000L
        val PRIVATE_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
    }
}
