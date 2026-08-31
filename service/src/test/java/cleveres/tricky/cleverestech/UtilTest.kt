package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalStdlibApi::class)
class UtilTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val originalFetcher = systemPropertiesGet
    private val properties = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        systemPropertiesGet = { key, def ->
            properties[key] ?: def
        }
        properties.clear()
    }

    @After
    fun tearDown() {
        systemPropertiesGet = originalFetcher
        BootIdentityStore.resetRootForTesting()
    }

    private fun setProp(
        key: String,
        value: String,
    ) {
        properties[key] = value
    }

    @Test
    fun testGetBootKeyFromProp_primary() {
        val expected = ByteArray(32) { 0xAA.toByte() }
        val hex = expected.toHexString()
        setProp("ro.boot.vbmeta.public_key_digest", hex)
        setProp("ro.boot.verifiedbootkey", "")

        val result = getBootKeyFromProp()
        assertArrayEquals(expected, result)
    }

    @Test
    fun testGetBootKeyFromProp_fallback() {
        val expected = ByteArray(32) { 0xBB.toByte() }
        val hex = expected.toHexString()
        setProp("ro.boot.vbmeta.public_key_digest", "")
        setProp("ro.boot.verifiedbootkey", hex)

        val result = getBootKeyFromProp()
        assertArrayEquals(expected, result)
    }

    @Test
    fun testGetBootKeyFromProp_ignoresZeroSentinelAndFallsBack() {
        val expected = ByteArray(32) { 0xBC.toByte() }
        setProp("ro.boot.vbmeta.public_key_digest", "0".repeat(64))
        setProp("ro.boot.verifiedbootkey", expected.toHexString())

        assertArrayEquals(expected, getBootKeyFromProp())
    }

    @Test
    fun testGetBootKeyFromProp_rejectsZeroSentinel() {
        setProp("ro.boot.vbmeta.public_key_digest", "0".repeat(64))
        setProp("ro.boot.verifiedbootkey", "0".repeat(64))

        assertNull(getBootKeyFromProp())
    }

    @Test
    fun testGetBootHashFromProp_rejectsZeroSentinel() {
        setProp("ro.boot.vbmeta.digest", "0".repeat(64))
        assertNull(getBootHashFromProp())

        val expected = ByteArray(32) { 0xCD.toByte() }
        setProp("ro.boot.vbmeta.digest", expected.toHexString())
        assertArrayEquals(expected, getBootHashFromProp())
    }

    @Test
    fun testGetBootKeyFromProp_missing() {
        setProp("ro.boot.vbmeta.public_key_digest", "")
        setProp("ro.boot.verifiedbootkey", "")

        val result = getBootKeyFromProp()
        assertNull(result)
    }

    @Test
    fun testGetBootKeyFromProp_invalidLength() {
        setProp("ro.boot.vbmeta.public_key_digest", "1234567890")
        setProp("ro.boot.verifiedbootkey", "abcdef")

        val result = getBootKeyFromProp()
        assertNull(result)
    }

    @Test
    fun testPersistentBootDigests_areStableDistinctAndNonZero() {
        val root = temporaryFolder.newFolder("boot-identity")
        BootIdentityStore.setRootForTesting(root)

        val firstKey = requireNotNull(BootIdentityStore.bootKey())
        val secondKey = requireNotNull(BootIdentityStore.bootKey())
        val bootHash = requireNotNull(BootIdentityStore.bootHash())

        assertTrue(firstKey.isUsableBootDigest())
        assertTrue(bootHash.isUsableBootDigest())
        assertArrayEquals(firstKey, secondKey)
        assertFalse(firstKey.contentEquals(bootHash))
        assertTrue(root.resolve("boot_key").readText().matches(Regex("[0-9a-f]{64}")))
        assertTrue(root.resolve("boot_hash").readText().matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun testPersistentBootDigest_replacesZeroSentinel() {
        val root = temporaryFolder.newFolder("zero-boot-identity")
        root.resolve("boot_key").writeText("0".repeat(64))
        BootIdentityStore.setRootForTesting(root)

        val bootKey = requireNotNull(BootIdentityStore.bootKey())

        assertTrue(bootKey.isUsableBootDigest())
        assertFalse(root.resolve("boot_key").readText().trim().all { it == '0' })
    }

    @Test
    fun testValidTransactCodes_filtersAndDeduplicates() {
        assertArrayEquals("Empty array should return empty array", intArrayOf(), validTransactCodes())
        assertArrayEquals("All negative or zero should return empty array", intArrayOf(), validTransactCodes(-1, 0, -5))
        assertArrayEquals("Valid inputs without duplicates should be returned as is", intArrayOf(1, 2, 3), validTransactCodes(1, 2, 3))
        assertArrayEquals("Duplicates should be removed", intArrayOf(1, 2, 3), validTransactCodes(1, 2, 2, 3, 1, 3))
        assertArrayEquals("Mixed valid, invalid and duplicates", intArrayOf(1, 5, 2), validTransactCodes(-1, 0, 1, 5, 2, 1, -10, 0))
    }

    @Test
    fun testUtf8ByteLength_matchesByteArraySize() {
        val samples =
            listOf(
                "",
                "Hello, World!",
                "1234567890",
                "Türkçe karakterler: ğüşıöç ĞÜŞİÖÇ",
                "简体中文测试: 华为小米一加",
                "Русский текст для проверки",
                "العربية: اختبار النص العربي",
                "Emoji test: 🎉🔥🚀📱💻🛡️✨",
                "Mixed content: com.google.android.gms_12345!@#$%^&*()_+~|}{[]:;?><,./",
                "Surrogate edge cases: \uD83D\uDE00 \uD83C\uDF89 \uD83D\uDE80",
                "A".repeat(1000),
                "Ç".repeat(1000),
                "中".repeat(1000),
            )
        for (sample in samples) {
            val expected = sample.toByteArray(Charsets.UTF_8).size
            val actual = sample.utf8ByteLength()
            org.junit.Assert.assertEquals("Length mismatch for: $sample", expected, actual)
        }
    }
}
