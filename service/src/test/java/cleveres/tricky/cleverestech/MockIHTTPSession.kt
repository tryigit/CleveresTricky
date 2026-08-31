package cleveres.tricky.cleverestech

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.CookieHandler
import java.io.InputStream

open class MockIHTTPSession(
    private val uri: String = "/",
    private val method: Method = Method.GET,
    private val headers: Map<String, String> = mapOf("host" to "localhost"),
    private val parms: Map<String, String> = emptyMap(),
    private val parameters: Map<String, List<String>> = emptyMap(),
    private val inputStream: InputStream? = null,
    private val queryParameterString: String = "",
    private val remoteIpAddress: String = "127.0.0.1",
    private val remoteHostName: String = "localhost"
) : NanoHTTPD.IHTTPSession {

    override fun execute() {}

    override fun getCookies(): CookieHandler? = null

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun getHeaders(): Map<String, String> = headers

    override fun getInputStream(): InputStream? = inputStream

    override fun getMethod(): Method = method

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun getParms(): Map<String, String> = parms

    override fun getParameters(): Map<String, List<String>> = if (parameters.isEmpty() && parms.isNotEmpty()) parms.mapValues { listOf(it.value) } else parameters

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun getQueryParameterString(): String = queryParameterString

    override fun getUri(): String = uri

    override fun parseBody(files: MutableMap<String, String>?) {}

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun getRemoteIpAddress(): String = remoteIpAddress

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun getRemoteHostName(): String = remoteHostName
}
