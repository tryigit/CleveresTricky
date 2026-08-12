package cleveres.tricky.cleverestech

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

internal object PolicyApi {
    fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val uri = session.uri
        val method = session.method
        if (uri == "/api/policy_state" && method == NanoHTTPD.Method.GET) {
            return json(NanoHTTPD.Response.Status.OK, PolicyState.stateJson())
        }
        if (uri == "/api/policy_state" && method == NanoHTTPD.Method.POST) {
            val data = parameter(session, "data")
                ?: return text(NanoHTTPD.Response.Status.BAD_REQUEST, "Missing policy state")
            return PolicyState.replaceFromJson(data).fold(
                onSuccess = { json(NanoHTTPD.Response.Status.OK, it) },
                onFailure = { text(NanoHTTPD.Response.Status.BAD_REQUEST, it.message ?: "Invalid policy state") },
            )
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
                    PolicyState.profileAction(action, payload).fold(
                        onSuccess = { json(NanoHTTPD.Response.Status.OK, it) },
                        onFailure = { text(NanoHTTPD.Response.Status.BAD_REQUEST, it.message ?: "Invalid profile request") },
                    )
                },
                onFailure = { text(NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid profile request") },
            )
        }
        return null
    }

    private fun parameter(session: NanoHTTPD.IHTTPSession, name: String): String? =
        session.parameters[name]?.singleOrNull()?.takeIf { it.length <= 1024 * 1024 }

    private fun json(status: NanoHTTPD.Response.Status, value: JSONObject): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", value.toString())

    private fun text(status: NanoHTTPD.Response.Status, value: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, value)
}