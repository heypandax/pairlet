package dev.ccpocket.daemon.codex

import dev.ccpocket.protocol.TokenUsage
import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
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
 * Drives OpenAI Codex via `codex app-server` — newline-delimited JSON-RPC over stdio (NO `jsonrpc` field
 * on the wire). A stateful, per-conversation handshake machine: on [attach] it sends `initialize`; on the
 * response it sends `initialized` + `thread/start` (or `thread/resume`); the first user prompt is buffered
 * until the thread id lands. Approvals are server→client requests answered by request `id`; mode/model/effort
 * are per-turn params (so a switch needs no relaunch). Provider schema verified against openai/codex app-server.
 */
class CodexBackend(private val codexBin: String?) : AgentBackend {
    private val log = logger("CodexBackend")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val idSeq = AtomicLong(1)
    private val bootstrap = Mutex() // guards threadId + pendingPrompt so the first turn is never lost to a race

    @Volatile private var io: AgentIo? = null
    @Volatile private var resolvedExe: Path? = null // codex binary, resolved lazily on first launch
    @Volatile private var workdir: String = ""
    @Volatile private var resumeId: String? = null
    @Volatile private var mode: PermissionMode = PermissionMode.DEFAULT
    @Volatile private var model: String? = null
    @Volatile private var effort: String? = null

    @Volatile private var threadId: String? = null
    @Volatile private var currentTurnId: String? = null
    private var pendingPrompt: Prompt? = null // buffered first turn (guarded by [bootstrap])

    // JSON-RPC id correlation: which of our outstanding requests this id belongs to
    @Volatile private var initializeId: Long = -1
    @Volatile private var threadOpenId: Long = -1

    // a turn's running state, reset on turn/completed
    @Volatile private var lastAgentText: String? = null
    @Volatile private var lastUsage = Usage()
    @Volatile private var usageSeen = false // no tokenUsage event yet → TurnResult must say "no usage", not zeros
    private val deltaSeen = ConcurrentHashMap.newKeySet<String>() // agentMessage itemIds that streamed deltas → don't re-emit final
    private val fileChangePaths = ConcurrentHashMap<String, String>() // fileChange itemId → first changed path (for the approval preview)
    private val fileChangeDiffs = ConcurrentHashMap<String, String>() // fileChange itemId → unified diff (for the approval diff view)
    private val pendingApprovals = ConcurrentHashMap<String, JsonElement>() // askId → original JSON-RPC id (preserve type for the response)

    private data class Prompt(val text: String, val images: List<ImageData>)
    private data class Usage(val input: Long = 0, val output: Long = 0, val cached: Long = 0)

    override val kind: AgentKind = AgentKind.CODEX

    override fun processBuilder(spec: AgentSpec): ProcessBuilder = CodexLauncher.processBuilder(exe(), spec)

    /** Resolve the codex binary lazily — only a launch needs it, so listing/replay work even without codex installed. */
    private fun exe(): Path = resolvedExe ?: CodexLauncher.resolveExecutable(codexBin).also { resolvedExe = it }

    override suspend fun attach(io: AgentIo, spec: AgentSpec) {
        this.io = io
        this.workdir = spec.workdir.toString()
        this.resumeId = spec.resumeId
        this.mode = spec.mode
        this.model = spec.model
        this.effort = spec.effort
        // reset per-process protocol state (this runs on every (re)launch)
        threadId = null; currentTurnId = null
        bootstrap.withLock { pendingPrompt = null }
        lastAgentText = null; lastUsage = Usage(); usageSeen = false
        deltaSeen.clear(); fileChangePaths.clear(); fileChangeDiffs.clear(); pendingApprovals.clear()
        // kick off the handshake — initialized + thread open happen when the response lands (see handleResponse)
        initializeId = rpcRequest("initialize", buildJsonObject {
            putJsonObject("clientInfo") { put("name", "cc-pocket"); put("version", CLIENT_VERSION) }
            putJsonObject("capabilities") { put("experimentalApi", false) }
        })
    }

    override suspend fun parse(line: String): List<AgentEvent> {
        val t = line.trim()
        if (t.isEmpty()) return emptyList()
        val root = runCatching { json.parseToJsonElement(t) }.getOrNull() as? JsonObject
            ?: return listOf(AgentEvent.Unparseable(t))
        val method = root.str("method")
        val idEl = root["id"]?.takeIf { it !is JsonNull }
        return runCatching {
            when {
                method != null && idEl != null -> handleServerRequest(method, idEl, root.obj("params"))
                method != null -> handleNotification(method, root.obj("params"))
                root.containsKey("result") -> handleResponse(idEl, root["result"])
                root.containsKey("error") -> { log.warn("codex error: ${root["error"]}"); emptyList() }
                else -> emptyList()
            }
        }.getOrElse { log.warn("codex parse failed: ${it.message}"); emptyList() }
    }

    // ---- inbound: responses to our requests ----

    private suspend fun handleResponse(idEl: JsonElement?, result: JsonElement?): List<AgentEvent> {
        return when ((idEl as? JsonPrimitive)?.longOrNull) {
            initializeId -> {
                rpcNotify("initialized", null)
                openThread()
                emptyList()
            }
            threadOpenId -> onThreadReady(result as? JsonObject)
            else -> emptyList()
        }
    }

    private suspend fun openThread() {
        val rid = resumeId
        threadOpenId = if (rid != null) {
            rpcRequest("thread/resume", buildJsonObject { put("threadId", rid); codexModel()?.let { put("model", it) } })
        } else {
            rpcRequest("thread/start", buildJsonObject {
                put("cwd", workdir)
                put("approvalPolicy", approvalPolicy())
                put("sandbox", sandbox().flat) // thread/start takes the flat SandboxMode string
                codexModel()?.let { put("model", it) }
            })
        }
    }

    private suspend fun onThreadReady(result: JsonObject?): List<AgentEvent> {
        val thread = result?.obj("thread") ?: return emptyList()
        val tid = thread.str("id") ?: return emptyList()
        val flush = bootstrap.withLock {
            threadId = tid
            pendingPrompt.also { pendingPrompt = null }
        }
        flush?.let { writeTurnStart(it.text, it.images) }
        return listOf(AgentEvent.SessionInit(sessionId = tid, cwd = workdir, model = result.str("model")))
    }

    // ---- inbound: server notifications ----

    private suspend fun handleNotification(method: String, params: JsonObject?): List<AgentEvent> {
        params ?: return emptyList()
        return when (method) {
            "thread/started" -> { // backup path: usually the thread/start RESULT lands first
                if (threadId == null) onThreadReady(buildJsonObject { params.obj("thread")?.let { put("thread", it) } }) else emptyList()
            }
            "turn/started" -> { currentTurnId = params.obj("turn")?.str("id"); emptyList() }
            "item/agentMessage/delta" -> params.str("delta")?.let { d ->
                params.str("itemId")?.let { deltaSeen.add(it) }
                listOf(AgentEvent.AssistantText(d))
            } ?: emptyList()
            "item/reasoning/textDelta", "item/reasoning/summaryTextDelta" ->
                params.str("delta")?.let { listOf(AgentEvent.AssistantThinking(it)) } ?: emptyList()
            "item/started" -> onItemStarted(params.obj("item"))
            "item/completed" -> onItemCompleted(params.obj("item"))
            "thread/tokenUsage/updated" -> {
                val tu = params.obj("tokenUsage")
                // `last` is the finished call's usage ≈ what's occupying the context window; `total` is the
                // SESSION-CUMULATIVE sum, which only grows and reads as absurd occupancy after a few turns.
                // Prefer last, fall back to total only for server builds that don't send it (app-server is
                // experimental and drifts — see the probe regression notes).
                captureUsage(tu?.obj("last") ?: tu?.obj("total"))
                emptyList()
            }
            "turn/completed" -> onTurnCompleted(params.obj("turn"))
            "error" -> {
                val msg = params.obj("error")?.str("message") ?: "codex error"
                // turn/completed (status=failed) still follows and clears `executing`; surface the text now
                listOf(AgentEvent.AssistantText("⚠️ $msg"))
            }
            else -> emptyList() // unknown notification type — tolerate (codex adds these over time)
        }
    }

    private fun onItemStarted(item: JsonObject?): List<AgentEvent> {
        item ?: return emptyList()
        val id = item.str("id")
        return when (item.str("type")) {
            "commandExecution" -> listOf(
                AgentEvent.AssistantToolUse(id, "Bash", buildJsonObject {
                    item.str("command")?.let { put("command", it) }
                    item.str("cwd")?.let { put("cwd", it) }
                }),
            )
            "fileChange" -> {
                val changes = item.arr("changes")
                val path = changes?.firstNotNullOfOrNull { (it as? JsonObject)?.str("path") }
                val diff = changes?.mapNotNull { (it as? JsonObject)?.str("diff") }?.joinToString("\n")?.takeIf { it.isNotBlank() }
                if (id != null) {
                    path?.let { fileChangePaths[id] = it }
                    diff?.let { fileChangeDiffs[id] = it.take(MAX_DIFF_CHARS) } // cap so the approval frame stays under the relay limit
                }
                listOf(AgentEvent.AssistantToolUse(id, "Edit", buildJsonObject { path?.let { put("file_path", it) } }))
            }
            "mcpToolCall" -> listOf(AgentEvent.AssistantToolUse(id, item.str("toolName") ?: "tool", null))
            "webSearch" -> listOf(AgentEvent.AssistantToolUse(id, "WebSearch", null))
            else -> emptyList() // agentMessage/reasoning flow through deltas; other item kinds are not surfaced
        }
    }

    private fun onItemCompleted(item: JsonObject?): List<AgentEvent> {
        item ?: return emptyList()
        val id = item.str("id")
        return when (item.str("type")) {
            "agentMessage" -> {
                val text = item.str("text")
                if (text != null) lastAgentText = text
                // deltas already streamed this message → don't double-emit; only emit if no delta arrived
                if (text != null && (id == null || id !in deltaSeen)) listOf(AgentEvent.AssistantText(text)) else emptyList()
            }
            "commandExecution" -> listOf(
                AgentEvent.ToolResult(id, item.str("aggregatedOutput"), isError = item.str("status") == "failed"),
            )
            "fileChange" -> {
                val status = item.str("status")
                listOf(AgentEvent.ToolResult(id, "patch ${status ?: "applied"}", isError = status == "failed" || status == "declined"))
            }
            else -> emptyList()
        }
    }

    private fun onTurnCompleted(turn: JsonObject?): List<AgentEvent> {
        val status = turn?.str("status")
        val u = lastUsage
        val ev = AgentEvent.TurnResult(
            finalText = lastAgentText,
            // a turn that never saw a tokenUsage event reports "unknown" (null), not an empty window
            usage = if (usageSeen) TokenUsage(u.input, u.output, null, u.cached) else null,
            isError = status == "failed",
        )
        lastAgentText = null
        currentTurnId = null
        deltaSeen.clear()
        fileChangePaths.clear(); fileChangeDiffs.clear() // approvals for this turn are resolved by now — don't accumulate
        return listOf(ev)
    }

    private fun captureUsage(usage: JsonObject?) {
        usage ?: return
        lastUsage = Usage(
            input = usage.long("inputTokens") ?: lastUsage.input,
            output = usage.long("outputTokens") ?: lastUsage.output,
            cached = usage.long("cachedInputTokens") ?: lastUsage.cached,
        )
        usageSeen = true
    }

    // ---- inbound: server→client requests (approvals) ----

    private suspend fun handleServerRequest(method: String, idEl: JsonElement, params: JsonObject?): List<AgentEvent> {
        val askId = (idEl as? JsonPrimitive)?.contentOrNull ?: idEl.toString()
        return when (method) {
            "item/commandExecution/requestApproval" -> {
                pendingApprovals[askId] = idEl
                val input = buildJsonObject {
                    params?.str("command")?.let { put("command", it) }
                    params?.str("cwd")?.let { put("cwd", it) }
                }
                listOf(AgentEvent.ControlRequest(askId, "Bash", input)) // "Bash" → ToolMetadata danger regex + "Run command" title
            }
            "item/fileChange/requestApproval" -> {
                pendingApprovals[askId] = idEl
                val itemId = params?.str("itemId")
                val path = itemId?.let { fileChangePaths[it] }
                val diff = itemId?.let { fileChangeDiffs[it] }
                val input = buildJsonObject {
                    (path ?: params?.str("reason"))?.let { put("file_path", it) }
                }
                listOf(AgentEvent.ControlRequest(askId, "Edit", input, diff = diff)) // diff is typed, not smuggled in input

            }
            else -> {
                // permissions/tool-input/elicitation + deprecated v1 approvals: not supported under our config.
                // Reply with an error so codex doesn't block waiting on us.
                log.warn("codex unsupported server request: $method")
                rpcRespondError(idEl, -32601, "not supported by cc-pocket")
                emptyList()
            }
        }
    }

    // ---- outbound (called by Conversation) ----

    override suspend fun sendPrompt(text: String, images: List<ImageData>) {
        val ready = bootstrap.withLock {
            if (threadId == null) { pendingPrompt = Prompt(text, images); false } else true
        }
        if (ready) writeTurnStart(text, images)
    }

    private suspend fun writeTurnStart(text: String, images: List<ImageData>) {
        val tid = threadId ?: return
        rpcRequest("turn/start", buildJsonObject {
            put("threadId", tid)
            putJsonArray("input") {
                addJsonObject { put("type", "text"); put("text", text) }
                // images: Codex takes image{url}/localImage{path}, not base64 inline — deferred (Tier C)
            }
            put("cwd", workdir)
            put("approvalPolicy", approvalPolicy())
            putJsonObject("sandboxPolicy") { put("type", sandbox().tag) } // turn/start takes the object form
            codexModel()?.let { put("model", it) }
            effort?.let { put("effort", codexEffort(it)) }
        })
    }

    override suspend fun interrupt() {
        val tid = threadId ?: return
        val turn = currentTurnId ?: return
        rpcRequest("turn/interrupt", buildJsonObject { put("threadId", tid); put("turnId", turn) })
    }

    override suspend fun respondPermission(
        askId: String,
        allow: Boolean,
        remember: Boolean,
        originalInput: JsonObject?,
        updatedInput: String?,
        denyMessage: String?,
    ) {
        val idEl = pendingApprovals.remove(askId) ?: return
        val decision = if (allow) (if (remember) "acceptForSession" else "accept") else "decline"
        rpcRespondResult(idEl, buildJsonObject { put("decision", decision) })
    }

    // codex applies mode/model/effort per turn → no relaunch; just stash for the next turn/start
    override fun applySettings(mode: PermissionMode?, model: String?, effort: String?): Boolean {
        mode?.let { this.mode = it }
        model?.let { this.model = it }
        effort?.let { this.effort = it }
        return false
    }

    override suspend fun onProcessEnded(sessionId: String?) {} // codex rollouts are self-managed; nothing to unhide

    // ---- disk: ~/.codex/sessions rollout scanning + replay (filtered by recorded cwd) ----

    override fun transcriptDir(workdir: String): Path = CodexPaths.sessionsRoot()
    override fun listSessions(workdir: String): List<SessionSummary> = CodexTranscriptScanner.scan(workdir)
    override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> =
        CodexPaths.findSession(sessionId)?.let { CodexTranscriptReplay.read(it) } ?: emptyList()

    // Codex usage is live-only (thread/tokenUsage/updated) — the rollout carries no per-turn usage
    // record to read back, so there's nothing to seed the statusline with on resume.
    override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null

    // issue #96: read the configured default (top-level `model` in $CODEX_HOME/config.toml) so a brand-new
    // Codex session's header shows the real model before the first turn instead of a blank segment.
    override fun defaultModel(workdir: String): String? = CodexDefaultModel.resolve()

    // ---- mode mapping (Claude's single mode → Codex's approvalPolicy × sandbox axes) ----

    // The 4 PermissionMode values are the phone's Codex presets (Cautious/Balanced/Autonomous/Full auto).
    private fun approvalPolicy(): String = when (mode) {
        PermissionMode.PLAN -> "untrusted"             // Cautious: ask every step (paired with read-only)
        PermissionMode.DEFAULT -> "on-request"         // Balanced: ask when needed (the recommended default)
        PermissionMode.ACCEPT_EDITS -> "never"         // Autonomous: never ask, writes in the workspace
        PermissionMode.BYPASS_PERMISSIONS -> "never"   // Full auto: never ask + full access
    }

    /** The sandbox for the current mode in both spellings codex needs: flat SandboxMode string (thread/start)
     *  + the SandboxPolicy object tag (turn/start). One source so the two can't desync. */
    private data class Sandbox(val flat: String, val tag: String)

    private fun sandbox(): Sandbox = when (mode) {
        PermissionMode.PLAN -> Sandbox("read-only", "readOnly")
        PermissionMode.BYPASS_PERMISSIONS -> Sandbox("danger-full-access", "dangerFullAccess")
        else -> Sandbox("workspace-write", "workspaceWrite")
    }

    /** Drop Claude model aliases (opus/sonnet/haiku) — they're meaningless to codex and would error. */
    private fun codexModel(): String? = model?.takeIf { it.lowercase() !in CLAUDE_ALIASES }

    /** cc-pocket's effort ladder includes "max"; codex's top tier is "xhigh" — map it so the turn isn't rejected. */
    private fun codexEffort(e: String): String = if (e == "max") "xhigh" else e

    // ---- JSON-RPC plumbing ----

    private suspend fun rpcRequest(method: String, params: JsonObject?): Long {
        val id = idSeq.getAndIncrement()
        write(buildJsonObject {
            put("id", id)
            put("method", method)
            params?.let { put("params", it) }
        })
        return id
    }

    private suspend fun rpcNotify(method: String, params: JsonObject?) =
        write(buildJsonObject { put("method", method); params?.let { put("params", it) } })

    private suspend fun rpcRespondResult(id: JsonElement, result: JsonObject) =
        write(buildJsonObject { put("id", id); put("result", result) })

    private suspend fun rpcRespondError(id: JsonElement, code: Int, message: String) =
        write(buildJsonObject { put("id", id); putJsonObject("error") { put("code", code); put("message", message) } })

    private suspend fun write(obj: JsonObject) {
        io?.writeLine(obj.toString())
    }

    private companion object {
        // the daemon's real build version (single runtime source — no per-release manual bump here)
        val CLIENT_VERSION: String get() = dev.ccpocket.daemon.util.DaemonVersion.CURRENT
        const val MAX_DIFF_CHARS = 6000 // approval diff cap — keeps the PermissionAsk frame well under the relay's 256 KiB limit
        val CLAUDE_ALIASES = setOf("opus", "sonnet", "haiku")
    }
}
