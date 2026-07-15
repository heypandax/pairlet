package dev.ccpocket.daemon.opencode

import dev.ccpocket.protocol.TokenUsage
import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.transcript.OpenCodeTranscriptWriter
import dev.ccpocket.daemon.transcript.TranscriptWriter
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionSummary
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives OpenCode via `opencode run --format json` — newline-delimited JSON over stdout.
 * Stateless per-launch (like Claude): all flags are baked into the CLI args, no handshake needed.
 * Session init comes from the first `step_start` event's `sessionID`. Approvals are auto-approved
 * (--auto) since OpenCode has no interactive permission protocol.
 */
class OpenCodeBackend(private val opencodeBin: String?) : AgentBackend {
    private val log = logger("OpenCodeBackend")

    @Volatile private var io: AgentIo? = null
    @Volatile private var resolvedExe: Path? = null
    @Volatile private var workdir: String = ""
    @Volatile private var resumeId: String? = null
    @Volatile private var mode: PermissionMode = PermissionMode.DEFAULT
    @Volatile private var model: String? = null
    @Volatile private var effort: String? = null

    @Volatile private var sessionId: String? = null

    // Turn running state
    @Volatile private var lastAgentText: String? = null
    @Volatile private var lastUsage: TokenUsage? = null
    private val deltaSeen = ConcurrentHashMap.newKeySet<String>()

    override val kind: AgentKind = AgentKind.OPENCODE

    override fun processBuilder(spec: AgentSpec): ProcessBuilder = OpenCodeLauncher.processBuilder(exe(), spec)

    private fun exe(): Path = resolvedExe ?: OpenCodeLauncher.resolveExecutable(opencodeBin).also { resolvedExe = it }

    override suspend fun attach(io: AgentIo, spec: AgentSpec) {
        this.io = io
        this.workdir = spec.workdir.toString()
        this.resumeId = spec.resumeId
        this.mode = spec.mode
        this.model = spec.model
        this.effort = spec.effort
        // reset per-process state
        sessionId = null
        lastAgentText = null; lastUsage = null
        deltaSeen.clear()
        // OpenCode is stateless — no handshake. SessionInit comes from the first step_start event.
    }

    override suspend fun parse(line: String): List<AgentEvent> {
        val events = OpenCodeStreamParser.parse(line)
        return events.flatMap { event ->
            when (event) {
                is AgentEvent.SessionInit -> {
                    // Capture session ID from first step_start
                    if (sessionId == null && event.sessionId != null) {
                        sessionId = event.sessionId
                    }
                    listOf(event)
                }
                is AgentEvent.AssistantText -> {
                    lastAgentText = (lastAgentText ?: "") + event.text
                    listOf(event)
                }
                is AgentEvent.AssistantToolUse -> {
                    // Tool use events pass through directly
                    listOf(event)
                }
                is AgentEvent.ToolResult -> {
                    listOf(event)
                }
                is AgentEvent.TurnResult -> {
                    // Merge our tracked usage into the turn result
                    val ev = event.copy(
                        usage = lastUsage ?: event.usage,
                        isError = event.isError,
                    )
                    lastAgentText = null
                    lastUsage = null
                    deltaSeen.clear()
                    listOf(ev)
                }
                is AgentEvent.AssistantThinking, is AgentEvent.Unparseable, is AgentEvent.Ignored -> listOf(event)
                else -> listOf(event)
            }
        }
    }

    override suspend fun sendPrompt(text: String, images: List<ImageData>) {
        // OpenCode is one-shot per process: the prompt is passed via CLI args in processBuilder().
        // `opencode run [message]` doesn't read stdin for follow-up prompts.
        // Follow-up turns rely on Conversation's relaunch mechanism: when the process exits,
        // proc becomes null, and the next sendPrompt() triggers a fresh launch with --session <id>.
        // This method is a no-op — the initial prompt is in argv, follow-ups trigger relaunches.
    }

    private suspend fun sendPromptDirect(text: String, images: List<ImageData>) {
        // OpenCode run mode: write the prompt directly as a command-line argument (already in processBuilder).
        // For interactive follow-up prompts, we'd need stdin writing, but `opencode run` is one-shot.
        // The prompt is passed via CLI args in the initial launch.
        // For subsequent prompts in a session, we'd need to use `--session <id>` with a new `opencode run`.
        // For MVP: single-turn per process. Multi-turn requires re-launch with --session.
        io?.writeLine(text)
    }

    override suspend fun interrupt() {
        // OpenCode run mode doesn't support interrupt. No-op.
        log.info("interrupt requested but opencode run mode doesn't support it")
    }

    override suspend fun respondPermission(
        askId: String,
        allow: Boolean,
        remember: Boolean,
        originalInput: JsonObject?,
        updatedInput: String?,
        denyMessage: String?,
    ) {
        // All permissions are auto-approved (--auto flag). This method should never be called
        // since we don't emit ControlRequest events. Log if it somehow is.
        log.warn("respondPermission called but opencode uses --auto mode (no approval protocol)")
    }

    // OpenCode bakes flags at launch → relaunch needed for model/mode/effort changes
    override fun applySettings(mode: PermissionMode?, model: String?, effort: String?): Boolean {
        mode?.let { this.mode = it }
        model?.let { this.model = it }
        effort?.let { this.effort = it }
        return true // relaunch needed since all flags are baked at launch
    }

    override suspend fun onProcessEnded(sessionId: String?) {}

    override fun createTranscriptWriter(workdir: String): TranscriptWriter? = OpenCodeTranscriptWriter()

    // ---- disk: transcript scanning + replay ----

    override fun transcriptDir(workdir: String): Path = OpenCodePaths.dataRoot()
    override fun listSessions(workdir: String): List<SessionSummary> = OpenCodeTranscriptScanner.scan(workdir)
    override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> =
        OpenCodeTranscriptReplay.read(sessionId)

    override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null

    override fun defaultModel(workdir: String): String? = OpenCodeDefaultModel.resolve(workdir)
}
