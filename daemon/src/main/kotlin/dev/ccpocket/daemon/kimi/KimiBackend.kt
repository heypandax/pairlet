package dev.ccpocket.daemon.kimi

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.disk.ReplaySlice
import dev.ccpocket.daemon.opencode.ToolNameMapper
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives the Kimi Code CLI via `kimi acp` — the Agent Client Protocol v1 over newline-delimited JSON-RPC 2.0
 * on stdio (issue #206). A stateful per-conversation handshake machine mirroring [dev.ccpocket.daemon.codex.CodexBackend]:
 * on [attach] it sends `initialize`; on the response it opens the session (`session/new`, or `session/load`
 * for a resume); user prompts are queued until the session id lands. Turns stream `session/update`
 * notifications (translated by [KimiAcpParser]); approvals are `session/request_permission` server→client
 * requests answered by the chosen option id — the exact provider-neutral shape [PermissionBridge] expects.
 *
 * SELECTION (probe 2026-08-06): the design assumed a `kimi --wire` mode, but 0.33.0 has no such flag; `kimi
 * acp` is its complete stdio protocol (initialize handshake confirmed: loadSession/resume/fork/permissions).
 * ACP gives the full approval chain (design plan A). Live turn/approval behavior is UNVERIFIED — the probe
 * was blocked by device-code login (V-auth); the mapping follows the ACP v1 spec + the confirmed handshake.
 *
 * IMAGES (issue #377): a prompt carries images only when THIS process's `initialize` answer advertises
 * `agentCapabilities.promptCapabilities.image` (current kimi-cli does, turning each ACP image block into a
 * model image URL). Otherwise the prompt is refused as an error turn rather than sent as its text alone.
 */
class KimiBackend(
    private val kimiBin: String?,
    private val modelService: KimiModelService = KimiModelService(),
) : AgentBackend {
    private val log = logger("KimiBackend")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val idSeq = AtomicLong(1)
    private val bootstrap = Mutex() // guards sessionId + promptQueue so the opening turn is never lost to a race

    @Volatile private var io: AgentIo? = null
    @Volatile private var resolvedExe: Path? = null
    @Volatile private var workdir: String = ""
    @Volatile private var resumeId: String? = null
    @Volatile private var mode: PermissionMode = PermissionMode.DEFAULT
    @Volatile private var model: String? = null

    @Volatile private var sessionId: String? = null

    // `agentCapabilities.promptCapabilities.image` off THIS process's `initialize` answer (issue #377) — only
    // an explicit `true` counts, and every attach forgets the previous process's answer.
    @Volatile private var imagePrompts = false

    // JSON-RPC id correlation. promptIds maps the outstanding session/prompt request id → its prompt text:
    // the text is replayed as a synthesized [AgentEvent.UserReplay] when the prompt settles — kimi's live
    // stream carries NO user_message_chunk (probe 0.34.0), so the turn settling IS the consumption receipt.
    // Without it Conversation's prompt ledger never settles: every relaunch would re-inject (re-RUN) all
    // past prompts, and task grants would never end at the turn boundary (maybeEndTaskOnSettle).
    // Registration happens INSIDE [bootstrap] (see reservePrompt) so the queue-vs-direct decision and the
    // in-flight mark are atomic — registering after the write left a window where a racing sendPrompt saw
    // "idle" and double-sent (-32600 turn.agent_busy, the very failure the FIFO exists to prevent).
    @Volatile private var initializeId: Long = -1
    @Volatile private var sessionOpenId: Long = -1
    private val promptIds = ConcurrentHashMap<Long, String>()

    // session/load replays the whole history via session/update BEFORE its response — those are historical,
    // not live turn output, and the daemon replays history from disk separately, so drop them in that window.
    @Volatile private var suppressReplayUpdates = false

    // askId → (JSON-RPC request id, permission options) — options carry the optionIds we answer with
    private val pendingApprovals = ConcurrentHashMap<String, PendingApproval>()

    // MID-TURN PROMPT QUEUE (probe 0.34.0): ACP rejects a second session/prompt while a turn runs
    // (-32600 turn.agent_busy "another turn is already in progress") — unlike the Claude CLI, which queues
    // stdin messages itself. Conversation hands every prompt straight to us (its ledger settles on the
    // UserReplay we synthesize at prompt settle), so the queue lives HERE: at most one session/prompt in
    // flight, the rest FIFO, flushed when the in-flight prompt settles (any stopReason, incl. cancelled/
    // error). Entries still queued when the process dies stay UNSETTLED in Conversation's ledger, which
    // re-injects them into the fresh process — so attach()'s clear loses nothing. Prompts that arrive before
    // the session opens wait here too: the single buffered slot this replaced let a second early prompt (a
    // quick follow-up, or a relaunch re-injecting two) silently overwrite the first.
    private val promptQueue = ArrayDeque<Prompt>() // guarded by [bootstrap]

    // toolCallId → accumulated tool state. ACP `tool_call` carries NO rawInput (probe 0.34.0): the input
    // JSON streams as cumulative text in in_progress `tool_call_update`s; the output arrives as `rawOutput`
    // on the settled update. The START event is therefore emitted only once the input parses complete —
    // an earlier emission produced the phone's empty Bash/Read cards.
    private class ToolAccum(val name: String, val title: String?, var inputText: String, var started: Boolean)
    private val toolCalls = ConcurrentHashMap<String, ToolAccum>()

    private data class Prompt(val text: String, val images: List<ImageData>)
    private data class PendingApproval(val rpcId: JsonElement, val options: JsonArray)

    override val kind: AgentKind = AgentKind.KIMI

    override fun processBuilder(spec: AgentSpec): ProcessBuilder = KimiLauncher.processBuilder(exe(), spec)

    private fun exe(): Path = resolvedExe ?: KimiLauncher.resolveExecutable(kimiBin).also { resolvedExe = it }

    override suspend fun attach(io: AgentIo, spec: AgentSpec) {
        this.io = io
        this.workdir = spec.workdir.toString()
        this.resumeId = spec.resumeId
        this.mode = spec.mode
        this.model = spec.model
        // reset per-process protocol state (runs on every (re)launch)
        sessionId = null
        suppressReplayUpdates = false
        imagePrompts = false
        bootstrap.withLock { promptQueue.clear() }
        promptIds.clear(); pendingApprovals.clear(); toolCalls.clear()
        // kick off the ACP handshake — session open happens when the initialize response lands
        initializeId = rpcRequest("initialize", buildJsonObject {
            put("protocolVersion", 1)
            putJsonObject("clientCapabilities") {
                putJsonObject("fs") { put("readTextFile", false); put("writeTextFile", false) }
            }
        })
    }

    override suspend fun parse(line: String): List<AgentEvent> {
        val t = line.trim()
        if (t.isEmpty()) return emptyList()
        val root = runCatching { json.parseToJsonElement(t) }.getOrNull() as? JsonObject
            ?: return listOf(AgentEvent.Unparseable(t))
        // our own refusal (see sendPrompt) — the one frame on this pump kimi did not write
        if (root.str("type") == SYNTHETIC_REFUSAL) return settleRefusal(root)
        val method = root.str("method")
        val idEl = root["id"]?.takeIf { it !is JsonNull }
        return runCatching {
            when {
                method != null && idEl != null -> handleServerRequest(method, idEl, root.obj("params"))
                method != null -> handleNotification(method, root.obj("params"))
                root.containsKey("result") -> handleResponse(idEl, root["result"] as? JsonObject)
                root.containsKey("error") -> handleErrorResponse(idEl, root.obj("error"))
                else -> emptyList()
            }
        }.getOrElse { log.warn("kimi parse failed: ${it.message}"); emptyList() }
    }

    // ---- inbound: responses to our requests ----

    private suspend fun handleResponse(idEl: JsonElement?, result: JsonObject?): List<AgentEvent> {
        val id = (idEl as? JsonPrimitive)?.longOrNull ?: return emptyList()
        if (id == initializeId) {
            val image = result?.obj("agentCapabilities")?.obj("promptCapabilities")?.get("image") as? JsonPrimitive
            imagePrompts = image != null && !image.isString && image.booleanOrNull == true
            openSession()
            return emptyList()
        }
        if (id == sessionOpenId) return onSessionOpened(result)
        val consumed = promptIds.remove(id) ?: return emptyList()
        // settle → synthesize the consumption receipt (no live user_message_chunk, probe 0.34.0) BEFORE the
        // TurnResult, so the ledger entry is gone by the time maybeEndTaskOnSettle checks it — then let the
        // next queued prompt go out.
        return listOf(AgentEvent.UserReplay(consumed)) + onPromptDone(result) + flushQueuedPrompt()
    }

    private suspend fun handleErrorResponse(idEl: JsonElement?, error: JsonObject?): List<AgentEvent> {
        val id = (idEl as? JsonPrimitive)?.longOrNull
        val msg = error?.str("message") ?: "kimi error"
        // an auth wall (no model / not logged in) surfaces here on session open or the first prompt
        if (id == sessionOpenId || (id != null && promptIds.containsKey(id))) {
            val consumed = id?.let { promptIds.remove(it) }
            val next = flushQueuedPrompt() // a failed prompt must not stall the FIFO behind it
            return listOfNotNull(
                // an errored prompt was still CONSUMED — its failure surfaced right here as an error turn.
                // Left unsettled, Conversation would re-inject it on every relaunch, looping the same
                // failure (#122 warns exactly against auto-draining a failure); the client resend path is
                // the rescue channel, not the ledger.
                consumed?.let { AgentEvent.UserReplay(it) },
                AgentEvent.AssistantText("⚠️ $msg"),
                AgentEvent.TurnResult(finalText = null, usage = null, isError = true),
            ) + next
        }
        log.warn("kimi error response id=$id: $msg")
        return emptyList()
    }

    private suspend fun openSession() {
        val rid = resumeId
        sessionOpenId = if (rid != null) {
            suppressReplayUpdates = true // session/load replays history via session/update before responding
            rpcRequest("session/load", buildJsonObject {
                put("sessionId", rid)
                put("cwd", workdir)
                putJsonArray("mcpServers") {}
            })
        } else {
            rpcRequest("session/new", buildJsonObject {
                put("cwd", workdir)
                putJsonArray("mcpServers") {}
            })
        }
    }

    private suspend fun onSessionOpened(result: JsonObject?): List<AgentEvent> {
        suppressReplayUpdates = false
        // session/new returns {sessionId}; session/load returns {} (id is the one we sent)
        val sid = result?.str("sessionId") ?: resumeId ?: return emptyList()
        val refused = ArrayList<Prompt>()
        val first = bootstrap.withLock {
            sessionId = sid
            takeQueuedPrompt(refused) // whatever arrived before the session, oldest first
        }
        first?.let { (id, prompt) -> writePrompt(id, prompt) }
        return listOf(AgentEvent.SessionInit(sessionId = sid, cwd = workdir, model = model)) + refusals(refused)
    }

    private fun onPromptDone(result: JsonObject?): List<AgentEvent> {
        // ACP prompt response: {stopReason: end_turn | cancelled | max_tokens | refusal | …}
        val stop = result?.str("stopReason")
        return listOf(
            AgentEvent.TurnResult(
                finalText = null, // text already streamed via agent_message_chunk
                usage = null, // ACP carries no per-turn token usage in the response; occupancy comes from updates
                isError = stop == "refusal",
            ),
        )
    }

    // ---- inbound: notifications (session/update) ----

    private fun handleNotification(method: String, params: JsonObject?): List<AgentEvent> {
        params ?: return emptyList()
        return when (method) {
            "session/update" -> {
                if (suppressReplayUpdates) return emptyList() // historical replay from session/load — drop
                val update = params.obj("update") ?: return emptyList()
                // tool_call / tool_call_update are handled HERE (stateful input accumulation); every other
                // update kind stays with the stateless parser.
                when (update.str("sessionUpdate")) {
                    "tool_call" -> onToolCall(update)
                    "tool_call_update" -> onToolCallUpdate(update)
                    else -> KimiAcpParser.translate(update)
                }
            }
            else -> emptyList()
        }
    }

    /** `tool_call`: card opens pending with NO input (probe 0.34.0) — record it, emit nothing yet. */
    private fun onToolCall(update: JsonObject): List<AgentEvent> {
        val id = update.str("toolCallId") ?: return emptyList()
        val title = update.str("title")
        val name = ToolNameMapper.map(update.str("kind") ?: title ?: "tool")
        toolCalls[id] = ToolAccum(name, title, inputText = "", started = false)
        return emptyList()
    }

    /** `tool_call_update`: the input JSON streams in as CUMULATIVE text while in_progress; the output lands
     *  as `rawOutput` (a plain string) on the settled update. Emit the START once the accumulated input
     *  parses as complete JSON (≈ execution begin), and the result on settle. */
    private fun onToolCallUpdate(update: JsonObject): List<AgentEvent> {
        val id = update.str("toolCallId") ?: return emptyList()
        val accum = toolCalls.getOrPut(id) {
            ToolAccum(ToolNameMapper.map(update.str("title") ?: "tool"), update.str("title"), "", false)
        }
        val status = update.str("status")
        val settled = status == "completed" || status == "failed"
        val out = ArrayList<AgentEvent>(2)
        if (!settled) {
            // in_progress: content text is the CUMULATIVE input JSON being built — latest is fullest.
            // (on the settled update the same slot carries the OUTPUT — never fold it into the input)
            toolCallContentText(update["content"])?.let { accum.inputText = it }
        }
        if (!accum.started) {
            val input = runCatching { json.parseToJsonElement(accum.inputText) as? JsonObject }.getOrNull()
            if (input != null || settled) {
                accum.started = true
                out += AgentEvent.AssistantToolUse(
                    id, accum.name,
                    input ?: buildJsonObject { accum.title?.let { put("description", it) } },
                )
            }
        }
        if (settled) {
            toolCalls.remove(id)
            // output: rawOutput is a plain STRING (probe 0.34.0), else the settled content text
            out += AgentEvent.ToolResult(
                id,
                update.str("rawOutput") ?: toolCallContentText(update["content"]),
                isError = status == "failed",
            )
        }
        return out
    }

    // ---- inbound: server→client requests (approvals + fs/terminal we decline) ----

    private suspend fun handleServerRequest(method: String, idEl: JsonElement, params: JsonObject?): List<AgentEvent> {
        val askId = (idEl as? JsonPrimitive)?.contentOrNull ?: idEl.toString()
        return when (method) {
            "session/request_permission" -> {
                val toolCall = params?.obj("toolCall")
                val options = params?.arr("options") ?: JsonArray(emptyList())
                pendingApprovals[askId] = PendingApproval(idEl, options)
                val name = ToolNameMapper.map(
                    toolCall?.str("kind") ?: toolCall?.str("title") ?: "tool",
                )
                // probe 0.34.0: no rawInput here either — the human sentence in the content text is the
                // only command carrier ("Requesting approval to Running: echo …"); surface it as the card body
                val input = toolCall?.obj("rawInput") ?: buildJsonObject {
                    put("description", toolCallContentText(toolCall?.get("content")) ?: toolCall?.str("title") ?: "tool")
                }
                listOf(AgentEvent.ControlRequest(askId, name, input))
            }
            // we declared fs caps false, so these shouldn't arrive; decline so the agent doesn't block on us.
            else -> {
                log.warn("kimi unsupported server request: $method")
                rpcRespondError(idEl, -32601, "not supported by cc-pocket")
                emptyList()
            }
        }
    }

    // ---- outbound (called by Conversation) ----

    override suspend fun sendPrompt(text: String, images: List<ImageData>) {
        val prompt = Prompt(text, images)
        val reserved = bootstrap.withLock {
            when {
                // no session yet — the session open releases the FIFO head
                sessionId == null -> { promptQueue.addLast(prompt); null }
                // a turn is in flight — ACP has no mid-turn stdin queue (-32600 turn.agent_busy, probe
                // 0.34.0), so FIFO it HERE and flush when the in-flight prompt settles
                promptIds.isNotEmpty() -> { promptQueue.addLast(prompt); null }
                else -> reservePrompt(text)
            }
        } ?: return
        if (acceptable(prompt)) {
            writePrompt(reserved, prompt)
        } else {
            // refused through the pump, like an error response: the reservation holds everything sent after it
            // until the refusal settles. Waiting for channel room is safe here, off the pump — a refusal the
            // pump itself finds returns its events instead (see takeQueuedPrompt).
            io?.inject?.invoke(syntheticRefusal(reserved, refuse(prompt)))
        }
    }

    /** Allocate the request id and mark the prompt in flight — MUST run inside [bootstrap], so a racing
     *  sendPrompt/flush can never see "idle" between the decision and the registration (double-send). */
    private fun reservePrompt(text: String): Long = idSeq.getAndIncrement().also { promptIds[it] = text }

    /** The in-flight prompt just settled (any stopReason / error) — send the oldest queued prompt, if any.
     *  Returns the error turns of the prompts refused on the way. */
    private suspend fun flushQueuedPrompt(): List<AgentEvent> {
        val refused = ArrayList<Prompt>()
        val next = bootstrap.withLock { takeQueuedPrompt(refused) }
        next?.let { (id, prompt) -> writePrompt(id, prompt) }
        return refusals(refused)
    }

    /** Reserve the FIFO head when no turn is running — MUST run inside [bootstrap]. A head this session can't
     *  take moves to [refused] and the next is tried, so a refusal never stalls the prompts behind it. Callers
     *  run on the pump and hand refusals back as events: injecting them would wait for room on the very
     *  channel the pump drains. */
    private fun takeQueuedPrompt(refused: MutableList<Prompt>): Pair<Long, Prompt>? {
        if (sessionId == null || promptIds.isNotEmpty()) return null
        while (true) {
            val next = promptQueue.removeFirstOrNull() ?: return null
            if (acceptable(next)) return reservePrompt(next.text) to next
            refused += next
        }
    }

    /** A prompt [sendPrompt] reserved but refused settles here exactly once, like its error response — a stale
     *  id (already settled, or reserved by a previous process) says nothing. */
    private suspend fun settleRefusal(root: JsonObject): List<AgentEvent> {
        val consumed = root.long("id")?.let { promptIds.remove(it) } ?: return emptyList()
        return errorTurn(consumed, root.str("message").orEmpty()) + flushQueuedPrompt()
    }

    // images ride as ACP image blocks after the text (ImageData is already the Base64 + MIME pair a block
    // holds); a prompt with images but blank text sends the images alone, never an empty text block
    private suspend fun writePrompt(id: Long, prompt: Prompt) {
        val sid = sessionId ?: return
        rpcSend(id, "session/prompt", buildJsonObject {
            put("sessionId", sid)
            putJsonArray("prompt") {
                if (prompt.text.isNotBlank() || prompt.images.isEmpty()) {
                    addJsonObject { put("type", "text"); put("text", prompt.text) }
                }
                prompt.images.forEach { image ->
                    addJsonObject { put("type", "image"); put("data", image.base64); put("mimeType", image.mediaType) }
                }
            }
        })
    }

    /** A prompt with images needs the advertised capability — sending its text alone would be the silent loss
     *  issue #377 is about, so without it the prompt is refused. */
    private fun acceptable(prompt: Prompt): Boolean = prompt.images.isEmpty() || imagePrompts

    private fun refusals(refused: List<Prompt>): List<AgentEvent> = refused.flatMap { errorTurn(it.text, refuse(it)) }

    /** Log a refusal and word it for the chat — counts only: the image bytes reach neither. */
    private fun refuse(prompt: Prompt): String {
        val n = prompt.images.size
        log.warn("kimi prompt with $n image(s) refused — this session did not advertise image input")
        return "not sent: Kimi Code did not advertise image input for this session, so nothing reached the " +
            "agent. Send the message again without the ${if (n == 1) "image" else "$n images"}."
    }

    private fun errorTurn(text: String, why: String): List<AgentEvent> = listOf(
        AgentEvent.UserReplay(text),
        AgentEvent.AssistantText("⚠️ $why"),
        AgentEvent.TurnResult(finalText = null, usage = null, isError = true),
    )

    override suspend fun interrupt() {
        val sid = sessionId ?: return
        // ACP session/cancel is a NOTIFICATION (no id); the in-flight prompt then resolves stopReason=cancelled
        rpcNotify("session/cancel", buildJsonObject { put("sessionId", sid) })
    }

    override suspend fun respondPermission(
        askId: String,
        allow: Boolean,
        remember: Boolean,
        originalInput: JsonObject?,
        updatedInput: String?,
        denyMessage: String?,
    ) {
        val pending = pendingApprovals.remove(askId) ?: return
        val optionId = pickOption(pending.options, allow, remember)
        val outcome = if (optionId != null) {
            buildJsonObject { put("outcome", "selected"); put("optionId", optionId) }
        } else {
            buildJsonObject { put("outcome", "cancelled") } // no matching option → treat as cancel/deny
        }
        rpcRespondResult(pending.rpcId, buildJsonObject { put("outcome", outcome) })
    }

    /** Choose the ACP permission option matching the decision. Options carry a `kind` ∈
     *  allow_once/allow_always/reject_once/reject_always (ACP spec). remember → the *_always variant. */
    private fun pickOption(options: JsonArray, allow: Boolean, remember: Boolean): String? {
        val byKind = options.mapNotNull { it as? JsonObject }
            .mapNotNull { o -> o.str("optionId")?.let { (o.str("kind") ?: "") to it } }
        fun of(vararg kinds: String): String? = kinds.firstNotNullOfOrNull { k -> byKind.firstOrNull { it.first == k }?.second }
        return if (allow) {
            if (remember) of("allow_always", "allow_once") else of("allow_once", "allow_always")
        } else {
            of("reject_once", "reject_always")
        }
    }

    // Model is chosen at session/new (ACP has no mid-session model swap) → relaunch to change it.
    // Permission mode maps to ACP session modes (P2) — for P1 approvals always flow, so a mode change is a
    // no-op that doesn't force a relaunch.
    override fun applySettings(mode: PermissionMode?, model: String?, effort: String?): Boolean {
        var relaunch = false
        model?.let { if (it != this.model) { this.model = it; relaunch = true } }
        mode?.let { this.mode = it }
        return relaunch
    }

    override suspend fun onProcessEnded(sessionId: String?) {} // kimi self-manages its session store

    // ---- disk: ~/.kimi-code session scanning + replay (filtered by recorded workDir; no process launch) ----

    override fun transcriptDir(workdir: String): Path = KimiPaths.sessionsRoot()
    override fun listSessions(workdir: String): List<SessionSummary> = KimiTranscriptScanner.scan(workdir)

    override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> =
        KimiPaths.sessionDir(sessionId)?.let { KimiTranscriptReplay.read(KimiPaths.mainWireLog(it)) } ?: emptyList()

    override fun replaySlice(workdir: String, sessionId: String, sinceSeq: Long?): ReplaySlice =
        KimiPaths.sessionDir(sessionId)?.let { KimiTranscriptReplay.slice(KimiPaths.mainWireLog(it), sinceSeq) }
            ?: ReplaySlice.EMPTY

    override fun replayPage(workdir: String, sessionId: String, beforeSeq: Long, limit: Int): ReplaySlice =
        KimiPaths.sessionDir(sessionId)?.let { KimiTranscriptReplay.page(KimiPaths.mainWireLog(it), beforeSeq, limit) }
            ?: ReplaySlice.EMPTY

    override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null // seeded live via updates

    override fun defaultModel(workdir: String): String? = KimiDefaultModel.resolve()

    // ---- JSON-RPC 2.0 plumbing (ACP requires the `jsonrpc` field, unlike codex app-server) ----

    private suspend fun rpcRequest(method: String, params: JsonObject?): Long {
        val id = idSeq.getAndIncrement()
        rpcSend(id, method, params)
        return id
    }

    /** A request whose id was pre-allocated (see [reservePrompt] — registered before the write). */
    private suspend fun rpcSend(id: Long, method: String, params: JsonObject?) {
        write(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            params?.let { put("params", it) }
        })
    }

    private suspend fun rpcNotify(method: String, params: JsonObject?) =
        write(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); params?.let { put("params", it) } })

    private suspend fun rpcRespondResult(id: JsonElement, result: JsonObject) =
        write(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })

    private suspend fun rpcRespondError(id: JsonElement, code: Int, message: String) =
        write(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); putJsonObject("error") { put("code", code); put("message", message) } })

    private suspend fun write(obj: JsonObject) { io?.writeLine(obj.toString()) }

    /** The refusal of the prompt reserved as [id] (see [sendPrompt]) — settled on the pump like its error
     *  response would be. */
    private fun syntheticRefusal(id: Long, message: String): String =
        buildJsonObject { put("type", SYNTHETIC_REFUSAL); put("id", id); put("message", message) }.toString()

    private companion object {
        /** Namespaced so it can never collide with a real kimi frame. */
        const val SYNTHETIC_REFUSAL = "cc-pocket/kimi-prompt-refused"
    }
}
