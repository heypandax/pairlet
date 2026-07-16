package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.daemon.disk.SpawnedSessions
import dev.ccpocket.daemon.disk.TranscriptPatcher
import dev.ccpocket.daemon.disk.TranscriptReplay
import dev.ccpocket.daemon.disk.TranscriptScanner
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PresetEnv
import dev.ccpocket.protocol.SessionSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives Claude Code (`claude -p` stream-json over stdin/stdout). Holds the resolved `claude` binary and
 * the per-launch IO; the schema knowledge is in [ClaudeLauncher] (argv), [StreamParser] (inbound) and the
 * frame builders below (outbound: user turn / interrupt / control_response). Stateless across relaunches —
 * claude bakes mode/model/effort at launch, so [applySettings] asks for a relaunch.
 */
class ClaudeBackend(
    private val claudeBin: String? = null,
    private val configDir: Path? = null,
    // API preset (issue #113): the ACTIVE preset's env for a launch, read per call so an activation
    // between launches takes effect without touching live backends. Null = no preset (env untouched).
    private val presetEnv: () -> Map<String, String>? = { null },
    // injectable for tests; production resolves via ClaudeLauncher (explicit --claude-bin → env → PATH)
    private val resolveExe: () -> Path = { ClaudeLauncher.resolveExecutable(claudeBin) },
) : AgentBackend {
    private val log = logger("ClaudeBackend")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val interruptSeq = AtomicLong(0)

    @Volatile private var io: AgentIo? = null
    @Volatile private var resolvedExe: Path? = null // claude binary, resolved lazily on first launch (issue #130)
    @Volatile private var workdir: String = "" // latest launch cwd — for transcript unhide on exit

    override val kind: AgentKind = AgentKind.CLAUDE

    /** Resolve the claude binary lazily — only a launch needs it, so a codex-only machine still runs the
     *  daemon (issue #130) and listing/replay work without claude. A Claude launch with no claude then
     *  fails with a clear PocketError (Conversation's spawn-failure path) instead of crashing the daemon. */
    private fun exe(): Path = resolvedExe ?: resolveExe().also { resolvedExe = it }

    override fun processBuilder(spec: AgentSpec): ProcessBuilder = ClaudeLauncher.processBuilder(exe(), spec, configDir, presetEnv())

    // rename acks in flight (issue #158): request_id -> waiter. Settled by [parse] when the CLI's
    // control_response arrives on the stdout pump; failed on relaunch (the old process took the
    // request to its grave).
    private val pendingRenames = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    override suspend fun attach(io: AgentIo, spec: AgentSpec) {
        this.io = io
        this.workdir = spec.workdir.toString()
        // a relaunch orphans any in-flight rename ack — fail it now instead of riding out the timeout
        pendingRenames.values.forEach { it.complete(false) }
        pendingRenames.clear()
    }

    override suspend fun parse(line: String): List<AgentEvent> {
        // rename_session ack (issue #158): control_response is daemon-side bookkeeping, not a UI event —
        // settle the waiter here and let StreamParser keep reporting the line as Ignored.
        if (pendingRenames.isNotEmpty() && line.contains("\"control_response\"")) settleRenameAck(line)
        return StreamParser.parse(line)
    }

    private fun settleRenameAck(line: String) {
        val root = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return
        if ((root["type"] as? JsonPrimitive)?.contentOrNull != "control_response") return
        val resp = root["response"] as? JsonObject ?: return
        val id = (resp["request_id"] as? JsonPrimitive)?.contentOrNull ?: return
        val waiter = pendingRenames.remove(id) ?: return // an interrupt's ack, or an already-timed-out rename
        val ok = (resp["subtype"] as? JsonPrimitive)?.contentOrNull == "success"
        if (!ok) log.warn("rename_session $id rejected: ${(resp["error"] as? JsonPrimitive)?.contentOrNull}")
        waiter.complete(ok)
    }

    /** issue #158: ask the LIVE CLI to rename its own session. Probed on 2.1.210: `-p` stream-json accepts
     *  `control_request/rename_session {title}`, appends the `custom-title` record through its own writer
     *  (no second appender on the transcript) and THEN acks success — so a true return means the record is
     *  on disk and a rescan sees the new title. False = no live IO, an explicit rejection (e.g. an older
     *  CLI without the subtype), or an ack timeout. */
    override suspend fun renameSession(title: String): Boolean {
        val io = io ?: return false
        val requestId = "pocket-rename-${interruptSeq.getAndIncrement()}"
        val waiter = CompletableDeferred<Boolean>()
        pendingRenames[requestId] = waiter
        val frame = buildJsonObject {
            put("type", "control_request")
            put("request_id", requestId)
            putJsonObject("request") {
                put("subtype", "rename_session")
                put("title", title)
            }
        }
        return try {
            io.writeLine(frame.toString())
            withTimeoutOrNull(RENAME_ACK_TIMEOUT_MS) { waiter.await() } ?: false
        } finally {
            pendingRenames.remove(requestId)
        }
    }

    override suspend fun sendPrompt(text: String, images: List<ImageData>) {
        val io = io ?: return
        val frame = buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                if (images.isEmpty()) {
                    put("content", text)
                } else {
                    putJsonArray("content") {
                        images.forEach { img ->
                            addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", img.mediaType)
                                    put("data", img.base64)
                                }
                            }
                        }
                        if (text.isNotBlank()) addJsonObject { put("type", "text"); put("text", text) }
                    }
                }
            }
        }
        io.writeLine(frame.toString())
    }

    override suspend fun interrupt() {
        val io = io ?: return
        val frame = buildJsonObject {
            put("type", "control_request")
            put("request_id", "pocket-interrupt-${interruptSeq.getAndIncrement()}")
            putJsonObject("request") { put("subtype", "interrupt") }
        }
        io.writeLine(frame.toString())
    }

    override suspend fun respondPermission(
        askId: String,
        allow: Boolean,
        remember: Boolean,
        originalInput: JsonObject?,
        updatedInput: String?,
        denyMessage: String?,
    ) {
        val io = io ?: return
        val frame = if (allow) {
            val updated: JsonElement =
                updatedInput?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                    ?: originalInput
                    ?: JsonObject(emptyMap())
            buildJsonObject {
                put("type", "control_response")
                putJsonObject("response") {
                    put("subtype", "success")
                    put("request_id", askId)
                    putJsonObject("response") {
                        put("behavior", "allow")
                        put("updatedInput", updated)
                    }
                }
            }
        } else {
            buildJsonObject {
                put("type", "control_response")
                putJsonObject("response") {
                    put("subtype", "success")
                    put("request_id", askId)
                    putJsonObject("response") {
                        put("behavior", "deny")
                        put("message", denyMessage ?: "denied")
                    }
                }
            }
        }
        io.writeLine(frame.toString())
    }

    // claude bakes --permission-mode/--model/--effort at launch, so any change needs a relaunch under --resume.
    override fun applySettings(mode: PermissionMode?, model: String?, effort: String?): Boolean = true

    // claude marks `-p` transcripts entrypoint:"sdk-cli" and the desktop --resume picker hides those; once
    // the process is dead the file is safe to rewrite so this session shows up there.
    override suspend fun onProcessEnded(sessionId: String?) {
        val sid = sessionId ?: return
        val file = ProjectPaths.dirFor(workdir).resolve("$sid.jsonl")
        if (TranscriptPatcher.unhide(file)) log.info("transcript unhidden for desktop resume: $sid")
    }

    // journal the spawn so a daemon that dies before onProcessEnded can still unhide this transcript
    // at its next boot (issue #70 — crash-stranded sessions were invisible to the pickers forever)
    override suspend fun onSessionStarted(sessionId: String, workdir: String) {
        SpawnedSessions.note(workdir, sessionId)
    }

    override fun transcriptDir(workdir: String): Path = ProjectPaths.dirFor(workdir)

    override fun listSessions(workdir: String): List<SessionSummary> =
        TranscriptScanner.scan(ProjectPaths.dirFor(workdir)).map { it.copy(agent = AgentKind.CLAUDE) }

    override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> =
        TranscriptReplay.read(ProjectPaths.dirFor(workdir).resolve("$sessionId.jsonl"))

    // incremental reattach + older-history paging (issue #147) — seq = the transcript's source line
    override fun replaySlice(workdir: String, sessionId: String, sinceSeq: Long?): dev.ccpocket.daemon.disk.ReplaySlice =
        TranscriptReplay.slice(ProjectPaths.dirFor(workdir).resolve("$sessionId.jsonl"), sinceSeq)

    override fun replayPage(workdir: String, sessionId: String, beforeSeq: Long, limit: Int): dev.ccpocket.daemon.disk.ReplaySlice =
        TranscriptReplay.page(ProjectPaths.dirFor(workdir).resolve("$sessionId.jsonl"), beforeSeq, limit)

    override fun resumeContextTokens(workdir: String, sessionId: String): Long? =
        TranscriptScanner.lastContextTokens(ProjectPaths.dirFor(workdir).resolve("$sessionId.jsonl"))

    override fun resumeModel(workdir: String, sessionId: String): String? =
        TranscriptScanner.lastModel(ProjectPaths.dirFor(workdir).resolve("$sessionId.jsonl"))

    // issue #96: read the configured default (settings.json `model` / $ANTHROPIC_MODEL) so a brand-new
    // session's header shows the real model before the first turn. configDir = the daemon's isolated
    // CLAUDE_CONFIG_DIR when credential isolation is on (settings.json is symlinked back to the real one).
    // An active preset's ANTHROPIC_MODEL wins — that's what the launch will actually inject (issue #113).
    override fun defaultModel(workdir: String): String? =
        presetEnv()?.get(PresetEnv.MODEL) ?: ClaudeDefaultModel.resolve(workdir, configDir)

    override fun resumeFailedTurnStreak(workdir: String, sessionId: String): Int =
        TranscriptScanner.syntheticTailStreak(ProjectPaths.dirFor(workdir).resolve("$sessionId.jsonl"))

    private companion object {
        // the CLI appends + acks in one tick; generous so a mid-turn rename survives a busy stdout,
        // bounded so a wedged process fails the rename honestly instead of hanging the router job
        const val RENAME_ACK_TIMEOUT_MS = 10_000L
    }
}
