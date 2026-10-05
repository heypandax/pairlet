package dev.ccpocket.daemon.kimi

import dev.ccpocket.daemon.acp.AcpRpc
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val taskPollMs: Long = TASK_POLL_MS,
    private val taskFile: (sessionId: String, taskId: String) -> Path? = KimiPaths::taskFile,
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
) : AgentBackend {
    private val log = logger("KimiBackend")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val rpc = AcpRpc { io?.writeLine?.invoke(it) }
    private val bootstrap = Mutex() // guards sessionId + promptQueue so the opening turn is never lost to a race

    @Volatile private var io: AgentIo? = null
    @Volatile private var resolvedExe: Path? = null
    @Volatile private var workdir: String = ""
    @Volatile private var resumeId: String? = null
    @Volatile private var mode: PermissionMode = PermissionMode.DEFAULT
    @Volatile private var model: String? = null

    @Volatile private var sessionId: String? = null

    /**
     * Why this session will never open, once a startup stage failed (audit 2026-10-04 H1 — the #388 fix
     * [dev.ccpocket.daemon.dsh.DshBackend] already carries). Set by [failStartup], cleared by [attach]. It
     * silences the handshake watchdog after a real diagnosis, and settles prompts that arrive AFTER the
     * failure — they would otherwise queue behind a session id that can never land, with no terminal state
     * and a re-run on every relaunch. Nothing retries a stage or opens a replacement session: a resume that
     * failed must stay the session the user asked for.
     */
    @Volatile private var openFailure: String? = null

    /** The handshake watchdog of the CURRENT process; replaced on every [attach]. */
    @Volatile private var handshakeWatch: Job? = null

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
    private class ToolAccum(val name: String, val title: String?, var inputText: String, var started: Boolean) {
        var background = false // the Bash call asked for run_in_background (issue #391)
        var description: String? = null
    }
    private val toolCalls = ConcurrentHashMap<String, ToolAccum>()

    // BACKGROUND TASKS (issue #391, probe 2.1.1 via scripts/probe-kimi-bgtask.py). A Bash call with
    // run_in_background settles at once with `task_id: bash-xxxxxxxx … status: running`, and ACP then says
    // NOTHING more about it: no update kind exists for task completion, and with no turn in flight the
    // stream stays silent after the task ends. The CLI's own record is the only completion signal —
    // `<sessionDir>/agents/main/tasks/<taskId>.json`, whose `status` leaves "running" when the task ends.
    // So each launched task gets a watcher that polls that file and, on a terminal status, feeds a synthetic
    // frame through the pump; [parse] turns it into the same BackgroundTaskUpdated a Claude task_notification
    // produces. Without this the job stayed RUNNING for as long as the kimi process lived (the stale-job
    // reaper deliberately trusts a live agent to report completion). Watchers die with the process.
    private val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskWatchers = ConcurrentHashMap<String, Job>()

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
        openFailure = null
        sessionOpenId = -1
        suppressReplayUpdates = false
        imagePrompts = false
        bootstrap.withLock { promptQueue.clear() }
        promptIds.clear(); pendingApprovals.clear(); toolCalls.clear()
        stopTaskWatchers() // the previous process's tasks are no longer this conversation's jobs
        handshakeWatch?.cancel()
        // kick off the ACP handshake — session open happens when the initialize response lands
        initializeId = rpc.request("initialize", buildJsonObject {
            put("protocolVersion", 1)
            putJsonObject("clientCapabilities") {
                putJsonObject("fs") { put("readTextFile", false); put("writeTextFile", false) }
            }
        })
        handshakeWatch = taskScope.launch { watchHandshake(io) }
    }

    /**
     * An `initialize` that never answers is the one startup failure no frame can report (a legacy Python
     * `kimi` sharing the name, a CLI that does not speak ACP v1). Bounded, like DSH's: once a session open
     * was sent, its own error rides the wire.
     */
    private suspend fun watchHandshake(owner: AgentIo) {
        delay(handshakeTimeoutMs)
        if (io !== owner || sessionOpenId >= 0 || sessionId != null || openFailure != null) return
        injectStartupFailure(
            STAGE_HANDSHAKE,
            "no answer to `initialize` within ${handshakeTimeoutMs / 1000}s — check that `kimi` is the Kimi Code " +
                "CLI (`kimi acp`), not the legacy Python kimi-cli",
        )
    }

    override suspend fun parse(line: String): List<AgentEvent> {
        val t = line.trim()
        if (t.isEmpty()) return emptyList()
        val root = runCatching { json.parseToJsonElement(t) }.getOrNull() as? JsonObject
            ?: return listOf(AgentEvent.Unparseable(t))
        // our own refusal (see sendPrompt) — the one frame on this pump kimi did not write
        if (root.str("type") == SYNTHETIC_REFUSAL) return settleRefusal(root)
        if (root.str("type") == SYNTHETIC_ERROR) {
            return listOf(
                AgentEvent.AssistantText("⚠️ ${root.str("message").orEmpty()}"),
                AgentEvent.TurnResult(finalText = null, usage = null, isError = true),
            )
        }
        if (root.str("type") == SYNTHETIC_TASK_SETTLED) {
            val taskId = root.str("taskId") ?: return emptyList()
            return listOf(AgentEvent.BackgroundTaskUpdated(taskId, root.str("status")))
        }
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
        // A failed startup stage leaves a session that will never open: every prompt waiting on it settles
        // (an auth wall — no model / not logged in — lands here on session open, too).
        if (id != null && id == initializeId) return failStartup(STAGE_HANDSHAKE, msg)
        if (id != null && id == sessionOpenId) {
            suppressReplayUpdates = false
            return failStartup(if (resumeId != null) STAGE_RESUME else STAGE_NEW, msg)
        }
        if (id != null && promptIds.containsKey(id)) {
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

    /** A startup stage failed, so this session will never open: name the stage and give every prompt waiting
     *  on it a terminal state — an unsettled one stays in Conversation's ledger and re-runs on relaunch. */
    private suspend fun failStartup(stage: String, why: String): List<AgentEvent> {
        val message = "$stage: $why"
        openFailure = message
        log.warn("kimi startup failed — $message")
        val stranded = drainWaitingPrompts()
        return if (stranded.isEmpty()) {
            listOf(
                AgentEvent.AssistantText("⚠️ $message"),
                AgentEvent.TurnResult(finalText = null, usage = null, isError = true),
            )
        } else {
            stranded.flatMap { errorTurn(it.text, message) }
        }
    }

    /** [failStartup] for the watchdog, which is not on the parse pump: the same settlement, delivered through
     *  [AgentIo.inject] (only the pump may return events). */
    private suspend fun injectStartupFailure(stage: String, why: String) {
        val message = "$stage: $why"
        openFailure = message
        log.warn("kimi startup failed — $message")
        val stranded = drainWaitingPrompts()
        if (stranded.isEmpty()) {
            io?.inject?.invoke(buildJsonObject { put("type", SYNTHETIC_ERROR); put("message", message) }.toString())
            return
        }
        for (prompt in stranded) {
            val id = bootstrap.withLock { reservePrompt(prompt.text) }
            io?.inject?.invoke(syntheticRefusal(id, message))
        }
    }

    private suspend fun drainWaitingPrompts(): List<Prompt> = bootstrap.withLock {
        promptQueue.toList().also { promptQueue.clear() }
    }

    private suspend fun openSession() {
        val rid = resumeId
        sessionOpenId = if (rid != null) {
            suppressReplayUpdates = true // session/load replays history via session/update before responding
            rpc.request("session/load", buildJsonObject {
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

    private suspend fun onSessionOpened(result: JsonObject?): List<AgentEvent> {
        suppressReplayUpdates = false
        // session/new returns {sessionId}; session/load returns {} (id is the one we sent). A session/new with
        // no id is a session nobody can address — the same dead end as an error answer, settled the same way.
        val sid = result?.str("sessionId") ?: resumeId
            ?: return failStartup(STAGE_NEW, "kimi did not return a session id")
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
                if (accum.name == "Bash" && input != null) {
                    val flag = input["run_in_background"] as? JsonPrimitive
                    accum.background = flag?.booleanOrNull ?: (flag?.contentOrNull == "true")
                    accum.description = input.str("description") ?: input.str("command")
                }
                out += AgentEvent.AssistantToolUse(
                    id, accum.name,
                    input ?: buildJsonObject { accum.title?.let { put("description", it) } },
                )
            }
        }
        if (settled) {
            toolCalls.remove(id)
            // output: rawOutput is a plain STRING (probe 0.34.0), else the settled content text
            val output = update.str("rawOutput") ?: toolCallContentText(update["content"])
            out += AgentEvent.ToolResult(id, output, isError = status == "failed")
            // a backgrounded launch answers with the task's id — link it to the job the tool_use created and
            // start watching the CLI's task record for its completion (see [taskWatchers])
            if (accum.background && status == "completed") {
                launchedTaskId(output)?.let { taskId ->
                    out += AgentEvent.BackgroundTaskStarted(taskId, id, accum.description, "local_bash")
                    watchTask(taskId)
                }
            }
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
                rpc.respondError(idEl, AcpRpc.METHOD_NOT_FOUND, "not supported by cc-pocket")
                emptyList()
            }
        }
    }

    // ---- outbound (called by Conversation) ----

    override suspend fun sendPrompt(text: String, images: List<ImageData>) {
        val prompt = Prompt(text, images)
        // the session already failed to open: queueing would park this behind a session id that can never
        // land — settle it with the stage error instead, exactly like a refused prompt
        openFailure?.let { why ->
            val id = bootstrap.withLock { reservePrompt(text) }
            io?.inject?.invoke(syntheticRefusal(id, why))
            return
        }
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
    private fun reservePrompt(text: String): Long = rpc.nextId().also { promptIds[it] = text }

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
        rpc.send(id, "session/prompt", buildJsonObject {
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
        rpc.notify("session/cancel", buildJsonObject { put("sessionId", sid) })
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
        rpc.respondResult(pending.rpcId, buildJsonObject { put("outcome", outcome) })
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

    // kimi self-manages its session store. A dead process cannot take a completion frame any more (inject
    // drops it), so stop watching — Conversation's stale-job reaper owns the jobs of a dead agent.
    override suspend fun onProcessEnded(sessionId: String?) {
        stopTaskWatchers()
        handshakeWatch?.cancel()
    }

    // ---- background task completion (issue #391) ----

    private fun watchTask(taskId: String) {
        val sid = sessionId ?: return
        val owner = io ?: return
        taskWatchers[taskId]?.cancel()
        taskWatchers[taskId] = taskScope.launch {
            var file: Path? = null
            while (isActive && io === owner) {
                delay(taskPollMs)
                val f = file ?: runCatching { taskFile(sid, taskId) }.getOrNull()?.also { file = it } ?: continue
                val settled = settledStatus(KimiPaths.taskStatus(f)) ?: continue
                owner.inject(buildJsonObject {
                    put("type", SYNTHETIC_TASK_SETTLED); put("taskId", taskId); put("status", settled)
                }.toString())
                break
            }
            taskWatchers.remove(taskId, coroutineContext[Job])
        }
    }

    private fun stopTaskWatchers() {
        taskWatchers.values.forEach { it.cancel() }
        taskWatchers.clear()
    }

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

    /** The refusal of the prompt reserved as [id] (see [sendPrompt]) — settled on the pump like its error
     *  response would be. */
    private fun syntheticRefusal(id: Long, message: String): String =
        buildJsonObject { put("type", SYNTHETIC_REFUSAL); put("id", id); put("message", message) }.toString()

    internal companion object {
        /** Namespaced so it can never collide with a real kimi frame. */
        private const val SYNTHETIC_REFUSAL = "cc-pocket/kimi-prompt-refused"
        private const val SYNTHETIC_TASK_SETTLED = "cc-pocket/kimi-task-settled"
        private const val SYNTHETIC_ERROR = "cc-pocket/kimi-error"
        const val TASK_POLL_MS = 2_000L

        /** The startup stages a failure can land in — each asks the user for something different. */
        const val STAGE_HANDSHAKE = "Kimi Code never completed its handshake"
        const val STAGE_NEW = "could not start a Kimi Code session"
        const val STAGE_RESUME = "could not resume this Kimi Code session — " +
            "it was not reopened, and nothing was sent to a different one"

        /** Handshake watchdog, as generous as DSH's: a cold start can take seconds on a slow machine, and a
         *  false "never completed its handshake" is worse than waiting. */
        const val HANDSHAKE_TIMEOUT_MS = 30_000L

        // kimi's own task-id shape (its VALID_TASK_ID, read out of the 2.1.1 bundle). The id becomes a file
        // name under the session dir, so anything else — a path, a `..` — is refused rather than resolved.
        private val TASK_ID = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*-[0-9a-z]{8}$")
        private val TASK_ID_LINE = Regex("(?m)^task_id:[ \\t]*(\\S+)[ \\t]*$")

        /** The task id a backgrounded launch reports (`task_id: bash-gbmpt89x`, probe 2.1.1), or null. */
        internal fun launchedTaskId(output: String?): String? =
            output?.let { TASK_ID_LINE.find(it) }?.groupValues?.get(1)?.takeIf { TASK_ID.matches(it) }

        /** kimi's task `status` → the status word [dev.ccpocket.daemon.conversation.BackgroundJobRegistry]
         *  settles on, or null while the task is still going. Only kimi's KNOWN terminal statuses settle a
         *  job (running / completed / failed / timed_out / killed / lost on 2.1.1): an unreadable record or
         *  a status a later CLI invents must not end a job that may still be running. */
        internal fun settledStatus(status: String?): String? = when (status) {
            "completed" -> "completed"
            "killed" -> "killed"
            "failed", "timed_out", "lost" -> "failed"
            else -> null
        }
    }
}
