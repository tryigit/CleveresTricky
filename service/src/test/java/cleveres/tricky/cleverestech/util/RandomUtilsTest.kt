package cleveres.tricky.cleverestech.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomUtilsTest {

    @Test
    fun testGenerateVisibleSimCount_allowZeroTrue() {
        val result = RandomUtils.generateVisibleSimCount(true)
        assertEquals("When allowZero is true, generated string should have length 2", 2, result.length)
        assertTrue("Generated string should only contain digits", result.all { it.isDigit() })
    }

    @Test
    fun testGenerateVisibleSimCount_allowZeroFalse() {
        val result = RandomUtils.generateVisibleSimCount(false)
        assertEquals("When allowZero is false, generated string should have length 1", 1, result.length)
        assertTrue("Generated string should only contain digits", result.all { it.isDigit() })
    }
}
