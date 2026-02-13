package cleveres.tricky.cleverestech.util

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.util.LinkedHashMap

class CborEncoderRepro {

    @Test
    fun testNegativeKeySorting() {
        // Map with negative keys: {-1: 1, -2: 2}
        // -1 -> Major 1, value 0. Encoded: 0x20
        // -2 -> Major 1, value 1. Encoded: 0x21
        // Canonical sort: 0x20 < 0x21. So -1 comes before -2.

        val map = LinkedHashMap<Int, Int>()
        map[-1] = 1
        map[-2] = 2

        val encoded = CborEncoder.encode(map)

        // Expected:
        // Map(2) -> 0xA2
        // Key -1 -> 0x20
        // Val 1  -> 0x01
        // Key -2 -> 0x21
        // Val 2  -> 0x02
        // Total: A2 20 01 21 02
        val expected = byteArrayOf(0xA2.toByte(), 0x20.toByte(), 0x01.toByte(), 0x21.toByte(), 0x02.toByte())

        // If bug exists (-2 before -1):
        // Total: A2 21 02 20 01

        assertArrayEquals("Map keys should be sorted canonically", expected, encoded)
    }
}
