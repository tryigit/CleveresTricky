package cleveres.tricky.encryptor

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.Locale

internal object VaultStore {
    private const val VAULT_DIR = "vault"
    private const val MAX_FILES = 256
    private const val MAX_CBOX_BYTES = 10 * 1024 * 1024 + 36
    private const val MAX_FILENAME_CHARS = 128
    private const val CBOX_SUFFIX = ".cbox"
    private val unsafeFilenameChars = Regex("[^a-zA-Z0-9._-]")

    private data class VaultNameSnapshot(
        val names: MutableSet<String>,
        val entryCount: Int,
    )

    fun directory(context: Context): File {
        check(NativeCrypto.ensureVault(context.noBackupFilesDir.absolutePath)) {
            "Secure vault is unavailable"
        }
        return File(context.noBackupFilesDir, VAULT_DIR)
    }

    fun filenameFor(author: String): String {
        val safe = author.replace(unsafeFilenameChars, "_").trim('.').take(100)
        return "${safe.ifEmpty { "keybox" }}$CBOX_SUFFIX"
    }

    fun allocateBatchFilenames(
        context: Context,
        author: String,
        sourceNames: List<String>,
    ): List<String> {
        require(sourceNames.isNotEmpty()) { "Batch is empty" }
        val snapshot = vaultNameSnapshot(context)
        if (sourceNames.size > MAX_FILES - snapshot.entryCount) {
            throw IOException("Vault capacity exceeded")
        }
        val existing = snapshot.names

        return sourceNames.map { sourceName ->
            val base = batchBaseName(author, sourceName)
            var sequence = 1
            var candidate = "$base$CBOX_SUFFIX"
            while (!existing.add(candidate.lowercase(Locale.ROOT))) {
                sequence++
                val suffix = "_$sequence"
                candidate = "${base.take(MAX_FILENAME_CHARS - CBOX_SUFFIX.length - suffix.length)}$suffix$CBOX_SUFFIX"
            }
            candidate
        }
    }

    internal fun batchBaseName(
        author: String,
        sourceName: String,
    ): String {
        val safeAuthor = sanitizeComponent(author, 48).ifEmpty { "keybox" }
        val basename = sourceName.substringAfterLast('/').substringAfterLast('\\')
        val sourceBase = basename.substringBeforeLast('.', basename)
        val safeSource = sanitizeComponent(sourceBase, 64).ifEmpty { "keybox" }
        return "$safeAuthor-$safeSource".take(MAX_FILENAME_CHARS - CBOX_SUFFIX.length)
    }

    fun exists(
        context: Context,
        filename: String,
    ): Boolean {
        if (!validName(filename)) return false
        val candidate = File(directory(context), filename).toPath()
        return Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
    }

    fun list(context: Context): List<File> {
        val vault = directory(context)
        val files = ArrayList<File>(MAX_FILES)
        Files.newDirectoryStream(vault.toPath()).use { entries ->
            for (entry in entries) {
                if (files.size == MAX_FILES) break
                val name = entry.fileName.toString()
                if (!validName(name)) continue
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue
                val size = Files.size(entry)
                if (size !in 1..MAX_CBOX_BYTES.toLong()) continue
                files += entry.toFile()
            }
        }
        return files.sortedWith(
            compareByDescending<File> { it.lastModified() }
                .thenBy { it.name.lowercase() },
        )
    }

    fun delete(
        context: Context,
        file: File,
    ): Boolean =
        validName(file.name) &&
            NativeCrypto.deleteEncrypted(context.noBackupFilesDir.absolutePath, file.name)

    fun delete(file: File): Boolean {
        val root = noBackupRootFor(file) ?: return false
        return validName(file.name) && NativeCrypto.deleteEncrypted(root.absolutePath, file.name)
    }

    fun export(
        context: Context,
        file: File,
        output: OutputStream,
    ) {
        exportFromRoot(context.noBackupFilesDir, file, output)
    }

    fun export(
        file: File,
        output: OutputStream,
    ) {
        val root = noBackupRootFor(file) ?: throw IOException("Vault entry is invalid")
        exportFromRoot(root, file, output)
    }

    /** Imports legacy app-specific external ciphertext only after bounded native validation. */
    fun migrateLegacy(context: Context) {
        val legacy = context.getExternalFilesDir(null) ?: return
        if (!Files.isDirectory(legacy.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        val privateVault = directory(context)
        var examined = 0

        Files.newDirectoryStream(legacy.toPath()).use { entries ->
            for (entry in entries) {
                if (examined++ == MAX_FILES) break
                val name = entry.fileName.toString()
                if (!validName(name)) continue
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue
                if (Files.exists(File(privateVault, name).toPath(), LinkOption.NOFOLLOW_LINKS)) continue

                var bytes = ByteArray(0)
                try {
                    Files.newInputStream(
                        entry,
                        StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS,
                    ).use { input ->
                        bytes = readBounded(input, MAX_CBOX_BYTES)
                    }
                    if (
                        NativeCrypto.storeEncrypted(
                            context.noBackupFilesDir.absolutePath,
                            name,
                            bytes,
                        )
                    ) {
                        Files.deleteIfExists(entry)
                    }
                } catch (_: Exception) {
                    // Keep the legacy file unless a validated private-vault commit succeeded.
                } finally {
                    bytes.fill(0)
                }
            }
        }
    }

    private fun vaultNameSnapshot(context: Context): VaultNameSnapshot {
        val names = linkedSetOf<String>()
        var entryCount = 0
        Files.newDirectoryStream(directory(context).toPath()).use { entries ->
            for (entry in entries) {
                entryCount++
                if (entryCount > MAX_FILES) throw IOException("Vault capacity exceeded")
                names += entry.fileName.toString().lowercase(Locale.ROOT)
            }
        }
        return VaultNameSnapshot(names = names, entryCount = entryCount)
    }

    private fun exportFromRoot(
        root: File,
        file: File,
        output: OutputStream,
    ) {
        require(validName(file.name)) { "Vault entry is invalid" }
        val encrypted =
            NativeCrypto.readEncrypted(root.absolutePath, file.name)
                ?: throw IOException("Secure vault read failed")
        try {
            require(encrypted.size in 1..MAX_CBOX_BYTES) { "Vault entry exceeds the size limit" }
            output.write(encrypted)
            output.flush()
        } finally {
            encrypted.fill(0)
        }
    }

    private fun noBackupRootFor(file: File): File? {
        val vault = file.parentFile ?: return null
        if (vault.name != VAULT_DIR) return null
        return vault.parentFile
    }

    private fun sanitizeComponent(
        value: String,
        maxLength: Int,
    ): String = value.replace(unsafeFilenameChars, "_").trim('.').take(maxLength)

    private fun validName(name: String): Boolean =
        name.endsWith(CBOX_SUFFIX, ignoreCase = true) &&
            name.length <= MAX_FILENAME_CHARS &&
            !name.startsWith('.') &&
            !name.contains('/') &&
            !name.contains('\u0000')

    private fun readBounded(
        input: java.io.InputStream,
        maxBytes: Int,
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count > maxBytes - total) throw IOException("Vault file exceeds size limit")
                output.write(buffer, 0, count)
                total += count
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }
}
