package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.disk.ReplaySlice
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Path

/**
 * Drives the DeepSeek Harness (`dsh`) — issue #255.
 *
 * ## Why this backend is shaped differently from the others
 *
 * Every backend before it spoke newline-delimited JSON on its child's stdout, so `parse(line)` was the
 * whole event path. dsh has no such mode: rc.6 ships no ACP server, and its SDK JSON-RPC mode has neither
 * cancel, resume, nor an approval callback. The only channel that can carry a real interactive session is
 * the local HTTP + WebSocket API of its `web` profile. So:
 *
 * ```
 *   processBuilder → dsh --profile web --port 0        (child; stdout carries only its boot banner)
 *   parse(bootline) → learn the port → DshApiClient    (WS connects, session opens)
 *   WS frames      → io.inject(json) → parse(json)     (back through the SAME pump, in order)
 *   sendPrompt     → HTTP POST /api/session.prompt
 * ```
 *
 * The re-injection is what keeps this honest: events still arrive on one channel, in one order, with the
 * Conversation's single pump assigning `seq`. See [AgentIo.inject].
 *
 * ## v1 scope (deliberately narrow)
 *
 * Session discovery, history replay, opening a session, sending/receiving messages including live
 * streaming, and (issue #291) the question/approval bridge. NOT included: usage accounting, model
 * switching, tool cards, the web UI, plugins.
 *
 * ## Two product constraints worth quoting in the follow-up work
 *
 *  1. **dsh has no "always allow".** Its `ApprovalOutcome` is `allowed-once | rejected | cancelled |
 *     unavailable`, and its `ApprovalPolicy` of `never` AUTO-REJECTS rather than auto-allowing. So the
 *     "remember this decision" half of cc-pocket's approval UI has no counterpart to map onto; a future
 *     bridge must either hide it for dsh or implement remembering on the daemon side.
 *  2. **The permission mode is NOT fixed for the session's life.** `DSH_PERMISSION_MODE` only seeds the
 *     boot default; dsh records mode changes as durable log events (`sandbox/mode`, `approval/policy`,
 *     `permission/preset`) and a `/permission <preset>` typed into the chat moves it mid-session. Any
 *     future mode UI must read the session's projection rather than trusting the launch value.
 *
 * ## Approvals and questions: bridged, and fail-CLOSED rather than fail-hang (issue #291)
 *
 * `approval/requested` and `question/requested` become real [AgentEvent.ControlRequest]s, so they run the
 * same [dev.ccpocket.daemon.agent.PermissionBridge] path every other backend uses — one card, one budget,
 * one verdict route. Nothing is ever auto-allowed here.
 *
 * The window matters more for dsh than for any other backend: **dsh has no timeout of its own**. An
 * unanswered request hangs the turn forever (probe-verified; the host source has no `setTimeout`), so the
 * pre-#291 "log it and leave it" was fail-HANG, not fail-closed. The coordinator's budget now bounds it
 * and an expired approval is answered `rejected`. See [DshAskLedger] for the rest of the contract.
 */
class DshBackend(private val dshBin: String?) : AgentBackend {
    private val log = logger("DshBackend")

    // Owns the WS client's coroutines; cancelled when the process ends so a dead session leaves nothing behind.
    private var clientScope: CoroutineScope? = null

    @Volatile private var io: AgentIo? = null
    @Volatile private var resolvedExe: Path? = null
    @Volatile private var workdir: String = ""
    @Volatile private var resumeId: String? = null
    @Volatile private var mode: PermissionMode = PermissionMode.DEFAULT

    @Volatile private var api: DshApiClient? = null
    @Volatile private var port: Int = 0
    @Volatile private var sessionId: String? = null

    /** issue #291: the pending question/approval table + the `/api/respond` return path. Lives for the
     *  backend's whole life (not per process) and is [DshAskLedger.reset] on every attach, because an
     *  rpcId only means anything to the host process that minted it. */
    private val asks = DshAskLedger(
        ourSession = { sessionId },
        send = { rpcId, value -> api?.respond(rpcId, value) ?: DshRespond.UNREACHABLE },
        // A refused reply is surfaced as a NOTICE, not a turn error: the dsh turn really is still running
        // (that is the whole problem), and faking a TurnResult would tell the phone it had ended.
        report = { message -> io?.inject?.invoke(syntheticNotice("⚠️ $message")) },
    )

    /** Guards [sessionId] and [pendingPrompts] so the opening turn can never be lost to a race between
     *  "the session is not open yet, buffer it" and "the session just opened, flush the buffer". */
    private val bootstrap = Mutex()
    private val pendingPrompts = ArrayDeque<String>()

    override val kind: AgentKind = AgentKind.DSH

    override fun processBuilder(spec: AgentSpec): ProcessBuilder =
        DshLauncher.processBuilder(exe(), spec, permissionModeFor(spec.mode))

    private fun exe(): Path = resolvedExe ?: DshLauncher.resolveExecutable(dshBin).also { resolvedExe = it }

    /** Map cc-pocket's permission ladder onto dsh's `SandboxMode`. dsh's own default is workspace-write;
     *  only an explicit bypass reaches danger-full-access, and it is the user's deliberate choice. */
    private fun permissionModeFor(mode: PermissionMode): String = when (mode) {
        PermissionMode.BYPASS_PERMISSIONS -> "danger-full-access"
        PermissionMode.PLAN -> "read-only"
        else -> DshLauncher.DEFAULT_PERMISSION_MODE
    }

    override suspend fun attach(io: AgentIo, spec: AgentSpec) {
        this.io = io
        this.workdir = spec.workdir.toString()
        this.resumeId = spec.resumeId
        this.mode = spec.mode
        // reset per-process state (attach runs on EVERY relaunch)
        teardownClient()
        port = 0
        // Every pending rpcId died with the previous host process; the conversation retires their cards
        // through PermissionBridge.cancelAll on the same relaunch.
        asks.reset()
        bootstrap.withLock { sessionId = null; pendingPrompts.clear() }
        // Nothing else can happen yet: the port is only knowable from the child's boot line, which
        // arrives on stdout and lands in parse().
    }

    /**
     * One line from the pump. Two very different kinds arrive here:
     *  - dsh's OWN stdout — human-readable log text, of which only the boot banner matters, and
     *  - re-injected WebSocket frames — JSON MuxFrames (see [AgentIo.inject]).
     *
     * They are told apart by shape: a MuxFrame is a JSON object, dsh's banner is not.
     */
    override suspend fun parse(line: String): List<AgentEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("{")) {
            val root = DshTranscript.parseLine(trimmed) ?: return listOf(AgentEvent.Unparseable(trimmed))
            return runCatching { translateMux(root) }
                .getOrElse { log.warn("dsh frame translate failed: ${it.message}"); emptyList() }
        }
        // stdout log line — the only one we act on is the boot banner carrying the bound port
        if (port == 0) {
            DshLauncher.parseBootPort(trimmed)?.let { bound ->
                port = bound
                log.info("dsh web profile bound to 127.0.0.1:$bound")
                startClient(bound)
            }
        }
        return listOf(AgentEvent.Ignored("dsh-stdout"))
    }

    // ---- transport lifecycle ----

    private fun startClient(port: Int) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        clientScope = scope
        val bound = io
        val client = DshApiClient(
            port = port,
            scope = scope,
            // Straight back into the Conversation pump — this is the ONLY place dsh events enter the
            // daemon, and routing them here keeps ordering and seq assignment untouched.
            onFrame = { frame -> bound?.inject?.invoke(frame) },
            onFatal = { why ->
                log.warn("dsh transport failed: $why")
                // Surfaced as a turn error through the same channel, so the phone sees a reason instead
                // of a session that simply stops answering.
                bound?.inject?.invoke(syntheticError(why))
            },
        )
        api = client
        client.start()
        scope.launch { openSession(client) }
    }

    /** Poll the client's connect flag for a bounded window. Returns false if it never came up. */
    private suspend fun awaitConnected(client: DshApiClient): Boolean {
        repeat(READY_POLLS) {
            if (client.connected) return true
            delay(READY_POLL_MS)
        }
        return client.connected
    }

    private fun teardownClient() {
        runCatching { api?.close() }
        api = null
        runCatching { clientScope?.cancel() }
        clientScope = null
    }

    /**
     * Open the conversation's session and release any prompt that arrived while we were booting.
     *
     * RESUME uses the recorded id directly rather than re-creating: dsh's host addresses sessions by id
     * and loads them from the store on demand. A resume whose id the host cannot load surfaces as a
     * failed `session.prompt` below, with dsh's own error text — which is the honest outcome, since we
     * genuinely cannot tell "unknown id" from "unreadable transcript" from out here.
     */
    private suspend fun openSession(client: DshApiClient) {
        // Wait for the mux socket before issuing the first RPC. dsh prints its port once the LISTENER is
        // up, which is a moment before the app has finished mounting its routes — firing session.create
        // into that window returns a carrier error and the session would open onto a dead conversation.
        // The WS client already retries across exactly this window, so its connect is the readiness
        // signal; if it never arrives, onFatal has already reported why and there is nothing to open.
        val ready = awaitConnected(client)
        if (!ready) return
        val existing = resumeId
        val sid = if (existing != null) {
            existing
        } else {
            val result = client.rpc("session.create", buildJsonObject { put("cwd", workdir) })
            val value = result?.obj("value")
            val created = value?.str("sessionId")
            if (created == null) {
                val why = result?.obj("error")?.str("message") ?: "dsh did not return a session id"
                io?.inject?.invoke(syntheticError("could not start a DeepSeek Harness session: $why"))
                return
            }
            created
        }
        val flush = bootstrap.withLock {
            sessionId = sid
            pendingPrompts.toList().also { pendingPrompts.clear() }
        }
        io?.inject?.invoke(syntheticInit(sid))
        flush.forEach { writePrompt(sid, it) }
    }

    // ---- inbound: MuxFrame translation ----

    /**
     * `{type:"server-request", rpcId, method, payload}` — the envelope's `method` IS the frame's own
     * discriminant, so this switch is the complete downstream vocabulary.
     */
    private suspend fun translateMux(root: JsonObject): List<AgentEvent> {
        // Synthetic frames we injected ourselves (init / errors) carry our own marker type.
        when (root.str("type")) {
            SYNTHETIC_INIT -> return listOf(
                AgentEvent.SessionInit(root.str("sessionId"), workdir, model = null),
            )
            SYNTHETIC_ERROR -> return listOf(
                AgentEvent.AssistantText("⚠️ ${root.str("message").orEmpty()}"),
                AgentEvent.TurnResult(finalText = null, usage = null, isError = true),
            )
            // A message with no verdict about the turn — the turn is still running (issue #291).
            SYNTHETIC_NOTICE -> return listOf(AgentEvent.AssistantText(root.str("message").orEmpty()))
        }
        val method = root.str("method") ?: return emptyList()
        // issue #291: the envelope's rpcId is the ONLY correlation token an ask carries — the payload has
        // none — and the answer must echo it back. It used to be dropped here.
        val rpcId = root.str("rpcId")
        val payload = root.obj("payload") ?: return emptyList()
        // The mux is MULTIPLEXED across every session the host holds — including sub-agents' own
        // sessions. Without this filter another session's output would be spliced into this chat.
        val frameSession = payload.str("sessionId")
        val ours = sessionId
        if (frameSession != null && ours != null && frameSession != ours) return emptyList()

        return when (method) {
            "session/event" -> translateSessionEvent(payload.obj("event") ?: return emptyList())
            "stream/error" -> {
                val msg = payload.obj("error")?.str("message") ?: "dsh stream error"
                listOf(
                    AgentEvent.AssistantText("⚠️ $msg"),
                    AgentEvent.TurnResult(finalText = null, usage = null, isError = true),
                )
            }
            // issue #291. A card, on the same PermissionBridge every other backend uses. Null = nothing new
            // to show: a mux replay of a card already pending, or a frame too malformed to answer.
            DshAskLedger.QUESTION_REQUESTED, DshAskLedger.APPROVAL_REQUESTED ->
                listOf(asks.requested(method, rpcId, payload) ?: AgentEvent.Ignored(method))
            // Somebody else settled it (the desktop web UI answered, or the turn was cancelled): retire
            // OUR card too, whatever the outcome. Our own answers left the table before this arrives.
            DshAskLedger.QUESTION_RESOLVED, DshAskLedger.APPROVAL_RESOLVED ->
                listOf(asks.resolved(method, payload) ?: AgentEvent.Ignored(method))
            else -> listOf(AgentEvent.Ignored(method))
        }
    }

    /**
     * One `SessionEvent` from a `session/event` frame.
     *
     * STREAMING vs FINAL: dsh emits `assistant/chunk` deltas as the reply is generated AND a complete
     * `assistant/message` when the step ends. Surfacing both would print every reply twice, so the LIVE
     * path takes the deltas (that is what makes text appear progressively) and drops the assembled
     * message. The DISK path does the exact opposite for the same reason — see [DshTranscriptReplay].
     */
    private fun translateSessionEvent(event: JsonObject): List<AgentEvent> {
        if (event["ignorable"]?.toString() == "true") return emptyList()
        val data = event.obj("data")
        return when (val type = event.str("type")) {
            // The consumption receipt for a prompt we sent: dsh echoes the user turn once it is really
            // in the conversation. Conversation's unconsumed-prompt ledger (issue #122) settles on this;
            // without it every relaunch would re-inject and re-run past prompts.
            "user/message" -> listOf(AgentEvent.UserReplay(DshTranscript.messageText(data)))
            "assistant/chunk" -> {
                val chunk = data?.obj("chunk") ?: return emptyList()
                when (chunk.str("type")) {
                    "text-delta" -> chunk.str("text")?.takeIf { it.isNotEmpty() }
                        ?.let { listOf(AgentEvent.AssistantText(it)) }.orEmpty()
                    "reasoning-delta" -> chunk.str("text")?.takeIf { it.isNotEmpty() }
                        ?.let { listOf(AgentEvent.AssistantThinking(it)) }.orEmpty()
                    // tool-call-delta: streamed tool arguments. v1 renders no tool cards (see scope).
                    else -> emptyList()
                }
            }
            // Dropped on purpose — already streamed as chunks above.
            "assistant/message" -> emptyList()
            "turn/end" -> listOf(AgentEvent.TurnResult(finalText = null, usage = null, isError = false))
            // v1 is text-only in BOTH the live and the replay path, so tool events render nothing. Doing
            // it in one path only would make a resumed session look different from the live one.
            "tool/call", "tool/result", "turn/start", "step/start", "step/end" ->
                listOf(AgentEvent.Ignored(type))
            else -> listOf(AgentEvent.Ignored(type ?: "dsh-event"))
        }
    }

    // ---- outbound ----

    override suspend fun sendPrompt(text: String, images: List<ImageData>) {
        // NOTE images ride no further: dsh's PromptContentPart does support an image part
        // ({type:"image", mediaType, data}), but v1 is text-only end to end and sending a half-wired
        // image part would fail the whole prompt rather than degrade.
        val sid = bootstrap.withLock {
            val current = sessionId
            if (current == null) pendingPrompts.addLast(text)
            current
        } ?: return
        writePrompt(sid, text)
    }

    private suspend fun writePrompt(sid: String, text: String) {
        val client = api ?: return
        val payload = buildJsonObject {
            put("sessionId", sid)
            // "queue" is the mid-turn-safe mode: a prompt sent while a turn runs is queued rather than
            // rejected, which matches how the Claude CLI buffers stdin. "steer" would interrupt.
            put("mode", "queue")
            putJsonArray("content") {
                addJsonObject { put("type", "text"); put("text", text) }
            }
        }
        val result = client.rpc("session.prompt", payload)
        // A 200 with ok:false is the normal way dsh reports a refusal — never treat the HTTP status alone
        // as success (that is how a failing session ends up looking merely silent).
        if (result != null && result["ok"]?.toString() != "true") {
            val why = result.obj("error")?.str("message") ?: "dsh rejected the prompt"
            io?.inject?.invoke(syntheticError(why))
        } else if (result == null) {
            io?.inject?.invoke(syntheticError("could not reach the dsh local API"))
        }
    }

    /**
     * VISIBLE FOR TESTS ONLY. The real session id is minted by a `session.create` RPC against a live dsh
     * host, and until one exists the ask bridge refuses every frame (fail-closed, [DshAskLedger]) — so
     * without this a test cannot reach the mux translation path at all.
     */
    internal fun bindSessionForTest(id: String) {
        sessionId = id
    }

    override suspend fun interrupt() {
        val sid = sessionId ?: return
        api?.rpc("session.cancel", buildJsonObject { put("sessionId", sid) })
    }

    override suspend fun renameSession(title: String): Boolean {
        val sid = sessionId ?: return false
        val result = api?.rpc(
            "session.rename",
            buildJsonObject { put("sessionId", sid); put("title", title) },
        )
        return result?.get("ok")?.toString() == "true"
    }

    override suspend fun respondPermission(
        askId: String,
        allow: Boolean,
        remember: Boolean,
        originalInput: JsonObject?,
        updatedInput: String?,
        denyMessage: String?,
    ) {
        // issue #291. `remember` / `originalInput` are deliberately unused: dsh has no "always allow"
        // (its outcome vocabulary is allowed-once / rejected, full stop), so the card is minted
        // neverRemember and a remembered scope can never form. `denyMessage` has no counterpart either —
        // dsh takes an outcome, not a sentence.
        if (!asks.answer(askId, allow, updatedInput)) {
            log.info("dsh respondPermission($askId) had nothing pending — already resolved or withdrawn")
        }
    }

    /** dsh bakes the sandbox mode into the process environment at launch, so a mode change needs a
     *  relaunch. Model switching is out of v1 scope and never forces one. */
    override fun applySettings(mode: PermissionMode?, model: String?, effort: String?): Boolean {
        var relaunch = false
        mode?.let {
            if (permissionModeFor(it) != permissionModeFor(this.mode)) relaunch = true
            this.mode = it
        }
        return relaunch
    }

    override suspend fun onProcessEnded(sessionId: String?) {
        teardownClient()
    }

    // ---- disk ----

    override fun transcriptDir(workdir: String): Path = DshPaths.sessionsRoot()

    override fun listSessions(workdir: String): List<SessionSummary> = DshTranscriptScanner.scan(workdir)

    override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> =
        DshTranscriptScanner.find(sessionId, workdir)?.let { DshTranscriptReplay.read(it.file) } ?: emptyList()

    override fun replaySlice(workdir: String, sessionId: String, sinceSeq: Long?): ReplaySlice =
        DshTranscriptScanner.find(sessionId, workdir)?.let { DshTranscriptReplay.slice(it.file, sinceSeq) }
            ?: ReplaySlice.EMPTY

    override fun replayPage(workdir: String, sessionId: String, beforeSeq: Long, limit: Int): ReplaySlice =
        DshTranscriptScanner.find(sessionId, workdir)?.let { DshTranscriptReplay.page(it.file, beforeSeq, limit) }
            ?: ReplaySlice.EMPTY

    /** dsh records per-turn usage in `assistant/message.usage`, but usage accounting is out of v1 scope
     *  and a half-populated statusline reads worse than an absent one. */
    override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null

    // ---- synthetic frames (our own injections, distinguished from dsh's by their `type`) ----

    private fun syntheticInit(sid: String): String =
        buildJsonObject { put("type", SYNTHETIC_INIT); put("sessionId", sid) }.toString()

    private fun syntheticError(message: String): String =
        buildJsonObject { put("type", SYNTHETIC_ERROR); put("message", message) }.toString()

    /** Like [syntheticError] but WITHOUT a TurnResult: says something went wrong while leaving the turn's
     *  state alone (issue #291 — a refused `/api/respond` leaves the dsh turn genuinely still running). */
    private fun syntheticNotice(message: String): String =
        buildJsonObject { put("type", SYNTHETIC_NOTICE); put("message", message) }.toString()

    private companion object {
        /** Namespaced so they can never collide with a real dsh frame type. */
        const val SYNTHETIC_INIT = "cc-pocket/dsh-init"
        const val SYNTHETIC_ERROR = "cc-pocket/dsh-error"
        const val SYNTHETIC_NOTICE = "cc-pocket/dsh-notice"

        /** Readiness window for the mux socket: comfortably longer than the client's own retry budget,
         *  so this never gives up while that is still trying. */
        const val READY_POLLS = 60
        const val READY_POLL_MS = 200L
    }
}
