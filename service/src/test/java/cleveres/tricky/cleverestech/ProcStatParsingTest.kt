package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcStatParsingTest {
    @Test
    fun parsesProcessTicksWithoutTokenAllocation() {
        val stat = "123 (service worker) S 1 2 3 4 5 6 7 8 9 10 120 30 0 0"

        assertEquals(150L, parseProcessCpuTicks(stat))
    }

    @Test
    fun parsesAggregateCpuTicksWithoutDoubleCountingGuestTime() {
        assertEquals(360L, parseTotalCpuTicks("cpu  10 20 30 40 50 60 70 80 90 100"))
        assertEquals(36L, parseTotalCpuTicks("cpu 1 2 3 4 5 6 7 8 100 200"))
    }

    @Test
    fun rejectsMalformedAndOverflowingStats() {
        assertNull(parseProcessCpuTicks("123 service S 1 2"))
        assertNull(parseProcessCpuTicks("123 (service) S 1 2 3 4 5 6 7 8 9 10 nope 30"))
        assertNull(parseTotalCpuTicks("cpu0 1 2 3 4"))
        assertNull(parseTotalCpuTicks("cpu 9223372036854775807 1 1 1"))
    }
}
