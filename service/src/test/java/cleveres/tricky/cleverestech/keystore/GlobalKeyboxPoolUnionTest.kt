package cleveres.tricky.cleverestech.keystore

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

class GlobalKeyboxPoolUnionTest {
    private fun box(name: String): CertHack.KeyBox {
        val box = Mockito.mock(CertHack.KeyBox::class.java)
        Mockito.`when`(box.filename()).thenReturn(name)
        return box
    }

    @Test
    fun `strongBox requests stay within StrongBox pools in custom mode`() {
        val sbEc = listOf(box("sb_ec"))
        val sbRsa = listOf(box("sb_rsa"))
        val teeEc = listOf(box("tee_ec"))
        val teeRsa = listOf(box("tee_rsa"))

        val selected = CertHack.unionAllowedPools(sbEc, sbRsa, teeEc, teeRsa, true)

        assertEquals(listOf("sb_ec", "sb_rsa"), selected.map { it.filename() })
    }

    @Test
    fun `strongBox requests fall back to TEE pools only when StrongBox pools are empty`() {
        val teeEc = listOf(box("tee_ec"))
        val teeRsa = listOf(box("tee_rsa"))

        val selected = CertHack.unionAllowedPools(emptyList(), emptyList(), teeEc, teeRsa, true)

        assertEquals(listOf("tee_ec", "tee_rsa"), selected.map { it.filename() })
    }

    @Test
    fun `tee requests never consult StrongBox pools`() {
        val sbEc = listOf(box("sb_ec"))
        val teeRsa = listOf(box("tee_rsa"))

        val selected = CertHack.unionAllowedPools(sbEc, emptyList(), emptyList(), teeRsa, false)

        assertEquals(listOf("tee_rsa"), selected.map { it.filename() })
    }

    @Test
    fun `empty pools yield an empty candidate set instead of null`() {
        assertEquals(emptyList<String>(), CertHack.unionAllowedPools(null, null, null, null, true).map { it.filename() })
        assertEquals(emptyList<String>(), CertHack.unionAllowedPools(null, null, null, null, false).map { it.filename() })
    }
}
