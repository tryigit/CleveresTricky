package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoIdentityManagerTest {
    @Test
    fun `latest Pixel beta identity is resolved from bounded Google metadata`() {
        val pages =
            mapOf(
                "https://developer.android.com/about/versions" to
                    """<a href="/about/versions/16">16</a><a href="/about/versions/17">17 preview</a>""",
                "https://developer.android.com/about/versions/17" to
                    """<a href="/about/versions/17/download">Factory</a><a href="/about/versions/17/download-ota">OTA</a>""",
                "https://developer.android.com/about/versions/17/download" to
                    """<table><tr id="komodo"><td>Pixel 9 Pro XL</td><td>build</td></tr></table>""",
                "https://developer.android.com/about/versions/17/download-ota" to
                    """<table><tr id="komodo"><td>Pixel 9 Pro XL</td></tr><tr id="tokay"><td>Pixel 9</td></tr></table>""",
                "https://flash.android.com/" to
                    """<body data-client-config="client;abcdefghijklmnopQRST_1234&project=x"></body>""",
                "https://source.android.com/docs/security/bulletin/pixel" to
                    """<table><tr><td>2608</td><td>2026-08-05</td></tr></table>""",
            )
        val fetcher =
            AutoIdentityManager.Fetcher { url, _ ->
                if (url.startsWith("https://content-flashstation-pa.googleapis.com/v1/builds?")) {
                    """
                    {
                      "builds": [
                        {"canary": false, "releaseCandidateName": "OLD", "buildId": "1"},
                        {
                          "id": "canary-2608",
                          "canary": true,
                          "releaseCandidateName": "BP31.260801.001",
                          "buildId": "12345678",
                          "releaseTrackVersionName": "Android 17 Canary"
                        }
                      ]
                    }
                    """.trimIndent()
                } else {
                    pages[url] ?: error("Unexpected URL: $url")
                }
            }

        val result =
            AutoIdentityManager.fetchLatest(fetcher) { candidates ->
                candidates.first { it.device == "tokay" }
            }

        assertEquals("Pixel 9", result.model)
        assertEquals("tokay_beta", result.product)
        assertEquals("tokay", result.device)
        assertEquals("17", result.release)
        assertEquals("2026-08-05", result.securityPatch)
        assertFalse(result.securityPatchEstimated)
        assertEquals(
            "google/tokay_beta/tokay:CANARY/BP31.260801.001/12345678:user/release-keys",
            result.fingerprint,
        )
        assertEquals("Google", result.buildVars()["MANUFACTURER"])
        assertEquals("release-keys", result.buildVars()["TAGS"])
    }

    @Test
    fun `latest canary selection is independent of response order`() {
        val older =
            """{"id":"canary-20260715","canary":true,"releaseCandidateName":"BP31.260715.001","buildId":"100"}"""
        val newer =
            """{"id":"canary-20260820","canary":true,"releaseCandidateName":"BP31.260820.001","buildId":"200"}"""

        val newerFirst = AutoIdentityManager.findLatestCanary("[$newer,$older]")
        val newerLast = AutoIdentityManager.findLatestCanary("[$older,$newer]")

        assertEquals("canary-20260820", newerFirst?.optString("id"))
        assertEquals("canary-20260820", newerLast?.optString("id"))
    }

    @Test
    fun `security patch falls back to canary month when bulletin has no match`() {
        assertEquals("2026-08-05", AutoIdentityManager.estimateSecurityPatch("canary-2608"))
        assertEquals("2026-08-05", AutoIdentityManager.estimateSecurityPatch("canary-202608"))
    }

    @Test
    fun `unsafe or malformed beta rows are ignored`() {
        val candidates =
            AutoIdentityManager.parseDeviceCandidates(
                """
                <tr id="good_device"><td>Pixel Good</td></tr>
                <tr id="bad/device"><td>Pixel Bad</td></tr>
                """.trimIndent(),
            )
        assertEquals(1, candidates.size)
        assertTrue(candidates.single().product.endsWith("_beta"))
    }
}
