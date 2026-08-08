@file:OptIn(ExperimentalStdlibApi::class)

package cleveres.tricky.cleverestech

import android.content.pm.IPackageManager
import android.os.Build
import android.os.SystemProperties
import java.security.MessageDigest
import java.time.LocalDate

var systemPropertiesGet: (String, String?) -> String? = { key, def -> SystemProperties.get(key, def) }

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
fun getBootKeyFromProp(): ByteArray? {
    val keys = listOf("ro.boot.vbmeta.public_key_digest", "ro.boot.verifiedbootkey")
    for (key in keys) {
        val b = systemPropertiesGet(key, null)
        if (b != null && b.length == 64) {
            val decoded = runCatching { b.hexToByteArray() }.getOrNull()
            if (decoded?.size == 32) return decoded
        }
    }
    return null
}

@OptIn(ExperimentalStdlibApi::class)
fun getBootHashFromProp(): ByteArray? {
    val b = systemPropertiesGet("ro.boot.vbmeta.digest", null) ?: return null
    if (b.length != 64) return null
    return runCatching { b.hexToByteArray() }.getOrNull()?.takeIf { it.size == 32 }
}

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
) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
