package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.io.Reader
import java.util.Collections

class ConfigKeyboxActivationTest {
    @Test
    fun `mixed validity keybox pool is rejected as a unit`() {
        val root = File.createTempFile("keybox-activation", ".tmp").also { it.delete() }
        check(root.mkdirs())
        root.deleteOnExit()
        File(root, "keybox.xml").writeText("placeholder")

        val validKeybox = Mockito.mock(CertHack.KeyBox::class.java)
        val revokedKeybox = Mockito.mock(CertHack.KeyBox::class.java)
        val certHack = Mockito.mockStatic(CertHack::class.java)
        val verifier = Mockito.mockStatic(KeyboxVerifier::class.java)

        try {
            certHack.`when`<List<CertHack.KeyBox>> {
                CertHack.parseKeyboxXml(Mockito.any(Reader::class.java), Mockito.eq("keybox.xml"))
            }.thenReturn(listOf(validKeybox, revokedKeybox))
            verifier.`when`<KeyboxVerifier.Status> {
                KeyboxVerifier.verifyKeybox(validKeybox, emptySet())
            }.thenReturn(KeyboxVerifier.Status.VALID)
            verifier.`when`<KeyboxVerifier.Status> {
                KeyboxVerifier.verifyKeybox(revokedKeybox, emptySet())
            }.thenReturn(KeyboxVerifier.Status.REVOKED)

            Config.reset()
            Config.setRootForTesting(root)
            Config.updateKeyBoxesSync(emptySet())

            certHack.verify {
                CertHack.setKeyboxes(Collections.emptyList())
            }
        } finally {
            Config.reset()
            verifier.close()
            certHack.close()
            root.deleteRecursively()
        }
    }
}
