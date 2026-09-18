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
                          "releaseTrackVersionName": "Android 17 Canary",
                          "securityPatch": "2026-02-31"
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
    fun `invalid canary calendar dates cannot outrank valid builds`() {
        val valid =
            """{"id":"canary-20260228","canary":true,"releaseCandidateName":"BP31.260228.001","buildId":"100"}"""
        val invalid =
            """{"id":"canary-20260231","canary":true,"releaseCandidateName":"BP31.260231.001","buildId":"999"}"""

        val latest = AutoIdentityManager.findLatestCanary("[$valid,$invalid]")

        assertEquals("canary-20260228", latest?.optString("id"))
    }

    @Test
    fun `security patch lookup stays inside the matching bulletin row`() {
        val bulletin =
            """
            <table>
              <tr><td>2607</td><td>2026-07-05</td></tr>
              <tr><td>2608</td><td>2026-08-05</td></tr>
            </table>
            """.trimIndent()

        assertEquals(
            "2026-08-05",
            AutoIdentityManager.findSecurityPatchInBulletin(bulletin, "canary-2608"),
        )
    }

    @Test
    fun `security patch lookup prioritizes SPL dates over release dates in the same row`() {
        val bulletin =
            """
            <table>
              <tr><td>canary-2608</td><td>2026-08-18</td><td>2026-08-05</td></tr>
            </table>
            """.trimIndent()

        assertEquals(
            "2026-08-05",
            AutoIdentityManager.findSecurityPatchInBulletin(bulletin, "canary-2608"),
        )
    }

    @Test
    fun `security patch lookup does not bridge across bulletin rows`() {
        val bulletin =
            """
            <table>
              <tr><td>2607</td><td>2026-07-05</td></tr>
              <p>canary-2608</p>
              <tr><td>2026-08-05</td></tr>
            </table>
            """.trimIndent()

        assertEquals(null, AutoIdentityManager.findSecurityPatchInBulletin(bulletin, "canary-2608"))
    }

    @Test
    fun `security patch lookup does not borrow a nearby date outside the matching row`() {
        val bulletin =
            """
            <div>2026-07-05</div>
            <p>canary-2608</p>
            <div>2026-08-05</div>
            """.trimIndent()

        assertEquals(null, AutoIdentityManager.findSecurityPatchInBulletin(bulletin, "canary-2608"))
    }

    @Test
    fun `security patch lookup rejects impossible calendar dates`() {
        val bulletin = """<table><tr><td>2608</td><td>2026-02-31</td></tr></table>"""

        assertEquals(null, AutoIdentityManager.findSecurityPatchInBulletin(bulletin, "canary-2608"))
    }

    @Test
    fun `security patch falls back to canary month when bulletin has no match`() {
        assertEquals("2026-08-05", AutoIdentityManager.estimateSecurityPatch("canary-2608"))
        assertEquals("2026-08-05", AutoIdentityManager.estimateSecurityPatch("canary-202608"))
    }

    @Test
    fun `findLatestVersionPath prioritizes preview and highest version`() {
        val html =
            """
            <a href="/about/versions/15">Android 15</a>
            <div data-icon="preview"><a href="/about/versions/16">Android 16 Developer Preview</a></div>
            """.trimIndent()

        assertEquals("/about/versions/16", AutoIdentityManager.findLatestVersionPath(html))
    }

    @Test
    fun `unsafe or malformed beta rows are ignored`() {
        val candidates =
            AutoIdentityManager.parseDeviceCandidates(
                """
                <tr class="pixel-row" data-id="ignored" id = "good_device"><td>Pixel Good</td></tr>
                <tr class="pixel-row" id="bad/device"><td>Pixel Bad</td></tr>
                """.trimIndent(),
            )
        assertEquals(1, candidates.size)
        assertEquals("good_device", candidates.single().device)
        assertTrue(candidates.single().product.endsWith("_beta"))
    }

    @Test
    fun `latest Pixel canary identity is resolved with nested previewMetadata and RELEASE=CANARY`() {
        val pages =
            mapOf(
                "https://developer.android.com/about/versions" to
                    """<a href="/about/versions/17">17 preview</a>""",
                "https://developer.android.com/about/versions/17" to
                    """<a href="/about/versions/17/download">Factory</a>""",
                "https://developer.android.com/about/versions/17/download" to
                    """<table><tr id="komodo"><td>Pixel 9 Pro XL</td><td>build</td></tr></table>""",
                "https://flash.android.com/" to
                    """<body data-client-config="client;apiKey=abcdefghijklmnopQRST_1234&project=x"></body>""",
                "https://source.android.com/docs/security/bulletin/pixel" to
                    """<table><tr><td>2026-09</td><td>2026-09-05</td></tr></table>""",
            )
        val fetcher =
            AutoIdentityManager.Fetcher { url, _ ->
                if (url.startsWith("https://content-flashstation-pa.googleapis.com/v1/builds?")) {
                    """
                    {
                      "flashstationBuild": [
                        {
                          "releaseCandidateName": "ZP11.260821.010",
                          "buildId": "16290768",
                          "previewMetadata": {
                            "id": "canary-202609",
                            "canary": true,
                            "releaseTrackVersionName": "Canary 202609",
                            "releaseTrackName": "Android Canary"
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                } else {
                    pages[url] ?: error("Unexpected URL: $url")
                }
            }

        val result = AutoIdentityManager.fetchLatest(fetcher)

        assertEquals("Pixel 9 Pro XL", result.model)
        assertEquals("komodo_beta", result.product)
        assertEquals("komodo", result.device)
        assertEquals("CANARY", result.release)
        assertEquals("ZP11.260821.010", result.buildId)
        assertEquals("16290768", result.incremental)
        assertEquals("2026-09-05", result.securityPatch)
        assertEquals(
            "google/komodo_beta/komodo:CANARY/ZP11.260821.010/16290768:user/release-keys",
            result.fingerprint,
        )
        val buildVars = result.buildVars()
        assertEquals("Google", buildVars["MANUFACTURER"])
        assertEquals("google", buildVars["BRAND"])
        assertEquals("Pixel 9 Pro XL", buildVars["MODEL"])
        assertEquals("komodo_beta", buildVars["PRODUCT"])
        assertEquals("komodo", buildVars["DEVICE"])
        assertEquals("CANARY", buildVars["RELEASE"])
        assertEquals("ZP11.260821.010", buildVars["BUILD_ID"])
        assertEquals("16290768", buildVars["INCREMENTAL"])
        assertEquals("user", buildVars["TYPE"])
        assertEquals("release-keys", buildVars["TAGS"])
        assertEquals("2026-09-05", buildVars["SECURITY_PATCH"])
    }

    @Test
    fun `canary lookup retries across candidate devices if primary candidate has no canary build`() {
        val pages =
            mapOf(
                "https://developer.android.com/about/versions" to
                    """<a href="/about/versions/17">17 preview</a>""",
                "https://developer.android.com/about/versions/17" to
                    """<a href="/about/versions/17/download">Factory</a>""",
                "https://developer.android.com/about/versions/17/download" to
                    """<table><tr id="caiman"><td>Pixel 9 Pro</td></tr><tr id="komodo"><td>Pixel 9 Pro XL</td></tr></table>""",
                "https://flash.android.com/" to
                    """<body data-client-config="client;apiKey=abcdefghijklmnopQRST_1234&project=x"></body>""",
                "https://source.android.com/docs/security/bulletin/pixel" to
                    """<table><tr><td>2026-09</td><td>2026-09-05</td></tr></table>""",
            )
        val fetcher =
            AutoIdentityManager.Fetcher { url, _ ->
                if (url.startsWith("https://content-flashstation-pa.googleapis.com/v1/builds?")) {
                    if (url.contains("product=caiman_beta")) {
                        """{"flashstationBuild": []}"""
                    } else {
                        """
                        {
                          "flashstationBuild": [
                            {
                              "releaseCandidateName": "ZP11.260821.010",
                              "buildId": "16290768",
                              "previewMetadata": {
                                "id": "canary-202609",
                                "canary": true,
                                "releaseTrackVersionName": "Canary 202609",
                                "releaseTrackName": "Android Canary"
                              }
                            }
                          ]
                        }
                        """.trimIndent()
                    }
                } else {
                    pages[url] ?: error("Unexpected URL: $url")
                }
            }

        // Primary selector selects caiman first (which has empty builds), should fail over to komodo
        val result = AutoIdentityManager.fetchLatest(fetcher) { candidates ->
            candidates.first { it.device == "caiman" }
        }

        assertEquals("komodo", result.device)
        assertEquals("Pixel 9 Pro XL", result.model)
        assertEquals("CANARY", result.release)
    }

    @Test
    fun `findSecurityPatchInBulletin matches 6-digit YYYYMM token to hyphenated YYYY-MM table text`() {
        val bulletin =
            """
            <table>
              <tr><td>2026-08</td><td>2026-08-05</td></tr>
              <tr><td>2026-09</td><td>2026-09-05</td></tr>
            </table>
            """.trimIndent()

        assertEquals(
            "2026-09-05",
            AutoIdentityManager.findSecurityPatchInBulletin(bulletin, "canary-202609"),
        )
    }
}
