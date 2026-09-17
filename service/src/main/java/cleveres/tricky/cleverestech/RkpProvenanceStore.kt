package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.SecureFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.cert.X509Certificate
import java.util.Locale

/**
 * Thread-safe persistent storage for authenticated Remote Key Provisioning (RKP) provenance metadata.
 */
internal object RkpProvenanceStore {
    const val PROVENANCE_FILE_NAME = "rkp_provenance.json"
    private const val MAX_ENTRIES = 256
    private const val MAX_FILE_SIZE = 64 * 1024L
    private val lock = Any()

    fun normalizeIdentifier(identifier: String): String =
        identifier.substringAfterLast(':').substringAfterLast('/').substringAfterLast('\\').trim()

    fun isRkp(
        identifier: String,
        baseDir: File = Config.getConfigRoot(),
    ): Boolean {
        val normalized = normalizeIdentifier(identifier)
        if (normalized.isEmpty()) return false
        val entries = load(baseDir)
        return entries.contains(normalized)
    }

    fun recordRkp(
        identifier: String,
        baseDir: File = Config.getConfigRoot(),
    ) {
        val normalized = normalizeIdentifier(identifier)
        if (normalized.isEmpty()) return
        synchronized(lock) {
            val current = load(baseDir).toMutableSet()
            if (current.add(normalized)) {
                save(baseDir, current)
            }
        }
    }

    fun removeRkp(
        identifier: String,
        baseDir: File = Config.getConfigRoot(),
    ) {
        val normalized = normalizeIdentifier(identifier)
        if (normalized.isEmpty()) return
        synchronized(lock) {
            val current = load(baseDir).toMutableSet()
            if (current.remove(normalized)) {
                save(baseDir, current)
            }
        }
    }

    fun hasVerifiedRkpCertificates(keybox: CertHack.KeyBox): Boolean {
        val certs = keybox.certificates() ?: return false
        if (certs.isEmpty()) return false
        return certs.any { cert ->
            if (cert !is X509Certificate) return@any false
            val subject = cert.subjectX500Principal?.name?.lowercase(Locale.ROOT) ?: ""
            val issuer = cert.issuerX500Principal?.name?.lowercase(Locale.ROOT) ?: ""
            fun isRkpDn(dn: String): Boolean =
                dn.contains("droid ca") ||
                    dn.contains("remote key provisioning") ||
                    dn.contains("remoteprovisioning") ||
                    dn.contains("key provisioning")
            isRkpDn(subject) || isRkpDn(issuer)
        }
    }

    private fun load(baseDir: File): Set<String> {
        val file = File(baseDir, PROVENANCE_FILE_NAME)
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return emptySet()
        }
        if (file.length() > MAX_FILE_SIZE) {
            return emptySet()
        }
        return try {
            val content = file.readText(Charsets.UTF_8)
            val json = JSONObject(content)
            val array = json.optJSONArray("rkp_keyboxes") ?: return emptySet()
            val result = HashSet<String>(array.length())
            for (i in 0 until array.length()) {
                val name = array.optString(i)
                if (name.isNotBlank()) {
                    result.add(normalizeIdentifier(name))
                }
            }
            result
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun save(
        baseDir: File,
        entries: Set<String>,
    ) {
        val file = File(baseDir, PROVENANCE_FILE_NAME)
        if (entries.isEmpty()) {
            if (file.exists()) file.delete()
            return
        }
        val json = JSONObject()
        val array = JSONArray()
        entries.sorted().take(MAX_ENTRIES).forEach { array.put(it) }
        json.put("rkp_keyboxes", array)
        SecureFile.writeText(file, json.toString(2))
    }

    fun resetForTesting(baseDir: File = Config.getConfigRoot()) {
        synchronized(lock) {
            val file = File(baseDir, PROVENANCE_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        }
    }
}
