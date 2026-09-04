package cleveres.tricky.cleverestech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeWorkCoordinatorRegressionTest {
    @Test
    fun `existing keybox directory requires parent and child coverage before handoff`() {
        assertTrue(
            keyboxWatcherCoverageReady(
                parentArmed = true,
                directoryExists = true,
                childArmed = true,
            ),
        )
        assertFalse(
            keyboxWatcherCoverageReady(
                parentArmed = true,
                directoryExists = true,
                childArmed = false,
            ),
        )
        assertFalse(
            keyboxWatcherCoverageReady(
                parentArmed = false,
                directoryExists = true,
                childArmed = true,
            ),
        )
    }

    @Test
    fun `absent keybox directory still requires parent coverage before handoff`() {
        assertTrue(
            keyboxWatcherCoverageReady(
                parentArmed = true,
                directoryExists = false,
                childArmed = false,
            ),
        )
        assertFalse(
            keyboxWatcherCoverageReady(
                parentArmed = false,
                directoryExists = false,
                childArmed = false,
            ),
        )
    }
}
