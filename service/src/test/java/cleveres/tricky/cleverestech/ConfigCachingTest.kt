package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class ConfigCachingTest {
    private lateinit var tempDir: File
    private lateinit var keyboxFile: File

    private val ecKey = TestKeyboxFixtures.ecPrivateKey
    private val testCertificate = TestKeyboxFixtures.certificate

    private val xmlV1 =
        "<?xml version=\"1.0\"?>\n" +
            "<AndroidAttestation>\n" +
            "<NumberOfKeyboxes>1</NumberOfKeyboxes>\n" +
            "<Keybox>\n" +
            "<Key algorithm=\"ecdsa\">\n" +
            "<PrivateKey>\n" + ecKey + "\n</PrivateKey>\n" +
            "<CertificateChain>\n" +
            "<NumberOfCertificates>1</NumberOfCertificates>\n" +
            "<Certificate>\n" + testCertificate + "\n</Certificate>\n" +
            "</CertificateChain>\n" +
            "</Key>\n" +
            "</Keybox>\n" +
            "</AndroidAttestation>"

    private val xmlV2 =
        "<?xml version=\"1.0\"?>\n" +
            "<AndroidAttestation>\n" +
            "<NumberOfKeyboxes>0</NumberOfKeyboxes>\n" +
            "</AndroidAttestation>"

    @Before
    fun setup() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "cleveres_cache_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        keyboxFile = File(tempDir, "keybox.xml")

        // Reset Config state
        Config.reset()
        Config.setRootForTesting(tempDir)

        // Ensure CertHack is clean
        CertHack.readFromXml(null)

        // Mock Logger to avoid spam
        Logger.setImpl(
            object : Logger.LogImpl {
                override fun d(
                    tag: String,
                    msg: String,
                ) {}

                override fun e(
                    tag: String,
                    msg: String,
                ) {
                    println("E/$tag: $msg")
                }

                override fun e(
                    tag: String,
                    msg: String,
                    t: Throwable?,
                ) {
                    println("E/$tag: $msg")
                    t?.printStackTrace()
                }

                override fun i(
                    tag: String,
                    msg: String,
                ) {
                    println("I/$tag: $msg")
                }
            },
        )
    }

    @After
    fun tearDown() {
        Config.reset()
        tempDir.deleteRecursively()
        CertHack.readFromXml(null)
    }

    private fun callUpdateKeyBoxes() {
        val method = Config::class.java.getDeclaredMethod("updateKeyBoxes")
        method.isAccessible = true
        val job = method.invoke(Config) as Job
        runBlocking {
            job.join()
        }
    }

    private fun getCachedLegacyKeyboxes(): List<*> {
        val field = Config::class.java.getDeclaredField("cachedLegacyKeyboxes")
        field.isAccessible = true
        return field.get(Config) as List<*>
    }

    @Test
    fun testCacheReloadsWhenLengthChangesAtSameTimestamp() {
        // 1. Write initial file
        keyboxFile.writeText(xmlV1)
        val initialTime = 10000L
        keyboxFile.setLastModified(initialTime)

        // 2. Load
        callUpdateKeyBoxes()

        // Verify loaded
        val cached1 = getCachedLegacyKeyboxes()
        assertEquals("Should load 1 keybox", 1, cached1.size)

        // 3. Change content but KEEP timestamp
        // We write V2 which has 0 keys.
        keyboxFile.writeText(xmlV2)
        keyboxFile.setLastModified(initialTime)

        // 4. Reload
        callUpdateKeyBoxes()

        // 5. The length change must invalidate the cache even when mtime is preserved.
        val cached2 = getCachedLegacyKeyboxes()
        assertEquals("Should reload the changed keybox", 0, cached2.size)

        // 6. Update timestamp
        val newTime = 20000L
        keyboxFile.setLastModified(newTime)

        // 7. Reload
        callUpdateKeyBoxes()

        // 8. A later timestamp remains valid and keeps the parsed empty state.
        val cached3 = getCachedLegacyKeyboxes()
        assertEquals("Should still have 0 keyboxes", 0, cached3.size)
    }
}
