package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.RandomUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomUtilsTest {
    @Test
    fun testGenerateLuhn() {
        val length = 15
        val luhn = RandomUtils.generateLuhn(length)
        assertEquals(length, luhn.length)
        assertTrue(luhn.matches(Regex("\\d+")))

        // Verify Luhn check digit
        val allDigits = luhn.map { it.toString().toInt() }.toIntArray()
        var verifySum = 0
        var doubleIt = false
        // Iterate from right to left (check digit is at index length-1)
        for (i in allDigits.indices.reversed()) {
            var d = allDigits[i]
            if (doubleIt) {
                d *= 2
                if (d > 9) d -= 9
            }
            verifySum += d
            doubleIt = !doubleIt
        }

        assertEquals("Luhn check failed for $luhn", 0, verifySum % 10)
    }

    @Test
    fun testGenerateLuhnWithPrefix() {
        val prefix = "35"
        val length = 15
        val luhn = RandomUtils.generateLuhn(length, prefix)
        assertEquals(length, luhn.length)
        assertTrue(luhn.startsWith(prefix))

        // Verify Luhn
        val allDigits = luhn.map { it.toString().toInt() }.toIntArray()
        var verifySum = 0
        var doubleIt = false
        for (i in allDigits.indices.reversed()) {
            var d = allDigits[i]
            if (doubleIt) {
                d *= 2
                if (d > 9) d -= 9
            }
            verifySum += d
            doubleIt = !doubleIt
        }
        assertEquals("Luhn check failed for $luhn", 0, verifySum % 10)
    }

    @Test
    fun testGenerateRandomSerial() {
        val length = 12
        val serial = RandomUtils.generateRandomSerial(length)
        assertEquals(length, serial.length)
        assertTrue(serial.matches(Regex("^[A-Z0-9]+$")))
    }

    @Test
    fun testGenerateDigitsWithPrefix() {
        val digits = RandomUtils.generateDigits(15, "310260")
        assertEquals(15, digits.length)
        assertTrue(digits.startsWith("310260"))
        assertTrue(digits.all(Char::isDigit))
    }

    @Test
    fun testRejectsInvalidIdentifierArguments() {
        assertThrows(IllegalArgumentException::class.java) { RandomUtils.generateLuhn(1) }
        assertThrows(IllegalArgumentException::class.java) { RandomUtils.generateLuhn(15, "abc") }
        assertThrows(IllegalArgumentException::class.java) { RandomUtils.generateLuhn(2, "12") }
        assertThrows(IllegalArgumentException::class.java) { RandomUtils.generateDigits(3, "1234") }
        assertThrows(IllegalArgumentException::class.java) { RandomUtils.generateRandomSerial(0) }
    }
}
