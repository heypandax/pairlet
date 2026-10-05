package dev.ccpocket.daemon.acp

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.protocol.ImageData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.Logger

/**
 * The client half of the Agent Client Protocol v1 (newline-delimited JSON-RPC 2.0 on the agent's stdio) that
 * every ACP backend shares — today [dev.ccpocket.daemon.kimi.KimiBackend] and [dev.ccpocket.daemon.dsh.DshBackend].
 *
 * ```
 *   attach     → initialize ──answer──▶ session/new | session/load | session/resume ──answer──▶ Host.onSessionOpened
 *   parse      → synthetic frames | server requests | notifications | responses
 *   sendPrompt → session/prompt (one in flight; the rest wait in the FIFO until the gate opens / the turn settles)
 * ```
 *
 * What lives here: the request ids ([AcpRpc]); the `initialize` handshake and the image capability it
 * advertises; the session open; the single-flight prompt FIFO with its gate ([AcpPromptFifo]); the
 * consumption receipt synthesized on every prompt settle (no ACP agent we drive sends `user_message_chunk`,
 * so the settle IS the receipt — without it Conversation's ledger re-runs old prompts on every relaunch);
 * the synthetic frames ([AcpSynthetic]); approvals ([AcpApprovals]); `session/cancel`; `-32601` for every
 * server request we do not serve (an unanswered one hangs the agent's turn); and the startup-failure
 * terminal state with its handshake watchdog.
 *
 * What stays in the backend ([Host]): what an open session announces, how `session/update` payloads become
 * events, what a permission card shows, and any requests of its own (dsh's config writes).
 */
class AcpClient(
    private val config: Config,
    private val log: Logger,
    private val host: Host,
) {
    /** How a backend's agent differs on the parts this client owns. */
    class Config(
        /** `kimi` / `dsh`: the log prefix and the namespace of the synthetic frames. */
        val tag: String,
        /** The product named in user-facing refusals ("Kimi Code", "DeepSeek Harness"). */
        val productName: String,
        val resume: Resume,
        /** The startup stages a failure can land in — each asks the user for something different. */
        val stageHandshake: String,
        val stageNew: String,
        val stageResume: String,
        val handshakeTimeoutMs: Long,
        /** The reason given when `initialize` never answers (evaluated when the watchdog fires). */
        val handshakeHint: () -> String,
        /** A JSON-RPC error object → the one line a user is shown. */
        val describeError: (JsonObject?) -> String,
        /**
         * Keep the previous process's session-open request id across a relaunch. dsh's backend never reset
         * it, which made its handshake watchdog guard only the FIRST process a backend instance launched;
         * kept as-is so this refactor changes no behaviour.
         */
        val keepSessionOpenIdAcrossRelaunch: Boolean = false,
    )

    /** How a recorded session is reopened. */
    enum class Resume(val method: String, val replaysHistory: Boolean) {
        /** `session/load` REPLAYS the whole history as `session/update`s before answering (kimi). The daemon
         *  replays history from disk itself, so those updates are dropped until the answer lands. */
        LOAD("session/load", replaysHistory = true),

        /** `session/resume` restores the session WITHOUT replaying anything (dsh). */
        RESUME("session/resume", replaysHistory = false),
    }

    /** The backend-specific half. Everything runs on the parse pump unless noted. */
    interface Host {
        /**
         * The session-open request answered with [sessionId] (the one sent, for a resume answering without
         * one). The host binds it — [openPromptGate] with `bind`, or [bindSession] and a later
         * [openPromptGate] once its own preconditions hold — and returns the events to announce.
         */
        suspend fun onSessionOpened(sessionId: String, result: JsonObject?): List<AgentEvent>

        /** One `session/update` payload (`params.update`) of this session. */
        fun onUpdate(update: JsonObject): List<AgentEvent>

        /** The card of a `session/request_permission`: the tool name and the input shown to the human. */
        fun permissionCard(params: JsonObject?): Pair<String, JsonObject>

        /** The answer to a request the host sent itself through [rpc]; null when the id is not the host's. */
        suspend fun onResponse(id: Long, result: JsonObject?): List<AgentEvent>? = null

        /** The error answer to a request the host sent itself; null when the id is not the host's. */
        suspend fun onErrorResponse(id: Long?, why: String): List<AgentEvent>? = null

        /** A synthetic frame of the host's own (one it injected); null when [type] is not the host's. */
        suspend fun onSyntheticFrame(type: String?, root: JsonObject): List<AgentEvent>? = null
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The JSON-RPC writer of the CURRENT process — the host sends its own requests through it too. */
    val rpc = AcpRpc { io?.writeLine?.invoke(it) }
    private val prompts = AcpPromptFifo(rpc::nextId)
    private val approvals = AcpApprovals(rpc)
    private val synthetic = AcpSynthetic(config.tag)

    /** Owns the handshake watchdog; the job is replaced on every [attach] and cancelled by [processEnded]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var watchdog: Job? = null

    @Volatile var io: AgentIo? = null
        private set
    @Volatile var workdir: String = ""
        private set
    @Volatile var resumeId: String? = null
        private set
    @Volatile var sessionId: String? = null
        private set

    /**
     * Why this session will never open, once a startup stage failed (issue #388; audit 2026-10-04 H1).
     *
     * Set by [failStartup], cleared by [attach]. It stops the watchdog from volunteering a second, WRONG
     * diagnosis over a stage that already reported a real one; it settles prompts that arrive AFTER the
     * failure, which would otherwise queue behind a gate that can never open; and it records that this
     * process is done trying — nothing here retries a stage or opens a replacement session, because a resume
     * that failed must stay the session the user asked for.
     */
    @Volatile private var openFailure: String? = null

    /** `agentCapabilities.promptCapabilities.image` off THIS process's `initialize` answer (issue #377) — only
     *  an explicit `true` counts, and every attach forgets the previous process's answer. */
    @Volatile private var imagePrompts = false

    @Volatile private var initializeId: Long = -1
    @Volatile private var sessionOpenId: Long = -1

    /** Inside a history-replaying `session/load` (see [Resume.LOAD]): its `session/update`s are dropped. */
    @Volatile private var replaying = false

    // ---- lifecycle ----

    /** A fresh process: forget the previous one's protocol state and start the handshake. */
    suspend fun attach(io: AgentIo, workdir: String, resumeId: String?) {
        this.io = io
        this.workdir = workdir
        this.resumeId = resumeId
        sessionId = null
        openFailure = null
        if (!config.keepSessionOpenIdAcrossRelaunch) sessionOpenId = -1
        replaying = false
        imagePrompts = false
        prompts.reset()
        approvals.clear()
        watchdog?.cancel()
        // the session open happens when the initialize answer lands
        initializeId = rpc.request("initialize", buildJsonObject {
            put("protocolVersion", ACP_PROTOCOL_VERSION)
            putJsonObject("clientCapabilities") {
                // We serve no filesystem or terminal on the agent's behalf; the agent does its own IO.
                putJsonObject("fs") { put("readTextFile", false); put("writeTextFile", false) }
            }
        })
        watchdog = scope.launch { watchHandshake(io) }
    }

    /** The process ended: its watchdog has nothing left to guard. */
    fun processEnded() {
        watchdog?.cancel()
    }

    /**
     * An `initialize` that never answers is the one startup failure no frame can report (a binary that shares
     * the name but does not speak ACP v1, a dsh too old to have the profile). Bounded and generous — a cold
     * start can take seconds, and a false accusation is worse than waiting. Once a session open was sent, its
     * own error rides the wire.
     */
    private suspend fun watchHandshake(owner: AgentIo) {
        delay(config.handshakeTimeoutMs)
        if (io !== owner || sessionOpenId >= 0 || sessionId != null || openFailure != null) return
        injectStartupFailure(config.stageHandshake, config.handshakeHint())
    }

    // ---- inbound ----

    suspend fun parse(line: String): List<AgentEvent> {
        val t = line.trim()
        if (t.isEmpty()) return emptyList()
        val root = runCatching { json.parseToJsonElement(t) }.getOrNull() as? JsonObject
            ?: return listOf(AgentEvent.Unparseable(t))
        // Frames we injected ourselves never travelled to the agent; they carry our own namespaced type.
        readSynthetic(root)?.let { return it }
        val method = root.str("method")
        val idEl = root["id"]?.takeIf { it !is JsonNull }
        return runCatching {
            when {
                method != null && idEl != null -> handleServerRequest(method, idEl, root.obj("params"))
                method != null -> handleNotification(method, root.obj("params"))
                root.containsKey("result") -> handleResponse(idEl, root.obj("result"))
                root.containsKey("error") -> handleErrorResponse(idEl, root.obj("error"))
                else -> emptyList()
            }
        }.getOrElse { log.warn("${config.tag} parse failed: ${it.message}"); emptyList() }
    }

    private suspend fun readSynthetic(root: JsonObject): List<AgentEvent>? {
        val type = root.str("type")
        return when (type) {
            // A prompt reserved but refused settles here exactly once, like its error response. A stale id
            // (already settled, or reserved by a previous process) says nothing.
            synthetic.refusalType -> root.long("id")?.let { prompts.settle(it) }
                ?.let { failedPrompt(it, root.str("message").orEmpty()) }.orEmpty()
            synthetic.errorType -> acpErrorEvents(root.str("message").orEmpty())
            // A message with no verdict about the turn — the turn itself is untouched (issue #291).
            synthetic.noticeType -> listOf(AgentEvent.AssistantText(root.str("message").orEmpty()))
            else -> host.onSyntheticFrame(type, root)
        }
    }

    private suspend fun handleResponse(idEl: JsonElement?, result: JsonObject?): List<AgentEvent> {
        val id = (idEl as? JsonPrimitive)?.longOrNull ?: return emptyList()
        if (id == initializeId) {
            val image = result?.obj("agentCapabilities")?.obj("promptCapabilities")?.get("image") as? JsonPrimitive
            imagePrompts = image != null && !image.isString && image.booleanOrNull == true
            openSession()
            return emptyList()
        }
        if (id == sessionOpenId) return onSessionOpenAnswered(result)
        host.onResponse(id, result)?.let { return it }
        val consumed = prompts.settle(id) ?: return emptyList()
        // Settle → the consumption receipt BEFORE the TurnResult, so the ledger entry is gone by the time the
        // task-grant check (maybeEndTaskOnSettle) runs — then let the next queued prompt out.
        return listOf(AgentEvent.UserReplay(consumed)) + promptDone(result) + flushQueuedPrompt()
    }

    private suspend fun handleErrorResponse(idEl: JsonElement?, error: JsonObject?): List<AgentEvent> {
        val id = (idEl as? JsonPrimitive)?.longOrNull
        val why = config.describeError(error)
        host.onErrorResponse(id, why)?.let { return it }
        // The two startup stages, told apart (issue #388): a failed stage leaves a session that will never open,
        // so every prompt waiting on it settles (an auth wall lands here on the session open, too).
        if (id != null && id == initializeId) return failStartup(config.stageHandshake, why)
        if (id != null && id == sessionOpenId) {
            replaying = false
            return failStartup(if (resumeId != null) config.stageResume else config.stageNew, why)
        }
        val consumed = id?.let { prompts.settle(it) }
        if (consumed != null) return failedPrompt(consumed, why)
        log.warn("${config.tag} error response id=$id: $why")
        return emptyList()
    }

    private suspend fun openSession() {
        val rid = resumeId
        sessionOpenId = if (rid != null) {
            replaying = config.resume.replaysHistory
            rpc.request(config.resume.method, buildJsonObject {
                put("sessionId", rid)
                put("cwd", workdir)
                putJsonArray("mcpServers") {}
            })
        } else {
            rpc.request("session/new", buildJsonObject {
                put("cwd", workdir)
                putJsonArray("mcpServers") {}
            })
        }
    }

    private suspend fun onSessionOpenAnswered(result: JsonObject?): List<AgentEvent> {
        replaying = false
        // session/new answers {sessionId, …}; a resume answers for the id we sent. A session/new with no id is a
        // session nobody can address — the same dead end as an error answer, settled the same way.
        val sid = result?.str("sessionId") ?: resumeId
            ?: return failStartup(config.stageNew, "${config.tag} did not return a session id")
        return host.onSessionOpened(sid, result)
    }

    private fun promptDone(result: JsonObject?): List<AgentEvent> {
        // ACP prompt response: {stopReason: end_turn | cancelled | max_tokens | refusal | …}
        val stop = result?.str("stopReason")
        return listOf(
            AgentEvent.TurnResult(
                finalText = null, // already streamed as agent_message_chunk
                usage = null, // ACP carries no per-turn totals in the response; occupancy rides the updates
                isError = stop == "refusal",
            ),
        )
    }

    private fun handleNotification(method: String, params: JsonObject?): List<AgentEvent> {
        params ?: return emptyList()
        return when (method) {
            "session/update" -> {
                if (replaying) return emptyList() // historical replay from session/load — drop
                // The stdio connection is single-session, but the agent stamps every update anyway; a frame for
                // another session (a sub-agent's) must never be spliced into this chat.
                val sid = params.str("sessionId")
                if (sid != null && sessionId != null && sid != sessionId) return emptyList()
                host.onUpdate(params.obj("update") ?: return emptyList())
            }
            else -> emptyList()
        }
    }

    private suspend fun handleServerRequest(method: String, idEl: JsonElement, params: JsonObject?): List<AgentEvent> =
        when (method) {
            "session/request_permission" -> {
                val askId = approvals.register(idEl, params)
                val (name, input) = host.permissionCard(params)
                listOf(AgentEvent.ControlRequest(askId, name, input))
            }
            // We declared no fs/terminal capabilities, so nothing else should arrive. Decline explicitly: an
            // unanswered server request hangs the agent's turn forever.
            else -> {
                log.warn("${config.tag} unsupported server request: $method")
                rpc.respondError(idEl, AcpRpc.METHOD_NOT_FOUND, "not supported by cc-pocket")
                emptyList()
            }
        }

    // ---- startup failure ----

    /**
     * A startup stage failed, so this session will never open: name the STAGE — a bare "Internal error" leaves
     * a user unable to tell a broken install from a session that no longer exists — and give every prompt
     * waiting on it a TERMINAL state. An unsettled one keeps its entry in Conversation's unconsumed-prompt
     * ledger, is re-injected on the next relaunch, and the failure loops.
     */
    private suspend fun failStartup(stage: String, why: String): List<AgentEvent> {
        val message = "$stage: $why"
        openFailure = message
        log.warn("${config.tag} startup failed — $message")
        val stranded = prompts.drain()
        return if (stranded.isEmpty()) acpErrorEvents(message) else stranded.flatMap { acpErrorTurn(it.text, message) }
    }

    /**
     * A startup stage of the HOST's own failed — one it runs between the session opening and the prompt gate
     * (dsh applies the launch-time model/effort there). The same terminal settlement as [failStartup]: every
     * waiting prompt gets its error turn, later ones are refused with the message, nothing is retried. On the
     * parse pump (the events are returned).
     */
    suspend fun failHostStartup(stage: String, why: String): List<AgentEvent> = failStartup(stage, why)

    /** [failStartup] for the watchdog, which is not on the parse pump: the same settlement, delivered through
     *  [AgentIo.inject] (only the pump may return events). */
    private suspend fun injectStartupFailure(stage: String, why: String) {
        val message = "$stage: $why"
        openFailure = message
        log.warn("${config.tag} startup failed — $message")
        val stranded = prompts.drain()
        if (stranded.isEmpty()) {
            io?.inject?.invoke(synthetic.error(message))
            return
        }
        // Each waiting prompt settles exactly like a refused one: reserve its id, then let the pump turn the
        // refusal into its error turn (UserReplay included, so the ledger entry goes away).
        for (prompt in stranded) {
            val id = prompts.reserve(prompt.text)
            io?.inject?.invoke(synthetic.refusal(id, message))
        }
    }

    // ---- outbound: prompts ----

    suspend fun sendPrompt(text: String, images: List<ImageData>) {
        val prompt = AcpPrompt(text, images)
        // The session already failed to open: queueing would park this behind a gate that can never open — no
        // terminal state, and a re-run on the next relaunch. Settle it with the stage error, like a refusal.
        openFailure?.let { why ->
            val id = prompts.reserve(text)
            io?.inject?.invoke(synthetic.refusal(id, why))
            return
        }
        // Gate still shut, or a turn in flight (the agent refuses a second prompt): FIFO it, released by the
        // gate opening / the in-flight prompt's settle.
        val reserved = prompts.admit(prompt) ?: return
        if (acceptable(prompt)) {
            writePrompt(reserved, prompt)
        } else {
            // Refused through the pump, like an error response: the reservation holds everything sent after it
            // until the refusal settles. Waiting for channel room is safe here, off the pump — a refusal the
            // pump itself finds returns its events instead (see [flushQueuedPrompt]).
            io?.inject?.invoke(synthetic.refusal(reserved, refuse(prompt)))
        }
    }

    /** Record the session id without opening the prompt gate — for a host that must do more first (dsh applies
     *  its launch-time model and effort before the opening turn may run). */
    fun bindSession(id: String) {
        sessionId = id
    }

    /**
     * Open the prompt gate and release the oldest waiting prompt. [bind] binds the session id inside the same
     * lock (so binding and releasing are atomic); without it the gate opens only once a session is bound.
     * Reaching this again with the gate already open releases the head of an idle FIFO. Returns the error
     * turns of the prompts refused on the way.
     */
    suspend fun openPromptGate(bind: String? = null): List<AgentEvent> {
        val refused = ArrayList<AcpPrompt>()
        val first = prompts.open(
            prepare = {
                if (bind != null) sessionId = bind
                sessionId != null
            },
            ::acceptable,
            refused,
        )
        first?.let { (id, prompt) -> writePrompt(id, prompt) }
        return refusals(refused)
    }

    /** The in-flight prompt settled (any stopReason, error included) — send the oldest queued one. Returns the
     *  error turns of the prompts refused on the way: this runs on the pump, so injecting them would wait for
     *  room on the very channel the pump drains. */
    private suspend fun flushQueuedPrompt(): List<AgentEvent> {
        val refused = ArrayList<AcpPrompt>()
        prompts.next(::acceptable, refused)?.let { (id, prompt) -> writePrompt(id, prompt) }
        return refusals(refused)
    }

    /** A failed prompt was still CONSUMED — its failure surfaces right here as an error turn. Left unsettled,
     *  Conversation would re-inject it on every relaunch and loop the failure (#122); and it must not stall the
     *  FIFO behind it. */
    private suspend fun failedPrompt(text: String, why: String): List<AgentEvent> =
        acpErrorTurn(text, why) + flushQueuedPrompt()

    private suspend fun writePrompt(id: Long, prompt: AcpPrompt) {
        val sid = sessionId ?: return
        rpc.send(id, "session/prompt", prompt.sessionPromptParams(sid))
    }

    /** A prompt with images needs the advertised capability: the agent may fail the WHOLE prompt on an image
     *  block it did not advertise, and sending the text alone would be the silent loss issue #377 is about. */
    private fun acceptable(prompt: AcpPrompt): Boolean = prompt.images.isEmpty() || imagePrompts

    private fun refusals(refused: List<AcpPrompt>): List<AgentEvent> =
        refused.flatMap { acpErrorTurn(it.text, refuse(it)) }

    /** Log a refusal and word it for the chat — counts only: the image bytes reach neither. */
    private fun refuse(prompt: AcpPrompt): String {
        val n = prompt.images.size
        log.warn("${config.tag} prompt with $n image(s) refused — this session did not advertise image input")
        return acpImageRefusal(config.productName, n)
    }

    // ---- outbound: control ----

    /** `session/cancel` is a NOTIFICATION (no id); the in-flight prompt then resolves stopReason=cancelled. */
    suspend fun interrupt() {
        val sid = sessionId ?: return
        rpc.notify("session/cancel", buildJsonObject { put("sessionId", sid) })
    }

    /** Answer a permission request; false when nothing was pending under [askId]. */
    suspend fun respondPermission(askId: String, allow: Boolean, remember: Boolean): Boolean =
        approvals.respond(askId, allow, remember)

    /** A message for the chat that says nothing about the turn (delivered through the pump). */
    suspend fun injectNotice(message: String) {
        io?.inject?.invoke(synthetic.notice(message))
    }

    companion object {
        /** ACP v1 — what both kimi and dsh answer. */
        const val ACP_PROTOCOL_VERSION = 1
    }
}
