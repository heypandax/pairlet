package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.claude.ClaudeEvent
import dev.ccpocket.daemon.claude.ClaudeLauncher
import dev.ccpocket.daemon.claude.ClaudeProcess
import dev.ccpocket.daemon.claude.ClaudeSpec
import dev.ccpocket.daemon.claude.PermissionBridge
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.daemon.disk.TranscriptReplay
import dev.ccpocket.daemon.util.logger
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
import dev.ccpocket.protocol.ImageData
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
    initialMode: PermissionMode,
    initialSink: OutboundSink,
    parentScope: CoroutineScope,
    private val claudeExe: Path,
) {
    // mutable: a phone can switch the permission mode mid-session (relaunches claude under --resume)
    @Volatile
    private var mode: PermissionMode = initialMode

    // session "Always allow" scopes; survives a mode-switch relaunch (the bridge is recreated, this isn't)
    private val allowRules: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob() + CoroutineName("convo-$convoId"),
    )
    private val log = logger("Convo")

    // the device sink is re-pointed when a phone re-opens a session that kept running in the background
    @Volatile
    private var sink: OutboundSink = initialSink

    /** Wall-clock of the last claude activity — drives the daemon's idle reaper. */
    @Volatile
    var lastActivityMs: Long = System.currentTimeMillis()
        private set

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

    // set when a mode switch relaunches the process: re-announce SessionLive on the next init so the phone clears "switching"
    @Volatile
    private var reemitLive = false

    fun open(resumeId: String?, model: String?) {
        pendingResumeId = resumeId // replay this session's transcript once it goes live
        launchProcess(ClaudeSpec(workdir, resumeId, model, mode))
    }

    /** Relaunch claude resuming the same session under a new permission mode. Keeps allow-rules + history. */
    suspend fun switchMode(newMode: PermissionMode) {
        if (newMode == mode || sessionId == null) { mode = newMode; return }
        mode = newMode
        reemitLive = true // signal the phone that the switch landed (sessionId is unchanged, so pump won't otherwise emit)
        val sid = sessionId
        stopProcess()
        launchProcess(ClaudeSpec(workdir, resumeId = sid, model = null, mode = newMode)) // no pendingResumeId: don't re-replay history
    }

    fun clearAllowRule(rule: String?) {
        if (rule == null) allowRules.clear() else allowRules.remove(rule)
    }

    private fun launchProcess(spec: ClaudeSpec) {
        intentionalStop = false
        val p = ClaudeProcess.start(ClaudeLauncher.processBuilder(claudeExe, spec), scope)
        val b = PermissionBridge(convoId, mode, scope, p::writeLine, { sink.emit(it) }, allowRules) // read sink dynamically (reattach)
        proc = p
        bridge = b
        scope.launch(CoroutineName("pump-$convoId")) { pump(p, b) }
    }

    private suspend fun pump(p: ClaudeProcess, b: PermissionBridge) {
        for (line in p.stdout) {
            lastActivityMs = System.currentTimeMillis()
            for (ev in StreamParser.parse(line)) {
                when (ev) {
                    is ClaudeEvent.SessionInit -> {
                        val firstTime = sessionId == null
                        ev.sessionId?.let { sessionId = it }
                        if (firstTime && sessionId != null) {
                            log.info("$convoId session live: $sessionId")
                            sink.emit(SessionLive(convoId, workdir.toString(), sessionId))
                            pendingResumeId?.let { rid ->
                                pendingResumeId = null
                                val file = ProjectPaths.dirFor(workdir.toString()).resolve("$rid.jsonl")
                                val history = TranscriptReplay.read(file)
                                if (history.isNotEmpty()) sink.emit(ConvoHistory(convoId, history))
                            }
                        } else if (reemitLive && sessionId != null) {
                            reemitLive = false // mode switch relaunch landed — tell the phone to drop "switching"
                            sink.emit(SessionLive(convoId, workdir.toString(), sessionId))
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
        log.info("$convoId pump ended (intentionalStop=$intentionalStop)")
        if (!intentionalStop) {
            sink.emit(PocketError("process_exited", "claude process ended", convoId))
        }
    }

    /** Re-point this live conversation to a re-opened device, replaying its transcript so far. */
    suspend fun reattach(newSink: OutboundSink) {
        sink = newSink
        lastActivityMs = System.currentTimeMillis()
        val sid = sessionId ?: return
        newSink.emit(SessionLive(convoId, workdir.toString(), sid))
        val file = ProjectPaths.dirFor(workdir.toString()).resolve("$sid.jsonl")
        val history = TranscriptReplay.read(file)
        if (history.isNotEmpty()) newSink.emit(ConvoHistory(convoId, history))
    }

    suspend fun sendPrompt(text: String, images: List<ImageData> = emptyList()) {
        val p = proc ?: return
        lastActivityMs = System.currentTimeMillis()
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
