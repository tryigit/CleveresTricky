package cleveres.tricky.cleverestech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificatePolicyWatcherTest {
    @Test
    fun `DRM and target policy files invalidate certificate cache`() {
        assertTrue(CertificatePolicyWatcher.affectsCertificatePolicy("drm_passthrough"))
        assertTrue(CertificatePolicyWatcher.affectsCertificatePolicy("drm_packages.txt"))
        assertTrue(CertificatePolicyWatcher.affectsCertificatePolicy("target.txt"))
        assertTrue(CertificatePolicyWatcher.affectsCertificatePolicy("global_mode"))
        assertTrue(CertificatePolicyWatcher.affectsCertificatePolicy("tee_broken_mode"))
    }

    @Test
    fun `unrelated settings do not invalidate certificate cache`() {
        assertFalse(CertificatePolicyWatcher.affectsCertificatePolicy("security_patch.txt"))
        assertFalse(CertificatePolicyWatcher.affectsCertificatePolicy("random_on_boot"))
    }
}
