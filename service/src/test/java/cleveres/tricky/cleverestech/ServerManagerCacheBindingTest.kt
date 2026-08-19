package cleveres.tricky.cleverestech

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerManagerCacheBindingTest {
    @Test
    fun `cache binding changes when remote source or trust inputs change`() {
        val original = server()

        assertTrue(
            ServerManager.cacheBindingChanged(
                original,
                original.copy(url = "https://other.example.com/keybox.xml"),
            ),
        )
        assertTrue(
            ServerManager.cacheBindingChanged(
                original,
                original.copy(
                    authType = "BEARER",
                    authData = JSONObject().put("token", "replacement-token"),
                ),
            ),
        )
        assertTrue(
            ServerManager.cacheBindingChanged(
                original,
                original.copy(contentPassword = "new-password"),
            ),
        )
        assertTrue(
            ServerManager.cacheBindingChanged(
                original,
                original.copy(contentPublicKey = "new-public-key"),
            ),
        )
    }

    @Test
    fun `cache binding ignores scheduling and presentation changes`() {
        val original = server()
        val replacement =
            original.copy(
                name = "Renamed server",
                priority = 99,
                enabled = false,
                autoRefresh = false,
                refreshIntervalHours = 48,
                lastStatus = "NETWORK_ERROR",
                lastChecked = 1234,
                lastAuthor = "Different author label",
                authData = JSONObject(original.authData.toString()),
            )

        assertFalse(ServerManager.cacheBindingChanged(original, replacement))
    }

    private fun server() =
        ServerManager.ServerConfig(
            id = "server-a",
            name = "Server A",
            url = "https://example.com/keybox.xml",
            priority = 10,
            enabled = true,
            authType = "NONE",
            authData = JSONObject(),
            autoRefresh = true,
            refreshIntervalHours = 24,
            contentPassword = "password",
            contentPublicKey = "public-key",
        )
}
