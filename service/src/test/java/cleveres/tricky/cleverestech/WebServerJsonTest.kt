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
}
