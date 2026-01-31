package cleveres.tricky.cleverestech

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService

/**
 * Background service for fetching latest Pixel Beta fingerprints.
 * Battery-optimized: Only runs when explicitly triggered or on schedule.
 * Goal: MEETS_STRONG_INTEGRITY via fresh fingerprints
 */
object BetaFetcher {
    private const val TAG = "BetaFetcher"
    
    private const val VERSIONS_URL = "https://developer.android.com/about/versions"
    private const val FLASH_URL = "https://flash.android.com"
    private const val BULLETIN_URL = "https://source.android.com/docs/security/bulletin/pixel"
    
    // Battery optimization: 24 hour minimum interval
    private const val MIN_UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 15000
    
    private var scheduler: ScheduledExecutorService? = null
    private var lastFetchTime = 0L
    
    data class BetaProfile(
        val model: String,
        val product: String,
        val device: String,
        val fingerprint: String,
        val securityPatch: String,
        val buildId: String,
        val incremental: String
    )
    
    data class FetchResult(
        val success: Boolean,
        val profile: BetaProfile? = null,
        val error: String? = null,
        val availableDevices: List<String> = emptyList()
    )
    
    /**
     * Starts background auto-update service.
     * Battery-optimized: Checks only once per 24 hours.
     */
    fun startBackgroundService(intervalHours: Int = 24) {
        if (scheduler != null) {
            Logger.d(TAG, "Background service already running")
            return
        }
        
        // Ensure minimum 24 hour interval for battery
        val safeInterval = maxOf(intervalHours, 24).toLong()
        
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "BetaFetcher-Background").apply { 
                isDaemon = true 
                priority = Thread.MIN_PRIORITY // Low priority for battery
            }
        }
        
        scheduler?.scheduleAtFixedRate({
            try {
                if (shouldUpdate()) {
                    fetchAndApply(null)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Background fetch failed", e)
            }
        }, safeInterval, safeInterval, TimeUnit.HOURS)
        
        Logger.i(TAG, "Background service started, interval: ${safeInterval}h")
    }
    
    /**
     * Stops background service.
     */
    fun stopBackgroundService() {
        scheduler?.shutdown()
        scheduler = null
        Logger.i(TAG, "Background service stopped")
    }
    
    /**
     * Checks if update should run based on interval.
     */
    private fun shouldUpdate(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastFetchTime) >= MIN_UPDATE_INTERVAL_MS
    }
    
    /**
     * Fetches latest Pixel Beta profile and applies it.
     * @param preferredDevice Optional device to prefer (e.g., "husky" for Pixel 8 Pro)
     */
    fun fetchAndApply(preferredDevice: String?): FetchResult {
        Logger.i(TAG, "Fetching latest Pixel Beta profile...")
        
        try {
            // 1. Get available devices
            val devices = fetchAvailableDevices()
            if (devices.isEmpty()) {
                return FetchResult(false, error = "No devices found")
            }
            
            // 2. Select device
            val selectedProduct = if (preferredDevice != null && devices.contains("${preferredDevice}_beta")) {
                "${preferredDevice}_beta"
            } else {
                devices.random()
            }
            
            // 3. Fetch fingerprint
            val profile = fetchProfileForDevice(selectedProduct)
                ?: return FetchResult(false, error = "Failed to fetch profile for $selectedProduct")
            
            // 4. Apply to spoof_build_vars
            applyProfile(profile)
            
            lastFetchTime = System.currentTimeMillis()
            Logger.i(TAG, "Applied profile: ${profile.model} (${profile.fingerprint})")
            
            return FetchResult(true, profile = profile, availableDevices = devices)
            
        } catch (e: Exception) {
            Logger.e(TAG, "Fetch failed", e)
            return FetchResult(false, error = e.message)
        }
    }
    
    /**
     * Gets list of available Pixel Beta devices.
     */
    fun fetchAvailableDevices(): List<String> {
        try {
            val versionsHtml = httpGet(VERSIONS_URL)
            val latestUrl = Regex("https://developer.android.com/about/versions/[0-9]+")
                .find(versionsHtml)?.value ?: "https://developer.android.com/about/versions/16"
            
            val latestHtml = httpGet(latestUrl)
            val downloadUrl = Regex("href=\"(/about/versions/[^\"]*download[^\"]*)\"")
                .find(latestHtml)?.groupValues?.get(1)
                ?.let { "https://developer.android.com$it" }
                ?: "https://developer.android.com/about/versions/16/download"
            
            val downloadHtml = httpGet(downloadUrl)
            
            // Extract device IDs from table
            return Regex("<tr id=\"([^\"]+)\"")
                .findAll(downloadHtml)
                .map { "${it.groupValues[1]}_beta" }
                .toList()
                .ifEmpty {
                    // Fallback list
                    listOf(
                        "komodo_beta", "caiman_beta", "tokay_beta",
                        "husky_beta", "shiba_beta", "cheetah_beta",
                        "panther_beta", "raven_beta", "oriole_beta"
                    )
                }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to fetch device list", e)
            return emptyList()
        }
    }
    
    /**
     * Fetches profile for specific device.
     */
    private fun fetchProfileForDevice(product: String): BetaProfile? {
        try {
            val device = product.removeSuffix("_beta")
            
            // Get Flash Tool API key
            val flashHtml = httpGet(FLASH_URL)
            val apiKey = Regex("data-client-config=[^;]*;([^&]+)")
                .find(flashHtml)?.groupValues?.get(1) ?: return null
            
            // Fetch builds
            val buildsUrl = "https://content-flashstation-pa.googleapis.com/v1/builds?product=$product&key=$apiKey"
            val buildsJson = httpGet(buildsUrl, mapOf("Referer" to FLASH_URL))
            
            // Find canary build
            val buildIdMatch = Regex("\"releaseCandidateName\"\\s*:\\s*\"([^\"]+)\"")
                .find(buildsJson)?.groupValues?.get(1) ?: return null
            
            val incrementalMatch = Regex("\"buildId\"\\s*:\\s*\"([^\"]+)\"")
                .find(buildsJson)?.groupValues?.get(1) ?: return null
            
            // Get model name from device code
            val model = getModelName(device)
            
            // Build fingerprint
            val fingerprint = "google/$product/$device:CANARY/$buildIdMatch/$incrementalMatch:user/release-keys"
            
            // Get security patch
            val bulletinHtml = httpGet(BULLETIN_URL)
            val canaryDate = Regex("canary-([0-9-]+)")
                .find(buildsJson)?.groupValues?.get(1)
                ?.let { if (it.length == 6) "${it.substring(0,4)}-${it.substring(4,6)}" else it }
            
            val securityPatch = if (canaryDate != null) {
                Regex("<td>$canaryDate-[0-9]{2}</td>")
                    .find(bulletinHtml)?.value
                    ?.replace("<td>", "")?.replace("</td>", "")
                    ?: "$canaryDate-05"
            } else {
                // Fallback to current month
                java.text.SimpleDateFormat("yyyy-MM-05").format(java.util.Date())
            }
            
            return BetaProfile(
                model = model,
                product = product,
                device = device,
                fingerprint = fingerprint,
                securityPatch = securityPatch,
                buildId = buildIdMatch,
                incremental = incrementalMatch
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to fetch profile for $product", e)
            return null
        }
    }
    
    /**
     * Applies profile to spoof_build_vars.
     */
    private fun applyProfile(profile: BetaProfile) {
        val dataDir = File("/data/adb/cleverestricky")
        if (!dataDir.exists()) dataDir.mkdirs()
        
        val varsFile = File(dataDir, "spoof_build_vars")
        
        // Preserve user overrides
        val userOverrides = if (varsFile.exists()) {
            varsFile.readLines()
                .filter { it.startsWith("#") || it.startsWith("ATTESTATION_") || 
                          it.startsWith("MODULE_") || it.startsWith("KEYMINT_") }
                .joinToString("\n")
        } else ""
        
        val content = """
# Auto-generated by CleveresTricky BetaFetcher
# Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}
# Device: ${profile.model} (${profile.product})

MANUFACTURER=Google
MODEL=${profile.model}
BRAND=google
PRODUCT=${profile.product}
DEVICE=${profile.device}
FINGERPRINT=${profile.fingerprint}
SECURITY_PATCH=${profile.securityPatch}
ID=${profile.buildId}
INCREMENTAL=${profile.incremental}
RELEASE=16
TYPE=user
TAGS=release-keys

# Hidden props
ro.boot.verifiedbootstate=green
ro.boot.flash.locked=1
ro.boot.vbmeta.device_state=locked

$userOverrides
        """.trimIndent()
        
        varsFile.writeText(content)
        Logger.i(TAG, "Profile applied to ${varsFile.absolutePath}")
    }
    
    /**
     * Gets model name from device codename.
     */
    private fun getModelName(device: String): String = when (device) {
        "komodo" -> "Pixel 9 Pro XL"
        "caiman" -> "Pixel 9 Pro"
        "tokay" -> "Pixel 9"
        "husky" -> "Pixel 8 Pro"
        "shiba" -> "Pixel 8"
        "cheetah" -> "Pixel 7 Pro"
        "panther" -> "Pixel 7"
        "raven" -> "Pixel 6 Pro"
        "oriole" -> "Pixel 6"
        else -> "Pixel"
    }
    
    /**
     * HTTP GET helper.
     */
    private fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap()): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "CleveresTricky/1.0")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            
            if (conn.responseCode != 200) {
                throw RuntimeException("HTTP ${conn.responseCode}")
            }
            
            return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
