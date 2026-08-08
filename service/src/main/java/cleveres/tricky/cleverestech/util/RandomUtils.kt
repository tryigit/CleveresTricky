package cleveres.tricky.cleverestech.util

import java.security.SecureRandom

object RandomUtils {
    private const val CHAR_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val MAX_IDENTIFIER_LENGTH = 64

    private val secureRandom: SecureRandom
        get() = requireNotNull(threadLocalRandom.get()) { "ThreadLocal SecureRandom must not be null" }
    private val threadLocalRandom = ThreadLocal.withInitial { SecureRandom() }

    fun generateLuhn(
        length: Int,
        prefix: String = "",
    ): String {
        require(length in 2..MAX_IDENTIFIER_LENGTH) { "Luhn length must be between 2 and $MAX_IDENTIFIER_LENGTH" }
        require(prefix.length < length) { "Prefix must leave room for a check digit" }
        require(prefix.all(Char::isDigit)) { "Prefix must contain only decimal digits" }

        val rng = secureRandom
        val sb = StringBuilder(length)
        sb.append(prefix)
        while (sb.length < length - 1) {
            sb.append(rng.nextInt(10))
        }

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

    fun generateRandomSerial(length: Int): String {
        require(length in 1..MAX_IDENTIFIER_LENGTH) { "Serial length must be between 1 and $MAX_IDENTIFIER_LENGTH" }
        val rng = secureRandom
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(CHAR_POOL[rng.nextInt(CHAR_POOL.length)])
        }
        return sb.toString()
    }
}
