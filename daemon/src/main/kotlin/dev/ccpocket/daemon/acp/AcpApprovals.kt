package dev.ccpocket.daemon.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Outstanding `session/request_permission` server→client requests, answered with one of the options the
 * agent offered — the exact provider-neutral shape [dev.ccpocket.daemon.agent.PermissionBridge] expects.
 */
class AcpApprovals(private val rpc: AcpRpc) {
    private data class Pending(val rpcId: JsonElement, val options: JsonArray)

    /** askId → the JSON-RPC request id + the options it offered (their optionIds are what we answer with). */
    private val pending = ConcurrentHashMap<String, Pending>()

    /** Record a permission request; returns the ask id the card carries (the request id's own text). */
    fun register(rpcId: JsonElement, params: JsonObject?): String {
        val askId = askIdOf(rpcId)
        pending[askId] = Pending(rpcId, params?.arr("options") ?: JsonArray(emptyList()))
        return askId
    }

    /** Answer [askId] with the option matching the decision, or `cancelled` when none matches. Returns false
     *  when nothing was pending (already answered, or from a previous process). */
    suspend fun respond(askId: String, allow: Boolean, remember: Boolean): Boolean {
        val request = pending.remove(askId) ?: return false
        val optionId = pickOption(request.options, allow, remember)
        val outcome = if (optionId != null) {
            buildJsonObject { put("outcome", "selected"); put("optionId", optionId) }
        } else {
            buildJsonObject { put("outcome", "cancelled") } // nothing matched → cancel beats guessing
        }
        rpc.respondResult(request.rpcId, buildJsonObject { put("outcome", outcome) })
        return true
    }

    /** A fresh process: the previous one's requests can no longer be answered. */
    fun clear() = pending.clear()

    companion object {
        fun askIdOf(rpcId: JsonElement): String = (rpcId as? JsonPrimitive)?.contentOrNull ?: rpcId.toString()

        /** The option whose `kind` matches the decision. Kinds are allow_once / allow_always / reject_once /
         *  reject_always (ACP spec); [remember] prefers the `_always` allow. Ids are the agent's own strings. */
        fun pickOption(options: JsonArray, allow: Boolean, remember: Boolean): String? {
            val byKind = options.mapNotNull { it as? JsonObject }
                .mapNotNull { o -> o.str("optionId")?.let { (o.str("kind") ?: "") to it } }
            fun of(vararg kinds: String): String? =
                kinds.firstNotNullOfOrNull { k -> byKind.firstOrNull { it.first == k }?.second }
            return if (allow) {
                if (remember) of("allow_always", "allow_once") else of("allow_once", "allow_always")
            } else {
                of("reject_once", "reject_always")
            }
        }
    }
}
