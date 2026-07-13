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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
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

    override suspend fun attach(io: AgentIo, spec: AgentSpec) {
        this.io = io
        this.workdir = spec.workdir.toString()
    }

    override suspend fun parse(line: String): List<AgentEvent> = StreamParser.parse(line)

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
}
