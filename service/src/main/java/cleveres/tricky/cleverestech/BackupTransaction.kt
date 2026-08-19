package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
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

    fun apply(
        configDir: File,
        mutations: List<Mutation>,
        beforeMutation: ((Int, File) -> Unit)? = null,
    ) {
        if (mutations.isEmpty()) return
        val canonicalRoot = configDir.canonicalFile
        require(Files.isDirectory(canonicalRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Configuration directory is unavailable"
        }

        val unique = LinkedHashMap<String, Mutation>()
        mutations.forEach { mutation ->
            val canonical = mutation.target.canonicalFile
            if (canonical != canonicalRoot && !canonical.path.startsWith(canonicalRoot.path + File.separator)) {
                throw SecurityException("Restore transaction escaped configuration directory")
            }
            unique[canonical.path] = Mutation(canonical, mutation.replacement)
        }

        val transactionDir = File(canonicalRoot, ".restore-txn-${UUID.randomUUID()}")
        SecureFile.mkdirs(transactionDir, 448)
        val originals = ArrayList<Original>(unique.size)
        try {
            unique.values.forEachIndexed { index, mutation ->
                val path = mutation.target.toPath()
                val existed = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                if (existed && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw SecurityException("Refusing non-regular restore transaction target")
                }
                val backup =
                    if (existed) {
                        val copy = File(transactionDir, index.toString().padStart(4, '0') + ".bak")
                        Files.copy(
                            path,
                            copy.toPath(),
                            LinkOption.NOFOLLOW_LINKS,
                            StandardCopyOption.COPY_ATTRIBUTES,
                        )
                        if (!Files.isRegularFile(copy.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            throw SecurityException("Restore transaction source changed while snapshotting")
                        }
                        copy
                    } else {
                        null
                    }
                originals += Original(mutation.target, existed, backup)
            }

            try {
                unique.values.forEachIndexed { index, mutation ->
                    beforeMutation?.invoke(index, mutation.target)
                    val replacement = mutation.replacement
                    if (replacement == null) {
                        Files.deleteIfExists(mutation.target.toPath())
                    } else {
                        mutation.target.parentFile?.let { parent ->
                            if (parent != canonicalRoot && !Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                                SecureFile.mkdirs(parent, 448)
                            }
                        }
                        SecureFile.writeBytes(mutation.target, replacement)
                    }
                }
            } catch (failure: Throwable) {
                var rollbackFailure: Throwable? = null
                originals.asReversed().forEach { original ->
                    try {
                        if (original.existed) {
                            val backup = requireNotNull(original.backup)
                            original.target.parentFile?.let { parent ->
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
                        val existingFailure = rollbackFailure
                        if (existingFailure == null) {
                            rollbackFailure = error
                        } else {
                            existingFailure.addSuppressed(error)
                        }
                    }
                }
                rollbackFailure?.let { failure.addSuppressed(it) }
                throw failure
            }
        } finally {
            originals.forEach { original ->
                runCatching { original.backup?.let { Files.deleteIfExists(it.toPath()) } }
            }
            runCatching { Files.deleteIfExists(transactionDir.toPath()) }
        }
    }
}
