package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrmInjectionHistoryTest {
    @Test
    fun `injection history evicts oldest pid while refreshing recent entries`() {
        val history = BoundedPidHistory<Long>(3)
        history.put(101, 1L)
        history.put(102, 2L)
        history.put(103, 3L)
        history.put(101, 4L)
        history.put(104, 5L)

        assertEquals(3, history.size)
        assertFalse(history.containsKey(102))
        assertTrue(history.containsKey(101))
        assertEquals(4L, history[101])
        assertTrue(history.containsKey(103))
        assertTrue(history.containsKey(104))
    }
}
