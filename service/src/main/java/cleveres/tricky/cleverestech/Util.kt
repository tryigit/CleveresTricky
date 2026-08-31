@file:OptIn(ExperimentalStdlibApi::class)

package cleveres.tricky.cleverestech

import android.content.pm.IPackageManager
import android.os.Build
import android.os.SystemProperties
import cleveres.tricky.cleverestech.util.SecureFile
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate

internal const val BOOT_IMAGE_SECURITY_PATCH_PROPERTY = "ro.bootimage.build.version.security_patch"
internal const val BOOT_IMAGE_SECURITY_PATCH_ALIAS = "ro.bootimage.build.security_patch"

internal fun getSystemPropertyCompat(
    key: String,
    def: String?,
    getter: (String, String?) -> String?,
): String? {
    if (key == BOOT_IMAGE_SECURITY_PATCH_ALIAS) {
        val canonical = getter(BOOT_IMAGE_SECURITY_PATCH_PROPERTY, "")
        if (!canonical.isNullOrBlank()) return canonical
    }
    return getter(key, def)
}

var systemPropertiesGet: (String, String?) -> String? = { key, def ->
    getSystemPropertyCompat(key, def) { property, fallback -> SystemProperties.get(property, fallback) }
}

fun getModuleDir(): String {
    val candidates =
        listOf(
            "/data/adb/modules/cleverestricky",
            "/data/adb/ksu/modules/cleverestricky",
            "/data/adb/ap/modules/cleverestricky",
        )
    return candidates.firstOrNull { java.io.File(it).exists() } ?: "/data/adb/modules/cleverestricky"
}

fun getTransactCode(
    clazz: Class<*>,
    method: String,
): Int {
    try {
        return clazz.getDeclaredField("TRANSACTION_$method").apply { isAccessible = true }.getInt(null)
    } catch (e: Exception) {
        try {
            val getDefaultTransactionName = clazz.getDeclaredMethod("getDefaultTransactionName", Int::class.javaPrimitiveType)
            for (i in 1..255) {
                val name = getDefaultTransactionName.invoke(null, i) as? String
                if (name == method) return i
            }
        } catch (ignored: Exception) {
        }
        Logger.e("Failed to find transaction code for $method in ${clazz.name}", e)
        return -1
    }
}

fun validTransactCodes(vararg codes: Int): IntArray = codes.filter { it > 0 }.distinct().toIntArray()

@OptIn(ExperimentalStdlibApi::class)
val bootHash by lazy {
    getBootHashFromProp()
}

@OptIn(ExperimentalStdlibApi::class)
val bootKey by lazy {
    getBootKeyFromProp() ?: getVerifiedBootKeyDigest()
}

val persistentBootKey by lazy {
    BootIdentityStore.bootKey()
}

val persistentBootHash by lazy {
    BootIdentityStore.bootHash()
}

private fun getVerifiedBootKeyDigest(): ByteArray? {
    val slot = systemPropertiesGet("ro.boot.slot_suffix", "") ?: ""
    val paths = mutableListOf<String>()
    if (slot.isNotEmpty()) {
        paths.add("/dev/block/by-name/vbmeta$slot")
    }
    paths.add("/dev/block/by-name/vbmeta")

    for (path in paths) {
        val key = VbMetaParser.extractPublicKey(path) ?: continue
        try {
            return MessageDigest.getInstance("SHA-256").digest(key)
        } finally {
            key.fill(0)
        }
    }
    return null
}

@OptIn(ExperimentalStdlibApi::class)
private fun decodeBootDigest(value: String?): ByteArray? {
    if (value?.length != 64) return null
    val decoded = runCatching { value.hexToByteArray() }.getOrNull() ?: return null
    if (decoded.isUsableBootDigest()) return decoded
    decoded.fill(0)
    return null
}

internal fun ByteArray?.isUsableBootDigest(): Boolean =
    this != null && size == BOOT_DIGEST_BYTES && any { it != 0.toByte() }

internal object BootIdentityStore {
    private const val BOOT_KEY_FILE = "boot_key"
    private const val BOOT_HASH_FILE = "boot_hash"
    private val lock = Any()

    @Volatile
    private var root = File("/data/adb/cleverestricky")

    fun bootKey(): ByteArray? = getOrCreate(BOOT_KEY_FILE)

    fun bootHash(): ByteArray? = getOrCreate(BOOT_HASH_FILE)

    private fun getOrCreate(filename: String): ByteArray? =
        synchronized(lock) {
            val file = File(root, filename)
            readDigest(file)?.let { return@synchronized it }

            val generated = generateDigest() ?: return@synchronized null
            try {
                SecureFile.writeText(file, generated.toHexString())
            } catch (error: Exception) {
                Logger.e("Could not persist $filename; using an in-memory boot identity for this runtime", error)
            }
            generated
        }

    private fun readDigest(file: File): ByteArray? {
        val path = file.toPath()
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        val size = runCatching { Files.size(path) }.getOrNull() ?: return null
        if (size !in 64L..65L) return null

        val encoded = ByteArray(66)
        return try {
            var total = 0
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                while (total < encoded.size) {
                    val count = input.read(encoded, total, encoded.size - total)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                }
            }
            if (total > 65) return null
            decodeBootDigest(encoded.copyOf(total).toString(Charsets.UTF_8).trim())
        } catch (error: Exception) {
            Logger.e("Could not read persisted ${file.name}", error)
            null
        } finally {
            encoded.fill(0)
        }
    }

    private fun generateDigest(): ByteArray? =
        runCatching {
            val random = SecureRandom()
            repeat(4) {
                val generated = ByteArray(BOOT_DIGEST_BYTES)
                random.nextBytes(generated)
                if (generated.isUsableBootDigest()) return@runCatching generated
                generated.fill(0)
            }
            error("SecureRandom repeatedly returned an invalid boot digest")
        }.onFailure { Logger.e("Could not generate a boot identity fallback", it) }
            .getOrNull()

    @androidx.annotation.VisibleForTesting
    fun setRootForTesting(newRoot: File) {
        synchronized(lock) {
            root = newRoot
        }
    }

    @androidx.annotation.VisibleForTesting
    fun resetRootForTesting() {
        setRootForTesting(File("/data/adb/cleverestricky"))
    }
}

@OptIn(ExperimentalStdlibApi::class)
fun getBootKeyFromProp(): ByteArray? {
    val keys = listOf("ro.boot.vbmeta.public_key_digest", "ro.boot.verifiedbootkey")
    for (key in keys) {
        decodeBootDigest(systemPropertiesGet(key, null))?.let { return it }
    }
    return null
}

@OptIn(ExperimentalStdlibApi::class)
fun getBootHashFromProp(): ByteArray? = decodeBootDigest(systemPropertiesGet("ro.boot.vbmeta.digest", null))

private const val BOOT_DIGEST_BYTES = 32

val patchLevel by lazy {
    runCatching { Build.VERSION.SECURITY_PATCH.convertPatchLevel(false) }
        .onFailure { Logger.e("Invalid platform security patch", it) }
        .getOrDefault(0)
}

fun String.convertPatchLevel(long: Boolean): Int {
    val value = trim()
    val compact = value.replace("-", "")
    require(value.length in setOf(6, 7, 8, 10)) { "Unsupported patch-level format" }
    require(compact.length == 6 || compact.length == 8) { "Unsupported patch-level format" }
    require(compact.all(Char::isDigit)) { "Patch level must contain only digits and separators" }
    require(
        '-' !in value ||
            (value.length == 7 && value[4] == '-') ||
            (value.length == 10 && value[4] == '-' && value[7] == '-'),
    ) { "Invalid patch-level separators" }

    val year = compact.substring(0, 4).toInt()
    val month = compact.substring(4, 6).toInt()
    val day = if (compact.length == 8) compact.substring(6, 8).toInt() else 1
    LocalDate.of(year, month, day)
    return if (long) year * 10_000 + month * 100 + day else year * 100 + month
}

fun IPackageManager.getPackageInfoCompat(
    name: String,
    flags: Long,
    userId: Int,
    sdkInt: Int = Build.VERSION.SDK_INT,
) = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
    getPackageInfo(name, flags, userId)
} else {
    getPackageInfo(name, flags.toInt(), userId)
}

// Optimized trimLine: ~2x faster than character-by-character iteration
fun String.trimLine(): String {
    var start = 0
    var end = length - 1
    while (start <= end && this[start].isWhitespace()) start++
    while (end >= start && this[end].isWhitespace()) end--
    if (start > end) return ""

    val sb = StringBuilder(end - start + 1)
    var lineStart = start
    while (lineStart <= end) {
        var lineEnd = indexOf('\n', lineStart)
        if (lineEnd == -1 || lineEnd > end) {
            lineEnd = end + 1
        }

        var s = lineStart
        var e = lineEnd - 1
        while (s <= e && this[s].isWhitespace()) s++
        while (e >= s && this[e].isWhitespace()) e--

        if (sb.isNotEmpty()) sb.append('\n')
        if (s <= e) sb.append(this, s, e + 1)

        lineStart = lineEnd + 1
    }
    return sb.toString()
}

/**
 * Returns the exact byte length of this character sequence when encoded as UTF-8
 * without allocating an intermediate ByteArray.
 */
fun CharSequence.utf8ByteLength(): Int {
    var count = 0
    var i = 0
    val len = length
    while (i < len) {
        val c = this[i].code
        if (c < 0x80) {
            count++
            i++
        } else if (c < 0x800) {
            count += 2
            i++
        } else if (c in 0xD800..0xDBFF) {
            if (i + 1 < len && this[i + 1].code in 0xDC00..0xDFFF) {
                count += 4
                i += 2
            } else {
                count += 3
                i++
            }
        } else {
            count += 3
            i++
        }
    }
    return count
}
