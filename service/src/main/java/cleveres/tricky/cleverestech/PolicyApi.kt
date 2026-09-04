package cleveres.tricky.cleverestech

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File

internal object PolicyApi {
    private val policyUris =
        setOf(
            "/api/policy_state",
            "/api/effective_state",
            "/api/profile_v2",
            "/api/identity_diagnostics",
        )

    fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val uri = session.uri
        val method = session.method
        if (uri in policyUris && !policyRuntimeReady()) {
            return text(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "Policy state is still initializing")
        }
        if (uri == "/api/policy_state" && method == NanoHTTPD.Method.GET) {
            return json(NanoHTTPD.Response.Status.OK, currentPolicyResponse())
        }
        if (uri == "/api/policy_state" && method == NanoHTTPD.Method.POST) {
            val data = parameter(session, "data")
                ?: return text(NanoHTTPD.Response.Status.BAD_REQUEST, "Missing policy state")
            return mutatePolicy("Invalid policy state") { PolicyState.replaceFromJson(data) }
        }
        if (uri == "/api/effective_state" && method == NanoHTTPD.Method.GET) {
            val packageName = parameter(session, "package")
                ?: return text(NanoHTTPD.Response.Status.BAD_REQUEST, "Missing package")
            return runCatching { PolicyState.effectiveStateJson(packageName) }.fold(
                onSuccess = { json(NanoHTTPD.Response.Status.OK, it) },
                onFailure = { text(NanoHTTPD.Response.Status.BAD_REQUEST, it.message ?: "Invalid package") },
            )
        }
        if (uri == "/api/profile_v2" && method == NanoHTTPD.Method.POST) {
            val action = parameter(session, "action")
                ?: return text(NanoHTTPD.Response.Status.BAD_REQUEST, "Missing profile action")
            val data = parameter(session, "data") ?: "{}"
            return runCatching { JSONObject(data) }.fold(
                onSuccess = { payload ->
                    mutatePolicy("Invalid profile request") { PolicyState.profileAction(action, payload) }
                },
                onFailure = { text(NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid profile request") },
            )
        }
        if (uri == "/api/identity_diagnostics" && method == NanoHTTPD.Method.GET) {
            return json(NanoHTTPD.Response.Status.OK, IdentityCoordinator.diagnosticsJson())
        }
        return null
    }

    private fun mutatePolicy(
        invalidMessage: String,
        mutation: () -> Result<JSONObject>,
    ): NanoHTTPD.Response =
        PolicyMutationCoordinator.mutate(
            preflight = { LegacyIdentityMarkers.preflight(Config.getConfigRoot()) },
            mutation = mutation,
            synchronizeCompatibility = { state ->
                LegacyIdentityMarkers.syncFromPolicyState(Config.getConfigRoot(), state)
            },
            captureBefore = { JSONObject(PolicyState.stateJson().toString()) },
            reconcileRuntime = { previousState, resultingState ->
                IdentityCoordinator.reconcilePolicyTransition(Config.getConfigRoot(), previousState, resultingState)
            },
        ).fold(
            onSuccess = { result ->
                CronAutoIdentity.onPolicyChanged()
                if (result.compatibilitySync == CompatibilitySyncStatus.PENDING) {
                    result.compatibilityError?.let { error ->
                        Logger.e("Policy state saved but early-boot identity markers could not be synchronized", error)
                    }
                }
                json(NanoHTTPD.Response.Status.OK, mutationResponse(result))
            },
            onFailure = { error ->
                if (error is CompatibilityPreflightException) {
                    Logger.e("Refusing policy mutation because identity compatibility markers are unsafe", error.cause ?: error)
                    text(NanoHTTPD.Response.Status.BAD_REQUEST, "Identity compatibility state is unsafe")
                } else {
                    text(NanoHTTPD.Response.Status.BAD_REQUEST, error.message ?: invalidMessage)
                }
            },
        )

    private fun currentPolicyResponse(): JSONObject =
        synchronized(PolicyState) {
            reconciledCompatibilityResponse(PolicyState.stateJson(), Config.getConfigRoot())
        }

    private fun policyRuntimeReady(): Boolean =
        runCatching { policyRuntimeReady(PolicyState.stateJson()) }.getOrDefault(false)

    internal fun policyRuntimeReady(state: JSONObject): Boolean =
        !state.optString("recovery").equals("bootstrap", ignoreCase = true)

    internal fun compatibilityStatusResponse(
        state: JSONObject,
        root: File,
    ): JSONObject {
        val synchronizedResult = LegacyIdentityMarkers.isSynchronized(root, state)
        val result =
            synchronizedResult.fold(
                onSuccess = { synchronized ->
                    PolicyMutationResult(
                        state = state,
                        compatibilitySync = if (synchronized) CompatibilitySyncStatus.OK else CompatibilitySyncStatus.PENDING,
                    )
                },
                onFailure = { error ->
                    PolicyMutationResult(
                        state = state,
                        compatibilitySync = CompatibilitySyncStatus.PENDING,
                        compatibilityError = error,
                    )
                },
            )
        return mutationResponse(result)
    }

    internal fun reconciledCompatibilityResponse(
        state: JSONObject,
        root: File,
    ): JSONObject {
        val synchronized = LegacyIdentityMarkers.isSynchronized(root, state)
        if (synchronized.getOrNull() == true) {
            return mutationResponse(PolicyMutationResult(state, CompatibilitySyncStatus.OK))
        }
        val result = retryCompatibility(state, root)
        if (result.compatibilitySync == CompatibilitySyncStatus.PENDING) {
            result.compatibilityError?.let { error ->
                Logger.e("Policy read could not reconcile early-boot identity compatibility markers", error)
            }
        }
        return mutationResponse(result)
    }

    internal fun retryCompatibility(
        state: JSONObject,
        root: File,
    ): PolicyMutationResult {
        val compatibilityResult =
            if (!state.optString("source").equals("v2", true)) {
                Result.success(Unit)
            } else {
                runCatching {
                    LegacyIdentityMarkers.preflight(root)
                    LegacyIdentityMarkers.syncFromPolicyState(root, state).getOrThrow()
                }
            }
        return PolicyMutationResult(
            state = state,
            compatibilitySync =
                if (compatibilityResult.isSuccess) CompatibilitySyncStatus.OK else CompatibilitySyncStatus.PENDING,
            compatibilityError = compatibilityResult.exceptionOrNull(),
        )
    }

    internal fun mutationResponse(result: PolicyMutationResult): JSONObject {
        val response = JSONObject(result.state.toString())
        val pending = result.compatibilitySync == CompatibilitySyncStatus.PENDING
        response.put("compatibilitySync", if (pending) "pending" else "ok")
        if (pending) {
            response.put(
                "compatibilityWarning",
                "Policy is saved, but early-boot compatibility markers are not synchronized. Retry before reboot by reloading this view.",
            )
        }
        result.runtimeTransition?.let { transition ->
            response.put("runtimeTransition", transition.toJson())
        }
        result.runtimeTransitionError?.let { error ->
            val errorCode =
                if (error is IdentityRuntimeSnapshotException) {
                    "snapshot_unavailable"
                } else {
                    "runtime_transition_failed"
                }
            response.put(
                "runtimeTransition",
                JSONObject().put("rebootRequired", true).put("error", errorCode),
            )
            response.put(
                "runtimeWarning",
                "Policy is saved, but live Identity changes were not applied. Reboot is required.",
            )
            Logger.e("Policy state saved but live Identity transition could not be prepared", error)
        }
        return response
    }

    private fun parameter(session: NanoHTTPD.IHTTPSession, name: String): String? =
        session.parameters[name]?.singleOrNull()?.takeIf { it.length <= 1024 * 1024 }

    private fun json(status: NanoHTTPD.Response.Status, value: JSONObject): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", value.toString())

    private fun text(status: NanoHTTPD.Response.Status, value: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, value)
}
