package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.RandomUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import javax.net.ssl.HttpsURLConnection

/**
 * Resolves a current Pixel beta/canary identity from Google's public Android
 * developer and Flash Tool metadata. This is an opt-in Custom ROM helper; it
 * never changes the always-on bootloader/TEE protection policy.
 */
object AutoIdentityManager {
    data class Result(
        val model: String,
        val product: String,
        val device: String,
        val fingerprint: String,
        val buildId: String,
        val incremental: String,
        val release: String?,
        val securityPatch: String,
        val securityPatchEstimated: Boolean,
    ) {
        fun buildVars(): Map<String, String> =
            linkedMapOf<String, String>().apply {
                put("MANUFACTURER", "Google")
                put("BRAND", "google")
                put("MODEL", model)
                put("PRODUCT", product)
                put("DEVICE", device)
                put("FINGERPRINT", fingerprint)
                put("BUILD_ID", buildId)
                put("INCREMENTAL", incremental)
                release?.let { put("RELEASE", it) }
                put("TYPE", "user")
                put("TAGS", "release-keys")
                put("SECURITY_PATCH", securityPatch)
            }
    }

    internal data class DeviceCandidate(
        val model: String,
        val device: String,
        val product: String,
    )

    internal fun interface Fetcher {
        @Throws(IOException::class)
        fun get(
            url: String,
            headers: Map<String, String>,
        ): String
    }

    private const val ANDROID_DEVELOPERS = "https://developer.android.com"
    private const val FLASH_TOOL = "https://flash.android.com/"
    private const val FLASH_BUILDS = "https://content-flashstation-pa.googleapis.com/v1/builds"
    private const val PIXEL_BULLETIN = "https://source.android.com/docs/security/bulletin/pixel"
    private const val MAX_DOWNLOAD_BYTES = 3 * 1024 * 1024
    private val allowedHosts =
        setOf(
            "developer.android.com",
            "flash.android.com",
            "content-flashstation-pa.googleapis.com",
            "source.android.com",
        )

    fun fetchLatest(): Result = fetchLatest(NetworkFetcher)

    internal fun fetchLatest(
        fetcher: Fetcher,
        selector: (List<DeviceCandidate>) -> DeviceCandidate? = { RandomUtils.choose(it) },
    ): Result {
        val versionsHtml = fetcher.get("$ANDROID_DEVELOPERS/about/versions", emptyMap())
        val latestPath = findLatestVersionPath(versionsHtml) ?: throw IOException("Pixel beta version page was not found")
        val versionHtml = fetcher.get(resolveDeveloperPath(latestPath), emptyMap())

        val downloadLinks = extractDownloadLinks(versionHtml)
        if (downloadLinks.isEmpty()) throw IOException("Pixel beta download pages were not found")
        val candidatePages =
            downloadLinks.mapNotNull { link ->
                runCatching {
                    val html = fetcher.get(resolveDeveloperPath(link), emptyMap())
                    parseDeviceCandidates(html)
                }.getOrNull()
            }
        val candidates = candidatePages.maxByOrNull { it.size }.orEmpty()
        val candidate = selector(candidates) ?: throw IOException("No Pixel beta device was found")

        val flashHtml = fetcher.get(FLASH_TOOL, emptyMap())
        val apiKey = extractFlashApiKey(flashHtml) ?: throw IOException("Android Flash Tool API key was not found")
        val product = URLEncoder.encode(candidate.product, StandardCharsets.UTF_8.name())
        val key = URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name())
        val buildsJson =
            fetcher.get(
                "$FLASH_BUILDS?product=$product&key=$key",
                mapOf("Referer" to FLASH_TOOL),
            )
        val canary = findLatestCanary(buildsJson) ?: throw IOException("Pixel canary build metadata was not found")
        val buildId = canary.optString("releaseCandidateName").trim()
        val incremental = canary.optString("buildId").trim()
        if (buildId.isEmpty() || incremental.isEmpty()) throw IOException("Pixel canary build metadata is incomplete")

        val track = canary.optString("releaseTrackVersionName").trim()
        val release = Regex("""\b(\d{1,2})(?:\.\d+)?\b""").find(track)?.groupValues?.get(1)
        val fingerprint =
            "google/${candidate.product}/${candidate.device}:CANARY/$buildId/$incremental:user/release-keys"

        var estimated = false
        val explicitPatch = findSecurityPatchField(canary)
        val bulletinPatch =
            if (explicitPatch == null) {
                runCatching {
                    val bulletin = fetcher.get(PIXEL_BULLETIN, emptyMap())
                    findSecurityPatchInBulletin(bulletin, canary.optString("id"))
                }.getOrNull()
            } else {
                null
            }
        val securityPatch = explicitPatch ?: bulletinPatch ?: estimateSecurityPatch(canary.optString("id")).also { estimated = true }

        return Result(
            model = candidate.model,
            product = candidate.product,
            device = candidate.device,
            fingerprint = fingerprint,
            buildId = buildId,
            incremental = incremental,
            release = release,
            securityPatch = securityPatch,
            securityPatchEstimated = estimated,
        )
    }

    internal fun findLatestVersionPath(html: String): String? =
        Regex("""href=["'](/about/versions/(\d{1,2}))["']""")
            .findAll(html)
            .map { match -> match.groupValues[1] to match.groupValues[2].toInt() }
            .maxByOrNull { it.second }
            ?.first

    internal fun extractDownloadLinks(html: String): List<String> =
        Regex("""href=["']([^"']*download(?:-ota)?[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .filter { it.startsWith('/') || it.startsWith(ANDROID_DEVELOPERS) }
            .distinct()
            .take(4)
            .toList()

    internal fun parseDeviceCandidates(html: String): List<DeviceCandidate> {
        val rows =
            Regex(
                """<tr\s+id=["']([^"']+)["'][^>]*>(.*?)</tr>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
        val modelCell =
            Regex(
                """<td[^>]*>\s*(.*?)\s*</td>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
        return rows.findAll(html).mapNotNull { row ->
            val device = row.groupValues[1].trim()
            val rawModel = modelCell.find(row.groupValues[2])?.groupValues?.get(1) ?: return@mapNotNull null
            val model = stripHtml(rawModel)
            if (!VALID_DEVICE.matches(device) || model.isBlank() || model.length > 128) return@mapNotNull null
            DeviceCandidate(model, device, "${device}_beta")
        }.distinctBy { it.device }.take(64).toList()
    }

    internal fun extractFlashApiKey(html: String): String? {
        val attribute =
            Regex("""data-client-config=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.replace("&amp;", "&")
        attribute
            ?.split(';')
            ?.drop(1)
            ?.asSequence()
            ?.map { it.substringBefore('&').trim() }
            ?.firstOrNull { VALID_API_KEY.matches(it) }
            ?.let { return it }

        return Regex("""(?:apiKey|key)(?:&quot;|["']|\s)*[:=](?:&quot;|["']|\s)*([A-Za-z0-9_-]{16,160})""")
            .find(html)
            ?.groupValues
            ?.get(1)
    }

    internal fun findLatestCanary(json: String): JSONObject? {
        val root: Any =
            runCatching { JSONObject(json) }.getOrElse {
                runCatching { JSONArray(json) }.getOrElse { throw IOException("Invalid Android Flash Tool response") }
            }
        val objects = ArrayList<JSONObject>()
        collectObjects(root, objects)
        return objects.lastOrNull { obj ->
            obj.optBoolean("canary", false) &&
                obj.optString("releaseCandidateName").isNotBlank() &&
                obj.optString("buildId").isNotBlank()
        }
    }

    private fun collectObjects(
        value: Any?,
        output: MutableList<JSONObject>,
    ) {
        when (value) {
            is JSONObject -> {
                output += value
                val keys = value.keys()
                while (keys.hasNext()) collectObjects(value.opt(keys.next()), output)
            }
            is JSONArray -> for (index in 0 until value.length()) collectObjects(value.opt(index), output)
        }
    }

    private fun findSecurityPatchField(canary: JSONObject): String? {
        val names = listOf("securityPatch", "securityPatchLevel", "securityPatchDate")
        for (name in names) {
            normalizePatch(canary.optString(name))?.let { return it }
        }
        return null
    }

    internal fun findSecurityPatchInBulletin(
        html: String,
        canaryId: String,
    ): String? {
        val token = canaryId.removePrefix("canary-").trim()
        if (token.isEmpty()) return null
        val index = html.indexOf(token, ignoreCase = true)
        if (index < 0) return null
        val start = (index - 1200).coerceAtLeast(0)
        val end = (index + token.length + 1200).coerceAtMost(html.length)
        return Regex("""20\d{2}-\d{2}-\d{2}""").find(html.substring(start, end))?.value
    }

    private fun normalizePatch(value: String): String? =
        Regex("""20\d{2}-\d{2}-\d{2}""").find(value)?.value

    internal fun estimateSecurityPatch(canaryId: String): String {
        val digits = canaryId.removePrefix("canary-").filter(Char::isDigit)
        val parsed =
            when {
                digits.length >= 6 && digits.substring(0, 4).toIntOrNull() in 2020..2099 -> {
                    val year = digits.substring(0, 4).toInt()
                    val month = digits.substring(4, 6).toIntOrNull()
                    if (month != null && month in 1..12) LocalDate.of(year, month, 5) else null
                }
                digits.length >= 4 -> {
                    val year = 2000 + (digits.substring(0, 2).toIntOrNull() ?: -100)
                    val month = digits.substring(2, 4).toIntOrNull()
                    if (year in 2020..2099 && month != null && month in 1..12) LocalDate.of(year, month, 5) else null
                }
                else -> null
            }
        return (parsed ?: LocalDate.now().withDayOfMonth(5)).toString()
    }

    private fun resolveDeveloperPath(path: String): String {
        val resolved = URI(ANDROID_DEVELOPERS).resolve(path)
        if (resolved.scheme != "https" || resolved.host != "developer.android.com") {
            throw IOException("Unsupported Android developer link")
        }
        return resolved.toString()
    }

    private fun stripHtml(value: String): String =
        value
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private object NetworkFetcher : Fetcher {
        override fun get(
            url: String,
            headers: Map<String, String>,
        ): String {
            var current = URI(url)
            repeat(4) {
                validateRemoteUri(current)
                val connection = current.toURL().openConnection() as HttpsURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("User-Agent", "CleveresTricky-AutoIdentity/1")
                    headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location") ?: throw IOException("Redirect without Location")
                        current = current.resolve(location)
                        return@repeat
                    }
                    if (code !in 200..299) throw IOException("Identity source returned HTTP $code")
                    return connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            if (count > MAX_DOWNLOAD_BYTES - total) throw IOException("Identity source response is too large")
                            output.write(buffer, 0, count)
                            total += count
                        }
                        output.toString(StandardCharsets.UTF_8.name())
                    }
                } finally {
                    connection.disconnect()
                }
            }
            throw IOException("Too many identity-source redirects")
        }

        private fun validateRemoteUri(uri: URI) {
            if (uri.scheme != "https" || uri.userInfo != null || uri.fragment != null || uri.host !in allowedHosts) {
                throw IOException("Unsupported identity source")
            }
        }
    }

    private val VALID_DEVICE = Regex("[A-Za-z0-9_.-]{1,64}")
    private val VALID_API_KEY = Regex("[A-Za-z0-9_-]{16,160}")
}
