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
class CodexBackend(
    private val codexBin: String?,
    private val modelService: CodexModelService = CodexModelService(),
) : AgentBackend {
    private val log = logger("CodexBackend")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val idSeq = AtomicLong(1)
    private val bootstrap = Mutex() // guards threadId + pendingPrompt so the first turn is never lost to a race

    @Volatile private var io: AgentIo? = null
    @Volatile private var resolvedExe: Path? = null // codex binary, resolved lazily on first launch
    @Volatile private var workdir: String = ""
    @Volatile private var resumeId: String? = null
    @Volatile private var forkSession: Boolean = false
    @Volatile private var mode: PermissionMode = PermissionMode.DEFAULT
    @Volatile private var model: String? = null
    @Volatile private var effort: String? = null
    @Volatile private var serviceTier: String? = null

    @Volatile private var threadId: String? = null
    @Volatile private var currentTurnId: String? = null
    private var pendingPrompt: Prompt? = null // buffered first turn (guarded by [bootstrap])
    private var pendingCompact = false // /compact may arrive while thread resume/fork is still handshaking
    private var pendingReview: String? = null // empty marker = uncommitted-changes review

    // JSON-RPC id correlation: which of our outstanding requests this id belongs to
    @Volatile private var initializeId: Long = -1
    @Volatile private var threadOpenId: Long = -1
    // Outstanding writes whose ERROR response must not be swallowed (PR #296 review): a steer that raced
    // turn completion retries as turn/start, a rejected turn/start settles the turn it never started, and
    // a control-plane op reports its failure instead of no-oping. Cleared on the success response.
    private val pendingSteers = ConcurrentHashMap<Long, SteerAttempt>() // turn/steer id → what rode it
    private val pendingStarts = ConcurrentHashMap.newKeySet<Long>() // turn/start ids in flight
    private val pendingControls = ConcurrentHashMap<Long, String>() // compact/review id → op label
    // Prompts that arrived mid-turn but can't ride turn/steer (it has no image transport — Tier C):
    // parked until the turn boundary instead of silently dropping their images (PR #296 re-review).
    private val queuedStarts = ArrayDeque<Prompt>() // guarded by [bootstrap]

    private data class SteerAttempt(val prompt: Prompt, val expectedTurnId: String)

    // a turn's running state, reset on turn/completed
    @Volatile private var lastAgentText: String? = null
    @Volatile private var lastErrorText: String? = null
    @Volatile private var lastUsage = Usage()
    @Volatile private var usageSeen = false // no tokenUsage event yet → TurnResult must say "no usage", not zeros
    private val deltaSeen = ConcurrentHashMap.newKeySet<String>() // agentMessage itemIds that streamed deltas → don't re-emit final
    private val fileChangePaths = ConcurrentHashMap<String, String>() // fileChange itemId → first changed path (for the approval preview)
    private val fileChangeDiffs = ConcurrentHashMap<String, String>() // fileChange itemId → unified diff (for the approval diff view)
    private val pendingApprovals = ConcurrentHashMap<String, JsonElement>() // askId → original JSON-RPC id (preserve type for the response)

    private data class Prompt(val text: String, val images: List<ImageData>)
    private data class Usage(val input: Long = 0, val output: Long = 0, val cached: Long = 0)

    override val kind: AgentKind = AgentKind.CODEX

    // app-server exposes compaction and review as control-plane RPCs (thread/compact/start, review/start)
    override val supportsNativeCompact: Boolean get() = true
    override val supportsNativeReview: Boolean get() = true

    override fun processBuilder(spec: AgentSpec): ProcessBuilder = CodexLauncher.processBuilder(exe(), spec)

    /** Resolve the codex binary lazily — only a launch needs it, so listing/replay work even without codex installed. */
    private fun exe(): Path = resolvedExe ?: CodexLauncher.resolveExecutable(codexBin).also { resolvedExe = it }

    override suspend fun attach(io: AgentIo, spec: AgentSpec) {
        this.io = io
        this.workdir = spec.workdir.toString()
        this.resumeId = spec.resumeId
        this.forkSession = spec.forkSession
        this.mode = spec.mode
        this.model = spec.model
        this.effort = normalizeEffort(spec.model, spec.effort)
        this.serviceTier = normalizeServiceTier(spec.model, spec.serviceTier)
        // reset per-process protocol state (this runs on every (re)launch)
        threadId = null; currentTurnId = null
        bootstrap.withLock { pendingPrompt = null; pendingCompact = false; pendingReview = null }
        lastAgentText = null; lastErrorText = null; lastUsage = Usage(); usageSeen = false
        deltaSeen.clear(); fileChangePaths.clear(); fileChangeDiffs.clear(); pendingApprovals.clear()
        pendingSteers.clear(); pendingStarts.clear(); pendingControls.clear()
        bootstrap.withLock { queuedStarts.clear() }
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
                root.containsKey("error") -> handleErrorResponse(idEl, root.obj("error"))
                else -> emptyList()
            }
        }.getOrElse { log.warn("codex parse failed: ${it.message}"); emptyList() }
    }

    // ---- inbound: responses to our requests ----

    private suspend fun handleResponse(idEl: JsonElement?, result: JsonElement?): List<AgentEvent> {
        val id = (idEl as? JsonPrimitive)?.longOrNull
        if (id != null) { pendingSteers.remove(id); pendingStarts.remove(id); pendingControls.remove(id) }
        return when (id) {
            initializeId -> {
                rpcNotify("initialized", null)
                openThread()
                emptyList()
            }
            threadOpenId -> onThreadReady(result as? JsonObject)
            else -> emptyList()
        }
    }

    /**
     * A JSON-RPC error response used to be logged and dropped — which turned three failure classes into
     * silence (PR #296 review): a turn/steer whose expectedTurnId went stale lost the user's ALREADY-ACKED
     * prompt (the issue #84/#104 "swallowed prompt" class), a thread open rejected by an older app-server
     * (one predating thread/fork — present since at least 0.145.0 per probe-codex-wire.py) hung the
     * session forever with the first prompt parked in [pendingPrompt], and a rejected compact/review
     * no-oped with no feedback.
     */
    private suspend fun handleErrorResponse(idEl: JsonElement?, error: JsonObject?): List<AgentEvent> {
        val id = (idEl as? JsonPrimitive)?.longOrNull
        val msg = error?.str("message") ?: error?.toString() ?: "unknown error"
        if (id == null) {
            // JSON-RPC's mandated reply to an unparseable/invalid request is `"id":null` — nothing of ours
            // to correlate, and the maps below reject null keys (ConcurrentHashMap NPEs on them).
            log.warn("codex error (no id): $error")
            return emptyList()
        }
        pendingSteers.remove(id)?.let { attempt ->
            if (currentTurnId == attempt.expectedTurnId) {
                // Rejected while — by this pipe's own ordering — the steered turn is STILL running (its
                // turn/completed would have been processed before this response). Not staleness: a blind
                // turn/start retry would hit the active-writer conflict and its rejection would falsely
                // settle a live turn. Surface it instead.
                return listOf(AgentEvent.AssistantText("⚠️ Codex rejected the mid-turn message: $msg"))
            }
            // The steer raced the turn's completion: [currentTurnId] was read before turn/completed landed,
            // so the server saw a stale expectedTurnId. That turn is over, which makes a plain turn/start
            // the correct delivery now. One bounded hop — the retry registers in [pendingStarts], so a
            // second rejection surfaces below instead of looping.
            log.info("codex steer rejected (${msg.take(120)}) — re-delivering as turn/start")
            writeTurnStart(attempt.prompt.text, attempt.prompt.images)
            return emptyList()
        }
        if (pendingStarts.remove(id)) {
            // Conversation marked the turn executing when it acked this prompt; only a TurnResult clears
            // that. Settle the turn the server never started, and say so where the user can see it.
            return listOf(AgentEvent.TurnResult("⚠️ Codex rejected the prompt: $msg", usage = null, isError = true))
        }
        pendingControls.remove(id)?.let { op ->
            return listOf(AgentEvent.AssistantText("⚠️ Codex /$op failed: $msg"))
        }
        if (id == threadOpenId) {
            // Deliberately NO silent thread/resume fallback for a failed fork: fork exists so a take-over
            // never becomes a second writer on a rollout another codex may still own. Surface it instead —
            // a buffered first prompt would otherwise wait forever behind a thread that will never open.
            val op = if (resumeId == null) "start" else if (forkSession) "fork" else "resume"
            return listOf(AgentEvent.TurnResult("⚠️ Codex could not $op this session: $msg", usage = null, isError = true))
        }
        if (id == initializeId) {
            return listOf(AgentEvent.TurnResult("⚠️ Codex failed to initialize: $msg", usage = null, isError = true))
        }
        log.warn("codex error: $error")
        return emptyList()
    }

    private suspend fun openThread() {
        val rid = resumeId
        threadOpenId = if (rid != null) {
            rpcRequest(if (forkSession) "thread/fork" else "thread/resume", buildJsonObject {
                put("threadId", rid)
                // thread/fork and thread/resume accept the same relevant overrides in the v2 app-server
                // protocol (probe-codex-wire.py proves fork on 0.145.0 already — mints a new thread id with
                // forkedFromId). Native fork is the Codex mirror of Claude --fork-session: a phone take-over
                // never creates a second writer on the desktop's live rollout. Older servers that lack the
                // method answer a correlated error response, surfaced by handleErrorResponse.
                codexModel()?.let { put("model", it) }
                serviceTier?.let { put("serviceTier", it) }
            })
        } else {
            rpcRequest("thread/start", buildJsonObject {
                put("cwd", workdir)
                put("approvalPolicy", approvalPolicy())
                put("sandbox", sandbox().flat) // thread/start takes the flat SandboxMode string
                codexModel()?.let { put("model", it) }
                serviceTier?.let { put("serviceTier", it) }
            })
        }
    }

    private suspend fun onThreadReady(result: JsonObject?): List<AgentEvent> {
        val thread = result?.obj("thread") ?: return emptyList()
        val tid = thread.str("id") ?: return emptyList()
        result.str("model")?.let { model = it }
        val flush = bootstrap.withLock {
            threadId = tid
            val prompt = pendingPrompt.also { pendingPrompt = null }
            val compact = pendingCompact.also { pendingCompact = false }
            val review = pendingReview.also { pendingReview = null }
            Triple(prompt, compact, review)
        }
        flush.first?.let { writeTurnStart(it.text, it.images) }
        if (flush.second) requestCompact(tid)
        flush.third?.let { requestReview(tid, it) }
        return listOf(AgentEvent.SessionInit(sessionId = tid, cwd = workdir, model = result.str("model")))
    }

    // ---- inbound: server notifications ----

    private suspend fun handleNotification(method: String, params: JsonObject?): List<AgentEvent> {
        params ?: return emptyList()
        return when (method) {
            "thread/started" -> { // backup path: usually the thread/start RESULT lands first
                if (threadId == null) onThreadReady(buildJsonObject { params.obj("thread")?.let { put("thread", it) } }) else emptyList()
            }
            "turn/started" -> {
                currentTurnId = params.obj("turn")?.str("id")
                lastErrorText = null // a stale between-turns error must not be billed to this new turn
                emptyList()
            }
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
            "turn/completed" -> {
                val events = onTurnCompleted(params.obj("turn"))
                // one parked image-prompt per boundary: it becomes the NEXT turn; any others wait their own
                bootstrap.withLock { queuedStarts.removeFirstOrNull() }?.let { writeTurnStart(it.text, it.images) }
                events
            }
            "error" -> {
                val msg = params.obj("error")?.str("message") ?: "codex error"
                // Mid-turn: the v2 protocol guarantees turn.error on a failed completion, so stash rather
                // than emit — emitting immediately duplicated the error and left Conversation to synthesize
                // the unhelpful literal "turn failed" afterwards. BETWEEN turns there is no turn/completed
                // coming to carry the stash (PR #296 review): surface it now or the user never sees it.
                if (currentTurnId == null) listOf(AgentEvent.AssistantText("⚠️ $msg"))
                else { lastErrorText = msg; emptyList() }
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
        // The stashed notification text is only evidence about THIS turn when the turn actually failed:
        // unconditioned, a leftover error became the "final answer" of a later tool-only turn that
        // completed fine (isError=false, finalText=<old error>) — PR #296 review.
        val failure = turn?.obj("error")?.str("message") ?: lastErrorText?.takeIf { status == "failed" }
        val u = lastUsage
        val ev = AgentEvent.TurnResult(
            // A FAILED turn reports its reason even when partial text streamed first: the deltas already
            // reached the phone, but `partial ?: failure` hid WHY the turn died (PR #296 re-review).
            finalText = if (status == "failed") failure ?: lastAgentText else lastAgentText,
            // a turn that never saw a tokenUsage event reports "unknown" (null), not an empty window
            usage = if (usageSeen) TokenUsage(u.input, u.output, null, u.cached) else null,
            isError = status == "failed",
        )
        lastAgentText = null
        lastErrorText = null
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
                    // file_path carries a PATH and nothing else: the daemon's scope guards (guest share roots,
                    // a bridge's workdir) resolve this key against the allowed roots, and codex's prose `reason`
                    // resolved as a relative path canonicalizes INSIDE the workdir — i.e. a cache miss would
                    // manufacture an in-scope target and wave a real out-of-scope edit through. When the path is
                    // unknown the guards must see "no target" and the ask must reach a human; the reason still
                    // rides along for the card, under a key no guard reads as a path.
                    path?.let { put("file_path", it) }
                    if (path == null) params?.str("reason")?.let { put("description", it) }
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
        val promptText = text // expansion happens at Conversation's prompt boundary (expandSlashPrompt)
        val ready = bootstrap.withLock {
            if (threadId == null) { pendingPrompt = Prompt(promptText, images); false } else true
        }
        if (ready) {
            val activeTurn = currentTurnId
            when {
                activeTurn != null && images.isNotEmpty() -> {
                    // turn/steer has no image transport (Tier C): steering would silently drop the images
                    // and answer the question without them. Park the whole prompt for the turn boundary —
                    // delivered as its own turn/start from onTurnCompleted's drain, images intact.
                    bootstrap.withLock { queuedStarts.addLast(Prompt(promptText, images)) }
                    // The turn can complete between the activeTurn read and the enqueue — then no future
                    // turn/completed exists to drain this. Re-check and drain ourselves; the lock makes
                    // the two drains take each prompt exactly once.
                    if (currentTurnId == null) {
                        bootstrap.withLock { queuedStarts.removeFirstOrNull() }?.let { writeTurnStart(it.text, it.images) }
                    }
                }
                activeTurn != null -> writeTurnSteer(promptText, images, activeTurn)
                else -> writeTurnStart(promptText, images)
            }
        }
    }

    /** `/simplify` is a Claude built-in skill, not an app-server method. Keep CC Pocket's action useful
     * for Codex by expanding it into an explicit, stable task instead of sending an unknown slash token.
     * Applied by Conversation at the prompt boundary (issue #301) — one rewrite, ledgered as sent. */
    override fun expandSlashPrompt(text: String): String {
        val trimmed = text.trim()
        if (trimmed.substringBefore(' ').substringBefore('\n') != "/simplify") return text
        val extra = trimmed.removePrefix("/simplify").trim()
        return buildString {
            append("Review the current uncommitted changes and simplify the implementation without changing behavior. ")
            append("Prioritize reuse, clarity, and removing unnecessary complexity; run relevant validation after editing.")
            if (extra.isNotEmpty()) append(" Additional instructions: ").append(extra)
        }
    }

    private suspend fun writeTurnStart(text: String, images: List<ImageData>) {
        val tid = threadId ?: return
        rpcRequest("turn/start", register = { pendingStarts.add(it) }, params = buildJsonObject {
            put("threadId", tid)
            putJsonArray("input") {
                addJsonObject { put("type", "text"); put("text", text) }
                // app-server's ImageUserInput takes a URL. A data URL keeps the phone's already-downscaled
                // image in-memory end to end, with no temporary file lifecycle or daemon-local path exposure.
                images.forEach { image ->
                    addJsonObject {
                        put("type", "image")
                        put("url", "data:${image.mediaType};base64,${image.base64}")
                    }
                }
            }
            put("cwd", workdir)
            put("approvalPolicy", approvalPolicy())
            putJsonObject("sandboxPolicy") { put("type", sandbox().tag) } // turn/start takes the object form
            codexModel()?.let { put("model", it) }
            effort?.let { put("effort", it) }
            serviceTier?.let { put("serviceTier", it) }
        })
    }

    /** Append input to the turn Codex is already running. Starting a second turn on the same thread is
     * rejected by app-server as an active-writer conflict; turn/steer is its native in-flight input API. */
    private suspend fun writeTurnSteer(text: String, images: List<ImageData>, turnId: String) {
        val tid = threadId ?: return
        // registered BEFORE the write: a stale-expectedTurnId rejection re-delivers this as turn/start
        rpcRequest("turn/steer", register = { pendingSteers[it] = SteerAttempt(Prompt(text, images), turnId) }, params = buildJsonObject {
            put("threadId", tid)
            putJsonArray("input") {
                addJsonObject { put("type", "text"); put("text", text) }
                // images: Codex takes image{url}/localImage{path}, not base64 inline — deferred (Tier C);
                // sendPrompt routes image-carrying prompts around this path entirely (queuedStarts)
            }
            put("expectedTurnId", turnId)
        })
    }

    override suspend fun interrupt() {
        val tid = threadId ?: return
        val turn = currentTurnId ?: return
        rpcRequest("turn/interrupt", buildJsonObject { put("threadId", tid); put("turnId", turn) })
    }

    override suspend fun compact(): Boolean {
        val tid = bootstrap.withLock {
            threadId.also { if (it == null) pendingCompact = true }
        }
        if (tid != null) requestCompact(tid)
        return true
    }

    private suspend fun requestCompact(tid: String) {
        rpcRequest("thread/compact/start", register = { pendingControls[it] = "compact" }, params = buildJsonObject { put("threadId", tid) })
    }

    override suspend fun review(instructions: String?): Boolean {
        val review = instructions?.trim().orEmpty()
        val tid = bootstrap.withLock {
            threadId.also { if (it == null) pendingReview = review }
        }
        if (tid != null) requestReview(tid, review)
        return true
    }

    private suspend fun requestReview(tid: String, instructions: String) {
        rpcRequest("review/start", register = { pendingControls[it] = "review" }, params = buildJsonObject {
            put("threadId", tid)
            put("delivery", "inline")
            putJsonObject("target") {
                if (instructions.isBlank()) put("type", "uncommittedChanges")
                else {
                    put("type", "custom")
                    put("instructions", instructions)
                }
            }
        })
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

    override fun applyEffort(effort: String?): Boolean {
        this.effort = effort
        return false
    }

    override fun applyServiceTier(serviceTier: String?): Boolean {
        this.serviceTier = serviceTier
        return false
    }

    override fun supportedEfforts(model: String?): Set<String>? =
        modelService.capabilitiesFor(model)?.reasoningEfforts?.toSet()

    override fun normalizeEffort(model: String?, effort: String?): String? {
        val supported = supportedEfforts(model) ?: return effort
        return effort?.takeIf { it in supported }
    }

    override fun normalizeServiceTier(model: String?, serviceTier: String?): String? {
        val caps = modelService.capabilitiesFor(model) ?: return serviceTier
        return serviceTier?.takeIf { wanted -> caps.serviceTiers.any { it.id == wanted } }
    }

    override suspend fun onProcessEnded(sessionId: String?) {} // codex rollouts are self-managed; nothing to unhide

    // ---- disk: ~/.codex/sessions rollout scanning + replay (filtered by recorded cwd) ----

    override fun transcriptDir(workdir: String): Path = CodexPaths.sessionsRoot()

    override fun transcriptPath(workdir: String, sessionId: String): Path? {
        // findSession is a suffix match against real filenames, but keep the wire-input guard uniform
        if (sessionId.contains('/') || sessionId.contains('\\') || sessionId.contains("..")) return null
        return CodexPaths.findSession(sessionId)
    }

    override fun externalWriterProbe(workdir: String, transcript: Path) =
        dev.ccpocket.daemon.disk.LiveProcesses.externalCodexAt(workdir, transcript)

    override val holdsTranscriptWhileIdle: Boolean get() = true
    override fun listSessions(workdir: String): List<SessionSummary> = CodexTranscriptScanner.scan(workdir)
    override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> =
        CodexPaths.findSession(sessionId)?.let { CodexTranscriptReplay.read(it) } ?: emptyList()

    // incremental reattach + older-history paging (issue #147) — seq = the rollout's source line
    override fun replaySlice(workdir: String, sessionId: String, sinceSeq: Long?): dev.ccpocket.daemon.disk.ReplaySlice =
        CodexPaths.findSession(sessionId)?.let { CodexTranscriptReplay.slice(it, sinceSeq) }
            ?: dev.ccpocket.daemon.disk.ReplaySlice.EMPTY

    override fun replayPage(workdir: String, sessionId: String, beforeSeq: Long, limit: Int): dev.ccpocket.daemon.disk.ReplaySlice =
        CodexPaths.findSession(sessionId)?.let { CodexTranscriptReplay.page(it, beforeSeq, limit) }
            ?: dev.ccpocket.daemon.disk.ReplaySlice.EMPTY

    override fun resumeContextTokens(workdir: String, sessionId: String): Long? =
        CodexPaths.findSession(sessionId)?.let { CodexTranscriptScanner.runtimeState(it).contextUsed }

    override fun resumeModel(workdir: String, sessionId: String): String? =
        CodexPaths.findSession(sessionId)?.let { CodexTranscriptScanner.runtimeState(it).model }

    override fun resumeTitle(workdir: String, sessionId: String): String? =
        CodexTranscriptScanner.threadNames()[sessionId]?.takeIf { it.isNotBlank() }
            ?: CodexPaths.findSession(sessionId)?.let { CodexTranscriptScanner.summarize(it, workdir)?.title }

    // issue #96: read the configured default (top-level `model` in $CODEX_HOME/config.toml) so a brand-new
    // Codex session's header shows the real model before the first turn instead of a blank segment.
    override fun defaultModel(workdir: String): String? = CodexDefaultModel.resolve()

    // ---- mode mapping (Claude's single mode → Codex's approvalPolicy × sandbox axes) ----

    // The 4 PermissionMode values are the phone's Codex presets (Cautious/Balanced/Autonomous/Full auto).
    private fun approvalPolicy(): String = approvalPolicyFor(mode)

    private fun sandbox(): Sandbox = sandboxFor(mode)

    /** Drop Claude model aliases (opus/sonnet/haiku) — they're meaningless to codex and would error. */
    private fun codexModel(): String? = model?.takeIf { it.lowercase() !in CLAUDE_ALIASES }

    // ---- JSON-RPC plumbing ----

    /** [register] runs between id allocation and the WRITE: an instant rejection is parsed on the pump
     *  coroutine, so registering after the write races it — the error would find no pending entry and
     *  fall to the log-and-drop path, resurrecting the swallowed-prompt class (PR #296 re-review). */
    private suspend fun rpcRequest(method: String, params: JsonObject?, register: ((Long) -> Unit)? = null): Long {
        val id = idSeq.getAndIncrement()
        register?.invoke(id)
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

    companion object {
        // the daemon's real build version (single runtime source — no per-release manual bump here)
        private val CLIENT_VERSION: String get() = dev.ccpocket.daemon.util.DaemonVersion.CURRENT
        private const val MAX_DIFF_CHARS = 6000 // approval diff cap — keeps the PermissionAsk frame well under the relay's 256 KiB limit
        private val CLAUDE_ALIASES = setOf("opus", "sonnet", "haiku")

        /** The sandbox for a mode in both spellings codex needs: flat SandboxMode string (thread/start)
         *  + the SandboxPolicy object tag (turn/start). One source so the two can't desync. */
        internal data class Sandbox(val flat: String, val tag: String)

        // internal (not private) so CodexModelServiceTest can assert the advertised MODE_PRESETS rows
        // stay paired with THIS translation — the copy the daemon broadcasts must describe what a session
        // under that mode actually runs as (PR #296 review: drift here is a daemon-authority lie).
        internal fun approvalPolicyFor(mode: PermissionMode): String = when (mode) {
            PermissionMode.PLAN -> "untrusted"             // Cautious: ask every step (paired with read-only)
            PermissionMode.DEFAULT -> "on-request"         // Balanced: ask when needed (the recommended default)
            PermissionMode.ACCEPT_EDITS -> "never"         // Autonomous: never ask, writes in the workspace
            PermissionMode.BYPASS_PERMISSIONS -> "never"   // Full auto: never ask + full access
        }

        internal fun sandboxFor(mode: PermissionMode): Sandbox = when (mode) {
            PermissionMode.PLAN -> Sandbox("read-only", "readOnly")
            PermissionMode.BYPASS_PERMISSIONS -> Sandbox("danger-full-access", "dangerFullAccess")
            else -> Sandbox("workspace-write", "workspaceWrite")
        }
    }
}
