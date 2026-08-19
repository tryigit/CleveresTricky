package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.DeviceKeyManager
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import cleveres.tricky.cleverestech.util.SecureFile
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap

object CboxManager {
    private data class UnlockedEntry(
        val sourceLastModified: Long,
        val sourceSize: Long,
        val sourceDigest: ByteArray,
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
        if (KeyboxLoader.consumeBackendOutage()) {
            invalidateBackendHandles()
        }

        val directory = Config.keyboxDirectory
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            unlockedCache.clear()
            lockedFiles.clear()
            return
        }

        val files =
            try {
                listCboxFiles(directory)
            } catch (error: IOException) {
                unlockedCache.clear()
                lockedFiles.clear()
                Logger.e("Failed to scan CBOX directory", error)
                return
            }
        val currentFiles = files.mapTo(HashSet()) { it.name }
        val crl = if (files.isEmpty()) null else KeyboxVerifier.fetchCrl()

        for (file in files) {
            val name = file.name
            val current = unlockedCache[name]
            val metadataMatches =
                current != null &&
                    current.sourceLastModified == file.lastModified() &&
                    current.sourceSize == file.length()
            val digestMatches =
                if (metadataMatches) {
                    val digest = runCatching { digestFile(file) }.getOrNull()
                    try {
                        digest != null && MessageDigest.isEqual(requireNotNull(current).sourceDigest, digest)
                    } finally {
                        digest?.fill(0)
                    }
                } else {
                    false
                }
            if (crl != null &&
                current != null &&
                digestMatches &&
                current.keyboxes.all {
                    KeyboxVerifier.verifyKeybox(it, crl) == KeyboxVerifier.Status.VALID
                }
            ) {
                lockedFiles.remove(name)
                continue
            }

            unlockedCache.remove(name)
            if (crl != null) {
                val loaded = loadCached(file, crl)
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
        if (crl == null && files.isNotEmpty()) {
            Logger.w("CBOX keyboxes remain locked because the revocation list is unavailable")
        }
    }

    /** Drops only managed opaque-handle views. Encrypted recovery caches remain for re-registration. */
    @Synchronized
    internal fun invalidateBackendHandles() {
        val names = unlockedCache.keys.toList()
        unlockedCache.clear()
        lockedFiles.addAll(names)
    }

    @Synchronized
    fun unlock(
        filename: String,
        password: String,
        publicKey: String?,
    ): Boolean {
        if (!validFilename.matches(filename) || password.length !in 1..MAX_PASSWORD_CHARS) return false
        val verificationKey = publicKey?.takeUnless { it.isBlank() }
        if ((verificationKey?.length ?: 0) > MAX_PUBLIC_KEY_CHARS) return false
        val file = File(Config.keyboxDirectory, filename)
        if (!isSafeCbox(file)) return false

        var encryptedBytes: ByteArray? = null
        var sourceDigest: ByteArray? = null
        var verificationDigest: ByteArray? = null
        var unlockPayload: FusedCboxBackend.UnlockPayload? = null
        return try {
            encryptedBytes = readCboxBounded(file)
            sourceDigest = MessageDigest.getInstance("SHA-256").digest(encryptedBytes)
            unlockPayload =
                FusedCboxBackend.unlockForRecovery(encryptedBytes, password, verificationKey)
                    ?: run {
                        Logger.e("CBOX decrypt, signature, or keybox validation failed for $filename")
                        return false
                    }
            val payload = unlockPayload.payload
            if (verificationKey == null && payload.hasSignature) {
                Logger.e("Signed CBOX requires an explicit verification key: $filename")
                return false
            }

            val parsed = KeyboxJcaAdapter.materialize(payload.document, filename)
            val crl = KeyboxVerifier.fetchCrl() ?: return false
            val verified = parsed.filter { KeyboxVerifier.verifyKeybox(it, crl) == KeyboxVerifier.Status.VALID }
            if (verified.isEmpty() || verified.size != parsed.size) {
                Logger.e("CBOX contains an invalid or revoked keybox: $filename")
                return false
            }

            val beforeModified = file.lastModified()
            val beforeSize = file.length()
            verificationDigest = digestFile(file)
            val afterModified = file.lastModified()
            val afterSize = file.length()
            if (beforeModified != afterModified ||
                beforeSize != afterSize ||
                !MessageDigest.isEqual(sourceDigest, verificationDigest)
            ) {
                Logger.e("CBOX source changed while it was being unlocked: $filename")
                return false
            }

            writeCredentialCache(file, unlockPayload.recoveryKey, verificationKey, sourceDigest)
            unlockedCache[filename] =
                UnlockedEntry(afterModified, afterSize, sourceDigest.copyOf(), verified.toList())
            lockedFiles.remove(filename)
            true
        } catch (error: Exception) {
            Logger.e("Failed to unlock CBOX: $filename", error)
            false
        } finally {
            unlockPayload?.wipeRecoveryKey()
            encryptedBytes?.fill(0)
            sourceDigest?.fill(0)
            verificationDigest?.fill(0)
        }
    }

    fun getUnlockedKeyboxes(): List<CertHack.KeyBox> =
        unlockedCache.entries.sortedBy { it.key }.flatMap { it.value.keyboxes }

    fun getLockedFiles(): Set<String> = lockedFiles.toSortedSet()

    fun isLocked(filename: String): Boolean = lockedFiles.contains(filename)

    @Throws(IOException::class)
    private fun listCboxFiles(directory: File): List<File> {
        val files = PriorityQueue<File>(MAX_CBOX_FILES, compareByDescending { it.name })
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (path in entries) {
                val file = path.toFile()
                if (!validFilename.matches(file.name) || !isSafeCbox(file)) continue
                if (files.size < MAX_CBOX_FILES) {
                    files.add(file)
                } else if (file.name < requireNotNull(files.peek()).name) {
                    files.poll()
                    files.add(file)
                }
            }
        }
        return files.sortedBy { it.name }
    }

    private fun loadCached(
        file: File,
        crl: CrlWire.Handle,
    ): UnlockedEntry? {
        val cacheFile = cacheFileFor(file)
        if (!Files.isRegularFile(cacheFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            cacheFile.length() !in 1..MAX_CACHE_BYTES
        ) {
            return null
        }

        val encrypted = cacheFile.readBytes()
        var decrypted: ByteArray? = null
        var credentials: CachedCredentials? = null
        var sourceDigest: ByteArray? = null
        var encryptedCbox: ByteArray? = null
        return try {
            decrypted = DeviceKeyManager.decrypt(encrypted) ?: return null
            credentials = decodeCredentialCache(decrypted) ?: throw SecurityException("Unsupported CBOX cache format")
            sourceDigest = digestFile(file)
            if (!MessageDigest.isEqual(credentials.sourceDigest, sourceDigest)) {
                throw SecurityException("CBOX cache does not match its source")
            }

            encryptedCbox = readCboxBounded(file)
            val payload = FusedCboxBackend.recover(encryptedCbox, credentials.recoveryKey, credentials.publicKey)
                ?: return null
            if (credentials.publicKey == null && payload.hasSignature) return null
            val parsed = KeyboxJcaAdapter.materialize(payload.document, file.name)
            val verified = parsed.filter { KeyboxVerifier.verifyKeybox(it, crl) == KeyboxVerifier.Status.VALID }
            if (verified.isEmpty() || verified.size != parsed.size) return null
            UnlockedEntry(file.lastModified(), file.length(), sourceDigest.copyOf(), verified.toList())
        } catch (error: RustBackendUnavailableException) {
            Logger.w("Rust backend unavailable; preserving CBOX recovery cache ${cacheFile.name}")
            throw error
        } catch (error: Exception) {
            Logger.e("Ignoring invalid CBOX recovery cache for ${file.name}: ${error.javaClass.simpleName}")
            deleteCacheSafely(cacheFile)
            null
        } finally {
            credentials?.wipe()
            encrypted.fill(0)
            decrypted?.fill(0)
            sourceDigest?.fill(0)
            encryptedCbox?.fill(0)
        }
    }

    private data class CachedCredentials(
        val sourceDigest: ByteArray,
        val recoveryKey: ByteArray,
        val publicKey: String?,
    ) {
        fun wipe() {
            sourceDigest.fill(0)
            recoveryKey.fill(0)
        }
    }

    private fun decodeCredentialCache(bytes: ByteArray): CachedCredentials? {
        if (bytes.size < CACHE_PREFIX_BYTES || !bytes.copyOfRange(0, CACHE_MAGIC.size).contentEquals(CACHE_MAGIC)) {
            return null
        }
        val digestStart = CACHE_MAGIC.size
        val digestEnd = digestStart + SHA256_BYTES
        val recoveryStart = digestEnd
        val recoveryEnd = recoveryStart + RECOVERY_KEY_BYTES
        val publicKeyLength = readU16(bytes, recoveryEnd)
        if (publicKeyLength !in 0..MAX_PUBLIC_KEY_BYTES) return null
        val publicKeyStart = CACHE_PREFIX_BYTES
        val publicKeyEnd = Math.addExact(publicKeyStart, publicKeyLength)
        if (publicKeyEnd != bytes.size) return null
        val recoveryKey = bytes.copyOfRange(recoveryStart, recoveryEnd)
        if (recoveryKey.all { it == 0.toByte() }) {
            recoveryKey.fill(0)
            return null
        }
        val publicKey =
            try {
                if (publicKeyLength == 0) null else decodeUtf8Strict(bytes, publicKeyStart, publicKeyLength)
            } catch (error: Throwable) {
                recoveryKey.fill(0)
                throw error
            }
        return CachedCredentials(bytes.copyOfRange(digestStart, digestEnd), recoveryKey, publicKey)
    }

    private fun writeCredentialCache(
        file: File,
        recoveryKey: ByteArray,
        publicKey: String?,
        sourceDigest: ByteArray,
    ) {
        require(sourceDigest.size == SHA256_BYTES)
        require(recoveryKey.size == RECOVERY_KEY_BYTES && recoveryKey.any { it != 0.toByte() })
        val publicKeyBytes = publicKey?.toByteArray(StandardCharsets.UTF_8) ?: EMPTY_BYTES
        var encrypted: ByteArray? = null
        try {
            require(publicKeyBytes.size <= MAX_PUBLIC_KEY_BYTES)
            val plaintext = ByteArray(CACHE_PREFIX_BYTES + publicKeyBytes.size)
            try {
                CACHE_MAGIC.copyInto(plaintext, 0)
                sourceDigest.copyInto(plaintext, CACHE_MAGIC.size)
                val recoveryOffset = CACHE_MAGIC.size + SHA256_BYTES
                recoveryKey.copyInto(plaintext, recoveryOffset)
                writeU16(plaintext, recoveryOffset + RECOVERY_KEY_BYTES, publicKeyBytes.size)
                publicKeyBytes.copyInto(plaintext, CACHE_PREFIX_BYTES)
                encrypted =
                    DeviceKeyManager.encrypt(plaintext)
                        ?: throw IllegalStateException("Device cache encryption is unavailable")
            } finally {
                plaintext.fill(0)
            }
            SecureFile.writeBytes(cacheFileFor(file), requireNotNull(encrypted))
        } finally {
            if (publicKeyBytes !== EMPTY_BYTES) publicKeyBytes.fill(0)
            encrypted?.fill(0)
        }
    }

    private fun decodeUtf8Strict(bytes: ByteArray, offset: Int, length: Int): String =
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()
        } catch (error: CharacterCodingException) {
            throw SecurityException("Invalid UTF-8 in CBOX recovery cache", error)
        }

    @Throws(IOException::class)
    private fun readCboxBounded(file: File): ByteArray {
        Files.newByteChannel(
            file.toPath(),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val size = channel.size()
            if (size !in MIN_CBOX_BYTES..MAX_CBOX_BYTES) {
                throw IOException("CBOX size is outside the supported range")
            }
            val bytes = ByteArray(size.toInt())
            return try {
                val target = ByteBuffer.wrap(bytes)
                var emptyReads = 0
                while (target.hasRemaining()) {
                    val count = channel.read(target)
                    if (count < 0) throw IOException("CBOX ended before its declared size")
                    if (count == 0) {
                        if (++emptyReads > MAX_EMPTY_READS) throw IOException("CBOX read stalled")
                    } else {
                        emptyReads = 0
                    }
                }
                if (channel.size() != size) throw IOException("CBOX size changed while being read")
                bytes
            } catch (error: Throwable) {
                bytes.fill(0)
                throw error
            }
        }
    }

    private fun digestFile(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
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

    private fun cleanupOrphanedCaches(directory: File, currentFiles: Set<String>) {
        try {
            Files.newDirectoryStream(directory.toPath()) { path ->
                path.fileName.toString().endsWith(".cbox.cache", ignoreCase = true)
            }.use { entries ->
                for (path in entries) {
                    val cache = path.toFile()
                    val sourceName = cache.name.removeSuffix(".cache")
                    if (sourceName !in currentFiles) deleteCacheSafely(cache)
                }
            }
        } catch (_: IOException) {
            Logger.w("Could not scan orphaned CBOX caches")
        }
    }

    private fun deleteCacheSafely(file: File) {
        try {
            if (!Files.isSymbolicLink(file.toPath())) Files.deleteIfExists(file.toPath())
        } catch (_: Exception) {
            Logger.w("Could not remove CBOX cache ${file.name}")
        }
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        require(value in 0..0xffff)
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private const val SHA256_BYTES = 32
    private const val RECOVERY_KEY_BYTES = 32
    private const val MIN_CBOX_BYTES = 4L + 4L + 16L + 12L + 16L
    private const val MAX_CBOX_BYTES = 10L * 1024 * 1024 + 36L
    private const val MAX_CACHE_BYTES = 64L * 1024
    private const val MAX_CBOX_FILES = 64
    private const val MAX_PASSWORD_CHARS = 1024
    private const val MAX_PUBLIC_KEY_CHARS = 16 * 1024
    private const val MAX_PUBLIC_KEY_BYTES = 16 * 1024
    private const val MAX_EMPTY_READS = 16
    private val CACHE_MAGIC = byteArrayOf('C'.code.toByte(), 'T'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), '3'.code.toByte())
    private val CACHE_PREFIX_BYTES = CACHE_MAGIC.size + SHA256_BYTES + RECOVERY_KEY_BYTES + 2
    private val EMPTY_BYTES = ByteArray(0)
}
