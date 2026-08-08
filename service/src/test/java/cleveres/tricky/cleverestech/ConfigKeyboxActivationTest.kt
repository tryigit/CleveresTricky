package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ConfigKeyboxActivationTest {
    @Test
    fun `mixed validity keybox pool is rejected as a unit`() {
        val originalRoot = Config.getConfigRoot()
        val root = File.createTempFile("keybox-activation", ".tmp").also { it.delete() }
        check(root.mkdirs())
        root.deleteOnExit()
        val keyboxDir = File(root, "keyboxes").also { check(it.mkdirs()) }
        File(keyboxDir, "valid.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)
        File(keyboxDir, "revoked.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)

        try {
            Config.reset()
            Config.setRootForTesting(root)
            val verificationCalls = AtomicInteger()
            Config.updateKeyBoxesSync(emptySet()) { _, _ ->
                if (verificationCalls.getAndIncrement() == 0) {
                    KeyboxVerifier.Status.VALID
                } else {
                    KeyboxVerifier.Status.REVOKED
                }
            }

            assertEquals(2, verificationCalls.get())
            assertEquals(0, CertHack.getKeyboxCount())
        } finally {
            Config.reset()
            Config.setRootForTesting(originalRoot)
            CertHack.readFromXml(null)
            root.deleteRecursively()
        }
    }
}
