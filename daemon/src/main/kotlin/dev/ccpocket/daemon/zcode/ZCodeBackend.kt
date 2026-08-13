package dev.ccpocket.daemon.zcode

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
import dev.ccpocket.protocol.TokenUsage
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
 * ZCode 3.7.6's persistent `app-server --stdio` backend (issue #228). Its wire is strict newline-delimited
 * ZCode Protocol — JSON request/response envelopes WITHOUT JSON-RPC's `jsonrpc` member. The runtime
 * accepts one foreground `session/send` at a time, so prompts after the first are held in a per-process
 * FIFO and settled only by the authoritative `turn.started.payload.input` receipt.
 */
class ZCodeBackend(
    private val zcodeBin: String?,
    private val modelService: ZCodeModelService = ZCodeModelService(),
    private val executable: () -> Path = { ZCodeLauncher.resolveExecutable(zcodeBin) },
) : AgentBackend {
    private val log = logger("ZCodeBackend")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val ids = AtomicLong(1)
    private val state = Mutex()

    @Volatile private var io: AgentIo? = null
    @Volatile private var resolvedExe: Path? = null
    @Volatile private var workdir = ""
    @Volatile private var resumeId: String? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var openId = ""
    @Volatile private var subscribeId = ""
    @Volatile private var mode = PermissionMode.DEFAULT
    @Volatile private var model: String? = null
    @Volatile private var effort: String? = null

    // A cancel can race the app-server handshake. Keep interrupts behind the first session/send write;
    // otherwise session/stop reaches an idle, not-yet-open session and the opening turn runs anyway.
    // These two fields are guarded by [state].
    private var readyForInterrupt = false
    private var stopWhenReady = false

    // Startup settings barrier. The NDJSON writes are ordered, but nothing in the ZCode protocol proves
    // the app-server has *applied* setModel/setMode/setThoughtLevel before it accepts session/send — so a
    // resumed session could otherwise run its first turn on the stored model/effort. The opening prompt is
    // therefore held until every setter this open issued has answered (result or error) and the
    // model-dependent thought-level chain has settled. All three fields are guarded by [state].
    private val startupSettings = mutableSetOf<String>()
    private var openHandshakeDone = false
    private var startupReleased = false

    // ZCode has no "default" sentinel for session/setThoughtLevel. Both an explicit level and restoring
    // the model default must therefore be checked against the full snapshot's enabled + available fields.
    // When a model switch is part of the relaunch, wait for its response (and, defensively, session/read)
    // so we never apply a level supported only by the previous model.
    @Volatile private var resetEffortOnResume = false
    @Volatile private var thoughtDefault: String? = null
    @Volatile private var thoughtEnabled: Boolean? = null
    @Volatile private var thoughtAvailable: Set<String>? = null
    @Volatile private var pendingEffortAfterModelId: String? = null
    @Volatile private var pendingEffortReadId: String? = null

    private data class Prompt(val text: String, val images: List<ImageData>)
    private data class PendingInteraction(
        val rpcId: JsonElement,
        val options: JsonArray = JsonArray(emptyList()),
        val userInput: Boolean = false,
        val answerValues: Map<String, Map<String, String>> = emptyMap(),
    )
    private data class ToolAccum(var name: String, val input: StringBuilder = StringBuilder(), var started: Boolean = false)

    private val queued = ArrayDeque<Prompt>()
    private val promptRequests = ConcurrentHashMap<String, String>()
    private val permissions = ConcurrentHashMap<String, PendingInteraction>()
    private val tools = ConcurrentHashMap<String, ToolAccum>()

    override val kind = AgentKind.ZCODE

    override fun processBuilder(spec: AgentSpec): ProcessBuilder =
        ZCodeLauncher.processBuilder(resolvedExe ?: executable().also { resolvedExe = it }, spec)

    override suspend fun attach(io: AgentIo, spec: AgentSpec) {
        this.io = io
        workdir = spec.workdir.toString()
        resumeId = spec.resumeId
        mode = spec.mode
        model = spec.model
        effort = spec.effort
        thoughtDefault = null
        thoughtEnabled = null
        thoughtAvailable = null
        pendingEffortAfterModelId = null
        pendingEffortReadId = null
        promptRequests.clear(); permissions.clear(); tools.clear()
        state.withLock {
            sessionId = null
            readyForInterrupt = false
            stopWhenReady = false
            startupSettings.clear()
            openHandshakeDone = false
            startupReleased = false
            queued.clear()
        }
        openId = nextId()
        val method = if (resumeId == null) "session/create" else "session/resume"
        val params = buildJsonObject {
            resumeId?.let { put("sessionId", it) }
            putJsonObject("workspace") { put("workspacePath", workdir); put("workspaceKey", workdir) }
            if (resumeId == null) {
                put("mode", zcodeMode(mode))
                modelRef(model)?.let { put("model", it) }
            }
        }
        send(openId, method, params)
    }

    override suspend fun parse(line: String): List<AgentEvent> {
        val raw = line.trim()
        if (raw.isEmpty()) return emptyList()
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return listOf(AgentEvent.Unparseable(raw))
        return runCatching {
            val method = root.str("method")
            val id = root["id"]?.takeUnless { it is JsonNull }
            when {
                method != null && id != null -> serverRequest(method, id, root.obj("params"))
                method != null -> notification(method, root.obj("params"))
                root.containsKey("error") -> errorResponse(id, root.obj("error"))
                root.containsKey("result") -> response(id, root.obj("result"))
                else -> emptyList()
            }
        }.getOrElse {
            log.warn("zcode parse failed: ${it.message}")
            emptyList()
        }
    }

    private suspend fun response(idElement: JsonElement?, result: JsonObject?): List<AgentEvent> {
        val id = idElement.idString() ?: return emptyList()
        if (id == openId) {
            val session = result?.obj("session")
            val sid = session?.str("sessionId") ?: resumeId ?: return emptyList()
            updateThoughtSettings(result)
            subscribeId = request("session/subscribe", buildJsonObject {
                put("sessionId", sid)
                put("deliveryKind", "desktop-continuous")
                // No afterSeq/includeSnapshot on resume: history comes from SQLite, not event replay.
            })
            if (resumeId != null) applyLiveSettingsAfterResume(sid)
            else finishEffortApplication(sid, finalSnapshot = true)

            // Publish the session id and mark the handshake done. The buffered opening prompt and the
            // interrupt gate are released by releaseStartupLocked, either right here (nothing to wait for)
            // or from the last outstanding setter's response. A racing interrupt either records
            // stopWhenReady before this block or waits until the prompt is on stdin; it can never
            // overtake that prompt.
            val deferredStop = state.withLock {
                sessionId = sid
                openHandshakeDone = true
                releaseStartupLocked()
            }
            if (deferredStop) stop(sid)
            val wireModel = session?.obj("model")?.let(::modelId) ?: model
            return listOf(AgentEvent.SessionInit(sid, session?.obj("workspace")?.str("workspacePath") ?: workdir, wireModel))
        }
        if (id == pendingEffortAfterModelId) {
            pendingEffortAfterModelId = null
            updateThoughtSettings(result)
            // Register the follow-up (session/read or the setter) BEFORE settling this id, so the barrier
            // never transiently empties mid-chain and lets the opening prompt through.
            finishOrReadEffortApplication()
            settleStartupSetting(id)
            return emptyList()
        }
        if (id == pendingEffortReadId) {
            pendingEffortReadId = null
            updateThoughtSettings(result)
            finishEffortApplication(sessionId ?: resumeId, finalSnapshot = true)
            settleStartupSetting(id)
            return emptyList()
        }
        if (id == subscribeId) {
            return result?.arr("events").orEmpty().flatMap { event ->
                translateEvent(event as? JsonObject ?: return@flatMap emptyList())
            }
        }
        // setMode / setThoughtLevel / a setModel with no effort work behind it.
        settleStartupSetting(id)
        // session/send's {accepted:true} is not a consumption receipt; turn.started is.
        return emptyList()
    }

    private suspend fun errorResponse(idElement: JsonElement?, error: JsonObject?): List<AgentEvent> {
        val id = idElement.idString()
        val message = error?.str("message") ?: "ZCode protocol error"
        if (id == pendingEffortAfterModelId || id == pendingEffortReadId) {
            if (id == pendingEffortAfterModelId) pendingEffortAfterModelId = null
            if (id == pendingEffortReadId) pendingEffortReadId = null
            resetEffortOnResume = false
            log.warn("zcode could not validate the requested thought level: $message")
        }
        // A rejected setter is a settled setter: consume it and release the barrier rather than holding
        // the opening prompt forever. The level/model simply stays as stored, which is logged above.
        if (id != null && id != openId && settleStartupSetting(id)) {
            log.warn("zcode startup setting $id failed: $message")
        }
        val prompt = id?.let { promptRequests.remove(it) }
        if (id == openId || prompt != null) {
            if (prompt != null) flushPrompt()
            return listOfNotNull(
                prompt?.let { AgentEvent.UserReplay(it) },
                AgentEvent.AssistantText("⚠️ $message"),
                AgentEvent.TurnResult(null, null, isError = true),
            )
        }
        log.warn("zcode error response id=$id: $message")
        return emptyList()
    }

    private suspend fun serverRequest(method: String, rpcId: JsonElement, params: JsonObject?): List<AgentEvent> = when (method) {
        "session/requestRuntimePreferences" -> {
            respond(rpcId, buildJsonObject {
                put("nativeSearchEnhancementsEnabled", false)
                put("memoryEnabled", false)
                put("askUserQuestionAutoResolutionEnabled", false)
                put("modelContextBudgetStrategy", "preflight-v1")
            })
            emptyList()
        }
        "interaction/requestPermission" -> {
            val askId = params?.str("requestId") ?: rpcId.idString() ?: rpcId.toString()
            permissions[askId] = PendingInteraction(rpcId, params?.arr("options") ?: JsonArray(emptyList()))
            listOf(
                AgentEvent.ControlRequest(
                    askId,
                    ToolNameMapper.map(params?.str("toolName") ?: "tool"),
                    params?.obj("input") ?: buildJsonObject { params?.str("reason")?.let { put("description", it) } },
                ),
            )
        }
        "interaction/requestUserInput" -> {
            // AgentEvent has no separate UserInputRequested variant. The established provider-neutral
            // AskUserQuestion path is ControlRequest(toolName=AskUserQuestion,input.questions), which
            // PermissionBridge renders as TaskDecision and routes answers back through updatedInput.
            val askId = params?.str("requestId") ?: rpcId.idString() ?: rpcId.toString()
            val questions = params?.arr("questions") ?: params?.obj("input")?.arr("questions") ?: JsonArray(emptyList())
            permissions[askId] = PendingInteraction(
                rpcId,
                userInput = true,
                answerValues = questions.mapNotNull { it as? JsonObject }.associate { question ->
                    question.str("question").orEmpty() to question.arr("options").orEmpty()
                        .mapNotNull { it as? JsonObject }
                        .mapNotNull { option -> option.str("label")?.let { label -> label to (option.str("value") ?: label) } }
                        .toMap()
                },
            )
            val input = params?.obj("input")?.takeIf { it["questions"] is JsonArray } ?: buildJsonObject {
                put("questions", questions)
            }
            listOf(AgentEvent.ControlRequest(askId, "AskUserQuestion", input))
        }
        "interaction/requestProviderRuntimeHeaders" -> {
            // The desktop client owns subscription-account headers and no ZCode protocol method exposes
            // them to cc-pocket. Returning the strict failure schema makes the runtime stop with an
            // actionable provider error instead of hanging or reporting an opaque Method not found.
            respond(rpcId, buildJsonObject {
                put("headersApplied", false)
                put("errorMessage", "cc-pocket cannot access ZCode Desktop Start Plan credentials; configure an API-key provider/model")
            })
            emptyList()
        }
        else -> {
            respondError(rpcId, -32601, "not supported by cc-pocket")
            emptyList()
        }
    }

    private suspend fun notification(method: String, params: JsonObject?): List<AgentEvent> {
        return when (method) {
            "session/event" -> params?.let { translateEvent(it) }.orEmpty()
            "state.updated" -> {
                updateThoughtSettings(params)
                emptyList()
            }
            else -> emptyList() // v4 telemetry duplicates the canonical session/event stream
        }
    }

    private suspend fun translateEvent(event: JsonObject): List<AgentEvent> {
        val type = event.str("type") ?: return emptyList()
        val p = event.obj("payload") ?: JsonObject(emptyMap())
        return when (type) {
            "turn.started" -> {
                val input = p.str("input")
                listOf(AgentEvent.UserReplay(input))
            }
            "model.streaming" -> streamEvent(p)
            "tool.updated" -> toolEvent(p)
            "session.updated" -> {
                // This is ONE provider call's usage. turn.completed sums every call in a tool loop, so
                // Conversation must receive the last call separately for accurate context occupancy.
                val u = p.obj("usage")
                if (p.str("type") == "model_request_completed" && u != null) {
                    listOf(
                        AgentEvent.AssistantUsage(
                            inputTokens = u.long("inputTokens") ?: 0,
                            cacheCreationInputTokens = u.long("cacheWriteTokens"),
                            cacheReadInputTokens = u.long("cacheReadTokens"),
                        ),
                    )
                } else emptyList()
            }
            "turn.completed" -> {
                val resultType = p.str("resultType")
                val usage = p.obj("usage")?.let { u ->
                    val input = u.long("inputTokens") ?: 0
                    val output = u.long("outputTokens") ?: 0
                    TokenUsage(input, output, cacheCreationInputTokens = u.long("cacheWriteTokens"), cacheReadInputTokens = u.long("cacheReadTokens"))
                }
                promptRequests.clear()
                flushPrompt()
                listOf(AgentEvent.TurnResult(p.str("response"), usage, isError = resultType != null && resultType != "success" && resultType != "cancelled"))
            }
            "turn.failed" -> {
                promptRequests.clear()
                flushPrompt()
                val message = p.obj("error")?.str("message") ?: p.str("message") ?: "ZCode turn failed"
                listOf(AgentEvent.AssistantText("⚠️ $message"), AgentEvent.TurnResult(null, null, isError = true))
            }
            "permission.resolved", "userInput.resolved" -> p.str("requestId")?.let { requestId ->
                permissions.remove(requestId)
                listOf(AgentEvent.ControlCancel(requestId))
            }.orEmpty()
            else -> emptyList()
        }
    }

    private fun streamEvent(p: JsonObject): List<AgentEvent> = when (p.str("kind")) {
        "text_delta" -> p.str("delta")?.takeIf { it.isNotEmpty() }?.let { listOf(AgentEvent.AssistantText(it)) }.orEmpty()
        "reasoning_delta" -> p.str("delta")?.takeIf { it.isNotEmpty() }?.let { listOf(AgentEvent.AssistantThinking(it)) }.orEmpty()
        "tool_input_start" -> {
            val id = p.str("toolCallId") ?: return emptyList()
            tools[id] = ToolAccum(ToolNameMapper.map(p.str("toolName") ?: "tool"))
            emptyList()
        }
        "tool_input_delta" -> {
            val id = p.str("toolCallId") ?: return emptyList()
            tools.getOrPut(id) { ToolAccum("Tool") }.input.append(p.str("delta").orEmpty())
            emptyList()
        }
        "tool_call" -> {
            val id = p.str("toolCallId") ?: return emptyList()
            val acc = tools.getOrPut(id) { ToolAccum(ToolNameMapper.map(p.str("toolName") ?: "tool")) }
            acc.name = ToolNameMapper.map(p.str("toolName") ?: acc.name)
            if (acc.started) emptyList() else {
                acc.started = true
                val input = p.obj("input") ?: runCatching { json.parseToJsonElement(acc.input.toString()) as? JsonObject }.getOrNull()
                listOf(AgentEvent.AssistantToolUse(id, acc.name, input ?: JsonObject(emptyMap())))
            }
        }
        else -> emptyList()
    }

    private fun toolEvent(p: JsonObject): List<AgentEvent> {
        if (p.str("kind") == "batch") {
            // A denied high-risk tool is terminally represented only by a batch with errorCount=1
            // in the official probe (no individual `kind:error`). Close any still-open cards here.
            val ids = p.arr("toolCallIds").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if ((p.long("errorCount") ?: 0) <= 0) return emptyList()
            return ids.mapNotNull { toolId ->
                tools.remove(toolId)?.let { AgentEvent.ToolResult(toolId, "Tool failed or was denied", isError = true) }
            }
        }
        val id = p.str("toolCallId") ?: return emptyList()
        val kind = p.str("kind")
        val acc = tools.getOrPut(id) { ToolAccum(ToolNameMapper.map(p.str("toolName") ?: "tool")) }
        val out = mutableListOf<AgentEvent>()
        if (!acc.started && (kind == "scheduled" || kind == "started")) {
            acc.started = true
            out += AgentEvent.AssistantToolUse(id, acc.name, p.obj("input") ?: JsonObject(emptyMap()))
        }
        when (kind) {
            "result" -> {
                tools.remove(id)
                val result = p.obj("result")
                out += AgentEvent.ToolResult(id, result?.get("content")?.textValue(), isError = result?.bool("success") == false)
            }
            "error" -> {
                tools.remove(id)
                out += AgentEvent.ToolResult(id, p.obj("error")?.str("message") ?: p["error"]?.textValue(), isError = true)
            }
        }
        return out
    }

    override suspend fun sendPrompt(text: String, images: List<ImageData>) {
        state.withLock { queued.addLast(Prompt(text, images)) }
        flushPrompt()
    }

    private suspend fun flushPrompt() {
        state.withLock { flushPromptLocked() }
    }

    /** [state] must be held. Keeping reservation + write atomic also prevents an interrupt from overtaking
     *  a just-submitted prompt after the session handshake has completed. */
    private suspend fun flushPromptLocked() {
        val sid = sessionId ?: return
        if (!startupReleased) return
        if (promptRequests.isNotEmpty()) return
        val prompt = queued.removeFirstOrNull() ?: return
        val id = nextId()
        promptRequests[id] = prompt.text
        send(id, "session/send", buildJsonObject {
            put("sessionId", sid)
            put("content", prompt.text)
            if (prompt.images.isNotEmpty()) {
                // Shape verified against the official 3.7.6 bundle. Raw base64 is intentional: the
                // runtime materializes the provider data URL itself.
                putJsonArray("attachments") {
                    prompt.images.forEachIndexed { index, image ->
                        addJsonObject {
                            put("kind", "image")
                            put("filename", "attachment-${index + 1}")
                            put("mimeType", image.mediaType)
                            put("dataBase64", image.base64)
                            runCatching { java.util.Base64.getMimeDecoder().decode(image.base64).size }
                                .getOrNull()?.let { put("sizeBytes", it) }
                        }
                    }
                }
            }
        })
    }

    override suspend fun interrupt() {
        val sid = state.withLock {
            sessionId?.takeIf { readyForInterrupt } ?: run {
                stopWhenReady = true
                null
            }
        }
        sid?.let { stop(it) }
    }

    override suspend fun respondPermission(
        askId: String,
        allow: Boolean,
        remember: Boolean,
        originalInput: JsonObject?,
        updatedInput: String?,
        denyMessage: String?,
    ) {
        val pending = permissions.remove(askId) ?: return
        if (pending.userInput) {
            val answered = updatedInput?.let { raw ->
                runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            }
            val content = buildJsonObject {
                (answered?.get("answers") as? JsonObject)?.let { answers ->
                    putJsonObject("answers") {
                        answers.forEach { (question, raw) ->
                            val answer = (raw as? JsonPrimitive)?.contentOrNull
                            val mapped = answer?.split(",")?.map { it.trim() }
                                ?.joinToString(", ") { label -> pending.answerValues[question]?.get(label) ?: label }
                            if (mapped != null) put(question, mapped) else put(question, raw)
                        }
                    }
                }
                answered?.str("response")?.let { put("answer", it) }
            }
            respond(pending.rpcId, buildJsonObject {
                put("action", if (allow) "accept" else "decline")
                if (allow) put("content", content)
                else denyMessage?.let { put("reason", it) }
            })
            return
        }
        val preferred = if (allow) {
            if (remember) listOf("allow_always", "allow_once") else listOf("allow_once", "allow_always")
        } else listOf("deny")
        val options = pending.options.mapNotNull { it as? JsonObject }
        val chosen = preferred.firstNotNullOfOrNull { kind -> options.firstOrNull { it.str("kind") == kind } }
        val result = chosen?.obj("response") ?: buildJsonObject {
            put("decision", if (allow) "allow" else "deny")
            put("reason", denyMessage ?: if (allow) "Approved" else "Denied")
        }
        respond(pending.rpcId, result)
    }

    override fun applySettings(mode: PermissionMode?, model: String?, effort: String?): Boolean {
        var relaunch = false
        mode?.let { if (it != this.mode) { this.mode = it; relaunch = true } }
        model?.let { if (it != this.model) { this.model = it; relaunch = true } }
        effort?.let { if (it != this.effort) { this.effort = it; relaunch = true } }
        return relaunch
    }

    override fun applyEffort(effort: String?): Boolean {
        if (effort == this.effort) return false
        this.effort = effort
        resetEffortOnResume = effort == null
        return true
    }

    private suspend fun applyLiveSettingsAfterResume(sid: String) {
        // Resume params intentionally preserve the stored session. Explicit new settings are then applied
        // through the protocol's strict setters, which also keeps their schemas out of session/resume.
        val needsEffortApplication = effort != null || resetEffortOnResume
        val requestedModel = modelRef(model)
        if (requestedModel != null && needsEffortApplication) clearThoughtSettings()
        val modelRequestId = requestedModel?.let { modelRef ->
            startupRequest("session/setModel", buildJsonObject { put("sessionId", sid); put("model", modelRef) })
        }
        startupRequest("session/setMode", buildJsonObject { put("sessionId", sid); put("mode", zcodeMode(mode)) })
        if (!needsEffortApplication) return
        if (modelRequestId != null) {
            // The open snapshot describes the stored model. Validate explicit and default levels only
            // against the setModel response for the new model.
            pendingEffortAfterModelId = modelRequestId
        } else {
            finishEffortApplication(sid, finalSnapshot = true)
        }
    }

    private suspend fun finishOrReadEffortApplication() {
        if (finishEffortApplication(sessionId ?: resumeId, finalSnapshot = false)) return
        val sid = sessionId ?: resumeId ?: return
        pendingEffortReadId = startupRequest("session/read", buildJsonObject { put("sessionId", sid) })
    }

    /** True when application is complete. Unknown capabilities get one session/read after setModel; a
     *  still-incomplete full snapshot is treated as unsupported. We never guess or send `default`. */
    private suspend fun finishEffortApplication(sid: String?, finalSnapshot: Boolean): Boolean {
        val desired = effort ?: thoughtDefault.takeIf { resetEffortOnResume }
        if (effort == null && !resetEffortOnResume) return true
        if (sid == null) return false

        val enabled = thoughtEnabled
        val available = thoughtAvailable
        if (enabled == null || available == null) {
            if (!finalSnapshot) return false
            log.info("zcode skipped thought level '$desired': runtime did not advertise thought capabilities")
            resetEffortOnResume = false
            return true
        }
        if (!enabled) {
            log.info("zcode skipped thought level '$desired': thought levels are disabled for this model")
            resetEffortOnResume = false
            return true
        }
        if (desired != null && desired in available) {
            startupRequest("session/setThoughtLevel", buildJsonObject {
                put("sessionId", sid)
                put("thoughtLevel", desired)
            })
            resetEffortOnResume = false
            return true
        }
        log.info("zcode skipped unsupported thought level '$desired'; available=${available.sorted()}")
        resetEffortOnResume = false
        return true
    }

    private fun updateThoughtSettings(container: JsonObject?) {
        val thought = findThoughtSettings(container) ?: return
        thoughtDefault = thought.str("defaultLevel")
        thoughtEnabled = thought.bool("enabled")
        thoughtAvailable = thought.arr("available")?.mapNotNull { option ->
            when (option) {
                is JsonObject -> option.str("value")
                is JsonPrimitive -> option.contentOrNull
                else -> null
            }
        }?.toSet()
    }

    private fun clearThoughtSettings() {
        thoughtDefault = null
        thoughtEnabled = null
        thoughtAvailable = null
    }

    private fun findThoughtSettings(container: JsonObject?): JsonObject? {
        container ?: return null
        container.obj("thoughtLevel")?.let { return it }
        container.obj("settings")?.obj("thoughtLevel")?.let { return it }
        container.obj("snapshot")?.let { findThoughtSettings(it)?.let { found -> return found } }
        container.obj("patch")?.let { findThoughtSettings(it)?.let { found -> return found } }
        return null
    }

    override suspend fun onProcessEnded(sessionId: String?) {}
    override fun transcriptDir(workdir: String): Path = ZCodePaths.database().parent
    override fun listSessions(workdir: String): List<SessionSummary> = ZCodeTranscriptScanner.scan(workdir)
    override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = ZCodeTranscriptReplay.read(sessionId)
    override fun replaySlice(workdir: String, sessionId: String, sinceSeq: Long?): ReplaySlice = ReplaySlice(replayHistory(workdir, sessionId))
    override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    override fun resumeModel(workdir: String, sessionId: String): String? = ZCodeTranscriptScanner.resumeModel(sessionId)
    override fun defaultModel(workdir: String): String? = modelService.defaultModel()

    private fun zcodeMode(mode: PermissionMode): String = when (mode) {
        PermissionMode.DEFAULT -> "build"
        PermissionMode.ACCEPT_EDITS -> "edit"
        PermissionMode.PLAN -> "plan"
        PermissionMode.BYPASS_PERMISSIONS -> "yolo"
    }

    private fun modelRef(raw: String?): JsonObject? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val slash = value.indexOf('/')
        if (slash <= 0 || slash == value.lastIndex) return null
        return buildJsonObject { put("providerId", value.substring(0, slash)); put("modelId", value.substring(slash + 1)) }
    }

    private fun modelId(ref: JsonObject): String? {
        val id = ref.str("modelId") ?: return null
        val provider = ref.str("providerId")
        return if (provider.isNullOrBlank() || '/' in id) id else "$provider/$id"
    }

    /**
     * Issues a setter whose effect the opening prompt depends on. The id is registered BEFORE the write so
     * a fast response can never settle an id the barrier has not seen yet. Every method used here
     * (setModel/setMode/setThoughtLevel/read) answers with a response envelope in the official 3.7.6
     * app-server, so the barrier is always released by the peer, never only by a timeout.
     */
    private suspend fun startupRequest(method: String, params: JsonObject): String {
        val id = nextId()
        state.withLock { startupSettings += id }
        send(id, method, params)
        return id
    }

    /** True when [id] was an outstanding startup setter (so callers can log a failed one). */
    private suspend fun settleStartupSetting(id: String): Boolean {
        val deferredStop = state.withLock {
            if (!startupSettings.remove(id)) return false
            releaseStartupLocked()
        }
        if (deferredStop) sessionId?.let { stop(it) }
        return true
    }

    /** [state] must be held. Opens the prompt/interrupt gate once the handshake and every startup setter
     *  have settled. Returns true when a cancel arrived during startup and must now be sent — after the
     *  opening prompt, so session/stop cancels the intended turn instead of an idle session. */
    private suspend fun releaseStartupLocked(): Boolean {
        if (startupReleased || !openHandshakeDone || startupSettings.isNotEmpty()) return false
        startupReleased = true
        flushPromptLocked()
        readyForInterrupt = true
        return stopWhenReady.also { stopWhenReady = false }
    }

    private fun nextId(): String = "ccp-${ids.getAndIncrement()}"
    private suspend fun request(method: String, params: JsonObject): String = nextId().also { send(it, method, params) }
    private suspend fun stop(sid: String) = request("session/stop", buildJsonObject { put("sessionId", sid) })
    private suspend fun send(id: String, method: String, params: JsonObject) = write(buildJsonObject { put("id", id); put("method", method); put("params", params) })
    private suspend fun respond(id: JsonElement, result: JsonObject) = write(buildJsonObject { put("id", id); put("result", result) })
    private suspend fun respondError(id: JsonElement, code: Int, message: String) = write(buildJsonObject {
        put("id", id); putJsonObject("error") { put("code", code); put("message", message) }
    })
    private suspend fun write(obj: JsonObject) { io?.writeLine(obj.toString()) }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
    private fun JsonElement?.idString(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.textValue(): String = (this as? JsonPrimitive)?.contentOrNull ?: toString()
}
