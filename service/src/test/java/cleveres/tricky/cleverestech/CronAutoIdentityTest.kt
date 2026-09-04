package cleveres.tricky.cleverestech

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CronAutoIdentityTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        Config.reset()
        ProfileAutoIdentityStore.resetForTesting()
        root = Files.createTempDirectory("cron-auto-identity-test").toFile()
        Config.setRootForTesting(root)
        CronAutoIdentity.configureForTesting(root)
    }

    @After
    fun tearDown() {
        CronAutoIdentity.stop()
        ProfileAutoIdentityStore.resetForTesting()
        PolicyState.resetForTesting()
        root.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `rapid disable and reenable shuts down stale scheduler before creating a new one`() {
        val executorField = CronAutoIdentity::class.java.getDeclaredField("executor").apply { isAccessible = true }
        File(root, CronAutoIdentity.TOGGLE_FILE).writeText("")
        PolicyState.installStateForTesting(state(buildIdentity = true).toString())

        try {
            CronAutoIdentity.refreshEnabled()
            val first = executorField.get(CronAutoIdentity) as java.util.concurrent.ScheduledExecutorService

            Files.delete(File(root, CronAutoIdentity.TOGGLE_FILE).toPath())
            CronAutoIdentity.refreshEnabled()
            assertFalse(CronAutoIdentity.isRunningForTesting())
            assertTrue("Disabled Auto Identity must shut down its executor", first.isShutdown)

            File(root, CronAutoIdentity.TOGGLE_FILE).writeText("")
            CronAutoIdentity.refreshEnabled()
            val second = executorField.get(CronAutoIdentity)
            assertNotSame("Re-enable must not reuse a shutdown executor", first, second)
        } finally {
            CronAutoIdentity.stop()
        }
    }

    @Test
    fun `global worker exists only while marker and Build Identity are both enabled`() {
        File(root, CronAutoIdentity.TOGGLE_FILE).writeText("")
        PolicyState.installStateForTesting(state(buildIdentity = false).toString())

        CronAutoIdentity.refreshEnabled()
        assertFalse(CronAutoIdentity.isRunningForTesting())

        PolicyState.installStateForTesting(state(buildIdentity = true).toString())
        CronAutoIdentity.refreshEnabled()
        assertTrue(CronAutoIdentity.isRunningForTesting())

        Files.delete(File(root, CronAutoIdentity.TOGGLE_FILE).toPath())
        CronAutoIdentity.refreshEnabled()
        assertFalse(CronAutoIdentity.isRunningForTesting())
    }

    @Test
    fun `profile Auto Identity starts worker without global cron marker`() {
        PolicyState.installStateForTesting(
            state(buildIdentity = false, profiles = listOf(profile(identityRefresh = true))).toString(),
        )

        CronAutoIdentity.refreshEnabled()
        assertTrue(CronAutoIdentity.isRunningForTesting())
    }

    @Test
    fun `disabled profile Auto Identity cannot keep worker alive`() {
        val profile =
            JSONObject()
                .put("name", "Disabled")
                .put("enabled", false)
                .put("applications", JSONArray().put("com.example.disabled"))
                .put("template", JSONObject.NULL)
                .put("keybox", JSONObject.NULL)
                .put("privacy", "inherit")
                .put(
                    "features",
                    JSONObject()
                        .put("buildIdentity", true)
                        .put("identityRefresh", true),
                )
                .put("securityPatch", JSONObject())
                .put("rkpPassthrough", JSONObject.NULL)
                .put("drmPassthrough", JSONObject.NULL)
        PolicyState.installStateForTesting(state(buildIdentity = false, profiles = listOf(profile)).toString())

        CronAutoIdentity.refreshEnabled()
        assertFalse(CronAutoIdentity.isRunningForTesting())
    }

    @Test
    fun `disabling profile work during fetch prevents stale identity commit`() {
        PolicyState.installStateForTesting(
            state(buildIdentity = false, profiles = listOf(profile(identityRefresh = true))).toString(),
        )
        CronAutoIdentity.refreshEnabled()
        assertTrue(CronAutoIdentity.isRunningForTesting())

        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val worker =
            Thread {
                CronAutoIdentity.runNowForTesting {
                    fetchStarted.countDown()
                    check(releaseFetch.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release Auto Identity fetch" }
                    identityResult()
                }
            }

        worker.start()
        try {
            assertTrue("Auto Identity fetch did not start", fetchStarted.await(5, TimeUnit.SECONDS))

            PolicyState.installStateForTesting(
                state(buildIdentity = false, profiles = listOf(profile(identityRefresh = false))).toString(),
            )
            CronAutoIdentity.onPolicyChanged()
            assertFalse("Policy disable must invalidate scheduler ownership", CronAutoIdentity.isRunningForTesting())
        } finally {
            releaseFetch.countDown()
        }

        worker.join(5_000)
        assertFalse("Auto Identity worker did not terminate after cancellation", worker.isAlive)
        assertEquals("Cancelled refresh must not publish a profile generation", 0L, ProfileAutoIdentityStore.generation())
        assertFalse(
            "Cancelled refresh must not persist a stale profile snapshot",
            File(root, ProfileAutoIdentityStore.FILE_NAME).exists(),
        )
    }

    @Test
    fun `scope change during fetch retries without failure backoff`() {
        PolicyState.installStateForTesting(
            state(buildIdentity = false, profiles = listOf(profile(identityRefresh = true))).toString(),
        )
        CronAutoIdentity.refreshEnabled()
        assertTrue(CronAutoIdentity.isRunningForTesting())

        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val worker =
            Thread {
                CronAutoIdentity.runNowForTesting {
                    fetchStarted.countDown()
                    check(releaseFetch.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release Auto Identity fetch" }
                    identityResult()
                }
            }

        worker.start()
        try {
            assertTrue("Auto Identity fetch did not start", fetchStarted.await(5, TimeUnit.SECONDS))

            File(root, CronAutoIdentity.TOGGLE_FILE).writeText("")
            PolicyState.installStateForTesting(state(buildIdentity = true).toString())
            CronAutoIdentity.onPolicyChanged()
            assertTrue("Scope change must keep Auto Identity scheduling enabled", CronAutoIdentity.isRunningForTesting())
        } finally {
            releaseFetch.countDown()
        }

        worker.join(5_000)
        assertFalse("Auto Identity worker did not terminate after policy handoff", worker.isAlive)
        val status = CronAutoIdentity.statusJson()
        val now = System.currentTimeMillis()
        assertEquals("Policy handoff is not a fetch failure", 0, status.getInt("failureCount"))
        assertTrue("Policy handoff must not surface a scheduler error", status.isNull("lastError"))
        assertTrue("Policy handoff must schedule a retry", status.getLong("nextRunMs") > now)
        assertTrue(
            "Policy handoff must use the initial retry window instead of the five-minute failure backoff",
            status.getLong("nextRunMs") - now <= TimeUnit.MINUTES.toMillis(2),
        )
        assertEquals("Cancelled profile-scope refresh must not publish stale profile state", 0L, ProfileAutoIdentityStore.generation())
    }

    private fun profile(identityRefresh: Boolean): JSONObject =
        JSONObject()
            .put("name", "Banking")
            .put("enabled", true)
            .put("applications", JSONArray().put("com.example.bank"))
            .put("template", JSONObject.NULL)
            .put("keybox", JSONObject.NULL)
            .put("privacy", "inherit")
            .put(
                "features",
                JSONObject()
                    .put("buildIdentity", true)
                    .put("identityRefresh", identityRefresh),
            )
            .put("securityPatch", JSONObject())
            .put("rkpPassthrough", JSONObject.NULL)
            .put("drmPassthrough", JSONObject.NULL)

    private fun identityResult(): AutoIdentityManager.Result =
        AutoIdentityManager.Result(
            model = "Pixel 9",
            product = "tokay_beta",
            device = "tokay",
            fingerprint = "google/tokay_beta/tokay:17/BP31.260801.001/12345678:user/release-keys",
            buildId = "BP31.260801.001",
            incremental = "12345678",
            release = "17",
            securityPatch = "2026-08-05",
            securityPatchEstimated = false,
        )

    private fun state(
        buildIdentity: Boolean,
        profiles: List<JSONObject> = emptyList(),
    ): JSONObject =
        JSONObject()
            .put("version", PolicyState.SCHEMA_VERSION)
            .put(
                "features",
                JSONObject()
                    .put("buildIdentity", buildIdentity)
                    .put("attestationIdentity", false)
                    .put("telephonyIdentity", false)
                    .put("regionIdentity", false)
                    .put("identityRefresh", false)
                    .put("securityPatch", false),
            )
            .put(
                "securityPatch",
                JSONObject()
                    .put("automaticThresholdMonths", 6)
                    .put("system", JSONObject().put("mode", "automatic"))
                    .put("vendor", JSONObject().put("mode", "automatic"))
                    .put("boot", JSONObject().put("mode", "automatic")),
            )
            .put("profiles", JSONArray().also { array -> profiles.forEach(array::put) })
            .put("activeProfile", JSONObject.NULL)
}
