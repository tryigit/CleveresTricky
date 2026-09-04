package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.util.KeyboxVerifier
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigKeyboxActivationTest {
    @Test
    fun `arbitrary direct root XML is activated like legacy keybox xml`() {
        withKeyboxRoot { root ->
            File(root, "A1B2C3.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)
            ManagedKeyboxParserOracle.install()
            KeyboxLoader.activeSetOverride = { ids -> ids.size == 1 && ids.all(ManagedOpaqueKeyOracle::contains) }
            assertTrue(Config.updateKeyBoxesSync(emptySet()) { _, _ -> KeyboxVerifier.Status.VALID })
            assertEquals(1, CertHack.getKeyboxSourceCount())
        }
    }

    @Test
    fun `mixed validity keybox pool is rejected as a unit and commits empty active set`() {
        withKeyboxRoot { root ->
            val keyboxDir = File(root, "keyboxes").also { check(it.mkdirs()) }
            File(keyboxDir, "valid.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)
            File(keyboxDir, "revoked.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)

            ManagedKeyboxParserOracle.install()
            val verificationCalls = AtomicInteger()
            val committedSizes = ArrayList<Int>()
            KeyboxLoader.activeSetOverride = { ids ->
                committedSizes += ids.size
                ids.all(ManagedOpaqueKeyOracle::contains)
            }
            Config.updateKeyBoxesSync(emptySet()) { _, _ ->
                if (verificationCalls.getAndIncrement() == 0) {
                    KeyboxVerifier.Status.VALID
                } else {
                    KeyboxVerifier.Status.REVOKED
                }
            }

            assertEquals(2, verificationCalls.get())
            assertEquals(listOf(0), committedSizes)
            assertEquals(0, CertHack.getKeyboxCount())
        }
    }

    @Test
    fun `managed keyboxes are never published before backend commit succeeds`() {
        withKeyboxRoot { root ->
            val keyboxDir = File(root, "keyboxes").also { check(it.mkdirs()) }
            File(keyboxDir, "valid.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)
            ManagedKeyboxParserOracle.install()

            var commits = 0
            KeyboxLoader.activeSetOverride = { ids ->
                commits++
                assertEquals("managed state was published before backend commit", 0, CertHack.getKeyboxCount())
                assertEquals(1, ids.size)
                false
            }
            Config.updateKeyBoxesSync(emptySet()) { _, _ -> KeyboxVerifier.Status.VALID }

            assertEquals(1, commits)
            assertThrows(IllegalStateException::class.java) {
                CertHack.getKeyboxCount()
            }
        }
    }

    @Test
    fun `failed keybox publication remains dirty and retries without another file event`() {
        withKeyboxRoot { root ->
            File(root, "retry.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)
            ManagedKeyboxParserOracle.install()

            var commits = 0
            KeyboxLoader.activeSetOverride = { ids ->
                commits++
                ids.all(ManagedOpaqueKeyOracle::contains) && commits > 1
            }

            assertFalse(Config.updateKeyBoxesSync(emptySet()) { _, _ -> KeyboxVerifier.Status.VALID })
            assertEquals(1, commits)

            assertTrue(Config.ensureFreshKeyboxes())
            assertEquals(2, commits)
            assertEquals(1, CertHack.getKeyboxSourceCount())
        }
    }

    @Test
    fun `parsed snapshot digest controls cache reuse instead of path metadata`() {
        withKeyboxRoot { root ->
            File(root, "cache.xml").writeText("placeholder")
            ManagedOpaqueKeyOracle.reset()
            val first = ManagedOpaqueKeyOracle.parse(TestKeyboxFixtures.validEcKeyboxXml.reader(), "cache.xml").single()
            val replacement = ManagedOpaqueKeyOracle.parse(TestKeyboxFixtures.validEcKeyboxXml.reader(), "cache.xml").single()
            val parsed =
                ArrayDeque(
                    listOf(
                        KeyboxLoader.ParsedFile("a".repeat(64), listOf(first)),
                        KeyboxLoader.ParsedFile("a".repeat(64), listOf(replacement)),
                        KeyboxLoader.ParsedFile("b".repeat(64), listOf(replacement)),
                    ),
                )
            KeyboxLoader.fileParserOverride = { _, _ -> parsed.removeFirst() }
            KeyboxLoader.activeSetOverride = { ids -> ids.all(ManagedOpaqueKeyOracle::contains) }
            val verified = ArrayList<CertHack.KeyBox>()
            val verifier: (CertHack.KeyBox, Set<String>) -> KeyboxVerifier.Status = { keybox, _ ->
                verified += keybox
                KeyboxVerifier.Status.VALID
            }

            assertTrue(Config.updateKeyBoxesSync(emptySet(), verifier))
            assertTrue(Config.updateKeyBoxesSync(emptySet(), verifier))
            assertTrue(Config.updateKeyBoxesSync(emptySet(), verifier))

            assertEquals(3, verified.size)
            assertSame(first, verified[0])
            assertSame("same digest must reuse the accepted parsed snapshot", first, verified[1])
            assertSame("digest change must invalidate the cached snapshot", replacement, verified[2])
        }
    }

    @Test
    fun `deleted active keybox commits empty set and removes managed snapshot`() {
        withKeyboxRoot { root ->
            val keyboxDir = File(root, "keyboxes").also { check(it.mkdirs()) }
            val file = File(keyboxDir, "active.xml")
            file.writeText(TestKeyboxFixtures.validEcKeyboxXml)
            ManagedKeyboxParserOracle.install()

            val committedSizes = ArrayList<Int>()
            KeyboxLoader.activeSetOverride = { ids ->
                committedSizes += ids.size
                ids.all(ManagedOpaqueKeyOracle::contains)
            }
            Config.updateKeyBoxesSync(emptySet()) { _, _ -> KeyboxVerifier.Status.VALID }
            assertEquals(1, CertHack.getKeyboxCount())

            assertTrue(file.delete())
            Config.updateKeyBoxesSync(emptySet()) { _, _ -> KeyboxVerifier.Status.VALID }

            assertEquals(listOf(1, 0), committedSizes)
            assertEquals(0, CertHack.getKeyboxCount())
        }
    }

    @Test
    fun `more than backend capacity rejected refreshes commit empty active set every cycle`() {
        withKeyboxRoot { root ->
            val keyboxDir = File(root, "keyboxes").also { check(it.mkdirs()) }
            File(keyboxDir, "candidate.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)
            ManagedKeyboxParserOracle.install()

            val committedSizes = ArrayList<Int>()
            KeyboxLoader.activeSetOverride = { ids ->
                committedSizes += ids.size
                true
            }

            repeat(MAX_STORED_KEYS + 1) {
                Config.updateKeyBoxesSync(emptySet()) { _, _ -> KeyboxVerifier.Status.REVOKED }
                assertEquals(0, CertHack.getKeyboxCount())
            }

            assertEquals(MAX_STORED_KEYS + 1, committedSizes.size)
            assertTrue("every rejected refresh must prune staged keys", committedSizes.all { it == 0 })
        }
    }

    @Test
    fun `keyboxes are admitted when auto_keybox_check is disabled even without crl`() {
        withKeyboxRoot { root ->
            val keyboxDir = File(root, "keyboxes").also { check(it.mkdirs()) }
            File(keyboxDir, "candidate.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)
            ManagedKeyboxParserOracle.install()

            val toggle = File(root, "auto_keybox_check")
            toggle.delete()

            KeyboxLoader.activeSetOverride = { ids ->
                ids.all(ManagedOpaqueKeyOracle::contains)
            }

            val updated = Config.updateKeyBoxesSync()
            assertTrue(updated)
            assertEquals(1, CertHack.getKeyboxCount())
        }
    }

    @Test
    fun `keyboxes are admitted when auto_keybox_check is enabled and crl is unavailable`() {
        withKeyboxRoot { root ->
            val keyboxDir = File(root, "keyboxes").also { check(it.mkdirs()) }
            File(keyboxDir, "candidate.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)
            ManagedKeyboxParserOracle.install()

            val toggle = File(root, "auto_keybox_check")
            toggle.writeText("")

            KeyboxLoader.activeSetOverride = { ids ->
                ids.all(ManagedOpaqueKeyOracle::contains)
            }

            val updated = Config.updateKeyBoxesSyncWithoutExternalSourcesForTesting(null) { _, _ ->
                KeyboxVerifier.Status.REVOKED
            }
            assertTrue(updated)
            assertEquals(1, CertHack.getKeyboxCount())
        }
    }

    @Test
    fun `keyboxes are rejected when auto_keybox_check is enabled and crl marks keybox revoked`() {
        withKeyboxRoot { root ->
            val keyboxDir = File(root, "keyboxes").also { check(it.mkdirs()) }
            File(keyboxDir, "candidate.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)
            ManagedKeyboxParserOracle.install()

            val toggle = File(root, "auto_keybox_check")
            toggle.writeText("")

            KeyboxLoader.activeSetOverride = { ids ->
                ids.all(ManagedOpaqueKeyOracle::contains)
            }

            Config.updateKeyBoxesSyncWithoutExternalSourcesForTesting(emptySet()) { _, _ ->
                KeyboxVerifier.Status.REVOKED
            }
            assertEquals(0, CertHack.getKeyboxCount())
        }
    }

    @Test
    fun `offline keybox admission followed by online CRL refresh deactivates revoked keybox`() {
        withKeyboxRoot { root ->
            val keyboxDir = File(root, "keyboxes").also { check(it.mkdirs()) }
            File(keyboxDir, "candidate.xml").writeText(TestKeyboxFixtures.validEcKeyboxXml)
            ManagedKeyboxParserOracle.install()

            val toggle = File(root, "auto_keybox_check")
            toggle.writeText("")

            val committedSizes = ArrayList<Int>()
            KeyboxLoader.activeSetOverride = { ids ->
                committedSizes += ids.size
                ids.all(ManagedOpaqueKeyOracle::contains)
            }

            // Phase 1: Boot offline (CRL unavailable) -> valid keybox is admitted immediately
            val offlineAdmitted = Config.updateKeyBoxesSyncWithoutExternalSourcesForTesting(null) { _, _ ->
                KeyboxVerifier.Status.REVOKED
            }
            assertTrue("Offline boot must admit keyboxes when CRL is unavailable", offlineAdmitted)
            assertEquals("Keybox must be active at boot", 1, CertHack.getKeyboxCount())
            assertEquals(listOf(1), committedSizes)

            // Phase 2: Online refresh arrives later -> CRL detects revocation -> keybox is deactivated
            Config.updateKeyBoxesSyncWithoutExternalSourcesForTesting(emptySet()) { _, _ ->
                KeyboxVerifier.Status.REVOKED
            }
            assertEquals("Keybox pool must be purged upon online revocation", 0, CertHack.getKeyboxCount())
            assertEquals(listOf(1, 0), committedSizes)
        }
    }

    private fun withKeyboxRoot(block: (File) -> Unit) {
        val originalRoot = Config.getConfigRoot()
        val root = File.createTempFile("keybox-activation", ".tmp").also { it.delete() }
        check(root.mkdirs())
        root.deleteOnExit()
        try {
            Config.reset()
            Config.setRootForTesting(root)
            block(root)
        } finally {
            Config.reset()
            ManagedKeyboxParserOracle.reset()
            Config.setRootForTesting(originalRoot)
            ManagedOpaqueKeyOracle.readFromXml(null)
            root.deleteRecursively()
        }
    }

    private companion object {
        const val MAX_STORED_KEYS = 256
    }
}
