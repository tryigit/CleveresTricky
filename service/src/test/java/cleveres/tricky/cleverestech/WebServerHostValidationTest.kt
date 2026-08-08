package cleveres.tricky.cleverestech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebServerHostValidationTest {
    @Test
    fun acceptsOnlyCanonicalLoopbackHosts() {
        assertTrue(WebServer.isSafeHost("localhost"))
        assertTrue(WebServer.isSafeHost("localhost:8080"))
        assertTrue(WebServer.isSafeHost("127.0.0.1:1"))
        assertTrue(WebServer.isSafeHost("[::1]:65535"))
        assertTrue(WebServer.isSafeHost("[0:0:0:0:0:0:0:1]"))

        assertFalse(WebServer.isSafeHost(null))
        assertFalse(WebServer.isSafeHost("attacker.example"))
        assertFalse(WebServer.isSafeHost("localhost.evil"))
        assertFalse(WebServer.isSafeHost("localhost:evil"))
        assertFalse(WebServer.isSafeHost("localhost:0"))
        assertFalse(WebServer.isSafeHost("localhost:65536"))
        assertFalse(WebServer.isSafeHost("[::1]evil"))
        assertFalse(WebServer.isSafeHost("::1"))
    }
}
