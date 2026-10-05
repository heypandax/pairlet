package dev.ccpocket.daemon.acp

import dev.ccpocket.daemon.agent.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * The `session/set_config_option` chain every ACP backend runs between the session opening and the prompt gate
 * (dsh: model + reasoning effort; kimi: model + mode), and — for dsh — on a live user switch too.
 *
 * ```
 *   start(writes) → write 1 ──answer──▶ write 2 ──answer──▶ … last ──answer──▶ Host.onChainSettled → gate opens
 *                      └─ no answer within timeoutMs ─▶ launch write: startup fails (stage [stageConfig])
 *                                                       user switch: reported like a refusal, chain moves on
 * ```
 *
 * Why the gate waits: a prompt that slipped through between the session opening and the write settling would
 * run the opening turn on settings the user did NOT pick. Each write is a request, so the gate opens on the
 * LAST write's RESPONSE — never on hope. A REFUSED write still advances the chain: queued prompts must never
 * be held hostage by a preference the agent refused. A write that never answers is bounded like the handshake,
 * its verdict delivered through the pump so it is ordered with the real answer (whichever reaches the pump
 * first claims the write).
 *
 * What stays in the backend ([Host]): which writes a session needs (and how a value is spelled on the wire),
 * how an answer's read-back reaches the header, and anything it announces once the chain settles.
 */
class AcpConfigChain(
    private val client: AcpClient,
    private val log: Logger,
    /** `kimi` / `dsh`: the log prefix and the namespace of the timeout frame. */
    private val tag: String,
    /** The agent as named in a timeout reason ("the DeepSeek Harness"). */
    private val agentName: String,
    /** The startup stage a LAUNCH-time write that never answered fails. */
    private val stageConfig: String,
    /** How long one write may go unanswered. */
    private val timeoutMs: Long,
    private val host: Host,
) {
    /** One pending `session/set_config_option`. [announce] marks a USER-driven switch, which is allowed to say
     *  out loud that it failed; a launch-time application stays quiet (a message before the first turn reads as
     *  output the agent never wrote). [flushAfter] carries the "open the prompt gate when this settles" duty
     *  through the response. */
    data class Write(
        val configId: String,
        val value: String,
        val announce: Boolean,
        val flushAfter: Boolean = false,
    )

    /** The backend-specific half. Everything runs on the parse pump. */
    interface Host {
        /** The user-facing name of what [configId] sets ("the model"). */
        fun describe(configId: String): String

        /** The agent answered [write] with its resulting state; returns the events that announce the read-back. */
        suspend fun onApplied(write: Write, result: JsonObject?): List<AgentEvent> = emptyList()

        /**
         * The chain is over: about to open the prompt gate ([timedOut] false), or about to fail the startup
         * because a launch write never answered ([timedOut] true). The returned events precede the gate's /
         * the failure's own.
         */
        suspend fun onChainSettled(timedOut: Boolean): List<AgentEvent> = emptyList()
    }

    /** The pump-bound verdict of [watch] (namespaced like [AcpSynthetic]'s frames). */
    private val timeoutType = "cc-pocket/$tag-config-timeout"

    /** outstanding write id → what it was trying to do, so its answer can be reported and the chain continued. */
    private val ids = ConcurrentHashMap<Long, Write>()

    /** The remaining writes of the current chain, in order. Written from the parse pump AND from a backend's
     *  own scope (a dsh user switch), so it is concurrent by construction. */
    private val pending = ConcurrentLinkedDeque<Write>()

    /** Owns the per-write watchdogs; replaced on every [reset], cancelled by [close]. */
    @Volatile private var scope: CoroutineScope? = null

    /** A fresh process: forget the previous one's writes and watchdogs. */
    fun reset() {
        ids.clear(); pending.clear()
        scope?.let { runCatching { it.cancel() } }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /** The process ended: its watchdogs have nothing left to guard. */
    fun close() {
        runCatching { scope?.cancel() }
        scope = null
    }

    /**
     * Queue [writes] and send the first; only the LAST opens the prompt gate. Returns true when a write really
     * went out, i.e. when the caller must wait for its response rather than opening the gate itself.
     */
    suspend fun start(writes: List<Write>): Boolean {
        val sid = client.sessionId ?: return false
        if (writes.isEmpty()) return false
        val chain = writes.mapIndexed { i, w -> w.copy(flushAfter = i == writes.lastIndex) }
        pending.addAll(chain.drop(1))
        send(sid, chain.first())
        return true
    }

    // ---- inbound (wire these into AcpClient.Host) ----

    /** The answer to one of our writes; null when [id] is not ours. */
    suspend fun onResponse(id: Long, result: JsonObject?): List<AgentEvent>? =
        ids.remove(id)?.let { host.onApplied(it, result) + continueChain(it) }

    /** The error answer to one of our writes; null when [id] is not ours. */
    suspend fun onErrorResponse(id: Long?, why: String): List<AgentEvent>? =
        ids.remove(id ?: -1)?.let { failed(it, why) }

    /** Our own timeout frame; null when [type] is not ours. */
    suspend fun onSyntheticFrame(type: String?, root: JsonObject): List<AgentEvent>? =
        if (type == timeoutType) timedOut(root.long("id")) else null

    // ---- the chain ----

    private suspend fun send(sid: String, write: Write) {
        val id = client.rpc.nextId()
        ids[id] = write
        scope?.launch { watch(id) }
        client.rpc.send(id, "session/set_config_option", buildJsonObject {
            put("sessionId", sid)
            put("configId", write.configId) // NOT optionId (a zod error on dsh)
            put("value", write.value)
        })
    }

    /**
     * A write that never answers would hold the prompt gate shut forever — the launch chain opens it only on the
     * LAST write's response — leaving the opening prompt parked with no error and no terminal state. The same
     * bounded wait as the handshake watchdog, on a per-process scope (a relaunch cancels it).
     */
    private suspend fun watch(id: Long) {
        delay(timeoutMs)
        if (!ids.containsKey(id)) return // answered in time
        client.io?.inject?.invoke(buildJsonObject { put("type", timeoutType); put("id", id) }.toString())
    }

    /**
     * On the pump: [id] got no answer in time. A USER-driven switch is reported like a refused one and the chain
     * moves on (the session is already running on settings the user saw announced). A LAUNCH-time write fails the
     * session open instead: carrying on would run the opening turn on the agent's defaults while the user
     * believes their pick is in effect — so the waiting prompts are settled with an error naming the stage, and
     * later ones are refused with it until a relaunch.
     */
    private suspend fun timedOut(id: Long?): List<AgentEvent> {
        val write = id?.let { ids.remove(it) } ?: return emptyList() // already answered, or a previous process
        val why = "$agentName did not answer the request to set ${host.describe(write.configId)} (${write.value}) " +
            "within ${timeoutMs / 1000} s"
        log.warn("$tag set_config_option(${write.configId}=${write.value}) unanswered after ${timeoutMs}ms")
        if (write.announce) return failed(write, why)
        pending.clear()
        return host.onChainSettled(timedOut = true) + client.failHostStartup(stageConfig, why)
    }

    private suspend fun failed(write: Write, why: String): List<AgentEvent> {
        log.warn("$tag set_config_option(${write.configId}=${write.value}) failed: $why")
        // Only a user-driven switch says so out loud (see [Write.announce]).
        if (write.announce) client.injectNotice("⚠️ could not switch ${host.describe(write.configId)}: $why")
        return continueChain(write)
    }

    /** Send the next write of the chain, or — when this was the last one — open the prompt gate (returning the
     *  error turns of any prompt it refused). A FAILED write still advances. */
    private suspend fun continueChain(write: Write): List<AgentEvent> {
        val next = pending.pollFirst()
        val sid = client.sessionId
        if (next != null && sid != null) {
            send(sid, next)
            return emptyList()
        }
        // End of the chain — or the session went away under it, which must not strand the queue either.
        if (!write.flushAfter && next == null) return emptyList()
        return host.onChainSettled(timedOut = false) + client.openPromptGate()
    }

    companion object {
        /** The `currentValue` of the option [id] in one `configOptions` array (`session/new`, `session/load`,
         *  `session/resume` and `session/set_config_option` all answer with it), or null when it is absent. */
        fun currentValue(configOptions: JsonArray?, id: String): String? =
            configOptions?.firstOrNull { (it as? JsonObject)?.str("id") == id }
                ?.let { (it as JsonObject).str("currentValue") }
    }
}
