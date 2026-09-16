package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class WebServerJsonTest {
    @Test
    fun testJsonInjectionVulnerability() {
        // We inject a payload that closes the filename string, adds a new field, and handles the trailing quote.
        // Payload: hack", "injected": "true", "x": "
        // Resulting "filename": "hack", "injected": "true", "x": "" ...
        val payload = "hack\", \"injected\": \"true\", \"x\": \""
        val results =
            listOf(
                KeyboxVerifier.Result(File("dummy"), payload, KeyboxVerifier.Status.INVALID, "Bad"),
            )
        val json = WebServer.createKeyboxVerificationJson(results)

        val array = JSONArray(json)
        val obj = array.getJSONObject(0)

        // In the vulnerable version, "injected" key exists.
        if (obj.has("injected")) {
            fail("Vulnerability detected! JSON Injection successful. Injected key found.")
        }

        // If secure, the filename should be exactly the payload
        assertEquals(payload, obj.getString("filename"))
    }

    @Test
    fun testCertificateSerialSerialized() {
        val results =
            listOf(
                KeyboxVerifier.Result(
                    file = File("box.xml"),
                    filename = "box.xml",
                    status = KeyboxVerifier.Status.VALID,
                    details = "Active",
                    certificateSerial = "1A2B3C4D",
                    securityLevel = "StrongBox",
                ),
            )
        val json = WebServer.createKeyboxVerificationJson(results)
        val array = JSONArray(json)
        val obj = array.getJSONObject(0)
        assertEquals("1A2B3C4D", obj.getString("certificate_serial"))
        assertEquals("StrongBox", obj.getString("security_level"))
        assertEquals(false, obj.getBoolean("is_rkp"))
    }

    @Test
    fun testRkpSerialized() {
        val results =
            listOf(
                KeyboxVerifier.Result(
                    file = File("rkp_box.xml"),
                    filename = "rkp_box.xml",
                    status = KeyboxVerifier.Status.VALID,
                    details = "Active",
                    certificateSerial = "5E6F7A8B",
                    securityLevel = "TEE",
                    isRkp = true,
                ),
            )
        val json = WebServer.createKeyboxVerificationJson(results)
        val array = JSONArray(json)
        val obj = array.getJSONObject(0)
        assertEquals(true, obj.getBoolean("is_rkp"))
        assertEquals("TEE", obj.getString("security_level"))
    }

    @Test
    fun testHasRsaAndEcSerialized() {
        val results =
            listOf(
                KeyboxVerifier.Result(
                    file = File("rsa_box.xml"),
                    filename = "rsa_box.xml",
                    status = KeyboxVerifier.Status.VALID,
                    details = "Active",
                    certificateSerial = "5E6F7A8B",
                    securityLevel = "TEE",
                    isRkp = false,
                    hasRsa = true,
                    hasEc = false,
                ),
                KeyboxVerifier.Result(
                    file = File("ec_box.xml"),
                    filename = "ec_box.xml",
                    status = KeyboxVerifier.Status.VALID,
                    details = "Active",
                    certificateSerial = "9C8D7E6F",
                    securityLevel = "TEE",
                    isRkp = true,
                    hasRsa = false,
                    hasEc = true,
                ),
            )
        val json = WebServer.createKeyboxVerificationJson(results)
        val array = JSONArray(json)
        val obj0 = array.getJSONObject(0)
        assertEquals(true, obj0.getBoolean("has_rsa"))
        assertEquals(false, obj0.getBoolean("has_ec"))
        assertEquals(false, obj0.getBoolean("is_rkp"))

        val obj1 = array.getJSONObject(1)
        assertEquals(false, obj1.getBoolean("has_rsa"))
        assertEquals(true, obj1.getBoolean("has_ec"))
        assertEquals(true, obj1.getBoolean("is_rkp"))
    }
}
