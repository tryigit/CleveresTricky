package cleveres.tricky.cleverestech

import android.content.res.Resources
import android.system.Os
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.BackupEncryptor
import cleveres.tricky.cleverestech.util.CboxDecryptor
import cleveres.tricky.cleverestech.util.FastByteArrayOutputStream
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import cleveres.tricky.cleverestech.util.RandomUtils
import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.readUtf8FileSnapshotBounded
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private fun isValidPkgName(s: String): Boolean {
    if (s.length !in 1..255) return false
    for (i in 0 until s.length) {
        val c = s[i]
        if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '*')) return false
    }
    return true
}

private fun isValidTemplateName(s: String): Boolean {
    if (s.length !in 1..64) return false
    for (i in 0 until s.length) {
        val c = s[i]
        if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-')) return false
    }
    return true
}

private val clonedKeyboxFilenameSuffix = Regex("""\s*\((\d+)\)(?=\s*(?:\(\d+\)\s*)*\.[^.]+$)""")

/**
 * Android file providers commonly append " (1)" when a filename is copied. Keep the strict
 * basename policy, but canonicalize that provider-generated suffix before validation and storage.
 */
private fun normalizeKeyboxUploadFilename(name: String): String =
    name.replace(clonedKeyboxFilenameSuffix) { match -> "_${match.groupValues[1]}" }

private fun currentSystemLocaleTag(): String {
    val locale =
        runCatching { Resources.getSystem().configuration.locales[0] }
            .getOrNull()
            ?: runCatching { java.util.Locale.getDefault() }.getOrNull()
            ?: return "en"
    val tag = locale.toLanguageTag()
    return if (tag.isBlank()) "en" else tag
}

private fun isValidKeyboxFilename(s: String): Boolean {
    if (s.length !in 5..128 || s.startsWith('.')) return false
    for (i in 0 until s.length) {
        val c = s[i]
        if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '-')) return false
    }
    val lower = s.lowercase()
    return lower.endsWith(".xml") || lower.endsWith(".cbox")
}

private fun isValidKeyValue(s: String): Boolean {
    if (s.isEmpty()) return false
    val eqIdx = s.indexOf('=')
    if (eqIdx <= 0 || eqIdx == s.length - 1) return false
    for (i in 0 until eqIdx) {
        val c = s[i]
        if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.')) return false
    }
    return true
}

private fun isValidSafeBuildVarValue(s: String): Boolean {
    for (i in 0 until s.length) {
        val c = s[i]
        val isAllowed =
            c.isLetterOrDigit() ||
                c == '_' || c == '-' || c == '.' || c.isWhitespace() ||
                c == '/' || c == ':' || c == ',' || c == '+' ||
                c == '=' || c == '(' || c == ')' || c == '@'
        if (!isAllowed) return false
    }
    return true
}

private fun isValidTargetPkg(s: String): Boolean {
    if (s.length !in 1..255) return false
    for (i in 0 until s.length) {
        val c = s[i]
        if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '*' || c == '!')) return false
    }
    return true
}

private fun isValidSecurityPatchValue(
    value: String,
    allowSpecial: Boolean,
): Boolean {
    if (value.equals("today", ignoreCase = true)) return true
    val isSpecial =
        value.equals("no", ignoreCase = true) ||
            value.equals("device_default", ignoreCase = true) ||
            value.equals("prop", ignoreCase = true)
    if (allowSpecial && isSpecial) return true
    if (value.any { it == 'Y' || it == 'M' || it == 'D' }) {
        val sample = value.replace("YYYY", "2024").replace("MM", "06").replace("DD", "15")
        return runCatching { sample.convertPatchLevel(false) }.isSuccess
    }
    return runCatching { value.convertPatchLevel(false) }.isSuccess
}

private class RestoreKeyboxActivationException : IOException("Keybox activation failed after restore")

private fun isValidFilename(s: String): Boolean {
    if (s.isEmpty()) return false
    for (i in 0 until s.length) {
        val c = s[i]
        if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-')) return false
    }
    return true
}

private fun parseUnsignedLong(
    value: CharSequence,
    start: Int,
    endExclusive: Int,
): Long? {
    if (start >= endExclusive) return null
    var result = 0L
    for (index in start until endExclusive) {
        val digit = value[index] - '0'
        if (digit !in 0..9 || result > (Long.MAX_VALUE - digit) / 10L) return null
        result = result * 10L + digit
    }
    return result
}

internal fun parseProcessCpuTicks(stat: CharSequence): Long? {
    val commandEnd = stat.lastIndexOf(')')
    if (commandEnd < 0) return null

    var index = commandEnd + 1
    var fieldIndex = 0
    var userTicks: Long? = null
    var systemTicks: Long? = null
    while (index < stat.length && fieldIndex <= 12) {
        while (index < stat.length && stat[index].isWhitespace()) index++
        if (index >= stat.length) break
        val start = index
        while (index < stat.length && !stat[index].isWhitespace()) index++
        if (fieldIndex == 11) userTicks = parseUnsignedLong(stat, start, index) ?: return null
        if (fieldIndex == 12) systemTicks = parseUnsignedLong(stat, start, index) ?: return null
        fieldIndex++
    }

    val user = userTicks ?: return null
    val system = systemTicks ?: return null
    return if (user <= Long.MAX_VALUE - system) user + system else null
}

internal fun parseTotalCpuTicks(stat: CharSequence): Long? {
    var index = 0
    while (index < stat.length && stat[index].isWhitespace()) index++
    if (index + 3 > stat.length || stat[index] != 'c' || stat[index + 1] != 'p' || stat[index + 2] != 'u') {
        return null
    }
    index += 3
    if (index >= stat.length || !stat[index].isWhitespace()) return null

    var total = 0L
    var count = 0
    while (index < stat.length && count < 8) {
        while (index < stat.length && stat[index].isWhitespace()) index++
        if (index >= stat.length) break
        val start = index
        while (index < stat.length && !stat[index].isWhitespace()) index++
        val value = parseUnsignedLong(stat, start, index) ?: return null
        if (total > Long.MAX_VALUE - value) return null
        total += value
        count++
    }
    return if (count >= 4) total else null
}

private const val LOOPBACK_TEST_HOST = "127.0.0.1"
internal const val MAX_HTTP_WORKERS = 8
internal const val MAX_HTTP_QUEUE_CAPACITY = 16

/**
 * NanoHTTPD 2.3.1's default runner creates one daemon thread per accepted socket.
 * Keep both thread stacks and retained ClientHandlers bounded because the WebUI is
 * loopback-only but still reachable by other apps on the same device.
 */
internal class BoundedHttpAsyncRunner(
    workerCount: Int = MAX_HTTP_WORKERS,
    queueCapacity: Int = MAX_HTTP_QUEUE_CAPACITY,
) : NanoHTTPD.AsyncRunner {
    private val stopped = AtomicBoolean(false)
    private val nextThreadId = AtomicInteger(0)
    private val running = Collections.synchronizedList(mutableListOf<NanoHTTPD.ClientHandler>())
    private val executor =
        ThreadPoolExecutor(
            workerCount,
            workerCount,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(queueCapacity),
            { runnable ->
                Thread(runnable, "CleveresTricky-HTTP-${nextThreadId.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply {
            allowCoreThreadTimeOut(true)
        }

    init {
        require(workerCount > 0) { "HTTP worker count must be positive" }
        require(queueCapacity > 0) { "HTTP queue capacity must be positive" }
    }

    override fun exec(handler: NanoHTTPD.ClientHandler) {
        val accepted =
            synchronized(running) {
                if (stopped.get()) {
                    false
                } else {
                    running.add(handler)
                    try {
                        executor.execute(handler)
                        true
                    } catch (_: RejectedExecutionException) {
                        running.remove(handler)
                        false
                    }
                }
            }
        if (!accepted) handler.close()
    }

    override fun closed(handler: NanoHTTPD.ClientHandler) {
        synchronized(running) { running.remove(handler) }
    }

    override fun closeAll() {
        val snapshot =
            synchronized(running) {
                if (!stopped.compareAndSet(false, true)) return
                running.toList()
            }
        snapshot.forEach { it.close() }
        executor.shutdownNow()
        synchronized(running) { running.clear() }
    }

    internal fun runningCountForTest(): Int = synchronized(running) { running.size }

    internal fun workerCountForTest(): Int = executor.maximumPoolSize

    internal fun queueCapacityForTest(): Int = executor.queue.size + executor.queue.remainingCapacity()
}

class WebServer(
    requestedPort: Int,
    private val configDir: File,
    private val isTampered: Boolean = false,
    // JVM test injection only. Production keeps revocation state as an opaque Rust handle.
    private val crlFetcher: (() -> Set<String>?)? = null,
    private val autoIdentityFetcher: () -> AutoIdentityManager.Result = { AutoIdentityManager.fetchLatest() },
    private val permissionSetter: (File, Int) -> Unit = { f, m ->
        try {
            Os.chmod(f.absolutePath, m)
        } catch (t: Throwable) {
            Logger.e("failed to set permissions for ${f.name}", t)
        }
    },
) : NanoHTTPD(LOOPBACK_TEST_HOST, requestedPort) {
    init {
        setAsyncRunner(BoundedHttpAsyncRunner())
        cleveres.tricky.cleverestech.util.LoggerConfig.disableNanoHttpdLogging()
    }

    val token: String by lazy {
        val randomBytes = ByteArray(32)
        try {
            SecureRandom().nextBytes(randomBytes)
            Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        } finally {
            randomBytes.fill(0)
        }
    }

    private class RateLimitEntry(var timestampNanos: Long, var count: Int)

    private val requestCounts = java.util.concurrent.ConcurrentHashMap<String, RateLimitEntry>()

    private val fileLock = ManagedFileCoordinator.monitor

    private fun updateKeyboxesFromConfiguredRevocationSource(): Boolean =
        crlFetcher?.let { Config.updateKeyBoxesSync(it()) } ?: Config.updateKeyBoxesSync()

    private fun keyboxActivationFailureResponse(): Response =
        secureResponse(
            Response.Status.SERVICE_UNAVAILABLE,
            "text/plain",
            "Keybox activation unavailable; previous active snapshot preserved",
        )

    private fun refreshRuntimeAfterRestoreRollback(configDir: File) {
        DeviceTemplateManager.initialize(configDir, persistBuiltInTemplates = false)
        WEB_UI_SETTINGS.forEach(Config::refreshRuntimeSetting)
        Config.refreshRestoredConfiguration().getOrThrow()
        if (!updateKeyboxesFromConfiguredRevocationSource()) {
            throw RestoreKeyboxActivationException()
        }
    }

    @Suppress("DEPRECATION")
    private fun getParam(
        session: IHTTPSession,
        name: String,
    ): String? {
        return session.parms[name]
    }

    private fun isRateLimited(ip: String): Boolean {
        val now = System.nanoTime()
        if (requestCounts.size > 1000) {
            requestCounts.entries.removeIf { now - it.value.timestampNanos > RATE_WINDOW_NANOS }
            if (requestCounts.size > 1000) requestCounts.clear() // Fallback
        }
        val current =
            requestCounts.compute(ip) { _, v ->
                if (v == null || now - v.timestampNanos > RATE_WINDOW_NANOS) {
                    RateLimitEntry(now, 1)
                } else {
                    v.count++
                    v
                }
            }
        return current!!.count > RATE_LIMIT
    }

    private fun readFile(filename: String): String {
        synchronized(fileLock) {
            return try {
                val f = getSafeFile(configDir, filename)
                if (f == null) {
                    Logger.e("Path traversal attempt detected: $filename")
                    return ""
                }
                if (
                    !Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    f.length() > MAX_CONFIG_FILE_SIZE
                ) {
                    Logger.e("Refusing oversized or non-regular config file: $filename")
                    return ""
                }
                Files.newInputStream(f.toPath(), LinkOption.NOFOLLOW_LINKS).use {
                    readTextLimited(it, MAX_CONFIG_FILE_SIZE.toInt())
                }
            } catch (e: Exception) {
                ""
            }
        }
    }

    private fun saveFile(
        filename: String,
        content: String,
    ): Boolean {
        synchronized(fileLock) {
            return try {
                val f = getSafeFile(configDir, filename)
                if (f == null) {
                    Logger.e("Path traversal attempt detected during save: $filename")
                    return false
                }
                if (Files.isSymbolicLink(f.toPath())) {
                    Logger.e("Refusing symbolic-link config destination: $filename")
                    return false
                }
                if (content.utf8ByteLength() > MAX_CONFIG_FILE_SIZE) return false
                SecureFile.writeText(f, content)
                true
            } catch (e: Exception) {
                Logger.e("Failed to save file: $filename", e)
                false
            }
        }
    }

    private fun fileExists(filename: String): Boolean {
        synchronized(fileLock) {
            val f = getSafeFile(configDir, filename)
            return f != null && Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)
        }
    }

    private fun identityJson(): JSONObject {
        val identity = Config.getIdentityOverrides()
        return JSONObject()
            .put("template", identity.template ?: "")
            .put("imei", identity.imei ?: "")
            .put("imei2", identity.imei2 ?: "")
            .put("imsi", identity.imsi ?: "")
            .put("imsi2", identity.imsi2 ?: "")
            .put("iccid", identity.iccid ?: "")
            .put("iccid2", identity.iccid2 ?: "")
            .put("meid", identity.meid ?: "")
            .put("meid2", identity.meid2 ?: "")
            .put("phone_number", identity.phoneNumber ?: "")
            .put("phone_number2", identity.phoneNumber2 ?: "")
            .put("serial", identity.serial ?: "")
            .put("visible_sim_count", identity.visibleSimCount?.toString() ?: "")
            .put("visible_camera_count", identity.visibleCameraCount?.toString() ?: "")
    }

    private fun randomIdentityValue(field: String): String =
        when (field) {
            "imei", "imei2" -> RandomUtils.generateLuhn(15, "35")
            "imsi", "imsi2" -> RandomUtils.generateDigits(15, "310260")
            "iccid", "iccid2" -> RandomUtils.generateLuhn(20, "8901")
            "meid", "meid2" -> RandomUtils.generateHex(14)
            "phone_number", "phone_number2" -> "+1${RandomUtils.generateDigits(10)}"
            "serial" -> RandomUtils.generateRandomSerial(12)
            "visible_sim_count" -> RandomUtils.generateVisibleSimCount(allowZero = true)
            "visible_camera_count" ->
                RandomUtils.choose(listOf("1", "2", "2", "3", "3", "3", "4", "4", "4", "4")) ?: "2"
            else -> throw IllegalArgumentException("Unsupported random identity field")
        }

    private fun randomTemplateJson(): JSONObject? {
        val template = RandomUtils.choose(DeviceTemplateManager.listTemplates()) ?: return null
        return JSONObject()
            .put("id", template.id)
            .put("model", template.model)
            .put("manufacturer", template.manufacturer)
            .put("fingerprint", template.fingerprint)
            .put("securityPatch", template.securityPatch)
    }

    private fun randomIdentityJson(selection: String): JSONObject? {
        val normalized = selection.trim().lowercase()
        val json = JSONObject()

        fun copyTemplateIfAvailable(required: Boolean): Boolean {
            val template = randomTemplateJson()
            if (template == null) return !required
            val keys = template.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                json.put(key, template.get(key))
            }
            return true
        }

        fun putFields(vararg fields: String) {
            fields.forEach { field -> json.put(field, randomIdentityValue(field)) }
        }

        when (normalized) {
            "all" -> {
                copyTemplateIfAvailable(required = false)
                putFields(
                    "imei",
                    "imei2",
                    "imsi",
                    "imsi2",
                    "iccid",
                    "iccid2",
                    "meid",
                    "meid2",
                    "phone_number",
                    "phone_number2",
                    "serial",
                    "visible_sim_count",
                    "visible_camera_count",
                )
                json.put("visible_sim_count", RandomUtils.generateVisibleSimCount(allowZero = false))
            }
            "template" -> if (!copyTemplateIfAvailable(required = true)) return null
            "sim1" -> putFields("imei", "imsi", "iccid", "meid", "phone_number")
            "sim2" -> putFields("imei2", "imsi2", "iccid2", "meid2", "phone_number2")
            "telephony" -> {
                putFields(
                    "imei", "imsi", "iccid", "meid", "phone_number",
                    "imei2", "imsi2", "iccid2", "meid2", "phone_number2",
                    "visible_sim_count",
                )
                json.put("visible_sim_count", RandomUtils.generateVisibleSimCount(allowZero = false))
            }
            "device" -> putFields("serial")
            "hardware" -> putFields("visible_camera_count")
            "imei", "imei2", "imsi", "imsi2", "iccid", "iccid2", "meid", "meid2",
            "phone_number", "phone_number2", "serial", "visible_sim_count", "visible_camera_count" ->
                putFields(normalized)
            else -> throw IllegalArgumentException("Unsupported random identity field")
        }
        return json
    }

    private fun parseIdentityUpdates(json: String): Map<String, String?> {
        require(json.utf8ByteLength() <= MAX_IDENTITY_REQUEST_BYTES) {
            "Identity request is too large"
        }
        val obj = JSONObject(json)
        require(obj.length() in 1..IDENTITY_FIELDS.size) { "Identity request is empty or too large" }

        val updates = LinkedHashMap<String, String?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val field = keys.next()
            val buildVar = IDENTITY_FIELDS[field] ?: throw IllegalArgumentException("Unsupported identity field")
            val raw = obj.opt(field)
            require(raw is String) { "Identity fields must be strings" }
            val value =
                raw.trim().let { trimmed ->
                    when (buildVar) {
                        "TEMPLATE" -> trimmed.lowercase()
                        "ATTESTATION_ID_MEID", "ATTESTATION_ID_MEID2" -> trimmed.uppercase()
                        else -> trimmed
                    }
                }
            if (value.isEmpty()) {
                updates[buildVar] = null
            } else {
                require(Config.isValidBuildVarEntry(buildVar, value)) { "Invalid identity field" }
                updates[buildVar] = value
            }
        }
        return updates
    }

    private fun readIdentityLinesBounded(file: File): MutableList<String> =
        readUtf8FileSnapshotBounded(file, 0, MAX_CONFIG_FILE_SIZE)
            .lineSequence()
            .toMutableList()

    private fun saveIdentityUpdates(updates: Map<String, String?>): Boolean {
        synchronized(fileLock) {
            val file = File(configDir, "spoof_build_vars")
            val path = file.toPath()
            if (
                Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            ) {
                return false
            }
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && file.length() > MAX_CONFIG_FILE_SIZE) {
                return false
            }

            return try {
                val templateRequested = updates.containsKey("TEMPLATE")
                val directUpdates = LinkedHashMap(updates).apply { remove("TEMPLATE") }
                val templateLines = ArrayList<String>()
                if (templateRequested) {
                    updates["TEMPLATE"]?.let { templateName ->
                        val template = Config.getTemplate(templateName) ?: return false
                        templateLines += BUILD_IDENTITY_BLOCK_START
                        templateLines += "TEMPLATE=$templateName"
                        BUILD_IDENTITY_VAR_KEYS.forEach { key ->
                            template[key]?.let { value -> templateLines += "$key=$value" }
                        }
                        templateLines += BUILD_IDENTITY_BLOCK_END
                    }
                }
                val lines =
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            readIdentityLinesBounded(file)
                        } catch (error: IOException) {
                            Logger.w(
                                "Refusing oversized or unstable identity configuration: " +
                                    (error.message ?: error::class.simpleName),
                            )
                            return false
                        }
                    } else {
                        mutableListOf()
                    }
                val rewritten = ArrayList<String>(lines.size + directUpdates.size + templateLines.size)
                val processed = HashSet<String>(directUpdates.size)
                var insideBuildIdentityBlock = false

                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed == BUILD_IDENTITY_BLOCK_START) {
                        insideBuildIdentityBlock = true
                        if (!templateRequested) rewritten += line
                        return@forEach
                    }
                    if (insideBuildIdentityBlock) {
                        if (trimmed == BUILD_IDENTITY_BLOCK_END) {
                            insideBuildIdentityBlock = false
                            if (!templateRequested) rewritten += line
                        } else if (!templateRequested) {
                            rewritten += line
                        }
                        return@forEach
                    }
                    val separator = if (trimmed.startsWith("#")) -1 else trimmed.indexOf('=')
                    val key = if (separator > 0) trimmed.substring(0, separator).trim() else ""
                    if (templateRequested && key == "TEMPLATE") {
                        return@forEach
                    }
                    if (key in directUpdates) {
                        if (processed.add(key)) {
                            directUpdates[key]?.let { value -> rewritten += "$key=$value" }
                        }
                    } else {
                        rewritten += line
                    }
                }
                require(!insideBuildIdentityBlock) { "Unterminated managed build identity block" }
                directUpdates.forEach { (key, value) ->
                    if (processed.add(key) && value != null) rewritten += "$key=$value"
                }
                if (templateRequested && templateLines.isNotEmpty()) {
                    if (rewritten.isNotEmpty() && rewritten.last().isNotBlank()) rewritten += ""
                    rewritten += templateLines
                }

                val content =
                    if (rewritten.isEmpty()) {
                        ""
                    } else {
                        rewritten.joinToString("\n", postfix = "\n")
                    }
                if (!validateContent("spoof_build_vars", content)) return false
                SecureFile.writeText(file, content)
                Config.updateBuildVars(file)
                true
            } catch (error: Exception) {
                Logger.e("Failed to save identity configuration", error)
                false
            }
        }
    }

    private fun listKeyboxes(): List<String> =
    StoredKeyboxInventory.list(configDir)
        .map { it.filename }
        .distinct()

    private fun keyboxInventoryJson(): String {
        val array = JSONArray()
        StoredKeyboxInventory.list(configDir).forEach { source ->
            array.put(
                JSONObject()
                    .put("id", source.id)
                    .put("scope", source.scope.apiValue)
                    .put("filename", source.filename)
                    .put("type", if (source.isCbox) "cbox" else "xml")
                    .put("certificate_serial", CertHack.getDeviceCertificateSerial(source.filename) ?: ""),
            )
        }
        return array.toString()
    }

    private enum class KeyboxUploadValidation {
        VALID,
        INVALID,
        REVOCATION_UNAVAILABLE,
        BACKEND_UNAVAILABLE,
    }

    private fun validateUploadedKeyboxXml(
        bytes: ByteArray,
        filename: String,
    ): KeyboxUploadValidation {
        return try {
            val keyboxes = KeyboxLoader.parse(bytes.copyOf(), filename)
            if (keyboxes.isEmpty()) return KeyboxUploadValidation.INVALID
            val allValid =
                crlFetcher?.let { legacyFetcher ->
                    val revoked = legacyFetcher() ?: return KeyboxUploadValidation.REVOCATION_UNAVAILABLE
                    keyboxes.all { KeyboxVerifier.verifyKeyboxLegacy(it, revoked) == KeyboxVerifier.Status.VALID }
                } ?: run {
                    val revoked = KeyboxVerifier.fetchCrl()
                        ?: return KeyboxUploadValidation.REVOCATION_UNAVAILABLE
                    keyboxes.all { KeyboxVerifier.verifyKeybox(it, revoked) == KeyboxVerifier.Status.VALID }
                }
            if (allValid) KeyboxUploadValidation.VALID else KeyboxUploadValidation.INVALID
        } catch (_: RustBackendUnavailableException) {
            KeyboxUploadValidation.BACKEND_UNAVAILABLE
        } catch (_: Exception) {
            KeyboxUploadValidation.INVALID
        }
    }

    private fun validateUploadedKeyboxXml(
        content: String,
        filename: String,
    ): KeyboxUploadValidation =
        validateUploadedKeyboxXml(content.toByteArray(Charsets.UTF_8), filename)

    private fun keyboxValidationError(validation: KeyboxUploadValidation): Response? =
        when (validation) {
            KeyboxUploadValidation.VALID -> null
            KeyboxUploadValidation.INVALID ->
                secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Revoked or invalid keybox")
            KeyboxUploadValidation.REVOCATION_UNAVAILABLE ->
                secureResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    "text/plain",
                    "Revocation service unavailable; keybox was not saved",
                )
            KeyboxUploadValidation.BACKEND_UNAVAILABLE ->
                secureResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    "text/plain",
                    "Rust backend unavailable; keybox was not saved",
                )
        }

    private fun getModuleDir(): File {
        val paths =
            listOf(
                "/data/adb/modules/cleverestricky",
                "/data/adb/ksu/modules/cleverestricky",
                "/data/adb/ap/modules/cleverestricky",
            )
        for (p in paths) {
            val f = File(p)
            if (f.exists() && f.isDirectory) return f
        }
        return File("/data/adb/modules/cleverestricky")
    }

    private fun readBytesLimited(
        input: InputStream,
        maxBytes: Int,
    ): ByteArray {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        val output = FastByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        return try {
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count > maxBytes - total) throw IOException("Input exceeds limit")
                output.write(buffer, 0, count)
                total += count
            }
            output.toByteArray()
        } finally {
            buffer.fill(0)
            output.wipe()
        }
    }

    private fun readTextLimited(
        input: InputStream,
        maxBytes: Int,
    ): String {
        val bytes = readBytesLimited(input, maxBytes)
        return try {
            String(bytes, Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }

    private fun readFileBytesLimited(
        file: File,
        maxBytes: Int,
    ): ByteArray {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Refusing non-regular file")
        }
        return Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use {
            readBytesLimited(it, maxBytes)
        }
    }

    private fun readCommandOutput(command: Array<String>): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val reader =
            FutureTask<String> {
                process.inputStream.use { readTextLimited(it, MAX_LOG_BYTES) }
            }
        Thread(reader, "cleverestricky-log-reader").apply {
            isDaemon = true
            start()
        }
        return try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            }
            runCatching { reader.get(2, TimeUnit.SECONDS) }.getOrDefault("")
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            if (!reader.isDone) reader.cancel(true)
        }
    }

    private fun isValidSetting(name: String): Boolean {
        return name in WEB_UI_SETTINGS
    }

    private fun isValidProfile(name: String): Boolean = name.lowercase() in setOf("maximum", "daily", "minimal", "default")

    private fun toggleFile(
        filename: String,
        enable: Boolean,
    ): Boolean {
        if (!isValidSetting(filename)) return false
        synchronized(fileLock) {
            val f = getSafeFile(configDir, filename)
            if (f == null) return false
            return try {
                val path = f.toPath()
                if (Files.isSymbolicLink(path)) return false
                if (enable) {
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
                    } else {
                        SecureFile.touch(f, 384)
                    }
                } else {
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        return false
                    }
                    Files.deleteIfExists(path)
                }
                Config.refreshRuntimeSetting(filename)
                true
            } catch (e: Exception) {
                Logger.e("Failed to toggle setting: $filename", e)
                false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getEnvironmentInfo(): String {
        if (File("/data/adb/ksu").exists() || File("/data/adb/ksud").exists()) return "KernelSU"
        if (File("/data/adb/apatch").exists()) return "APatch"
        if (File("/sbin/magisk").exists() || File("/data/adb/magisk").exists()) return "Unsupported (Magisk)"
        return "Unknown Root"
    }

    private data class CpuSample(val processTicks: Long, val totalTicks: Long)

    private fun readCpuSample(): CpuSample? {
        return try {
            val processStat = File("/proc/self/stat").bufferedReader().use { it.readLine() } ?: return null
            val systemStat = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
            val processTicks = parseProcessCpuTicks(processStat) ?: return null
            val totalTicks = parseTotalCpuTicks(systemStat) ?: return null
            CpuSample(processTicks, totalTicks)
        } catch (_: Exception) {
            null
        }
    }

    private var lastCpuSample: CpuSample? = readCpuSample()
    private var lastCpuSampleNanos: Long = System.nanoTime()
    private var lastCpuUsage: Double = 0.0
    private val availableProcessorCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private fun getCpuUsagePercent(): Double {
        val now = System.nanoTime()
        val cached = synchronized(this) {
            if (now - lastCpuSampleNanos in 0 until CPU_SAMPLE_MIN_INTERVAL_NANOS) lastCpuUsage else null
        }
        if (cached != null) return cached

        val current = readCpuSample()
        
        return synchronized(this) {
            val previous = lastCpuSample
            if (current != null && previous != null &&
                current.totalTicks > previous.totalTicks &&
                current.processTicks >= previous.processTicks
            ) {
                val deltaProcess = current.processTicks - previous.processTicks
                val deltaSystem = current.totalTicks - previous.totalTicks
                lastCpuUsage =
                    ((deltaProcess.toDouble() / deltaSystem.toDouble()) * 100.0 * availableProcessorCount)
                        .coerceIn(0.0, availableProcessorCount * 100.0)
            }
            if (current != null) {
                lastCpuSample = current
                lastCpuSampleNanos = now
            }
            lastCpuUsage
        }
    }

    private fun getRamUsageKb(): Long {
        try {
            val buffer = ByteArray(8192)
            java.io.FileInputStream("/proc/self/status").use { fis ->
                val read = fis.read(buffer)
                if (read > 0) {
                    var pos = 0
                    while (pos < read) {
                        // Check if line starts with "VmRSS:"
                        if (pos + 6 < read &&
                            buffer[pos] == 'V'.code.toByte() &&
                            buffer[pos + 1] == 'm'.code.toByte() &&
                            buffer[pos + 2] == 'R'.code.toByte() &&
                            buffer[pos + 3] == 'S'.code.toByte() &&
                            buffer[pos + 4] == 'S'.code.toByte() &&
                            buffer[pos + 5] == ':'.code.toByte()
                        ) {
                            pos += 6
                            // Skip spaces
                            while (pos < read && (buffer[pos] == ' '.code.toByte() || buffer[pos] == '\t'.code.toByte())) {
                                pos++
                            }
                            var kb = 0L
                            while (pos < read && buffer[pos] >= '0'.code.toByte() && buffer[pos] <= '9'.code.toByte()) {
                                kb = kb * 10 + (buffer[pos] - '0'.code.toByte())
                                pos++
                            }
                            return kb
                        }
                        // Skip to next line
                        while (pos < read && buffer[pos] != '\n'.code.toByte()) {
                            pos++
                        }
                        pos++
                    }
                }
            }
        } catch (e: Exception) {
        }
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024
    }

    private fun readProcessStartTicks(pid: Int): Long? {
        if (pid <= 0) return null
        val statFile = File("/proc/$pid/stat")
        return runCatching {
            if (!Files.isRegularFile(statFile.toPath(), LinkOption.NOFOLLOW_LINKS) || statFile.length() > 16 * 1024) {
                return@runCatching null
            }
            val stat =
                Files.newInputStream(statFile.toPath(), LinkOption.NOFOLLOW_LINKS).use {
                    readTextLimited(it, 16 * 1024)
                }
            val commandEnd = stat.lastIndexOf(')')
            if (commandEnd < 0) return@runCatching null
            stat.substring(commandEnd + 1)
                .trim()
                .splitToSequence(' ')
                .filter { it.isNotEmpty() }
                .elementAtOrNull(19)
                ?.toLongOrNull()
        }.getOrNull()
    }

    private fun readNativeRuntimeStatus(): JSONObject {
        val unavailable = JSONObject().put("state", "unavailable").put("alive", false)
        val statusFile = File(configDir, "native_runtime_status")
        if (!Files.isRegularFile(statusFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return unavailable
        }
        return runCatching {
            val values = LinkedHashMap<String, String>()
            readUtf8FileSnapshotBounded(statusFile, 1, 4096).lineSequence().take(16).forEach { line ->
                val separator = line.indexOf('=')
                if (separator > 0 && separator < line.lastIndex) {
                    values[line.substring(0, separator)] = line.substring(separator + 1)
                }
            }
            val version =
                values["version"]?.toIntOrNull()?.takeIf { it in 1..2 }
                    ?: return@runCatching unavailable
            val state =
                values["state"]?.takeIf { it in setOf("starting", "active", "failed") }
                    ?: return@runCatching unavailable
            val pid = values["pid"]?.toIntOrNull()?.takeIf { it > 0 } ?: 0
            val recordedStartTicks = values["start_ticks"]?.toLongOrNull()?.takeIf { it > 0 } ?: 0L
            val currentStartTicks = readProcessStartTicks(pid)
            val alive = pid > 0 && recordedStartTicks > 0 && currentStartTicks == recordedStartTicks
            val knownFailures =
                setOf(
                    "none",
                    "request_validation",
                    "target_attach",
                    "symbol_resolution",
                    "descriptor_transfer",
                    "library_load",
                    "entry_activation",
                    "target_detach",
                    "panic",
                    "unknown",
                )
            val failure =
                if (version >= 2) {
                    values["failure"]?.takeIf { it in knownFailures } ?: "unknown"
                } else if (state == "failed") {
                    "unknown"
                } else {
                    "none"
                }
            JSONObject()
                .put("state", state)
                .put("alive", alive)
                .put("failure", failure)
                .put("pid", pid)
                .put("entry", values["entry"] ?: "unknown")
                .put("timestamp_ms", values["timestamp_ms"]?.toLongOrNull() ?: 0L)
        }.getOrElse { unavailable }
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            serveInternal(session, false)
        } catch (e: Exception) {
            Logger.e("WebServer: Error handling request", e)
            secureResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Internal Server Error")
        }
    }

    internal fun serveBridge(session: IHTTPSession): Response {
        return try {
            serveInternal(session, true)
        } catch (e: Exception) {
            Logger.e("WebServer: Native bridge request failed", e)
            secureResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Internal Server Error")
        }
    }

    private fun serveInternal(
        session: IHTTPSession,
        trustedBridge: Boolean,
    ): Response {
        val uri = session.uri
        val method = session.method
        val headers = session.headers

        if (isTampered && (trustedBridge || uri.startsWith("/api/"))) {
            return secureResponse(Response.Status.FORBIDDEN, "text/plain", "Module verification failed")
        }

        val contentLengthStr = headers["content-length"] ?: headers["Content-Length"]
        if (contentLengthStr != null) {
            val contentLength =
                contentLengthStr.toLongOrNull()
                    ?: return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid Content-Length")
            if (contentLength < 0) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid Content-Length")
            }
            val maximumRequestSize = if (trustedBridge) MAX_NATIVE_REQUEST_SIZE else MAX_UPLOAD_SIZE
            if (contentLength > maximumRequestSize) {
                Logger.e("WebServer: Request too large, blocking to prevent resource exhaustion (Firewall)")
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Payload Too Large")
            }
        }

        if (!trustedBridge) {
            if (!isSafeHost(headers["host"])) {
                return secureResponse(Response.Status.FORBIDDEN, "text/plain", "Invalid Host header")
            }

            var ip = session.remoteIpAddress ?: "unknown"
            if (ip.startsWith("/")) ip = ip.substring(1)
            if (isRateLimited(ip)) return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Too Many Requests")

            val origin = headers["origin"]
            val host = headers["host"]
            if (origin != null && host != null) {
                val allowedOrigin = "http://$host"
                val allowedSecureOrigin = "https://$host"
                if (origin != allowedOrigin && origin != allowedSecureOrigin) {
                    return secureResponse(
                        Response.Status.FORBIDDEN,
                        "text/plain",
                        "CSRF Forbidden",
                    )
                }
            }
        }

        if (uri == "/" || uri == "/index.html") {
            if (isTampered) {
                val warningHtml =
                    """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="utf-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
                        <title>Tamper Warning</title>
                        <style>
                            :root { --bg: #fff3f3; --text: #d00; --link: #b00; }
                            @media (prefers-color-scheme: dark) {
                                :root { --bg: #1a0505; --text: #ff6b6b; --link: #ff9999; }
                            }
                            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; padding: 20px; background: var(--bg); color: var(--text); margin: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 100vh; text-align: center; }
                            h1 { font-size: 1.5em; margin-bottom: 15px; }
                            p { font-size: 1em; line-height: 1.5; max-width: 600px; }
                            a { color: var(--link); word-break: break-all; }
                        </style>
                    </head>
                    <body>
                        <h1>Module Modified / Tampering Detected</h1>
                        <p>This module has been modified and is therefore dangerous. Please use the official version from GitHub:</p>
                        <p><a href="https://github.com/tryigit/CleveresTricky/">https://github.com/tryigit/CleveresTricky/</a></p>
                    </body>
                    </html>
                    """.trimIndent()
                return secureResponse(Response.Status.FORBIDDEN, "text/html", warningHtml.toByteArray())
            }
            return secureResponse(Response.Status.OK, "text/html", htmlBytes)
        }

        if (method == Method.POST || method == Method.PUT) {
            val lenStr = headers["content-length"]
            if (lenStr != null) {
                try {
                    val contentLen = lenStr.toLong()
                    val contentType = headers["content-type"] ?: ""
                    val isMultipart = contentType.contains("multipart/form-data", ignoreCase = true)
                    val maxSize =
                        when {
                            !isMultipart -> MAX_BODY_SIZE
                            trustedBridge -> MAX_NATIVE_REQUEST_SIZE
                            else -> MAX_UPLOAD_SIZE
                        }
                    if (contentLen > maxSize) return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Payload too large")
                } catch (e: NumberFormatException) {
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid Content-Length")
                }
            } else {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Content-Length required")
            }
        }

        if (!trustedBridge) {
            var authToken = headers["x-auth-token"]
            if (authToken == null) {
                val authHeader = headers["authorization"]
                if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
                    authToken = authHeader.substring(7)
                }
            }
            if (authToken == null) authToken = getParam(session, "token")

            if (
                authToken == null ||
                authToken.length != token.length ||
                !MessageDigest.isEqual(
                    token.toByteArray(Charsets.US_ASCII),
                    authToken.toByteArray(Charsets.US_ASCII),
                )
            ) {
                return secureResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized")
            }
        }

        return handlePolicyAndConfigRoutes(session, uri, method, headers, trustedBridge)
            ?: handleKeyboxRoutes(session, uri, method, headers, trustedBridge)
            ?: handleServerRoutes(session, uri, method, headers, trustedBridge)
            ?: handleIdentityAndTemplateRoutes(session, uri, method, headers, trustedBridge)
            ?: handleSystemAndAppRoutes(session, uri, method, headers, trustedBridge)
            ?: secureResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }

    private fun handlePolicyAndConfigRoutes(
        session: IHTTPSession,
        uri: String,
        method: Method,
        headers: Map<String, String>,
        trustedBridge: Boolean
    ): Response? {
        if (uri == "/api/policy_state" || uri == "/api/effective_state" || uri == "/api/profile_v2") {
            if (method == Method.POST) {
                val files = HashMap<String, String>()
                try {
                    session.parseBody(files)
                } catch (error: Exception) {
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
                }
            }
            PolicyApi.serve(session)?.let { response ->
                addSecurityHeaders(response)
                return response
            }
        }

        if (uri == "/api/config" && method == Method.GET) {
            val json = JSONObject()
            json.put("system_locale", currentSystemLocaleTag())
            WEB_UI_SETTINGS.forEach { setting -> json.put(setting, fileExists(setting)) }
            val files = JSONArray()
            files.put("keybox.xml")
            files.put("target.txt")
            files.put("identity_target.txt")
            files.put("security_patch.txt")
            files.put("spoof_build_vars")
            files.put("app_config")
            files.put("templates.json")
            files.put("drm_packages.txt")
            files.put("boot_props_mode")
            json.put("files", files)
            Config.ensureFreshKeyboxes()
            val keyboxCount = runCatching { CertHack.getKeyboxSourceCount() }.getOrDefault(0)
            json.put("keybox_count", keyboxCount)
            val templates = JSONArray()
            Config.getTemplateNames().forEach { name -> templates.put(name) }
            json.put("templates", templates)
            return secureResponse(Response.Status.OK, "application/json", json.toString())
        }

        if (uri == "/api/apply_profile" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val profileName = getParam(session, "profile")
            if (profileName != null && isValidProfile(profileName)) {
                synchronized(fileLock) {
                    try {
                        Config.applyProfile(profileName)
                        return secureResponse(Response.Status.OK, "text/plain", "Profile Applied")
                    } catch (e: Exception) {
                        Logger.e("Failed to apply profile", e)
                        return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
                    }
                }
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing profile")
        }

        return null
    }

    private fun handleKeyboxRoutes(
        session: IHTTPSession,
        uri: String,
        method: Method,
        headers: Map<String, String>,
        trustedBridge: Boolean
    ): Response? {
        if (uri == "/api/keyboxes" && method == Method.GET) {
            Config.ensureFreshKeyboxes()
            val keyboxes = listKeyboxes()
            val array = JSONArray(keyboxes)
            return secureResponse(Response.Status.OK, "application/json", array.toString())
        }

        if (uri == "/api/keybox_inventory" && method == Method.GET) {
            return try {
                Config.ensureFreshKeyboxes()
                secureResponse(Response.Status.OK, "application/json", keyboxInventoryJson())
            } catch (error: Exception) {
                Logger.e("Failed to enumerate stored keyboxes", error)
                secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to enumerate stored keyboxes")
            }
        }

        if (uri == "/api/cbox_status" && method == Method.GET) {
            Config.ensureFreshKeyboxes()
            val json = JSONObject()
            val locked = JSONArray()
            CboxManager.getLockedFiles().forEach { locked.put(it) }
            json.put("locked", locked)
            val unlocked = JSONArray()
            CboxManager.getUnlockedKeyboxes().forEach { k ->
                // Only show distinct filenames
                if (!k.filename.startsWith("server_")) unlocked.put(k.filename)
            }
            json.put("unlocked", unlocked)

            val servers = JSONArray()
            ServerManager.getServers().forEach { s ->
                val obj = JSONObject()
                obj.put("id", s.id)
                obj.put("status", s.lastStatus)
                servers.put(obj)
            }
            json.put("server_status", servers)

            return secureResponse(Response.Status.OK, "application/json", json.toString())
        }

        if (uri == "/api/upload_keybox" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val filename = getParam(session, "filename")
            val content = getParam(session, "content")
            val tmpFilePath = map["file"]
            if (tmpFilePath != null) {
                val originalName = getParam(session, "filename") ?: "upload.bin"
                val storedName = normalizeKeyboxUploadFilename(originalName)
                val tmpFile = File(tmpFilePath)
                val extension = storedName.substringAfterLast('.', "").lowercase()
                val uploadLimit =
                    if (extension == "cbox") {
                        MAX_CBOX_UPLOAD_SIZE
                    } else {
                        MAX_KEYBOX_XML_UPLOAD_SIZE
                    }
                if (!Files.isRegularFile(tmpFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    tmpFile.length() !in 1..uploadLimit
                ) {
                    if (tmpFile.exists()) tmpFile.delete()
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid upload size")
                }
                if (!isValidKeyboxFilename(storedName) || (extension != "xml" && extension != "cbox")) {
                    tmpFile.delete()
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid upload filename")
                }
                val bytes = readFileBytesLimited(tmpFile, uploadLimit.toInt())
                try {
                    synchronized(fileLock) {
                        val keyboxDir = File(configDir, "keyboxes")
                        SecureFile.mkdirs(keyboxDir, 448)
                        val dest = getSafeFile(keyboxDir, storedName)
                        if (dest == null) {
                            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid upload path")
                        }
                        if (extension == "cbox") {
                            if (!CboxDecryptor.hasSupportedEnvelopeHeader(bytes)) {
                                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid CBOX envelope")
                            }
                            SecureFile.writeBytes(dest, bytes)
                            CboxManager.refresh()
                        } else {
                            keyboxValidationError(validateUploadedKeyboxXml(bytes, storedName))?.let { return it }
                            SecureFile.writeBytes(dest, bytes)
                        }
                        if (!updateKeyboxesFromConfiguredRevocationSource()) {
                            return keyboxActivationFailureResponse()
                        }
                        val count = CertHack.getKeyboxSourceCount()
                        val response = JSONObject()
                        response.put("status", "ok")
                        response.put("filename", storedName)
                        response.put("keybox_count", count)
                        return secureResponse(Response.Status.OK, "application/json", response.toString())
                    }
                } finally {
                    bytes.fill(0)
                    if (tmpFile.exists() && !tmpFile.delete()) Logger.w("Failed to clean upload temp file")
                }
            }

            val storedName = filename?.let(::normalizeKeyboxUploadFilename)
            if (
                storedName != null &&
                content != null &&
                storedName.endsWith(".xml", ignoreCase = true) &&
                isValidKeyboxFilename(storedName)
            ) {
                synchronized(fileLock) {
                    keyboxValidationError(validateUploadedKeyboxXml(content, storedName))?.let { return it }
                    val keyboxDir = File(configDir, "keyboxes")
                    SecureFile.mkdirs(keyboxDir, 448)
                    val file = getSafeFile(keyboxDir, storedName)
                    if (file == null) {
                        return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Path traversal attempt detected")
                    }
                    try {
                        SecureFile.writeText(file, content)
                        if (!updateKeyboxesFromConfiguredRevocationSource()) {
                            return keyboxActivationFailureResponse()
                        }
                        val count = CertHack.getKeyboxSourceCount()
                        val response = JSONObject()
                        response.put("status", "ok")
                        response.put("filename", storedName)
                        response.put("keybox_count", count)
                        return secureResponse(Response.Status.OK, "application/json", response.toString())
                    } catch (e: Exception) {
                        Logger.e("Failed to save keybox", e)
                        return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to save keybox")
                    }
                }
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid request")
        }

        if (uri == "/api/delete_keybox" && method == Method.POST) {
        val map = HashMap<String, String>()
        try {
            session.parseBody(map)
        } catch (error: Exception) {
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
        }
        val filename = getParam(session, "filename")
        val scope = getParam(session, "scope") ?: "keyboxes"
        if (filename != null) {
            synchronized(fileLock) {
                val source = StoredKeyboxInventory.resolve(configDir, scope, filename)
                    ?: return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid keybox source")
                if (source.file.delete()) {
                    if (source.isCbox) {
                        Files.deleteIfExists(File(source.file.parentFile, "${source.filename}.cache").toPath())
                        CboxManager.refresh()
                    }
                    if (!updateKeyboxesFromConfiguredRevocationSource()) return keyboxActivationFailureResponse()
                    return secureResponse(Response.Status.OK, "text/plain", "Deleted")
                }
                return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to delete file")
            }
        }
        return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid filename")
    }

    if (uri == "/api/delete_keyboxes" && method == Method.POST) {
        val map = HashMap<String, String>()
        try {
            session.parseBody(map)
        } catch (error: Exception) {
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
        }
        val rawItems = getParam(session, "items")
            ?: return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing items")
        return synchronized(fileLock) {
            try {
                val items = JSONArray(rawItems)
                if (items.length() !in 1..StoredKeyboxInventory.MAX_STORED_SOURCES) {
                    return@synchronized secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid item count")
                }
                var deleted = 0
                var failed = 0
                var cboxChanged = false
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index)
                    val filename = item?.optString("filename").orEmpty()
                    val scope = item?.optString("scope").orEmpty()
                    val source = StoredKeyboxInventory.resolve(configDir, scope, filename)
                    if (source == null || !source.file.delete()) {
                        failed++
                        continue
                    }
                    deleted++
                    if (source.isCbox) {
                        Files.deleteIfExists(File(source.file.parentFile, "${source.filename}.cache").toPath())
                        cboxChanged = true
                    }
                }
                if (cboxChanged) CboxManager.refresh()
                if (!updateKeyboxesFromConfiguredRevocationSource()) return@synchronized keyboxActivationFailureResponse()
                secureResponse(
                    if (failed == 0) Response.Status.OK else Response.Status.INTERNAL_ERROR,
                    "application/json",
                    JSONObject().put("deleted", deleted).put("failed", failed).toString(),
                )
            } catch (error: Exception) {
                Logger.e("Failed to bulk-delete keyboxes", error)
                secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid keybox selection")
            }
        }
    }

    if (uri == "/api/verify_keyboxes" && method == Method.POST) {
            try {
                synchronized(fileLock) {
                    val results = crlFetcher?.let { KeyboxVerifier.verifyLegacy(configDir, it) }
                        ?: KeyboxVerifier.verify(configDir)
                    val json = createKeyboxVerificationJson(results)
                    return secureResponse(Response.Status.OK, "application/json", json)
                }
            } catch (e: Exception) {
                Logger.e("Failed to verify keyboxes", e)
                return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
            }
        }

        return null
    }

    private fun handleServerRoutes(
        session: IHTTPSession,
        uri: String,
        method: Method,
        headers: Map<String, String>,
        trustedBridge: Boolean
    ): Response? {
        if (uri == "/api/servers" && method == Method.GET) {
            val json = JSONArray()
            ServerManager.getServers().forEach { s ->
                val obj = JSONObject()
                obj.put("id", s.id)
                obj.put("name", s.name)
                obj.put("url", s.url)
                obj.put("priority", s.priority)
                obj.put("enabled", s.enabled)
                obj.put("authType", s.authType)
                obj.put("autoRefresh", s.autoRefresh)
                obj.put("refreshIntervalHours", s.refreshIntervalHours)
                obj.put("lastStatus", s.lastStatus)
                obj.put("lastChecked", s.lastChecked)
                obj.put("lastAuthor", s.lastAuthor)
                json.put(obj)
            }
            return secureResponse(Response.Status.OK, "application/json", json.toString())
        }

        if (uri == "/api/server/add" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val jsonStr = getParam(session, "data")
            if (jsonStr != null) {
                try {
                    val obj = JSONObject(jsonStr)
                    val server =
                        ServerManager.ServerConfig(
                            id = obj.optString("id").ifEmpty { UUID.randomUUID().toString() },
                            name = obj.getString("name"),
                            url = obj.getString("url"),
                            priority = obj.optInt("priority", 0),
                            enabled = obj.optBoolean("enabled", true),
                            authType = obj.getString("authType"),
                            authData = obj.optJSONObject("authData") ?: JSONObject(),
                            autoRefresh = obj.optBoolean("autoRefresh", true),
                            refreshIntervalHours = obj.optInt("refreshIntervalHours", 24),
                            contentPassword = obj.optString("contentPassword").ifEmpty { null },
                            contentPublicKey = obj.optString("contentPublicKey").ifEmpty { null },
                        )
                    ServerManager.addServer(server)
                    Config.updateKeyBoxes()
                    return secureResponse(Response.Status.OK, "text/plain", "Saved")
                } catch (e: Exception) {
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid JSON")
                }
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing data")
        }

        if (uri == "/api/server/delete" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val id = getParam(session, "id")
            if (id != null) {
                if (ServerManager.removeServer(id)) {
                    if (!updateKeyboxesFromConfiguredRevocationSource()) {
                        return keyboxActivationFailureResponse()
                    }
                    return secureResponse(Response.Status.OK, "text/plain", "Deleted")
                }
                return secureResponse(Response.Status.NOT_FOUND, "text/plain", "Server not found")
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing id")
        }

        if (uri == "/api/server/refresh" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val id = getParam(session, "id")
            if (id != null) {
                val s = ServerManager.findServer(id)
                if (s != null) {
                    val refreshed = ServerManager.fetchFromServer(s)
                    if (!updateKeyboxesFromConfiguredRevocationSource()) {
                        return keyboxActivationFailureResponse()
                    }
                    if (refreshed) {
                        return secureResponse(Response.Status.OK, "text/plain", "Refreshed")
                    } else {
                        return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Fetch Failed: ${s.lastStatus}")
                    }
                }
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing id")
        }

        return null
    }

    private fun handleIdentityAndTemplateRoutes(
        session: IHTTPSession,
        uri: String,
        method: Method,
        headers: Map<String, String>,
        trustedBridge: Boolean
    ): Response? {
        if (uri == "/api/kernel_identity" && method == Method.GET) {
            return secureResponse(Response.Status.OK, "application/json", KernelIdentityManager.json().toString())
        }

        if (uri == "/api/kernel_identity" && method == Method.POST) {
            val body = HashMap<String, String>()
            return try {
                session.parseBody(body)
                val data = getParam(session, "data") ?: throw IllegalArgumentException("Missing kernel identity data")
                require(data.utf8ByteLength() <= 4096) { "Kernel identity request is too large" }
                KernelIdentityManager.save(data)
                val applied = KeystoreInterceptor.refreshKernelIdentity()
                secureResponse(Response.Status.OK, "application/json", KernelIdentityManager.json().put("applied", applied).toString())
            } catch (error: IllegalArgumentException) {
                secureResponse(Response.Status.BAD_REQUEST, "text/plain", error.message ?: "Invalid kernel identity data")
            } catch (error: Exception) {
                Logger.e("Failed to save kernel identity configuration", error)
                secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Kernel identity configuration was not saved")
            }
        }

        if (uri == "/api/templates" && method == Method.GET) {
            val templates = DeviceTemplateManager.listTemplates()
            val array = JSONArray()
            templates.forEach { t ->
                val obj = JSONObject()
                obj.put("id", t.id)
                obj.put("model", t.model)
                obj.put("manufacturer", t.manufacturer)
                obj.put("fingerprint", t.fingerprint)
                obj.put("securityPatch", t.securityPatch)
                array.put(obj)
            }
            return secureResponse(Response.Status.OK, "application/json", array.toString())
        }

        if (uri == "/api/identity" && method == Method.GET) {
            return secureResponse(Response.Status.OK, "application/json", identityJson().toString())
        }

        if (uri == "/api/identity" && method == Method.POST) {
            val body = HashMap<String, String>()
            try {
                session.parseBody(body)
                val data = getParam(session, "data") ?: throw IllegalArgumentException("Missing identity data")
                val updates = parseIdentityUpdates(data)
                if (saveIdentityUpdates(updates)) {
                    return secureResponse(Response.Status.OK, "application/json", identityJson().toString())
                }
            } catch (error: IllegalArgumentException) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", error.message ?: "Invalid identity data")
            } catch (error: Exception) {
                Logger.e("Failed to process identity request", error)
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid identity data")
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Identity configuration was not saved")
        }

        if (uri == "/api/random_identity" && method == Method.GET) {
            val selection = getParam(session, "field")?.trim()?.lowercase().orEmpty().ifEmpty { "all" }
            return try {
                val json = randomIdentityJson(selection)
                    ?: return secureResponse(Response.Status.NOT_FOUND, "text/plain", "No templates found")
                secureResponse(Response.Status.OK, "application/json", json.toString())
            } catch (error: IllegalArgumentException) {
                secureResponse(Response.Status.BAD_REQUEST, "text/plain", error.message ?: "Invalid random identity request")
            }
        }

        if (uri == "/api/auto_identity" && method == Method.POST) {
            return try {
                val resolved = autoIdentityFetcher()
                AutoIdentityPersistence.save(configDir, resolved).getOrThrow()
                val json =
                    JSONObject()
                        .put("model", resolved.model)
                        .put("product", resolved.product)
                        .put("device", resolved.device)
                        .put("fingerprint", resolved.fingerprint)
                        .put("build_id", resolved.buildId)
                        .put("incremental", resolved.incremental)
                        .put("release", resolved.release ?: "")
                        .put("security_patch", resolved.securityPatch)
                        .put("security_patch_estimated", resolved.securityPatchEstimated)
                secureResponse(Response.Status.OK, "application/json", json.toString())
            } catch (error: IOException) {
                Logger.e("Auto Identity source lookup failed", error)
                secureResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Auto Identity source is unavailable")
            } catch (error: Exception) {
                Logger.e("Auto Identity failed", error)
                secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Auto Identity failed")
            }
        }

        return null
    }

    private fun handleSystemAndAppRoutes(
        session: IHTTPSession,
        uri: String,
        method: Method,
        headers: Map<String, String>,
        trustedBridge: Boolean
    ): Response? {
        if (uri == "/api/unlock_cbox" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val filename = getParam(session, "filename")
            val password = getParam(session, "password")
            val pubKey = getParam(session, "public_key")

            if (filename != null && password != null) {
                if (CboxManager.unlock(filename, password, pubKey)) {
                    if (!updateKeyboxesFromConfiguredRevocationSource()) {
                        return keyboxActivationFailureResponse()
                    }
                    return secureResponse(Response.Status.OK, "text/plain", "Unlocked")
                } else {
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Unlock failed")
                }
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing params")
        }

        if (uri == "/api/packages" && method == Method.GET) {
            return try {
                val sortedPackages = Config.getInstalledPackages()
                val array = JSONArray(sortedPackages)
                secureResponse(Response.Status.OK, "application/json", array.toString())
            } catch (e: Exception) {
                Logger.e("Failed to list packages", e)
                secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to list packages")
            }
        }

        if (uri == "/api/app_config_structured" && method == Method.GET) {
            val file = File(configDir, "app_config")
            val array = JSONArray()
            synchronized(fileLock) {
                if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                ) {
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid app configuration")
                }
                if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    val text =
                        try {
                            readUtf8FileSnapshotBounded(file, 0, MAX_CONFIG_FILE_SIZE)
                        } catch (_: IOException) {
                            return secureResponse(
                                Response.Status.BAD_REQUEST,
                                "text/plain",
                                "App configuration is invalid or too large",
                            )
                        }
                    var ruleCount = 0
                    text.lineSequence().forEach { line ->
                        if (line.isNotBlank() && !line.startsWith("#")) {
                            if (++ruleCount > MAX_APP_CONFIG_RULES) {
                                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Too many app rules")
                            }
                            val trimmed = line.trim()
                            if (trimmed.isEmpty()) return@forEach

                            val len = trimmed.length
                            var idx = 0

                            var start = idx
                            while (idx < len && !trimmed[idx].isWhitespace()) idx++
                            val pkg = trimmed.substring(start, idx)

                            var tmpl = ""
                            var kb = ""
                            var privacy = Config.AppPrivacyMode.INHERIT.configValue
                            var autoIdentity = "inherit"

                            while (idx < len && trimmed[idx].isWhitespace()) idx++
                            if (idx < len) {
                                start = idx
                                while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                val tmplStr = trimmed.substring(start, idx)
                                if (tmplStr != "null") tmpl = tmplStr

                                while (idx < len && trimmed[idx].isWhitespace()) idx++
                                if (idx < len) {
                                    start = idx
                                    while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                    val kbStr = trimmed.substring(start, idx)
                                    if (kbStr != "null") kb = kbStr

                                    while (idx < len && trimmed[idx].isWhitespace()) idx++
                                    if (idx < len) {
                                        start = idx
                                        while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                        privacy = trimmed.substring(start, idx)
                                        while (idx < len && trimmed[idx].isWhitespace()) idx++
                                        if (idx < len) {
                                            start = idx
                                            while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                            autoIdentity = trimmed.substring(start, idx)
                                        }
                                    }
                                }
                            }

                            if (pkg.isNotEmpty()) {
                                if (isValidPkg(pkg)) {
                                    val isTmplValid = tmpl.isEmpty() || isValidTemplate(tmpl)
                                    val isKbValid = kb.isEmpty() || isValidKeybox(kb)
                                    while (idx < len && trimmed[idx].isWhitespace()) idx++
                                    val parsedPrivacy = Config.AppPrivacyMode.parse(privacy)
                                    if (isTmplValid && isKbValid && parsedPrivacy != null && idx == len) {
                                        val obj = JSONObject()
                                        obj.put("package", pkg)
                                        obj.put("template", tmpl)
                                        obj.put("keybox", kb)
                                        obj.put("privacy", parsedPrivacy.configValue)
                                        obj.put("autoIdentity", autoIdentity)
                                        array.put(obj)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return secureResponse(Response.Status.OK, "application/json", array.toString())
        }

        if (uri == "/api/app_config_structured" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val jsonStr = getParam(session, "data")
            if (jsonStr != null) {
                try {
                    val array = JSONArray(jsonStr)
                    if (array.length() > MAX_APP_CONFIG_RULES) {
                        return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Too many app rules")
                    }
                    val sb = StringBuilder()
                    val seenPackages = HashSet<String>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val keys = obj.keys()
                        while (keys.hasNext()) require(keys.next() in APP_RULE_FIELDS)
                        val pkg = obj.get("package") as? String ?: throw IllegalArgumentException("Invalid package")
                        val tmpl =
                            if (obj.has("template")) {
                                (obj.get("template") as? String ?: throw IllegalArgumentException("Invalid template"))
                                    .ifEmpty { "null" }
                            } else {
                                "null"
                            }
                        val kb =
                            if (obj.has("keybox")) {
                                (obj.get("keybox") as? String ?: throw IllegalArgumentException("Invalid keybox"))
                                    .ifEmpty { "null" }
                            } else {
                                "null"
                            }
                        val privacy =
                            if (obj.has("privacy")) {
                                Config.AppPrivacyMode.parse(
                                    obj.get("privacy") as? String
                                        ?: throw IllegalArgumentException("Invalid privacy mode"),
                                )?.configValue ?: throw IllegalArgumentException("Invalid privacy mode")
                            } else {
                                Config.AppPrivacyMode.INHERIT.configValue
                            }
                        if (!isValidPkg(
                                pkg,
                            )
                        ) {
                            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid input: invalid characters")
                        }
                        if (tmpl != "null" &&
                            !isValidTemplate(
                                tmpl,
                            )
                        ) {
                            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid input")
                        }
                        if (kb != "null" &&
                            !isValidKeybox(
                                kb,
                            )
                        ) {
                            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid input")
                        }
                        if (Config.AppPrivacyMode.parse(privacy) == null) {
                            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid privacy mode")
                        }
                        val autoIdentityRaw = obj.optString("autoIdentity", "inherit")
                        if (autoIdentityRaw != "true" && autoIdentityRaw != "false" && autoIdentityRaw != "inherit" && autoIdentityRaw != "null") {
                            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid auto identity mode")
                        }
                        val autoIdentity = if (autoIdentityRaw == "true") "true" else if (autoIdentityRaw == "false") "false" else "inherit"
                        if (tmpl == "null" && kb == "null" && privacy == Config.AppPrivacyMode.INHERIT.configValue && autoIdentity == "inherit") {
                            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Empty app rule")
                        }
                        if (pkg.any { it.isWhitespace() }) {
                            return secureResponse(
                                Response.Status.BAD_REQUEST,
                                "text/plain",
                                "Invalid input",
                            )
                        }
                        if (!seenPackages.add(pkg)) {
                            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Duplicate app rule")
                        }
                        sb.append("$pkg $tmpl $kb $privacy $autoIdentity\n")
                        if (sb.length.toLong() > MAX_CONFIG_FILE_SIZE) {
                            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "App configuration is too large")
                        }
                    }
                    synchronized(fileLock) {
                        try {
                            val f = File(configDir, "app_config")
                            SecureFile.writeText(f, sb.toString())
                            java.nio.file.Files.setAttribute(
                                f.toPath(),
                                "basic:lastModifiedTime",
                                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()),
                                LinkOption.NOFOLLOW_LINKS
                            )
                            Config.updateAppConfigs(f).getOrThrow()
                            return secureResponse(Response.Status.OK, "text/plain", "Saved")
                        } catch (e: Exception) {
                            Logger.e("Failed to save app_config", e)
                            return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
                        }
                    }
                } catch (e: Exception) {
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid JSON")
                }
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing data")
        }

        if (uri == "/api/file" && method == Method.GET) {
            val filename = getParam(session, "filename")
            if (filename != null && filename in EDITABLE_CONFIG_FILES) {
                return secureResponse(Response.Status.OK, "text/plain", readFile(filename))
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid filename")
        }

        if (uri == "/api/save" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val filename = getParam(session, "filename")
            val content = getParam(session, "content")
            if (filename != null && filename in EDITABLE_CONFIG_FILES && content != null) {
                if (validateContent(filename, content)) {
                    if (saveFile(filename, content)) {
                        if (filename == "templates.json") {
                            DeviceTemplateManager.initialize(configDir)
                        } else if (filename == "keybox.xml") {
                            updateKeyboxesFromConfiguredRevocationSource()
                        }
                        return secureResponse(Response.Status.OK, "text/plain", "Saved")
                    }
                } else {
                    return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid content")
                }
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid request")
        }

        if (uri == "/api/toggle" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val setting = getParam(session, "setting")
            val value = getParam(session, "value")
            if (setting != null && value != null && value in setOf("true", "false")) {
                if (toggleFile(setting, value.toBooleanStrict())) {
                    return secureResponse(Response.Status.OK, "text/plain", "Toggled")
                }
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid setting")
        }

        if (uri == "/api/reset_environment" && method == Method.POST) {
            try {
                synchronized(fileLock) {
                    val spoofFile = File(configDir, "spoof_build_vars")
                    val replacements =
                        linkedMapOf(
                            "ATTESTATION_ID_IMEI" to RandomUtils.generateLuhn(15, "35"),
                            "ATTESTATION_ID_IMEI2" to RandomUtils.generateLuhn(15, "35"),
                            "ATTESTATION_ID_SERIAL" to RandomUtils.generateRandomSerial(12),
                            "ATTESTATION_ID_IMSI" to RandomUtils.generateDigits(15, "310260"),
                            "ATTESTATION_ID_IMSI2" to RandomUtils.generateDigits(15, "310260"),
                            "ATTESTATION_ID_ICCID" to RandomUtils.generateLuhn(20, "8901"),
                            "ATTESTATION_ID_ICCID2" to RandomUtils.generateLuhn(20, "8901"),
                            "ATTESTATION_ID_MEID" to RandomUtils.generateHex(14),
                            "ATTESTATION_ID_MEID2" to RandomUtils.generateHex(14),
                            "ATTESTATION_ID_PHONE_NUMBER" to "+1${RandomUtils.generateDigits(10)}",
                            "ATTESTATION_ID_PHONE_NUMBER2" to "+1${RandomUtils.generateDigits(10)}",
                            "VISIBLE_SIM_COUNT" to RandomUtils.generateVisibleSimCount(allowZero = false),
                            "VISIBLE_CAMERA_COUNT" to
                                (RandomUtils.choose(listOf("1", "2", "2", "3", "3", "3", "4", "4", "4", "4")) ?: "2"),
                        )
                    val spoofPath = spoofFile.toPath()
                    if (Files.exists(spoofPath, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isRegularFile(spoofPath, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid identity configuration")
                    }
                    val lines =
                        if (Files.isRegularFile(spoofPath, LinkOption.NOFOLLOW_LINKS)) {
                            try {
                                readIdentityLinesBounded(spoofFile)
                            } catch (error: IOException) {
                                Logger.w(
                                    "Refusing oversized or unstable identity configuration: ${error.message ?: error::class.simpleName}",
                                )
                                return secureResponse(
                                    Response.Status.BAD_REQUEST,
                                    "text/plain",
                                    "Identity configuration is too large or changed during read",
                                )
                            }
                        } else {
                            mutableListOf()
                        }
                    val processed = mutableSetOf<String>()
                    for (index in lines.indices) {
                        val key = lines[index].substringBefore('=', "").trim()
                        replacements[key]?.let { value ->
                            lines[index] = "$key=$value"
                            processed += key
                        }
                    }
                    replacements.filterKeys { it !in processed }.forEach { (key, value) -> lines += "$key=$value" }
                    SecureFile.writeText(spoofFile, lines.joinToString("\n", postfix = "\n"))
                    Config.updateBuildVars(spoofFile)
                    val target = File(configDir, "target.txt")
                    if (Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        java.nio.file.Files.setAttribute(
                            target.toPath(),
                            "basic:lastModifiedTime",
                            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()),
                            LinkOption.NOFOLLOW_LINKS
                        )
                    }
                    if (!updateKeyboxesFromConfiguredRevocationSource()) {
                        return keyboxActivationFailureResponse()
                    }
                    return secureResponse(Response.Status.OK, "text/plain", "Environment Reset")
                }
            } catch (e: Exception) {
                Logger.e("Failed to reset environment", e)
                return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Environment reset failed")
            }
        }

        if (uri == "/api/reload" && method == Method.POST) {
            try {
                synchronized(fileLock) {
                    val target = File(configDir, "target.txt")
                    if (Files.isSymbolicLink(target.toPath())) {
                        return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid target file")
                    }
                    if (Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        java.nio.file.Files.setAttribute(
                            target.toPath(),
                            "basic:lastModifiedTime",
                            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()),
                            LinkOption.NOFOLLOW_LINKS
                        )
                    }
                    val legacyFetcher = crlFetcher
                    val revocationAvailable =
                        if (legacyFetcher != null) {
                            val revoked = legacyFetcher()
                            if (revoked != null) Config.updateKeyBoxesSync(revoked)
                            revoked != null
                        } else {
                            val revoked = KeyboxVerifier.fetchCrl()
                            if (revoked != null) Config.updateKeyBoxesSync()
                            revoked != null
                        }
                    if (!revocationAvailable) {
                        Logger.w("Runtime reload kept the active keybox pool because revocation data is unavailable")
                    }
                    return secureResponse(Response.Status.OK, "text/plain", "Reloaded")
                }
            } catch (e: Exception) {
                Logger.e("Failed to reload", e)
                return secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
            }
        }

        if (uri == "/api/logs" && method == Method.GET) {
            return try {
                val type = session.parameters["type"]?.firstOrNull() ?: "cleverestricky"
                val cmd =
                    when (type) {
                        "errors" -> arrayOf("logcat", "-d", "-t", "1000", "*:E")
                        "system" -> arrayOf("logcat", "-d", "-t", "1000")
                        else -> arrayOf("logcat", "-d", "-t", "1000", "-s", "cleverestricky:V", "CleveresTricky:V")
                    }
                val logs = readCommandOutput(cmd)
                secureResponse(Response.Status.OK, "text/plain", logs.ifBlank { "No logs found." })
            } catch (e: Exception) {
                Logger.e("Failed to fetch logs", e)
                secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to fetch logs")
            }
        }

        if (uri == "/api/backup" && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val password = getParam(session, "pw")
            if (password == null || password.length !in 12..1024) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Backup password must be 12-1024 characters")
            }
            return try {
                val zipBytes = synchronized(fileLock) { createBackupZip(configDir) }
                val encBytes =
                    try {
                        BackupEncryptor.encrypt(zipBytes, password)
                    } finally {
                        zipBytes.fill(0)
                    }
                val response =
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/octet-stream",
                        ByteArrayInputStream(encBytes),
                        encBytes.size.toLong(),
                    )
                response.addHeader("Content-Disposition", "attachment; filename=\"cleverestricky_backup.ctsb\"")
                addSecurityHeaders(response)
                response
            } catch (e: Exception) {
                Logger.e("Failed to create encrypted backup", e)
                secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Encrypted backup failed")
            }
        }

        if (uri == "/api/language" && method == Method.GET) {
            val langFile = File(configDir, "lang.json")
            if (Files.isRegularFile(langFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return secureResponse(Response.Status.OK, "application/json", readFile("lang.json"))
            } else {
                return secureResponse(Response.Status.NOT_FOUND, "application/json", "{}")
            }
        }

        if (uri == "/api/resource_usage" && method == Method.GET) {
            val json = JSONObject()
            json.put("version_name", BuildConfig.VERSION_NAME)
            json.put("version_code", BuildConfig.VERSION_CODE)
            val keyboxCount = runCatching { CertHack.getKeyboxSourceCount() }.getOrDefault(0)
            json.put("keybox_count", keyboxCount)
            val appConfig = File(configDir, "app_config")
            val appConfigSize =
                if (Files.isRegularFile(appConfig.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    appConfig.length()
                } else {
                    0L
                }
            json.put("app_config_size", appConfigSize)
            WEB_UI_SETTINGS.forEach { setting -> json.put(setting, fileExists(setting)) }
            json.put("real_ram_kb", getRamUsageKb())
            json.put("real_cpu", getCpuUsagePercent())
            json.put("environment", getEnvironmentInfo())
            json.put("native_runtime", readNativeRuntimeStatus())
            json.put("keystore_interceptor_running", KeystoreInterceptor.isRunning())
            json.put("telephony_interceptor_running", TelephonyInterceptor.isRunning())
            return secureResponse(Response.Status.OK, "application/json", json.toString())
        }

        if (uri == "/api/restore" && method == Method.POST) {
            val files = HashMap<String, String>()
            try {
                session.parseBody(files)
            } catch (e: Exception) {
                return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Failed to parse body")
            }
            val tmpFilePath = files["file"]
            if (tmpFilePath != null) {
                val tmpFile = File(tmpFilePath)
                var uploadedBytes: ByteArray? = null
                return try {
                    val restoreLimit = if (trustedBridge) MAX_NATIVE_UPLOAD_SIZE else MAX_UPLOAD_SIZE
                    if (
                        !Files.isRegularFile(tmpFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                        tmpFile.length() !in 1..restoreLimit
                    ) {
                        return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid backup size")
                    }
                    val encryptedBytes = readFileBytesLimited(tmpFile, restoreLimit.toInt())
                    uploadedBytes = encryptedBytes
                    if (!BackupEncryptor.isEncryptedBackup(encryptedBytes)) {
                        return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Only encrypted .ctsb backups are accepted")
                    }
                    val pw = getParam(session, "pw")
                    if (pw == null || pw.length !in 12..1024) {
                        return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "Valid backup password required")
                    }
                    val decrypted = BackupEncryptor.decrypt(encryptedBytes, pw)
                    try {
                        synchronized(fileLock) {
                            restoreBackupZip(
                                configDir,
                                ByteArrayInputStream(decrypted),
                                afterMutation = {
                                    DeviceTemplateManager.initialize(configDir, persistBuiltInTemplates = false)
                                    PolicyState.validatePublishedState().getOrThrow()
                                    WEB_UI_SETTINGS.forEach(Config::refreshRuntimeSetting)
                                    Config.refreshRestoredConfiguration().getOrThrow()
                                    if (!updateKeyboxesFromConfiguredRevocationSource()) {
                                        throw RestoreKeyboxActivationException()
                                    }
                                },
                                onRollback = {
                                    refreshRuntimeAfterRestoreRollback(configDir)
                                },
                            )
                            val target = File(configDir, "target.txt")
                            if (Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                                java.nio.file.Files.setAttribute(
                                    target.toPath(),
                                    "basic:lastModifiedTime",
                                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()),
                                    LinkOption.NOFOLLOW_LINKS
                                )
                            }
                            secureResponse(Response.Status.OK, "text/plain", "Restore Successful")
                        }
                    } finally {
                        decrypted.fill(0)
                    }
                } catch (e: RestoreKeyboxActivationException) {
                    Logger.e("Keybox activation failed during backup restore", e)
                    keyboxActivationFailureResponse()
                } catch (e: RustBackendUnavailableException) {
                    Logger.e("Rust backend unavailable during backup restore", e)
                    secureResponse(
                        Response.Status.SERVICE_UNAVAILABLE,
                        "text/plain",
                        "Rust backend unavailable; restore not applied",
                    )
                } catch (e: Exception) {
                    Logger.e("Failed to restore backup", e)
                    secureResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Restore failed")
                } finally {
                    uploadedBytes?.fill(0)
                    if (tmpFile.exists() && !tmpFile.delete()) Logger.w("Failed to clean backup temp file")
                }
            }
            return secureResponse(Response.Status.BAD_REQUEST, "text/plain", "No file uploaded")
        }


        return null
    }

    private fun secureResponse(
        status: Response.Status,
        mimeType: String,
        txt: String,
    ): Response {
        val response = newFixedLengthResponse(status, mimeType, txt)
        addSecurityHeaders(response)
        return response
    }

    private fun secureResponse(
        status: Response.Status,
        mimeType: String,
        bytes: ByteArray,
    ): Response {
        val response = newFixedLengthResponse(status, mimeType, ByteArrayInputStream(bytes), bytes.size.toLong())
        addSecurityHeaders(response)
        return response
    }

    private fun addSecurityHeaders(response: Response) {
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("X-Frame-Options", "DENY")
        response.addHeader("X-XSS-Protection", "0")
        response.addHeader(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; " +
                "form-action 'self'; frame-ancestors 'none'",
        )
        response.addHeader("Referrer-Policy", "no-referrer")
        response.addHeader("Cross-Origin-Resource-Policy", "same-origin")
        response.addHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        response.addHeader("Cache-Control", "no-store, max-age=0")
        response.addHeader("Pragma", "no-cache")
    }

    private val htmlBytes by lazy { buildHtmlContent().toByteArray(Charsets.UTF_8) }

    private fun buildHtmlContent(): String {
        val candidates =
            listOf(
                File("module/template/webroot/index.html"),
                File("../module/template/webroot/index.html"),
            )
        val source =
            candidates.firstOrNull { file ->
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() in 1..MAX_WEB_UI_HTML_BYTES
            } ?: throw IOException("Native WebUI source is unavailable")
        return readUtf8FileSnapshotBounded(source, 1, MAX_WEB_UI_HTML_BYTES)
            .replace("@DEBUG@", BuildConfig.DEBUG.toString())
    }

    companion object {
        @androidx.annotation.VisibleForTesting
        internal var backupEntryWipeObserver: ((ByteArray) -> Unit)? = null

        private val MAX_UPLOAD_SIZE = CboxWireLimits.MAX_BYTES.toLong() + 1024 * 1024L
        private const val MAX_NATIVE_UPLOAD_SIZE = 20 * 1024 * 1024L
        private const val MAX_NATIVE_REQUEST_SIZE = MAX_NATIVE_UPLOAD_SIZE + 1024 * 1024L
        private const val MAX_KEYBOX_XML_UPLOAD_SIZE = 10 * 1024 * 1024L
        private val MAX_CBOX_UPLOAD_SIZE = CboxWireLimits.MAX_BYTES.toLong()
        private const val MAX_BODY_SIZE = 5 * 1024 * 1024L
        private const val MAX_CONFIG_FILE_SIZE = 1024 * 1024L
        private const val MAX_SCANNED_DIRECTORY_ENTRIES = 4_096
        private const val MAX_IDENTITY_REQUEST_BYTES = 8 * 1024
        private const val MAX_LOG_BYTES = 2 * 1024 * 1024
        private const val MAX_WEB_UI_HTML_BYTES = 512 * 1024L
        private const val RATE_LIMIT = 100
        private const val RATE_WINDOW = 60 * 1000L
        private const val RATE_WINDOW_NANOS = RATE_WINDOW * 1_000_000L
        private const val CPU_SAMPLE_MIN_INTERVAL_NANOS = 250_000_000L
        private const val MAX_BACKUP_ENTRIES = 128
        private const val MAX_BACKUP_KEYBOXES = 64
        private const val MAX_LISTED_KEYBOX_FILES = 128
        private const val MAX_BACKUP_CONFIG_ENTRY_BYTES = 1024 * 1024
        private const val MAX_BACKUP_XML_ENTRY_BYTES = 10 * 1024 * 1024
        private const val MAX_BACKUP_CBOX_ENTRY_BYTES = CboxWireLimits.MAX_BYTES
        private const val MAX_BACKUP_UNCOMPRESSED_BYTES = 16 * 1024 * 1024
        private const val MAX_SECURITY_PATCH_RULES = 512
        private const val MAX_DRM_PACKAGE_RULES = 256
        private const val MAX_APP_CONFIG_RULES = 1024
        private const val MAX_TARGET_RULES = 2048
        private const val MAX_TEMPLATES = 128
        private const val MAX_TEMPLATE_FIELD_LENGTH = 512
        private const val MAX_DRM_PACKAGES_BYTES = 64 * 1024
        private val SECURITY_PATCH_COMPONENTS = setOf("all", "system", "vendor", "boot")
        private val VALID_TEMPLATE_ID = Regex("[a-z0-9_-]{1,64}")
        private val CUSTOM_TEMPLATE_PROPERTIES =
            setOf(
                "BRAND",
                "DEVICE",
                "PRODUCT",
                "MANUFACTURER",
                "MODEL",
                "FINGERPRINT",
                "RELEASE",
                "BUILD_ID",
                "INCREMENTAL",
                "TYPE",
                "TAGS",
                "SECURITY_PATCH",
            )
        private val IDENTITY_FIELDS =
            linkedMapOf(
                "template" to "TEMPLATE",
                "imei" to "ATTESTATION_ID_IMEI",
                "imei2" to "ATTESTATION_ID_IMEI2",
                "imsi" to "ATTESTATION_ID_IMSI",
                "imsi2" to "ATTESTATION_ID_IMSI2",
                "iccid" to "ATTESTATION_ID_ICCID",
                "iccid2" to "ATTESTATION_ID_ICCID2",
                "meid" to "ATTESTATION_ID_MEID",
                "meid2" to "ATTESTATION_ID_MEID2",
                "phone_number" to "ATTESTATION_ID_PHONE_NUMBER",
                "phone_number2" to "ATTESTATION_ID_PHONE_NUMBER2",
                "serial" to "ATTESTATION_ID_SERIAL",
                "visible_sim_count" to "VISIBLE_SIM_COUNT",
                "visible_camera_count" to "VISIBLE_CAMERA_COUNT",
            )
        private val BUILD_IDENTITY_VAR_KEYS =
            linkedSetOf(
                "BRAND",
                "DEVICE",
                "PRODUCT",
                "MANUFACTURER",
                "MODEL",
                "FINGERPRINT",
                "RELEASE",
                "BUILD_ID",
                "INCREMENTAL",
                "TYPE",
                "TAGS",
                "SECURITY_PATCH",
                "SERIAL",
            )
        private const val BUILD_IDENTITY_BLOCK_START = "# BEGIN CLEVERESTRICKY BUILD IDENTITY"
        private const val BUILD_IDENTITY_BLOCK_END = "# END CLEVERESTRICKY BUILD IDENTITY"
        private val WEB_UI_SETTINGS =
            linkedSetOf(
                "spoof_enabled",
                "spoof_build_identity",
                "global_mode",
                "auto_keybox_check",
                "random_on_boot",
                "spoof_region_cn",
                "telephony",
                "camera_visibility",
                "drm_passthrough",
                "global_identity_mode",
            )
        private val EDITABLE_CONFIG_FILES =
            setOf(
                "target.txt",
                "identity_target.txt",
                "security_patch.txt",
                "spoof_build_vars",
                "app_config",
                "templates.json",
                "drm_packages.txt",
                "boot_props_mode",
                PolicyState.STATE_FILE,
            )
        private val BACKUP_CONFIG_FILES =
            setOf(
                "target.txt",
                "identity_target.txt",
                "security_patch.txt",
                "spoof_build_vars",
                "app_config",
                "privacy_seed",
                "keybox.xml",
                "module_hash",
                "templates.json",
                "custom_templates",
                "spoof_enabled",
                "spoof_build_identity",
                "global_identity_mode",
                "global_mode",
                "tee_broken_mode",
                "auto_keybox_check",
                "random_on_boot",
                "hide_sensitive_props",
                "spoof_region_cn",
                "telephony",
                "camera_visibility",
                // Retained only for legacy backup compatibility.
                "rkp_passthrough",
                "drm_passthrough",
                "drm_packages.txt",
                "boot_props_mode",
                PolicyState.STATE_FILE,
            )
        private val APP_RULE_FIELDS = setOf("package", "template", "keybox", "privacy", "autoIdentity")

        fun getSafeFile(
            baseDir: File,
            requestedPath: String,
        ): File? {
            val targetFile = File(baseDir, requestedPath)
            return try {
                val canonicalBase = baseDir.canonicalPath
                val canonicalTarget = targetFile.canonicalPath
                if (canonicalTarget.equals(canonicalBase) || canonicalTarget.startsWith(canonicalBase + File.separator)) {
                    targetFile
                } else {
                    Logger.e("Path Traversal attempt prevented! Target: $canonicalTarget")
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        fun isValidPkg(s: String): Boolean {
            if (s.length !in 1..255) return false
            for (i in 0 until s.length) {
                val c = s[i]
                if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '*')) {
                    return false
                }
            }
            return true
        }

        fun isValidTemplate(s: String): Boolean {
            if (s.length !in 1..64) return false
            for (i in 0 until s.length) {
                val c = s[i]
                if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-')) {
                    return false
                }
            }
            return true
        }

        fun isValidKeybox(s: String): Boolean {
            return isValidKeyboxFilename(s)
        }

        fun isSafeHost(host: String?): Boolean {
            val value = host?.trim()?.lowercase() ?: return false
            val address: String
            val port: String?
            if (value.startsWith("[")) {
                val closingBracket = value.indexOf(']')
                if (closingBracket <= 1) return false
                address = value.substring(1, closingBracket)
                val suffix = value.substring(closingBracket + 1)
                if (suffix.isEmpty()) {
                    port = null
                } else {
                    if (!suffix.startsWith(':')) return false
                    port = suffix.substring(1)
                }
                if (address != "::1" && address != "0:0:0:0:0:0:0:1") return false
            } else {
                val separator = value.indexOf(':')
                if (separator < 0) {
                    address = value
                    port = null
                } else {
                    if (value.indexOf(':', separator + 1) >= 0) return false
                    address = value.substring(0, separator)
                    port = value.substring(separator + 1)
                }
                if (address != "localhost" && address != "127.0.0.1") return false
            }
            return port == null || port.toIntOrNull()?.let { it in 1..65535 } == true
        }

        fun isSafePath(
            configDir: File,
            file: File,
        ): Boolean {
            return try {
                val configCanonical = configDir.canonicalPath
                val fileCanonical = file.canonicalPath
                fileCanonical.equals(configCanonical) || fileCanonical.startsWith(configCanonical + File.separator)
            } catch (e: Exception) {
                false
            }
        }

        fun isValidFilename(name: String): Boolean {
            return cleveres.tricky.cleverestech.isValidFilename(name)
        }

        fun validateContent(
            filename: String,
            content: String,
        ): Boolean {
            if (filename in WEB_UI_SETTINGS) return content.isEmpty()
            // Basic validation based on known file types
            if (filename == PolicyState.STATE_FILE) {
                return PolicyState.validateStateJson(content, validateReferences = false).isSuccess
            }
            if (filename == "target.txt" || filename == "identity_target.txt") {
                var ruleCount = 0
                val lines = content.lineSequence()
                return lines.all {
                    it.isEmpty() || it.startsWith("#") ||
                        (++ruleCount <= MAX_TARGET_RULES && isValidTargetPkg(it))
                }
            }
            if (filename == "drm_packages.txt") {
                if (content.utf8ByteLength() > MAX_DRM_PACKAGES_BYTES) return false
                var ruleCount = 0
                val lines = content.lineSequence()
                return lines.all { line ->
                    val value = line.trim()
                    value.isEmpty() || value.startsWith("#") ||
                        (++ruleCount <= MAX_DRM_PACKAGE_RULES && isValidPkg(value))
                }
            }
            if (filename == "boot_props_mode") {
                return content.trim().lowercase() in setOf("auto", "force", "disable")
            }
            if (filename == "module_hash") {
                val value = content.trim()
                return value.length == 64 && value.all { it.digitToIntOrNull(16) != null }
            }
            if (filename == "security_patch.txt") {
                var inPackageSection = false
                var ruleCount = 0
                return content.lineSequence().all { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@all true
                    if (++ruleCount > MAX_SECURITY_PATCH_RULES) return@all false

                    if (line.startsWith("[") && line.endsWith("]")) {
                        val packageName = line.substring(1, line.lastIndex).trim()
                        inPackageSection = true
                        return@all packageName.length <= 255 && isValidPkg(packageName)
                    }

                    val separator = line.indexOf('=')
                    if (separator < 0) {
                        return@all isValidSecurityPatchValue(line, allowSpecial = true)
                    }

                    val key = line.substring(0, separator).trim()
                    val value = line.substring(separator + 1).trim()
                    if (key.isEmpty() || value.isEmpty()) return@all false
                    if (key in SECURITY_PATCH_COMPONENTS) {
                        return@all isValidSecurityPatchValue(value, allowSpecial = true)
                    }
                    !inPackageSection &&
                        key.length <= 255 &&
                        isValidPkg(key) &&
                        isValidSecurityPatchValue(value, allowSpecial = false)
                }
            }
            if (filename == "spoof_build_vars") {
                val lines = content.lineSequence()
                return lines.all { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@all true
                    // Must be KEY=VALUE format
                    if (!isValidKeyValue(trimmed)) return@all false
                    // Value part security check
                    val idx = trimmed.indexOf('=')
                    if (idx == -1) return@all false
                    val key = trimmed.substring(0, idx).trim()
                    val value = trimmed.substring(idx + 1).trim()
                    // Check for unsafe shell chars
                    isValidSafeBuildVarValue(value) && Config.isValidBuildVarEntry(key, value)
                }
            }
            if (filename == "app_config") {
                var ruleCount = 0
                val seenPackages = HashSet<String>()
                val lines = content.lineSequence()
                return lines.all { line ->
                    if (line.isBlank() || line.startsWith("#")) return@all true
                    if (++ruleCount > MAX_APP_CONFIG_RULES) return@all false

                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@all true

                    val len = trimmed.length
                    var idx = 0

                    var start = idx
                    while (idx < len && !trimmed[idx].isWhitespace()) idx++
                    val pkg = trimmed.substring(start, idx)

                    if (!isValidPkg(pkg) || !seenPackages.add(pkg)) return@all false
                    var hasEffect = false

                    while (idx < len && trimmed[idx].isWhitespace()) idx++
                    if (idx < len) {
                        start = idx
                        while (idx < len && !trimmed[idx].isWhitespace()) idx++
                        val tmplStr = trimmed.substring(start, idx)
                        if (tmplStr != "null" && !isValidTemplate(tmplStr)) return@all false
                        if (tmplStr != "null") hasEffect = true

                        while (idx < len && trimmed[idx].isWhitespace()) idx++
                        if (idx < len) {
                            start = idx
                            while (idx < len && !trimmed[idx].isWhitespace()) idx++
                            val kbStr = trimmed.substring(start, idx)
                            if (kbStr != "null" && !isValidKeybox(kbStr)) return@all false
                            if (kbStr != "null") hasEffect = true

                            while (idx < len && trimmed[idx].isWhitespace()) idx++
                            if (idx < len) {
                                start = idx
                                while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                val privacy = trimmed.substring(start, idx)
                                val privacyMode = Config.AppPrivacyMode.parse(privacy) ?: return@all false
                                if (privacyMode != Config.AppPrivacyMode.INHERIT) hasEffect = true

                                while (idx < len && trimmed[idx].isWhitespace()) idx++
                                if (idx < len) {
                                    start = idx
                                    while (idx < len && !trimmed[idx].isWhitespace()) idx++
                                    val autoId = trimmed.substring(start, idx)
                                    if (autoId != "inherit" && autoId != "null") {
                                        val parsed = autoId.toBooleanStrictOrNull() ?: return@all false
                                        hasEffect = true
                                    }
                                    while (idx < len && trimmed[idx].isWhitespace()) idx++
                                    if (idx < len) return@all false
                                }
                            }
                        }
                    }

                    hasEffect
                }
            }
            if (filename == "privacy_seed") {
                val bytes = content.toByteArray(Charsets.UTF_8)
                return try {
                    Config.isValidPrivacySeedEncoding(bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            if (filename == "templates.json") {
                return runCatching {
                    val array = JSONArray(content)
                    require(array.length() <= MAX_TEMPLATES)
                    val requiredFields =
                        listOf(
                            "id",
                            "manufacturer",
                            "model",
                            "fingerprint",
                            "brand",
                            "product",
                            "device",
                            "release",
                            "buildId",
                            "incremental",
                            "securityPatch",
                        )
                    val seenIds = HashSet<String>()
                    for (index in 0 until array.length()) {
                        val template = array.getJSONObject(index)
                        val id = template.getString("id").trim().lowercase()
                        require(VALID_TEMPLATE_ID.matches(id) && seenIds.add(id))
                        requiredFields.forEach { field ->
                            val value = template.getString(field)
                            require(
                                value.isNotBlank() &&
                                    value.length <= MAX_TEMPLATE_FIELD_LENGTH &&
                                    value.none(Char::isISOControl),
                            )
                        }
                        listOf("type", "tags").forEach { field ->
                            val value = template.optString(field, "user")
                            require(
                                value.isNotBlank() &&
                                    value.length <= MAX_TEMPLATE_FIELD_LENGTH &&
                                    value.none(Char::isISOControl),
                            )
                        }
                        template.getString("securityPatch").convertPatchLevel(false)
                    }
                    true
                }.getOrDefault(false)
            }
            if (filename == "custom_templates") {
                var sectionCount = 0
                var hasCurrentTemplate = false
                return content.lineSequence().all { rawLine ->
                    val value = rawLine.trim()
                    if (value.isEmpty() || value.startsWith("#")) return@all true

                    if (value.startsWith("[") && value.endsWith("]")) {
                        val name = value.substring(1, value.lastIndex).trim().lowercase()
                        if (!VALID_TEMPLATE_ID.matches(name) || ++sectionCount > MAX_TEMPLATES) {
                            return@all false
                        }
                        hasCurrentTemplate = true
                        return@all true
                    }

                    if (!hasCurrentTemplate) return@all false
                    val separator = value.indexOf('=')
                    if (separator !in 1 until value.lastIndex) return@all false
                    val key = value.substring(0, separator).trim()
                    val propertyValue = value.substring(separator + 1).trim()
                    key in CUSTOM_TEMPLATE_PROPERTIES &&
                        propertyValue.isNotEmpty() &&
                        propertyValue.length <= MAX_TEMPLATE_FIELD_LENGTH &&
                        propertyValue.none(Char::isISOControl)
                }
            }
            return false
        }

        private fun listBoundedKeyboxFiles(
            directory: File,
            maxFiles: Int,
            extraFilter: (File) -> Boolean = { true },
        ): List<File> {
            val files = ArrayList<File>(maxFiles)
            Files.newDirectoryStream(directory.toPath()).use { entries ->
                var scanned = 0
                for (entry in entries) {
                    if (++scanned > MAX_SCANNED_DIRECTORY_ENTRIES) {
                        throw IOException("Too many entries in keybox directory")
                    }
                    val file = entry.toFile()
                    if (!isValidKeyboxFilename(file.name) ||
                        !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) ||
                        !extraFilter(file)
                    ) {
                        continue
                    }
                    if (files.size >= maxFiles) throw IOException("Too many keybox files")
                    files.add(file)
                }
            }
            files.sortBy { it.name }
            return files
        }

        fun createBackupZip(configDir: File): ByteArray {
            Config.ensurePrivacySeed(configDir).getOrThrow()
            val bos = FastByteArrayOutputStream()
            return try {
                ZipOutputStream(bos).use { zos ->
                    var totalBytes = 0L
                    BACKUP_CONFIG_FILES.sorted().forEach { name ->
                        val file = File(configDir, name)
                        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
                        val size = file.length()
                        val entryLimit = backupEntryLimit(name)
                        if (size !in 0..entryLimit.toLong()) {
                            throw IOException("Backup entry exceeds size limit: $name")
                        }
                        val remaining = MAX_BACKUP_UNCOMPRESSED_BYTES.toLong() - totalBytes
                        if (remaining < 0) throw IOException("Backup exceeds uncompressed size limit")
                        zos.putNextEntry(ZipEntry(name))
                        val copied =
                            Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                                BackupIo.copyBounded(input, zos, entryLimit.toLong(), remaining)
                            }
                        zos.closeEntry()
                        totalBytes += copied
                    }

                    val keyboxDir = File(configDir, "keyboxes")
                    if (Files.isDirectory(keyboxDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        val keyboxes =
                            listBoundedKeyboxFiles(keyboxDir, MAX_BACKUP_KEYBOXES) { file ->
                                isValidKeyboxBackupPath("keyboxes/${file.name}")
                            }
                        keyboxes.forEach { keybox ->
                            val backupName = "keyboxes/${keybox.name}"
                            val entryLimit = backupEntryLimit(backupName)
                            val size = keybox.length()
                            if (size !in 1..entryLimit.toLong()) {
                                throw IOException("Keybox exceeds size limit: ${keybox.name}")
                            }
                            val remaining = MAX_BACKUP_UNCOMPRESSED_BYTES.toLong() - totalBytes
                            if (remaining < 0) throw IOException("Backup exceeds uncompressed size limit")
                            zos.putNextEntry(ZipEntry(backupName))
                            val copied =
                                Files.newInputStream(keybox.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                                    BackupIo.copyBounded(
                                        input,
                                        zos,
                                        entryLimit.toLong(),
                                        remaining,
                                    )
                                }
                            zos.closeEntry()
                            totalBytes += copied
                        }
                    }
                }
                bos.toByteArray()
            } finally {
                bos.wipe()
            }
        }

        fun createKeyboxVerificationJson(results: List<KeyboxVerifier.Result>): String {
            val array = JSONArray()
            results.forEach { r ->
                val obj = JSONObject()
                obj.put("filename", r.filename)
            obj.put("storage_id", r.storageId)
            obj.put("certificate_serial", r.certificateSerial ?: "")
                obj.put("status", r.status.name)
                obj.put("details", r.details)
                array.put(obj)
            }
            return array.toString()
        }

        fun restoreBackupZip(
            configDir: File,
            inputStream: InputStream,
            afterMutation: (() -> Unit)? = null,
            onRollback: (() -> Unit)? = null,
        ) {
            val staged = LinkedHashMap<String, ByteArray>()
            var totalBytes = 0
            var keyboxCount = 0
            try {
                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (staged.size >= MAX_BACKUP_ENTRIES) {
                            throw IOException("Backup contains too many entries")
                        }
                        val name = entry.name
                        val allowed = name in BACKUP_CONFIG_FILES || isValidKeyboxBackupPath(name)
                        if (entry.isDirectory || !allowed || staged.containsKey(name)) {
                            throw SecurityException("Unsupported or duplicate backup entry: $name")
                        }
                        if (isValidKeyboxBackupPath(name) && ++keyboxCount > MAX_BACKUP_KEYBOXES) {
                            throw IOException("Backup contains too many keyboxes")
                        }

                        val entryLimit = backupEntryLimit(name)
                        val bytes = readAndValidateZipEntry(zis, name, entryLimit)
                        totalBytes += bytes.size
                        if (totalBytes > MAX_BACKUP_UNCOMPRESSED_BYTES) {
                            wipeBackupEntry(bytes)
                            throw IOException("Backup exceeds uncompressed size limit")
                        }
                        staged[name] = bytes
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                if (staged.isEmpty()) throw IOException("Backup is empty")

                val destinations =
                    staged.keys.associateWith { name ->
                        getSafeFile(configDir, name)
                            ?: throw SecurityException("Backup path escaped the configuration directory: $name")
                    }
                destinations.forEach { (name, file) ->
                    val path = file.toPath()
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        throw SecurityException("Refusing non-regular backup destination: $name")
                    }
                }

                val staleConfigFiles =
                    BACKUP_CONFIG_FILES
                        .asSequence()
                        .filter { it !in staged && it != "privacy_seed" }
                        .map { File(configDir, it) }
                        .filter { file ->
                            val path = file.toPath()
                            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@filter false
                            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                                throw SecurityException("Refusing non-regular stale configuration: ${file.name}")
                            }
                            true
                        }.toList()

                val keyboxDir = File(configDir, "keyboxes")
                val staleKeyboxFiles = ArrayList<File>()
                val invalidatedCacheFiles = ArrayList<File>()
                if (Files.exists(keyboxDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isDirectory(keyboxDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        throw SecurityException("Refusing non-directory keybox destination")
                    }
                    Files.newDirectoryStream(keyboxDir.toPath()).use { entries ->
                        var scanned = 0
                        var existingKeyboxCount = 0
                        for (entry in entries) {
                            if (++scanned > MAX_SCANNED_DIRECTORY_ENTRIES) {
                                throw IOException("Too many entries in keybox directory")
                            }
                            val name = entry.fileName.toString()
                            val backupPath = "keyboxes/$name"
                            if (!isValidKeyboxBackupPath(backupPath)) continue
                            if (++existingKeyboxCount > MAX_LISTED_KEYBOX_FILES) {
                                throw IOException("Too many existing keybox files")
                            }
                            if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                                throw SecurityException("Refusing non-regular stale keybox: $name")
                            }
                            if (backupPath !in staged) staleKeyboxFiles.add(entry.toFile())
                        }
                    }
                }

                staged.keys
                    .asSequence()
                    .filter { it.startsWith("keyboxes/") && it.endsWith(".cbox", ignoreCase = true) }
                    .map { File(keyboxDir, "${it.substringAfter('/')}.cache") }
                    .forEach { invalidatedCacheFiles.add(it) }
                staleKeyboxFiles
                    .asSequence()
                    .filter { it.name.endsWith(".cbox", ignoreCase = true) }
                    .map { File(keyboxDir, "${it.name}.cache") }
                    .forEach { invalidatedCacheFiles.add(it) }
                invalidatedCacheFiles.forEach { cacheFile ->
                    val path = cacheFile.toPath()
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        throw SecurityException("Refusing non-regular keybox cache")
                    }
                }

                val mutations = ArrayList<BackupRestoreTransaction.Mutation>()
                staged.forEach { (name, bytes) ->
                    mutations += BackupRestoreTransaction.Mutation(requireNotNull(destinations[name]), bytes)
                }
                staleConfigFiles.forEach { mutations += BackupRestoreTransaction.Mutation(it, null) }
                staleKeyboxFiles.forEach { mutations += BackupRestoreTransaction.Mutation(it, null) }
                invalidatedCacheFiles.forEach { mutations += BackupRestoreTransaction.Mutation(it, null) }
                BackupRestoreTransaction.apply(
                    configDir,
                    mutations,
                    afterMutation = afterMutation,
                    onRollback = onRollback,
                )
            } finally {
                staged.values.forEach { it.fill(0) }
            }
        }

        private fun isBackupKeyboxEntry(name: String): Boolean = name == "keybox.xml" || isValidKeyboxBackupPath(name)

        private fun backupEntryLimit(name: String): Int =
            when {
                name.endsWith(".cbox", ignoreCase = true) -> MAX_BACKUP_CBOX_ENTRY_BYTES
                isBackupKeyboxEntry(name) -> MAX_BACKUP_XML_ENTRY_BYTES
                else -> MAX_BACKUP_CONFIG_ENTRY_BYTES
            }

        private fun isValidKeyboxBackupPath(name: String): Boolean {
            if (!name.startsWith("keyboxes/") || name.count { it == '/' } != 1) return false
            val filename = name.substringAfter('/')
            val lower = filename.lowercase()
            return (lower.endsWith(".xml") || lower.endsWith(".cbox")) &&
                filename.length in 5..128 &&
                !filename.startsWith('.') &&
                filename.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        }

        private fun wipeBackupEntry(bytes: ByteArray) {
            bytes.fill(0)
            backupEntryWipeObserver?.let { observer -> runCatching { observer(bytes) } }
        }

        private fun isValidPrivacySeedBytes(bytes: ByteArray): Boolean = Config.isValidPrivacySeedEncoding(bytes)

        private fun readAndValidateZipEntry(
            input: InputStream,
            name: String,
            maxBytes: Int,
        ): ByteArray {
            val bytes = readZipEntry(input, maxBytes)
            return try {
                validateBackupEntry(name, bytes)
                bytes
            } catch (error: Throwable) {
                wipeBackupEntry(bytes)
                throw error
            }
        }

        private fun readZipEntry(
            input: InputStream,
            maxBytes: Int,
        ): ByteArray {
            val output = FastByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            return try {
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (count > maxBytes - total) throw IOException("Backup entry exceeds size limit")
                    output.write(buffer, 0, count)
                    total += count
                }
                output.toByteArray()
            } finally {
                buffer.fill(0)
                output.wipe()
            }
        }

        private fun validateBackupEntry(
            name: String,
            bytes: ByteArray,
        ) {
            if (name.endsWith(".cbox", ignoreCase = true)) {
                if (!CboxDecryptor.hasSupportedEnvelopeHeader(bytes)) {
                    throw IOException("Backup CBOX has an invalid envelope: $name")
                }
                return
            }
            if (isBackupKeyboxEntry(name)) {
                val parserInput = bytes.copyOf()
                try {
                    if (KeyboxLoader.parse(parserInput, name).isEmpty()) {
                        throw IOException("Backup keybox is empty: $name")
                    }
                } finally {
                    parserInput.fill(0)
                }
                return
            }
            if (name == "privacy_seed") {
                if (!isValidPrivacySeedBytes(bytes)) {
                    throw IOException("Backup privacy seed is invalid: $name")
                }
                return
            }
            val content = bytes.toString(Charsets.UTF_8)
            if (!content.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
                throw IOException("Backup entry is not valid UTF-8: $name")
            }
            if (!validateContent(name, content)) {
                throw IOException("Backup configuration is invalid: $name")
            }
        }
    }
}
