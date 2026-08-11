package cleveres.tricky.cleverestech

import android.os.FileObserver
import cleveres.tricky.cleverestech.keystore.CertHack
import java.io.File

/**
 * Keeps the substituted attestation-chain cache aligned with runtime policy changes.
 *
 * DRM passthrough, target scope, and global/TEE-broken policy can change whether a UID
 * is allowed to receive a rewritten certificate chain. Cached substitutions must not
 * survive those policy transitions, otherwise an app that has just moved onto the
 * genuine Keystore path can still observe a chain produced under the previous policy.
 */
internal object CertificatePolicyWatcher {
    private val policyFiles =
        setOf(
            "drm_passthrough",
            "drm_packages.txt",
            "target.txt",
            "global_mode",
            "tee_broken_mode",
        )

    private var observer: FileObserver? = null

    @Synchronized
    fun start(root: File) {
        if (observer != null) return
        observer =
            object : FileObserver(root, CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO) {
                override fun onEvent(
                    event: Int,
                    path: String?,
                ) {
                    val name = path ?: return
                    if (!affectsCertificatePolicy(name)) return
                    runCatching {
                        // Refresh the policy first so no request can repopulate the cache using
                        // the old decision after the invalidation below.
                        Config.refreshRuntimeSetting(name)
                        CertHack.clearCertificateCache()
                        Config.signalRuntimeController()
                        Logger.i("Certificate policy changed; cleared substituted attestation cache")
                    }.onFailure { error ->
                        Logger.e("Failed to refresh certificate policy", error)
                    }
                }
            }.also(FileObserver::startWatching)
    }

    @Synchronized
    fun stop() {
        observer?.stopWatching()
        observer = null
    }

    internal fun affectsCertificatePolicy(path: String): Boolean = path in policyFiles
}
