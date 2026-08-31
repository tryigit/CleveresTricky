package cleveres.tricky.cleverestech

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.CookieHandler
import java.io.InputStream

open class MockIHTTPSession(
    private val uri: String = "/",
    private val method: Method = Method.GET,
    private val headers: Map<String, String> = mapOf("host" to "localhost"),
    private val parameters: Map<String, List<String>> = emptyMap(),
    private val inputStream: InputStream? = null,
    private val queryParameterString: String = "",
    private val remoteIpAddress: String = "127.0.0.1",
    private val remoteHostName: String = "localhost"
) : NanoHTTPD.IHTTPSession {

    override fun execute() {}

    override fun getCookies(): CookieHandler? = null

    @Deprecated("Deprecated by NanoHTTPD")
    override fun getHeaders(): Map<String, String> = headers

    override fun getInputStream(): InputStream? = inputStream

    override fun getMethod(): Method = method

    @Deprecated("Use getParameters")
    override fun getParms(): Map<String, String> = parameters.mapValues { it.value.first() }

    override fun getParameters(): Map<String, List<String>> = parameters

    @Deprecated("Deprecated by NanoHTTPD")
    override fun getQueryParameterString(): String = queryParameterString

    override fun getUri(): String = uri

    override fun parseBody(files: MutableMap<String, String>?) {}

    @Deprecated("Deprecated by NanoHTTPD")
    override fun getRemoteIpAddress(): String = remoteIpAddress

    @Deprecated("Deprecated by NanoHTTPD")
    override fun getRemoteHostName(): String = remoteHostName
}
