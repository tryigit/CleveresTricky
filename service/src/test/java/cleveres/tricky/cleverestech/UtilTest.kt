package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalStdlibApi::class)
class UtilTest {
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
}
