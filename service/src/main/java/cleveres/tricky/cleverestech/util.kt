@file:OptIn(ExperimentalStdlibApi::class)

package cleveres.tricky.cleverestech

import android.content.pm.IPackageManager
import android.os.Build
import android.os.SystemProperties
import java.util.concurrent.ThreadLocalRandom

fun getTransactCode(clazz: Class<*>, method: String) =
    clazz.getDeclaredField("TRANSACTION_$method").apply { isAccessible = true }
        .getInt(null) // 2

@OptIn(ExperimentalStdlibApi::class)
val bootHash by lazy {
    getBootHashFromProp() ?: "d75926e016f5acee00523712b830379c53203ac08cb8a485583005f529ee7587".hexToByteArray()
}

// TODO: get verified boot keys
@OptIn(ExperimentalStdlibApi::class)
val bootKey by lazy {
    "c34b68e0571933605261e790156658696e4788a88cb5b71d6173cf214c7e87ca".hexToByteArray()
}

@OptIn(ExperimentalStdlibApi::class)
fun getBootHashFromProp(): ByteArray? {
    val b = SystemProperties.get("ro.boot.vbmeta.digest", null) ?: return null
    if (b.length != 64) return null
    return b.hexToByteArray()
}

fun randomBytes() = ByteArray(32).also { ThreadLocalRandom.current().nextBytes(it) }

val patchLevel by lazy {
    Build.VERSION.SECURITY_PATCH.convertPatchLevel(false)
}

val patchLevelLong by lazy {
    Build.VERSION.SECURITY_PATCH.convertPatchLevel(true)
}

// FIXME
val osVersion by lazy {
    when (Build.VERSION.SDK_INT) {
        36 -> 160000
        35 -> 150000
        Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> 140000
        Build.VERSION_CODES.TIRAMISU -> 130000
        Build.VERSION_CODES.S_V2 -> 120100
        Build.VERSION_CODES.S -> 120000
        else -> 0
    }
}

val keyMintVersion by lazy {
    when (Build.VERSION.SDK_INT) {
        36 -> 400
        35 -> 400
        Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> 300
        Build.VERSION_CODES.TIRAMISU -> 200
        Build.VERSION_CODES.S_V2 -> 100
        Build.VERSION_CODES.S -> 100
        else -> 100
    }
}

fun String.convertPatchLevel(long: Boolean) = kotlin.runCatching {
    val l = split("-")
    if (long) l[0].toInt() * 10000 + l[1].toInt() * 100 + l[2].toInt()
    else l[0].toInt() * 100 + l[1].toInt()
}.onFailure { Logger.e("invalid patch level $this !", it) }.getOrDefault(202404)

fun IPackageManager.getPackageInfoCompat(name: String, flags: Long, userId: Int) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(name, flags, userId)
    } else {
        getPackageInfo(name, flags.toInt(), userId)
    }

fun String.trimLine(): String {
    val sb = StringBuilder(length)
    var start = 0
    var end = length - 1
    while (start <= end && this[start].isWhitespace()) start++
    while (end >= start && this[end].isWhitespace()) end--
    if (start > end) return ""

    var lineStart = start
    while (lineStart <= end) {
        var ptr = lineStart
        while (ptr <= end && this[ptr] != '\n') ptr++

        var s = lineStart
        var e = ptr - 1
        while (s <= e && this[s].isWhitespace()) s++
        while (e >= s && this[e].isWhitespace()) e--

        if (sb.isNotEmpty()) sb.append('\n')
        if (s <= e) sb.append(this, s, e + 1)

        lineStart = ptr + 1
    }
    return sb.toString()
}
