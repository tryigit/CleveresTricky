package cleveres.tricky.cleverestech

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

        Config.reset()
        Config.setRootForTesting(tempDir)
        ManagedKeyboxParserOracle.install()
        ManagedOpaqueKeyOracle.readFromXml(null)

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
                    // no-op
                }

                override fun e(
                    tag: String,
                    msg: String,
                    t: Throwable?,
                ) {
                    // no-op
                    // no-op
                }

                override fun i(
                    tag: String,
                    msg: String,
                ) {
                    // no-op
                }
            },
        )
    }

    @After
    fun tearDown() {
        Config.reset()
        ManagedKeyboxParserOracle.reset()
        tempDir.deleteRecursively()
        ManagedOpaqueKeyOracle.readFromXml(null)
    }

    private fun callUpdateKeyBoxes() {
        val method = Config::class.java.getDeclaredMethod("updateKeyBoxes")
        method.isAccessible = true
        val job = method.invoke(Config) as Job
        runBlocking {
            job.join()
        }
    }

    private fun getCachedKeyboxCount(): Int {
        val field = Config::class.java.getDeclaredField("storedKeyboxCache")
        field.isAccessible = true
        val cache = field.get(Config) as Map<*, *>
        return cache.values.sumOf { entry ->
            requireNotNull(entry)
            val keyboxesField = entry.javaClass.getDeclaredField("keyboxes")
            keyboxesField.isAccessible = true
            (keyboxesField.get(entry) as List<*>).size
        }
    }

    @Test
    fun testCacheReloadsWhenLengthChangesAtSameTimestamp() {
        keyboxFile.writeText(xmlV1)
        val initialTime = 10000L
        keyboxFile.setLastModified(initialTime)

        callUpdateKeyBoxes()

        val cached1 = getCachedKeyboxCount()
        assertEquals("Should load 1 keybox", 1, cached1)

        keyboxFile.writeText(xmlV2)
        keyboxFile.setLastModified(initialTime)

        callUpdateKeyBoxes()

        val cached2 = getCachedKeyboxCount()
        assertEquals("Should reload the changed keybox", 0, cached2)

        val newTime = 20000L
        keyboxFile.setLastModified(newTime)

        callUpdateKeyBoxes()

        val cached3 = getCachedKeyboxCount()
        assertEquals("Should still have 0 keyboxes", 0, cached3)
    }

    @Test
    fun testCacheReloadsWhenContentChangesWithSameLengthAndTimestamp() {
        keyboxFile.writeText(xmlV1)
        val originalTimestamp = 30000L
        keyboxFile.setLastModified(originalTimestamp)

        callUpdateKeyBoxes()
        assertEquals("Should load the original keybox", 1, getCachedKeyboxCount())

        val replacement = xmlV1.replace("AndroidAttestation", "AndroidAttestatioN")
        assertEquals(xmlV1.toByteArray().size, replacement.toByteArray().size)
        keyboxFile.writeText(replacement)
        keyboxFile.setLastModified(originalTimestamp)

        callUpdateKeyBoxes()

        assertEquals("Should reject the same-metadata replacement", 0, getCachedKeyboxCount())
    }
}
