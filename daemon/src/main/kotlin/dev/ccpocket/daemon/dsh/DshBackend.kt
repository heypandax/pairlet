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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
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
 * Drives the DeepSeek Harness (`dsh`) over its ACP v1 stdio profile (issue #255, re-transported for dsh 0.1.2).
 *
 * ## Why the transport changed
 *
 * v1 of this backend drove the `web` profile's local HTTP+WebSocket API, because dsh rc.6 had no ACP
 * server at all. dsh 0.1.2-rc.1 deleted that API (`@deepseek-ai/dsh-host-apiproxy` stops at 0.1.1-rc.2)
 * and replaced it with the Typert gateway: `POST /api/<namespace>/<method>`, a bidirectional
 * `/api/remote.mux` socket, and — decisively — a signed browser cookie that only `GET /?token=<launch
 * token>` can mint, required on EVERY `/api` request. The old client could no longer even open its
 * socket, which is what the phone reported as *"could not reach the dsh local API on 127.0.0.1:<port>
 * after 20 attempts"*. The same release shipped `dsh --profile acp`, a STANDARD Agent Client Protocol
 * server on stdio, so this backend now looks like every other one:
 *
 * ```
 *   processBuilder → dsh --profile acp      (stdout = ACP JSON-RPC frames, stderr = dsh's own logs)
 *   attach         → initialize → session/new | session/resume
 *   parse(line)    → session/update … → AgentEvent
 *   sendPrompt     → session/prompt (one in flight; the rest queue here)
 * ```
 *
 * Every shape below is pinned by `scripts/probe-dsh-acp.py` against dsh 0.1.2-rc.1 — RE-RUN IT AFTER
 * EVERY dsh UPGRADE, because drift here is silent (a renamed param degrades to "the model never
 * switched", not to an error anyone sees).
 *
 * ## Probe-verified facts that shape this class
 *
 *  1. **One prompt in flight per session.** A second `session/prompt` is refused with `-32602 "a prompt
 *     is already in flight for this session"`, so the FIFO lives HERE (same as [dev.ccpocket.daemon.kimi.KimiBackend]).
 *  2. **There is no `user_message_chunk`.** The prompt's own response IS the consumption receipt, so the
 *     [AgentEvent.UserReplay] is synthesized when the turn settles. Without it Conversation's
 *     unconsumed-prompt ledger never settles and every relaunch re-runs old prompts (issue #122).
 *  3. **`session/set_config_option` takes `configId`, not `optionId`** (a wrong name is a zod error, not
 *     a no-op), and the model VALUE is the opaque `["<provider>","<model>"]` JSON string dsh advertised —
 *     a bare model id is rejected with `unknown model option`. The bare id is what cc-pocket shows the
 *     user, so the two are joined through the advertised catalogue ([DshConfigOptions]).
 *  4. **`session/request_permission` carries only `toolCall.toolCallId`** — no name, no input. The card's
 *     body therefore comes from the `tool_call` update we saw earlier in the turn, which is why tool
 *     calls are tracked even though v1 renders no tool cards.
 *  5. **`session/resume` replays NO history** and happily loads sessions created by the old web profile
 *     (probe-verified against a pre-upgrade session): existing sessions stay resumable, and history keeps
 *     coming from the on-disk transcript, which 0.1.2 did not change ([DshTranscriptReplay]).
 *
 * ## What the ACP surface does not carry (deliberate scope loss vs. the web transport)
 *
 * Agent presets, `/`-commands, session rename and fork have no ACP counterpart — dsh states this
 * explicitly ("modes, commands, plans, terminals, elicitation" are outside the automation surface). So
 * [renameSession] answers false, the preset axis is no longer advertised ([DshModelService]), and
 * `spec.agentPreset` is ignored with a log line rather than silently pretended into the header.
 *
 * ## Images (issue #377)
 *
 * Whether a prompt may carry images is decided per CONNECTION: dsh's `initialize` answer advertises
 * `agentCapabilities.promptCapabilities.image` only when its launch model route declares image input and an
 * attachment store is mounted (dsh-v0.1.5-rc.1), and it rejects any image block the handshake did not
 * advertise. So the flag is read off every handshake and reset on every attach — never pinned to a probed
 * release. Advertised: every [ImageData] rides the prompt as an ACP image block. Not advertised: the prompt
 * is refused as an error turn rather than sent as its text alone.
 *
 * ## Permissions
 *
 * `session/request_permission` becomes a real [AgentEvent.ControlRequest] on the same
 * [dev.ccpocket.daemon.agent.PermissionBridge] every other backend uses. dsh offers only
 * `allow-once` / `reject-once` (no always-allow), so `remember` cannot be honoured and a remembered
 * scope can never form — same product constraint as v1.
 */
class DshBackend(
    private val dshBin: String?,
    private val catalog: DshCatalog = DshCatalog,
    private val sessionsRoot: () -> Path = DshPaths::sessionsRoot,
) : AgentBackend {
    private val log = logger("DshBackend")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val idSeq = AtomicLong(1)

    /** Owns the handshake watchdog and the async config pushes; cancelled when the process ends. */
    private var scope: CoroutineScope? = null

    @Volatile private var io: AgentIo? = null
    @Volatile private var resolvedExe: Path? = null
    @Volatile private var workdir: String = ""
    @Volatile private var resumeId: String? = null
    @Volatile private var mode: PermissionMode = PermissionMode.DEFAULT

    /** The launch knobs the client chose. Both are live-switchable through `session/set_config_option`
     *  and are re-applied on every [applySettings]. */
    @Volatile private var launchModel: String? = null
    @Volatile private var launchEffort: String? = null

    @Volatile private var sessionId: String? = null

    /**
     * Why this session will never open, once a startup stage has answered with an error (issue #388).
     *
     * Set by [failStartup], cleared by [attach]. It carries three duties: it stops [watchHandshake] from
     * volunteering a second, WRONG diagnosis over a stage that already reported a real one; it settles
     * prompts that arrive AFTER the failure, which would otherwise queue behind a gate that can no longer
     * open; and it is the record that this process is done trying — nothing here retries a stage or opens
     * a replacement session, because a resume that failed must stay the session the user asked for.
     */
    @Volatile private var openFailure: String? = null

    /** `agentCapabilities.promptCapabilities.image` off THIS process's `initialize` answer — only an explicit
     *  `true` counts (see "Images" above). */
    @Volatile private var imagePrompts = false

    /** The session's advertised configuration options, as last read back from dsh. The model picker
     *  ([DshModelService]) reads the same catalogue through [DshCatalog], and every model/effort write
     *  joins its opaque wire value out of it. */
    @Volatile private var options: DshConfigOptions = DshConfigOptions.EMPTY

    // JSON-RPC id correlation.
    @Volatile private var initializeId: Long = -1
    @Volatile private var sessionOpenId: Long = -1

    /** outstanding session/prompt id → the prompt text, replayed as the consumption receipt on settle. */
    private val promptIds = ConcurrentHashMap<Long, String>()

    /** outstanding set_config_option id → what it was trying to do, so its answer can be reported and the
     *  chain continued. */
    private val configIds = ConcurrentHashMap<Long, ConfigWrite>()

    /** toolCallId → what dsh said it was about, so a later permission request (which carries only the id)
     *  can render a card a human can decide on. */
    private val toolCalls = ConcurrentHashMap<String, ToolInfo>()

    /** askId → the JSON-RPC request id + the options it offered. */
    private val pendingApprovals = ConcurrentHashMap<String, PendingApproval>()

    private data class ToolInfo(val title: String?, val input: JsonObject?)
    private data class PendingApproval(val rpcId: JsonElement, val options: JsonArray)

    /** One pending `session/set_config_option`. [announce] marks a USER-driven switch, which is allowed to
     *  say out loud that it failed; a launch-time application stays quiet (a message before the first turn
     *  reads as output the agent never wrote). [flushAfter] carries the "open the prompt gate when this
     *  settles" duty through the response. */
    private data class ConfigWrite(
        val configId: String,
        val value: String,
        val announce: Boolean,
        val flushAfter: Boolean,
    )

    /** Guards [sessionId], [pendingPrompts] and [promptQueue] so the opening turn can never be lost to a
     *  race between "not open yet, buffer it" and "just opened, flush the buffer". */
    private val bootstrap = Mutex()

    /** Prompts that arrived before the session was ready to take them. */
    private val pendingPrompts = ArrayDeque<Prompt>()

    /**
     * Closed from launch until the session is open AND its launch-time model/effort have landed.
     *
     * A prompt that slips through the window between `session/new` answering and the config write
     * settling would run the opening turn on the model the user did NOT pick — which is what the whole
     * write-then-flush chain exists to prevent, and which a "buffer only while sessionId is null" gate
     * misses by exactly the round trip that matters.
     */
    @Volatile private var promptGate = false

    /** Prompts that arrived while a turn was in flight (fact 1). */
    private val promptQueue = ArrayDeque<Prompt>()

    private data class Prompt(val text: String, val images: List<ImageData>)

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
        this.launchModel = spec.model
        this.launchEffort = spec.effort
        spec.agentPreset?.takeIf { it.isNotBlank() }?.let {
            // The ACP surface has no agent-preset axis; announcing one we cannot select would be a lie in
            // the session header. Creation explains the Web grouping limitation separately.
            log.info("dsh agent preset '$it' ignored — the ACP profile exposes no preset selection")
        }
        // reset per-process protocol state (runs on EVERY (re)launch)
        sessionId = null
        promptGate = false
        openFailure = null
        imagePrompts = false
        options = DshConfigOptions.EMPTY
        catalog.unpublish(this)
        promptIds.clear(); configIds.clear(); toolCalls.clear(); pendingApprovals.clear()
        bootstrap.withLock { pendingPrompts.clear(); promptQueue.clear() }
        scope?.let { runCatching { it.cancel() } }
        val fresh = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = fresh
        initializeId = rpcRequest("initialize", buildJsonObject {
            put("protocolVersion", ACP_PROTOCOL_VERSION)
            putJsonObject("clientCapabilities") {
                // We serve no filesystem or terminal on the agent's behalf; dsh does its own IO.
                putJsonObject("fs") { put("readTextFile", false); put("writeTextFile", false) }
            }
        })
        fresh.launch { watchHandshake() }
    }

    /**
     * A handshake that never answers is the ONE failure this backend cannot diagnose from a frame, and it
     * is exactly what a pre-0.1.2 dsh does: `--profile acp` composes a profile with no app in it, so
     * nothing ever claims stdio and the session would sit silent forever. Bounded, and the message names
     * the version to install.
     */
    private suspend fun watchHandshake() {
        repeat(HANDSHAKE_POLLS) {
            if (sessionId != null || initializeId < 0 || openFailure != null) return
            delay(HANDSHAKE_POLL_MS)
            if (sessionOpenId >= 0) return // the handshake got as far as opening a session; errors ride the wire
            // A stage that answered with an error already told the user WHICH stage and why (issue #388);
            // "your dsh is too old" on top of that would be a second, contradicting diagnosis.
            if (openFailure != null) return
        }
        if (sessionOpenId < 0 && sessionId == null && openFailure == null) {
            injectStartupFailure(STAGE_HANDSHAKE, DshLauncher.outdatedHint())
        }
    }

    // ---- inbound ----

    override suspend fun parse(line: String): List<AgentEvent> {
        val t = line.trim()
        if (t.isEmpty()) return emptyList()
        val root = runCatching { json.parseToJsonElement(t) }.getOrNull() as? JsonObject
            // dsh keeps its logs on stderr, so an unparseable stdout line is genuinely unexpected.
            ?: return listOf(AgentEvent.Unparseable(t))
        // Frames we injected ourselves never travelled to dsh; they carry our own namespaced type.
        synthetic(root)?.let { return it }
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
        }.getOrElse { log.warn("dsh parse failed: ${it.message}"); emptyList() }
    }

    /** Our own injections (see [syntheticError] / [syntheticNotice] / [syntheticRefusal]) — the only frames on
     *  this pump that dsh did not write. */
    private suspend fun synthetic(root: JsonObject): List<AgentEvent>? {
        return when (root.str("type")) {
            SYNTHETIC_ERROR -> listOf(
                AgentEvent.AssistantText("⚠️ ${root.str("message").orEmpty()}"),
                AgentEvent.TurnResult(finalText = null, usage = null, isError = true),
            )
            // A message with no verdict about the turn — the turn itself is untouched (issue #291).
            SYNTHETIC_NOTICE -> listOf(AgentEvent.AssistantText(root.str("message").orEmpty()))
            // A prompt [sendPrompt] reserved but refused settles here exactly once, like its error response. A
            // stale id (already settled, or reserved by a previous process) says nothing.
            SYNTHETIC_REFUSAL -> root.long("id")?.let { promptIds.remove(it) }
                ?.let { failedPrompt(it, root.str("message").orEmpty()) }.orEmpty()
            else -> null
        }
    }

    private suspend fun handleResponse(idEl: JsonElement?, result: JsonObject?): List<AgentEvent> {
        val id = (idEl as? JsonPrimitive)?.longOrNull ?: return emptyList()
        if (id == initializeId) {
            imagePrompts = result?.obj("agentCapabilities")?.obj("promptCapabilities")?.bool("image") == true
            openSession()
            return emptyList()
        }
        if (id == sessionOpenId) return onSessionOpened(result)
        configIds.remove(id)?.let { return onConfigApplied(it, result) }
        val consumed = promptIds.remove(id) ?: return emptyList()
        // Settle → the consumption receipt (fact 2) BEFORE the TurnResult, so the ledger entry is gone by
        // the time the task-grant check runs — then let the next queued prompt out.
        return listOf(AgentEvent.UserReplay(consumed)) + onPromptDone(result) + flushQueuedPrompt()
    }

    private suspend fun handleErrorResponse(idEl: JsonElement?, error: JsonObject?): List<AgentEvent> {
        val id = (idEl as? JsonPrimitive)?.longOrNull
        val why = describeError(error)
        configIds.remove(id ?: -1)?.let { return onConfigFailed(it, why) }
        // issue #388: the two startup stages, told apart. `initialize` failing used to fall through to the
        // log line below — invisible on the wire, after which the handshake watchdog blamed the dsh version
        // 30s later. And a failed session open reported itself but left the opening prompt queued behind a
        // gate that could never open, so the conversation showed "Internal error" and then a turn that never
        // settled (and that every relaunch re-ran).
        if (id != null && id == initializeId) return failStartup(STAGE_HANDSHAKE, why)
        if (id != null && id == sessionOpenId) {
            return failStartup(if (resumeId != null) STAGE_RESUME else STAGE_NEW, why)
        }
        val consumed = id?.let { promptIds.remove(it) }
        if (consumed != null) return failedPrompt(consumed, why)
        log.warn("dsh error response id=$id: $why")
        return emptyList()
    }

    /**
     * A JSON-RPC error object → one line a user can act on.
     *
     * `message` alone is routinely `Internal error` (the JSON-RPC text for -32603), which names neither the
     * cause nor the stage; dsh puts the real reason in `data`. So the code and a BOUNDED, single-line
     * summary of `data` ride along — bounded because `data` can carry a whole stack, and this string is
     * shown in a chat.
     */
    private fun describeError(error: JsonObject?): String {
        val message = error?.str("message")?.takeIf { it.isNotBlank() }
            ?: "the DeepSeek Harness rejected the request"
        val code = error?.long("code")
        val data = error?.get("data")?.takeIf { it !is JsonNull }?.let { detail ->
            val text = (detail as? JsonPrimitive)?.contentOrNull
                ?: (detail as? JsonObject)?.let { o -> o.str("message") ?: o.str("details") ?: o.toString() }
                ?: detail.toString()
            text.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() && it != message }
        }
        return buildString {
            append(message)
            if (code != null) append(" (code $code)")
            if (data != null) append(": ").append(data.take(MAX_ERROR_DETAIL_CHARS))
        }
    }

    /**
     * A startup stage answered with an error, so this session will never open (issue #388).
     *
     * Names the STAGE — "Internal error" on its own leaves a user unable to tell a too-old dsh from a
     * session that no longer exists from a prompt that was refused — and gives every message that was
     * waiting on the session a TERMINAL state. That second half is not cosmetic: an unsettled prompt keeps
     * its entry in the Conversation's unconsumed-prompt ledger, so it is re-injected on the next relaunch
     * and the failure loops (the same hazard [failedPrompt] exists for).
     *
     * What it deliberately does NOT do: retry the stage, or open a replacement session. A resume that
     * failed must stay the session the user asked for, and no other conversation's state is touched.
     */
    private suspend fun failStartup(stage: String, why: String): List<AgentEvent> {
        val message = "$stage: $why"
        openFailure = message
        log.warn("dsh startup failed — $message")
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

    /** [failStartup] for callers that are NOT on the parse pump (the handshake watchdog): the same
     *  settlement, delivered through [AgentIo.inject] because only the pump may return events. */
    private suspend fun injectStartupFailure(stage: String, why: String) {
        val message = "$stage: $why"
        openFailure = message
        log.warn("dsh startup failed — $message")
        val stranded = drainWaitingPrompts()
        if (stranded.isEmpty()) {
            io?.inject?.invoke(syntheticError(message))
            return
        }
        // Each waiting prompt settles exactly like a refused one: reserve its id, then let the pump turn the
        // refusal into its error turn (UserReplay included, so the ledger entry goes away).
        for (prompt in stranded) {
            val id = bootstrap.withLock { reservePrompt(prompt.text) }
            io?.inject?.invoke(syntheticRefusal(id, message))
        }
    }

    /** Everything queued on a session that will never take it, removed in arrival order. */
    private suspend fun drainWaitingPrompts(): List<Prompt> = bootstrap.withLock {
        val waiting = pendingPrompts.toList() + promptQueue.toList()
        pendingPrompts.clear()
        promptQueue.clear()
        waiting
    }

    /** A failed prompt was still CONSUMED — its failure surfaces right here as an error turn. Left unsettled,
     *  Conversation would re-inject it on every relaunch and loop the failure; and it must not stall the FIFO
     *  behind it. */
    private suspend fun failedPrompt(text: String, why: String): List<AgentEvent> =
        errorTurn(text, why) + flushQueuedPrompt()

    private fun errorTurn(text: String, why: String): List<AgentEvent> = listOf(
        AgentEvent.UserReplay(text),
        AgentEvent.AssistantText("⚠️ $why"),
        AgentEvent.TurnResult(finalText = null, usage = null, isError = true),
    )

    private suspend fun openSession() {
        val rid = resumeId
        sessionOpenId = if (rid != null) {
            // A resume adopts the session's own recorded configuration; dsh verifies the workspace and
            // restores the log WITHOUT replaying updates (fact 5).
            rpcRequest("session/resume", buildJsonObject {
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

    /**
     * `session/new` answers `{sessionId, configOptions}`; `session/resume` answers `{configOptions}` for
     * the id we sent. The catalogue read-back is what seeds the header — the model chip used to stay
     * blank until the session happened to answer once.
     */
    private suspend fun onSessionOpened(result: JsonObject?): List<AgentEvent> {
        val sid = result?.str("sessionId") ?: resumeId
            // A `session/new` that answers without an id is a success frame for a session nobody can address:
            // the same dead end as an error response, settled the same way rather than left to the watchdog.
            ?: return failStartup(STAGE_NEW, "dsh did not return a session id")
        // Only a successful fresh creation needs this notice. Keeping the pre-assignment state also
        // avoids repeating it if the same session/new response is delivered twice.
        val showGroupingNotice = resumeId == null && sessionId == null
        options = DshConfigOptions.parse(result?.arr("configOptions"))
        catalog.publish(this, options)
        sessionId = sid
        val events = listOfNotNull(
            AgentEvent.SessionInit(sessionId = sid, cwd = workdir, model = options.currentModel,
                notice = if (showGroupingNotice) UNGROUPED_NOTICE else null),
            runtimeMeta(model = options.currentModel, effort = options.currentEffort),
        )
        // Model/effort BEFORE the prompt gate opens: running the opening turn on the previous model and
        // correcting it afterwards would bill the user for a model they did not pick. Each write is a
        // request, so the gate opens on its RESPONSE (see [onConfigApplied]) rather than on hope.
        if (startConfigChain(launchModel, launchEffort, announce = false)) return events
        return events + flushPendingPrompts()
    }

    private fun onPromptDone(result: JsonObject?): List<AgentEvent> {
        // ACP prompt response: {stopReason: end_turn | cancelled | max_tokens | refusal | …}
        val stop = result?.str("stopReason")
        return listOf(
            AgentEvent.TurnResult(
                finalText = null, // already streamed as agent_message_chunk
                // No per-turn totals on this wire; occupancy rides usage_update (see [handleUpdate]).
                usage = null,
                isError = stop == "refusal",
            ),
        )
    }

    // ---- inbound: notifications ----

    private fun handleNotification(method: String, params: JsonObject?): List<AgentEvent> {
        params ?: return emptyList()
        return when (method) {
            "session/update" -> {
                // The stdio connection is single-session, but dsh stamps every update anyway; a frame for
                // another session (a sub-agent's) must never be spliced into this chat.
                val sid = params.str("sessionId")
                if (sid != null && sessionId != null && sid != sessionId) return emptyList()
                handleUpdate(params.obj("update") ?: return emptyList())
            }
            else -> emptyList()
        }
    }

    private fun handleUpdate(update: JsonObject): List<AgentEvent> = when (val kind = update.str("sessionUpdate")) {
        "agent_message_chunk" -> textOf(update)?.let { listOf(AgentEvent.AssistantText(it)) }.orEmpty()
        "agent_thought_chunk" -> textOf(update)?.let { listOf(AgentEvent.AssistantThinking(it)) }.orEmpty()
        // Not observed on this wire (fact 2), but harmless to honour if a later dsh starts sending it.
        "user_message_chunk" -> listOf(AgentEvent.UserReplay(textOf(update)))
        // `{used, size}` — the session's context occupancy and its real window, the only place either
        // reaches the live wire (issue #320). `used` is a TOTAL, so it rides the ONE AssistantUsage column
        // that means "context-bearing input"; splitting it across the cache columns would double count,
        // because downstream treats those columns as disjoint sets.
        "usage_update" -> {
            val used = update.long("used")
            listOfNotNull(
                runtimeMeta(contextWindow = update.long("size")),
                used?.takeIf { it > 0 }?.let {
                    AgentEvent.AssistantUsage(
                        inputTokens = it,
                        cacheCreationInputTokens = null,
                        cacheReadInputTokens = null,
                    )
                },
            )
        }
        // v1 renders no tool cards for dsh, in the LIVE path and the replay path alike — a resumed session
        // must not look different from a live one. The state is still recorded: a permission request names
        // only the tool-call id (fact 4), and this is the only place its subject is on the wire.
        "tool_call", "tool_call_update" -> {
            update.str("toolCallId")?.let { id ->
                val previous = toolCalls[id]
                toolCalls[id] = ToolInfo(
                    title = update.str("title") ?: previous?.title,
                    input = update.obj("rawInput") ?: previous?.input,
                )
                if (update.str("status").let { it == "completed" || it == "failed" }) toolCalls.remove(id)
            }
            listOf(AgentEvent.Ignored(kind))
        }
        else -> listOf(AgentEvent.Ignored(kind))
    }

    /** ACP ContentBlock → its text, when it is one. */
    private fun textOf(update: JsonObject): String? =
        update.obj("content")?.takeIf { it.str("type") == "text" }?.str("text")?.takeIf { it.isNotEmpty() }

    // ---- inbound: server→client requests ----

    private suspend fun handleServerRequest(
        method: String,
        idEl: JsonElement,
        params: JsonObject?,
    ): List<AgentEvent> {
        val askId = (idEl as? JsonPrimitive)?.contentOrNull ?: idEl.toString()
        return when (method) {
            "session/request_permission" -> {
                val options = params?.arr("options") ?: JsonArray(emptyList())
                pendingApprovals[askId] = PendingApproval(idEl, options)
                // The request carries only toolCall.toolCallId (fact 4) — the subject comes from the
                // tool_call update we recorded earlier in this turn.
                val toolCallId = params?.obj("toolCall")?.str("toolCallId")
                val info = toolCallId?.let { toolCalls[it] }
                val name = info?.title ?: params?.obj("toolCall")?.str("title") ?: "tool"
                val input = info?.input ?: buildJsonObject { put("description", name) }
                listOf(AgentEvent.ControlRequest(askId, name, input))
            }
            // We declared no fs/terminal capabilities, so nothing else should arrive. Decline explicitly:
            // an unanswered server request hangs dsh's turn forever (it has no timeout of its own).
            else -> {
                log.warn("dsh unsupported server request: $method")
                rpcRespondError(idEl, -32601, "not supported by cc-pocket")
                emptyList()
            }
        }
    }

    // ---- outbound: prompts ----

    override suspend fun sendPrompt(text: String, images: List<ImageData>) {
        val prompt = Prompt(text, images)
        // The session already failed to open (issue #388): buffering this would park it behind a gate that
        // can never open — no terminal state, and a re-run on the next relaunch. Settle it instead, with the
        // stage error that explains why, exactly like a refused prompt.
        openFailure?.let { why ->
            val id = bootstrap.withLock { reservePrompt(text) }
            io?.inject?.invoke(syntheticRefusal(id, why))
            return
        }
        val reserved = bootstrap.withLock {
            when {
                sessionId == null || !promptGate -> { pendingPrompts.addLast(prompt); null }
                // A turn is in flight — dsh refuses a second prompt (fact 1), so FIFO it here and flush
                // when the in-flight one settles.
                promptIds.isNotEmpty() -> { promptQueue.addLast(prompt); null }
                else -> reservePrompt(text)
            }
        } ?: return
        if (acceptable(prompt)) {
            writePrompt(reserved, prompt)
        } else {
            // Refused through the pump, like an error response: the reservation holds everything sent after
            // it until the refusal settles. Waiting for channel room is safe here, off the pump — a refusal
            // the pump itself finds returns its events instead (see [takeQueuedPrompt]).
            io?.inject?.invoke(syntheticRefusal(reserved, refuse(prompt)))
        }
    }

    /** Allocate the request id and mark the prompt in flight — MUST run inside [bootstrap] so a racing
     *  send/flush can never see "idle" between the decision and the registration (a double send is
     *  exactly the `-32602 already in flight` the queue exists to prevent). */
    private fun reservePrompt(text: String): Long = idSeq.getAndIncrement().also { promptIds[it] = text }

    /** The session just opened (and any launch-time config landed): release what arrived before it. Returns
     *  the error turns of the prompts refused on the way. */
    private suspend fun flushPendingPrompts(): List<AgentEvent> {
        val refused = ArrayList<Prompt>()
        val first = bootstrap.withLock {
            if (sessionId == null) return emptyList()
            promptGate = true
            // Everything moves onto the one FIFO; only its head may go out, and only if no turn is
            // running (this can be reached mid-session by a user-driven model switch).
            promptQueue.addAll(pendingPrompts)
            pendingPrompts.clear()
            takeQueuedPrompt(refused)
        }
        first?.let { (id, prompt) -> writePrompt(id, prompt) }
        return refusals(refused)
    }

    /** The in-flight prompt settled (any stopReason, error included) — send the oldest queued one. Returns
     *  the error turns of the prompts refused on the way. */
    private suspend fun flushQueuedPrompt(): List<AgentEvent> {
        val refused = ArrayList<Prompt>()
        val next = bootstrap.withLock { takeQueuedPrompt(refused) }
        next?.let { (id, prompt) -> writePrompt(id, prompt) }
        return refusals(refused)
    }

    /**
     * Reserve the FIFO head when no turn is running — MUST run inside [bootstrap]. A head this session cannot
     * take moves to [refused] and the next one is tried, so a refusal never stalls the prompts behind it.
     *
     * Its callers run on the pump, so they hand the refusals back as events: injecting them would wait for
     * room on the very channel the pump drains.
     */
    private fun takeQueuedPrompt(refused: MutableList<Prompt>): Pair<Long, Prompt>? {
        if (sessionId == null || promptIds.isNotEmpty()) return null
        while (true) {
            val next = promptQueue.removeFirstOrNull() ?: return null
            if (acceptable(next)) return reservePrompt(next.text) to next
            refused += next
        }
    }

    /** Images ride as ACP image blocks after the text ([ImageData] is already the Base64 + MIME pair a block
     *  holds); a prompt with images but blank text sends the images alone, never an empty text block. */
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

    /** dsh fails the WHOLE prompt on an image block it did not advertise, and sending the text alone would be
     *  the silent loss issue #377 is about — so a prompt with images needs the capability, or it is refused. */
    private fun acceptable(prompt: Prompt): Boolean = prompt.images.isEmpty() || imagePrompts

    private fun refusals(refused: List<Prompt>): List<AgentEvent> = refused.flatMap { errorTurn(it.text, refuse(it)) }

    /** Log a refusal and word it for the chat — counts only: the image bytes reach neither. */
    private fun refuse(prompt: Prompt): String {
        val n = prompt.images.size
        log.warn("dsh prompt with $n image(s) refused — this session did not advertise image input")
        return "not sent: DeepSeek Harness did not advertise image input for this session, so nothing reached " +
            "the agent. Send the message again without the ${if (n == 1) "image" else "$n images"}."
    }

    override suspend fun interrupt() {
        val sid = sessionId ?: return
        // ACP session/cancel is a NOTIFICATION; the in-flight prompt then resolves stopReason=cancelled.
        rpcNotify("session/cancel", buildJsonObject { put("sessionId", sid) })
    }

    /** The ACP surface has no rename (dsh names sessions itself, from the first prompt). Answering false
     *  lets the caller fall back rather than reporting a rename that never happened. */
    override suspend fun renameSession(title: String): Boolean = false

    override suspend fun respondPermission(
        askId: String,
        allow: Boolean,
        remember: Boolean,
        originalInput: JsonObject?,
        updatedInput: String?,
        denyMessage: String?,
    ) {
        val pending = pendingApprovals.remove(askId) ?: run {
            log.info("dsh respondPermission($askId) had nothing pending — already resolved or withdrawn")
            return
        }
        // `remember` / `denyMessage` are deliberately unused: dsh offers allow-once / reject-once only
        // (probe 0.1.2-rc.1), so a remembered scope can never form and there is no place for a sentence.
        val optionId = pickOption(pending.options, allow)
        val outcome = if (optionId != null) {
            buildJsonObject { put("outcome", "selected"); put("optionId", optionId) }
        } else {
            buildJsonObject { put("outcome", "cancelled") } // nothing matched → cancel beats guessing
        }
        rpcRespondResult(pending.rpcId, buildJsonObject { put("outcome", outcome) })
    }

    /** The option whose `kind` matches the decision. Ids are dsh's own strings (`allow-once`), never ours. */
    private fun pickOption(options: JsonArray, allow: Boolean): String? {
        val byKind = options.mapNotNull { it as? JsonObject }
            .mapNotNull { o -> o.str("optionId")?.let { (o.str("kind") ?: "") to it } }
        fun of(vararg kinds: String): String? =
            kinds.firstNotNullOfOrNull { k -> byKind.firstOrNull { it.first == k }?.second }
        return if (allow) of("allow_once", "allow_always") else of("reject_once", "reject_always")
    }

    // ---- outbound: model / effort ----

    /**
     * Queue the `session/set_config_option` writes [model] and [effort] need, and send the first.
     *
     * Returns true when a write really went out, i.e. when the caller must wait for its response rather
     * than proceeding. Nothing is sent for a value the session is ALREADY on — dsh would accept it, but
     * an unnecessary round trip at launch delays the opening turn for nothing.
     */
    private suspend fun startConfigChain(model: String?, effort: String?, announce: Boolean): Boolean {
        val sid = sessionId ?: return false
        val writes = ArrayList<ConfigWrite>(2)
        model?.takeIf { it.isNotBlank() && it != options.currentModel }?.let { wanted ->
            val value = options.modelValue(wanted)
            if (value == null) {
                // The id is not in dsh's catalogue: say so rather than sending a value it will reject.
                log.warn("dsh has no model option for $wanted — leaving the session's own selection")
                if (announce) io?.inject?.invoke(syntheticNotice("⚠️ DeepSeek Harness has no model $wanted"))
            } else {
                writes += ConfigWrite(DshConfigOptions.MODEL, value, announce, flushAfter = false)
            }
        }
        effort?.takeIf { it.isNotBlank() && it != options.currentEffort }?.let {
            writes += ConfigWrite(DshConfigOptions.EFFORT, it, announce, flushAfter = false)
        }
        if (writes.isEmpty()) return false
        // Only the LAST write opens the prompt gate; the model must land before the effort, because the
        // valid effort levels are a property of the selected model.
        val chain = writes.mapIndexed { i, w -> w.copy(flushAfter = i == writes.lastIndex) }
        pendingConfig.addAll(chain.drop(1))
        sendConfig(sid, chain.first())
        return true
    }

    /** The remaining writes of the current chain, in order. Written from the parse pump AND from
     *  [applySettings]'s scope launch, so it is concurrent by construction. */
    private val pendingConfig = java.util.concurrent.ConcurrentLinkedDeque<ConfigWrite>()

    private suspend fun sendConfig(sid: String, write: ConfigWrite) {
        val id = idSeq.getAndIncrement()
        configIds[id] = write
        rpcSend(id, "session/set_config_option", buildJsonObject {
            put("sessionId", sid)
            put("configId", write.configId) // NOT optionId (fact 3)
            put("value", write.value)
        })
    }

    /** dsh answers a config write with the COMPLETE resulting state — that read-back, never our request,
     *  is what the header is told. */
    private suspend fun onConfigApplied(write: ConfigWrite, result: JsonObject?): List<AgentEvent> {
        options = DshConfigOptions.parse(result?.arr("configOptions"), fallback = options)
        catalog.publish(this, options)
        val meta = runtimeMeta(model = options.currentModel, effort = options.currentEffort)
        return listOfNotNull(meta) + continueConfigChain(write)
    }

    private suspend fun onConfigFailed(write: ConfigWrite, why: String): List<AgentEvent> {
        log.warn("dsh set_config_option(${write.configId}=${write.value}) failed: $why")
        // Only a user-driven switch says so out loud (see [ConfigWrite.announce]).
        if (write.announce) {
            val what = if (write.configId == DshConfigOptions.MODEL) "the model" else "the reasoning effort"
            io?.inject?.invoke(syntheticNotice("⚠️ could not switch $what: $why"))
        }
        return continueConfigChain(write)
    }

    /** Send the next write of the chain, or — when this was the last one — open the prompt gate (returning the
     *  error turns of any prompt it refused). A FAILED write still advances: queued prompts must never be held
     *  hostage by a preference that dsh refused. */
    private suspend fun continueConfigChain(write: ConfigWrite): List<AgentEvent> {
        val next = pendingConfig.pollFirst()
        val sid = sessionId
        if (next != null && sid != null) {
            sendConfig(sid, next)
            return emptyList()
        }
        // End of the chain — or the session went away under it, which must not strand the queue either.
        return if (write.flushAfter || next != null) flushPendingPrompts() else emptyList()
    }

    /**
     * A model/effort change applies to the NEXT turn — no relaunch (`session/set_config_option` is
     * accepted at any point in a session's life). A MODE change does need one: `DSH_PERMISSION_MODE` is
     * read at process boot and the ACP surface exposes no mode switch.
     *
     * The write is fired on the backend scope rather than awaited: this runs on the Conversation's command
     * path, which must not block on IO, and the Boolean only answers the relaunch question. The read-back
     * rides home as a [AgentEvent.RuntimeMeta], so the header follows the truth rather than the request.
     */
    override fun applySettings(mode: PermissionMode?, model: String?, effort: String?): Boolean {
        var relaunch = false
        mode?.let {
            if (permissionModeFor(it) != permissionModeFor(this.mode)) relaunch = true
            this.mode = it
        }
        model?.let { launchModel = it }
        effort?.let { launchEffort = it }
        if (model == null && effort == null) return relaunch
        if (sessionId == null) return relaunch // stored above; the launch path applies it at session open
        scope?.launch { startConfigChain(model, effort, announce = true) }
        return relaunch
    }

    override suspend fun onProcessEnded(sessionId: String?) {
        catalog.unpublish(this)
        runCatching { scope?.cancel() }
        scope = null
    }

    // ---- disk (unchanged by the transport switch: dsh 0.1.2 did not move or reshape the store) ----

    override fun transcriptDir(workdir: String): Path = DshPaths.sessionsRoot()

    override fun listSessions(workdir: String): List<SessionSummary> = DshTranscriptScanner.scan(workdir)

    override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> =
        replaySlice(workdir, sessionId, null).messages

    // Known-session replay must reach the reader even when its header is damaged. Discovery still
    // requires a trustworthy header/cwd; locating a file by its encoded id does not invent either.
    private fun replayFile(workdir: String, sessionId: String): Path? =
        DshPaths.findSessionDir(sessionId, workdir, sessionsRoot())?.let(DshPaths::transcriptFile)

    private fun missingHistory() = ReplaySlice(emptyList(), delta = true, quality = "unavailable",
        readError = "DSH history unavailable: the session transcript could not be located or read. Retry after restoring access.")

    override fun replaySlice(workdir: String, sessionId: String, sinceSeq: Long?): ReplaySlice =
        replayFile(workdir, sessionId)?.let { DshTranscriptReplay.slice(it, sinceSeq) } ?: missingHistory()

    override fun replayPage(workdir: String, sessionId: String, beforeSeq: Long, limit: Int): ReplaySlice =
        replayFile(workdir, sessionId)?.let { DshTranscriptReplay.page(it, beforeSeq, limit) } ?: missingHistory()

    /**
     * The RESUME SEED — the occupancy a reopened session shows BEFORE its first new turn.
     *
     * Read off the last answered turn's own `data.usage`, with the same arithmetic dsh publishes live as
     * `usage_update.used` ([DshTranscript.ResumeMeta.contextUsed] documents the derivation), so the seed
     * and the first live frame are one number. Rides the SAME (file, mtime)-cached pass as the model /
     * window / effort reads below — the three-facts parse became four at no extra I/O.
     */
    override fun resumeContextTokens(workdir: String, sessionId: String): Long? =
        resumeMeta(workdir, sessionId).contextUsed

    // ---- resume metadata (issue #320) ----
    //
    // These read the model / effort / context window off the RECORDS of a running request on disk (see
    // [DshTranscript.resumeMeta]). The live wire says the same things through `session/new`'s
    // configOptions and `usage_update`, but only once the process is up; a reopened session shows the
    // header before that. No evidence ⇒ null ⇒ the phone keeps saying unknown.

    override fun resumeModel(workdir: String, sessionId: String): String? =
        resumeMeta(workdir, sessionId).model

    override fun resumeContextWindow(workdir: String, sessionId: String): Long? =
        resumeMeta(workdir, sessionId).contextWindow

    override fun resumeEffort(workdir: String, sessionId: String): String? =
        resumeMeta(workdir, sessionId).effort

    private data class CachedMeta(val mtime: Long, val meta: DshTranscript.ResumeMeta)

    /** The Conversation asks for the three facts one at a time; the answer costs a full-transcript stream, so
     *  it is parsed ONCE per (file, mtime). Keyed by mtime rather than cached outright: a live session's file
     *  keeps growing, and a resume that landed on a stale parse would announce yesterday's model. */
    private val metaCache = ConcurrentHashMap<String, CachedMeta>()

    private fun resumeMeta(workdir: String, sessionId: String): DshTranscript.ResumeMeta {
        val found = runCatching { DshTranscriptScanner.find(sessionId, workdir, storeRoot()) }.getOrNull()
            ?: return DshTranscript.ResumeMeta.EMPTY
        metaCache[found.file.toString()]?.takeIf { it.mtime == found.mtime }?.let { return it.meta }
        val fresh = runCatching { DshTranscript.resumeMeta(found.file) }
            .getOrDefault(DshTranscript.ResumeMeta.EMPTY)
        metaCache[found.file.toString()] = CachedMeta(found.mtime, fresh)
        return fresh
    }

    /** VISIBLE FOR TESTS ONLY. The store root is `$DSH_HOME/sessions` in production and cannot be moved from
     *  inside the JVM, so the resume hooks (the only readers that take no explicit root) get this seam. */
    @Volatile private var storeRootForTest: Path? = null

    internal fun bindStoreRootForTest(root: Path) {
        storeRootForTest = root
    }

    private fun storeRoot(): Path = storeRootForTest ?: DshPaths.sessionsRoot()

    /** VISIBLE FOR TESTS ONLY: what dsh last reported as this session's model (the live IT asserts the
     *  read-back rather than the request — see [DshBackendLiveIT]). */
    internal fun liveModelForTest(): String? = options.currentModel

    /** VISIBLE FOR TESTS ONLY: stands in for the session a live handshake would have opened. */
    internal fun bindSessionForTest(id: String, options: DshConfigOptions = DshConfigOptions.EMPTY) {
        sessionId = id
        this.options = options
    }

    // ---- helpers ----

    /** A [AgentEvent.RuntimeMeta] only when at least one field is really present — an all-null event would
     *  travel to the Conversation to say nothing, and blanks are dropped so a `""` can never displace a
     *  model we already know. */
    private fun runtimeMeta(
        model: String? = null,
        effort: String? = null,
        contextWindow: Long? = null,
    ): AgentEvent.RuntimeMeta? {
        val m = model?.takeIf { it.isNotBlank() }
        val e = effort?.takeIf { it.isNotBlank() }
        val w = contextWindow?.takeIf { it > 0 }
        return if (m == null && e == null && w == null) null else AgentEvent.RuntimeMeta(m, e, w)
    }

    private fun syntheticError(message: String): String =
        buildJsonObject { put("type", SYNTHETIC_ERROR); put("message", message) }.toString()

    /** Like [syntheticError] but WITHOUT a TurnResult: something went wrong while the turn's own state is
     *  untouched (a refused config write leaves the session perfectly alive). */
    private fun syntheticNotice(message: String): String =
        buildJsonObject { put("type", SYNTHETIC_NOTICE); put("message", message) }.toString()

    /** The refusal of the prompt reserved as [id] (see [sendPrompt]) — settled on the pump like its error
     *  response would be. */
    private fun syntheticRefusal(id: Long, message: String): String =
        buildJsonObject { put("type", SYNTHETIC_REFUSAL); put("id", id); put("message", message) }.toString()

    // ---- JSON-RPC 2.0 plumbing ----

    private suspend fun rpcRequest(method: String, params: JsonObject?): Long {
        val id = idSeq.getAndIncrement()
        rpcSend(id, method, params)
        return id
    }

    /** A request whose id was pre-allocated (see [reservePrompt] / [sendConfig] — both register first). */
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
        write(
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id)
                putJsonObject("error") { put("code", code); put("message", message) }
            },
        )

    private suspend fun write(obj: JsonObject) { io?.writeLine(obj.toString()) }

    private companion object {
        /** ACP v1. dsh answers `protocolVersion: 1` (probe 0.1.2-rc.1). */
        const val ACP_PROTOCOL_VERSION = 1

        /** ACP creates with cwd metadata only; no preset is selected or written by Pairlet (#376). */
        const val UNGROUPED_NOTICE = "This session appears under Ungrouped in DSH Web. " +
            "To use a preset group, create the session in DSH Web, then continue it from Pairlet history."

        /**
         * The startup stages a failure can land in (issue #388). Each opening is a DIFFERENT thing for the
         * user to do — reinstall dsh, pick another session, or just try again — which is precisely what a
         * bare "Internal error" (and the "turn failed" that used to follow it) never said.
         */
        const val STAGE_HANDSHAKE = "the DeepSeek Harness never completed its handshake"
        const val STAGE_NEW = "could not start a DeepSeek Harness session"
        const val STAGE_RESUME = "could not resume this DeepSeek Harness session — " +
            "it was not reopened, and nothing was sent to a different one"

        /** `error.data` is summarized, not quoted whole: it can carry a full stack, and this lands in a chat. */
        const val MAX_ERROR_DETAIL_CHARS = 300

        /** Namespaced so they can never collide with a real dsh frame. */
        const val SYNTHETIC_ERROR = "cc-pocket/dsh-error"
        const val SYNTHETIC_NOTICE = "cc-pocket/dsh-notice"
        const val SYNTHETIC_REFUSAL = "cc-pocket/dsh-prompt-refused"

        /** Handshake watchdog: generous, because a cold Node start plus the profile compose can take a few
         *  seconds on a slow machine, and a false accusation of "your dsh is too old" is worse than waiting. */
        const val HANDSHAKE_POLLS = 60
        const val HANDSHAKE_POLL_MS = 500L
    }
}
