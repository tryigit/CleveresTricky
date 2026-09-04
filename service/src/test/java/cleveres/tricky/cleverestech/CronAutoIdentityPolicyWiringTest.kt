package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import cleveres.tricky.cleverestech.util.SecureFileOperations
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CronAutoIdentityPolicyWiringTest {
    private lateinit var root: File
    private lateinit var originalSecureFileImpl: SecureFileOperations

    @Before
    fun setUp() {
        Config.reset()
        root = Files.createTempDirectory("cron-policy-wiring-test").toFile()
        Config.setRootForTesting(root)
        PolicyState.setRootForTesting(root)
        originalSecureFileImpl = SecureFile.impl
        SecureFile.impl =
            object : SecureFileOperations {
                override fun writeText(
                    file: File,
                    content: String,
                ) {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                }

                override fun mkdirs(
                    file: File,
                    mode: Int,
                ) {
                    file.mkdirs()
                }

                override fun touch(
                    file: File,
                    mode: Int,
                ) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                }
            }
        CronAutoIdentity.configureForTesting(root)
    }

    @After
    fun tearDown() {
        CronAutoIdentity.stop()
        SecureFile.impl = originalSecureFileImpl
        PolicyState.resetForTesting()
        root.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `policy api stops profile scheduler when identity refresh is disabled`() {
        PolicyState.installStateForTesting(policy(profileRefresh = true).toString())
        CronAutoIdentity.refreshEnabled()
        assertTrue("profile Auto Identity should own a scheduler before the mutation", CronAutoIdentity.isRunningForTesting())

        val response =
            PolicyApi.serve(
                MockIHTTPSession(
                    uri = "/api/policy_state",
                    method = NanoHTTPD.Method.POST,
                    parameters = mapOf("data" to listOf(policy(profileRefresh = false).toString())),
                ),
            )

        assertEquals(NanoHTTPD.Response.Status.OK, response?.status)
        assertFalse(
            "successful policy mutations must re-evaluate and stop stale Auto Identity ownership",
            CronAutoIdentity.isRunningForTesting(),
        )
    }

    @Test
    fun `policy api never acquires identity commit barrier before policy state`() {
        PolicyState.installStateForTesting(policy(profileRefresh = true).toString())
        CronAutoIdentity.refreshEnabled()
        assertTrue(PolicyState.hasProfileAutoIdentityWork())

        val finished = CountDownLatch(1)
        val response = AtomicReference<NanoHTTPD.Response?>()
        val worker =
            Thread {
                try {
                    response.set(
                        PolicyApi.serve(
                            MockIHTTPSession(
                                uri = "/api/policy_state",
                                method = NanoHTTPD.Method.POST,
                                parameters = mapOf("data" to listOf(policy(profileRefresh = false).toString())),
                            ),
                        ),
                    )
                } finally {
                    finished.countDown()
                }
            }.apply {
                isDaemon = true
                name = "policy-lock-order-regression"
            }

        IdentityCoordinator.withCommitBarrier {
            worker.start()
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (PolicyState.hasProfileAutoIdentityWork() && System.nanoTime() < deadlineNanos) {
                Thread.sleep(5)
            }
            assertFalse(
                "Policy publication must not wait for the identity barrier before acquiring PolicyState",
                PolicyState.hasProfileAutoIdentityWork(),
            )
            assertFalse(
                "Published policy should wait at the scheduler commit barrier until its current owner releases it",
                finished.await(25, TimeUnit.MILLISECONDS),
            )
        }

        assertTrue("Policy API did not finish after the identity barrier was released", finished.await(5, TimeUnit.SECONDS))
        assertEquals(NanoHTTPD.Response.Status.OK, response.get()?.status)
        assertFalse(CronAutoIdentity.isRunningForTesting())
    }

    private fun policy(profileRefresh: Boolean): JSONObject {
        val features =
            JSONObject()
                .put("buildIdentity", false)
                .put("attestationIdentity", false)
                .put("telephonyIdentity", false)
                .put("regionIdentity", false)
                .put("identityRefresh", false)
                .put("securityPatch", false)
        val patch = JSONObject().put("mode", "device_default")
        val profile =
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
                        .put("identityRefresh", profileRefresh),
                )
                .put("securityPatch", JSONObject())
                .put("rkpPassthrough", JSONObject.NULL)
                .put("drmPassthrough", JSONObject.NULL)

        return JSONObject()
            .put("version", PolicyState.SCHEMA_VERSION)
            .put("features", features)
            .put(
                "securityPatch",
                JSONObject()
                    .put("automaticThresholdMonths", 6)
                    .put("system", JSONObject(patch.toString()))
                    .put("vendor", JSONObject(patch.toString()))
                    .put("boot", JSONObject(patch.toString())),
            )
            .put("profiles", JSONArray().put(profile))
            .put("activeProfile", JSONObject.NULL)
    }
}
