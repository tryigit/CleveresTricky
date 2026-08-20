package cleveres.tricky.cleverestech

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerManagerConcurrencyRegressionTest {
    @Test
    fun `server refresh never owns global state monitor across network io`() {
        val source = source()
        assertFalse(source.contains("@Synchronized\n    fun fetchFromServer"))
        assertTrue(source.contains("synchronized(fetchLockFor(server.id))"))
        assertTrue(source.contains("private fun fetchFromServerLocked"))
        assertTrue(source.contains("conn.responseCode"))
    }

    @Test
    fun `recovery and mutations invalidate stale fetch snapshots`() {
        val source = source()
        assertTrue(source.contains("@Synchronized\n    fun initialize()"))
        assertTrue(source.contains("stateGeneration++"))
        assertTrue(source.contains("context.generation == stateGeneration"))
        assertTrue(source.contains("serversMap[context.snapshot.id] === context.target"))
        assertTrue(
            source.contains(
                "val snapshot = target.copy(authData = JSONObject(target.authData.toString()))",
            ),
        )
    }

    @Test
    fun `stale fetch results are checked before every publication path`() {
        val source = source()
        val currentGuard = "if (!isFetchCurrent(context)) return false"
        assertTrue(source.split(currentGuard).size - 1 >= 2)
        assertTrue(source.contains("private fun commitFetchFailure"))
        assertTrue(source.contains("private fun commitFetchSuccess"))
    }

    private fun source(): String {
        var current = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(6) {
            val candidate = File(current, "service/src/main/java/cleveres/tricky/cleverestech/ServerManager.kt")
            if (candidate.isFile) return candidate.readText()
            current = current.parentFile ?: return@repeat
        }
        error("Repository root not found")
    }
}
