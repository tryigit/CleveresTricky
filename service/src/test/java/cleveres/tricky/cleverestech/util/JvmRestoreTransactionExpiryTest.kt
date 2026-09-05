package cleveres.tricky.cleverestech.util

import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class JvmRestoreTransactionExpiryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun expiredTransactionsCannotPermanentlyExhaustRestoreCapacity() {
        var nowNanos = 0L
        val backend = JvmSecureRestoreFileOperations { nowNanos }
        val configDir = tempFolder.newFolder("config")
        val activeTokens =
            (1..4).map { value ->
                value.toString(16).padStart(32, '0')
            }

        activeTokens.forEach { token ->
            backend.begin(configDir, token, 0L)
        }

        val replacementToken = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        assertThrows(IOException::class.java) {
            backend.begin(configDir, replacementToken, 0L)
        }

        nowNanos = 16L * 60L * 1_000_000_000L
        backend.begin(configDir, replacementToken, 0L)

        assertThrows(IOException::class.java) {
            backend.abort(configDir, activeTokens.first())
        }
        backend.abort(configDir, replacementToken)
    }
}
