package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.keystore.CertHack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class ServerManagerCacheTest {
    @Test
    fun `multi-keybox cache is serialized as one valid document`() {
        val original =
            CertHack.parseKeyboxXml(
                StringReader(TestKeyboxFixtures.validEcKeyboxXml),
                "source.xml",
            ).single()

        val cachedXml = ServerManager.serializeKeyboxesForCache(listOf(original, original))
        val reparsed = CertHack.parseKeyboxXml(StringReader(cachedXml), "cache.xml")

        assertEquals(2, reparsed.size)
        assertTrue(reparsed.all { it.certificates.isNotEmpty() })
    }
}
