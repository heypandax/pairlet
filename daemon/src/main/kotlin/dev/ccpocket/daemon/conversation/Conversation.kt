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
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.BackgroundJobs
import dev.ccpocket.protocol.CommandList
import dev.ccpocket.protocol.contextWindowFor
import dev.ccpocket.protocol.provenWindow
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PromptAck
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
import dev.ccpocket.protocol.isSubagentTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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
    /** Which agent backend drives this conversation — live project rows tag it so a tap resumes the right CLI. */
    val kind: AgentKind get() = backend.kind

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

    // FAN-OUT (issue #47): every client that opened this conversation gets the stream. The old single
    // field made the last reattach steal it from everyone else — a phone foregrounding auto-reopens its
    // last session and silently blinded an attached desktop mid-turn (no TurnDone, caret forever).
    // Keyed by client identity so a reconnecting device replaces its stale sink instead of stacking one.
    private val sinks = java.util.concurrent.ConcurrentHashMap<Any, OutboundSink>().apply {
        put(sinkKey(initialSink), initialSink)
    }

    // every existing emit site goes through this fan-out; one failing transport must not break the rest
    private val sink: OutboundSink = OutboundSink { f -> sinks.values.forEach { s -> runCatching { s.emit(f) } } }

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

    // in-flight top-level sub-agent (Task/Agent) calls, keyed by tool_use id (issue #77). Drives the
    // phone's Task card: START on the tool_use, RESULT with the report on completion. `background`
    // (run_in_background) flips the completion source: a foreground run's tool_result IS the report;
    // a background run's tool_result is only the launch ack — task_notification carries the outcome.
    // Only touched from the single stdout pump (like `jobs`), so no locking. Bounded by MAX_SUBAGENTS.
    private data class SubagentRun(val tool: String, val background: Boolean)
    private val subagentRuns = LinkedHashMap<String, SubagentRun>()

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

    // whether open() decided to --fork-session (the desktop was actively writing the resumed transcript).
    // A pre-first-turn relaunch must REUSE this decision: sessionId is still null then, and the old
    // `resumeId != sessionId` heuristic read that as "foreign id → fork", minting a duplicate session
    // from a mere mode switch before the first message (issue #18/#21 residual).
    @Volatile
    private var openedWithFork = false

    // model read back from the resumed transcript, for DISPLAY only (header + context window before the
    // first init lands). Never baked into an AgentSpec: pinning a historical — possibly retired — model
    // onto a relaunch or /clear would silently override the user's configured default (issue #27 residual).
    @Volatile
    private var backfilledModel: String? = null

    // set when a mode switch relaunches the process: re-announce SessionLive on the next init so the phone clears "switching"
    @Volatile
    private var reemitLive = false

    // context tokens the resumed transcript's last turn left in the window — seeds the phone's usage
    // statusline before the first new turn lands. Null for a brand-new session (nothing used yet).
    @Volatile
    private var resumeContextUsed: Long? = null

    /** The turn's most recent per-call usage (see [AgentEvent.AssistantUsage]) — consumed and cleared by
     *  the TurnResult branch, which prefers it over the result event's across-calls sum. */
    private var lastCallUsage: AgentEvent.AssistantUsage? = null

    // the turn emitted a `<synthetic>` placeholder (every API call failed) — consumed by TurnResult,
    // which reports the turn as an ERROR instead of letting the placeholder pass for a real reply (issue #65)
    @Volatile
    private var sawSyntheticThisTurn = false

    // ■ was pressed for the in-flight turn: its result may report is_error, which must NOT render as a
    // red failure row — the user cancelled it themselves. Cleared when the result lands.
    @Volatile
    private var interruptRequested = false

    // consecutive turns that produced ONLY a synthetic placeholder — ≥ DEGRADED_STREAK flips
    // SessionLive.degraded so clients warn + gate further sends into a session that can only bloat
    // (issue #65). Seeded from the resumed transcript's tail in open(); reset by /clear and switchDir.
    @Volatile
    private var failedTurnStreak = 0

    // promptIds already delivered — a client RESEND after a lost ack is re-acked, never double-run
    // (issue #66). Bounded; guarded by its own lock (touched from router + pump scopes).
    private val seenPromptIds = LinkedHashSet<String>()

    private fun degraded(): Boolean = failedTurnStreak >= DEGRADED_STREAK

    /** True if [promptId] was seen before (recording it when not). Bounded LRU-ish: oldest falls off. */
    private fun promptSeenBefore(promptId: String): Boolean = synchronized(seenPromptIds) {
        if (!seenPromptIds.add(promptId)) return true
        if (seenPromptIds.size > SEEN_PROMPTS_MAX) seenPromptIds.iterator().run { next(); remove() }
        false
    }

    /** The model the phone should SEE: the requested/confirmed one, else the transcript backfill. */
    private fun displayModel(): String? = model ?: backfilledModel

    /** The 1M/200k denominator the phone renders usage % against. Claude-only (Codex windows differ; null →
     *  the phone falls back). Observed occupancy beyond the 200k default PROVES the 1M window — beta-gated
     *  models report a canonical id that declares 200k (capability ≠ enablement) — so upgrade, never downgrade. */
    private fun claudeWindow(): Long? {
        if (backend.kind != AgentKind.CLAUDE) return null
        return provenWindow(displayModel()?.let(::contextWindowFor), resumeContextUsed)
    }

    /** The announce frame, stamped with everything mutable the phone reconciles from (mode, executing, model, effort, agent). */
    private fun live(sid: String?) =
        SessionLive(
            convoId, workdir.toString(), sid, mode = mode, executing = executing, model = displayModel(), effort = effort,
            // stamp the 1M/200k window from the model so the phone's usage % has an authoritative denominator
            // (issue #20) instead of sniffing the id itself. Phones that predate the field simply ignore it.
            contextWindow = claudeWindow(),
            contextUsed = resumeContextUsed, agent = backend.kind,
            degraded = degraded(),
        )

    /** The current permission mode — read by the shell approval gate so it can't be spoofed from the phone. */
    fun currentMode(): PermissionMode = mode

    /** True while this conversation still streams to [s] — the LAN grace-close ownership check. */
    fun isAttachedTo(s: OutboundSink): Boolean = sinks.containsKey(sinkKey(s))

    /** Remove [s]'s view of this conversation; true when no clients remain (caller may close for real). */
    fun detach(s: OutboundSink): Boolean {
        sinks.remove(sinkKey(s))
        return sinks.isEmpty()
    }

    /** The id this conversation is resuming while [sessionId] is still null (pre-first-turn) — lets a
     *  reconnect reattach the live process instead of spawning a second one on the same transcript. */
    val resumeAnchor: String? get() = openedResumeId

    suspend fun open(resumeId: String?, model: String?, effort: String? = null, fork: Boolean = false, takeOver: Boolean = false) {
        this.model = model
        this.effort = effort // restore the session's last reasoning effort on a fresh resume (transcript doesn't carry it)
        this.openedResumeId = resumeId
        this.openedWithFork = fork
        // LAZY START (issue #61): a plain open PREVIEWS the session — it announces SessionLive, replays history and
        // lists commands — but does NOT spawn an agent process. Spawning on open bound a daemon-owned `claude
        // --resume` to this session the instant a phone tapped in; that held the session so the desktop (or a
        // terminal) could no longer use it, and with a phone online the idle reaper never fired to release it. The
        // process now starts lazily on the first prompt (see sendPrompt) — the moment a spawn is actually needed.
        // A plain open never forks: [fork] is only set on take-over, so the deferred first-prompt launch resumes
        // in place (openedWithFork == false), appending to the resumed transcript.
        //
        // EXCEPTION — an explicit take-over ([takeOver] == OpenSession.takeOver, the phone's "Continue here") spawns
        // EAGERLY, on purpose. Its whole semantics are "seize this session NOW", and the take-over fork decision
        // (issue #35: branch a fresh id off a possibly-live desktop `claude --resume`, carried in [fork]) was already
        // computed by the registry and must be honored at open time — deferring it to an uncertain first prompt would
        // break "tap to take over" and could let two writers clobber one transcript. Codex ignores forkSession; a
        // null resumeId is a brand-new session either way.
        if (takeOver) {
            launchProcess(AgentSpec(workdir, resumeId, model, mode, effort = effort, forkSession = fork))
        }
        // a headless agent (claude `--input-format stream-json`; codex pre-thread) emits NOTHING — not even the
        // init that would drive SessionLive — until the first user turn / handshake lands (and on a lazy open there
        // is no process at all yet). But the phone needs convoId (carried by SessionLive) before it can send that
        // first turn. So announce the session as live now (we own convoId + workdir), and replay the resumed
        // transcript up front; once the first prompt spawns the agent, the pump re-emits SessionLive with the real
        // sessionId.
        scope.launch {
            // Seed model + usage from the resumed transcript so the header shows the real model/window and the
            // usage statusline on open — before the first new turn's init lands (a headless claude is silent
            // until then, issue #27). Done off the relay inbound loop; the transcript read can be a multi-MB parse.
            if (model == null && resumeId != null) {
                runCatching { backend.resumeModel(workdir.toString(), resumeId) }.getOrNull()?.let { backfilledModel = it }
            }
            resumeContextUsed = resumeId?.let { runCatching { backend.resumeContextTokens(workdir.toString(), it) }.getOrNull() }
            // seed the degraded flag from the transcript's tail: a session that died over its context
            // window stays warned across close/reopen, not just while this daemon watched it fail
            if (resumeId != null) {
                failedTurnStreak = runCatching { backend.resumeFailedTurnStreak(workdir.toString(), resumeId) }.getOrDefault(0)
            }
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

    /** Labels of the still-RUNNING background jobs — names the work an auth-switch blocker row shows. */
    fun runningJobLabels(): List<String> =
        jobs.snapshot().filter { it.status == dev.ccpocket.protocol.JobStatus.RUNNING }.map { it.label }

    /** True while a permission ask / AskUserQuestion is still awaiting the phone's verdict. Like
     *  [hasBackgroundWork] this keeps the idle reaper off the conversation: a turn blocked on an unanswered
     *  question is not idle, and reaping it would discard a card the user is expected to answer — the plan-mode
     *  failure in issue #55, where the question lands long after a premature `result` while the phone is
     *  backgrounded (past the 90s idle window). Bounded by the bridge's question timeout. */
    fun hasPendingAsk(): Boolean = bridge?.hasPending() == true

    /** True while a turn is streaming — the LAN disconnect grace-close re-arms instead of killing it
     *  (in-flight work must survive its owner app quitting; see SessionRegistry.scheduleClose). */
    fun isExecuting(): Boolean = executing

    /** True while the conversation is doing or awaiting anything that must outlive its owner leaving: a
     *  streaming turn, running background jobs, or an unanswered permission/question card. The shared
     *  keep-alive predicate for SessionRegistry.close/scheduleClose (reapIdle keeps its own idle-time
     *  variant, where lastActivityMs already covers the streaming case). */
    fun isBusy(): Boolean = isExecuting() || hasBackgroundWork() || hasPendingAsk()

    /** Pre-first-turn (issue #61 lazy start): with no agent process yet, a mode/model/effort switch only
     *  records the field and re-announces — relaunching would spawn the very process the lazy open avoided,
     *  re-occupying the session before any message. Returns true when it handled this case (caller returns). */
    private suspend fun recordedPreFirstTurn(): Boolean {
        if (proc != null) return false
        sink.emit(live(sessionId ?: openedResumeId))
        return true
    }

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
     * Fork decision: pre-first-turn ([sessionId] still null) reuse open()'s call — the desktop's liveness
     * hasn't changed just because the phone flipped a setting; the old `resumeId != sessionId` heuristic
     * forked a duplicate session here. Post-init, fork only for a genuinely foreign id (never today's callers).
     */
    private suspend fun relaunch(resumeId: String? = sessionId) {
        stopProcess()
        val fork = if (sessionId == null) openedWithFork else resumeId != sessionId
        launchProcess(
            AgentSpec(workdir, resumeId = resumeId, model = model, mode = mode, effort = effort, forkSession = fork),
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
        if (recordedPreFirstTurn()) return // issue #61: no process yet → record the mode, don't relaunch
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
        backfilledModel = null // an explicit choice replaces the transcript guess, even a choice of "default"
        if (recordedPreFirstTurn()) return // issue #61: no process yet → record the model, don't relaunch
        if (backend.applySettings(mode = null, model = newModel, effort = null)) {
            reemitLive = true // the next init re-announces too — it carries the post-resume sessionId
            // pre-first-turn fall back to the opened resumeId, like switchMode — relaunch(sessionId=null)
            // would drop --resume entirely and orphan the resumed session's history
            val sid = sessionId ?: openedResumeId
            relaunch(sid)
            sink.emit(live(sid)) // confirm now (new model + window) — init won't arrive until the next turn
        } else sink.emit(live(sessionId))
    }

    /** Switch reasoning effort. Claude relaunches under --effort; Codex applies it to the next turn. */
    suspend fun switchEffort(newEffort: String?) {
        effort = newEffort
        if (recordedPreFirstTurn()) return // issue #61: no process yet → record the effort, don't relaunch
        if (backend.applySettings(mode = null, model = null, effort = newEffort)) {
            reemitLive = true
            val sid = sessionId ?: openedResumeId // same pre-first-turn anchor as switchModel/switchMode
            relaunch(sid)
            sink.emit(live(sid))
        } else sink.emit(live(sessionId))
    }

    fun clearAllowRule(rule: String?) {
        if (rule == null) allowRules.clear() else allowRules.remove(rule)
    }

    private suspend fun launchProcess(spec: AgentSpec) {
        intentionalStop = false
        val p = AgentProcess.start(backend.processBuilder(spec), scope)
        val io = AgentIo(writeLine = p::writeLine, emit = { sink.emit(it) }) // read sink dynamically (reattach)
        val b = PermissionBridge(convoId, mode, scope, { sink.emit(it) }, allowRules, respond = backend::respondPermission)
        proc = p
        bridge = b
        // Bind IO + kick off any handshake (Codex initialize → thread/start) SYNCHRONOUSLY, before returning. The
        // lazy first prompt (issue #61) calls backend.sendPrompt right after this: sendPrompt reads the io attach
        // installs (Claude) and the thread state attach resets (Codex). Running attach inside the pump coroutine
        // (as before) let that first sendPrompt race ahead of it and silently drop the opening turn. attach only
        // KICKS OFF the handshake — its writes buffer on the process's stdin channel — so this can't block on the
        // agent; the pump below reads the replies (stdout is buffered until it starts).
        backend.attach(io, spec)
        scope.launch(CoroutineName("pump-$convoId")) {
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
                        ev.model?.let { model = it; backfilledModel = null } // the agent's resolved model beats the transcript guess
                        if (firstTime && sessionId != null) {
                            reemitLive = false // this announce already carries the fresh sessionId + mode
                            log.info("$convoId session live: $sessionId")
                            // journal the spawn (Claude) so a crashed daemon can still unhide this
                            // transcript for the resume pickers at next boot (issue #70)
                            sessionId?.let { sid -> runCatching { backend.onSessionStarted(sid, workdir.toString()) } }
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
                    // stream evidence also arms `executing`: a message sent MID-turn is queued by claude and
                    // may start its own follow-up turn after the current result — that turn has no sendPrompt
                    // to arm the flag, and without it the grace-close reaper could kill the in-flight work
                    // (mirrors the phone, whose appendChunk sets `streaming` on the same evidence).
                    // a sub-agent's inner monologue (parentId set) must not render as the MAIN agent
                    // speaking — its activity reaches the phone as parent-tagged tool events the client
                    // folds into the Task card instead (issue #77)
                    is AgentEvent.AssistantText -> {
                        executing = true
                        if (ev.parentId == null) sink.emit(AssistantChunk(convoId, seq.getAndIncrement(), StreamPiece.Text(ev.text)))
                    }
                    is AgentEvent.AssistantThinking -> {
                        executing = true
                        if (ev.parentId == null) sink.emit(AssistantChunk(convoId, seq.getAndIncrement(), StreamPiece.Thinking(ev.text)))
                    }
                    is AgentEvent.AssistantToolUse -> {
                        executing = true
                        val subagent = ev.parentId == null && isSubagentTool(ev.name)
                        // ExitPlanMode's input IS the proposed plan (input["plan"]) — surface it in full via the
                        // shared ToolMetadata extractor so the plan is readable in the phone's chat, not truncated
                        // to 280 chars of raw JSON (issue #10). A sub-agent call reads as its type + description
                        // (issue #77, same label as the jobs sheet). Other tools keep the compact JSON preview.
                        val preview = when {
                            ev.name == "ExitPlanMode" || ev.name == "exit_plan_mode" -> ToolMetadata.of(ev.name, ev.input).preview
                            subagent -> listOfNotNull(ev.input.strField("subagent_type"), ev.input.strField("description"))
                                .joinToString(": ").ifBlank { "sub-agent" }
                            else -> ev.input?.toString()?.take(280)
                        }
                        if (subagent && ev.id != null) rememberSubagent(ev.id, ev.name, ev.input.boolField("run_in_background"))
                        sink.emit(
                            ToolEvent(
                                convoId, seq.getAndIncrement(), ToolPhase.START, ev.name, preview,
                                toolUseId = ev.id, parentToolUseId = ev.parentId,
                            ),
                        )
                        if (jobs.onToolUse(ev.id, ev.name, ev.input, System.currentTimeMillis())) emitJobs()
                    }
                    is AgentEvent.ToolResult -> {
                        if (ev.parentId == null) finishSubagentFromResult(ev)
                        if (jobs.onToolResult(ev.toolUseId, ev.content, ev.isError, System.currentTimeMillis())) emitJobs()
                    }
                    is AgentEvent.BackgroundTaskStarted ->
                        if (jobs.onTaskStarted(ev.taskId, ev.toolUseId, ev.description, ev.taskType, System.currentTimeMillis())) emitJobs()
                    is AgentEvent.BackgroundTaskUpdated -> {
                        finishSubagentFromTask(ev)
                        if (jobs.onTaskUpdated(ev.taskId, ev.status, System.currentTimeMillis())) emitJobs()
                    }
                    is AgentEvent.AssistantUsage -> lastCallUsage = ev
                    // the CLI's API-failure placeholder — never a real reply. Suppress the chunk (the
                    // TurnDone error row replaces it) but still arm `executing`: a turn did run (issue #65).
                    is AgentEvent.SyntheticReply -> {
                        executing = true
                        sawSyntheticThisTurn = true
                    }
                    is AgentEvent.TurnResult -> {
                        executing = false
                        // a FOREGROUND sub-agent still tracked at turn end never delivered its result
                        // (interrupted / died) — settle its card so it can't spin forever. Background
                        // runs live across turns; task_notification (or stopProcess) settles those.
                        settleSubagents(includeBackground = false)
                        val synthetic = sawSyntheticThisTurn
                        sawSyntheticThisTurn = false
                        val interrupted = interruptRequested
                        interruptRequested = false
                        // A result WITHOUT usage (interrupted turn, some error exits) surfaces as usage=null,
                        // never zeros — a zero snaps the phone's statusline to 0% and poisons the resume seed.
                        // Context fields prefer the turn's LAST API call: the result event SUMS input/cache
                        // across every call of the turn (N tool batches ≈ N× the real occupancy — the phone
                        // read 88% on a 44% session). Output keeps the result's turn total. Same
                        // last-vs-total rule as the Codex backend.
                        val last = lastCallUsage
                        lastCallUsage = null
                        val usage = when {
                            last != null -> TokenUsage(last.inputTokens, ev.usage?.outputTokens ?: 0, last.cacheCreationInputTokens, last.cacheReadInputTokens)
                            else -> ev.usage
                        }
                        // keep the resume seed current: a mid-session reconnect then seeds the latest
                        // occupancy, not the stale open-time snapshot (same value the phone shows live).
                        usage?.contextTokens?.takeIf { it > 0 }?.let { resumeContextUsed = it }
                        // The turn's real outcome (issue #65). A synthetic placeholder means every API call
                        // failed — say so instead of letting "No response requested." pass for an answer. A
                        // user-cancelled turn (■) may report is_error too: that one is not a failure to paint red.
                        val error = when {
                            synthetic ->
                                "API request failed — the agent wrote a placeholder, not a real reply. " +
                                    "If this keeps happening the session has likely outgrown its context window: " +
                                    "start a new session or send /clear."
                            ev.isError && !interrupted -> ev.finalText?.takeIf { it.isNotBlank() }?.take(300) ?: "turn failed"
                            else -> null
                        }
                        sink.emit(TurnDone(convoId, ev.finalText, usage, error = error))
                        // degraded tracking: consecutive placeholder-only turns mark the session as likely
                        // context-dead; announce transitions so clients warn + gate the next send (issue #65)
                        val wasDegraded = degraded()
                        failedTurnStreak = if (synthetic) failedTurnStreak + 1 else 0
                        if (degraded() != wasDegraded) sink.emit(live(sessionId))
                        // wake an offline phone (relay mode only; hook is null on LAN). Launched off the pump
                        // so a control-plane send never stalls stdout parsing. A failed turn pushes the error,
                        // not the placeholder text.
                        pushHookProvider()?.let { hook -> val sid = sessionId; scope.launch { hook.onTurnComplete(workdir, sid, error ?: ev.finalText) } }
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
            // carry the exit code + the agent's last stderr line: a --resume that dies before its first
            // init (bad session id, context overflow) used to surface as a bare "agent process ended"
            val why = p.lastStderr?.let { " — ${it.take(300)}" } ?: ""
            sink.emit(PocketError("process_exited", "agent process ended (exit ${p.exitCode() ?: "?"})$why", convoId))
        }
    }

    /** ADD a re-opened device's view (fan-out — it no longer steals the stream from the others),
     *  replaying the transcript so far to the newcomer only. */
    suspend fun reattach(newSink: OutboundSink) {
        sinks[sinkKey(newSink)] = newSink
        lastActivityMs = System.currentTimeMillis()
        // pre-first-turn the agent hasn't minted a sessionId yet — anchor on the resume id (same trick
        // as switchMode) so the reattach still confirms + replays instead of leaving a blank chat
        val sid = sessionId ?: resumeAnchor ?: return
        // executing rights the phone's stale ■: a turn that finished (or started) while it was away
        newSink.emit(live(sid))
        val history = backend.replayHistory(workdir.toString(), sid)
        if (history.isNotEmpty()) newSink.emit(ConvoHistory(convoId, history))
        emitCommands()
        newSink.emit(BackgroundJobs(convoId, jobs.snapshot())) // a re-opened live session re-shows its running jobs
        // A permission ask / question still awaiting a verdict is re-shown to the reconnecting device: it fired
        // while this phone was away (in plan mode the AskUserQuestion can land minutes after a premature `result`,
        // so the phone is often backgrounded — socket suspended — when the live frame goes out), and without this
        // its card never reappears and the turn wedges on an answer the user was never shown (issue #55). Emitted
        // to the newcomer only; a device already showing the card is untouched. Ordered after SessionLive above so
        // the phone's convoId is set before the PermissionAsk (its handler is convoId-gated).
        bridge?.resurfacePending { newSink.emit(it) }
    }

    suspend fun sendPrompt(text: String, images: List<ImageData> = emptyList(), promptId: String? = null) {
        // idempotent retry (issue #66): a promptId we already delivered is re-ACKED, never re-run —
        // the client may resend after a lost ack, and a duplicate turn is worse than a duplicate receipt
        if (promptId != null && promptSeenBefore(promptId)) {
            sink.emit(PromptAck(convoId, promptId))
            return
        }
        if (tryIntercept(text)) {
            promptId?.let { sink.emit(PromptAck(convoId, it)) } // handled by the daemon = delivered
            return
        }
        // LAZY START (issue #61): a plain open no longer spawns the agent — the FIRST prompt does. Resume the id
        // open() recorded (openedResumeId), reusing its fork decision (openedWithFork — false for a plain open, so
        // this appends in place). Without this, the first message after a lazy open would hit the old `proc == null`
        // guard and be silently dropped. A spawn failure (CLI missing / bad resume id) surfaces as a PocketError so
        // it can't propagate out and wedge the inbound pump; proc stays null, so the next prompt simply retries.
        if (proc == null) {
            val launched = runCatching {
                launchProcess(AgentSpec(workdir, openedResumeId, model, mode, effort = effort, forkSession = openedWithFork))
            }
            if (launched.isFailure) {
                // no ack: the prompt did NOT reach an agent — forget the id so the client's retry can run
                promptId?.let { synchronized(seenPromptIds) { seenPromptIds.remove(it) } }
                sink.emit(PocketError("agent_unavailable", "agent failed to start (${launched.exceptionOrNull()?.message})", convoId))
                return
            }
        }
        executing = true // cleared by TurnResult (also covers cancelTurn — the agent still emits a result)
        lastActivityMs = System.currentTimeMillis()
        backend.sendPrompt(text, images)
        promptId?.let { sink.emit(PromptAck(convoId, it)) } // the turn is in the agent's hands — receipt (issue #66)
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
            reply("Current model: ${displayModel() ?: "default"}.\nUsage: /model <name> — e.g. /model opus, /model sonnet, /model haiku (or a full model id).")
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
        openedWithFork = false
        backfilledModel = null
        failedTurnStreak = 0 // a fresh session starts healthy — the degraded warning belongs to the old transcript
        sawSyntheticThisTurn = false
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
        interruptRequested = true // the coming result may say is_error — that's the user's ■, not a failure
        backend.interrupt()
    }

    /** Default semantics: kill the current process tree and start a fresh session in the new cwd. */
    suspend fun switchDirectory(newWorkdir: Path) {
        stopProcess()
        workdir = newWorkdir
        sessionId = null
        openedResumeId = null // fresh session in the new cwd — no resume lineage left to preserve
        openedWithFork = false
        backfilledModel = null
        failedTurnStreak = 0 // fresh session in a new cwd — degraded state died with the old transcript
        sawSyntheticThisTurn = false
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
        settleSubagents(includeBackground = true) // sub-agents died with the tree — stop their cards spinning
        if (jobs.clear()) sink.emit(BackgroundJobs(convoId, emptyList())) // the killed process tree took its bg shells with it
        backend.onProcessEnded(sessionId)
    }

    // ---- sub-agent (Task/Agent) card lifecycle (issue #77) — pump-thread only, like `jobs` ----

    private fun rememberSubagent(id: String, tool: String, background: Boolean) {
        subagentRuns[id] = SubagentRun(tool, background)
        // bounded like the jobs registry: a leaked entry (completion never seen) must not grow forever
        while (subagentRuns.size > MAX_SUBAGENTS) subagentRuns.remove(subagentRuns.keys.first())
    }

    /** Main-chain tool_result for a tracked sub-agent: a foreground run's result IS its report — emit the
     *  card's RESULT. A background run's success result is only the launch ack (task_notification finishes
     *  it); its ERROR result means the launch itself failed, so settle now. */
    private suspend fun finishSubagentFromResult(ev: AgentEvent.ToolResult) {
        val id = ev.toolUseId ?: return
        val run = subagentRuns[id] ?: return
        if (run.background && !ev.isError) return
        subagentRuns.remove(id)
        emitSubagentResult(id, run.tool, ok = !ev.isError, output = subagentReport(ev.content))
    }

    /** `task_notification` completion for a tracked BACKGROUND sub-agent — the authoritative outcome
     *  (its tool_result was only the launch ack). Foreground runs wait for the tool_result instead:
     *  it carries the full report, where the notification only has a summary. */
    private suspend fun finishSubagentFromTask(ev: AgentEvent.BackgroundTaskUpdated) {
        val id = ev.toolUseId ?: return
        val run = subagentRuns[id]?.takeIf { it.background } ?: return
        val ok = when (ev.status?.lowercase()) {
            "completed", "complete", "done", "success" -> true
            "failed", "error", "killed", "cancelled", "canceled", "interrupted" -> false
            else -> return // not terminal — keep the card running
        }
        subagentRuns.remove(id)
        emitSubagentResult(id, run.tool, ok, output = subagentReport(ev.summary))
    }

    /** Settle every still-tracked sub-agent as not-ok (its completion can no longer arrive). */
    private suspend fun settleSubagents(includeBackground: Boolean) {
        val iter = subagentRuns.entries.iterator()
        while (iter.hasNext()) {
            val (id, run) = iter.next()
            if (!includeBackground && run.background) continue
            iter.remove()
            emitSubagentResult(id, run.tool, ok = false, output = null)
        }
    }

    private suspend fun emitSubagentResult(id: String, tool: String, ok: Boolean, output: String?) {
        sink.emit(ToolEvent(convoId, seq.getAndIncrement(), ToolPhase.RESULT, tool, ok = ok, toolUseId = id, output = output))
    }

    /** The sub-agent's final report, minus the CLI's trailing "agentId: … <usage>…" continuation
     *  plumbing, capped so one Task card can't threaten the relay frame budget. */
    private fun subagentReport(content: String?): String? {
        content ?: return null
        val cut = content.indexOf("\nagentId: ")
        val body = if (cut >= 0) content.substring(0, cut) else content
        return body.trim().take(SUBAGENT_OUTPUT_MAX).ifBlank { null }
    }

    private fun JsonObject?.strField(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.boolField(key: String): Boolean {
        val p = this?.get(key) as? JsonPrimitive ?: return false
        return p.booleanOrNull ?: (p.contentOrNull == "true")
    }

    suspend fun close() {
        stopProcess()
        scope.cancel()
    }

    private companion object {
        val EFFORT_LEVELS = setOf("low", "medium", "high", "xhigh", "max")

        // consecutive placeholder-only turns before the session is announced degraded (issue #65)
        const val DEGRADED_STREAK = 2

        // delivered promptIds kept for retry dedupe — well past any realistic resend window
        const val SEEN_PROMPTS_MAX = 64

        // in-flight sub-agent cards tracked at once (issue #77) — parallel fan-outs stay well under this
        const val MAX_SUBAGENTS = 16

        // cap on a sub-agent report crossing the wire in a ToolEvent/HistoryMessage (4 MiB frame budget)
        const val SUBAGENT_OUTPUT_MAX = 4000
    }
}
