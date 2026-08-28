package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.DeviceKeyManager
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.readFileSnapshotBounded
import cleveres.tricky.cleverestech.util.sha256FileSnapshotBounded
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap

object CboxManager {

    private data class CboxFile(
        val file: File,
        val lastModified: Long,
        val length: Long,
    )

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

    fun refresh() =
        KeyboxActivation.coordinateRefresh {
            refreshLocked()
        }

    private fun refreshLocked() {
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
        val currentFiles = files.mapTo(HashSet()) { it.file.name }
        val crl = if (files.isEmpty()) null else KeyboxVerifier.fetchCrl()
        var retainedKeyboxCount = 0

        for (cbox in files) {
            val file = cbox.file
            val name = file.name
            val current = unlockedCache[name]
            val metadataMatches =
                current != null &&
                    current.sourceLastModified == cbox.lastModified &&
                    current.sourceSize == cbox.length
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
                if (retainedKeyboxCount > KeyboxLoader.MAX_ACTIVE_KEYS - current.keyboxes.size) {
                    unlockedCache.remove(name)
                    lockedFiles.add(name)
                    Logger.w("CBOX keybox cache exceeds the active keybox limit; keeping $name locked")
                    continue
                }
                retainedKeyboxCount += current.keyboxes.size
                lockedFiles.remove(name)
                continue
            }

            unlockedCache.remove(name)
            if (crl != null) {
                val loaded = loadCached(file, crl)
                if (loaded != null) {
                    if (retainedKeyboxCount > KeyboxLoader.MAX_ACTIVE_KEYS - loaded.keyboxes.size) {
                        lockedFiles.add(name)
                        Logger.w("CBOX keybox cache exceeds the active keybox limit; keeping $name locked")
                        continue
                    }
                    unlockedCache[name] = loaded
                    retainedKeyboxCount += loaded.keyboxes.size
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
    internal fun invalidateBackendHandles() {
        val names = unlockedCache.keys.toList()
        unlockedCache.clear()
        lockedFiles.addAll(names)
    }

    fun unlock(
        filename: String,
        password: String,
        publicKey: String?,
    ): Boolean =
        synchronized(ManagedFileCoordinator.monitor) {
            KeyboxActivation.coordinateRefresh {
                unlockLocked(filename, password, publicKey)
            }
        }

    private fun unlockLocked(
        filename: String,
        password: String,
        publicKey: String?,
    ): Boolean {
        if (!validFilename.matches(filename) || !isUnlockPasswordWithinLimit(password)) return false
        val verificationKey = publicKey?.takeUnless { it.isBlank() }
        if (!FusedCboxBackend.isPublicKeyWithinLimit(verificationKey)) return false
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

            if (totalCachedKeyboxes(excluding = filename) > KeyboxLoader.MAX_ACTIVE_KEYS - verified.size) {
                Logger.w("CBOX keybox cache would exceed the active keybox limit; keeping $filename locked")
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

    private fun totalCachedKeyboxes(excluding: String? = null): Int =
        unlockedCache.entries
            .asSequence()
            .filter { excluding == null || it.key != excluding }
            .sumOf { it.value.keyboxes.size }

    fun getUnlockedKeyboxes(): List<CertHack.KeyBox> =
        unlockedCache.entries.sortedBy { it.key }.flatMap { it.value.keyboxes }

    fun getLockedFiles(): Set<String> = lockedFiles.toSortedSet()

    fun isLocked(filename: String): Boolean = lockedFiles.contains(filename)

    /** New producers require a strong password, but decryption keeps legacy empty-password files readable. */
    internal fun isUnlockPasswordWithinLimit(password: String): Boolean = password.length <= MAX_PASSWORD_CHARS

    @Throws(IOException::class)
    @androidx.annotation.VisibleForTesting
    internal fun listCboxFilesForTesting(directory: File): List<String> =
        listCboxFiles(directory).map { it.file.name }

    private fun listCboxFiles(directory: File): List<CboxFile> {
        val files = PriorityQueue<CboxFile>(MAX_CBOX_FILES, compareByDescending { it.file.name })
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            var scanned = 0
            for (path in entries) {
                if (++scanned > MAX_SCANNED_ENTRIES_PER_DIRECTORY) {
                    throw IOException("CBOX directory contains too many entries")
                }
                val file = path.toFile()
                if (!validFilename.matches(file.name) || !isSafeCbox(file)) continue
                val cboxFile = CboxFile(file, file.lastModified(), file.length())

                if (files.size < MAX_CBOX_FILES) {
                    files.add(cboxFile)
                } else if (file.name < requireNotNull(files.peek()).file.name) {
                    files.poll()
                    files.add(cboxFile)
                }
            }
        }
        return files.sortedBy { it.file.name }
    }

    private fun loadCached(
        file: File,
        crl: CrlWire.Handle,
    ): UnlockedEntry? {
        val cacheFile = cacheFileFor(file)
        if (!Files.isRegularFile(cacheFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return null

        var encrypted: ByteArray? = null
        var decrypted: ByteArray? = null
        var credentials: CachedCredentials? = null
        var sourceDigest: ByteArray? = null
        var encryptedCbox: ByteArray? = null
        var verificationDigest: ByteArray? = null
        return try {
            val encryptedSnapshot = readFileSnapshotBounded(cacheFile, 1, MAX_CACHE_BYTES)
            encrypted = encryptedSnapshot
            decrypted = DeviceKeyManager.decrypt(encryptedSnapshot) ?: return null
            credentials = decodeCredentialCache(decrypted) ?: throw SecurityException("Unsupported CBOX cache format")

            val cboxSnapshot = readCboxBounded(file)
            encryptedCbox = cboxSnapshot
            sourceDigest = MessageDigest.getInstance("SHA-256").digest(cboxSnapshot)
            if (!MessageDigest.isEqual(credentials.sourceDigest, sourceDigest)) {
                throw SecurityException("CBOX cache does not match its source")
            }

            val payload = FusedCboxBackend.recover(cboxSnapshot, credentials.recoveryKey, credentials.publicKey)
                ?: return null
            if (credentials.publicKey == null && payload.hasSignature) return null
            val parsed = KeyboxJcaAdapter.materialize(payload.document, file.name)
            val verified = parsed.filter { KeyboxVerifier.verifyKeybox(it, crl) == KeyboxVerifier.Status.VALID }
            if (verified.isEmpty() || verified.size != parsed.size) return null

            val beforeModified = file.lastModified()
            val beforeSize = file.length()
            verificationDigest = digestFile(file)
            val afterModified = file.lastModified()
            val afterSize = file.length()
            if (beforeModified != afterModified ||
                beforeSize != afterSize ||
                !MessageDigest.isEqual(sourceDigest, verificationDigest)
            ) {
                throw SecurityException("CBOX source changed while its recovery cache was loaded")
            }

            UnlockedEntry(afterModified, afterSize, sourceDigest.copyOf(), verified.toList())
        } catch (error: RustBackendUnavailableException) {
            Logger.w("Rust backend unavailable; preserving CBOX recovery cache ${cacheFile.name}")
            throw error
        } catch (error: Exception) {
            Logger.e("Ignoring invalid CBOX recovery cache for ${file.name}: ${error.javaClass.simpleName}")
            deleteCacheSafely(cacheFile)
            null
        } finally {
            credentials?.wipe()
            encrypted?.fill(0)
            decrypted?.fill(0)
            sourceDigest?.fill(0)
            encryptedCbox?.fill(0)
            verificationDigest?.fill(0)
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
    private fun readCboxBounded(file: File): ByteArray =
        readFileSnapshotBounded(file, MIN_CBOX_BYTES, MAX_CBOX_BYTES)

    @Throws(IOException::class)
    private fun digestFile(file: File): ByteArray =
        sha256FileSnapshotBounded(file, MIN_CBOX_BYTES, MAX_CBOX_BYTES)

    private fun isSafeCbox(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            file.length() in MIN_CBOX_BYTES..MAX_CBOX_BYTES

    private fun cacheFileFor(file: File) = File(file.parentFile, "${file.name}.cache")

    private fun cleanupOrphanedCaches(directory: File, currentFiles: Set<String>) {
        try {
            Files.newDirectoryStream(directory.toPath()) { path ->
                path.fileName.toString().endsWith(".cbox.cache", ignoreCase = true)
            }.use { entries ->
                var scanned = 0
                for (path in entries) {
                    if (++scanned > MAX_SCANNED_ENTRIES_PER_DIRECTORY) {
                        throw IOException("CBOX cache directory contains too many entries")
                    }
                    val cache = path.toFile()
                    val sourceName = cache.name.removeSuffix(".cache")
                    if (sourceName !in currentFiles) deleteCacheSafely(cache)
                }
            }
        } catch (error: IOException) {
            Logger.w("Could not scan orphaned CBOX caches: ${error.message}")
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
    private val MIN_CBOX_BYTES = CboxWireLimits.MIN_BYTES.toLong()
    private val MAX_CBOX_BYTES = CboxWireLimits.MAX_BYTES.toLong()
    private const val MAX_CACHE_BYTES = 64L * 1024
    private const val MAX_CBOX_FILES = 64
    internal const val MAX_SCANNED_ENTRIES_PER_DIRECTORY = 4_096
    private const val MAX_PASSWORD_CHARS = 1024
    private const val MAX_PUBLIC_KEY_BYTES = 16 * 1024
    private val CACHE_MAGIC = byteArrayOf('C'.code.toByte(), 'T'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), '3'.code.toByte())
    private val CACHE_PREFIX_BYTES = CACHE_MAGIC.size + SHA256_BYTES + RECOVERY_KEY_BYTES + 2
    private val EMPTY_BYTES = ByteArray(0)
}
