package cleveres.tricky.cleverestech.util

import java.security.SecureRandom

object RandomUtils {
    private const val CHAR_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val HEX_POOL = "0123456789ABCDEF"
    private const val MAX_IDENTIFIER_LENGTH = 64

    private val secureRandom: SecureRandom
        get() = requireNotNull(threadLocalRandom.get()) { "ThreadLocal SecureRandom must not be null" }
    private val threadLocalRandom = ThreadLocal.withInitial { SecureRandom() }
    private val visibleSimCounts = listOf("0", "1", "1", "1", "1", "2", "2")
    private val activeVisibleSimCounts = listOf("1", "1", "1", "1", "2", "2")

    fun generateVisibleSimCount(allowZero: Boolean): String =
        choose(if (allowZero) visibleSimCounts else activeVisibleSimCounts) ?: "1"

    fun generateLuhn(
        length: Int,
        prefix: String = "",
        postfix: String = "",
    ): String {
        require(length in 2..MAX_IDENTIFIER_LENGTH) { "Luhn length must be between 2 and $MAX_IDENTIFIER_LENGTH" }
        val count = length - prefix.length - postfix.length - 1
        require(count >= 0) { "Length too short" }
        require(prefix.all(Char::isDigit)) { "Prefix must contain only decimal digits" }
        require(postfix.all(Char::isDigit)) { "Postfix must contain only decimal digits" }

        val sb = StringBuilder(length)
        sb.append(prefix)
        val body = generateDigits(count)
        if (body.isNotEmpty()) {
            sb.append(body)
        }
        sb.append(postfix)

        var sum = 0
        var isSecond = true
        for (i in sb.length - 1 downTo 0) {
            var d = sb[i] - '0'
            if (isSecond) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            isSecond = !isSecond
        }
        val checkDigit = (10 - (sum % 10)) % 10
        sb.append(checkDigit)
        return sb.toString()
    }

    fun generateDigits(
        length: Int,
        prefix: String = "",
    ): String {
        require(length in 1..MAX_IDENTIFIER_LENGTH) { "Identifier length must be between 1 and $MAX_IDENTIFIER_LENGTH" }
        require(prefix.length <= length) { "Prefix cannot exceed identifier length" }
        require(prefix.all(Char::isDigit)) { "Prefix must contain only decimal digits" }

        val result = StringBuilder(length).append(prefix)
        while (result.length < length) result.append(secureRandom.nextInt(10))
        return result.toString()
    }

    fun generateHex(length: Int): String {
        require(length in 1..MAX_IDENTIFIER_LENGTH) { "Hex length must be between 1 and $MAX_IDENTIFIER_LENGTH" }
        val rng = secureRandom
        return buildString(length) {
            repeat(length) { append(HEX_POOL[rng.nextInt(HEX_POOL.length)]) }
        }
    }

    fun generateRandomSerial(length: Int): String {
        require(length in 1..MAX_IDENTIFIER_LENGTH) { "Serial length must be between 1 and $MAX_IDENTIFIER_LENGTH" }
        val rng = secureRandom
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(CHAR_POOL[rng.nextInt(CHAR_POOL.length)])
        }
        return sb.toString()
    }

    fun <T> choose(values: List<T>): T? {
        if (values.isEmpty()) return null
        return values[secureRandom.nextInt(values.size)]
    }
}
