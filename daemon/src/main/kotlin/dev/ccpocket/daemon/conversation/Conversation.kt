package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentProcess
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.agent.PermissionBridge
import dev.ccpocket.daemon.agent.ToolMetadata
import dev.ccpocket.daemon.disk.SlashCommandScanner
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.BackgroundJobs
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
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * One live conversation: glues an [AgentBackend] (Claude / Codex) to an [OutboundSink]. Owns its own
 * scope; a single stdout pump assigns the monotonic `seq` (no locks). Agent-agnostic — every provider
 * specific (wire schema, prompt/interrupt/approval encoding, transcript layout) lives behind [backend].
 */
class Conversation(
    val convoId: String,
    initialWorkdir: Path,
    initialMode: PermissionMode,
    initialSink: OutboundSink,
    parentScope: CoroutineScope,
    private val backend: AgentBackend,
    // read dynamically: the relay client installs the hook after this conversation may already exist
    private val pushHookProvider: () -> PushHook? = { null },
) {
    // mutable: a phone can switch the permission mode mid-session (Claude relaunches; Codex applies next turn)
    @Volatile
    private var mode: PermissionMode = initialMode

    // mutable: a phone can switch the model mid-session via `/model <name>`
    @Volatile
    private var model: String? = null

    // mutable: a phone can switch reasoning effort mid-session via `/effort <level>`
    @Volatile
    private var effort: String? = null

    // session "Always allow" scopes; survives a mode-switch relaunch (the bridge is recreated, this isn't)
    private val allowRules: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob() + CoroutineName("convo-$convoId"),
    )
    private val log = logger("Convo")

    // the device sink is re-pointed when a phone re-opens a session that kept running in the background
    @Volatile
    private var sink: OutboundSink = initialSink

    /** Wall-clock of the last agent activity — drives the daemon's idle reaper. */
    @Volatile
    var lastActivityMs: Long = System.currentTimeMillis()
        private set

    @Volatile
    var workdir: Path = initialWorkdir
        private set

    @Volatile
    var sessionId: String? = null
        private set

    private var proc: AgentProcess? = null
    private var bridge: PermissionBridge? = null
    private val seq = AtomicLong(0)

    // background work (bg shells / sub-agents / monitors) tracked from the tool stream; drives the in-chat
    // jobs indicator and keeps the session "busy" (un-reapable) while anything is still running
    private val jobs = BackgroundJobRegistry()

    // a user turn is in flight (prompt written, TurnDone not yet seen) — SessionLive carries it so
    // a (re)attaching phone can reset its ■/streaming state instead of trusting a stale local value
    @Volatile
    private var executing = false

    @Volatile
    private var intentionalStop = false

    @Volatile
    private var pendingResumeId: String? = null

    // the resumeId this conversation was opened with — the relaunch anchor while sessionId is still
    // null (the agent emits nothing, init included, until the first turn lands). Without it, a
    // pre-first-turn mode switch on a resumed/taken-over terminal session would relaunch blank
    // and orphan that session's history.
    @Volatile
    private var openedResumeId: String? = null

    // set when a mode switch relaunches the process: re-announce SessionLive on the next init so the phone clears "switching"
    @Volatile
    private var reemitLive = false

    // context tokens the resumed transcript's last turn left in the window — seeds the phone's usage
    // statusline before the first new turn lands. Null for a brand-new session (nothing used yet).
    @Volatile
    private var resumeContextUsed: Long? = null

    /** The announce frame, stamped with everything mutable the phone reconciles from (mode, executing, model, effort, agent). */
    private fun live(sid: String?) =
        SessionLive(convoId, workdir.toString(), sid, mode = mode, executing = executing, model = model, effort = effort, contextUsed = resumeContextUsed, agent = backend.kind)

    /** The current permission mode — read by the shell approval gate so it can't be spoofed from the phone. */
    fun currentMode(): PermissionMode = mode

    fun open(resumeId: String?, model: String?, effort: String? = null, fork: Boolean = false) {
        this.model = model
        this.effort = effort // restore the session's last reasoning effort on a fresh resume (transcript doesn't carry it)
        this.openedResumeId = resumeId
        // Resume in-place by default — appending to the resumed transcript. The registry already ruled out the
        // dangerous concurrent-writer cases before we get here: a live daemon session reattaches, and an
        // externally-active Claude transcript routes to a read-only ObserveSession. Forking unconditionally (the
        // old behavior) minted a new .jsonl on every phone re-entry of an idle session, cluttering the list with
        // duplicates (issue #12). We DO still fork for an explicit take-over ([fork] == OpenSession.takeOver):
        // that path deliberately bypasses the ObserveSession guard, so a desktop `claude --resume` may still be
        // writing — branching into a fresh id (carrying full history) leaves the original .jsonl untouched.
        // Codex ignores forkSession. No-op when resumeId is null (a brand-new session).
        launchProcess(AgentSpec(workdir, resumeId, model, mode, effort = effort, forkSession = fork))
        // a headless agent (claude `--input-format stream-json`; codex pre-thread) emits NOTHING — not even the
        // init that would drive SessionLive — until the first user turn / handshake lands. But the phone needs
        // convoId (carried by SessionLive) before it can send that first turn. So announce the session as live
        // now (we own convoId + workdir), and replay the resumed transcript up front; the pump later re-emits
        // SessionLive with the real sessionId.
        scope.launch {
            // Seed the usage statusline from the resumed transcript's last turn so it shows on open
            // (before the first new TurnDone). Done here, off the relay inbound loop — the transcript
            // read can be a multi-MB parse. Null for a brand-new session / no prior usage.
            resumeContextUsed = resumeId?.let { runCatching { backend.resumeContextTokens(workdir.toString(), it) }.getOrNull() }
            sink.emit(live(resumeId))
            if (resumeId != null) {
                val history = backend.replayHistory(workdir.toString(), resumeId)
                if (history.isNotEmpty()) sink.emit(ConvoHistory(convoId, history))
            }
            emitCommands()
        }
    }

    /** Tell the phone which slash commands its composer can autocomplete (workdir-dependent). */
    private suspend fun emitCommands() {
        sink.emit(CommandList(convoId, SlashCommandScanner.scan(workdir)))
    }

    /** Push the current background-job snapshot to the phone. A job-state change also counts as activity. */
    private suspend fun emitJobs() {
        lastActivityMs = System.currentTimeMillis()
        sink.emit(BackgroundJobs(convoId, jobs.snapshot()))
    }

    /** True while any background job is still RUNNING — the daemon's idle reaper must not reap such a session. */
    fun hasBackgroundWork(): Boolean = jobs.hasRunning()

    /**
     * Settle background jobs stuck RUNNING with no update for [staleMs] (a completion event that never came),
     * pushing the refreshed snapshot to the phone. Driven by the daemon's periodic reaper so a forever-RUNNING
     * count clears even with no stream activity. Returns true if anything was reaped.
     */
    suspend fun reapStaleJobs(staleMs: Long): Boolean {
        if (!jobs.hasRunning()) return false // idle conversation: nothing RUNNING to settle, skip the clock+scan
        val changed = jobs.reapStale(System.currentTimeMillis(), staleMs)
        if (changed) sink.emit(BackgroundJobs(convoId, jobs.snapshot()))
        return changed
    }

    /**
     * The relaunch primitive: stop the agent and re-spawn it resuming [resumeId], rebuilding the spec from the
     * live `model`/`mode`/`effort` fields. Used by the Claude path (which bakes flags at launch); Codex applies
     * settings to the next turn and skips relaunch. No pendingResumeId: a resume relaunch must not re-replay history.
     *
     * Fork only when [resumeId] is NOT our own materialized [sessionId] (Claude take-over case; see [open]).
     */
    private suspend fun relaunch(resumeId: String? = sessionId) {
        stopProcess()
        launchProcess(
            AgentSpec(workdir, resumeId = resumeId, model = model, mode = mode, effort = effort, forkSession = resumeId != sessionId),
        )
    }

    /** Switch the permission mode. Claude relaunches under --resume + the new mode; Codex applies it next turn. */
    suspend fun switchMode(newMode: PermissionMode) {
        if (newMode == mode) {
            // no-op, but still announce: an out-of-sync phone badge corrects itself from this
            sink.emit(live(sessionId))
            return
        }
        mode = newMode
        if (!backend.applySettings(mode = newMode, model = null, effort = null)) {
            // Codex: no relaunch; the next turn/start carries the new approval policy
            sink.emit(live(sessionId))
            return
        }
        reemitLive = true // the next init re-announces too — it carries the post-resume sessionId
        // pre-first-turn the agent has reported no sessionId yet: fall back to the id this conversation
        // was opened with, so a resumed/taken-over terminal session keeps its history (null = brand
        // new session — nothing happened yet, a fresh start loses nothing)
        val sid = sessionId ?: openedResumeId
        relaunch(sid)
        // the relaunch killed any in-flight turn — executing is false again, the phone's ■ resets
        sink.emit(live(sid)) // confirm now — init won't arrive until the next turn
    }

    /** Switch the model. Claude relaunches under --model; Codex applies it to the next turn. */
    suspend fun switchModel(newModel: String?) {
        model = newModel
        if (backend.applySettings(mode = null, model = newModel, effort = null)) relaunch() else sink.emit(live(sessionId))
    }

    /** Switch reasoning effort. Claude relaunches under --effort; Codex applies it to the next turn. */
    suspend fun switchEffort(newEffort: String?) {
        effort = newEffort
        if (backend.applySettings(mode = null, model = null, effort = newEffort)) relaunch() else sink.emit(live(sessionId))
    }

    fun clearAllowRule(rule: String?) {
        if (rule == null) allowRules.clear() else allowRules.remove(rule)
    }

    private fun launchProcess(spec: AgentSpec) {
        intentionalStop = false
        val p = AgentProcess.start(backend.processBuilder(spec), scope)
        val io = AgentIo(writeLine = p::writeLine, emit = { sink.emit(it) }) // read sink dynamically (reattach)
        val b = PermissionBridge(convoId, mode, scope, { sink.emit(it) }, allowRules, respond = backend::respondPermission)
        proc = p
        bridge = b
        scope.launch(CoroutineName("pump-$convoId")) {
            backend.attach(io, spec) // bind IO + run any handshake (Codex initialize/thread-start) before reading
            pump(p, b)
        }
    }

    private suspend fun pump(p: AgentProcess, b: PermissionBridge) {
        for (line in p.stdout) {
            lastActivityMs = System.currentTimeMillis()
            for (ev in backend.parse(line)) {
                when (ev) {
                    is AgentEvent.SessionInit -> {
                        val firstTime = sessionId == null
                        ev.sessionId?.let { sessionId = it }
                        ev.model?.let { model = it } // the agent's resolved model (real name even when opened with model=null)
                        if (firstTime && sessionId != null) {
                            reemitLive = false // this announce already carries the fresh sessionId + mode
                            log.info("$convoId session live: $sessionId")
                            sink.emit(live(sessionId))
                            pendingResumeId?.let { rid ->
                                pendingResumeId = null
                                val history = backend.replayHistory(workdir.toString(), rid)
                                if (history.isNotEmpty()) sink.emit(ConvoHistory(convoId, history))
                            }
                        } else if (reemitLive && sessionId != null) {
                            reemitLive = false // mode switch relaunch landed — refresh the phone's sessionId
                            sink.emit(live(sessionId))
                        }
                    }
                    is AgentEvent.AssistantText ->
                        sink.emit(AssistantChunk(convoId, seq.getAndIncrement(), StreamPiece.Text(ev.text)))
                    is AgentEvent.AssistantThinking ->
                        sink.emit(AssistantChunk(convoId, seq.getAndIncrement(), StreamPiece.Thinking(ev.text)))
                    is AgentEvent.AssistantToolUse -> {
                        // ExitPlanMode's input IS the proposed plan (input["plan"]) — surface it in full via the
                        // shared ToolMetadata extractor so the plan is readable in the phone's chat, not truncated
                        // to 280 chars of raw JSON (issue #10). Other tools keep the compact JSON preview.
                        val preview =
                            if (ev.name == "ExitPlanMode" || ev.name == "exit_plan_mode") ToolMetadata.of(ev.name, ev.input).preview
                            else ev.input?.toString()?.take(280)
                        sink.emit(
                            ToolEvent(convoId, seq.getAndIncrement(), ToolPhase.START, ev.name, preview),
                        )
                        if (jobs.onToolUse(ev.id, ev.name, ev.input, System.currentTimeMillis())) emitJobs()
                    }
                    is AgentEvent.ToolResult ->
                        if (jobs.onToolResult(ev.toolUseId, ev.content, ev.isError, System.currentTimeMillis())) emitJobs()
                    is AgentEvent.BackgroundTaskStarted ->
                        if (jobs.onTaskStarted(ev.taskId, ev.toolUseId, ev.description, ev.taskType, System.currentTimeMillis())) emitJobs()
                    is AgentEvent.BackgroundTaskUpdated ->
                        if (jobs.onTaskUpdated(ev.taskId, ev.status, System.currentTimeMillis())) emitJobs()
                    is AgentEvent.TurnResult -> {
                        executing = false
                        val usage = TokenUsage(ev.inputTokens, ev.outputTokens, ev.cacheCreationInputTokens, ev.cacheReadInputTokens)
                        // keep the resume seed current: a mid-session reconnect then seeds the latest
                        // occupancy, not the stale open-time snapshot (same value the phone shows live).
                        resumeContextUsed = usage.contextTokens
                        sink.emit(TurnDone(convoId, ev.finalText, usage))
                        // wake an offline phone (relay mode only; hook is null on LAN). Launched off the pump
                        // so a control-plane send never stalls stdout parsing.
                        pushHookProvider()?.let { hook -> val sid = sessionId; scope.launch { hook.onTurnComplete(workdir, sid, ev.finalText) } }
                    }
                    is AgentEvent.ControlRequest -> b.onControlRequest(ev)
                    is AgentEvent.ControlCancel -> b.onCancel(ev)
                    AgentEvent.UserReplay -> {}
                    is AgentEvent.Ignored -> {}
                    is AgentEvent.Unparseable -> {}
                }
            }
        }
        log.info("$convoId pump ended (intentionalStop=$intentionalStop)")
        if (!intentionalStop) {
            // unexpected death: stdout EOF precedes the last transcript flush, so wait for the
            // real process exit before touching the file (intentional stops settle in stopProcess)
            executing = false // a dead process never delivers TurnResult
            p.awaitExit()
            backend.onProcessEnded(sessionId)
            sink.emit(PocketError("process_exited", "agent process ended", convoId))
        }
    }

    /** Re-point this live conversation to a re-opened device, replaying its transcript so far. */
    suspend fun reattach(newSink: OutboundSink) {
        sink = newSink
        lastActivityMs = System.currentTimeMillis()
        val sid = sessionId ?: return
        // executing rights the phone's stale ■: a turn that finished (or started) while it was away
        newSink.emit(live(sid))
        val history = backend.replayHistory(workdir.toString(), sid)
        if (history.isNotEmpty()) newSink.emit(ConvoHistory(convoId, history))
        emitCommands()
        newSink.emit(BackgroundJobs(convoId, jobs.snapshot())) // a re-opened live session re-shows its running jobs
    }

    suspend fun sendPrompt(text: String, images: List<ImageData> = emptyList()) {
        if (tryIntercept(text)) return
        if (proc == null) return
        executing = true // cleared by TurnResult (also covers cancelTurn — the agent still emits a result)
        lastActivityMs = System.currentTimeMillis()
        backend.sendPrompt(text, images)
    }

    /**
     * Daemon-intercepted slash commands. The agent `-p` ignores the interactive forms, so we honor them here
     * (relaunch under the matching flag, or reset the session). Returns true if [text] was a recognized
     * command (and was handled) — the caller then skips the normal prompt path. Custom commands, skills,
     * and prompt-backed built-ins (/review, /compact, …) are NOT intercepted: they pass through to the agent.
     */
    private suspend fun tryIntercept(text: String): Boolean {
        val trimmed = text.trim()
        when (trimmed.substringBefore(' ').substringBefore('\n')) {
            "/model" -> handleModelCommand(trimmed)
            "/effort" -> handleEffortCommand(trimmed)
            "/clear" -> handleClearCommand()
            else -> return false
        }
        return true
    }

    /** Handle the phone's `/model [name]` — the agent `-p` ignores it, so the daemon honors it. */
    private suspend fun handleModelCommand(text: String) {
        val arg = text.removePrefix("/model").trim()
        if (arg.isEmpty()) {
            reply("Current model: ${model ?: "default"}.\nUsage: /model <name> — e.g. /model opus, /model sonnet, /model haiku (or a full model id).")
            return
        }
        switchModel(arg)
        reply("✓ Model switched to \"$arg\" for this session. Your next message will use it.")
    }

    /** Handle the phone's `/effort [level]` — the agent `-p` ignores it, so the daemon honors it. */
    private suspend fun handleEffortCommand(text: String) {
        val arg = text.removePrefix("/effort").trim().lowercase()
        if (arg.isEmpty()) {
            reply("Current reasoning effort: ${effort ?: "default"}.\nUsage: /effort <level> — one of ${EFFORT_LEVELS.joinToString(", ")}.")
            return
        }
        if (arg !in EFFORT_LEVELS) {
            reply("Unknown effort \"$arg\". Choose one of: ${EFFORT_LEVELS.joinToString(", ")}.")
            return
        }
        switchEffort(arg)
        reply("✓ Reasoning effort set to \"$arg\" for this session. Your next message will use it.")
    }

    /**
     * Handle the phone's `/clear` — there is no stream-json "clear", so the daemon starts a fresh session in
     * the same cwd (no resume), keeping the chosen model/effort/mode. The phone's transcript is wiped via an
     * empty history; the next turn lands on a brand-new sessionId.
     */
    private suspend fun handleClearCommand() {
        stopProcess() // also clears + re-emits background jobs (the killed tree took its bg shells with it)
        sessionId = null
        openedResumeId = null // brand-new session — no resume lineage left to preserve
        launchProcess(AgentSpec(workdir, resumeId = null, model = model, mode = mode, effort = effort))
        sink.emit(ConvoHistory(convoId, emptyList())) // wipe the phone's transcript
        sink.emit(live(null))                          // sessionId backfills on the next init
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
     * Interrupt the in-flight turn (phone composer ■). Claude uses the stream-json control protocol; Codex
     * sends turn/interrupt. Either way the agent aborts the turn and the session/process stay alive.
     */
    suspend fun cancelTurn() {
        if (proc == null) return
        lastActivityMs = System.currentTimeMillis()
        backend.interrupt()
    }

    /** Default semantics: kill the current process tree and start a fresh session in the new cwd. */
    suspend fun switchDirectory(newWorkdir: Path) {
        stopProcess()
        workdir = newWorkdir
        sessionId = null
        openedResumeId = null // fresh session in the new cwd — no resume lineage left to preserve
        launchProcess(AgentSpec(workdir, resumeId = null, model = null, mode = mode, effort = effort))
        emitCommands() // project commands differ per workdir
    }

    private suspend fun stopProcess() {
        intentionalStop = true
        executing = false // any in-flight turn dies with the process
        bridge?.cancelAll()
        proc?.shutdown() // waits for real exit (force-kill fallback) — file is quiet after this
        proc = null
        bridge = null
        if (jobs.clear()) sink.emit(BackgroundJobs(convoId, emptyList())) // the killed process tree took its bg shells with it
        backend.onProcessEnded(sessionId)
    }

    suspend fun close() {
        stopProcess()
        scope.cancel()
    }

    private companion object {
        val EFFORT_LEVELS = setOf("low", "medium", "high", "xhigh", "max")
    }
}
