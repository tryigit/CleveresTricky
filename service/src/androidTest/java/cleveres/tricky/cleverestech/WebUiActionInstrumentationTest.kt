package cleveres.tricky.cleverestech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class WebUiActionInstrumentationTest {
    @Test
    fun testActionScriptUrlLoadsAuthenticatedWebUiOverLoopback() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val configDir = File(appContext.filesDir, "config_action_url")
        configDir.mkdirs()

        val server = WebServer(0, configDir)
        server.start()

        try {
            val port = server.listeningPort
            val token = server.token
            val url = URL("http://127.0.0.1:$port/?token=$token")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.connect()

            assertEquals("Action-script WebUI URL should load successfully", 200, connection.responseCode)
            val content = connection.inputStream.bufferedReader().use { it.readText() }
            assertTrue("Response should contain app name", content.contains("CleveresTricky"))
        } finally {
            server.stop()
        }
    }
}
