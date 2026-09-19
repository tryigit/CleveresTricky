package cleveres.tricky.encryptor

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboxCertificateIdentityTest {
    @Test
    fun `real single certificate fixture has no third certificate identity`() {
        val root = locateRoot()
        val xml = File(root, "service/src/test/resources/keybox/valid_ec.xml").readBytes()
        try {
            assertNull(KeyboxCertificateIdentity.thirdCertificateSerial(xml))
        } finally {
            xml.fill(0)
        }
    }

    @Test
    fun `single leaf certificate provides a distinguishing identity`() {
        val root = locateRoot()
        val fixture = File(root, "service/src/test/resources/keybox/valid_ec.xml").readText()
        val beginMarker = "-----BEGIN CERTIFICATE-----"
        val endMarker = "-----END CERTIFICATE-----"
        val begin = fixture.indexOf(beginMarker)
        val end = fixture.indexOf(endMarker, begin) + endMarker.length
        require(begin >= 0 && end >= endMarker.length)
        val pem = fixture.substring(begin, end)
        val xml = "<CertificateChain><Certificate>$pem</Certificate></CertificateChain>".toByteArray()
        try {
            val serial = KeyboxCertificateIdentity.leafCertificateSerial(xml)
            assertNotNull(serial)
            assertTrue(requireNotNull(serial).matches(Regex("[0-9A-F]+")))
        } finally {
            xml.fill(0)
        }
    }

    @Test
    fun `fewer than three certificate PEM blocks has no identity`() {
        val xml = "<CertificateChain><Certificate>-----BEGIN CERTIFICATE-----x-----END CERTIFICATE-----</Certificate></CertificateChain>".toByteArray()
        assertNull(KeyboxCertificateIdentity.thirdCertificateSerial(xml))
    }

    private fun locateRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(6) {
            if (File(current, "encryptor-app").isDirectory && File(current, "service").isDirectory) return current
            current = current.parentFile ?: return@repeat
        }
        error("Repository root not found")
    }
}
