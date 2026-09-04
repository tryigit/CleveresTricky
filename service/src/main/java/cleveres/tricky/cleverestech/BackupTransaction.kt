package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Shared bounded streaming used while creating backups. */
internal object BackupIo {
    fun copyBounded(
        input: InputStream,
        output: OutputStream,
        entryLimit: Long,
        remainingTotal: Long,
    ): Long {
        require(entryLimit >= 0 && remainingTotal >= 0)
        val limit = minOf(entryLimit, remainingTotal)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        return try {
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count.toLong() > limit - total) {
                    throw IOException("Backup source changed while it was being read or exceeds its size limit")
                }
                output.write(buffer, 0, count)
                total += count
            }
            total
        } finally {
            buffer.fill(0)
        }
    }
}

/**
 * Applies a validated restore set with rollback. All original files are snapshotted before
 * the first mutation, so a write/delete failure cannot leave a mixed configuration set.
 */
internal object BackupRestoreTransaction {
    data class Mutation(
        val target: File,
        val replacement: ByteArray?,
    )

    private data class Original(
        val target: File,
        val existed: Boolean,
        val backup: File?,
    )

    /** Preserves the original before-mutation callback API for existing callers. */
    fun apply(
        configDir: File,
        mutations: List<Mutation>,
        maxSnapshotBytes: Long,
        beforeMutation: ((Int, File) -> Unit)? = null,
    ) {
        apply(
            configDir,
            mutations,
            maxSnapshotBytes,
            afterMutation = null,
            onRollback = null,
            beforeMutation = beforeMutation,
        )
    }

    /** Applies mutations and optionally validates/publishes runtime state before committing. */
    fun apply(
        configDir: File,
        mutations: List<Mutation>,
        maxSnapshotBytes: Long,
        afterMutation: (() -> Unit)?,
        onRollback: (() -> Unit)?,
        beforeMutation: ((Int, File) -> Unit)? = null,
    ) {
        if (mutations.isEmpty()) return
        require(maxSnapshotBytes >= 0) { "Restore snapshot limit must be non-negative" }
        val rootPath = configDir.absoluteFile.toPath().normalize()
        require(!Files.isSymbolicLink(rootPath) && Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            "Configuration directory is unavailable"
        }

        val unique = LinkedHashMap<String, Mutation>()
        mutations.forEach { mutation ->
            val normalized = mutation.target.absoluteFile.toPath().normalize()
            if (normalized == rootPath || !normalized.startsWith(rootPath)) {
                throw SecurityException("Restore transaction escaped configuration directory")
            }
            var parent = normalized.parent
            while (parent != null && parent != rootPath) {
                if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS) &&
                    (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
                ) {
                    throw SecurityException("Restore transaction parent is unsafe")
                }
                parent = parent.parent
            }
            if (parent != rootPath) throw SecurityException("Restore transaction escaped configuration directory")
            unique[normalized.toString()] = Mutation(normalized.toFile(), mutation.replacement)
        }

        val transactionDir = File(rootPath.toFile(), ".restore-txn-${UUID.randomUUID()}")
        SecureFile.mkdirs(transactionDir, 448)
        val originals = ArrayList<Original>(unique.size)
        var retainRecoveryArtifacts = false
        try {
            var snapshotBytes = 0L
            unique.values.forEachIndexed { index, mutation ->
                val path = mutation.target.toPath()
                val existed = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                if (existed && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw SecurityException("Refusing non-regular restore transaction target")
                }
                val backup =
                    if (existed) {
                        val copy = File(transactionDir, index.toString().padStart(4, '0') + ".bak")
                        try {
                            val remaining = maxSnapshotBytes - snapshotBytes
                            if (remaining < 0) throw IOException("Restore snapshot exceeds its size limit")
                            val copied =
                                Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                                    val initialSize = channel.size()
                                    if (initialSize !in 0..remaining) {
                                        throw IOException("Restore snapshot exceeds its size limit")
                                    }
                                    Files.newOutputStream(
                                        copy.toPath(),
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE,
                                    ).use { output ->
                                        BackupIo.copyBounded(
                                            Channels.newInputStream(channel),
                                            output,
                                            remaining,
                                            remaining,
                                        )
                                    }.also { streamed ->
                                        if (streamed != initialSize || channel.size() != initialSize) {
                                            throw IOException("Restore transaction source changed while snapshotting")
                                        }
                                    }
                                }
                            if (!Files.isRegularFile(copy.toPath(), LinkOption.NOFOLLOW_LINKS) || copy.length() != copied) {
                                throw IOException("Restore transaction snapshot is incomplete")
                            }
                            runCatching {
                                Files.setPosixFilePermissions(
                                    copy.toPath(),
                                    Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS),
                                )
                            }
                            runCatching {
                                Files.setLastModifiedTime(
                                    copy.toPath(),
                                    Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS),
                                )
                            }
                            snapshotBytes += copied
                        } catch (e: IOException) {
                            runCatching { Files.deleteIfExists(copy.toPath()) }
                            throw SecurityException("Refusing unsafe, unreadable, or oversized restore snapshot", e)
                        }
                        copy
                    } else {
                        null
                    }
                originals += Original(mutation.target, existed, backup)
            }

            var afterMutationInvoked = false
            try {
                unique.values.forEachIndexed { index, mutation ->
                    beforeMutation?.invoke(index, mutation.target)
                    val replacement = mutation.replacement
                    if (replacement == null) {
                        Files.deleteIfExists(mutation.target.toPath())
                    } else {
                        mutation.target.parentFile?.let { parent ->
                            if (parent.toPath() != rootPath && !Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                                SecureFile.mkdirs(parent, 448)
                            }
                        }
                        SecureFile.writeBytes(mutation.target, replacement)
                    }
                }
                if (afterMutation != null) {
                    afterMutationInvoked = true
                    afterMutation.invoke()
                }
            } catch (failure: Throwable) {
                var rollbackFailure: Throwable? = null
                originals.asReversed().forEach { original ->
                    try {
                        if (original.existed) {
                            val backup = requireNotNull(original.backup)
                            original.target.parentFile?.let { parent ->
                                if (Files.isSymbolicLink(parent.toPath())) {
                                    throw SecurityException("Restore rollback parent became a symbolic link")
                                }
                                if (!Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                                    SecureFile.mkdirs(parent, 448)
                                }
                            }
                            try {
                                Files.move(
                                    backup.toPath(),
                                    original.target.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING,
                                    StandardCopyOption.ATOMIC_MOVE,
                                )
                            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                                Files.move(
                                    backup.toPath(),
                                    original.target.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING,
                                )
                            }
                        } else {
                            Files.deleteIfExists(original.target.toPath())
                        }
                    } catch (error: Throwable) {
                        if (original.existed && original.backup?.exists() == true) retainRecoveryArtifacts = true
                        val existingFailure = rollbackFailure
                        if (existingFailure == null) {
                            rollbackFailure = error
                        } else {
                            existingFailure.addSuppressed(error)
                        }
                    }
                }
                if (afterMutationInvoked && onRollback != null) {
                    try {
                        onRollback.invoke()
                    } catch (error: Throwable) {
                        val existingFailure = rollbackFailure
                        if (existingFailure == null) {
                            rollbackFailure = error
                        } else {
                            existingFailure.addSuppressed(error)
                        }
                    }
                }
                if (retainRecoveryArtifacts) {
                    failure.addSuppressed(
                        IOException("Rollback recovery data retained in ${transactionDir.absolutePath}"),
                    )
                }
                rollbackFailure?.let { failure.addSuppressed(it) }
                throw failure
            }
        } finally {
            if (!retainRecoveryArtifacts) {
                originals.forEach { original ->
                    runCatching { original.backup?.let { Files.deleteIfExists(it.toPath()) } }
                }
                runCatching { Files.deleteIfExists(transactionDir.toPath()) }
            }
        }
    }
}
