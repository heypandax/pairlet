package dev.ccpocket.daemon.kimi

import dev.ccpocket.daemon.acp.AcpClient
import dev.ccpocket.daemon.acp.AcpConfigChain
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives the Kimi Code CLI via `kimi acp` — the Agent Client Protocol v1 over newline-delimited JSON-RPC 2.0
 * on stdio (issue #206). The protocol half shared with DSH — `initialize`, the session open (`session/new`,
 * or `session/load` for a resume), the prompt FIFO, approvals answered by the chosen option id (the exact
 * provider-neutral shape [dev.ccpocket.daemon.agent.PermissionBridge] expects) — lives in [AcpClient]. What
 * is kimi's own stays here: `session/update` translation ([KimiAcpParser] plus the streamed tool input
 * below), the permission card, and the background-task watchers.
 *
 * SELECTION (probe 2026-08-06): the design assumed a `kimi --wire` mode, but 0.33.0 has no such flag; `kimi
 * acp` is its complete stdio protocol (initialize handshake confirmed: loadSession/resume/fork/permissions).
 * ACP gives the full approval chain (design plan A). Live turn/approval behavior is UNVERIFIED — the probe
 * was blocked by device-code login (V-auth); the mapping follows the ACP v1 spec + the confirmed handshake.
 *
 * IMAGES (issue #377): a prompt carries images only when THIS process's `initialize` answer advertises
 * `agentCapabilities.promptCapabilities.image` (current kimi-cli does, turning each ACP image block into a
 * model image URL). Otherwise the prompt is refused as an error turn rather than sent as its text alone.
 *
 * MODEL AND MODE (probe 2.1.1, 2026-10-04): `session/new` ignores `modelId` / `modeId`, so the session opens on
 * kimi's own defaults. The user's pick is applied through `session/set_config_option` (`configId` `model` — a
 * `config.toml` alias, the same ids [KimiModelService] lists — and `mode`) on the chain shared with dsh
 * ([AcpConfigChain]), after the session opens and before the opening prompt. Both `session/new` and
 * `session/load` answer with `configOptions`; a loaded session keeps its model but NOT its mode, so the launch
 * writes run on both paths. Only DEFAULT → `default` and PLAN → `plan` are written: BYPASS_PERMISSIONS stays
 * on kimi's `default` with the daemon's permission bridge approving every ask (never kimi's own `yolo`/`auto`,
 * which would move the approval decision into kimi). kimi's `plan` is NOT read-only — a Bash call still asks
 * and runs once approved. A mode switch on an open session is written at once (kimi switches without a
 * restart); leaving Plan for Full access writes `default` back. A model switch relaunches.
 */
class KimiBackend(
    private val kimiBin: String?,
    private val modelService: KimiModelService = KimiModelService(),
    private val taskPollMs: Long = TASK_POLL_MS,
    private val taskFile: (sessionId: String, taskId: String) -> Path? = KimiPaths::taskFile,
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
    /** How long one launch `session/set_config_option` may go unanswered. Injectable for tests. */
    private val configTimeoutMs: Long = CONFIG_TIMEOUT_MS,
) : AgentBackend {
    private val log = logger("KimiBackend")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var resolvedExe: Path? = null

    /** The user's picks — what the next launch writes. */
    @Volatile private var mode: PermissionMode = PermissionMode.DEFAULT
    @Volatile private var model: String? = null

    /** What kimi last REPORTED the session is on (`configOptions` of the open / of a write's answer); null =
     *  not reported. The header is told these, never the request. */
    @Volatile private var currentModel: String? = null
    @Volatile private var currentMode: String? = null

    /** This process's session has been announced ([AgentEvent.SessionInit]) — once, after the launch writes. */
    @Volatile private var announced = false

    /** The mode the launch writes were computed for; a switch landing while they are in flight is caught up
     *  once they settle (see the config host's onChainSettled). */
    @Volatile private var launchMode: PermissionMode = PermissionMode.DEFAULT

    /** Owns the mid-session mode writes fired from [applySettings]; replaced on attach, cancelled at process end. */
    @Volatile private var scope: CoroutineScope? = null

    /**
     * The ACP protocol half shared with DSH: handshake, session open, the one-in-flight prompt FIFO
     * (ACP rejects a second session/prompt while a turn runs — `-32600 turn.agent_busy`, probe 0.34.0), the
     * consumption receipt synthesized on every settle (kimi's live stream carries NO user_message_chunk),
     * approvals, cancel, and the startup-failure terminal state with its handshake watchdog (audit
     * 2026-10-04 H1). A resume is `session/load`, which REPLAYS history — the client drops those updates.
     */
    private val client = AcpClient(
        AcpClient.Config(
            tag = "kimi",
            productName = "Kimi Code",
            resume = AcpClient.Resume.LOAD,
            stageHandshake = STAGE_HANDSHAKE,
            stageNew = STAGE_NEW,
            stageResume = STAGE_RESUME,
            handshakeTimeoutMs = handshakeTimeoutMs,
            // a legacy Python `kimi` sharing the name, or a CLI that does not speak ACP v1
            handshakeHint = {
                "no answer to `initialize` within ${handshakeTimeoutMs / 1000}s — check that `kimi` is the Kimi Code " +
                    "CLI (`kimi acp`), not the legacy Python kimi-cli"
            },
            describeError = ::describeError,
        ),
        log,
        object : AcpClient.Host {
            override suspend fun onSessionOpened(sessionId: String, result: JsonObject?) = sessionOpened(sessionId, result)
            override fun onUpdate(update: JsonObject) = handleUpdate(update)
            override fun permissionCard(params: JsonObject?) = approvalCard(params)
            override suspend fun onResponse(id: Long, result: JsonObject?): List<AgentEvent>? =
                config.onResponse(id, result)
            override suspend fun onErrorResponse(id: Long?, why: String): List<AgentEvent>? =
                config.onErrorResponse(id, why)
            override suspend fun onSyntheticFrame(type: String?, root: JsonObject): List<AgentEvent>? =
                config.onSyntheticFrame(type, root) ?: taskSettled(type, root)
        },
    )

    /**
     * The launch-time model/mode writes, on the chain shared with dsh: in order, the prompt gate opening on the
     * last one's answer. A write kimi refuses (an alias not in `config.toml`: `-32603`) is quiet and the chain
     * moves on — exactly dsh's launch behaviour; one that never answers fails the open with [STAGE_CONFIG].
     */
    private val config: AcpConfigChain = AcpConfigChain(
        client, log,
        tag = "kimi",
        agentName = "Kimi Code",
        stageConfig = STAGE_CONFIG,
        timeoutMs = configTimeoutMs,
        host = object : AcpConfigChain.Host {
            override fun describe(configId: String) = if (configId == CONFIG_MODEL) "the model" else "the permission mode"

            // kimi answers a write with the COMPLETE resulting configOptions — that read-back is the truth
            override suspend fun onApplied(write: AcpConfigChain.Write, result: JsonObject?): List<AgentEvent> {
                readBack(result?.arr("configOptions"))
                return emptyList()
            }

            override suspend fun onChainSettled(timedOut: Boolean): List<AgentEvent> {
                val events = announce(timedOut)
                // the user switched mode while the launch writes were in flight — those were computed before it
                if (!timedOut && mode != launchMode) {
                    launchMode = mode
                    switchTarget(mode).takeIf { it != (currentMode ?: MODE_DEFAULT) }?.let { writeMode(it) }
                }
                return events
            }
        },
    )

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

    override val kind: AgentKind = AgentKind.KIMI

    override fun processBuilder(spec: AgentSpec): ProcessBuilder = KimiLauncher.processBuilder(exe(), spec)

    private fun exe(): Path = resolvedExe ?: KimiLauncher.resolveExecutable(kimiBin).also { resolvedExe = it }

    override suspend fun attach(io: AgentIo, spec: AgentSpec) {
        this.mode = spec.mode
        this.model = spec.model
        // reset per-process state (runs on every (re)launch)
        currentModel = null; currentMode = null; announced = false
        config.reset()
        scope?.let { runCatching { it.cancel() } }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        toolCalls.clear()
        stopTaskWatchers() // the previous process's tasks are no longer this conversation's jobs
        // kick off the ACP handshake — session open happens when the initialize response lands
        client.attach(io, spec.workdir.toString(), spec.resumeId)
    }

    override suspend fun parse(line: String): List<AgentEvent> = client.parse(line)

    /** Our own task-completion frame (see [watchTask]); every other synthetic frame is the client's. */
    private fun taskSettled(type: String?, root: JsonObject): List<AgentEvent>? {
        if (type != SYNTHETIC_TASK_SETTLED) return null
        val taskId = root.str("taskId") ?: return emptyList()
        return listOf(AgentEvent.BackgroundTaskUpdated(taskId, root.str("status")))
    }

    /**
     * session/new returns {sessionId, configOptions}; session/load returns {configOptions} for the id we sent
     * (probe 2.1.1). The user's model/mode are written FIRST (see [launchWrites]); the session is announced and
     * the prompt gate opened only once they settled, so the opening turn runs on the user's pick and the header
     * names the model kimi reports — announcing at open would pin kimi's default into the conversation, because
     * a later read-back no longer moves a model an init already set. Opening the gate releases whatever arrived
     * before it, oldest first.
     */
    private suspend fun sessionOpened(sid: String, result: JsonObject?): List<AgentEvent> {
        currentModel = null; currentMode = null
        readBack(result?.arr("configOptions"))
        client.bindSession(sid)
        launchMode = mode
        if (config.start(launchWrites())) return emptyList()
        return announce(timedOut = false) + client.openPromptGate()
    }

    /** The writes this launch needs: the chosen model when kimi is not on it, then kimi's mode for [mode] when
     *  it differs. Nothing for a value kimi already reports — an extra round trip only delays the first turn. */
    private fun launchWrites(): List<AcpConfigChain.Write> = buildList {
        model?.takeIf { it.isNotBlank() && it != currentModel }?.let { add(AcpConfigChain.Write(CONFIG_MODEL, it, announce = false)) }
        // an unreported mode is kimi's own default
        kimiMode(mode)?.takeIf { it != (currentMode ?: MODE_DEFAULT) }?.let { add(AcpConfigChain.Write(CONFIG_MODE, it, announce = false)) }
    }

    /** One `configOptions` array → what kimi says the session is on; an option it did not mention keeps its value. */
    private fun readBack(options: JsonArray?) {
        AcpConfigChain.currentValue(options, CONFIG_MODEL)?.takeIf { it.isNotBlank() }?.let { currentModel = it }
        AcpConfigChain.currentValue(options, CONFIG_MODE)?.takeIf { it.isNotBlank() }?.let { currentMode = it }
    }

    /**
     * The session announcement, once per process: the model kimi reports (or, from a kimi that reports none, the
     * user's pick, as before). After a launch write TIMED OUT nothing is known about the model, so none is named
     * — the conversation keeps the user's pick for the next launch instead of adopting kimi's default.
     */
    private fun announce(timedOut: Boolean): List<AgentEvent> {
        val sid = client.sessionId ?: return emptyList()
        if (announced) return emptyList()
        announced = true
        val reported = if (timedOut) null else currentModel ?: model
        return listOf(AgentEvent.SessionInit(sessionId = sid, cwd = client.workdir, model = reported))
    }

    // ---- inbound: session/update ----

    // tool_call / tool_call_update are handled HERE (stateful input accumulation); every other update kind
    // stays with the stateless parser.
    private fun handleUpdate(update: JsonObject): List<AgentEvent> = when (update.str("sessionUpdate")) {
        "tool_call" -> onToolCall(update)
        "tool_call_update" -> onToolCallUpdate(update)
        else -> KimiAcpParser.translate(update)
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

    // ---- inbound: approvals ----

    /** The card of a `session/request_permission`: the tool name mapped like a tool card's. */
    private fun approvalCard(params: JsonObject?): Pair<String, JsonObject> {
        val toolCall = params?.obj("toolCall")
        val name = ToolNameMapper.map(
            toolCall?.str("kind") ?: toolCall?.str("title") ?: "tool",
        )
        // probe 0.34.0: no rawInput here either — the human sentence in the content text is the
        // only command carrier ("Requesting approval to Running: echo …"); surface it as the card body
        val input = toolCall?.obj("rawInput") ?: buildJsonObject {
            put("description", toolCallContentText(toolCall?.get("content")) ?: toolCall?.str("title") ?: "tool")
        }
        return name to input
    }

    // ---- outbound (called by Conversation) ----

    override suspend fun sendPrompt(text: String, images: List<ImageData>) = client.sendPrompt(text, images)

    override suspend fun interrupt() = client.interrupt()

    override suspend fun respondPermission(
        askId: String,
        allow: Boolean,
        remember: Boolean,
        originalInput: JsonObject?,
        updatedInput: String?,
        denyMessage: String?,
    ) {
        // remember → the `_always` option (kimi offers both); nothing matching → cancelled
        client.respondPermission(askId, allow, remember)
    }

    /**
     * A model change relaunches; the new process writes it at session open ([launchWrites]). A mode change never
     * relaunches (the return value keeps saying so): on an OPEN session it is written to kimi right away — kimi
     * switches mode without a restart — through the same config chain, as a user-driven write that reports its own
     * failure. Before the session is open it is only recorded; the launch writes carry it.
     *
     * Runs on the Conversation's command path, so the write is fired on the per-process scope, not awaited.
     */
    override fun applySettings(mode: PermissionMode?, model: String?, effort: String?): Boolean {
        var relaunch = false
        model?.let { if (it != this.model) { this.model = it; relaunch = true } }
        mode?.let { wanted ->
            val before = switchTarget(this.mode)
            this.mode = wanted
            val target = switchTarget(wanted)
            // a changed target, or one kimi is not on (a refused launch write); never a repeat of the same request
            if (announced && (target != before || target != (currentMode ?: MODE_DEFAULT))) {
                scope?.launch { writeMode(target) }
            }
        }
        return relaunch
    }

    /** A mid-session mode write. The chain's own settle re-opens nothing new: the gate is already open. */
    private suspend fun writeMode(target: String) {
        config.start(listOf(AcpConfigChain.Write(CONFIG_MODE, target, announce = true)))
    }

    // kimi self-manages its session store. A dead process cannot take a completion frame any more (inject
    // drops it), so stop watching — Conversation's stale-job reaper owns the jobs of a dead agent.
    override suspend fun onProcessEnded(sessionId: String?) {
        stopTaskWatchers()
        client.processEnded()
        config.close()
        scope?.let { runCatching { it.cancel() } }
        scope = null
    }

    // ---- background task completion (issue #391) ----

    private fun watchTask(taskId: String) {
        val sid = client.sessionId ?: return
        val owner = client.io ?: return
        taskWatchers[taskId]?.cancel()
        taskWatchers[taskId] = taskScope.launch {
            var file: Path? = null
            while (isActive && client.io === owner) {
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

    internal companion object {
        /** Namespaced like [AcpSynthetic]'s frames so it can never collide with a real kimi frame. */
        private const val SYNTHETIC_TASK_SETTLED = "cc-pocket/kimi-task-settled"
        const val TASK_POLL_MS = 2_000L

        /** The startup stages a failure can land in — each asks the user for something different. */
        const val STAGE_HANDSHAKE = "Kimi Code never completed its handshake"
        const val STAGE_NEW = "could not start a Kimi Code session"
        const val STAGE_RESUME = "could not resume this Kimi Code session — " +
            "it was not reopened, and nothing was sent to a different one"

        /** Handshake watchdog, as generous as DSH's: a cold start can take seconds on a slow machine, and a
         *  false "never completed its handshake" is worse than waiting. */
        const val HANDSHAKE_TIMEOUT_MS = 30_000L

        /** One launch config write's bound — the handshake's, as dsh's is. */
        const val CONFIG_TIMEOUT_MS = HANDSHAKE_TIMEOUT_MS

        /** The launch-time model/mode never landed: the session was not started on the user's choice. */
        const val STAGE_CONFIG = "could not apply the chosen model and mode to the Kimi Code session — " +
            "nothing was sent with different settings"

        /** kimi's `configOptions` ids (probe 2.1.1). */
        const val CONFIG_MODEL = "model"
        const val CONFIG_MODE = "mode"
        const val MODE_DEFAULT = "default"

        /** cc-pocket's mode → kimi's, for the modes that are kimi's to enforce. BYPASS_PERMISSIONS (and an
         *  ACCEPT_EDITS from an old client) leave kimi on its own default: the daemon's permission bridge answers
         *  those asks, and kimi's `yolo`/`auto` are deliberately never used. */
        internal fun kimiMode(mode: PermissionMode): String? = when (mode) {
            PermissionMode.DEFAULT -> MODE_DEFAULT
            PermissionMode.PLAN -> "plan"
            PermissionMode.ACCEPT_EDITS, PermissionMode.BYPASS_PERMISSIONS -> null
        }

        /** `error.data.details` is summarized, not quoted whole: it lands in a log line and, for a refused user
         *  switch, in the chat. */
        private const val MAX_ERROR_DETAIL_CHARS = 300

        /**
         * A JSON-RPC error object → one line. kimi answers a failure with `-32603 "Internal error"` and puts the
         * real reason in `data.details` (probe 2.1.1: `Model "nope" is not configured in config.toml.`), so the
         * reason rides along when present — otherwise every refusal logged as a bare "Internal error".
         */
        internal fun describeError(error: JsonObject?): String {
            val message = error?.str("message")?.takeIf { it.isNotBlank() } ?: "kimi error"
            val data = error?.get("data")
            val details = ((data as? JsonObject)?.str("details") ?: (data as? JsonPrimitive)?.takeIf { it.isString }?.content)
                ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() && it != message }
            return if (details == null) message else "$message: ${details.take(MAX_ERROR_DETAIL_CHARS)}"
        }

        /** The kimi mode a MID-SESSION switch moves to: the same mapping, with the daemon-enforced modes back on
         *  kimi's `default` — leaving Plan for Full access must not leave kimi in `plan`. */
        internal fun switchTarget(mode: PermissionMode): String = kimiMode(mode) ?: MODE_DEFAULT

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
