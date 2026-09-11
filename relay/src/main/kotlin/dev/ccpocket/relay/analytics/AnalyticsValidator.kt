package dev.ccpocket.relay.analytics

import dev.ccpocket.observability.AnalyticsCatalog
import kotlinx.serialization.json.*

/** A validated event ready for forwarding: the whitelisted params as GA4 will see them. */
data class ValidatedEvent(val name: String, val environment: String, val params: JsonObject)

data class ValidatedCollect(val clientId: String, val sessionId: String, val events: List<ValidatedEvent>)

/**
 * Strict request validation against [AnalyticsCatalog] (DESKTOP-GA4-INGRESS.md §3–4). Anything not on the
 * whitelist rejects the whole request: there is no free-text key through which a prompt, path, session
 * name or identifier could travel. Failures carry only an error CODE, never the offending value.
 */
object AnalyticsValidator {
    val clientId = AnalyticsCatalog.clientId
    class Rejected(val code: String) : Exception(code)

    private val allowedParams = AnalyticsCatalog.params + AnalyticsCatalog.transportParams + "app_version"

    fun parseRegister(body: String): String {
        val obj = parseObject(body)
        if (obj["v"]?.jsonPrimitive?.intOrNull != 1) throw Rejected("bad_request")
        val id = obj["install_id"]?.let { str(it) } ?: throw Rejected("bad_request")
        if (!clientId.matches(id)) throw Rejected("bad_request")
        return id
    }

    fun parseCollect(body: String, tokenInstallId: String): ValidatedCollect {
        val obj = parseObject(body)
        if (obj["v"]?.jsonPrimitive?.intOrNull != 1) throw Rejected("bad_request")
        val cid = obj["client_id"]?.let { str(it) } ?: throw Rejected("bad_request")
        if (!clientId.matches(cid)) throw Rejected("bad_request")
        if (cid != tokenInstallId) throw Rejected("unauthorized")
        val sid = obj["session_id"]?.let { str(it) } ?: throw Rejected("bad_request")
        if (!AnalyticsCatalog.sessionId.matches(sid)) throw Rejected("bad_request")
        val events = obj["events"] as? JsonArray ?: throw Rejected("bad_request")
        if (events.isEmpty()) throw Rejected("bad_request")
        if (events.size > AnalyticsCatalog.MAX_EVENTS_PER_REQUEST) throw Rejected("too_many_events")
        return ValidatedCollect(cid, sid, events.map { validateEvent(it as? JsonObject ?: throw Rejected("bad_request")) })
    }

    private fun validateEvent(e: JsonObject): ValidatedEvent {
        val name = e["name"]?.let { str(it) } ?: throw Rejected("invalid_event")
        if (name !in AnalyticsCatalog.events) throw Rejected("invalid_event")
        val params = e["params"] as? JsonObject ?: throw Rejected("invalid_param")
        if (params.keys.any { it in AnalyticsCatalog.serverOwnedParams }) throw Rejected("invalid_param")
        val out = buildJsonObject {
            for ((k, v) in params) {
                if (k !in allowedParams) throw Rejected("invalid_param")
                val p = v as? JsonPrimitive ?: throw Rejected("invalid_param")
                when {
                    p.isString -> {
                        val s = p.content
                        val ok = if (k == "app_version") AnalyticsCatalog.appVersion.matches(s)
                        else s.length <= AnalyticsCatalog.MAX_STRING_LENGTH && AnalyticsCatalog.stringValue.matches(s)
                        if (!ok) throw Rejected("invalid_param")
                        put(k, s)
                    }
                    else -> {
                        val n = p.longOrNull ?: throw Rejected("invalid_param") // booleans/null/floats rejected
                        if (n < -AnalyticsCatalog.MAX_INTEGER_MAGNITUDE || n > AnalyticsCatalog.MAX_INTEGER_MAGNITUDE) throw Rejected("invalid_param")
                        put(k, n)
                    }
                }
            }
        }
        if (out["app_platform"]?.jsonPrimitive?.contentOrNull != "desktop") throw Rejected("platform_mismatch")
        if (out["edition"]?.jsonPrimitive?.contentOrNull != "desktop") throw Rejected("platform_mismatch")
        if (out["analytics_schema"]?.jsonPrimitive?.contentOrNull != AnalyticsCatalog.SCHEMA) throw Rejected("invalid_param")
        val env = out["app_environment"]?.jsonPrimitive?.contentOrNull ?: throw Rejected("invalid_param")
        if (env !in AnalyticsCatalog.environments) throw Rejected("invalid_param")
        return ValidatedEvent(name, env, out)
    }

    private fun parseObject(body: String): JsonObject =
        runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: throw Rejected("bad_request")

    private fun str(e: JsonElement): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
}
