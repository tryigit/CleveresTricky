package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.RestoreFiles
import cleveres.tricky.cleverestech.util.SecureFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
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
 * Applies a validated restore set with rollback. Original state is owned by a descriptor-bound
 * backend before the first mutation so rollback cannot be redirected by ancestor path swaps.
 */
internal object BackupRestoreTransaction {
    data class Mutation(
        val target: File,
        val replacement: ByteArray?,
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
            val relative = rootPath.relativize(normalized)
            val allowed =
                relative.nameCount == 1 ||
                    (relative.nameCount == 2 && relative.getName(0).toString() == KEYBOX_DIRECTORY)
            if (!allowed || relative.any { component -> !isSafeComponent(component.toString()) }) {
                throw SecurityException("Restore target is outside an allowed capability subtree")
            }
            unique[normalized.toString()] = Mutation(normalized.toFile(), mutation.replacement)
        }

        val needsKeyboxDirectory =
            unique.values.any { mutation ->
                if (mutation.replacement == null) return@any false
                val relative = rootPath.relativize(mutation.target.toPath())
                relative.nameCount == 2 && relative.getName(0).toString() == KEYBOX_DIRECTORY
            }
        if (needsKeyboxDirectory) {
            // Creation happens before the transaction boundary. The backend then pins this exact
            // directory handle before snapshotting, so later pathname replacement cannot redirect
            // restore writes or rollback.
            SecureFile.mkdirs(configDir.resolve(KEYBOX_DIRECTORY), DIRECTORY_MODE)
        }

        val restoreFiles = RestoreFiles.current()
        val token = UUID.randomUUID().toString().replace("-", "")
        var transactionActive = false
        var retainSecureRecoveryState = false
        try {
            restoreFiles.begin(configDir, token, maxSnapshotBytes)
            transactionActive = true
            try {
                unique.values.forEach { mutation ->
                    restoreFiles.snapshot(configDir, token, mutation.target)
                }
            } catch (snapshotFailure: Throwable) {
                try {
                    restoreFiles.abort(configDir, token)
                    transactionActive = false
                } catch (abortFailure: Throwable) {
                    snapshotFailure.addSuppressed(abortFailure)
                }
                throw SecurityException("Refusing unsafe, unreadable, or oversized restore snapshot", snapshotFailure)
            }

            var afterMutationInvoked = false
            try {
                unique.values.forEachIndexed { index, mutation ->
                    beforeMutation?.invoke(index, mutation.target)
                    val replacement = mutation.replacement
                    if (replacement == null) {
                        restoreFiles.delete(configDir, token, mutation.target)
                    } else {
                        restoreFiles.replace(configDir, token, mutation.target, replacement)
                    }
                }
                if (afterMutation != null) {
                    afterMutationInvoked = true
                    afterMutation.invoke()
                }
                restoreFiles.commit(configDir, token)
                transactionActive = false
            } catch (failure: Throwable) {
                var rollbackFailure: Throwable? = null
                try {
                    restoreFiles.rollback(configDir, token)
                    transactionActive = false
                } catch (error: Throwable) {
                    rollbackFailure = error
                    try {
                        val recoveryLocation = restoreFiles.exportRecovery(configDir, token)
                        transactionActive = false
                        failure.addSuppressed(
                            IOException("Rollback recovery data retained at $recoveryLocation"),
                        )
                    } catch (exportFailure: Throwable) {
                        error.addSuppressed(exportFailure)
                        retainSecureRecoveryState = true
                        failure.addSuppressed(
                            IOException("Rollback recovery remains retained by the secure transaction backend"),
                        )
                    }
                }

                if (afterMutationInvoked && onRollback != null && rollbackFailure == null) {
                    try {
                        onRollback.invoke()
                    } catch (error: Throwable) {
                        rollbackFailure = error
                    }
                }
                rollbackFailure?.let { failure.addSuppressed(it) }
                throw failure
            }
        } finally {
            if (transactionActive && !retainSecureRecoveryState) {
                runCatching { restoreFiles.abort(configDir, token) }
            }
        }
    }

    private fun isSafeComponent(value: String): Boolean =
        value.isNotEmpty() && value != "." && value != ".." && '/' !in value && '\u0000' !in value

    private const val KEYBOX_DIRECTORY = "keyboxes"
    private const val DIRECTORY_MODE = 448
}
