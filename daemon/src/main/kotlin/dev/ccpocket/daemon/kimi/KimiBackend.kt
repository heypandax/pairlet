package dev.ccpocket.daemon.kimi

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
 * Drives the Kimi Code CLI via `kimi acp` — the Agent Client Protocol v1 over newline-delimited JSON-RPC 2.0
 * on stdio (issue #206). A stateful per-conversation handshake machine mirroring [dev.ccpocket.daemon.codex.CodexBackend]:
 * on [attach] it sends `initialize`; on the response it opens the session (`session/new`, or `session/load`
 * for a resume); the first user prompt is buffered until the session id lands. Turns stream `session/update`
 * notifications (translated by [KimiAcpParser]); approvals are `session/request_permission` server→client
 * requests answered by the chosen option id — the exact provider-neutral shape [PermissionBridge] expects.
 *
 * SELECTION (probe 2026-08-06): the design assumed a `kimi --wire` mode, but 0.33.0 has no such flag; `kimi
 * acp` is its complete stdio protocol (initialize handshake confirmed: loadSession/resume/fork/permissions).
 * ACP gives the full approval chain (design plan A). Live turn/approval behavior is UNVERIFIED — the probe
 * was blocked by device-code login (V-auth); the mapping follows the ACP v1 spec + the confirmed handshake.
 */
class KimiBackend(
    private val kimiBin: String?,
    private val modelService: KimiModelService = KimiModelService(),
) : AgentBackend {
    private val log = logger("KimiBackend")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val idSeq = AtomicLong(1)
    private val bootstrap = Mutex() // guards sessionId + pendingPrompt so the first turn is never lost to a race

    @Volatile private var io: AgentIo? = null
    @Volatile private var resolvedExe: Path? = null
    @Volatile private var workdir: String = ""
    @Volatile private var resumeId: String? = null
    @Volatile private var mode: PermissionMode = PermissionMode.DEFAULT
    @Volatile private var model: String? = null

    @Volatile private var sessionId: String? = null
    private var pendingPrompt: Prompt? = null // buffered first turn (guarded by [bootstrap])

    // JSON-RPC id correlation
    @Volatile private var initializeId: Long = -1
    @Volatile private var sessionOpenId: Long = -1
    private val promptIds = ConcurrentHashMap.newKeySet<Long>() // outstanding session/prompt request ids

    // session/load replays the whole history via session/update BEFORE its response — those are historical,
    // not live turn output, and the daemon replays history from disk separately, so drop them in that window.
    @Volatile private var suppressReplayUpdates = false

    // askId → (JSON-RPC request id, permission options) — options carry the optionIds we answer with
    private val pendingApprovals = ConcurrentHashMap<String, PendingApproval>()

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
        bootstrap.withLock { pendingPrompt = null }
        promptIds.clear(); pendingApprovals.clear()
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
        return when (id) {
            initializeId -> { openSession(); emptyList() }
            sessionOpenId -> onSessionOpened(result)
            in promptIds -> { promptIds.remove(id); onPromptDone(result) }
            else -> emptyList()
        }
    }

    private suspend fun handleErrorResponse(idEl: JsonElement?, error: JsonObject?): List<AgentEvent> {
        val id = (idEl as? JsonPrimitive)?.longOrNull
        val msg = error?.str("message") ?: "kimi error"
        // an auth wall (no model / not logged in) surfaces here on session open or the first prompt
        if (id == sessionOpenId || (id != null && id in promptIds)) {
            promptIds.remove(id)
            return listOf(
                AgentEvent.AssistantText("⚠️ $msg"),
                AgentEvent.TurnResult(finalText = null, usage = null, isError = true),
            )
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
        val flush = bootstrap.withLock {
            sessionId = sid
            pendingPrompt.also { pendingPrompt = null }
        }
        flush?.let { writePrompt(it.text) }
        return listOf(AgentEvent.SessionInit(sessionId = sid, cwd = workdir, model = model))
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
                params.obj("update")?.let { KimiAcpParser.translate(it) } ?: emptyList()
            }
            else -> emptyList()
        }
    }

    // ---- inbound: server→client requests (approvals + fs/terminal we decline) ----

    private suspend fun handleServerRequest(method: String, idEl: JsonElement, params: JsonObject?): List<AgentEvent> {
        val askId = (idEl as? JsonPrimitive)?.contentOrNull ?: idEl.toString()
        return when (method) {
            "session/request_permission" -> {
                val toolCall = params?.obj("toolCall")
                val options = params?.arr("options") ?: JsonArray(emptyList())
                pendingApprovals[askId] = PendingApproval(idEl, options)
                val name = dev.ccpocket.daemon.opencode.ToolNameMapper.map(
                    toolCall?.str("kind") ?: toolCall?.str("title") ?: "tool",
                )
                val input = toolCall?.obj("rawInput") ?: buildJsonObject {
                    toolCall?.str("title")?.let { put("description", it) }
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
        val ready = bootstrap.withLock {
            if (sessionId == null) { pendingPrompt = Prompt(text, images); false } else true
        }
        if (ready) writePrompt(text)
    }

    private suspend fun writePrompt(text: String) {
        val sid = sessionId ?: return
        val id = rpcRequest("session/prompt", buildJsonObject {
            put("sessionId", sid)
            putJsonArray("prompt") {
                addJsonObject { put("type", "text"); put("text", text) }
            }
        })
        promptIds.add(id)
    }

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
        write(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            params?.let { put("params", it) }
        })
        return id
    }

    private suspend fun rpcNotify(method: String, params: JsonObject?) =
        write(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); params?.let { put("params", it) } })

    private suspend fun rpcRespondResult(id: JsonElement, result: JsonObject) =
        write(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })

    private suspend fun rpcRespondError(id: JsonElement, code: Int, message: String) =
        write(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); putJsonObject("error") { put("code", code); put("message", message) } })

    private suspend fun write(obj: JsonObject) { io?.writeLine(obj.toString()) }
}
