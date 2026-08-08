package cleveres.tricky.cleverestech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class WebServerInstrumentationTest {
    @Test
    fun testWebServerStartsAndServesContent() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val configDir = File(appContext.filesDir, "config")
        configDir.mkdirs()

        // Start on a random port
        val server = WebServer(0, configDir)
        server.start()

        try {
            assertTrue("Server should be alive", server.isAlive)
            val port = server.listeningPort
            assertTrue("Port should be greater than 0", port > 0)

            // Connect using loopback IP explicitly instead of "localhost" to avoid DNS issues in some envs
            val url = URL("http://127.0.0.1:$port/")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2000 // 2s timeout
            connection.readTimeout = 2000
            connection.connect()

            assertEquals("Response code should be 200", 200, connection.responseCode)
            val content = connection.inputStream.bufferedReader().use { it.readText() }
            assertTrue("Response should contain app name", content.contains("CleveresTricky"))
        } finally {
            server.stop()
        }
    }

    private fun getDeviceIp(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address) return addr.hostAddress
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    @Test
    fun testWebServerBindsToLocalhostOnly() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val configDir = File(appContext.filesDir, "config_bind")
        configDir.mkdirs()

        // Start on a random port
        val server = WebServer(0, configDir)
        server.start()

        try {
            val port = server.listeningPort
            assertTrue("Port should be greater than 0", port > 0)

            // 1. Should connect via localhost (127.0.0.1)
            val localUrl = URL("http://127.0.0.1:$port/")
            val localConn = localUrl.openConnection() as HttpURLConnection
            localConn.connectTimeout = 1000
            localConn.connect()
            assertEquals("Should connect via localhost", 200, localConn.responseCode)
            localConn.inputStream.close()

            // 2. Should NOT connect via external IP
            val deviceIp = getDeviceIp()
            if (deviceIp != null) {
                try {
                    val remoteUrl = URL("http://$deviceIp:$port/")
                    val remoteConn = remoteUrl.openConnection() as HttpURLConnection
                    remoteConn.connectTimeout = 1000
                    remoteConn.connect()
                    fail("Should not be able to connect via external IP: $deviceIp")
                } catch (e: Exception) {
                    // Expecting ConnectException or SocketTimeoutException
                    // or some IO Exception indicating refusal or timeout
                }
            } else {
                // If no external IP, we can't test this part, but that's fine for emulator
                println("No external IP found, skipping external access test")
            }
        } finally {
            server.stop()
        }
    }
}
