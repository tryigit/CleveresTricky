package cleveres.tricky.cleverestech.util

import android.util.JsonReader
import java.io.IOException
import java.io.Reader
import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** Frozen managed oracle for CRL normalization and revocation matching during the Rust migration. */
internal object ManagedCrlOracle {
    private const val MAX_CRL_ENTRIES = 1_000_000
    private const val MAX_CRL_KEY_CHARS = 128
    private val hashLengths = intArrayOf(32, 40, 64)
    private val zeros = "0".repeat(64)

    fun parse(reader: Reader): Set<String> {
        val output = HashSet<String>()
        val json = JsonReader(reader)
        var entriesFound = false
        var entriesProcessed = 0
        try {
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() == "entries") {
                    entriesFound = true
                    json.beginObject()
                    while (json.hasNext()) {
                        if (++entriesProcessed > MAX_CRL_ENTRIES) throw IOException("CRL has too many entries")
                        val key = json.nextName()
                        if (key.length > MAX_CRL_KEY_CHARS) throw IOException("CRL entry key is too long")
                        json.skipValue()
                        normalizeEntry(key, output)
                    }
                    json.endObject()
                } else {
                    json.skipValue()
                }
            }
            json.endObject()
        } finally {
            json.close()
        }
        if (!entriesFound) throw IOException("Invalid CRL: 'entries' object missing")
        return output
    }

    fun isRevoked(
        cert: X509Certificate,
        revoked: Set<String>,
    ): Boolean {
        if (revoked.contains(cert.serialNumber.toString(16))) return true
        val encoded = cert.publicKey.encoded
        return hashMatches(encoded, "SHA-1", revoked) ||
            hashMatches(encoded, "SHA-256", revoked) ||
            hashMatches(encoded, "MD5", revoked)
    }

    private fun normalizeEntry(
        value: String,
        output: HashSet<String>,
    ) {
        if (value.isEmpty() || value.length > MAX_CRL_KEY_CHARS) return
        var added = false
        val digitStart = if (value[0] == '-') 1 else 0
        var decimal = digitStart < value.length
        if (decimal && value.length - digitStart > 1 && value[digitStart] == '0') {
            decimal = false
        } else if (decimal) {
            for (index in digitStart until value.length) {
                if (!Character.isDigit(value[index])) {
                    decimal = false
                    break
                }
            }
        }
        if (decimal) {
            try {
                val number = BigInteger(value)
                val hex = number.toString(16)
                output += hex
                if (number.signum() >= 0) {
                    for (target in hashLengths) {
                        if (hex.length < target) output += zeros.substring(0, target - hex.length) + hex
                    }
                }
                added = true
            } catch (_: Exception) {
            }
        }
        if (value.length in hashLengths && value.all(::isHexChar)) {
            output += value.lowercase()
        }
        if (!decimal && !added && value.all(::isHexChar)) {
            try {
                output += BigInteger(value, 16).toString(16)
            } catch (_: Exception) {
            }
        }
    }

    private fun hashMatches(
        bytes: ByteArray,
        algorithm: String,
        revoked: Set<String>,
    ): Boolean {
        val digest = MessageDigest.getInstance(algorithm).digest(bytes)
        return revoked.contains(digest.joinToString("") { "%02x".format(it) })
    }

    private fun isHexChar(value: Char): Boolean =
        value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'
}
