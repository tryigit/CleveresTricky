package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class PathTraversalTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: WebServer
    private lateinit var configDir: File
    private lateinit var outsideFile: File

    @Before
    fun setUp() {
        Logger.setImpl(
            object : Logger.LogImpl {
                override fun d(
                    tag: String,
                    msg: String,
                ) {}

                override fun e(
                    tag: String,
                    msg: String,
                ) {}

                override fun e(
                    tag: String,
                    msg: String,
                    t: Throwable?,
                ) {
                    t?.printStackTrace()
                }

                override fun i(
                    tag: String,
                    msg: String,
                ) {}
            },
        )
        configDir = tempFolder.newFolder("config")
        outsideFile = File(tempFolder.root, "outside.txt")

        server = WebServer(0, configDir)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun testPathTraversalInToggle() {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/api/toggle?token=$token")

        // Try to create a file outside the config directory
        val postData = "setting=../outside.txt&value=true"
        val postDataBytes = postData.toByteArray(StandardCharsets.UTF_8)

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.write(postDataBytes)
        conn.outputStream.close()

        val responseCode = conn.responseCode
        println("Response Code: $responseCode")

        // In vulnerable state, this creates the file
        if (outsideFile.exists()) {
            println("VULNERABILITY CONFIRMED: Created file outside config dir")
        } else {
            println("File not created.")
        }

        // Assert that the file was NOT created (passes if fixed)
        assertFalse("Path traversal should be prevented", outsideFile.exists())
    }
}
