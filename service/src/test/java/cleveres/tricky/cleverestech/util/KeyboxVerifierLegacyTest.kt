package cleveres.tricky.cleverestech.util

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KeyboxVerifierLegacyTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testVerify_FindsLegacyAndJukebox() {
        val root = tempFolder.root
        val keyboxesDir = File(root, "keyboxes")
        keyboxesDir.mkdirs()

        // Legacy file in root
        File(root, "keybox.xml").writeText("<invalid></invalid>")

        // Jukebox file
        File(keyboxesDir, "jukebox.xml").writeText("<invalid></invalid>")

        // Mock CRL
        val crlFetcher = { emptySet<String>() }

        // Simulate WebUI call: pointing to "keyboxes" dir, but we want it to find legacy too.
        // Or rather, we will change WebUI to pass 'root' and KeyboxVerifier to be smart.
        // But for now, let's call verify with 'keyboxesDir' and see it fail to find legacy.
        // Actually, if we change the call signature in WebUI, we are changing the contract.
        // If I pass 'root' to verify(), existing code finds 'keybox.xml' but ignores 'keyboxes/jukebox.xml' (non-recursive).

        // So let's test verify(root).
        val results = KeyboxVerifier.verify(root, crlFetcher)

        // Expectation: It should find BOTH.
        val foundLegacy = results.any { it.filename == "keybox.xml" }
        val foundJukebox = results.any { it.filename == "jukebox.xml" }

        // Current behavior: finds legacy, misses jukebox (because listFiles is non-recursive).
        // Or if we simulate WebUI call: verify(keyboxesDir)
        // Current behavior: misses legacy, finds jukebox.

        // Since I can only call it once per test effectively (or multiple times),
        // I want to prove that NO single call currently satisfies the requirement.

        // Let's stick to the "smart verifier" plan where we pass 'root' (configDir).

        assertTrue("Should find legacy keybox.xml", foundLegacy)
        assertTrue("Should find jukebox.xml inside keyboxes/", foundJukebox)
    }
}
