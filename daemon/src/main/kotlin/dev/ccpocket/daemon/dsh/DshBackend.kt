package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.acp.AcpClient
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

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
 *     is already in flight for this session"`, so the FIFO lives client-side ([AcpClient], shared with Kimi).
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
    /** How long one `session/set_config_option` may go unanswered — see [watchConfig]. Injectable for tests. */
    private val configTimeoutMs: Long = CONFIG_TIMEOUT_MS,
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
) : AgentBackend {
    private val log = logger("DshBackend")

    /** Owns the async config pushes; cancelled when the process ends. */
    private var scope: CoroutineScope? = null

    @Volatile private var resolvedExe: Path? = null
    @Volatile private var mode: PermissionMode = PermissionMode.DEFAULT

    /** The launch knobs the client chose. Both are live-switchable through `session/set_config_option`
     *  and are re-applied on every [applySettings]. */
    @Volatile private var launchModel: String? = null
    @Volatile private var launchEffort: String? = null

    /**
     * The ACP protocol half shared with Kimi: handshake, session open, the one-in-flight prompt FIFO (fact 1)
     * with the consumption receipt synthesized on settle (fact 2), approvals, cancel, and the startup-failure
     * terminal state of issue #388 with its handshake watchdog. A resume is `session/resume`, which replays
     * nothing (fact 5).
     *
     * Its prompt gate stays closed from launch until the session is open AND its launch-time model/effort
     * have landed: a prompt that slipped through the window between `session/new` answering and the config
     * write settling would run the opening turn on the model the user did NOT pick — which is what the whole
     * write-then-flush chain exists to prevent, and which a "buffer only while sessionId is null" gate misses
     * by exactly the round trip that matters.
     */
    private val client = AcpClient(
        AcpClient.Config(
            tag = "dsh",
            productName = "DeepSeek Harness",
            resume = AcpClient.Resume.RESUME,
            stageHandshake = STAGE_HANDSHAKE,
            stageNew = STAGE_NEW,
            stageResume = STAGE_RESUME,
            handshakeTimeoutMs = handshakeTimeoutMs,
            // A handshake that never answers is exactly what a pre-0.1.2 dsh does: `--profile acp` composes a
            // profile with no app in it, so nothing ever claims stdio. The message names the version to install.
            handshakeHint = DshLauncher::outdatedHint,
            describeError = ::describeError,
        ),
        log,
        object : AcpClient.Host {
            override suspend fun onSessionOpened(sessionId: String, result: JsonObject?) = sessionOpened(sessionId, result)
            override fun onUpdate(update: JsonObject) = handleUpdate(update)
            override fun permissionCard(params: JsonObject?) = approvalCard(params)
            override suspend fun onResponse(id: Long, result: JsonObject?) =
                configIds.remove(id)?.let { onConfigApplied(it, result) }
            override suspend fun onErrorResponse(id: Long?, why: String) =
                configIds.remove(id ?: -1)?.let { onConfigFailed(it, why) }
            override suspend fun onSyntheticFrame(type: String?, root: JsonObject) =
                if (type == CONFIG_TIMEOUT_TYPE) onConfigTimedOut(root.long("id")) else null
        },
    )

    /** The session's advertised configuration options, as last read back from dsh. The model picker
     *  ([DshModelService]) reads the same catalogue through [DshCatalog], and every model/effort write
     *  joins its opaque wire value out of it. */
    @Volatile private var options: DshConfigOptions = DshConfigOptions.EMPTY

    /** outstanding set_config_option id → what it was trying to do, so its answer can be reported and the
     *  chain continued. */
    private val configIds = ConcurrentHashMap<Long, ConfigWrite>()

    /** toolCallId → what dsh said it was about, so a later permission request (which carries only the id)
     *  can render a card a human can decide on. */
    private val toolCalls = ConcurrentHashMap<String, ToolInfo>()

    private data class ToolInfo(val title: String?, val input: JsonObject?)

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
        this.mode = spec.mode
        this.launchModel = spec.model
        this.launchEffort = spec.effort
        spec.agentPreset?.takeIf { it.isNotBlank() }?.let {
            // The ACP surface has no agent-preset axis; announcing one we cannot select would be a lie in
            // the session header. Creation explains the Web grouping limitation separately.
            log.info("dsh agent preset '$it' ignored — the ACP profile exposes no preset selection")
        }
        // reset per-process state (runs on EVERY (re)launch)
        options = DshConfigOptions.EMPTY
        catalog.unpublish(this)
        configIds.clear(); toolCalls.clear()
        scope?.let { runCatching { it.cancel() } }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        client.attach(io, spec.workdir.toString(), spec.resumeId)
    }

    // ---- inbound ----

    // dsh keeps its logs on stderr, so an unparseable stdout line is genuinely unexpected.
    override suspend fun parse(line: String): List<AgentEvent> = client.parse(line)

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
     * `session/new` answers `{sessionId, configOptions}`; `session/resume` answers `{configOptions}` for
     * the id we sent. The catalogue read-back is what seeds the header — the model chip used to stay
     * blank until the session happened to answer once.
     */
    private suspend fun sessionOpened(sid: String, result: JsonObject?): List<AgentEvent> {
        // Only a successful fresh creation needs this notice. Keeping the pre-assignment state also
        // avoids repeating it if the same session/new response is delivered twice.
        val showGroupingNotice = client.resumeId == null && client.sessionId == null
        options = DshConfigOptions.parse(result?.arr("configOptions"))
        catalog.publish(this, options)
        client.bindSession(sid)
        val events = listOfNotNull(
            AgentEvent.SessionInit(sessionId = sid, cwd = client.workdir, model = options.currentModel,
                notice = if (showGroupingNotice) UNGROUPED_NOTICE else null),
            runtimeMeta(model = options.currentModel, effort = options.currentEffort),
        )
        // Model/effort BEFORE the prompt gate opens: running the opening turn on the previous model and
        // correcting it afterwards would bill the user for a model they did not pick. Each write is a
        // request, so the gate opens on its RESPONSE (see [onConfigApplied]) rather than on hope.
        if (startConfigChain(launchModel, launchEffort, announce = false)) return events
        return events + client.openPromptGate()
    }

    // ---- inbound: session/update (no `user_message_chunk` live — fact 2; usage rides `usage_update`) ----

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

    // ---- inbound: approvals ----

    /** The request carries only toolCall.toolCallId (fact 4) — the subject comes from the tool_call update we
     *  recorded earlier in this turn, and the name is dsh's raw title (never mapped). */
    private fun approvalCard(params: JsonObject?): Pair<String, JsonObject> {
        val toolCallId = params?.obj("toolCall")?.str("toolCallId")
        val info = toolCallId?.let { toolCalls[it] }
        val name = info?.title ?: params?.obj("toolCall")?.str("title") ?: "tool"
        val input = info?.input ?: buildJsonObject { put("description", name) }
        return name to input
    }

    // ---- outbound: prompts ----

    override suspend fun sendPrompt(text: String, images: List<ImageData>) = client.sendPrompt(text, images)

    override suspend fun interrupt() = client.interrupt()

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
        // `remember` / `denyMessage` are deliberately unused: dsh offers allow-once / reject-once only
        // (probe 0.1.2-rc.1), so a remembered scope can never form and there is no place for a sentence.
        // The answer is dsh's own option id (`allow-once`), never ours.
        if (!client.respondPermission(askId, allow, remember = false)) {
            log.info("dsh respondPermission($askId) had nothing pending — already resolved or withdrawn")
        }
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
        val sid = client.sessionId ?: return false
        val writes = ArrayList<ConfigWrite>(2)
        model?.takeIf { it.isNotBlank() && it != options.currentModel }?.let { wanted ->
            val value = options.modelValue(wanted)
            if (value == null) {
                // The id is not in dsh's catalogue: say so rather than sending a value it will reject.
                log.warn("dsh has no model option for $wanted — leaving the session's own selection")
                if (announce) client.injectNotice("⚠️ DeepSeek Harness has no model $wanted")
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
        val id = client.rpc.nextId()
        configIds[id] = write
        scope?.launch { watchConfig(id) }
        client.rpc.send(id, "session/set_config_option", buildJsonObject {
            put("sessionId", sid)
            put("configId", write.configId) // NOT optionId (fact 3)
            put("value", write.value)
        })
    }

    /**
     * A config write that never answers would hold the prompt gate shut forever — the launch chain opens it only
     * on the LAST write's response — leaving the opening prompt parked with no error and no terminal state. The
     * same bounded wait as the handshake watchdog, on the same per-process scope (a relaunch cancels it). The
     * verdict is delivered through the pump ([CONFIG_TIMEOUT_TYPE] → [onConfigTimedOut]) so it is ordered with
     * the real answer: whichever reaches the pump first claims the write.
     */
    private suspend fun watchConfig(id: Long) {
        delay(configTimeoutMs)
        if (!configIds.containsKey(id)) return // answered in time
        client.io?.inject?.invoke(
            buildJsonObject { put("type", CONFIG_TIMEOUT_TYPE); put("id", id) }.toString(),
        )
    }

    /**
     * On the pump: [id] got no answer in time. A USER-driven switch is reported like a refused one and the chain
     * moves on (the session is already running on a model the user saw announced). A LAUNCH-time write fails the
     * session open instead: carrying on would run the opening turn on dsh's default model while the user believes
     * their pick is in effect — so the waiting prompts are settled with an error naming the stage, and later ones
     * are refused with it until a relaunch.
     */
    private suspend fun onConfigTimedOut(id: Long?): List<AgentEvent> {
        val write = id?.let { configIds.remove(it) } ?: return emptyList() // already answered, or a previous process
        val what = if (write.configId == DshConfigOptions.MODEL) "the model" else "the reasoning effort"
        val why = "the DeepSeek Harness did not answer the request to set $what (${write.value}) " +
            "within ${configTimeoutMs / 1000} s"
        log.warn("dsh set_config_option(${write.configId}=${write.value}) unanswered after ${configTimeoutMs}ms")
        if (write.announce) return onConfigFailed(write, why)
        pendingConfig.clear()
        return client.failHostStartup(STAGE_CONFIG, why)
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
            client.injectNotice("⚠️ could not switch $what: $why")
        }
        return continueConfigChain(write)
    }

    /** Send the next write of the chain, or — when this was the last one — open the prompt gate (returning the
     *  error turns of any prompt it refused). A FAILED write still advances: queued prompts must never be held
     *  hostage by a preference that dsh refused. */
    private suspend fun continueConfigChain(write: ConfigWrite): List<AgentEvent> {
        val next = pendingConfig.pollFirst()
        val sid = client.sessionId
        if (next != null && sid != null) {
            sendConfig(sid, next)
            return emptyList()
        }
        // End of the chain — or the session went away under it, which must not strand the queue either.
        return if (write.flushAfter || next != null) client.openPromptGate() else emptyList()
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
        if (client.sessionId == null) return relaunch // stored above; the launch path applies it at session open
        scope?.launch { startConfigChain(model, effort, announce = true) }
        return relaunch
    }

    // List the session under its project in DSH's own sidebar (issue #388) — see [DshWorkspaceRegistry].
    override suspend fun onSessionStarted(sessionId: String, workdir: String) {
        DshWorkspaceRegistry.adopt(sessionId, workdir)
    }

    override suspend fun onProcessEnded(sessionId: String?) {
        // again at the end: a DSH Web that was running meanwhile may have rewritten the registry from its
        // own memory and dropped the entry
        val wd = client.workdir
        if (sessionId != null && wd.isNotBlank()) DshWorkspaceRegistry.adopt(sessionId, wd)
        catalog.unpublish(this)
        client.processEnded()
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
        client.bindSession(id)
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

    private companion object {
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

        /** Handshake watchdog: generous, because a cold Node start plus the profile compose can take a few
         *  seconds on a slow machine, and a false accusation of "your dsh is too old" is worse than waiting. */
        const val HANDSHAKE_TIMEOUT_MS = 30_000L

        /** One config write's bound — the handshake watchdog's, for the same reason: a slow machine must not be
         *  accused, and a `set_config_option` is a cheaper round trip than the handshake it follows. */
        const val CONFIG_TIMEOUT_MS = HANDSHAKE_TIMEOUT_MS

        /** The launch-time model/effort never landed: the session was not started on the user's choice. */
        const val STAGE_CONFIG = "could not apply the chosen model settings to the DeepSeek Harness session — " +
            "nothing was sent on a different model"

        /** The pump-bound verdict of [watchConfig] (namespaced like [dev.ccpocket.daemon.acp.AcpSynthetic]'s). */
        const val CONFIG_TIMEOUT_TYPE = "cc-pocket/dsh-config-timeout"
    }
}
