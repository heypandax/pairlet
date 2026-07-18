package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentProcessMode
import dev.ccpocket.daemon.agent.AgentPromptDelivery
import dev.ccpocket.daemon.agent.AgentProcess
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.agent.ApprovalTimeout
import dev.ccpocket.daemon.agent.PermissionBridge
import dev.ccpocket.daemon.agent.ToolMetadata
import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.daemon.disk.SessionGroups
import dev.ccpocket.daemon.disk.SlashCommandScanner
import dev.ccpocket.daemon.disk.WorkflowFiles
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.BackgroundJobs
import dev.ccpocket.protocol.CommandList
import dev.ccpocket.protocol.contextWindowFor
import dev.ccpocket.protocol.provenWindow
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.ConvoHistoryPage
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
import dev.ccpocket.protocol.WorkflowAgentDetail
import dev.ccpocket.protocol.WorkflowUpdate
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.isSubagentTool
import dev.ccpocket.protocol.isWorkflowTool
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
    // how long [isBusy] holds the conversation after a "continuation expected" trigger (see
    // [continuationGraceUntil]); a knob only so tests can expire it without waiting minutes
    private val continuationGraceMs: Long = CONTINUATION_GRACE_MS,
    /** The restricted credential that opened this conversation (issue #91 bridge / #115 guest) — null for
     *  every interactive owner client. Rides SessionLive/ActiveSession as the "via <name>" label, lengthens
     *  the ask timeout (nobody is watching the sheet live), and arms the ask push below. */
    val origin: String? = null,
    // how a pending permission ask reaches a human who isn't watching: a bridge conversation's owner
    // (issue #91 — the bridge never gets the frame) or an owner session's locked/away phone (issue #138)
    private val askPushHookProvider: () -> AskPushHook? = { null },
    // per-conversation window between ask pushes (see [lastAskPushMs]); a knob only so tests can
    // exercise the coalescing without waiting a minute
    private val askPushCoalesceMs: Long = ASK_PUSH_COALESCE_MS,
    /** GUEST folder-share scope (issue #115): the canonical shared roots this conversation is confined to.
     *  Non-null → the PermissionBridge hard-denies any Read/Write/Edit whose target escapes them, BEFORE
     *  the guest is even asked. Null = an unrestricted owner conversation (no path guard). */
    private val pathScope: List<String>? = null,
) {
    /** Which agent backend drives this conversation — live project rows tag it so a tap resumes the right CLI. */
    val kind: AgentKind get() = backend.kind

    // mutable: a phone can switch the permission mode mid-session — applied on the NEXT turn, never mid-turn
    // (issue #84): Claude defers its relaunch to the next sendPrompt, Codex carries it in the next turn's params
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

    // the OPENER's own view — open()'s DELTA replay goes here, never the fan-out: the delta continues
    // the opener's cursor specifically, and a second (possibly OLD) client reattaching inside open()'s
    // launch window must not receive a frame it would misread as a full window (issue #147).
    private val openerSink: OutboundSink = initialSink

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

    @Volatile
    private var proc: AgentProcess? = null
    private var bridge: PermissionBridge? = null
    private val seq = AtomicLong(0)

    // background work (bg shells / sub-agents / monitors) tracked from the tool stream; drives the in-chat
    // jobs indicator and keeps the session "busy" (un-reapable) while anything is still running
    private val jobs = BackgroundJobRegistry()

    // Workflow orchestration runs (issue #106) tracked from the same stream — the fan-out container
    // the phone renders as a run card + progress tree. Pump-only, like `jobs` (no locking).
    private val workflows = WorkflowTracker()

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

    // UNPROMPTED-CONTINUATION grace (issue #105 residual). Two probed CLI behaviors (2.1.206) start a
    // new turn with no sendPrompt to arm `executing`: plan mode keeps working after its premature
    // `result` (the research → AskUserQuestion flow of issue #55, reproduced organically: a fresh
    // system/init follows the result within 0.1s), and a completed background task starts a follow-up
    // turn. Between the shield-clearing line (that result / the task's terminal event) and the
    // continuation's first assistant line, `executing` is false, no ask is pending and no job runs —
    // only the activity clock keeps the reaper away, and the continuation's first API call can be
    // stdout-silent past the 90s idle window (pre-first-token latency under retry backoff; the
    // thinking_tokens system lines only flow once the API responds). This stamp keeps [isBusy] true
    // for a bounded grace after those triggers; the continuation's own stream then re-arms
    // `executing`. Gated on a LIVE process, so a dead conversation can never ride the grace.
    @Volatile
    private var continuationGraceUntil: Long = 0L

    @Volatile
    private var intentionalStop = false

    // last time an approval push fired for this conversation (bridge #91 / owner session #138) —
    // coalesces a burst of asks into one alert. Stamped on the single permission-bridge emit path;
    // rolled back from the hook coroutine when no push actually went out (see [maybePushAsk]).
    @Volatile
    private var lastAskPushMs = 0L

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

    // best-guess model for DISPLAY only (header + context window before the first init lands): read back from
    // the resumed transcript, or — for a brand-new session with no --model — the backend's configured default
    // (issue #96). Never baked into an AgentSpec: pinning a historical — possibly retired — model onto a
    // relaunch or /clear would silently override the user's configured default (issue #27 residual). The first
    // turn's init clears it and becomes the source of truth.
    @Volatile
    private var backfilledModel: String? = null

    // set when a mode switch relaunches the process: re-announce SessionLive on the next init so the phone clears "switching"
    @Volatile
    private var reemitLive = false

    // NEXT-TURN SWITCH (issue #84): a mid-session model/mode/effort switch on a bake-at-launch backend (Claude)
    // used to relaunch immediately — killing the in-flight turn. Now the switch only records the desired field +
    // optimistically updates the badge; this arms to mark that the RUNNING process's launch flags are now stale.
    // The next sendPrompt relaunches under the new flags BEFORE it sends that turn (relaunch-then-send), so the
    // change lands on the very next turn without interrupting a running one. Codex applies settings per turn
    // (applySettings == false) and never arms this. Cleared by any (re)launch (it bakes the current flags).
    @Volatile
    private var pendingRelaunch = false

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

    // UNCONSUMED-PROMPT LEDGER (issue #122). A prompt is only PROVEN delivered when the CLI echoes it
    // back on stdout (`--replay-user-messages` replays a user message once it is actually consumed) —
    // "written to the stdin channel" proves nothing: the channel write succeeds even when the process
    // is already dead, and the CLI's own mid-turn queue dies with its process. Every prompt handed to
    // backend.sendPrompt is recorded here and settled by its matching UserReplay; whatever is still in
    // the ledger when a fresh process spawns is RE-INJECTED into it, in order (this generalizes the old
    // healSessionLock lastPrompt single slot — which only survived one prompt and only the lock path).
    // Guarded by synchronized(promptLedger): touched from the router (sendPrompt) + pump (replay) scopes.
    private class PendingPrompt(
        val key: String,
        val text: String,
        val images: List<ImageData>,
        var generation: Long,
        var redeliveries: Int = 0,
    )

    // the turn-starting prompt handed to a fresh (re)launch, so launchProcess can LEDGER it before it
    // starts the pump — a fast agent echoes the user message within ms, and a record that lands after
    // that replay orphans the entry → a spurious re-injection on the next relaunch (issue #122 race).
    private class InitialSend(val promptId: String?, val text: String, val images: List<ImageData>)

    private val promptLedger = ArrayDeque<PendingPrompt>()
    private val localPromptSeq = AtomicLong(0)

    // which launchProcess call a ledger entry was written to — an entry from a PREVIOUS generation (or
    // with no process at all) can no longer be consumed by anyone: it is lost, not merely queued
    @Volatile
    private var processGeneration = 0L

    // when the last TurnResult landed — anchors the relaunch continuation grace (issue #122 ⑤): a
    // result may be a PHANTOM (fable emits an early result / fallback mid-turn and keeps working), so
    // for a short window after one, `!executing` is NOT proof the process is safe to kill
    @Volatile
    private var lastTurnEndedMs = 0L

    // healSessionLock already fired for the current prompt — one heal per user action, so a fork that
    // somehow gets refused too can't relaunch-loop. Re-armed by the next sendPrompt.
    @Volatile
    private var lockForkRetried = false

    // consecutive turns that produced ONLY a synthetic placeholder — ≥ DEGRADED_STREAK flips
    // SessionLive.degraded so clients warn + gate further sends into a session that can only bloat
    // (issue #65). Seeded from the resumed transcript's tail in open(); reset by /clear and switchDir.
    @Volatile
    private var failedTurnStreak = 0

    // promptIds already delivered — a client RESEND after a lost ack is re-acked, never double-run
    // (issue #66). Bounded; guarded by its own lock (touched from router + pump scopes).
    private val seenPromptIds = LinkedHashSet<String>()

    private fun degraded(): Boolean = failedTurnStreak >= DEGRADED_STREAK

    /** Issue #122 ⑤: outside the post-result window a phantom early result could hide a live turn in.
     *  Overridable (system property) so tests — and a field incident — can tune it without a build. */
    private fun relaunchGraceElapsed(): Boolean =
        System.currentTimeMillis() - lastTurnEndedMs >=
            (System.getProperty(RELAUNCH_GRACE_PROP)?.toLongOrNull() ?: RELAUNCH_GRACE_DEFAULT_MS)

    /** True if [promptId] was seen before (recording it when not). Bounded LRU-ish: oldest falls off. */
    private fun promptSeenBefore(promptId: String): Boolean = synchronized(seenPromptIds) {
        if (!seenPromptIds.add(promptId)) return true
        if (seenPromptIds.size > SEEN_PROMPTS_MAX) seenPromptIds.iterator().run { next(); remove() }
        false
    }

    // ---- unconsumed-prompt ledger (issue #122) ----

    /** Record a prompt handed to backend.sendPrompt — unproven until its UserReplay settles it. */
    private fun recordPromptWritten(promptId: String?, text: String, images: List<ImageData>) {
        synchronized(promptLedger) {
            promptLedger.addLast(
                PendingPrompt(promptId ?: "local-${localPromptSeq.getAndIncrement()}", text, images, processGeneration),
            )
            while (promptLedger.size > LEDGER_MAX) promptLedger.removeFirst()
        }
    }

    /** The CLI replayed a consumed user message — settle the first ledger entry with matching text.
     *  Injected plumbing turns (task-notifications, compact summaries) never match a recorded prompt. */
    private fun settlePromptReplay(text: String?) {
        text ?: return
        synchronized(promptLedger) {
            val iter = promptLedger.iterator()
            while (iter.hasNext()) if (iter.next().text == text) { iter.remove(); return }
        }
    }

    /** One-shot argv prompts have no stdin replay; the process accepting the turn settles the launch prompt. */
    private fun settleInitialArgPrompt() = synchronized(promptLedger) {
        val iter = promptLedger.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.generation == processGeneration) {
                iter.remove()
                return@synchronized
            }
        }
    }

    private fun hasUnconsumedPrompts(): Boolean = synchronized(promptLedger) { promptLedger.isNotEmpty() }

    /** /clear + switchDirectory: the old session's undelivered prompts must not leak into a fresh one. */
    private fun clearPromptLedger() = synchronized(promptLedger) { promptLedger.clear() }

    /** Ledger entries a fresh process (generation [gen]) must be re-handed, oldest first. Stamps them
     *  onto the new generation and counts the redelivery; an entry redelivered [MAX_REDELIVERIES] times
     *  without ever seeing its replay is dropped — a safety valve against replay-parse drift turning
     *  every relaunch into a duplicate turn. */
    private fun promptsForRedelivery(gen: Long): List<PendingPrompt> = synchronized(promptLedger) {
        promptLedger.removeAll { it.redeliveries >= MAX_REDELIVERIES }
        promptLedger.forEach { it.generation = gen; it.redeliveries++ }
        promptLedger.toList()
    }

    /** Issue #122 ④: true when [promptId]'s earlier write is provably LOST — it never produced a user
     *  replay AND the process it was written to is gone (dead or superseded). The entry is removed and
     *  the caller re-runs the prompt through the normal path instead of hollow-re-acking it. An entry
     *  still owned by the LIVE process is merely queued mid-turn — that one stays a re-ack (a duplicate
     *  turn is worse than a duplicate receipt). */
    private fun releaseLostPrompt(promptId: String): Boolean = synchronized(promptLedger) {
        val entry = promptLedger.firstOrNull { it.key == promptId } ?: return false
        val lost = proc == null || entry.generation != processGeneration
        if (lost) promptLedger.remove(entry)
        lost
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
            origin = origin, // "via <bridge>" label (issue #91); null for interactive sessions
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

    suspend fun open(resumeId: String?, model: String?, effort: String? = null, fork: Boolean = false, takeOver: Boolean = false, sinceSeq: Long? = null) {
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
            // OpenCode: same guard as sendPrompt — only pass the real opencode sessionId, not the daemon's
            // SessionRegistry ID (which opencode doesn't know and hangs on). On first takeover there is no
            // opencode session yet → resumeId=null → fresh session.
            val opencodeResumeId = if (backend.kind == AgentKind.OPENCODE) sessionId else resumeId
            launchProcess(AgentSpec(workdir, opencodeResumeId, model, mode, effort = effort, forkSession = fork))
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
            // issue #96: no explicit --model AND nothing recovered from a transcript (a brand-new session, or a
            // resume whose transcript named no model) — eagerly resolve the backend's CONFIGURED default so the
            // header shows the real model before the first turn instead of a blank segment. Best-effort +
            // DEFENSIVE: any failure here must never crash or block the open (claude ≥1.3.1 crash-loops on
            // eager-resolve failures) — the runCatching leaves backfilledModel null and the phone renders its
            // "account default" placeholder. The first turn's init still wins (it clears backfilledModel).
            if (model == null && backfilledModel == null) {
                runCatching { backend.defaultModel(workdir.toString()) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { backfilledModel = it }
            }
            resumeContextUsed = resumeId?.let { runCatching { backend.resumeContextTokens(workdir.toString(), it) }.getOrNull() }
            // seed the degraded flag from the transcript's tail: a session that died over its context
            // window stays warned across close/reopen, not just while this daemon watched it fail
            if (resumeId != null) {
                failedTurnStreak = runCatching { backend.resumeFailedTurnStreak(workdir.toString(), resumeId) }.getOrDefault(0)
            }
            sink.emit(live(resumeId))
            if (resumeId != null) {
                // incremental reattach (issue #147): a client that still holds the transcript sends its
                // cursor and gets only the delta; anything un-honorable falls back to the full window
                // inside replaySlice. An EMPTY delta is never emitted — the client is already caught up,
                // and an empty non-delta ConvoHistory means /clear to it. A DELTA goes to the OPENER's
                // sink only (it continues that client's cursor); the full window keeps the fan-out.
                val slice = backend.replaySlice(workdir.toString(), resumeId, sinceSeq)
                if (slice.messages.isNotEmpty()) (if (slice.delta) openerSink else sink).emit(historyFrame(slice))
                replayWorkflowRuns(resumeId, sink)
            }
            emitCommands()
        }
    }

    /** One replayed window/delta as a wire frame — the single place the #147 cursor fields are stamped. */
    private fun historyFrame(slice: dev.ccpocket.daemon.disk.ReplaySlice) = ConvoHistory(
        convoId, slice.messages,
        lastSeq = slice.lastSeq, firstSeq = slice.firstSeq, delta = slice.delta, hasMore = slice.hasMore,
    )

    /** The phone scrolled to the top of its first-screen window — serve one page of OLDER history
     *  (issue #147). Answered to the REQUESTING sink only: other attached clients didn't ask and
     *  would prepend rows they may already hold. */
    suspend fun fetchHistoryPage(beforeSeq: Long, limit: Int, to: OutboundSink) {
        val sid = sessionId ?: openedResumeId ?: return
        val slice = backend.replayPage(workdir.toString(), sid, beforeSeq, limit.coerceIn(1, 200))
        to.emit(ConvoHistoryPage(convoId, slice.messages, firstSeq = slice.firstSeq, hasMore = slice.hasMore))
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

    /** Push one Workflow run's live snapshot (issue #106). Also counts as activity, like jobs. */
    private suspend fun emitWorkflow(taskId: String) {
        lastActivityMs = System.currentTimeMillis()
        workflows.snapshotFor(taskId)?.let { sink.emit(WorkflowUpdate(convoId, it)) }
    }

    /** A settled run's manifest lands on disk ~right after task_updated — read it (briefly retried)
     *  so the terminal card gains the final return + exact duration. Launched off the pump. */
    private fun patchWorkflowFromManifest(taskId: String, runId: String?) {
        if (runId == null) return
        scope.launch {
            var run: dev.ccpocket.protocol.WorkflowRun? = null
            repeat(3) { attempt ->
                if (run == null) {
                    delay(700L * (attempt + 1))
                    run = runCatching { WorkflowFiles.readRun(sessionDir() ?: return@launch, runId) }.getOrNull()
                }
            }
            val manifest = run ?: return@launch
            if (workflows.onManifest(taskId, manifest.runId, manifest.finalResult, manifest.durationMs, manifest.error)) {
                workflows.snapshotWithFinal(taskId, manifest.finalResult)?.let { sink.emit(WorkflowUpdate(convoId, it)) }
            }
        }
    }

    /** Re-emit the finished Workflow runs recorded on disk for a resumed/observed session — the replay
     *  sibling of the live [emitWorkflow] pushes (issue #106). Live (in-memory) runs win over their
     *  manifest snapshot; missing manifests (killed mid-run before the CLI wrote one) just don't list. */
    private suspend fun replayWorkflowRuns(resumeId: String, to: OutboundSink) {
        val dir = ProjectPaths.dirFor(workdir.toString()).resolve(resumeId)
        val runs = runCatching { WorkflowFiles.listRuns(dir) }.getOrNull().orEmpty()
        if (runs.isEmpty()) return
        val liveIds = workflows.snapshots().map { it.runId }.toSet()
        for (run in runs) {
            if (run.runId in liveIds) continue
            runCatching { to.emit(WorkflowUpdate(convoId, run)) }
        }
    }

    private fun sessionDir(): java.nio.file.Path? =
        sessionId?.let { ProjectPaths.dirFor(workdir.toString()).resolve(it) }

    /** The phone opened a workflow agent's detail sheet — read the full prompt/return off disk.
     *  Tries the LIVE session dir first, then the opened resume id's: a fork mints a new sessionId
     *  while replayed runs still live under the original transcript's directory. */
    suspend fun fetchWorkflowAgentDetail(runId: String, agentIndex: Int, agentId: String?) {
        var detail: WorkflowFiles.AgentDetail? = null
        if (agentId != null) {
            val root = ProjectPaths.dirFor(workdir.toString())
            for (sid in listOfNotNull(sessionId, openedResumeId).distinct()) {
                detail = runCatching { WorkflowFiles.readAgentDetail(root.resolve(sid), runId, agentId) }.getOrNull()
                    ?.takeIf { it.prompt != null || it.result != null }
                if (detail != null) break
            }
        }
        sink.emit(
            WorkflowAgentDetail(
                convoId, runId, agentIndex,
                prompt = detail?.prompt,
                result = detail?.result,
            ),
        )
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

    private fun armContinuationGrace() {
        continuationGraceUntil = System.currentTimeMillis() + continuationGraceMs
    }

    /** True while an unprompted continuation turn may still be coming (see [continuationGraceUntil]):
     *  the grace is armed and the agent process is alive to deliver it. */
    private fun continuationExpected(): Boolean =
        System.currentTimeMillis() < continuationGraceUntil && proc?.isAlive() == true

    /** True while the conversation is doing or awaiting anything that must outlive its owner leaving: a
     *  streaming turn, running background jobs, an unanswered permission/question card, or the bounded
     *  window in which the CLI is expected to start an unprompted continuation turn (plan mode's
     *  premature result / a just-completed background task — issue #105 residual). The shared
     *  keep-alive predicate for SessionRegistry.close/scheduleClose/reapIdle. */
    fun isBusy(): Boolean = isExecuting() || hasBackgroundWork() || hasPendingAsk() || continuationExpected()

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
     *
     * Only while the agent process is DEAD (or never started): a LIVE agent is the authoritative tracker of
     * its own background tasks — its completion `task_*` event WILL arrive on stdout (turns don't gate system
     * events), however long the task runs. Settling by clock under a live agent declared quiet long-running
     * work (a 20-min backgrounded build) dead at STALE_JOB_MS, dropped the conversation's reaper shield, and
     * the idle reaper then killed the process tree — the still-running build with it (issue #105's second
     * casualty path). The clock heuristic exists for the case the event source itself died and can no longer
     * report (agent killed outside the daemon / event lost to a crash), so gate it on exactly that.
     */
    suspend fun reapStaleJobs(staleMs: Long): Boolean {
        if (!jobs.hasRunning()) return false // idle conversation: nothing RUNNING to settle, skip the clock+scan
        if (proc?.isAlive() == true) return false // live agent: trust its eventual task_* completion, never the clock
        val changed = jobs.reapStale(System.currentTimeMillis(), staleMs)
        if (changed) sink.emit(BackgroundJobs(convoId, jobs.snapshot()))
        return changed
    }

    /**
     * The relaunch primitive: stop the agent and re-spawn it resuming [resumeId], rebuilding the spec from the
     * live `model`/`mode`/`effort` fields. Driven by sendPrompt's relaunch-then-send (issue #84): a Claude
     * model/mode/effort switch defers its relaunch to here — right before the next turn — so a running turn is
     * never interrupted. No pendingResumeId: a resume relaunch must not re-replay history.
     *
     * Fork decision: pre-first-turn ([sessionId] still null) reuse open()'s call — the desktop's liveness
     * hasn't changed just because the phone flipped a setting; the old `resumeId != sessionId` heuristic
     * forked a duplicate session here. Post-init, fork only for a genuinely foreign id (never today's callers).
     */
    private suspend fun relaunch(resumeId: String? = sessionId, armExecuting: Boolean = false, initialSend: InitialSend? = null) {
        stopProcess() // clears executing — the fresh launch re-arms it (armExecuting) with the right ordering
        val fork = if (sessionId == null) openedWithFork else resumeId != sessionId
        launchProcess(
            AgentSpec(workdir, resumeId = resumeId, model = model, mode = mode, effort = effort, forkSession = fork, initialPrompt = initialSend?.text),
            armExecuting = armExecuting,
            initialSend = initialSend,
        )
    }

    /** Switch the permission mode — next-turn semantics (issue #84): a running turn is never interrupted.
     *  Claude defers its relaunch to the next sendPrompt; Codex carries the new approval policy next turn. */
    suspend fun switchMode(newMode: PermissionMode) {
        if (newMode == mode) {
            // no-op, but still announce: an out-of-sync phone badge corrects itself from this
            sink.emit(live(sessionId))
            return
        }
        mode = newMode
        recordPendingSettings(mode = newMode, model = null, effort = null)
    }

    /** Switch the model — next-turn semantics (issue #84): the running turn is untouched; the change takes
     *  effect on the next turn (Claude relaunches then, Codex applies it in that turn's params). */
    suspend fun switchModel(newModel: String?) {
        model = newModel
        backfilledModel = null // an explicit choice replaces the transcript guess, even a choice of "default"
        recordPendingSettings(mode = null, model = newModel, effort = null)
    }

    /** Switch reasoning effort — next-turn semantics (issue #84), same deferral as switchModel. */
    suspend fun switchEffort(newEffort: String?) {
        effort = newEffort
        recordPendingSettings(mode = null, model = null, effort = newEffort)
    }

    /**
     * Record a mid-session mode/model/effort switch under NEXT-TURN semantics (issue #84) — a running turn is
     * NEVER interrupted. The caller has already updated the desired `mode`/`model`/`effort` field; this decides
     * how the change reaches the agent:
     *  - Pre-first-turn (no process yet, issue #61): record only — the deferred first-prompt launch bakes the
     *    fields into its AgentSpec, so nothing to relaunch.
     *  - Codex ([applySettings] returns false): the value is stashed for the next turn/start; no relaunch.
     *  - Claude ([applySettings] returns true): the flags are baked at launch, so applying the change needs a
     *    relaunch — but NOT now (it would kill the in-flight turn). Arm [pendingRelaunch]; the next sendPrompt
     *    relaunches under the new flags FIRST, then sends that turn to the fresh process (relaunch-then-send).
     * Either way the badge is optimistically re-announced; the resolved value confirms on the next init.
     */
    private suspend fun recordPendingSettings(mode: PermissionMode?, model: String?, effort: String?) {
        if (recordedPreFirstTurn()) return
        if (backend.applySettings(mode = mode, model = model, effort = effort)) pendingRelaunch = true
        sink.emit(live(sessionId))
    }

    fun clearAllowRule(rule: String?) {
        if (rule == null) allowRules.clear() else allowRules.remove(rule)
    }

    // Restricted-credential conversations (GUEST folder-share #115, BRIDGE #91) launch their agent
    // clean-room: no MCP servers (can't act through the owner's authenticated integrations) and — the part
    // that bit for real — `--setting-sources ""`, because the owner's own ~/.claude settings carry
    // permissions.allow rules accumulated for their PERSONAL convenience (a bare "Edit" was live on the
    // machine this shipped from), and the CLI honours those BEFORE the daemon's --permission-prompt-tool
    // ever hears about the call. A stranger in an IM chat inheriting the owner's "don't ask me again"
    // choices breaks the one promise the tier ceiling makes ("dangerous actions prompt your phone"), so
    // both restricted kinds strip the settings layer and the daemon stays the sole permission authority.
    // origin != null is exactly "bridge or guest": the scheduler and every interactive device open with a
    // null origin. One place to stamp it: every AgentSpec built above flows through here.
    private val cleanRoom: Boolean = launchesCleanRoom(pathScope, origin)

    private suspend fun launchProcess(rawSpec: AgentSpec, armExecuting: Boolean = false, initialSend: InitialSend? = null) {
        // OpenCode requires a message argument — can't launch without one (opencode run exits with error).
        // Defer to sendPrompt() which always provides initialPrompt.
        if (backend.kind == AgentKind.OPENCODE && rawSpec.initialPrompt == null) {
            log.info("$convoId skip launch (OpenCode needs a prompt — deferring to sendPrompt)")
            return
        }
        val spec = if (cleanRoom) rawSpec.copy(cleanRoom = true) else rawSpec
        intentionalStop = false
        pendingRelaunch = false
        processGeneration += 1
        log.info("$convoId launchProcess: kind=${backend.kind} workdir=$workdir resumeId=${spec.resumeId?.take(12)} initialPrompt=${spec.initialPrompt?.take(40)}")
        val pb = backend.processBuilder(spec)
        log.info("$convoId launchProcess: pb.cmd=${pb.command().joinToString(" ") { if (it.length > 40) it.take(20) + "…" else it }} pb.dir=${pb.directory()}")
        val p = AgentProcess.start(pb, scope)
        val io = AgentIo(writeLine = p::writeLine, emit = { sink.emit(it) }) // read sink dynamically (reattach)
        // Ask pushes ride the emit path for two flavors of conversation (issue #91 bridge + #138 owner):
        //  - BRIDGE (origin set, no pathScope): the ask frame fans out normally (the bridge's egress
        //    filter drops it; any interactive device that reattached sees it), AND the ask-push hook tells
        //    the owner's phone — the bridge itself can neither see nor answer the ask. The verdict window
        //    is stretched: nobody is watching an approval sheet live, the owner has to arrive via push.
        //  - OWNER sessions (origin == null, issue #138): the ask fans out to attached clients as always,
        //    but with the phone locked/offline (or nobody attached) the card goes unseen and the ask
        //    times out to a safe deny — the hook (presence-gated in the relay client) wakes the phone.
        //  - A GUEST (pathScope != null) answers its OWN asks — the ask fans out to the guest normally,
        //    and the owner must NOT be push-nudged for it (that inbox is the guest's, per the design's
        //    "requests go to <guest>" — issue #115 crypto review L2). Never hooked.
        val emitWithAskPush: suspend (dev.ccpocket.protocol.Frame) -> Unit =
            if (pathScope != null) { f -> sink.emit(f) }
            else { f ->
                sink.emit(f)
                if (f is dev.ccpocket.protocol.PermissionAsk) maybePushAsk(f)
            }
        val b = PermissionBridge(
            convoId, mode, scope, emitWithAskPush, allowRules, respond = backend::respondPermission,
            // verdict + question windows both default to the generous, env-configurable ApprovalTimeout.ms
            // (issue #100 unified them). Bridge-origin sessions keep issue #91's 120s FLOOR on top (#32):
            // the owner arrives via push → tap → reattach, so a deliberately short CC_POCKET_ASK_TIMEOUT_SEC
            // (clamp floor 30s, a fine preference when the phone is already in hand) must not shrink that
            // arrival window into an auto-deny.
            verdictTimeoutMs = if (origin != null) ApprovalTimeout.bridgeMs() else ApprovalTimeout.ms,
            questionTimeoutMs = if (origin != null) ApprovalTimeout.bridgeMs() else ApprovalTimeout.ms,
            // a bridge-origin ask is a one-off human decision (issue #91): never offer/honor "always
            // allow", so one owner approval can't be replayed by later attacker-supplied prompts
            forceNeverRemember = origin != null,
            // bridge defense-in-depth (issue #91): Bash gated by BridgeCommandPolicy + structured file tools
            // confined to the bound workdir (a bridge Read must not escape to ~/.ssh). Bridge only.
            bridgeSession = origin != null && pathScope == null,
            // GUEST folder-share (issue #115): confine every file tool to the shared roots
            pathScope = pathScope,
            workdir = workdir.toString(),
        )
        proc = p
        bridge = b
        // Bind IO + kick off any handshake (Codex initialize → thread/start) SYNCHRONOUSLY, before returning. The
        // lazy first prompt (issue #61) calls backend.sendPrompt right after this: sendPrompt reads the io attach
        // installs (Claude) and the thread state attach resets (Codex). Running attach inside the pump coroutine
        // (as before) let that first sendPrompt race ahead of it and silently drop the opening turn. attach only
        // KICKS OFF the handshake — its writes buffer on the process's stdin channel — so this can't block on the
        // agent; the pump below reads the replies (stdout is buffered until it starts).
        backend.attach(io, spec)
        // RE-INJECTION (issue #122 ③): whatever the LAST process took to its grave — prompts written to
        // its stdin (or its internal mid-turn queue) that never produced a consumption replay — is
        // re-handed to this fresh process, oldest first, before anything else rides it. This is the old
        // healSessionLock resend generalized to EVERY spawn: crash, shutdown, settings relaunch, lock heal.
        val redeliver = if (backend.promptDelivery == AgentPromptDelivery.STDIN_REPLAY) {
            promptsForRedelivery(processGeneration)
        } else {
            emptyList()
        }
        if (redeliver.isNotEmpty()) {
            executing = true // the re-injected prompts start a turn; TurnResult clears it as usual
            log.info("$convoId re-injecting ${redeliver.size} unconsumed prompt(s) into the fresh agent")
            redeliver.forEach { backend.sendPrompt(it.text, it.images) }
        }
        // LEDGER the launch's own prompt HERE — after the generation bump (so its entry is stamped with
        // THIS process, keeping releaseLostPrompt's generation check correct) but BEFORE the pump starts.
        // The caller still writes it (backend.sendPrompt) after we return; only the RECORD must precede
        // the pump, so the CLI's near-instant user-message echo settles the entry instead of orphaning it
        // (issue #122 record-vs-replay race — a spurious re-injection on the next relaunch). NOT re-sent
        // here: it is not in `redeliver` (recorded after that snapshot) — the caller owns its single write.
        initialSend?.let { recordPromptWritten(it.promptId, it.text, it.images) }
        // Arm `executing` for a turn-starting (re)launch BEFORE the pump can run (sendPrompt's lazy start /
        // settings relaunch): the pump's death-branch `executing = false` is then guaranteed to happen-after
        // this write, so an instant startup death always wins and can't be resurrected by a late arm on the
        // caller thread (the reap-flake / stranded-■ race). Ordered last so it also covers re-injection.
        if (armExecuting) executing = true
        // OpenCode startup watchdog: if the process hangs on launch (bad model, resume failure, etc.)
        // with zero stdout for OPENCODE_STARTUP_TIMEOUT_MS, kill it and surface an error — the pump
        // would otherwise block on `for (line in p.stdout)` forever (issue: opencode run with an
        // invalid --model on a resumed session exits neither stdout nor stderr, just hangs).
        if (backend.kind == AgentKind.OPENCODE) {
            scope.launch(CoroutineName("opencode-watchdog-$convoId")) {
                delay(OPENCODE_STARTUP_TIMEOUT_MS)
                // still the same process? (a relaunch already replaced it → no-op)
                if (proc === p && p.isAlive()) {
                    log.warn("$convoId OpenCode watchdog: no stdout in ${OPENCODE_STARTUP_TIMEOUT_MS}ms, killing process ${p.pid}")
                    intentionalStop = true
                    p.shutdown(eofGraceMs = 1_000, termGraceMs = 1_000, forceGraceMs = 1_000)
                    p.awaitExit()
                    // Null proc + clear state so the next sendPrompt triggers a fresh relaunch
                    // (without this, subsequent prompts would write into the dead stdin and be lost)
                    proc = null
                    executing = false
                    bridge?.cancelAll()
                    bridge = null
                    // Surface the last stderr (often the real cause) + a clear message
                    val why = p.lastStderr?.let { " — ${it.take(300)}" } ?: ""
                    sink.emit(PocketError(
                        "opencode_startup_timeout",
                        "OpenCode did not produce any output within ${OPENCODE_STARTUP_TIMEOUT_MS / 1000}s ($why). " +
                            "The model may be invalid or the session may be corrupted. Try a new session or a different model.",
                        convoId,
                    ))
                }
            }
        }
        scope.launch(CoroutineName("pump-$convoId")) {
            pump(p, b)
        }
    }

    /**
     * Fire the ask-push hook for a just-emitted [dev.ccpocket.protocol.PermissionAsk] (issues #91/#138).
     *
     * COALESCE (issue #91 LOW): a turn can raise several asks in quick succession; at most one approval
     * push per conversation per window so a burst can't spam the lock screen. The push is a "come look"
     * nudge — reattach + resurface shows every pending card, so one alert covers the batch. The stamp is
     * taken OPTIMISTICALLY (burst-safe: the pump thread sees it before the next ask parses) and rolled
     * back when the hook reports it didn't push (issue #138: an owner ask suppressed because the phone
     * was watching must not mute the next ask's push after the user walks away).
     */
    private fun maybePushAsk(f: dev.ccpocket.protocol.PermissionAsk) {
        val hook = askPushHookProvider() ?: return
        val now = System.currentTimeMillis()
        val prev = lastAskPushMs
        if (now - prev < askPushCoalesceMs) return
        lastAskPushMs = now
        val label = f.title.ifBlank { f.tool }
        // a REAL client must have the ask frame on the data plane — the scheduler's headless fire sink
        // is a black hole (issue #137/C1): counting it as "watched" suppressed the owner-ask push while
        // nobody could actually see or answer the card, timing the ask out to a safe deny.
        val watched = sinks.values.any { it.isWatching() }
        // off the pump: a control-plane push must never stall stdout parsing
        scope.launch {
            val pushed = runCatching { hook.onAskPending(workdir, sessionId, origin, label, watched) }.getOrDefault(false)
            // one line so a "why didn't my phone buzz" never again means grepping two hours of relay logs:
            // this is the daemon-side truth of whether an ask-push was even attempted.
            log.info("ask-push origin=${origin ?: "owner"} tool=$label watched=$watched → ${if (pushed) "queued to relay" else "not pushed"}")
            if (!pushed) lastAskPushMs = prev
        }
    }

    private suspend fun pump(p: AgentProcess, b: PermissionBridge) {
        var turnCompleted = false
        for (line in p.stdout) {
            lastActivityMs = System.currentTimeMillis()
            for (ev in backend.parse(line)) {
                when (ev) {
                    is AgentEvent.SessionInit -> {
                        if (backend.promptDelivery == AgentPromptDelivery.INITIAL_ARG_ONE_SHOT) settleInitialArgPrompt()
                        val firstTime = sessionId == null
                        val prevSid = sessionId
                        ev.sessionId?.let { newSid ->
                            // FORK convergence (issue #119): a --fork-session relaunch (heal-lock / take-over /
                            // conditional fork) reports a sessionId that differs from the one we resumed/ran —
                            // carry the parent's group membership onto the branch so a forked copy stays filed
                            // where the user put it. Parent = the id we were running, or (pre-first-turn) the
                            // resume anchor. A plain in-place resume reports the SAME id → no-op. Single choke
                            // point for every fork path, so we don't sprinkle inherit() across the callers.
                            val parentSid = prevSid ?: openedResumeId
                            if (parentSid != null && parentSid != newSid) {
                                runCatching { SessionGroups.inherit(workdir.toString(), parentSid, newSid) }
                            }
                            sessionId = newSid
                        }
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
                                val slice = backend.replaySlice(workdir.toString(), rid)
                                if (slice.messages.isNotEmpty()) sink.emit(historyFrame(slice))
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
                        if (ev.parentId == null) {
                            sink.emit(AssistantChunk(convoId, seq.getAndIncrement(), StreamPiece.Text(ev.text)))
                        }
                    }
                    is AgentEvent.AssistantThinking -> {
                        executing = true
                        if (ev.parentId == null) {
                            sink.emit(AssistantChunk(convoId, seq.getAndIncrement(), StreamPiece.Thinking(ev.text)))
                        }
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
                            // a Workflow's input is the whole orchestration script — preview its description,
                            // never 280 chars of raw source (issue #106)
                            isWorkflowTool(ev.name) -> ev.input.strField("description")?.ifBlank { null } ?: "workflow"
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
                        if (ev.parentId == null) {
                            finishSubagentFromResult(ev)
                        }
                        if (jobs.onToolResult(ev.toolUseId, ev.content, ev.isError, System.currentTimeMillis())) emitJobs()
                    }
                    is AgentEvent.BackgroundTaskStarted -> {
                        val now = System.currentTimeMillis()
                        if (ev.taskType == "local_workflow" && workflows.onTaskStarted(ev.taskId, ev.toolUseId, ev.workflowName, now)) emitWorkflow(ev.taskId)
                        if (jobs.onTaskStarted(ev.taskId, ev.toolUseId, ev.description, ev.taskType, now)) emitJobs()
                    }
                    is AgentEvent.BackgroundTaskUpdated -> {
                        finishSubagentFromTask(ev)
                        val now = System.currentTimeMillis()
                        if (workflows.onTaskSettled(ev.taskId, ev.status, now)) {
                            emitWorkflow(ev.taskId)
                            // the manifest lands right after — patch in the final return/duration when it does
                            patchWorkflowFromManifest(ev.taskId, workflows.snapshotFor(ev.taskId)?.runId?.takeIf { it.startsWith("wf_") })
                        }
                        if (jobs.onTaskUpdated(ev.taskId, ev.status, now)) {
                            // a settled background task triggers the CLI's unprompted follow-up turn
                            // (probed on 2.1.206) — hold the conversation until that continuation's
                            // own stream re-arms `executing`
                            armContinuationGrace()
                            emitJobs()
                        }
                    }
                    // Workflow orchestration (issue #106): the launch ack ties the chat card's tool_use to
                    // the run id; progress snapshots re-emit the whole run (transitions only — the CLI
                    // omits the array on pure activity ticks, so this is naturally coalesced)
                    is AgentEvent.WorkflowLaunched -> {
                        if (workflows.onLaunched(ev.toolUseId, ev.runId, ev.taskId, ev.workflowName, System.currentTimeMillis())) {
                            ev.taskId?.let { emitWorkflow(it) }
                        }
                    }
                    is AgentEvent.WorkflowProgress -> {
                        if (workflows.onProgress(ev.taskId, ev.toolUseId, ev.items, System.currentTimeMillis())) emitWorkflow(ev.taskId)
                    }
                    is AgentEvent.AssistantUsage -> lastCallUsage = ev
                    // the CLI's API-failure placeholder — never a real reply. Suppress the chunk (the
                    // TurnDone error row replaces it) but still arm `executing`: a turn did run (issue #65).
                    is AgentEvent.SyntheticReply -> {
                        executing = true
                        sawSyntheticThisTurn = true
                    }
                    is AgentEvent.TurnResult -> {
                        turnCompleted = true
                        if (backend.promptDelivery == AgentPromptDelivery.INITIAL_ARG_ONE_SHOT) settleInitialArgPrompt()
                        executing = false
                        // relaunch continuation grace anchor (issue #122 ⑤): this result may be a phantom
                        // (fable early result/fallback) — for RELAUNCH_GRACE ms after it, sendPrompt must
                        // not treat `!executing` as license to kill this process. NOTE: the ledger is NOT
                        // settled here — only a UserReplay proves a prompt was consumed (a result says
                        // nothing about prompts still sitting in the CLI's mid-turn queue).
                        lastTurnEndedMs = System.currentTimeMillis()
                        // a FOREGROUND sub-agent still tracked at turn end never delivered its result
                        // (interrupted / died) — settle its card so it can't spin forever. Background
                        // runs live across turns; task_notification (or stopProcess) settles those.
                        settleSubagents(includeBackground = false)
                        val synthetic = sawSyntheticThisTurn
                        sawSyntheticThisTurn = false
                        val interrupted = interruptRequested
                        interruptRequested = false
                        // plan mode's `result` is routinely PREMATURE (issue #55): the CLI continues
                        // unprompted toward its AskUserQuestion/ExitPlanMode (a fresh init follows the
                        // result within 0.1s — probed on 2.1.206). Hold the conversation for that
                        // continuation; a user-interrupted turn genuinely ends, so no grace then.
                        if (mode == PermissionMode.PLAN && !interrupted) armContinuationGrace()
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
                        // usage-limit reset moment (issue #137): parsed daemon-side so the phone can
                        // offer one-tap "auto-continue when the limit resets"; null for ordinary errors
                        sink.emit(
                            TurnDone(
                                convoId, ev.finalText, usage, error = error,
                                usageLimitResetAt = dev.ccpocket.daemon.relay.PushPolicy.usageLimitResetAtMs(error),
                            ),
                        )
                        // degraded tracking: consecutive placeholder-only turns mark the session as likely
                        // context-dead; announce transitions so clients warn + gate the next send (issue #65)
                        val wasDegraded = degraded()
                        failedTurnStreak = if (synthetic) failedTurnStreak + 1 else 0
                        if (degraded() != wasDegraded) sink.emit(live(sessionId))
                        // wake an offline phone (relay mode only; hook is null on LAN). Launched off the pump
                        // so a control-plane send never stalls stdout parsing. A failed turn carries [error]
                        // separately so the push is worded as a failure (usage-limit hits included — #138),
                        // never as a normal turn-complete.
                        pushHookProvider()?.let { hook -> val sid = sessionId; scope.launch { hook.onTurnComplete(workdir, sid, ev.finalText, error) } }
                    }
                    is AgentEvent.ControlRequest -> b.onControlRequest(ev)
                    is AgentEvent.ControlCancel -> b.onCancel(ev)
                    // the CLI's consumption receipt (issue #122): a top-level user replay proves the
                    // matching prompt reached the model — settle its ledger entry. A parent-tagged
                    // replay is a sub-agent's inner user line, never one of ours.
                    is AgentEvent.UserReplay -> if (ev.parentId == null) settlePromptReplay(ev.text)
                    is AgentEvent.Ignored -> {}
                    is AgentEvent.Unparseable -> {}
                }
            }
        }
        log.info("$convoId pump ended (intentionalStop=$intentionalStop)")
        if (!intentionalStop) {
            // superseded: a newer launch already owns this conversation (a relaunch raced this pump's
            // tail) — the old process's death is history, not an error, and must not touch shared state
            if (proc !== p) return
            // unexpected death: stdout EOF precedes the last transcript flush, so wait for the
            // real process exit before touching the file (intentional stops settle in stopProcess)
            executing = false // a dead process never delivers TurnResult
            p.awaitExit()
            if (backend.processMode == AgentProcessMode.ONE_SHOT_TURN && p.exitCode() == 0 && turnCompleted) {
                log.info("$convoId one-shot process completed normally (sid=${sessionId?.take(8) ?: "-"})")
                proc = null
                bridge?.cancelAll()
                bridge = null
                return
            }
            // workflows died with the process — settle them so no card pulses forever (#106)
            for (taskId in workflows.killRunning(System.currentTimeMillis())) emitWorkflow(taskId)
            backend.onProcessEnded(sessionId)
            if (healSessionLock(p)) return
            // opencode "Session not found" after state DB relocation or stale resume id
            // (e.g. XDG_STATE_HOME change): clear the resume lineage so the next spawn creates a
            // fresh session instead of looping on an id the agent can no longer locate.
            if (p.lastStderr?.contains(SESSION_NOT_FOUND) == true) {
                log.warn("$convoId opencode: session not found — clearing stale resumeId, fresh session on next prompt")
                sessionId = null; openedResumeId = null
            }
            // drop the dead handle (issue #122): the process took its stdin queue with it — with `proc`
            // still set, every later prompt would be written into a dead pipe and hollow-acked. Nulling
            // it makes the NEXT prompt lazy-respawn, and that spawn re-injects the unconsumed ledger.
            proc = null
            bridge?.cancelAll()
            bridge = null
            // carry the exit code + the agent's last stderr line: a --resume that dies before its first
            // init (bad session id, context overflow) used to surface as a bare "agent process ended"
            val why = p.lastStderr?.let { " — ${it.take(300)}" } ?: ""
            val summary = "agent process ended (exit ${p.exitCode() ?: "?"})$why"
            sink.emit(PocketError("process_exited", summary, convoId))
            // an UNEXPECTED death is exactly what a locked phone must hear about (issue #138): the
            // session died with no TurnDone push coming. Same hook + presence gate as a failed turn;
            // stderr rides as the error summary (a usage-limit refusal printed there words the push).
            // NOT within [DEATH_PUSH_QUIET_MS] of a TurnResult: a fatal turn error routinely kills the
            // process right after its result — that failure was already pushed, don't alert it twice.
            if (System.currentTimeMillis() - lastTurnEndedMs > DEATH_PUSH_QUIET_MS) {
                // the cleartext relay push must NOT carry raw process stderr (stack traces / absolute
                // paths / a value the CLI echoed into an error): NotifyPush rides the TEXT plane
                // unsealed. Keep the usage-limit wording — it reads as the limit refusal and carries the
                // reset epoch the phone needs (issue #137) — but for any other death send a generic
                // reason. The full stderr already rode the E2E PocketError above (security review 07-15).
                val pushReason = if (dev.ccpocket.daemon.relay.PushPolicy.isUsageLimit(summary)) summary
                    else "agent process ended (exit ${p.exitCode() ?: "?"})"
                pushHookProvider()?.let { hook -> val sid = sessionId; scope.launch { hook.onTurnComplete(workdir, sid, null, pushReason) } }
            }
        }
    }

    /**
     * claude ≥2.1 refuses a bare `--resume <id>` when ANY live process has that session registered
     * (~/.claude/sessions/<pid>.json — an interactive window, a `--bg` background agent, a leaked
     * zombie): it exits 1 at startup, before any stdout, with only a stderr hint to add
     * --fork-session. The daemon's writer-liveness heuristics can't see an idle holder (a held
     * session between turns never touches its transcript), so the refusal is only observable here,
     * at process death. Heal it: relaunch ONCE with --fork-session (branching a fresh id is the only
     * control path the CLI leaves for a held session) and re-hand the fresh process the prompt the
     * refused one took with it. Returns true when the death was handled (heal attempted); a failed
     * heal surfaces its own PocketError.
     */
    private suspend fun healSessionLock(p: AgentProcess): Boolean {
        if (lockForkRetried || p.lastStderr?.contains(SESSION_LOCK_MARKER) != true) return false
        val anchor = sessionId ?: openedResumeId ?: return false // nothing to fork off
        lockForkRetried = true
        openedWithFork = true // pre-init relaunches (mode/model switch) must keep the fork decision
        reemitLive = true // a mid-session heal mints a NEW sessionId — the next init must re-announce it
        log.info("$convoId resume ${anchor.take(8)}… refused (session held by a live agent) → retrying with --fork-session")
        proc = null // already dead — don't shutdown/clear jobs like stopProcess, just replace it
        bridge?.cancelAll()
        bridge = null
        val healed = runCatching {
            // the refused process took the prompt with it — it's still in the unconsumed ledger (no
            // replay ever came), so launchProcess re-injects it into the forked process (issue #122)
            if (hasUnconsumedPrompts()) sink.emit(AssistantChunk(convoId, seq.getAndIncrement(), StreamPiece.Text(FORK_NOTICE)))
            launchProcess(AgentSpec(workdir, resumeId = anchor, model = model, mode = mode, effort = effort, forkSession = true))
        }
        if (healed.isFailure) {
            sink.emit(
                PocketError(
                    "agent_unavailable",
                    "session is held by another running claude and the forked retry failed (${healed.exceptionOrNull()?.message})",
                    convoId,
                ),
            )
        }
        return true
    }

    /** ADD a re-opened device's view (fan-out — it no longer steals the stream from the others),
     *  replaying the transcript so far to the newcomer only. [sinceSeq] = the newcomer's transcript
     *  cursor (issue #147): only the delta past it is replayed when it can be honored. */
    suspend fun reattach(newSink: OutboundSink, sinceSeq: Long? = null) {
        sinks[sinkKey(newSink)] = newSink
        lastActivityMs = System.currentTimeMillis()
        // pre-first-turn the agent hasn't minted a sessionId yet — anchor on the resume id (same trick
        // as switchMode) so the reattach still confirms + replays instead of leaving a blank chat
        val sid = sessionId ?: resumeAnchor ?: return
        // executing rights the phone's stale ■: a turn that finished (or started) while it was away
        newSink.emit(live(sid))
        val slice = backend.replaySlice(workdir.toString(), sid, sinceSeq)
        if (slice.messages.isNotEmpty()) newSink.emit(historyFrame(slice))
        emitCommands()
        newSink.emit(BackgroundJobs(convoId, jobs.snapshot())) // a re-opened live session re-shows its running jobs
        // re-show workflow runs: live (in-memory) ones first, then finished manifests off disk (#106)
        for (run in workflows.snapshots()) runCatching { newSink.emit(WorkflowUpdate(convoId, run)) }
        replayWorkflowRuns(sid, newSink)
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
        // the client may resend after a lost ack, and a duplicate turn is worse than a duplicate receipt.
        // EXCEPT (issue #122 ④): when the ledger proves the earlier write was LOST (no consumption
        // replay + its process is gone), a re-ack would bury the prompt forever — forget the dedupe and
        // genuinely re-run it, so the App's turn-stalled auto-resend (#104) can actually rescue the turn.
        if (promptId != null && promptSeenBefore(promptId)) {
            if (!releaseLostPrompt(promptId)) {
                sink.emit(PromptAck(convoId, promptId))
                return
            }
            // fall through: promptId stays in seenPromptIds — it is being run for real right now
        }
        if (tryIntercept(text)) {
            promptId?.let { sink.emit(PromptAck(convoId, it)) } // handled by the daemon = delivered
            return
        }
        // RELAUNCH-THEN-SEND (issue #84): a mid-session model/mode/effort switch only recorded the desired value
        // + armed pendingRelaunch — relaunching then would have killed the in-flight turn. Now, before this next
        // turn goes out, reconcile a stale process: stop it and re-spawn under the new flags FIRST, then let the
        // send below hand this prompt to the FRESH process — so the switch takes effect on THIS turn (the very
        // next trigger), not the one after. Guarded by `!executing`: a mid-turn queued send can't relaunch
        // without killing the very turn we're protecting, so it rides the current process and the flag survives
        // for the next idle turn. `proc == null` needs no relaunch (the lazy launch below already bakes the
        // current fields); Codex never arms this (it applies settings per turn). A relaunch failure surfaces as a
        // PocketError (forgetting the id so the client can retry), mirroring the lazy-launch failure just below.
        //
        // CONTINUATION GRACE (issue #122 ⑤): `!executing` alone is NOT proof the process is idle — fable can
        // emit a premature result/fallback and keep working (`executing` only re-arms once the continuation's
        // next stdout event lands, and a silent thinking stretch emits nothing). So for RELAUNCH_GRACE ms after
        // the last TurnResult the relaunch also defers: this prompt rides the current process and pendingRelaunch
        // survives to the next idle send — exactly the established mid-turn behavior, never a killed turn. A
        // switch still lands promptly in the common case: a human's next message after a REAL turn end almost
        // always arrives past the grace window. (Deliberately NOT "recent stream activity" as liveness — a
        // silently-thinking fable would read as idle and get killed.)
        //
        // The same deferral covers [continuationExpected]: an armed unprompted-continuation window (plan
        // mode's premature result, a settled background task — issues #55/#105) is one more "the process
        // is NOT idle despite !executing" signal, so the relaunch waits it out too.
        //
        // (issue #104) snapshot the process state BEFORE the (re)launch below: a prompt acked during a fresh
        // spawn or a settings relaunch is exactly the window a client "delivered but no turn" (turnStalled) targets.
        val firstSpawn = proc == null
        log.info("$convoId sendPrompt: text=${text.take(40)} procNull=${proc == null} executing=$executing pendingRelaunch=$pendingRelaunch promptId=${promptId?.take(8)}")
        val relaunching = proc != null && !executing && pendingRelaunch && relaunchGraceElapsed() && !continuationExpected()
        // `executing` must be armed with a happens-before edge to the new pump: a process that dies
        // instantly at startup runs its death-branch `executing = false` on the pump thread, and that
        // clear MUST win. Arming AFTER the launch (as before) lost the race under load — the late `true`
        // stranded the conversation executing forever, never idle-reaped, a permanent ■ on the phone
        // (surfaced as a SessionRegistryReapTest CI flake). So the (re)launch paths arm it INSIDE
        // launchProcess right before `scope.launch(pump)` (armExecuting); only the already-live queued
        // send arms it here, where no new pump can race it.
        // the launch's own prompt, LEDGERED inside launchProcess before its pump starts (issue #122
        // record-vs-replay race); the queued-send branch below records it inline instead (no new pump).
        val initialSend = InitialSend(promptId, text, images)
        if (relaunching) {
            reemitLive = true // the post-relaunch init re-announces SessionLive with the fresh sessionId + model
            val relaunched = runCatching { relaunch(sessionId ?: if (backend.kind == AgentKind.OPENCODE) null else openedResumeId, armExecuting = true, initialSend = initialSend) }
            if (relaunched.isFailure) {
                executing = false // the relaunch never started a turn
                promptId?.let { synchronized(seenPromptIds) { seenPromptIds.remove(it) } }
                sink.emit(PocketError("agent_unavailable", "agent failed to relaunch for the new settings (${relaunched.exceptionOrNull()?.message})", convoId))
                return
            }
        } else if (proc == null) {
            // LAZY START (issue #61): a plain open no longer spawns the agent — the FIRST prompt does. Resume the id
            // open() recorded (openedResumeId), reusing its fork decision (openedWithFork — false for a plain open, so
            // this appends in place). Without this, the first message after a lazy open would hit the old `proc == null`
            // guard and be silently dropped. A spawn failure (CLI missing / bad resume id) surfaces as a PocketError so
            // it can't propagate out and wedge the inbound pump; proc stays null, so the next prompt simply retries.
            // This branch is ALSO the respawn after an unexpected process death (issue #122 — the pump nulls `proc`):
            // then the live sessionId is the anchor (the dead process's turns live in ITS transcript, not the
            // originally-resumed one), resumed in place — its own id is never a foreign id to fork off.
            // OpenCode: only use the real opencode sessionId from step_start; NEVER fall back to openedResumeId
            // (the daemon's SessionRegistry ID) — opencode hangs silently when asked to resume a session that
            // doesn't exist in its SQLite DB. On first launch sessionId is null → no --session → fresh session.
            val anchor = if (backend.kind == AgentKind.OPENCODE) sessionId else (sessionId ?: openedResumeId)
            val fork = if (sessionId == null) openedWithFork else false
            log.info("$convoId sendPrompt proc==null: anchor=$anchor model=$model mode=$mode effort=$effort initialPrompt=${text.take(40)}")
            val launched = runCatching {
                launchProcess(AgentSpec(workdir, anchor, model, mode, effort = effort, forkSession = fork, initialPrompt = text), armExecuting = true, initialSend = initialSend)
            }
            if (launched.isFailure) {
                executing = false
                log.warn("$convoId agent failed to start: ${launched.exceptionOrNull()?.message}")
                promptId?.let { synchronized(seenPromptIds) { seenPromptIds.remove(it) } }
                sink.emit(PocketError("agent_unavailable", "agent failed to start (${launched.exceptionOrNull()?.message})", convoId))
                return
            }
        } else if (backend.promptDelivery == AgentPromptDelivery.INITIAL_ARG_ONE_SHOT && proc != null) {
            promptId?.let { synchronized(seenPromptIds) { seenPromptIds.remove(it) } }
            sink.emit(PocketError("agent_busy", "${backend.kind} is still handling the current turn; retry when it finishes.", convoId))
            return
        } else {
            log.info("$convoId sendPrompt queued-send: proc!=null backend=${backend.kind}")
            executing = true
            recordPromptWritten(promptId, text, images)
        }
        lastActivityMs = System.currentTimeMillis()
        lockForkRetried = false // each user prompt re-arms one heal
        backend.sendPrompt(text, images)
        promptId?.let {
            sink.emit(PromptAck(convoId, it)) // the turn is in the agent's hands — receipt (issue #66)
            // (issue #104) an ack is NOT a started turn. If the client later reports turnStalled for this prompt,
            // this line pins whether the ack landed during a spawn/relaunch window (write possibly lost) or steady state.
            log.info("$convoId acked prompt ${it.take(8)}… → agent (firstSpawn=$firstSpawn relaunch=$relaunching)")
        }
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
        val wasExecuting = executing // switchModel doesn't touch it, but read before any await to be safe
        switchModel(arg)
        // issue #84: don't splice a confirmation + TurnDone into a running turn's stream (it would prematurely
        // clear the phone's ■ and inject the notice mid-reply). Mid-turn the optimistic SessionLive badge is the
        // feedback; confirm in chat only when idle. Either way the switch lands on the next turn.
        if (!wasExecuting) reply("✓ Model switched to \"$arg\" for this session. Your next message will use it.")
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
        val wasExecuting = executing
        switchEffort(arg)
        // issue #84: as in handleModelCommand — no mid-stream confirmation; the badge is the feedback when a
        // turn is in flight, and the switch still takes effect on the next turn.
        if (!wasExecuting) reply("✓ Reasoning effort set to \"$arg\" for this session. Your next message will use it.")
    }

    /**
     * Handle the phone's `/clear` — there is no stream-json "clear", so the daemon starts a fresh session in
     * the same cwd (no resume), keeping the chosen model/effort/mode. The phone's transcript is wiped via an
     * empty history; the next turn lands on a brand-new sessionId.
     */
    private suspend fun handleClearCommand() {
        stopProcess() // also clears + re-emits background jobs (the killed tree took its bg shells with it)
        clearPromptLedger() // a wiped session must not re-inject the old one's undelivered prompts (issue #122)
        sessionId = null
        openedResumeId = null // brand-new session — no resume lineage left to preserve
        openedWithFork = false
        backfilledModel = null
        failedTurnStreak = 0 // a fresh session starts healthy — the degraded warning belongs to the old transcript
        sawSyntheticThisTurn = false
        // fresh window — the live(null) below (and the init backfill announce) must not carry the wiped
        // session's occupancy, which re-seeded the phone's "Context NN%" statusline post-clear (issue #149)
        resumeContextUsed = null
        lastCallUsage = null // nor may a killed mid-flight turn's usage leak into the fresh session's first TurnDone
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
     * Arm the user-cancel interrupt (Claude: stream-json control, Codex: turn/interrupt — either way the
     * turn aborts and the session/process stay alive). Only WHILE a turn is executing: arming
     * [interruptRequested] with no turn to consume it (a ■ racing TurnDone, stopping a job that lingered
     * past its turn) leaves the flag set until the NEXT turn and repaints that turn's genuine failure as
     * a clean user cancel.
     */
    private suspend fun requestInterrupt() {
        if (!executing) return
        interruptRequested = true // the coming result's is_error is the user's stop, not a failure to paint red
        backend.interrupt()
    }

    /** Interrupt the in-flight turn (phone composer ■). */
    suspend fun cancelTurn() {
        if (proc == null) return
        lastActivityMs = System.currentTimeMillis()
        requestInterrupt()
    }

    /** A live agent process is attached right now — its CLI holds (and appends) the transcript. */
    fun hasLiveProcess(): Boolean = proc?.isAlive() == true

    /** Rename the session THROUGH the live agent (issue #158): the CLI appends its own `custom-title`
     *  record and acks, so the daemon never appends to a transcript its child is writing. False = not
     *  acked (no live process / backend without rename support / rejected / timeout) — the caller
     *  reports it; it must NOT fall back to a disk append while this process lives. */
    suspend fun renameSession(title: String): Boolean {
        if (!hasLiveProcess()) return false
        return backend.renameSession(title)
    }

    /**
     * Stop ONE background job from the phone's task panel (issue #80). The daemon's only lever over the
     * agent's work is the interrupt control (same primitive as [cancelTurn] / the composer ■) — it can't
     * reach into the agent's process tree to signal one detached OS shell (that is the model's own
     * KillShell). So we settle the targeted job KILLED for immediate panel feedback and fire an interrupt,
     * which genuinely aborts the turn-bound work a RUNNING job usually is: a stuck foreground command
     * (the gcloud-auth-waiting-on-a-callback case), a monitor, or a sub-agent. A lingering turn's is_error
     * is suppressed like a user cancel. No-op if the job is unknown or already finished.
     */
    suspend fun stopBackgroundJob(jobId: String) {
        if (!jobs.markKilled(jobId, System.currentTimeMillis())) return
        requestInterrupt()
        emitJobs() // reflect KILLED in the panel now (also stamps lastActivityMs)
    }

    /** Default semantics: kill the current process tree and start a fresh session in the new cwd. */
    suspend fun switchDirectory(newWorkdir: Path) {
        stopProcess()
        clearPromptLedger() // fresh session in a new cwd — the old session's undelivered prompts die with it (issue #122)
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
        // workflows run INSIDE the CLI process — settle any still-running run as KILLED, not a forever-pulse (#106)
        for (taskId in workflows.killRunning(System.currentTimeMillis())) emitWorkflow(taskId)
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

    companion object {
        val EFFORT_LEVELS = setOf("low", "medium", "high", "xhigh", "max")

        // claude's session-lock refusal on stderr: "Error: Session <id> is currently running as a
        // background agent (<kind>). … add --fork-session to branch off a copy." The kind varies
        // (bg / interactive / …); this prefix doesn't. Probed on 2.1.204 — scripts/probe-claude-wire.py
        // `lock` scenario guards the wording against CLI drift.
        const val SESSION_LOCK_MARKER = "is currently running as a background agent"

        // opencode emits this on stderr when the --session id is not in its state DB (state DB
        // was relocated or the id never existed). The daemon must clear its resume lineage on
        // this error so subsequent spawns start fresh instead of repeating the same failure.
        const val SESSION_NOT_FOUND = "Session not found"

        // prepended to the healed turn so the fork isn't silent — the user sees why a new session
        // id appears in their list instead of suspecting the "duplicate sessions" bug class
        const val FORK_NOTICE = "⑂ This session is held by another running claude (`claude agents`), " +
            "so your message continues in a forked copy.\n\n"

        // consecutive placeholder-only turns before the session is announced degraded (issue #65)
        const val DEGRADED_STREAK = 2

        // delivered promptIds kept for retry dedupe — well past any realistic resend window
        const val SEEN_PROMPTS_MAX = 64

        // unconsumed-prompt ledger bounds (issue #122): more pending prompts than this means something
        // is deeply wrong upstream — cap so pinned base64 image payloads can't grow without bound
        const val LEDGER_MAX = 16

        // spawns an entry may be re-injected into without ever seeing its consumption replay — then it's
        // dropped, so replay-parse drift can't turn every future relaunch into a duplicate turn
        const val MAX_REDELIVERIES = 3

        // continuation grace after a TurnResult before a settings relaunch may kill the process (issue
        // #122 ⑤) — covers fable's phantom early result + the silent-thinking stretch that follows it
        const val RELAUNCH_GRACE_PROP = "ccpocket.relaunch.graceMs"
        const val RELAUNCH_GRACE_DEFAULT_MS = 15_000L

        // in-flight sub-agent cards tracked at once (issue #77) — parallel fan-outs stay well under this
        const val MAX_SUBAGENTS = 16

        // cap on a sub-agent report crossing the wire in a ToolEvent/HistoryMessage (4 MiB frame budget)
        const val SUBAGENT_OUTPUT_MAX = 4000

        // how long after a "continuation expected" trigger the conversation stays [isBusy]. Covers the
        // continuation's pre-first-token silence in the worst case the grace exists for (API retry
        // backoff) — the happy path re-arms `executing` within seconds (probed: plan continuation's
        // init follows the premature result by 0.1s, bg-completion's by 0.1s), so this only ever
        // DELAYS a reap when the continuation never comes at all.
        const val CONTINUATION_GRACE_MS = 5 * 60 * 1000L

        // at most one approval push per conversation per this window (issue #91 bridge, #138 owner). The
        // verdict windows themselves are unified under agent.ApprovalTimeout.ms (issue #100) — see
        // PermissionBridge. Only a push that actually went out spends the window (see [maybePushAsk]).
        const val ASK_PUSH_COALESCE_MS = 60_000L

        // OpenCode: max time to wait for first stdout after process launch before declaring it hung.
        // opencode run with an invalid --model on a resumed session hangs silently (no stdout, no stderr,
        // no exit) — the pump would block forever without this watchdog.
        const val OPENCODE_STARTUP_TIMEOUT_MS = 45_000L

        // a process death this soon after a TurnResult is the SAME failure the turn's push already
        // reported (a fatal error result is often followed by the CLI exiting) — no second alert (#138)
        private const val DEATH_PUSH_QUIET_MS = 10_000L

        /** True when a conversation's agent must launch CLEAN-ROOM (no MCP, no settings sources — the
         *  daemon is the sole permission authority). Exactly the restricted-credential conversations:
         *  a GUEST share carries a [pathScope]; a BRIDGE carries an [origin]; the scheduler and every
         *  interactive device carry neither. Pinned by ConversationCleanRoomTest — a regression here
         *  silently re-opens the owner-settings bypass (a stranger inheriting "don't ask me again"). */
        fun launchesCleanRoom(pathScope: List<String>?, origin: String?): Boolean =
            pathScope != null || origin != null
    }
}
