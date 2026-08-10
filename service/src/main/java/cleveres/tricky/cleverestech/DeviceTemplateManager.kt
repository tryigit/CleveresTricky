package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class DeviceTemplate(
    val id: String,
    val manufacturer: String,
    val model: String,
    val fingerprint: String,
    val brand: String,
    val product: String,
    val device: String,
    val release: String,
    val buildId: String,
    val incremental: String,
    val type: String = "user",
    val tags: String = "release-keys",
    val securityPatch: String,
) {
    fun toPropMap(): Map<String, String> {
        return mapOf(
            "MANUFACTURER" to manufacturer,
            "MODEL" to model,
            "BRAND" to brand,
            "PRODUCT" to product,
            "DEVICE" to device,
            "FINGERPRINT" to fingerprint,
            "RELEASE" to release,
            "BUILD_ID" to buildId,
            "INCREMENTAL" to incremental,
            "TYPE" to type,
            "TAGS" to tags,
            "SECURITY_PATCH" to securityPatch,
        )
    }
}

object DeviceTemplateManager {
    private const val TEMPLATES_FILE = "templates.json"
    private const val MAX_TEMPLATES = 128
    private const val MAX_TEMPLATES_BYTES = 1024 * 1024L
    private const val MAX_FIELD_LENGTH = 512
    private val validId = Regex("[a-z0-9_-]{1,64}")
    private var templates: MutableMap<String, DeviceTemplate> = mutableMapOf()
    private var cachedList: List<DeviceTemplate>? = null

    private var executor: ExecutorService =
        ThreadPoolExecutor(
            1,
            1,
            30,
            TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable>(),
            ThreadFactory { runnable ->
                Thread(runnable, "CleveresTricky-Templates").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            },
        ).apply { allowCoreThreadTimeOut(true) }

    @Volatile
    private var initFuture: Future<*>? = null
    private val initializationGeneration = AtomicLong()

    @androidx.annotation.VisibleForTesting
    @Synchronized
    fun setExecutorForTesting(newExecutor: ExecutorService) {
        initializationGeneration.incrementAndGet()
        cancelPendingInitialization()
        executor = newExecutor
    }

    private val builtInTemplates =
        listOf(
            DeviceTemplate(
                id = "pixel8pro",
                manufacturer = "Google",
                model = "Pixel 8 Pro",
                fingerprint = "google/husky/husky:14/AP1A.240405.002/11480754:user/release-keys",
                brand = "google",
                product = "husky",
                device = "husky",
                release = "14",
                buildId = "AP1A.240405.002",
                incremental = "11480754",
                securityPatch = "2024-04-05",
            ),
            DeviceTemplate(
                id = "pixel8",
                manufacturer = "Google",
                model = "Pixel 8",
                fingerprint = "google/shiba/shiba:14/AP1A.240405.002/11480754:user/release-keys",
                brand = "google",
                product = "shiba",
                device = "shiba",
                release = "14",
                buildId = "AP1A.240405.002",
                incremental = "11480754",
                securityPatch = "2024-04-05",
            ),
            DeviceTemplate(
                id = "pixel7pro",
                manufacturer = "Google",
                model = "Pixel 7 Pro",
                fingerprint = "google/cheetah/cheetah:14/AP1A.240305.019.A1/11445699:user/release-keys",
                brand = "google",
                product = "cheetah",
                device = "cheetah",
                release = "14",
                buildId = "AP1A.240305.019.A1",
                incremental = "11445699",
                securityPatch = "2024-03-05",
            ),
            DeviceTemplate(
                id = "pixel6pro",
                manufacturer = "Google",
                model = "Pixel 6 Pro",
                fingerprint = "google/raven/raven:13/TQ3A.230901.001/10750268:user/release-keys",
                brand = "google",
                product = "raven",
                device = "raven",
                release = "13",
                buildId = "TQ3A.230901.001",
                incremental = "10750268",
                securityPatch = "2023-09-01",
            ),
            DeviceTemplate(
                id = "s24ultra",
                manufacturer = "samsung",
                model = "SM-S928B",
                fingerprint = "samsung/e3sxXX/e3s:14/UP1A.231005.007/S928BXXS1AXBG:user/release-keys",
                brand = "samsung",
                product = "e3sxXX",
                device = "e3s",
                release = "14",
                buildId = "UP1A.231005.007",
                incremental = "S928BXXS1AXBG",
                securityPatch = "2024-02-01",
            ),
            DeviceTemplate(
                id = "s23ultra",
                manufacturer = "samsung",
                model = "SM-S918B",
                fingerprint = "samsung/dm3qxxx/dm3q:14/UP1A.231005.007/S918BXXS3BXE0:user/release-keys",
                brand = "samsung",
                product = "dm3qxxx",
                device = "dm3q",
                release = "14",
                buildId = "UP1A.231005.007",
                incremental = "S918BXXS3BXE0",
                securityPatch = "2024-05-01",
            ),
            DeviceTemplate(
                id = "xiaomi14",
                manufacturer = "Xiaomi",
                model = "23127PN0CG",
                fingerprint = "Xiaomi/houji_global/houji:14/UKQ1.230804.001/V816.0.4.0.UNCMIXM:user/release-keys",
                brand = "Xiaomi",
                product = "houji_global",
                device = "houji",
                release = "14",
                buildId = "UKQ1.230804.001",
                incremental = "V816.0.4.0.UNCMIXM",
                securityPatch = "2024-03-01",
            ),
            DeviceTemplate(
                id = "oneplus11",
                manufacturer = "OnePlus",
                model = "CPH2449",
                fingerprint = "OnePlus/CPH2449/OP5554L1:14/UKQ1.230924.001/R.15f1de6-1-1:user/release-keys",
                brand = "OnePlus",
                product = "CPH2449",
                device = "OP5554L1",
                release = "14",
                buildId = "UKQ1.230924.001",
                incremental = "R.15f1de6-1-1",
                securityPatch = "2024-04-05",
            ),
            DeviceTemplate(
                id = "nothing2",
                manufacturer = "Nothing",
                model = "A065",
                fingerprint = "Nothing/Pong/Pong:13/TKQ1.220915.002/2.5.1-231228-0054:user/release-keys",
                brand = "Nothing",
                product = "Pong",
                device = "Pong",
                release = "13",
                buildId = "TKQ1.220915.002",
                incremental = "2.5.1-231228-0054",
                securityPatch = "2024-01-01",
            ),
        )

    @Synchronized
    fun initialize(configDir: File) {
        val generation = initializationGeneration.incrementAndGet()
        templates = builtInTemplates.associateByTo(LinkedHashMap()) { it.id.lowercase() }
        cachedList = null
        cancelPendingInitialization()
        initFuture = executor.submit { loadCustomTemplates(configDir, generation) }
    }

    @Synchronized
    private fun cancelPendingInitialization() {
        val previous = initFuture ?: return
        previous.cancel(false)
        val threadPool = executor as? ThreadPoolExecutor
        if (previous is Runnable && threadPool != null) {
            threadPool.remove(previous)
            threadPool.purge()
        }
        initFuture = null
    }

    private fun loadCustomTemplates(
        configDir: File,
        generation: Long,
    ) {
        val file = File(configDir, TEMPLATES_FILE)
        if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            try {
                val beforeLength = file.length()
                val beforeModified = file.lastModified()
                require(beforeLength in 1..MAX_TEMPLATES_BYTES) { "templates.json has an invalid size" }
                val json = readTextBounded(file)
                require(
                    file.length() == beforeLength && file.lastModified() == beforeModified,
                ) { "templates.json changed while it was being read" }
                val array = JSONArray(json)
                require(array.length() <= MAX_TEMPLATES) { "templates.json contains too many templates" }
                val list = ArrayList<DeviceTemplate>()
                val seenIds = HashSet<String>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val template = requireNotNull(parseJson(obj)) { "templates.json contains an invalid template" }
                    require(seenIds.add(template.id)) { "templates.json contains duplicate template IDs" }
                    list.add(template)
                }
                synchronized(this) {
                    if (initializationGeneration.get() != generation) return
                    require((templates.keys + list.map { it.id }).size <= MAX_TEMPLATES) {
                        "Merged template set is too large"
                    }
                    list.forEach { templates[it.id.lowercase()] = it }
                    cachedList = null
                }
                Logger.i("Loaded ${array.length()} templates from $TEMPLATES_FILE")
            } catch (e: Exception) {
                Logger.e("Failed to load templates.json", e)
            }
        } else if (!file.exists()) {
            synchronized(this) {
                if (initializationGeneration.get() != generation) return
                saveTemplatesInternal(configDir)
            }
        } else {
            Logger.e("Refusing non-regular templates.json")
        }
    }

    private fun readTextBounded(file: File): String {
        val output = ByteArrayOutputStream(minOf(file.length(), 64 * 1024L).toInt())
        Files.newInputStream(file.toPath()).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > MAX_TEMPLATES_BYTES) throw IOException("templates.json exceeds its size limit")
                output.write(buffer, 0, count)
            }
        }
        val bytes = output.toByteArray()
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } finally {
            bytes.fill(0)
        }
    }

    private fun waitForInit() {
        while (true) {
            val future = synchronized(this) { initFuture } ?: return
            try {
                future.get()
            } catch (_: java.util.concurrent.CancellationException) {
                continue
            } catch (e: Exception) {
                Logger.e("Error waiting for template initialization", e)
                return
            }
            if (synchronized(this) { future === initFuture }) return
        }
    }

    private fun parseJson(obj: JSONObject): DeviceTemplate? {
        return try {
            validateTemplate(
                DeviceTemplate(
                    id = obj.getString("id"),
                    manufacturer = obj.getString("manufacturer"),
                    model = obj.getString("model"),
                    fingerprint = obj.getString("fingerprint"),
                    brand = obj.getString("brand"),
                    product = obj.getString("product"),
                    device = obj.getString("device"),
                    release = obj.getString("release"),
                    buildId = obj.getString("buildId"),
                    incremental = obj.getString("incremental"),
                    type = obj.optString("type", "user"),
                    tags = obj.optString("tags", "release-keys"),
                    securityPatch = obj.getString("securityPatch"),
                ),
            )
        } catch (e: Exception) {
            Logger.e("Error parsing template JSON", e)
            null
        }
    }

    fun getTemplate(id: String): DeviceTemplate? {
        waitForInit()
        synchronized(this) {
            return templates[id.lowercase()]
        }
    }

    fun getTemplateAsMap(id: String): Map<String, String>? {
        waitForInit()
        synchronized(this) {
            return templates[id.lowercase()]?.toPropMap()
        }
    }

    fun listTemplates(): List<DeviceTemplate> {
        waitForInit()
        synchronized(this) {
            val current = cachedList
            if (current != null) return current

            val sorted = templates.values.toList().sortedBy { it.model }
            cachedList = sorted
            return sorted
        }
    }

    fun saveTemplates(configDir: File) {
        waitForInit()
        synchronized(this) {
            saveTemplatesInternal(configDir)
        }
    }

    private fun saveTemplatesInternal(configDir: File) {
        try {
            val array = JSONArray()
            templates.values.forEach { template ->
                val obj = JSONObject()
                obj.put("id", template.id)
                obj.put("manufacturer", template.manufacturer)
                obj.put("model", template.model)
                obj.put("fingerprint", template.fingerprint)
                obj.put("brand", template.brand)
                obj.put("product", template.product)
                obj.put("device", template.device)
                obj.put("release", template.release)
                obj.put("buildId", template.buildId)
                obj.put("incremental", template.incremental)
                obj.put("type", template.type)
                obj.put("tags", template.tags)
                obj.put("securityPatch", template.securityPatch)
                array.put(obj)
            }
            SecureFile.writeText(File(configDir, TEMPLATES_FILE), array.toString(4))
        } catch (e: Exception) {
            Logger.e("Failed to save templates.json", e)
        }
    }

    fun addTemplate(template: DeviceTemplate) {
        waitForInit()
        synchronized(this) {
            require(templates.size < MAX_TEMPLATES || templates.containsKey(template.id.lowercase())) {
                "Too many templates"
            }
            val validated = validateTemplate(template)
            templates[validated.id] = validated
            cachedList = null
        }
    }

    private fun validateTemplate(template: DeviceTemplate): DeviceTemplate {
        val normalizedId = template.id.trim().lowercase()
        require(validId.matches(normalizedId)) { "Invalid template ID" }
        val values =
            listOf(
                template.manufacturer,
                template.model,
                template.fingerprint,
                template.brand,
                template.product,
                template.device,
                template.release,
                template.buildId,
                template.incremental,
                template.type,
                template.tags,
                template.securityPatch,
            )
        require(values.all { it.isNotBlank() && it.length <= MAX_FIELD_LENGTH && it.none(Char::isISOControl) }) {
            "Invalid template field"
        }
        template.securityPatch.convertPatchLevel(false)
        return template.copy(id = normalizedId)
    }
}
