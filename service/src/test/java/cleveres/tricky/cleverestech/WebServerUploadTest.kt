package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class WebServerUploadTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: WebServer
    private lateinit var configDir: File
    private lateinit var originalSecureFileImpl: SecureFileOperations

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

        originalSecureFileImpl = SecureFile.impl
        SecureFile.impl =
            object : SecureFileOperations {
                override fun writeText(
                    file: File,
                    content: String,
                ) {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                }

                override fun mkdirs(
                    file: File,
                    mode: Int,
                ) {
                    file.mkdirs()
                }

                override fun touch(
                    file: File,
                    mode: Int,
                ) {
                    file.parentFile?.mkdirs()
                    if (!file.exists()) file.createNewFile()
                }
            }

        server = WebServer(0, configDir, crlFetcher = { emptySet() })
        server.start()
    }

    @After
    fun tearDown() {
        SecureFile.impl = originalSecureFileImpl
        server.stop()
    }

    private fun uploadKeybox(
        filename: String,
        content: String,
    ): Int {
        val port = server.listeningPort
        val token = server.token
        val url = URL("http://localhost:$port/api/upload_keybox?token=$token")

        val encodedFilename = java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8.name())
        val encodedContent = java.net.URLEncoder.encode(content, StandardCharsets.UTF_8.name())
        val postData = "filename=$encodedFilename&content=$encodedContent"
        val postDataBytes = postData.toByteArray(StandardCharsets.UTF_8)

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.write(postDataBytes)
        conn.outputStream.close()

        return conn.responseCode
    }

    @Test
    fun testUploadKeyboxValidFilename() {
        val validXml = TestKeyboxFixtures.validEcKeyboxXml

        val responseCode = uploadKeybox("valid_keybox.xml", validXml)
        assertEquals(200, responseCode)

        val f = File(configDir, "keyboxes/valid_keybox.xml")
        assert(f.exists())
    }

    @Test
    fun testUploadKeyboxInvalidContent() {
        val responseCode = uploadKeybox("invalid_content.xml", "<xml>bad</xml>")
        assertEquals(400, responseCode)
    }

    @Test
    fun testUploadKeyboxInvalidFilenameSpace() {
        val responseCode = uploadKeybox("keybox space.xml", "<xml>bad</xml>")
        assertEquals(400, responseCode)
    }

    @Test
    fun testUploadKeyboxInvalidFilenameSpecialChar() {
        val responseCode = uploadKeybox("keybox!.xml", "<xml>bad</xml>")
        assertEquals(400, responseCode)
    }

    @Test
    fun testUploadKeyboxInvalidFilenameTraversal() {
        // Even if we URL encode it, the server sees the decoded param.
        // But here we send raw string in post body (x-www-form-urlencoded).
        // ".." is dots. "/" is slash.
        // If we send "filename=../foo.xml", regex matches "." but not "/".

        val responseCode = uploadKeybox("../foo.xml", "<xml>bad</xml>")
        assertEquals(400, responseCode)
    }
}
