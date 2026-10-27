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

/**
 * The `session/set_config_option` chain every ACP backend runs between the session opening and the prompt gate
 * (dsh: model + reasoning effort; kimi: model + mode), and on a live user switch too.
 *
 * ```
 *   start(writes) → write 1 ──answer──▶ write 2 ──answer──▶ … queue drained ──▶ Host.onChainSettled → gate opens
 *                      └─ no answer within timeoutMs ─▶ launch write: startup fails (stage [stageConfig])
 *                                                       user switch: reported like a refusal, chain moves on
 * ```
 *
 * Why the gate waits: a prompt that slipped through between the session opening and the write settling would
 * run the opening turn on settings the user did NOT pick. Each write is a request, so the gate opens once the
 * queue has DRAINED — on the last answer, never on hope. A REFUSED write still advances the chain: queued prompts
 * must never be held hostage by a preference the agent refused — unless the write is [Write.required] (dsh's
 * model: the agent's default would bill a provider the user did not pick). A required launch write that fails
 * fails the open; a required USER switch that fails ([stageSwitch]) leaves the process refusing every prompt until
 * a relaunch ([AcpClient.failSettings]). A required write also counts only once the answer's read-back names it
 * ([Host.unconfirmed]). A write that never answers is bounded like the handshake, its verdict delivered through
 * the pump so it is ordered with the real answer (whichever reaches the pump first claims the write).
 *
 * ONE WRITE AT A TIME, in order, whoever queued it: a launch chain, a user switch arriving while it runs, and a
 * second switch right after the first all share one queue. That is what makes "the gate opens when the queue
 * drains" mean what it says — two interleaved chains used to open the gate on the first one's answer while the
 * second one's write was still out. A user switch queues SYNCHRONOUSLY ([enqueue], on the caller's thread) and is
 * sent from the backend's own scope ([kick]); [busy] tells the gate's open path that something is still pending.
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
    /** What a required USER switch that failed reports, ahead of the reason ([AcpClient.failSettings]). */
    private val stageSwitch: String = stageConfig,
) {
    /** One pending `session/set_config_option`. [announce] marks a USER-driven switch, which is allowed to say
     *  out loud that it failed; a launch-time application stays quiet (a message before the first turn reads as
     *  output the agent never wrote). [required]: see the class comment — a failure that must not let prompts
     *  run on what the agent had before. */
    data class Write(
        val configId: String,
        val value: String,
        val announce: Boolean,
        val required: Boolean = false,
    )

    /** The backend-specific half. Everything runs on the parse pump. */
    interface Host {
        /** The user-facing name of what [configId] sets ("the model"). */
        fun describe(configId: String): String

        /** The agent answered [write] with its resulting state; returns the events that announce the read-back. */
        suspend fun onApplied(write: Write, result: JsonObject?): List<AgentEvent> = emptyList()

        /**
         * After [onApplied], for a [Write.required] write only: why the agent's answer does NOT show [write] in
         * effect, or null when it does — or says nothing either way, in which case the success answer stands.
         */
        fun unconfirmed(write: Write, result: JsonObject?): String? = null

        /**
         * The queue drained: about to open the prompt gate ([timedOut] false), or a write failed in a way that
         * keeps it shut ([timedOut] true: a launch write that never answered or a required write that failed —
         * nothing is known any more about what the session runs). The returned events precede the gate's own and
         * follow the failure's (the stranded prompts settle first). Called again each time the queue drains.
         */
        suspend fun onChainSettled(timedOut: Boolean): List<AgentEvent> = emptyList()
    }

    /** The pump-bound verdict of [watch] (namespaced like [AcpSynthetic]'s frames). */
    private val timeoutType = "cc-pocket/$tag-config-timeout"

    /** Guards [queue] and the one-outstanding rule over [ids]: decisions about what to send next happen under it. */
    private val lock = Any()

    /** Writes not sent yet, in order. Guarded by [lock]. */
    private val queue = ArrayDeque<Write>()

    /** The outstanding write's id → what it was trying to do (at most one entry), so its answer can be reported
     *  and the chain continued. Changed only under [lock]; read lock-free by [watch]. */
    private val ids = ConcurrentHashMap<Long, Write>()

    /** Owns the per-write watchdogs; replaced on every [reset], cancelled by [close]. */
    @Volatile private var scope: CoroutineScope? = null

    /** A fresh process: forget the previous one's writes and watchdogs. */
    fun reset() {
        synchronized(lock) { ids.clear(); queue.clear() }
        scope?.let { runCatching { it.cancel() } }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /** The process ended: its watchdogs have nothing left to guard. */
    fun close() {
        runCatching { scope?.cancel() }
        scope = null
    }

    /** True while a write is queued or awaiting its answer — the prompt gate must stay shut meanwhile
     *  ([AcpClient.Host.holdsPromptGate]). Cheap and non-blocking: it is asked under the FIFO's lock. */
    fun busy(): Boolean = synchronized(lock) { queue.isNotEmpty() || ids.isNotEmpty() }

    /**
     * Queue [writes] and send the first unless a write is already awaiting its answer. Returns true when the caller
     * must wait for the queue to drain rather than opening the gate itself.
     */
    suspend fun start(writes: List<Write>): Boolean {
        if (client.sessionId == null) return false
        if (writes.isEmpty()) return false
        enqueue(writes)
        kick()
        return true
    }

    /** Queue [writes] synchronously, on the caller's thread — [busy] from this moment — and leave the sending to a
     *  [kick] from the caller's own scope. For a user switch on a command path that must not block on IO. */
    fun enqueue(writes: List<Write>) {
        synchronized(lock) { queue.addAll(writes) }
    }

    /** Send the head of the queue unless a write is already awaiting its answer. Safe from any thread. */
    suspend fun kick() {
        val sid = client.sessionId ?: return
        val next = synchronized(lock) {
            if (ids.isNotEmpty()) return
            val write = queue.removeFirstOrNull() ?: return
            val id = client.rpc.nextId()
            ids[id] = write
            id to write
        }
        send(sid, next.first, next.second)
    }

    // ---- inbound (wire these into AcpClient.Host) ----

    /** The answer to one of our writes; null when [id] is not ours. */
    suspend fun onResponse(id: Long, result: JsonObject?): List<AgentEvent>? {
        val write = synchronized(lock) { ids.remove(id) } ?: return null
        val applied = host.onApplied(write, result)
        // A required write counts once the agent's read-back names it — an answer reporting another value did not switch.
        if (write.required) host.unconfirmed(write, result)?.let { return applied + failed(write, it) }
        return applied + next()
    }

    /** The error answer to one of our writes; null when [id] is not ours. */
    suspend fun onErrorResponse(id: Long?, why: String): List<AgentEvent>? {
        val write = synchronized(lock) { ids.remove(id ?: -1) } ?: return null
        return failed(write, why)
    }

    /** Our own timeout frame; null when [type] is not ours. */
    suspend fun onSyntheticFrame(type: String?, root: JsonObject): List<AgentEvent>? =
        if (type == timeoutType) timedOut(root.long("id")) else null

    // ---- the chain ----

    private suspend fun send(sid: String, id: Long, write: Write) {
        scope?.launch { watch(id) }
        client.rpc.send(id, "session/set_config_option", buildJsonObject {
            put("sessionId", sid)
            put("configId", write.configId) // NOT optionId (a zod error on dsh)
            put("value", write.value)
        })
    }

    /**
     * A write that never answers would hold the prompt gate shut forever — it opens only once the queue drained —
     * leaving the opening prompt parked with no error and no terminal state. The same bounded wait as the handshake
     * watchdog, on a per-process scope (a relaunch cancels it).
     */
    private suspend fun watch(id: Long) {
        delay(timeoutMs)
        if (!ids.containsKey(id)) return // answered in time
        client.io?.inject?.invoke(buildJsonObject { put("type", timeoutType); put("id", id) }.toString())
    }

    /**
     * On the pump: [id] got no answer in time. A USER-driven switch is reported like a refused one ([failed]). A
     * LAUNCH-time write fails the session open instead: carrying on would run the opening turn on the agent's
     * defaults while the user believes their pick is in effect — so the waiting prompts are settled with an error
     * naming the stage, and later ones are refused with it until a relaunch.
     */
    private suspend fun timedOut(id: Long?): List<AgentEvent> {
        val write = id?.let { synchronized(lock) { ids.remove(it) } } ?: return emptyList() // answered, or a previous process
        val why = "$agentName did not answer the request to set ${host.describe(write.configId)} (${write.value}) " +
            "within ${timeoutMs / 1000} s"
        log.warn("$tag set_config_option(${write.configId}=${write.value}) unanswered after ${timeoutMs}ms")
        if (write.announce) return failed(write, why)
        synchronized(lock) { queue.clear() }
        // the stranded prompts settle first; the host's announcement follows the failure
        return client.failHostStartup(stageConfig, why) + host.onChainSettled(timedOut = true)
    }

    private suspend fun failed(write: Write, why: String): List<AgentEvent> {
        log.warn("$tag set_config_option(${write.configId}=${write.value}) failed: $why")
        if (write.required) {
            // Nothing after it may run: what the session is on is no longer what the user picked.
            synchronized(lock) { queue.clear() }
            val reason = "${host.describe(write.configId)} was not applied: $why"
            val settled = if (write.announce) client.failSettings(stageSwitch, reason) else client.failHostStartup(stageConfig, reason)
            return settled + host.onChainSettled(timedOut = true)
        }
        // Only a user-driven switch says so out loud (see [Write.announce]).
        if (write.announce) client.injectNotice("⚠️ could not switch ${host.describe(write.configId)}: $why")
        return next()
    }

    /** Send the next queued write, or — the queue drained — tell the host and open the prompt gate (returning the
     *  error turns of any prompt it refused). A FAILED write that was not required still advances. */
    private suspend fun next(): List<AgentEvent> {
        if (synchronized(lock) { queue.isNotEmpty() }) {
            kick()
            return emptyList()
        }
        // A write queued and sent meanwhile (a user switch's own kick) settles the chain on ITS answer instead.
        if (busy()) return emptyList()
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
