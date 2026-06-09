package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.claude.ClaudeEvent
import dev.ccpocket.daemon.claude.ClaudeLauncher
import dev.ccpocket.daemon.claude.ClaudeProcess
import dev.ccpocket.daemon.claude.ClaudeSpec
import dev.ccpocket.daemon.claude.PermissionBridge
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.daemon.disk.TranscriptReplay
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.TokenUsage
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * One live conversation: glues a [ClaudeProcess] + [StreamParser] + [PermissionBridge] to an
 * [OutboundSink]. Owns its own scope; a single stdout pump assigns the monotonic `seq` (no locks).
 */
class Conversation(
    val convoId: String,
    initialWorkdir: Path,
    private val mode: PermissionMode,
    private val sink: OutboundSink,
    parentScope: CoroutineScope,
    private val claudeExe: Path,
) {
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob() + CoroutineName("convo-$convoId"),
    )

    @Volatile
    var workdir: Path = initialWorkdir
        private set

    @Volatile
    var sessionId: String? = null
        private set

    private var proc: ClaudeProcess? = null
    private var bridge: PermissionBridge? = null
    private val seq = AtomicLong(0)

    @Volatile
    private var intentionalStop = false

    @Volatile
    private var pendingResumeId: String? = null

    fun open(resumeId: String?, model: String?) {
        pendingResumeId = resumeId // replay this session's transcript once it goes live
        launchProcess(ClaudeSpec(workdir, resumeId, model, mode))
    }

    private fun launchProcess(spec: ClaudeSpec) {
        intentionalStop = false
        val p = ClaudeProcess.start(ClaudeLauncher.processBuilder(claudeExe, spec), scope)
        val b = PermissionBridge(convoId, mode, scope, p::writeLine, sink::emit)
        proc = p
        bridge = b
        scope.launch(CoroutineName("pump-$convoId")) { pump(p, b) }
    }

    private suspend fun pump(p: ClaudeProcess, b: PermissionBridge) {
        for (line in p.stdout) {
            for (ev in StreamParser.parse(line)) {
                when (ev) {
                    is ClaudeEvent.SessionInit -> {
                        val firstTime = sessionId == null
                        ev.sessionId?.let { sessionId = it }
                        if (firstTime && sessionId != null) {
                            sink.emit(SessionLive(convoId, workdir.toString(), sessionId))
                            pendingResumeId?.let { rid ->
                                pendingResumeId = null
                                val file = ProjectPaths.dirFor(workdir.toString()).resolve("$rid.jsonl")
                                val history = TranscriptReplay.read(file)
                                if (history.isNotEmpty()) sink.emit(ConvoHistory(convoId, history))
                            }
                        }
                    }
                    is ClaudeEvent.AssistantText ->
                        sink.emit(AssistantChunk(convoId, seq.getAndIncrement(), StreamPiece.Text(ev.text)))
                    is ClaudeEvent.AssistantThinking ->
                        sink.emit(AssistantChunk(convoId, seq.getAndIncrement(), StreamPiece.Thinking(ev.text)))
                    is ClaudeEvent.AssistantToolUse ->
                        sink.emit(
                            ToolEvent(convoId, seq.getAndIncrement(), ToolPhase.START, ev.name, ev.input?.toString()?.take(280)),
                        )
                    is ClaudeEvent.TurnResult ->
                        sink.emit(
                            TurnDone(
                                convoId,
                                ev.finalText,
                                TokenUsage(ev.inputTokens, ev.outputTokens, ev.cacheCreationInputTokens, ev.cacheReadInputTokens),
                            ),
                        )
                    is ClaudeEvent.ControlRequest -> b.onControlRequest(ev)
                    is ClaudeEvent.ControlCancel -> b.onCancel(ev)
                    ClaudeEvent.UserReplay -> {}
                    is ClaudeEvent.Ignored -> {}
                    is ClaudeEvent.Unparseable -> {}
                }
            }
        }
        if (!intentionalStop) {
            sink.emit(PocketError("process_exited", "claude process ended", convoId))
        }
    }

    suspend fun sendPrompt(text: String) {
        val p = proc ?: return
        val frame = buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                put("content", text)
            }
        }
        p.writeLine(frame.toString())
    }

    suspend fun submitVerdict(v: PermissionVerdict) {
        bridge?.onVerdict(v)
    }

    /** Default semantics: kill the current process tree and start a fresh session in the new cwd. */
    suspend fun switchDirectory(newWorkdir: Path) {
        stopProcess()
        workdir = newWorkdir
        sessionId = null
        launchProcess(ClaudeSpec(workdir, resumeId = null, model = null, mode = mode))
    }

    private suspend fun stopProcess() {
        intentionalStop = true
        bridge?.cancelAll()
        proc?.shutdown()
        proc = null
        bridge = null
    }

    suspend fun close() {
        stopProcess()
        scope.cancel()
    }
}
