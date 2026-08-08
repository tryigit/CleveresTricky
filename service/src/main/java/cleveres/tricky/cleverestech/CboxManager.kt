package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.CboxDecryptor
import cleveres.tricky.cleverestech.util.DeviceKeyManager
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import cleveres.tricky.cleverestech.util.SecureFile
import java.io.File
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private fun ByteArray.indexOfFrom(
    value: Byte,
    startIndex: Int = 0,
): Int {
    for (index in startIndex.coerceAtLeast(0) until size) {
        if (this[index] == value) return index
    }
    return -1
}

object CboxManager {
    private data class UnlockedEntry(
        val sourceLastModified: Long,
        val sourceSize: Long,
        val keyboxes: List<CertHack.KeyBox>,
    )

    private val unlockedCache = ConcurrentHashMap<String, UnlockedEntry>()
    private val lockedFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val validFilename =
        Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,122}\\.cbox", RegexOption.IGNORE_CASE)

    fun initialize() {
        refresh()
    }

    @Synchronized
    fun refresh() {
        val directory = Config.keyboxDirectory
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            unlockedCache.clear()
            lockedFiles.clear()
            return
        }

        val files =
            directory.listFiles { file ->
                validFilename.matches(file.name) && isSafeCbox(file)
            }?.sortedBy { it.name }.orEmpty()
        val currentFiles = files.mapTo(HashSet()) { it.name }
        val revoked = if (files.isEmpty()) emptySet() else KeyboxVerifier.fetchCrl()

        for (file in files) {
            val name = file.name
            val current = unlockedCache[name]
            if (revoked != null &&
                current != null &&
                current.sourceLastModified == file.lastModified() &&
                current.sourceSize == file.length() &&
                current.keyboxes.all {
                    KeyboxVerifier.verifyKeybox(it, revoked) == KeyboxVerifier.Status.VALID
                }
            ) {
                lockedFiles.remove(name)
                continue
            }

            unlockedCache.remove(name)
            if (revoked != null) {
                val loaded = loadCached(file, revoked)
                if (loaded != null) {
                    unlockedCache[name] = loaded
                    lockedFiles.remove(name)
                    continue
                }
            }
            lockedFiles.add(name)
        }

        unlockedCache.keys.removeIf { it !in currentFiles }
        lockedFiles.retainAll(currentFiles)
        cleanupOrphanedCaches(directory, currentFiles)
        if (revoked == null && files.isNotEmpty()) {
            Logger.w("CBOX keyboxes remain locked because the revocation list is unavailable")
        }
    }

    @Synchronized
    fun unlock(
        filename: String,
        password: String,
        publicKey: String?,
    ): Boolean {
        if (!validFilename.matches(filename) || password.length !in 1..MAX_PASSWORD_CHARS) return false
        val directory = Config.keyboxDirectory
        val file = File(directory, filename)
        if (!isSafeCbox(file)) return false

        var sourceDigest: ByteArray? = null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val payload =
                Files.newInputStream(file.toPath()).use { raw ->
                    DigestInputStream(raw, digest).use { input -> CboxDecryptor.decrypt(input, password) }
                } ?: return false
            sourceDigest = digest.digest()

            if (payload.signatureBase64.isNotEmpty()) {
                if (publicKey.isNullOrBlank() || !CboxDecryptor.verifySignature(payload, publicKey)) {
                    Logger.e("CBOX signature verification failed for $filename")
                    return false
                }
            } else if (!publicKey.isNullOrBlank()) {
                Logger.e("A verification key was supplied for an unsigned CBOX")
                return false
            }

            val parsed = CertHack.parseKeyboxXml(StringReader(payload.xmlContent), filename)
            val revoked = KeyboxVerifier.fetchCrl() ?: return false
            val verified =
                parsed.filter {
                    KeyboxVerifier.verifyKeybox(it, revoked) == KeyboxVerifier.Status.VALID
                }
            if (verified.isEmpty() || verified.size != parsed.size) {
                Logger.e("CBOX contains an invalid or revoked keybox: $filename")
                return false
            }

            writeCache(file, payload.xmlContent, sourceDigest)
            unlockedCache[filename] =
                UnlockedEntry(file.lastModified(), file.length(), verified.toList())
            lockedFiles.remove(filename)
            true
        } catch (e: Exception) {
            Logger.e("Failed to unlock CBOX: $filename", e)
            false
        } finally {
            sourceDigest?.fill(0)
        }
    }

    fun getUnlockedKeyboxes(): List<CertHack.KeyBox> =
        unlockedCache.entries
            .sortedBy { it.key }
            .flatMap { it.value.keyboxes }

    fun getLockedFiles(): Set<String> = lockedFiles.toSortedSet()

    fun isLocked(filename: String): Boolean = lockedFiles.contains(filename)

    private fun loadCached(
        file: File,
        revoked: Set<String>,
    ): UnlockedEntry? {
        val cacheFile = cacheFileFor(file)
        if (!Files.isRegularFile(cacheFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            cacheFile.length() !in 1..MAX_CACHE_BYTES
        ) {
            return null
        }

        val encrypted = cacheFile.readBytes()
        var decrypted: ByteArray? = null
        var sourceDigest: ByteArray? = null
        var expectedDigestBytes: ByteArray? = null
        try {
            decrypted = DeviceKeyManager.decrypt(encrypted) ?: return null
            val firstNewline = decrypted.indexOfFrom('\n'.code.toByte())
            val secondNewline =
                if (firstNewline >= 0) decrypted.indexOfFrom('\n'.code.toByte(), firstNewline + 1) else -1
            if (firstNewline <= 0 || secondNewline <= firstNewline ||
                String(decrypted, 0, firstNewline, StandardCharsets.US_ASCII) != CACHE_VERSION
            ) {
                throw SecurityException("Unsupported CBOX cache format")
            }

            val expectedDigest =
                String(
                    decrypted,
                    firstNewline + 1,
                    secondNewline - firstNewline - 1,
                    StandardCharsets.US_ASCII,
                )
            sourceDigest = digestFile(file)
            expectedDigestBytes = expectedDigest.toHexBytes()
            if (!MessageDigest.isEqual(expectedDigestBytes, sourceDigest)) {
                throw SecurityException("CBOX cache does not match its source")
            }

            val xml =
                String(
                    decrypted,
                    secondNewline + 1,
                    decrypted.size - secondNewline - 1,
                    StandardCharsets.UTF_8,
                )
            val parsed = CertHack.parseKeyboxXml(StringReader(xml), file.name)
            val verified =
                parsed.filter {
                    KeyboxVerifier.verifyKeybox(it, revoked) == KeyboxVerifier.Status.VALID
                }
            if (verified.isEmpty() || verified.size != parsed.size) return null
            return UnlockedEntry(file.lastModified(), file.length(), verified.toList())
        } catch (e: Exception) {
            Logger.e("Ignoring invalid CBOX cache for ${file.name}: ${e.javaClass.simpleName}")
            deleteCacheSafely(cacheFile)
            return null
        } finally {
            encrypted.fill(0)
            decrypted?.fill(0)
            sourceDigest?.fill(0)
            expectedDigestBytes?.fill(0)
        }
    }

    private fun writeCache(
        file: File,
        xml: String,
        sourceDigest: ByteArray,
    ) {
        val plaintext =
            (CACHE_VERSION + "\n" + sourceDigest.toHexStringLowercase() + "\n" + xml)
                .toByteArray(StandardCharsets.UTF_8)
        var encrypted: ByteArray? = null
        try {
            encrypted = DeviceKeyManager.encrypt(plaintext)
                ?: throw IllegalStateException("Device cache encryption is unavailable")
            SecureFile.writeBytes(cacheFileFor(file), encrypted)
        } finally {
            plaintext.fill(0)
            encrypted?.fill(0)
        }
    }

    private fun digestFile(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath()).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            } finally {
                buffer.fill(0)
            }
        }
        return digest.digest()
    }

    private fun isSafeCbox(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            file.length() in MIN_CBOX_BYTES..MAX_CBOX_BYTES

    private fun cacheFileFor(file: File) = File(file.parentFile, "${file.name}.cache")

    private fun cleanupOrphanedCaches(
        directory: File,
        currentFiles: Set<String>,
    ) {
        directory
            .listFiles { file -> file.name.endsWith(".cbox.cache", ignoreCase = true) }
            ?.forEach { cache ->
                val sourceName = cache.name.removeSuffix(".cache")
                if (sourceName !in currentFiles) deleteCacheSafely(cache)
            }
    }

    private fun deleteCacheSafely(file: File) {
        try {
            Files.deleteIfExists(file.toPath())
        } catch (e: Exception) {
            Logger.w("Could not remove CBOX cache ${file.name}")
        }
    }

    private fun ByteArray.toHexStringLowercase(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun String.toHexBytes(): ByteArray {
        if (length != SHA256_HEX_CHARS) throw SecurityException("Invalid CBOX digest length")
        val result = ByteArray(SHA256_HEX_CHARS / 2)
        for (index in result.indices) {
            val high =
                this[index * 2].digitToIntOrNull(16)
                    ?: throw SecurityException("Invalid CBOX digest")
            val low =
                this[index * 2 + 1].digitToIntOrNull(16)
                    ?: throw SecurityException("Invalid CBOX digest")
            result[index] = ((high shl 4) or low).toByte()
        }
        return result
    }

    private const val CACHE_VERSION = "CTCB1"
    private const val SHA256_HEX_CHARS = 64
    private const val MIN_CBOX_BYTES = 4L + 4L + 16L + 12L + 16L
    private const val MAX_CBOX_BYTES = 10L * 1024 * 1024 + 36L
    private const val MAX_CACHE_BYTES = 16L * 1024 * 1024
    private const val MAX_PASSWORD_CHARS = 1024
}
