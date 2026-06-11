package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.claude.ClaudeEvent
import dev.ccpocket.daemon.claude.ClaudeLauncher
import dev.ccpocket.daemon.claude.ClaudeProcess
import dev.ccpocket.daemon.claude.ClaudeSpec
import dev.ccpocket.daemon.claude.PermissionBridge
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.daemon.disk.SlashCommandScanner
import dev.ccpocket.daemon.disk.TranscriptPatcher
import dev.ccpocket.daemon.disk.TranscriptReplay
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.CommandList
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

    // mutable: a phone can switch the model mid-session via `/model <name>` (also relaunches under --resume)
    @Volatile
    private var model: String? = null

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

    // the resumeId this conversation was opened with — the relaunch anchor while sessionId is still
    // null (claude emits nothing, init included, until the first turn lands). Without it, a
    // pre-first-turn mode switch on a resumed/taken-over terminal session would relaunch blank
    // and orphan that session's history.
    @Volatile
    private var openedResumeId: String? = null

    // set when a mode switch relaunches the process: re-announce SessionLive on the next init so the phone clears "switching"
    @Volatile
    private var reemitLive = false

    fun open(resumeId: String?, model: String?) {
        this.model = model
        this.openedResumeId = resumeId
        launchProcess(ClaudeSpec(workdir, resumeId, model, mode))
        // claude in `--input-format stream-json` emits NOTHING (not even the system/init that would
        // drive SessionLive) until the first user turn lands on stdin. But the phone needs convoId —
        // carried by SessionLive — before it can send that first turn. Waiting for claude's init here
        // would deadlock. So announce the session as live now (we own convoId + workdir), and replay
        // the resumed transcript up front; the pump later re-emits SessionLive with the real sessionId.
        scope.launch {
            sink.emit(SessionLive(convoId, workdir.toString(), resumeId, mode = mode))
            if (resumeId != null) {
                val history = TranscriptReplay.read(ProjectPaths.dirFor(workdir.toString()).resolve("$resumeId.jsonl"))
                if (history.isNotEmpty()) sink.emit(ConvoHistory(convoId, history))
            }
            emitCommands()
        }
    }

    /** Tell the phone which slash commands its composer can autocomplete (workdir-dependent). */
    private suspend fun emitCommands() {
        sink.emit(CommandList(convoId, SlashCommandScanner.scan(workdir)))
    }

    /** Relaunch claude resuming the same session under a new permission mode. Keeps allow-rules + history. */
    suspend fun switchMode(newMode: PermissionMode) {
        if (newMode == mode) {
            // no-op, but still announce: an out-of-sync phone badge corrects itself from this
            sink.emit(SessionLive(convoId, workdir.toString(), sessionId, mode = mode))
            return
        }
        mode = newMode
        reemitLive = true // the next init re-announces too — it carries the post-resume sessionId
        // pre-first-turn claude has reported no sessionId yet: fall back to the id this conversation
        // was opened with, so a resumed/taken-over terminal session keeps its history (null = brand
        // new session — nothing happened yet, a fresh start loses nothing)
        val sid = sessionId ?: openedResumeId
        stopProcess()
        launchProcess(ClaudeSpec(workdir, resumeId = sid, model = model, mode = newMode)) // no pendingResumeId: don't re-replay history
        sink.emit(SessionLive(convoId, workdir.toString(), sid, mode = mode)) // confirm now — init won't arrive until the next turn
    }

    /**
     * Relaunch claude on a different model, resuming the same session (claude `-p` ignores the interactive
     * `/model` command, so the daemon honors it by re-spawning with `--model`). Keeps mode + allow-rules.
     */
    suspend fun switchModel(newModel: String?) {
        model = newModel
        val sid = sessionId
        stopProcess()
        launchProcess(ClaudeSpec(workdir, resumeId = sid, model = newModel, mode = mode))
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
                            reemitLive = false // this announce already carries the fresh sessionId + mode
                            log.info("$convoId session live: $sessionId")
                            sink.emit(SessionLive(convoId, workdir.toString(), sessionId, mode = mode))
                            pendingResumeId?.let { rid ->
                                pendingResumeId = null
                                val file = ProjectPaths.dirFor(workdir.toString()).resolve("$rid.jsonl")
                                val history = TranscriptReplay.read(file)
                                if (history.isNotEmpty()) sink.emit(ConvoHistory(convoId, history))
                            }
                        } else if (reemitLive && sessionId != null) {
                            reemitLive = false // mode switch relaunch landed — refresh the phone's sessionId
                            sink.emit(SessionLive(convoId, workdir.toString(), sessionId, mode = mode))
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
            // unexpected death: stdout EOF precedes the last transcript flush, so wait for the
            // real process exit before touching the file (intentional stops patch in stopProcess)
            p.awaitExit()
            unhideTranscript()
            sink.emit(PocketError("process_exited", "claude process ended", convoId))
        }
    }

    // claude marks `-p` transcripts `entrypoint:"sdk-cli"` and the desktop `--resume` picker hides
    // those; once our process is dead the file is safe to rewrite so this session shows up there
    private fun unhideTranscript() {
        val sid = sessionId ?: return
        val file = ProjectPaths.dirFor(workdir.toString()).resolve("$sid.jsonl")
        if (TranscriptPatcher.unhide(file)) log.info("$convoId transcript unhidden for desktop resume: $sid")
    }

    /** Re-point this live conversation to a re-opened device, replaying its transcript so far. */
    suspend fun reattach(newSink: OutboundSink) {
        sink = newSink
        lastActivityMs = System.currentTimeMillis()
        val sid = sessionId ?: return
        newSink.emit(SessionLive(convoId, workdir.toString(), sid, mode = mode))
        val file = ProjectPaths.dirFor(workdir.toString()).resolve("$sid.jsonl")
        val history = TranscriptReplay.read(file)
        if (history.isNotEmpty()) newSink.emit(ConvoHistory(convoId, history))
        emitCommands()
    }

    suspend fun sendPrompt(text: String, images: List<ImageData> = emptyList()) {
        if (text.trimStart().startsWith("/model")) { handleModelCommand(text.trim()); return }
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

    /** Handle the phone's `/model [name]` — claude `-p` ignores it, so the daemon relaunches with `--model`. */
    private suspend fun handleModelCommand(text: String) {
        val arg = text.removePrefix("/model").trim()
        if (arg.isEmpty()) {
            reply("Current model: ${model ?: "default"}.\nUsage: /model <name> — e.g. /model opus, /model sonnet, /model haiku (or a full model id).")
            return
        }
        switchModel(arg)
        reply("✓ Model switched to \"$arg\" for this session. Your next message will use it.")
    }

    /** Emit a daemon-side message to the phone as a complete assistant turn (used by slash commands). */
    private suspend fun reply(msg: String) {
        sink.emit(AssistantChunk(convoId, seq.getAndIncrement(), StreamPiece.Text(msg)))
        sink.emit(TurnDone(convoId, msg, null))
    }

    suspend fun submitVerdict(v: PermissionVerdict) {
        bridge?.onVerdict(v)
    }

    /**
     * Interrupt the in-flight turn (phone composer ■). Uses the stream-json control protocol —
     * claude aborts the turn and emits its result; the session and process stay alive.
     */
    suspend fun cancelTurn() {
        val p = proc ?: return
        lastActivityMs = System.currentTimeMillis()
        val frame = buildJsonObject {
            put("type", "control_request")
            put("request_id", "pocket-interrupt-${seq.getAndIncrement()}")
            putJsonObject("request") { put("subtype", "interrupt") }
        }
        p.writeLine(frame.toString())
    }

    /** Default semantics: kill the current process tree and start a fresh session in the new cwd. */
    suspend fun switchDirectory(newWorkdir: Path) {
        stopProcess()
        workdir = newWorkdir
        sessionId = null
        openedResumeId = null // fresh session in the new cwd — no resume lineage left to preserve
        launchProcess(ClaudeSpec(workdir, resumeId = null, model = null, mode = mode))
        emitCommands() // project commands differ per workdir
    }

    private suspend fun stopProcess() {
        intentionalStop = true
        bridge?.cancelAll()
        proc?.shutdown() // waits for real exit (force-kill fallback) — file is quiet after this
        proc = null
        bridge = null
        unhideTranscript()
    }

    suspend fun close() {
        stopProcess()
        scope.cancel()
    }
}
