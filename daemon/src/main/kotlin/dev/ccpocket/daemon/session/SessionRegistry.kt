package dev.ccpocket.daemon.session

import dev.ccpocket.observability.*

import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.conversation.AskPushHook
import dev.ccpocket.daemon.conversation.Conversation
import dev.ccpocket.daemon.conversation.DEVICE_SINK_KEY_PREFIX
import dev.ccpocket.daemon.conversation.KeyedSink
import dev.ccpocket.daemon.conversation.ObserveSession
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.conversation.PromptFate
import dev.ccpocket.daemon.conversation.PushHook
import dev.ccpocket.daemon.conversation.sinkKey
import dev.ccpocket.daemon.codex.CodexPaths
import dev.ccpocket.daemon.disk.LiveProcesses
import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.daemon.disk.SessionGroups
import dev.ccpocket.daemon.disk.TranscriptScanner
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AuthBlockReason
import dev.ccpocket.protocol.AuthBlocker
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.GetWorkflowAgentDetail
import dev.ccpocket.protocol.ClearAllowRule
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PendingApproval
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.StopBackgroundJob
import dev.ccpocket.protocol.SwitchDirectory
import dev.ccpocket.protocol.SwitchMode
import dev.ccpocket.protocol.SwitchServiceTier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime

/** convoId -> live [Conversation]. Create on open (picking the backend by [OpenSession.agent]),
 *  relaunch on switch-dir, reap on close. */
class SessionRegistry(
    private val scope: CoroutineScope,
    private val backends: Map<AgentKind, AgentBackendFactory>,
    // "is a claude OUTSIDE the daemon alive on this workdir/transcript?" — injectable so the fork/observe
    // decision matrix is unit-testable; the real probe shells out to lsof (LiveProcesses.externalClaudeAt)
    private val processProbe: (workdir: String, transcript: Path) -> LiveProcesses.ExternalClaude =
        LiveProcesses::externalClaudeAt,
    // Codex equivalent of [processProbe]. Kept separate so the existing Claude decision-matrix tests and
    // call sites stay source-compatible while Codex gets the same observe/take-over safety semantics.
    private val codexProcessProbe: (workdir: String, transcript: Path) -> LiveProcesses.ExternalClaude =
        LiveProcesses::externalCodexAt,
    // Resolve an agent's durable transcript. Injectable so Codex observe tests use a temp rollout rather
    // than the developer's real ~/.codex tree.
    // Derived from the registered backends (issue #301): "which file owns this session id" is a backend
    // capability, not registry knowledge — a new backend gets observe/take-over safety by overriding
    // AgentBackend.transcriptPath, no registry edit. The wire-input guard (separators/dot-dot) lives in
    // each implementation. Injectable so Codex observe tests use a temp rollout.
    private val transcriptResolver: (agent: AgentKind, workdir: String, sessionId: String) -> Path? = { agent, workdir, sessionId ->
        backends[agent]?.create()?.transcriptPath(workdir, sessionId)
    },
    // Claude transcript root — injectable ONLY so [renameSession]'s disk write is unit-testable against
    // a temp dir instead of the user's real ~/.claude/projects (every other path resolves via the
    // backends / ProjectPaths directly, same default)
    private val projectsRoot: Path = ProjectPaths.projectsRoot(),
    // the daemon-wide pending-approval ledger (approval design M1) — every conversation's gates register
    // their asks here; defaulted so tests that never exercise approvals need no wiring
    private val approvals: dev.ccpocket.daemon.approval.ApprovalCoordinator =
        dev.ccpocket.daemon.approval.ApprovalCoordinator(scope),
    // the daemon-wide task-grant engine (approval design M2), shared with the quick terminal
    private val grants: dev.ccpocket.daemon.approval.ApprovalGrantStore =
        dev.ccpocket.daemon.approval.ApprovalGrantStore(),
    // M3 deterministic risk radar (advisory) — daemon-wide so the sequence ledger survives relaunches
    private val riskEngine: dev.ccpocket.daemon.approval.ApprovalRiskEngine? =
        dev.ccpocket.daemon.approval.ApprovalRiskEngine(),
) {
    private val mutex = Mutex()
    private val log = dev.ccpocket.daemon.util.logger("SessionRegistry")
    private val convos = mutableMapOf<String, Conversation>()
    private val observes = mutableMapOf<String, ObserveSession>()
    /** A shared conversation can have several LAN viewers disconnect inside the same grace window. Each
     *  dead connection owns an independent cleanup; keying only by convoId made the newest disconnect
     *  cancel the older one's cleanup and leave its sink attached forever. */
    private data class PendingCloseKey(val convoId: String, val ownerKey: Any)
    private val pendingClose = mutableMapOf<PendingCloseKey, Job>()
    private data class LiveReattachClaim(val staleClose: Job?)

    /** Conversations with a rewind PAST its point of no return (issue #282), guarded by [mutex]. The
     *  frame arrives on the router's scope, so two copies of one request — a duplicate tap, a resend
     *  after a flaky link — can both clear the admission gate before either has closed anything, and
     *  would then branch the same session twice. The claim is what makes the swap happen at most once. */
    private val rewinding = mutableSetOf<String>()

    /**
     * SINGLE-FLIGHT OPEN (lifecycle design S8 / D10), guarded by [mutex]: a cold open of one persistent
     * identity (the resume id) in progress. The live lookup and the slow create (the external-writer probe may
     * run lsof, [Conversation.open] reads disk) used to be two critical sections apart, so two opens of one id
     * could both miss and both create — two conversations, two writers. The first open registers here in the
     * SAME critical section as its live miss; a concurrent open waits for it and then reattaches to what it
     * created (or repeats the observe decision when it created none). A brand-new session (no resume id)
     * needs no de-duplication.
     */
    private val opening = mutableMapOf<String, CompletableDeferred<Unit>>()

    /**
     * CLOSE TOMBSTONES (S8), guarded by [mutex]: a conversation removed from [convos] whose [Conversation.close]
     * has not returned yet, keyed by its persistent identity (sessionId and resume anchor). Removal and close
     * are separate steps — the close runs off the lock and spends the process's shutdown ladder — so a re-open
     * in between used to find no live match and spawn a second process while the first still held the session
     * (Claude then refuses the resume and the open forks). A re-open now waits for the tombstone, bounded by
     * [TOMBSTONE_WAIT_MS], then opens cold as before.
     */
    private val closing = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** One removed conversation's tombstone: the keys it was filed under and the signal its close sets. */
    private class Tombstone(val keys: List<String>) {
        val done = CompletableDeferred<Unit>()
    }

    /** Caller holds [mutex] and has just removed [c] from [convos]: file its tombstone. Null when [c] has no
     *  persistent identity yet (a new session before its first turn — nothing can re-open it by id). */
    private fun tombstoneLocked(c: Conversation): Tombstone? {
        val keys = listOfNotNull(c.sessionId, c.resumeAnchor).distinct()
        if (keys.isEmpty()) return null
        return Tombstone(keys).also { t -> keys.forEach { closing[it] = t.done } }
    }

    /** The close behind [t] has returned: lift the tombstone (a newer one filed under the same key stays). */
    private suspend fun finishClose(t: Tombstone?) {
        if (t == null) return
        withContext(NonCancellable) {
            mutex.withLock { t.keys.forEach { if (closing[it] === t.done) closing.remove(it) } }
            t.done.complete(Unit)
        }
    }

    /** Close conversations already removed (with their tombstones) from [convos]: close, then record the
     *  self-close, and only then lift the tombstone — a waiting re-open's external-writer probe must already
     *  see our own close tail as ours. Lifted even when the close throws. */
    private suspend fun closeRemoved(c: Conversation, t: Tombstone?) {
        try {
            c.close()
            noteSelfClosed(c)
        } finally {
            finishClose(t)
        }
    }

    /** The live conversation [resume] reattaches to — matched on convoId, the agent-reported id, or (pre-first-
     *  turn) the resume anchor. Caller holds [mutex]. */
    private fun liveMatchLocked(resume: String): Conversation? =
        convos.values.firstOrNull {
            it.convoId == resume || it.sessionId == resume || (it.sessionId == null && it.resumeAnchor == resume)
        }

    private sealed interface OpenStep {
        /** A live conversation already serves the id: reattach. */
        class Live(val convo: Conversation) : OpenStep
        /** This open owns the id's cold open; it MUST be released (see [releaseOpen]). */
        class Claimed(val done: CompletableDeferred<Unit>) : OpenStep
    }

    /**
     * Decide, in ONE critical section, between "reattach to the live conversation" and "own the cold open" of
     * [resume] — waiting first for an open of the same id already in flight (unbounded: it always settles, see
     * [releaseOpen]) and for a close still in progress (bounded; past the bound the open proceeds as before and
     * says so in the log, so a wedged close can never wedge every later open of the session).
     */
    private suspend fun liveOrClaim(resume: String): OpenStep {
        var ignoredTombstone: CompletableDeferred<Unit>? = null
        while (true) {
            var waitOpen: CompletableDeferred<Unit>? = null
            var waitClose: CompletableDeferred<Unit>? = null
            val step = mutex.withLock {
                opening[resume]?.let { waitOpen = it; return@withLock null }
                liveMatchLocked(resume)?.let { return@withLock OpenStep.Live(it) }
                closing[resume]?.takeIf { it !== ignoredTombstone }?.let { waitClose = it; return@withLock null }
                OpenStep.Claimed(CompletableDeferred<Unit>().also { opening[resume] = it })
            }
            if (step != null) return step
            waitOpen?.let {
                log.info("open ${resume.take(8)}… → waiting for a concurrent open of the same session")
                it.await()
            }
            waitClose?.let {
                log.info("open ${resume.take(8)}… → waiting for the previous conversation of this session to finish closing")
                if (withTimeoutOrNull(TOMBSTONE_WAIT_MS) { it.await() } == null) {
                    log.warn("open ${resume.take(8)}… → the previous conversation did not finish closing within ${TOMBSTONE_WAIT_MS}ms — opening anyway")
                    ignoredTombstone = it
                }
            }
        }
    }

    /** Release a [OpenStep.Claimed] open of [resume] — always, even when the open failed or was cancelled. */
    private suspend fun releaseOpen(resume: String, done: CompletableDeferred<Unit>) {
        withContext(NonCancellable) {
            mutex.withLock { if (opening[resume] === done) opening.remove(resume) }
            done.complete(Unit)
        }
    }

    /** Test-only race seam: invoked after a grace delay but before that timer claims the mutex. A
     *  replacement can supersede the awakened timer here, pinning the cancellation boundary. */
    internal var beforePendingCloseClaim: (suspend () -> Unit)? = null

    /** Test-only race seam between the optimistic live lookup and its authoritative atomic claim. */
    internal var beforeLiveReattachClaim: (suspend () -> Unit)? = null

    /** Test-only race seam (lifecycle design S0): a cold open has decided to CREATE a conversation (no live
     *  match) and is about to insert it. Two opens of one resume id parked here are the D10 double-create. */
    @Volatile
    internal var beforeColdInsert: (suspend () -> Unit)? = null

    /** Test-only race seam (lifecycle design S0): the reaper has removed a conversation from the map and is
     *  about to close it — the window in which a re-open sees no live match while the old process still runs. */
    @Volatile
    internal var beforeReapClose: (suspend (Conversation) -> Unit)? = null

    /** Test-only handle on the LIVE conversation behind [convoId] (issue #375), so a test can read the
     *  authoritative state — [Conversation.currentMode], fan-out membership — and install
     *  [Conversation.fanOutProbe], instead of inferring any of it from one client's frame history.
     *  Null means "no live conversation", which is exactly what makes [switchMode] a silent no-op. */
    internal suspend fun conversationForTest(convoId: String): Conversation? = get(convoId)

    // live LAN sockets — the reaper must treat "a phone is attached over LAN" like relay peerOnline,
    // else a LAN session idle past the reap window is killed under the user's thumbs
    private val lanConnections = java.util.concurrent.atomic.AtomicInteger(0)

    fun onLanConnect() { lanConnections.incrementAndGet() }
    fun onLanDisconnect() { lanConnections.decrementAndGet() }
    fun lanConnected(): Boolean = lanConnections.get() > 0

    // sessions the DAEMON itself closed recently: sessionId -> closedAt (bounded LRU, guarded by [mutex]).
    // A close right after an assistant reply leaves a genuinely fresh transcript mtime; without this record,
    // re-entering within the 20s liveness window misreads our own last write as "an external claude is
    // writing" — bogus observe banner, and a take-over would fork a duplicate (issue #33 residual).
    private val selfClosed = object : LinkedHashMap<String, Long>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > 64
    }

    /** When THIS daemon process booted — see the restart-amnesia guard in [externallyActive]. */
    private val startedAt = System.currentTimeMillis()

    /** True if [file]'s recent mtime is explained by an EXTERNAL writer — i.e. recently written AND not
     *  merely the tail of a session we closed ourselves (writes after our close mean a real foreign claude).
     *  mtime freshness is only the cheap GATE: a hit is confirmed by a real process probe (is a claude
     *  outside the daemon actually alive on [workdir]?), because a terminal claude the user just quit
     *  leaves a fresh mtime for up to 20s — trusting it blindly forked take-overs and demoted plain opens
     *  to read-only observe against a writer that no longer exists (the main "mystery fork" source).
     *  Internal (not private) so the decision matrix is unit-testable with a stubbed [processProbe]. */
    internal suspend fun externallyActive(
        sessionId: String,
        workdir: String,
        file: Path,
        agent: AgentKind = AgentKind.CLAUDE,
    ): Boolean {
        // one stat serves both the freshness gate and the ownership checks below
        val mtime = runCatching { if (file.exists()) file.getLastModifiedTime().toMillis() else null }.getOrNull() ?: return false
        // Which probe answers for this agent: the two named params stay as the historical test seams for
        // Claude/Codex; any OTHER backend brings its own via AgentBackend.externalWriterProbe (issue #301).
        val probeFor: (String, Path) -> LiveProcesses.ExternalClaude = when (agent) {
            AgentKind.CLAUDE -> processProbe
            AgentKind.CODEX -> codexProcessProbe
            else -> { wd, f ->
                backends[agent]?.create()?.externalWriterProbe(wd, f) ?: LiveProcesses.ExternalClaude.UNKNOWN
            }
        }
        // A backend whose CLI holds its transcript fd even while idle (Codex; declared via
        // holdsTranscriptWhileIdle) is probed BEFORE the freshness and restart-amnesia gates: an
        // hours-old but still-owned rollout must remain read-only. The production Codex probe only
        // permits cwd matching for fresh rollouts, so this cannot promote unrelated old sessions.
        val holdsWhileIdle = agent == AgentKind.CODEX || backends[agent]?.create()?.holdsTranscriptWhileIdle == true
        val earlyProbe = if (holdsWhileIdle) {
            runCatching { withContext(Dispatchers.IO) { probeFor(workdir, file) } }
                .getOrDefault(LiveProcesses.ExternalClaude.UNKNOWN)
        } else null
        if (earlyProbe == LiveProcesses.ExternalClaude.PRESENT) {
            log.info("externallyActive(${sessionId.take(8)}…): $agent holds exact transcript → true")
            return true
        }
        if (earlyProbe == LiveProcesses.ExternalClaude.UNKNOWN) {
            // UNKNOWN from an idle-holder probe means external processes EXIST but transcript ownership
            // could not be verified (lsof failure/timeout, or Windows where fd probing is impossible —
            // "none at all" reports ABSENT, not UNKNOWN). An idle holder keeps an OLD mtime, so the
            // freshness gate below would answer false — the exact wrong direction for a backend with no
            // session lock (a silent double-writer, probed on codex app-server). Take the safe verdict
            // instead: observe / fork-on-take-over. A spurious fork is recoverable; a clobbered
            // transcript is not (PR #296 review).
            log.info("externallyActive(${sessionId.take(8)}…): $agent transcript ownership unverifiable → assume held")
            return true
        }
        if (System.currentTimeMillis() - mtime >= TranscriptScanner.LIVE_WINDOW_MS) return false
        // Restart amnesia: a write that predates this daemon's boot came from our PREVIOUS instance's own
        // claude (children die with the daemon, and the restart wiped [selfClosed], which would otherwise
        // prove ownership). Never read it as a foreign writer — the app auto-reopens its session seconds
        // after a daemon update, and landed in read-only observe with a spurious "Continue here" banner.
        // A real terminal claude keeps writing, so its mtime moves past our boot within one turn.
        if (mtime < startedAt) return false
        val closedAt = mutex.withLock { selfClosed[sessionId] }
        val now = System.currentTimeMillis()
        if (closedAt != null && mtime <= closedAt + SELF_CLOSE_SLACK_MS) {
            log.info("externallyActive(${sessionId.take(8)}…): mtime ${now - mtime}ms ago is our own close tail (selfClosed ${now - closedAt}ms ago) → false")
            return false
        }
        // mtime alone can't tell "terminal agent still running" from "user quit it seconds ago" — ask the
        // OS. Only reached on a fresh foreign-looking mtime, so the lsof cost stays off every ordinary open.
        // UNKNOWN (Windows / lsof failure / timeout) keeps the old mtime verdict: a wrongly forked session
        // is recoverable, two writers clobbering one transcript is not.
        val probe = earlyProbe ?: runCatching { withContext(Dispatchers.IO) { probeFor(workdir, file) } }
            .getOrDefault(LiveProcesses.ExternalClaude.UNKNOWN)
        val verdict = probe != LiveProcesses.ExternalClaude.ABSENT
        log.info(
            "externallyActive(${sessionId.take(8)}…): mtime ${now - mtime}ms ago, " +
                "selfClosed ${closedAt?.let { "${now - it}ms ago" } ?: "absent"}, probe=$probe → $verdict",
        )
        return verdict
    }

    /** Installed by the relay client; null in local-server mode. Read per turn so a conversation opened
     *  before the relay attached still sees it. */
    @Volatile
    var pushHook: PushHook? = null

    /** Installed by the relay client: how a pending permission ask reaches a human who isn't watching —
     *  a BRIDGE conversation's owner (issue #91: the ask frame never goes to the bridge) or an owner
     *  session's locked/away phone (issue #138). Null in local-server mode. */
    @Volatile
    var askPushHook: AskPushHook? = null

    /** issue #360: receives every trusted native session id a conversation's backend reports (Claude and Codex
     *  alike — the Conversation's SessionInit choke point), read at report time. Null = managed list not wired. */
    @Volatile var managedSessions: NativeSessionHook? = null

    /**
     * Read-only observation policy (docs/design/DOTS-SESSION-OBSERVABILITY.md §4.3): the PERSISTED binding of
     * (agent, workdir, sessionId), consulted before any control path — plain open, take-over, rename. Null =
     * no managed store wired (nothing is bound). An [ObservationLookup.Unavailable] answer (corrupt / unreadable
     * store) refuses control: a policy this build cannot read never degrades to "writable".
     */
    @Volatile var observationPolicy: ((agent: AgentKind, workdir: String, sessionId: String) -> dev.ccpocket.daemon.disk.ObservationLookup)? = null

    /** Why a session may not be controlled right now, or null when it may. Evaluated per request, never cached. */
    private fun controlRefusal(agent: AgentKind, workdir: String, sessionId: String): String? =
        when (val lookup = observationPolicy?.invoke(agent, workdir, sessionId) ?: dev.ccpocket.daemon.disk.ObservationLookup.Unbound) {
            dev.ccpocket.daemon.disk.ObservationLookup.Unbound -> null
            is dev.ccpocket.daemon.disk.ObservationLookup.Bound -> if (lookup.binding.readOnly) "this session is observed read-only (bound to an outside task); act on it in its own app" else null
            is dev.ccpocket.daemon.disk.ObservationLookup.Unavailable -> "the managed-session store cannot be read (${lookup.reason}); control is refused until it is repaired"
        }

    /** Returns the opened convoId, or "" if the requested backend is unavailable (a PocketError is
     *  emitted). [origin] names the restricted credential that opened it (issue #91 bridge / #115 guest);
     *  null = interactive. [pathScope] (issue #115) is a GUEST's shared roots — the conversation's
     *  PermissionBridge denies any Read/Write/Edit whose target escapes them; null = unrestricted (owner).
     *  [bridgeAllowedCommands] (issue #91) is a BRIDGE's owner-configured Bash allow-list — the conversation's
     *  PermissionBridge auto-runs a matching command without a phone prompt; empty for owner/guest. */
    suspend fun open(
        open: OpenSession,
        sink: OutboundSink,
        origin: String? = null,
        pathScope: List<String>? = null,
        peerSupportsOpencode: Boolean = true,
        peerSupportsKimi: Boolean = true,
        peerSupportsZcode: Boolean = true,
        peerSupportsDsh: Boolean = true,
        bridgeAllowedCommands: List<String> = emptyList(),
        // issue #242: a BRIDGE's session-stable context preamble (chat/project/capability boundary), appended
        // to the agent's system prompt on every launch of this conversation. Null for owner/guest opens.
        bridgeContextPreamble: String? = null,
        // issue #91 OWNER BYPASS: this is the bridge owner's OWN dedicated session. It may mint one-turn
        // OWNER_BYPASS grants through the in-process entry point; the flag alone does not auto-allow tools.
        // Passed ONLY by trusted in-process code (the built-in engine).
        ownerBypass: Boolean = false,
        // issue #201: a HEADLESS fire (the scheduler) has no client attached, so its asks must keep the
        // bounded window even when the owner turned on "wait for my decision" — see Conversation's noAutoDeny.
        headless: Boolean = false,
        // issue #219: the workdir the phone OPENED with (raw "~"-relative allowed) — announced verbatim in
        // SessionLive so the phone's identity guard matches it; null → the daemon's canonical workdir.
        announcedWorkdir: String? = null,
        // #367 LOW-3: how an approval from this conversation is announced to the OWNER (see
        // [dev.ccpocket.daemon.conversation.Conversation.askOriginLabel]). Only a remote run sets it.
        askOriginLabel: String? = null,
        // read-only observation: the peer declared ClientCaps.supportsSessionObservationV1, so a read-only view
        // announces its binding + progress snapshot (an undeclared peer gets `observing` + notice only)
        peerSupportsObservation: Boolean = false,
    ): String {
        // S8: whatever this open claims (the resume id's cold open) is released however it ends
        val openClaim = OpenClaim()
        try {
            return openClaimed(
                openClaim, open, sink, origin, pathScope, peerSupportsOpencode, peerSupportsKimi, peerSupportsZcode,
                peerSupportsDsh, bridgeAllowedCommands, bridgeContextPreamble, ownerBypass, headless,
                announcedWorkdir, askOriginLabel, peerSupportsObservation,
            )
        } finally {
            val resume = open.resumeId
            openClaim.done?.let { if (resume != null) releaseOpen(resume, it) }
        }
    }

    /** What [open] holds while it owns a resume id's cold open ([OpenStep.Claimed]). */
    private class OpenClaim {
        var done: CompletableDeferred<Unit>? = null
    }

    /** The body of [open]; any claim it takes on the cold open is recorded in [claim] for [open] to release. */
    private suspend fun openClaimed(
        openClaim: OpenClaim,
        open: OpenSession,
        sink: OutboundSink,
        origin: String?,
        pathScope: List<String>?,
        peerSupportsOpencode: Boolean,
        peerSupportsKimi: Boolean,
        peerSupportsZcode: Boolean,
        peerSupportsDsh: Boolean,
        bridgeAllowedCommands: List<String>,
        bridgeContextPreamble: String?,
        ownerBypass: Boolean,
        headless: Boolean,
        announcedWorkdir: String?,
        askOriginLabel: String?,
        peerSupportsObservation: Boolean = false,
    ): String {
        val resume = open.resumeId
        // A resume id is the durable backend identity. Older Apps did not send `agent`, and a newer App
        // can still carry a stale per-session guess persisted before Codex support. Trust the transcript
        // that actually owns the id when the requested backend has no such transcript. Without this
        // correction a Codex rollout is opened by the Claude backend, so both the badge and replay are
        // wrong (Claude's scanner quite correctly finds no history in a Codex JSONL).
        val effectiveAgent = resume?.let { resolveResumeAgent(open.agent, open.workdir, it) } ?: open.agent
        if (effectiveAgent != open.agent) {
            log.info("open ${resume?.take(8)}…: corrected stale agent ${open.agent} → $effectiveAgent from transcript")
        }
        // Read-only observation (docs/design/DOTS-SESSION-OBSERVABILITY.md §4.3) comes BEFORE reattach, writer
        // probing, resume/fork and any launch: a persisted read-only binding, or the caller's own `observeOnly`,
        // yields an independent observe subscription on the EXISTING native record — even when this daemon drives
        // the same session (that conversation is untouched and this client gets no handle on it) — and nothing
        // else. A take-over of a bound session is refused outright; a store this build cannot read refuses too.
        if (open.observeOnly && resume == null) {
            // the contract: observeOnly needs an existing record; it never creates a session
            sink.emit(PocketError(dev.ccpocket.protocol.ObservationErrors.OBSERVE_UNAVAILABLE, "observeOnly needs an existing session id — nothing was created"))
            return ""
        }
        if (resume != null) {
            val lookup = observationPolicy?.invoke(effectiveAgent, open.workdir, resume) ?: dev.ccpocket.daemon.disk.ObservationLookup.Unbound
            // a store this build cannot read (corrupt / unknown schema) may hide a binding for ANY member of the
            // project: fail closed to READ-ONLY, not to nothing — the user can still look at the session, while
            // control (take-over, rename, and a controllable open) stays refused until the store is repaired.
            // EXCEPT for a conversation this very daemon is driving right now: it cannot be bound (binding is
            // refused while live), and demoting its reconnect to a tail would orphan a running turn and its asks.
            val policyUnreadable = lookup is dev.ccpocket.daemon.disk.ObservationLookup.Unavailable && !isLiveSession(resume)
            if (policyUnreadable) log.warn("open ${resume.take(8)}… → observation policy unreadable (${(lookup as dev.ccpocket.daemon.disk.ObservationLookup.Unavailable).reason}): read-only until the store file is repaired or removed")
            val binding = (lookup as? dev.ccpocket.daemon.disk.ObservationLookup.Bound)?.binding
            val readOnly = open.observeOnly || binding?.readOnly == true || policyUnreadable
            if (readOnly) {
                if (open.takeOver && (binding?.readOnly == true || policyUnreadable)) {
                    log.info("open ${resume.take(8)}… → refused: take-over of a read-only bound session")
                    sink.emit(PocketError(dev.ccpocket.protocol.ObservationErrors.READ_ONLY, controlRefusal(effectiveAgent, open.workdir, resume) ?: "read-only"))
                    return ""
                }
                if (origin != null) {
                    // a bridge / headless adapter has no read-only rendering and no prompt path here
                    sink.emit(PocketError(dev.ccpocket.protocol.ObservationErrors.READ_ONLY, "session is observed read-only; not available to a bridge"))
                    return ""
                }
                val file = transcriptResolver(effectiveAgent, open.workdir, resume)
                if (file == null || !file.exists()) {
                    log.info("open ${resume.take(8)}… → refused: observe-only but no readable native record")
                    sink.emit(PocketError(dev.ccpocket.protocol.ObservationErrors.OBSERVE_UNAVAILABLE, "no readable record for this session on this computer — nothing was created"))
                    return ""
                }
                val stale = mutex.withLock {
                    val dead = observes.filterValues { it.sessionId == resume && it.isAttachedTo(sink) }
                    dead.keys.forEach(observes::remove)
                    dead.values.toList()
                }
                stale.forEach { o ->
                    log.info("open ${resume.take(8)}… → reap stale observer ${o.convoId.take(8)}… (same client re-open)")
                    runCatching { o.close() }
                }
                val convoId = UUID.randomUUID().toString()
                log.info("open ${resume.take(8)}… → OBSERVE ${convoId.take(8)}… (read-only${if (binding != null) ", bound" else ", observeOnly"})")
                val obs = ObserveSession(
                    convoId, open.workdir, resume, file, sink, scope,
                    agent = effectiveAgent, sinceSeq = open.lastEventSeq,
                    // the caller's own policy (observeOnly / unreadable store) is fixed; the binding is re-read
                    readOnly = open.observeOnly || policyUnreadable, binding = binding, observationCapable = peerSupportsObservation,
                    policy = observationPolicy?.let { p -> { p(effectiveAgent, open.workdir, resume) } },
                )
                mutex.withLock { observes[convoId] = obs }
                obs.start()
                return convoId
            }
        }
        if (resume != null) {
            // re-attach to a session the daemon is already running (a cc-pocket background session).
            // Pre-first-turn the agent hasn't reported a sessionId yet — match the resume anchor too,
            // else a reconnect re-open spawns a second Conversation onto the same transcript.
            // S8: the live miss and the claim on the cold open are ONE step (see [liveOrClaim]).
            var live = when (val step = liveOrClaim(resume)) {
                is OpenStep.Live -> step.convo
                is OpenStep.Claimed -> { openClaim.done = step.done; null }
            }
            val attach = live
            if (attach != null) {
                // The reattach match is by resumeId ALONE — `open.agent` is deliberately not consulted, so
                // a client that guessed the agent wrong still lands on the right conversation. That makes
                // this the choke point for the wire-compat gate: reattaching a peer that cannot decode
                // AgentKind.OPENCODE would answer with a SessionLive it drops whole, leaving a session
                // that reports itself open while every push vanishes. Refuse with something readable
                // instead — and refuse HERE, so any future path that hands such a client an opencode
                // session id is covered too.
                if ((attach.kind == AgentKind.OPENCODE && !peerSupportsOpencode) ||
                    (attach.kind == AgentKind.KIMI && !peerSupportsKimi) ||
                    (attach.kind == AgentKind.ZCODE && !peerSupportsZcode) ||
                    (attach.kind == AgentKind.DSH && !peerSupportsDsh)
                ) {
                    log.info("open ${resume.take(8)}… → refused: ${attach.kind} session, peer never declared support")
                    sink.emit(PocketError("agent_unavailable", "update the app to open ${attach.kind} sessions"))
                    return ""
                }
                log.info("open ${resume.take(8)}… → reattach ${attach.convoId.take(8)}…")
                // Issue #50 on the HOT path — parity with the cold resume below, which builds the
                // Conversation from open.mode: re-opening a still-live conversation applies the caller's
                // mode instead of silently reviving whatever the convo drifted to. Since M5 (Full Control
                // auto-expiry) the drift is real: a convo alive past the TTL has fallen back to DEFAULT,
                // and without this every re-open kept that fallback — reading as "Settings' default mode
                // is ignored", which is exactly what #50 reported.
                //
                // GATED on the reattacher carrying the SAME authority the conversation's walls were built
                // for. The tempting argument — "every restricted ingress already clamps open.mode to its
                // tier ceiling before it gets here" — only covers opens BY a restricted credential. An
                // OWNER open lands here too, carrying the owner's own Settings default, which nobody
                // clamped: ungated, an owner peeking at a session a guest or a bridge started would apply
                // that default to a conversation whose walls were built for the restricted credential.
                // Matching path scope AND origin keeps #50's actual case (owner re-opening their own
                // session) working, while a restricted conversation keeps the mode its own ceiling clamped.
                //
                // Same-mode re-opens no-op inside switchMode (grants and the M5 expiry clock are
                // untouched — merely re-entering never renews Full Control). A BUSY conversation is left
                // alone: peeking at a running task must not yank its grants/autonomy mid-flight (the
                // idle-only spirit of the reaper and the client's close-on-switch).
                beforeLiveReattachClaim?.invoke()
                // The first lookup above is optimistic: an awakened grace timer may remove and close that
                // Conversation while switchMode/replay suspends. Claim it again under the SAME mutex the
                // timer uses for cleanup. Presence recheck + same-key timer removal + replacement-sink
                // registration are indivisible, so either reattach wins and expiry becomes stale, or expiry
                // wins and this open falls through to a fresh cold resume instead of returning a dead id.
                val claim = mutex.withLock {
                    if (convos[attach.convoId] !== attach) return@withLock null
                    val staleClose = pendingClose.remove(PendingCloseKey(attach.convoId, sinkKey(sink)))
                    attach.registerReattach(sink)
                    LiveReattachClaim(staleClose)
                }
                if (claim != null) {
                    claim.staleClose?.cancel()
                    val sameAuthority = attach.matchesGrant(pathScope) && attach.origin == origin
                    if (!attach.isBusy() && sameAuthority) {
                        if (attach.currentMode() != open.mode) {
                            log.info("open ${resume.take(8)}… → reattach applies caller mode ${open.mode} (was ${attach.currentMode()})")
                        }
                        attach.switchMode(open.mode, open.permissionMode)
                    } else if (!attach.isBusy() && !sameAuthority && attach.currentMode() != open.mode) {
                        log.info("open ${resume.take(8)}… → reattach keeps convo mode ${attach.currentMode()}: caller's grant shape differs (mode ${open.mode} not applied)")
                    }
                    attach.replayReattach(sink, open.lastEventSeq)
                    return attach.convoId
                }
                log.info("open ${resume.take(8)}… → live candidate ${attach.convoId.take(8)}… expired before reattach claim; resuming cold")
                live = null
                // S8: the cold resume goes through the single-flight too — it waits out the expired
                // conversation's close (its tombstone) and any concurrent open; should a live conversation
                // have appeared meanwhile, start over and reattach to it instead.
                when (val step = liveOrClaim(resume)) {
                    // (holds no claim here: this branch only runs after a Live step)
                    is OpenStep.Live -> return openClaimed(
                        openClaim, open, sink, origin, pathScope, peerSupportsOpencode, peerSupportsKimi, peerSupportsZcode,
                        peerSupportsDsh, bridgeAllowedCommands, bridgeContextPreamble, ownerBypass,
                        headless, announcedWorkdir, askOriginLabel, peerSupportsObservation,
                    )
                    is OpenStep.Claimed -> openClaim.done = step.done
                }
            }
            // Observe a session running OUTSIDE the daemon (e.g. a terminal) — read-only, no second
            // writer. Admission is the transcript capability itself (issue #301): a backend that resolves
            // no per-session file (OpenCode's SQLite, Kimi/ZCode/DSH) never reaches the gate — by
            // declaration, not by a kind list here. externallyActive still requires a LIVE external
            // process, not just a fresh mtime — a terminal claude the user quit seconds ago falls through
            // to an ordinary in-place resume, not read-only observe.
            if (!open.takeOver) {
                val file = transcriptResolver(effectiveAgent, open.workdir, resume)
                val recent = file != null && externallyActive(resume, open.workdir, file, effectiveAgent)
                if (recent) {
                    // a bridge gets a clean refusal instead of a read-only ObserveSession: observes have
                    // no prompt path, and a headless adapter can't render "someone else is driving this"
                    if (origin != null) {
                        sink.emit(PocketError("bridge_busy", "session is live in another client — try again later"))
                        return ""
                    }
                    // a reconnecting client re-opens its observe with a FRESH sink (same key, new
                    // instance). Reap this client's previous observer(s) of the same transcript first —
                    // they survive the reconnect (the device's sink key revives with it) and would keep
                    // tailing under a stale convoId, ping-ponging the phone between two SessionLive/
                    // ConvoHistory streams forever (issue #107).
                    val stale = mutex.withLock {
                        val dead = observes.filterValues { it.sessionId == resume && it.isAttachedTo(sink) }
                        dead.keys.forEach(observes::remove)
                        dead.values.toList()
                    }
                    stale.forEach { o ->
                        log.info("open ${resume.take(8)}… → reap stale observer ${o.convoId.take(8)}… (same client re-open)")
                        runCatching { o.close() }
                    }
                    val convoId = UUID.randomUUID().toString()
                    log.info("open ${resume.take(8)}… → OBSERVE ${convoId.take(8)}… (live foreign writer)")
                    val obs = ObserveSession(
                        convoId, open.workdir, resume, file!!, sink, scope,
                        agent = effectiveAgent, sinceSeq = open.lastEventSeq,
                        observationCapable = peerSupportsObservation,
                        policy = observationPolicy?.let { p -> { p(effectiveAgent, open.workdir, resume) } },
                    )
                    mutex.withLock { observes[convoId] = obs }
                    obs.start()
                    return convoId
                }
            }
        }
        // resume + control: an idle session, or an explicit "Continue here" take-over
        val factory = backends[effectiveAgent]
        if (factory == null) {
            sink.emit(PocketError("agent_unavailable", "no backend registered for $effectiveAgent"))
            return ""
        }
        // create() is cheap + never throws (the binary resolves lazily on first launch); the real "CLI not
        // installed" failure surfaces synchronously from c.open() below, so one guard there covers it.
        val convoId = UUID.randomUUID().toString()
        val c = Conversation(
            convoId, Path.of(open.workdir), open.mode, sink, scope, factory.create(),
            pushHookProvider = { pushHook }, origin = origin, askPushHookProvider = { askPushHook },
            pathScope = pathScope, bridgeAllowedCommands = bridgeAllowedCommands,
            bridgeContextPreamble = bridgeContextPreamble, askOriginLabel = askOriginLabel, ownerBypass = ownerBypass,
            headless = headless,
            announcedWorkdir = announcedWorkdir,
            approvals = approvals, grants = grants, riskEngine = riskEngine,
        )
        c.nativeSessionHookProvider = { managedSessions } // issue #360: trusted native ids → managed-list registration
        // issue #360 security review M1: the owner fact, fixed at open — a bridge (origin) or a guest (its path
        // scope) never registers into the owner's managed list
        c.ownerCreated = origin == null && pathScope == null
        // S8: the persistent identity is in place BEFORE the conversation becomes visible — the transcript
        // sweep's isLiveSession and renameSession both match on it, and used to miss a
        // conversation between this insert and the first line of c.open
        c.presetResumeAnchor(resume)
        beforeColdInsert?.invoke()
        mutex.withLock { convos[convoId] = c }
        // Bind/open race (review finding): a bind that checked "not live" just before this insert may have landed
        // meanwhile. Re-read the policy now that the conversation is visible — the bind side re-checks liveness
        // after ITS write — so one of the two always sees the other; a bound session never gets a controllable convo.
        if (resume != null && controlRefusal(effectiveAgent, open.workdir, resume) != null) {
            log.info("open ${resume.take(8)}… → bound read-only while opening; withdrawing the controllable conversation")
            val tomb = mutex.withLock { convos.remove(convoId); tombstoneLocked(c) }
            try { runCatching { c.close() } } finally { finishClose(tomb) }
            sink.emit(PocketError(dev.ccpocket.protocol.ObservationErrors.READ_ONLY, controlRefusal(effectiveAgent, open.workdir, resume) ?: "read-only"))
            return ""
        }
        // For an explicit take-over we bypassed the ObserveSession guard above, so a desktop `claude --resume`
        // MIGHT still be writing this transcript. Fork (branch to a fresh id, dodging a two-writer clobber) ONLY
        // when [externallyActive] confirms it — fresh mtime AND a claude process alive outside the daemon (the
        // mtime window alone mistook "user quit the terminal seconds ago" for an active writer and minted bogus
        // forks). Otherwise resume IN PLACE on the same sessionId: the phone truly takes over (issue #18 — no
        // duplicate session) and the desktop picks up the phone's turns on its next --resume (issue #22 — sync).
        // Ordinary cold/idle resume already appends in place. Same detector as the ObserveSession guard above.
        val takeOverTranscript = resume?.let { transcriptResolver(effectiveAgent, open.workdir, it) }
        val forkForTakeOver = open.takeOver && resume != null && takeOverTranscript != null &&
            externallyActive(resume, open.workdir, takeOverTranscript, effectiveAgent)
        // issue #363: the requested launch shape rides in the open log — a "full auto didn't apply" report
        // used to be diagnosable only by `ps` on the child's argv, which says nothing about what the
        // client actually asked for (a client that never sent BYPASS vs a daemon that dropped it).
        log.info(
            "open ${resume?.take(8) ?: "new"}${if (open.takeOver) " (take-over)" else ""} → " +
                "convo ${convoId.take(8)}… agent=$effectiveAgent${if (forkForTakeOver) " FORK" else ""}" +
                " mode=${open.mode}${open.permissionMode?.let { "/$it" } ?: ""}" +
                " model=${open.model ?: "-"} effort=${open.effort ?: "-"}" +
                "${if (origin != null) " origin=$origin" else ""}",
        )
        // takeOver → Conversation.open spawns EAGERLY (seize the session now); a plain open starts lazily on the
        // first prompt (issue #61) so merely previewing a session never holds/occupies it for the desktop.
        val started = runCatching {
            c.open(
                open.resumeId,
                open.model,
                open.effort,
                fork = forkForTakeOver,
                takeOver = open.takeOver,
                sinceSeq = open.lastEventSeq,
                permissionMode = open.permissionMode,
                serviceTier = open.serviceTier,
                thinking = open.thinking,
                // issue #333: NEW sessions only — the backend drops it on a resume (the preset is locked
                // once a session has produced output), so no filtering is needed here.
                agentPreset = open.agentPreset,
            )
        }
        if (started.isFailure) {
            Diagnostics.report(ErrorPath.SESSION_OPEN, Stage.INITIALIZE, ErrorCode.UNAVAILABLE, started.exceptionOrNull())
            val tomb = mutex.withLock { convos.remove(convoId); tombstoneLocked(c) }
            try { runCatching { c.close() } } finally { finishClose(tomb) }
            sink.emit(PocketError("agent_unavailable", "$effectiveAgent CLI not found — is it installed? (${started.exceptionOrNull()?.message})"))
            return ""
        }
        return convoId
    }

    /** Resolve a stale/missing wire agent from the durable transcript id. A positive requested-agent
     *  match always wins; only a missing transcript permits the unambiguous Claude↔Codex correction. */
    private fun resolveResumeAgent(requested: AgentKind, workdir: String, sessionId: String): AgentKind {
        fun hasTranscript(agent: AgentKind): Boolean = runCatching {
            transcriptResolver(agent, workdir, sessionId)?.exists() == true
        }.getOrDefault(false)

        if (hasTranscript(requested)) return requested
        // candidates derive from the registered backends (issue #301); the legacy pair stays appended so
        // test registries built with an empty/partial backends map keep the historical probe order
        return (backends.keys + listOf(AgentKind.CODEX, AgentKind.CLAUDE)).distinct()
            .firstOrNull { it != requested && hasTranscript(it) }
            ?: requested
    }

    // ── rewind / fork (issue #282, docs/design/REWIND-FORK.md §6) ──────────────────────────────────

    /**
     * Answer one [RewindSession]: preview the cut, or make it.
     *
     * The whole operation is "close one conversation, open a branch of it", and the ONE invariant is
     * that it either happens completely or leaves nothing behind. So every refusable condition is
     * checked BEFORE anything is closed — the admission gate, then the anchor translated against the
     * file on disk — and the only step after the point of no return is creating the replacement, whose
     * failure path restores nothing because nothing on disk was touched: `--fork-session` writes a new
     * transcript and leaves the original byte-for-byte (probed on 2.1.228), and the branch's own file
     * does not exist until its first turn.
     *
     * Both modes launch identically (`--resume <sid> --resume-session-at <anchor> --fork-session`); the
     * mode only decides how the ORIGINAL is later filed, which is the lineage ledger's business, not
     * this function's. The "truncate WITHOUT forking" shape is never emitted anywhere: it keeps the
     * original id and appends the branch to the same file, turning the transcript into a tree that
     * linear replay renders twice.
     *
     * Answers the REQUESTING sink only — another attached client did not ask and must not be told its
     * session is being cut by a frame it has no context for; it finds out the ordinary way, by being
     * migrated onto the branch below.
     */
    suspend fun rewind(req: dev.ccpocket.protocol.RewindSession, sink: OutboundSink) {
        val refuse: suspend (String) -> Unit = { reason ->
            log.info("rewind ${req.convoId.take(8)}… refused: $reason")
            sink.emit(
                if (req.dryRun) dev.ccpocket.protocol.RewindPreview(req.convoId, 0, 0, ok = false, reason = reason)
                else dev.ccpocket.protocol.RewindDone(req.convoId, ok = false, reason = reason),
            )
        }
        val R = dev.ccpocket.protocol.RewindRefusal
        if (req.mode != dev.ccpocket.protocol.RewindMode.REWIND && req.mode != dev.ccpocket.protocol.RewindMode.FORK) {
            return refuse(R.BAD_MODE)
        }
        val convo = get(req.convoId) ?: return refuse(R.NO_CONVO)
        // Claude only: no other backend has a truncated-resume primitive, and silently doing something
        // ELSE (a plain resume, a fresh session) would be worse than refusing. Restricted conversations
        // are out too — a bridge / guest session branching the owner's history is an authority question
        // this feature has not answered, so the answer is no.
        if (convo.kind != AgentKind.CLAUDE) return refuse(R.UNSUPPORTED)
        if (convo.origin != null || !convo.matchesGrant(null)) return refuse(R.UNSUPPORTED)
        // idle in BOTH senses: nothing running or unanswered (isBusy), and nothing queued toward the
        // agent that a cut would strand (the #122 ledger). Keeps this orthogonal to the #285 attribution
        // gate — a rewind can only start from a standstill, where no prompt has a fate to decide.
        if (convo.isBusy() || convo.hasQueuedPrompts()) return refuse(R.NOT_IDLE)
        val sid = convo.sessionId ?: convo.resumeAnchor ?: return refuse(R.NO_TRANSCRIPT)
        val workdir = convo.workdir.toString()
        val file = ProjectPaths.dirForUnder(projectsRoot, workdir).resolve("$sid.jsonl")
        if (!file.exists()) return refuse(R.NO_TRANSCRIPT)
        // a terminal `claude --resume` on this transcript is a second writer; our own live process is
        // fine (we are about to stop it) and externallyActive already excludes it
        if (externallyActive(sid, workdir, file)) return refuse(R.EXTERNAL_WRITER)

        val plan = when (val p = withContext(Dispatchers.IO) { dev.ccpocket.daemon.disk.RewindPlanner.plan(file, req.anchorUuid, req.anchorSeq) }) {
            is dev.ccpocket.daemon.disk.RewindPlanner.Result.Refused -> return refuse(p.reason)
            is dev.ccpocket.daemon.disk.RewindPlanner.Result.Ok -> p.plan
        }
        if (req.dryRun) {
            log.info("rewind ${req.convoId.take(8)}… preview: ${plan.dropTurns} turn(s), ${plan.dropToolCalls} tool call(s)")
            return sink.emit(dev.ccpocket.protocol.RewindPreview(req.convoId, plan.dropTurns, plan.dropToolCalls, ok = true))
        }

        val factory = backends[AgentKind.CLAUDE] ?: return refuse(R.UNSUPPORTED)
        // Claim the conversation before touching anything. A loser here gets NO_CONVO, which is what its
        // request has effectively become: the conversation it named is on its way out.
        if (!mutex.withLock { rewinding.add(convo.convoId) }) return refuse(R.NO_CONVO)
        try {
            rewindLocked(req, sink, convo, sid, plan, factory, refuse)
        } finally {
            mutex.withLock { rewinding.remove(convo.convoId) }
        }
    }

    /** The execute half of [rewind], past the point of no return and holding the [rewinding] claim. */
    private suspend fun rewindLocked(
        req: dev.ccpocket.protocol.RewindSession,
        sink: OutboundSink,
        convo: Conversation,
        sid: String,
        plan: dev.ccpocket.daemon.disk.RewindPlanner.Plan,
        factory: AgentBackendFactory,
        refuse: suspend (String) -> Unit,
    ) {
        val knobs = convo.launchKnobs()
        // Everyone else watching this conversation moves onto the branch: the alternative is leaving them
        // attached to a conversation that is about to stop existing.
        val spectators = convo.attachedSinks().filterNot { sinkKey(it) == sinkKey(sink) }
        // POINT OF NO RETURN. Stop the old conversation first and let its process flush: the branch is
        // launched with --fork-session so two writers could not actually collide, but the daemon's own
        // "one writer per session" discipline does not get relaxed just because the CLI would survive it.
        // S8: the old conversation's tombstone stands until the branch is in place (see rewindBranch), so a
        // re-open of the session in between neither spawns next to the dying process nor beats the branch in
        val tomb = mutex.withLock { convos.remove(convo.convoId); tombstoneLocked(convo) }
        try {
            cancelPendingCloses(convo.convoId)
            approvals.withdrawAllForConvo(convo.convoId)
            runCatching { convo.close() }
            noteSelfClosed(convo)
            rewindBranch(req, sink, convo, sid, plan, factory, refuse, knobs, spectators)
        } finally {
            finishClose(tomb)
        }
    }

    /** The branch half of [rewindLocked], once the old conversation is closed. */
    private suspend fun rewindBranch(
        req: dev.ccpocket.protocol.RewindSession,
        sink: OutboundSink,
        convo: Conversation,
        sid: String,
        plan: dev.ccpocket.daemon.disk.RewindPlanner.Plan,
        factory: AgentBackendFactory,
        refuse: suspend (String) -> Unit,
        knobs: Conversation.LaunchKnobs,
        spectators: List<OutboundSink>,
    ) {
        val R = dev.ccpocket.protocol.RewindRefusal
        val newConvoId = UUID.randomUUID().toString()
        val branch = Conversation(
            newConvoId, convo.workdir, knobs.mode, sink, scope, factory.create(),
            pushHookProvider = { pushHook }, askPushHookProvider = { askPushHook },
            approvals = approvals, grants = grants, riskEngine = riskEngine,
        )
        branch.nativeSessionHookProvider = { managedSessions } // issue #360: the rewind/fork branch reports its new id too
        branch.ownerCreated = convo.ownerCreated // …under the owner fact of the conversation it branched from
        mutex.withLock { convos[newConvoId] = branch }
        val started = runCatching {
            branch.open(
                sid, knobs.model, knobs.effort,
                permissionMode = knobs.permissionMode,
                serviceTier = knobs.serviceTier,
                thinking = knobs.thinking,
                agentPreset = knobs.agentPreset,
                // LAZY, like every plain open. Nothing is forked until the person actually sends the
                // first turn — back out here and no transcript, no session row and no ledger entry were
                // created, which is the strongest possible reading of "a fork is never implicit". It is
                // also why RewindDone carries no newSessionId: the CLI has not minted one yet.
                rewind = Conversation.RewindLaunch(
                    parentSid = sid,
                    anchorUuid = plan.anchorUuid,
                    dropsTurnUuid = plan.dropsTurnUuid,
                    cutSeq = req.anchorSeq,
                    mode = req.mode,
                ),
            )
        }
        if (started.isFailure) {
            Diagnostics.report(ErrorPath.SESSION_OPEN, Stage.INITIALIZE, ErrorCode.UNAVAILABLE, started.exceptionOrNull())
            mutex.withLock { convos.remove(newConvoId) }
            runCatching { branch.close() }
            log.warn("rewind ${req.convoId.take(8)}… branch failed to open: ${started.exceptionOrNull()?.message}")
            return refuse(R.LAUNCH_FAILED)
        }
        for (s in spectators) {
            runCatching { branch.reattach(s) }
                .onFailure { log.warn("rewind: could not migrate a spectator onto ${newConvoId.take(8)}…: ${it.message}") }
        }
        log.info(
            "rewind ${req.convoId.take(8)}… → ${req.mode} branch ${newConvoId.take(8)}… of ${sid.take(8)}… " +
                "at seq ${req.anchorSeq} (dropping ${plan.dropTurns} turn(s), ${plan.dropToolCalls} tool call(s)" +
                "${if (plan.dropsTurnUuid != null) ", guarded" else ""})",
        )
        sink.emit(dev.ccpocket.protocol.RewindDone(req.convoId, ok = true, newConvoId = newConvoId))
    }

    /**
     * Test hook (issue #216): cut ONE relay device's live view of [convoIds] without closing anything — what a
     * phone leaving the session view amounts to for the reaper's occupancy check. Keyed on the relay's stable
     * `dev:<deviceId>` fan-out identity, so every other attached client keeps streaming. An observe view has
     * exactly one sink by construction, so detaching that device closes it — but only after confirming the
     * sink is that device's. Idempotent: an absent convo or an already-detached sink counts 0.
     */
    internal suspend fun detachDevice(convoIds: Set<String>, deviceId: String): Int {
        if (convoIds.isEmpty()) return 0
        // a stub carrying ONLY the identity: every attach/detach path matches on sinkKey, never on the
        // lambda instance, so this removes the device's real sink without needing a handle to it
        val probe = KeyedSink("$DEVICE_SINK_KEY_PREFIX$deviceId", OutboundSink { })
        val orphaned = mutableListOf<ObserveSession>()
        var cut = 0
        mutex.withLock {
            for (id in convoIds) {
                convos[id]?.let { c -> if (c.isAttachedTo(probe)) { c.detach(probe); cut++ } }
                observes[id]?.let { o -> if (o.isAttachedTo(probe)) { observes.remove(id); orphaned += o; cut++ } }
            }
        }
        orphaned.forEach { runCatching { it.close() } } // off the lock: close() cancels its tail scope
        return cut
    }

    /** Test hook (issue #360 M1): the owner fact [open] fixed on [convoId]'s conversation; null for a gone convo. */
    internal suspend fun ownerCreatedOf(convoId: String): Boolean? = get(convoId)?.ownerCreated

    /** Test hook: is [convoId] still a live observe view? (the issue-107 stale-observer reap) */
    internal suspend fun observing(convoId: String): Boolean = mutex.withLock { observes.containsKey(convoId) }

    /** Resumable sessions for [workdir] across every agent backend (each tags its summaries with its kind),
     *  newest-first, each stamped with its [SessionGroup] membership (issue #119; null = ungrouped). */
    fun listSessions(workdir: String): List<SessionSummary> =
        backends.values.flatMap { runCatching { it.create().listSessions(workdir) }
            .onFailure { Diagnostics.report(ErrorPath.SESSION_LIST, Stage.SCAN, ErrorCode.READ_FAILED, it,
                SafeMetrics(resultQuality = ResultQuality.PARTIAL)) }.getOrDefault(emptyList()) }
            .map { it.copy(group = SessionGroups.groupOf(workdir, it.sessionId)) }
            .sortedByDescending { it.lastModified }

    /** One backend's scan of [workdir] WITH its completeness (issue #360) — the only scan a durable decision
     *  (managed-list migration, import verification, "record unavailable") may rely on. Unlike [listSessions] a
     *  failure is reported as such, never as an empty list; an unregistered backend is an ERROR scan. Rows carry
     *  no group stamp: grouping is a projection concern. [listSessions] itself is unchanged. */
    fun scanSessions(workdir: String, agent: AgentKind): SessionScan {
        val factory = backends[agent] ?: return SessionScan(agent, workdir, emptyList(), ScanCompleteness.ERROR, detail = "no backend")
        val scan = try { factory.create().scanSessions(workdir, agent) } catch (e: Exception) { SessionScan.failed(agent, workdir, e) }
        // a row tagged with another agent cannot be attributed to this scan: never guess, downgrade instead
        return if (scan.items.any { it.agent != null && it.agent != agent }) {
            scan.copy(completeness = ScanCompleteness.ERROR, detail = "foreign agent rows")
        } else scan
    }

    /**
     * Close conversations with no agent activity for longer than [idleMs]. Returns the reap count.
     * A BUSY conversation ([Conversation.isBusy]) is NEVER reaped, however stale its activity clock:
     *  - a streaming turn (executing): `lastActivityMs` only moves when a stream-json line arrives, and the
     *    CLI is routinely silent for minutes MID-TURN — a quiet long-running tool (build/test/install), a
     *    single long generation (no --include-partial-messages), an API-retry backoff. Reaping on the stale
     *    clock alone killed the process tree mid-task once the phone went offline >90s — the "submitted a
     *    task, quit the app, came back to find only step 1 done" failure (issue #105). No leak in return:
     *    a dead process clears `executing` in the pump, so only a LIVE process can hold its conversation.
     *  - running background work: killing the conversation would take its still-running background shells /
     *    sub-agents with it (the "I left it running" case this is meant to preserve).
     *  - an unanswered permission ask / question (issue #55): blocked on the user, not idle — reaping would
     *    silently discard a card the phone is expected to answer (plan mode surfaces the question long after
     *    a premature `result`, past this idle window, while the phone is backgrounded).
     *
     * Occupancy is judged PER SESSION, not by global presence (issue #216). The caller used to gate this
     * on "no phone connected at all", which never fired while a desktop App or phone held its permanent
     * connection — a session the user had already tapped away from stayed warm (and hidden from the
     * desktop `--resume` picker) indefinitely. Now a conversation is spared only while a REACHABLE client
     * view is attached ([clientOccupied]): a `dev:` relay view counts while the relay peer is online
     * ([relayPeerOnline]), a plain LAN view while any LAN socket lives, and a headless sink (scheduler
     * fire) never counts. An idle session nobody is looking at is released — and unhidden — promptly,
     * App online or not.
     */
    suspend fun reapIdle(idleMs: Long, relayPeerOnline: Boolean = false): Int {
        // first settle background jobs whose completion event never arrived — otherwise their forever-RUNNING
        // status keeps hasBackgroundWork() true and the session can never be reaped (and the phone's "N running"
        // count never clears). Snapshot outside the lock so the per-conversation emit doesn't hold the mutex.
        mutex.withLock { convos.values.toList() }.forEach { runCatching { it.reapStaleJobs(STALE_JOB_MS) } }
        val now = System.currentTimeMillis()
        val lanClientPresent = lanConnected()
        val stale = mutex.withLock {
            val s = convos.filterValues {
                now - it.lastActivityMs > idleMs && !it.isBusy() && !clientOccupied(it, relayPeerOnline, lanClientPresent)
            }
            convos.keys.removeAll(s.keys)
            // S8: each removal files its tombstone in the same critical section — a re-open waits for the close
            s.values.map { it to tombstoneLocked(it) }
        }
        // Every one of these is already OUT of the registry, so nothing else will ever close it: one close that
        // throws must not skip the rest (their processes would be orphaned), and a reaper cancelled mid-loop
        // must still finish the job.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            stale.forEach { (it, tomb) ->
                // name each casualty: "which session died, when, how stale" is exactly what a field report
                // of a vanished background task needs from the daemon log (issue #105 was undiagnosable
                // from the RelayClient's bare reap count)
                log.info("reapIdle: closing ${it.convoId.take(8)}… (sid=${it.sessionId?.take(8) ?: "-"}, idle ${now - it.lastActivityMs}ms)")
                try {
                    beforeReapClose?.invoke(it)
                    it.close(); noteSelfClosed(it)
                } catch (e: Exception) {
                    log.warn("reapIdle: closing ${it.convoId.take(8)}… failed — continuing with the rest", e)
                } finally {
                    finishClose(tomb)
                }
            }
        }
        return stale.size
    }

    /** Does a REACHABLE client view hold this conversation open? (the reaper's per-session occupancy
     *  check, issue #216). Reachability is judged per sink, not per daemon:
     *   - a `dev:` keyed sink is a relay device view — relay sinks are never detached when the peer
     *     drops (the relay link just goes quiet), so a stale one must not pin the session forever;
     *     it counts only while the relay peer is online.
     *   - a plain (unkeyed) sink is a LAN socket view — LAN disconnects detach it via the
     *     [scheduleClose] grace, so its mere presence implies a live client; gated on any LAN socket
     *     being open at all, which also keeps offline-era unit fixtures (attached collector lambdas,
     *     no real socket) reapable exactly like the pre-#216 offline reaper.
     *   - a non-watching sink (the scheduler's headless fire) is a black hole, never an occupant —
     *     a scheduled run that settled should surface in the desktop picker like any other leftover. */
    private fun clientOccupied(c: Conversation, relayPeerOnline: Boolean, lanClientPresent: Boolean): Boolean =
        c.attachedSinks().any { sink ->
            val keyed = sink as? KeyedSink ?: return@any lanClientPresent
            when {
                !keyed.watching -> false
                (keyed.key as? String)?.startsWith(DEVICE_SINK_KEY_PREFIX) == true -> relayPeerOnline
                else -> lanClientPresent
            }
        }

    /** Is [sessionId] one of THIS daemon's live conversations (by settled id or resume anchor)?
     *  The periodic spawned-session sweep asks this before touching a journaled transcript: a live
     *  conversation's claude may hold the file, and rewriting under it drops concurrent appends
     *  (the d8fa0da class of regression). Queried per entry, not snapshotted — an open racing the
     *  sweep must be seen. */
    suspend fun isLiveSession(sessionId: String): Boolean = mutex.withLock {
        convos.values.any { it.sessionId == sessionId || it.resumeAnchor == sessionId }
    }

    /** Conversations mid-work (turn in flight or background jobs) — these BLOCK an account switch:
     *  swapping credentials under an agent that is actively talking to the API breaks it mid-turn.
     *  Merely-open idle conversations don't count — [closeIdleForAuth] reaps those instead (otherwise
     *  the desktop could never switch: its own open chat would always hold the guard). Observe
     *  sessions never count (they spawn no agent and hold no token).
     *
     *  Settles stale jobs FIRST: a bg shell killed outside the daemon leaves its job RUNNING forever
     *  (completion only arrives via the agent stream), and without this reap a ghost job blocks every
     *  switch until [reapIdle] happens to run. Returns one [AuthBlocker] per offender so the client
     *  can show WHAT is busy and offer to stop it, not just a count. */
    suspend fun busyForAuth(): List<AuthBlocker> {
        mutex.withLock { convos.values.toList() }.forEach { runCatching { it.reapStaleJobs(STALE_JOB_MS) } }
        return mutex.withLock {
            convos.values.mapNotNull { c ->
                val executing = c.isExecuting()
                if (!executing && !c.hasBackgroundWork()) return@mapNotNull null
                AuthBlocker(
                    convoId = c.convoId,
                    sessionId = c.sessionId,
                    cwd = c.workdir.toString(),
                    reason = if (executing) AuthBlockReason.EXECUTING else AuthBlockReason.BACKGROUND_JOBS,
                    jobLabels = if (executing) emptyList() else c.runningJobLabels(),
                )
            }
        }
    }

    /** Close every mid-work conversation ahead of a FORCED credential swap — the user saw the blocker
     *  list and chose "stop them & switch". Same lifecycle as [closeIdleForAuth] (transcripts persist,
     *  resumable); the killed process trees take their background shells with them. */
    suspend fun closeBusyForAuth(): Int {
        val busy = mutex.withLock {
            val s = convos.filterValues { it.isExecuting() || it.hasBackgroundWork() }
            convos.keys.removeAll(s.keys)
            s.values.map { it to tombstoneLocked(it) } // S8
        }
        busy.forEach { (c, tomb) -> closeRemoved(c, tomb) }
        return busy.size
    }

    /** Close every idle conversation ahead of a credential swap. Transcripts persist on disk, so the
     *  client resumes them like any cold session afterwards — new turns just bill the new account. */
    suspend fun closeIdleForAuth(): Int {
        val idle = mutex.withLock {
            val s = convos.filterValues { !it.isExecuting() && !it.hasBackgroundWork() }
            convos.keys.removeAll(s.keys)
            s.values.map { it to tombstoneLocked(it) } // S8
        }
        idle.forEach { (c, tomb) -> closeRemoved(c, tomb) }
        return idle.size
    }

    /** Force-close every conversation opened by [origin] (a restricted credential's label). Used on
     *  revoke/expiry so a guest's sessions END the instant the owner revokes — including convos it opened on
     *  an EARLIER connection, which DeviceSessions' per-connection `owned` list no longer holds (issue #115
     *  crypto review L1). Same lifecycle as [closeBusyForAuth]: the killed process trees take their
     *  background shells with them; transcripts persist. */
    suspend fun closeByOrigin(origin: String): Int {
        val hits = mutex.withLock {
            val s = convos.filterValues { it.origin == origin }
            convos.keys.removeAll(s.keys)
            s.values.map { it to tombstoneLocked(it) } // S8
        }
        hits.forEach { (c, tomb) -> closeRemoved(c, tomb) }
        return hits.size
    }

    /** Is any conversation doing or awaiting work that a daemon exit would destroy? The same keep-alive
     *  predicate the reaper and closeIfIdle use ([Conversation.isBusy]): a streaming turn, a queued prompt,
     *  running background jobs, an unanswered permission/question/bridge-request card, or the bounded
     *  continuation grace. The auto-update gate (UpdateChecker) defers its restart while this holds. */
    suspend fun hasActiveWork(): Boolean = mutex.withLock { convos.values.any { it.isBusy() } }

    /** cwds of live conversations with running background work — kept "active" in the project list even when idle. */
    suspend fun busyCwds(): Set<String> =
        mutex.withLock { convos.values.filter { it.hasBackgroundWork() }.map { it.workdir.toString() }.toSet() }

    /** sessionIds of live conversations with running background work — keep their session row's "running" badge on. */
    suspend fun busySessionIds(): Set<String> =
        mutex.withLock { convos.values.filter { it.hasBackgroundWork() }.mapNotNull { it.sessionId }.toSet() }

    /** Live daemon conversations grouped by cwd, with their REAL turn state — the project list's
     *  authoritative live info (a dir can host several sessions; the transcript-mtime heuristic can't
     *  see turn boundaries). Pre-first-turn conversations (no sessionId yet) are skipped: there is no
     *  transcript to link a row to. Titles/branches are left null — DirectoryService enriches them. */
    suspend fun liveByCwd(): Map<String, List<dev.ccpocket.protocol.ActiveSession>> =
        mutex.withLock {
            convos.values.mapNotNull { c ->
                val sid = c.sessionId ?: return@mapNotNull null
                // PUBLISHED work state, not the reaper's. A plan-mode result and a completed background
                // task both arm a 5-minute continuation grace so the reaper can't reclaim a conversation
                // whose unprompted follow-up turn is still coming — but that shield is not evidence the
                // agent is running, and publishing it kept settled rows lit as Active for five minutes
                // (issue #269). The continuation re-arms `executing` from its own init when it starts.
                val executing = c.hasVisibleTurnWork()
                c.workdir.toString() to dev.ccpocket.protocol.ActiveSession(
                    sid, executing = executing, busy = c.hasBackgroundWork(), agent = c.kind,
                    origin = c.origin, executingAuthoritative = true,
                )
            }
        }.groupBy({ it.first }, { it.second })

    /** How many of [ids] are still LIVE conversations — the bridge concurrency budget counts these,
     *  not the historical ledger (issue #91): an idle-reaped session must not eat a slot forever. */
    suspend fun liveCountOf(ids: Collection<String>): Int =
        mutex.withLock { ids.count { it in convos } }

    /** Daemon-authoritative owner approval queue. Conversation-owned asks already carry provenance;
     * service-owned asks (quick shell/export) are enriched here by their convo id. Soonest deadline first. */
    suspend fun pendingApprovals(extra: List<PendingApproval> = emptyList()): List<PendingApproval> =
        mutex.withLock {
            val byId = convos
            (convos.values.flatMap { it.pendingApprovals() } + extra).map { row ->
                val convo = byId[row.ask.convoId]
                if (convo == null) row else row.copy(
                    workdir = row.workdir ?: convo.workdir.toString(),
                    sessionId = row.sessionId ?: convo.sessionId ?: convo.resumeAnchor,
                    origin = row.origin ?: convo.origin,
                )
            // §18.1 P1-3: askId is only unique per agent connection — the account-wide dedupe key must
            // be the composite, or two sessions both asking as "1" collapse into one row
            }.distinctBy { it.ask.convoId to it.ask.askId }.sortedBy { it.expiresAt ?: Long.MAX_VALUE }
        }

    /**
     * Release a warm conversation only when it has no turn, background job, pending approval/question, or
     * continuation grace left. Built-in chat bridges call this after a request settles: their session id stays
     * resumable on disk, but an idle chat no longer occupies one of the bridge's concurrent-request slots.
     *
     * The busy check and registry removal are one mutex operation so a reaper/release cannot observe an idle
     * conversation and then remove a newly-busy registry entry. False means either "still busy" or "already
     * gone"; callers that retry can distinguish those with [liveCountOf].
     */
    suspend fun closeIfIdle(convoId: String): Boolean {
        var tomb: Tombstone? = null
        val removed = mutex.withLock {
            val convo = convos[convoId] ?: return@withLock null
            if (convo.isBusy()) return@withLock null
            (removePendingCloseJobsLocked(convoId) to (convos.remove(convoId) ?: return@withLock null))
                .also { tomb = tombstoneLocked(it.second) } // S8
        } ?: return false
        removed.first.forEach { it.cancel() }
        closeRemoved(removed.second, tomb)
        log.info("closeIfIdle: released ${convoId.take(8)}… (sid=${removed.second.sessionId?.take(8) ?: "-"})")
        return true
    }

    /** Routes a prompt into its conversation. False = the convo is gone (idle-reaped / daemon restarted):
     *  the router answers [dev.ccpocket.protocol.SessionGone] so the phone can re-open + resend instead of
     *  the prompt vanishing into silence (the root of "sent a message, nothing happened"). */
    suspend fun sendPrompt(p: SendPrompt): Boolean {
        val convo = get(p.convoId) ?: return false
        convo.sendPrompt(p.text, p.images, p.promptId, p.diagnostic)
        return true
    }

    /** One-off bridge request approval, before the requester-controlled text reaches the agent. */
    suspend fun approveBridgeRequest(convoId: String, preview: String): Boolean =
        get(convoId)?.awaitBridgeRequestApproval(preview) ?: false

    /** issue #285 归属门：桥接用它判定一个 TurnDone 是否属于自己发出的那条请求（凭证=消费账本）。
     *  会话已不在册时回 UNKNOWN——此时也不会再有它的帧了，答案只影响日志措辞。 */
    suspend fun promptFate(convoId: String, promptId: String): PromptFate =
        get(convoId)?.promptFate(promptId) ?: PromptFate.UNKNOWN

    /** Execute the one request that just passed [approveBridgeRequest] under its ephemeral full grant. */
    suspend fun sendApprovedBridgePrompt(p: SendPrompt): Boolean {
        val convo = get(p.convoId) ?: return false
        return convo.sendApprovedBridgePrompt(p.text, p.promptId)
    }

    /**
     * Execute one request from a chat/project the owner PRE-TRUSTED (issues #198/#233). No approval card is
     * shown; a broad AUTO_TRUSTED grant is armed only for this prompt and revoked at turn end.
     *
     * In-process callers only, exactly like [approveBridgeRequest]: the trust decision is keyed on the
     * attested chat id, which only the built-in engine sees. No frame reaches this — the router has no branch
     * that calls it, so a bridge cannot claim its own request is trusted over the wire.
     */
    suspend fun sendTrustedBridgePrompt(p: SendPrompt): Boolean {
        val convo = get(p.convoId) ?: return false
        return convo.sendTrustedBridgePrompt(p.text, p.promptId)
    }

    /**
     * Execute one Guardian-passed request from a REVIEWED chat (reviewed-trust design) under the one-turn
     * REVIEWER_APPROVED grant — the Guardian path's closed ceiling, distinct from trusted full authority.
     *
     * In-process callers only, exactly like [sendTrustedBridgePrompt]: the review decision is made and
     * re-validated inside the built-in engine, and no router branch calls this — a bridge cannot claim its
     * own request passed review over the wire.
     */
    suspend fun sendReviewedBridgePrompt(p: SendPrompt, reviewId: String): Boolean {
        val convo = get(p.convoId) ?: return false
        return convo.sendReviewedBridgePrompt(p.text, p.promptId, reviewId = reviewId)
    }

    /** Execute one request in the configured owner's dedicated in-process bridge session. No wire route
     *  calls this; the Feishu engine exchanges its attested owner identity for a one-turn OWNER_BYPASS grant. */
    suspend fun sendOwnerBypassBridgePrompt(p: SendPrompt): Boolean {
        val convo = get(p.convoId) ?: return false
        return convo.sendOwnerBypassBridgePrompt(p.text, p.promptId)
    }

    suspend fun switchDir(s: SwitchDirectory) = get(s.convoId)?.switchDirectory(Path.of(s.workdir)) ?: Unit
    suspend fun switchMode(s: SwitchMode) = get(s.convoId)?.switchMode(s.mode, s.permissionMode) ?: Unit
    suspend fun switchServiceTier(s: SwitchServiceTier) = get(s.convoId)?.switchServiceTier(s.serviceTier) ?: Unit
    /** Authoritative mutation result for the App's tighten/clear acknowledgement. */
    suspend fun clearRule(c: ClearAllowRule): Boolean = get(c.convoId)?.clearAllowRule(c.rule) ?: false
    suspend fun cancelTurn(c: CancelTurn) = get(c.convoId)?.cancelTurn() ?: Unit
    suspend fun stopBackgroundJob(s: StopBackgroundJob) = get(s.convoId)?.stopBackgroundJob(s.jobId) ?: Unit

    /** A workflow agent's detail sheet opened — the conversation reads the full prompt/return off disk (#106). */
    suspend fun fetchWorkflowAgentDetail(f: GetWorkflowAgentDetail) =
        get(f.convoId)?.fetchWorkflowAgentDetail(f.runId, f.agentIndex, f.agentId) ?: Unit

    /** Older-history page (issue #147): a transcript parse answered to the REQUESTING sink only —
     *  works for both live conversations and read-only observe views. */
    suspend fun fetchHistoryPage(f: dev.ccpocket.protocol.FetchHistoryPage, sink: OutboundSink) {
        get(f.convoId)?.let { it.fetchHistoryPage(f.beforeSeq, f.limit, sink); return }
        mutex.withLock { observes[f.convoId] }?.fetchHistoryPage(f.beforeSeq, f.limit, sink)
    }

    /**
     * Rename [sessionId]'s title (issue #158) by landing Claude's own `custom-title` transcript record,
     * picking the writer by who holds the file:
     *  - a conversation THIS daemon is driving with a live process → the CLI renames itself
     *    (`rename_session` control_request; it appends the record and acks) — never a second appender
     *    on a file our child is writing;
     *  - a claude LIVE OUTSIDE the daemon (terminal) → refused: we can't control that process, and
     *    appending under a foreign writer risks splicing into a record it is mid-writing;
     *  - idle transcript (incl. a daemon convo with no live process yet) → [TranscriptRename.append],
     *    the CLI's exact record shape.
     * Returns null on success (the record is on disk — a rescan sees the new title), else a
     * human-readable failure for the client's error surface. Codex sessions have no transcript under
     * the project dir and fail with the not-found message (their rename path is out of #158's scope).
     */
    suspend fun renameSession(workdir: String, sessionId: String, title: String): String? {
        val t = title.trim()
        if (t.isEmpty()) return "title must not be empty"
        // a read-only bound session is renamed in its own app; same policy input as open/take-over
        for (agent in listOf(AgentKind.CLAUDE, AgentKind.CODEX)) controlRefusal(agent, workdir, sessionId)?.let { return it }
        // Pre-first-turn the agent hasn't reported a sessionId yet — match the resume anchor too (the
        // same identity [open] reattaches by above): a spawned-but-not-yet-init conversation already
        // holds the transcript, and a sessionId-only miss here read it as "idle disk" and appended
        // under our own child's pen.
        val live = mutex.withLock {
            convos.values.firstOrNull { it.sessionId == sessionId || (it.sessionId == null && it.resumeAnchor == sessionId) }
        }
        if (live != null && live.hasLiveProcess()) {
            log.info("rename ${sessionId.take(8)}… via live convo ${live.convoId.take(8)}…")
            return if (live.renameSession(t)) null
            else "the running agent didn't accept the rename — stop the session and try again"
        }
        val file = ProjectPaths.dirForUnder(projectsRoot, workdir).resolve("$sessionId.jsonl")
        if (!file.exists()) return "no transcript for this session here (Codex sessions can't be renamed yet)"
        if (externallyActive(sessionId, workdir, file)) {
            return "session is live in another client — rename it there (/rename) or stop it first"
        }
        log.info("rename ${sessionId.take(8)}… via idle transcript append")
        return if (dev.ccpocket.daemon.disk.TranscriptRename.append(file, sessionId, t)) null
        else "couldn't write the rename to the transcript"
    }

    /** Workdir of a live conversation — used by voice transcription for term injection. */
    suspend fun workdirOf(convoId: String): Path? = get(convoId)?.workdir

    /** The conversation's current permission mode — the authoritative input to the shell approval gate (issue #3). */
    suspend fun modeOf(convoId: String): PermissionMode? = get(convoId)?.currentMode()

    /** The conversation's CURRENT task id (approval design M2) — registry truth for the quick terminal's
     *  shared grant match; never trusted from the client. */
    suspend fun taskIdOf(convoId: String): String? = get(convoId)?.currentTaskId()

    /** Close [convoId]. With a [requester] (a client closing ITS view) this only detaches that client's
     *  sink — the conversation keeps streaming (to any other clients, else headless) when others are still
     *  attached (fan-out, issue #47) OR it is still busy (a turn in flight / background work / an unanswered
     *  ask). The phone sends CloseSession when it leaves a session its `streaming` flag calls idle, but plan
     *  mode's premature `result` clears that flag long before the turn truly ends (issue #55): a "done"-looking
     *  session the user taps away from is often still researching and about to ask a question. Killing it here
     *  would abort the turn and drop the question; instead it survives (the idle reaper reclaims it once it
     *  really goes idle with no client view attached — the same spare set as reapIdle/scheduleClose). Returns true
     *  when the conversation was actually closed.
     *
     *  [force] = the user explicitly asked to STOP the session (an account-switch blocker row), not to
     *  leave it: skip the keep-alive shield and kill it busy or not (transcript persists, resumable). */
    suspend fun close(convoId: String, requester: OutboundSink? = null, force: Boolean = false): Boolean {
        if (requester != null && !force) {
            val keepAlive = mutex.withLock {
                val c = convos[convoId] ?: return@withLock false // gone, or an observe session — fall through to close
                val emptied = c.detach(requester) // drop THIS view; true if no sinks remain
                !emptied || c.isBusy()
            }
            if (keepAlive) {
                log.info("detach ${convoId.take(8)}… (other clients or still busy — kept alive)")
                return false
            }
        }
        var tomb: Tombstone? = null
        val (jobs, convo, obs) = mutex.withLock {
            Triple(removePendingCloseJobsLocked(convoId), convos.remove(convoId), observes.remove(convoId))
                .also { removed -> tomb = removed.second?.let { tombstoneLocked(it) } } // S8
        }
        try {
            // §18.1 P1-5: a REAL close sweeps every pending approval of this conversation across ALL sources
            // (agent asks die with the conversation anyway; shell/export pending live in daemon-global
            // services and would otherwise stay approvable from the account inbox after the session is gone).
            // BEFORE convo.close() so the withdraw frames still ride the fan-out sinks.
            if (convo != null || obs != null) approvals.withdrawAllForConvo(convoId)
            jobs.forEach { it.cancel() }; convo?.close(); obs?.close()
            if (convo != null || obs != null) log.info("close ${convoId.take(8)}… (sid=${convo?.sessionId?.take(8) ?: "-"}, observe=${obs != null})")
            convo?.let { noteSelfClosed(it) }
        } finally {
            finishClose(tomb)
        }
        return convo != null || obs != null
    }

    /** Remember that WE closed this session just now — see [selfClosed]/[externallyActive]. Call AFTER
     *  [Conversation.close] returns: the dying process's final transcript flush must predate closedAt. */
    private suspend fun noteSelfClosed(convo: Conversation) {
        val sid = convo.sessionId ?: return // pre-first-turn: our silent process never wrote the transcript
        mutex.withLock { selfClosed[sid] = System.currentTimeMillis() }
    }

    /**
     * Detach [owner]'s view of [convoId] after [graceMs]. A reconnect under the SAME keyed sink identity
     * cancels this cleanup; a LAN reconnect uses a fresh plain sink and deliberately leaves the old
     * connection's cleanup armed, so only the zombie view is removed after the grace.
     * The LAN server uses this on socket drop instead of closing immediately: a flaky link / backgrounded phone
     * would otherwise instantly kill the claude process and rewrite the transcript, forcing the reconnect into a
     * cold `--resume` (issue #24's amplifier) and losing warm session state. Relay drops have their own grace
     * (reaperLoop's 90s idle window); this is the LAN equivalent. A second schedule replaces the first.
     *
     * [owner] is the scheduling connection's sink: at expiry the close only fires if the conversation is STILL
     * attached to that identity. A zombie socket's late `finally` (TCP can take minutes to give up) must not kill
     * a conversation whose keyed sink a newer connection replaced — the matching-key reconnect cancels this
     * timer before replacing the delegate. Plain LAN sinks have per-connection identity, so their old cleanup
     * remains armed and cannot detach the replacement.
     */
    suspend fun scheduleClose(convoId: String, owner: OutboundSink, graceMs: Long = LAN_DISCONNECT_GRACE_MS) {
        val key = PendingCloseKey(convoId, sinkKey(owner))
        // Register before the timer can execute. In particular delay(0) does not suspend, so a normally
        // started child on an immediate/concurrent dispatcher could otherwise inspect an empty map, return,
        // and then be installed as an already-completed job: the sink would never be detached and the stale
        // map entry would live until a later real close/shutdown.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val me = currentCoroutineContext()[Job]
            while (true) {
                delay(graceMs)
                beforePendingCloseClaim?.invoke()
                var retry = false
                var convo: Conversation? = null
                var obs: ObserveSession? = null
                var tomb: Tombstone? = null
                val ownsCurrentTimer = mutex.withLock {
                    // Ownership check and detach/removal share one critical section with open()'s claim.
                    // Never expose a window where this job has dropped its map identity but can still
                    // remove the replacement sink installed by a same-key reconnect.
                    if (pendingClose[key] !== me) return@withLock false
                    val currentConvo = convos[convoId]
                    val currentObs = observes[convoId]
                    when {
                        currentConvo != null && currentConvo.isAttachedTo(owner) && currentConvo.isBusy() -> {
                            // Keep this same job registered/cancellable across the next grace window.
                            retry = true
                        }
                        currentConvo != null && currentConvo.isAttachedTo(owner) -> {
                            pendingClose.remove(key)
                            if (currentConvo.detach(owner)) {
                                convos.remove(convoId)
                                convo = currentConvo
                                tomb = tombstoneLocked(currentConvo) // S8
                            }
                        }
                        currentObs != null && currentObs.isAttachedTo(owner) -> {
                            pendingClose.remove(key)
                            observes.remove(convoId)
                            obs = currentObs
                        }
                        else -> pendingClose.remove(key) // already detached/gone — retire this timer
                    }
                    true
                }
                if (!ownsCurrentTimer) return@launch
                if (retry) {
                    log.info("grace expiry ${convoId.take(8)}… still working → re-armed ${graceMs}ms")
                    continue
                }
                if (convo != null || obs != null) {
                    log.info("grace expiry closed ${convoId.take(8)}… (sid=${convo?.sessionId?.take(8) ?: "-"}, observe=${obs != null})")
                }
                try {
                    convo?.close(); obs?.close()
                    convo?.let { noteSelfClosed(it) }
                } finally {
                    finishClose(tomb)
                }
                return@launch
            }
        }
        mutex.withLock { pendingClose.put(key, job) }?.cancel()
        job.start()
    }

    /** Real conversation removal invalidates every connection-specific grace timer. */
    private suspend fun cancelPendingCloses(convoId: String) {
        mutex.withLock { removePendingCloseJobsLocked(convoId) }.forEach { it.cancel() }
    }

    /** Caller holds [mutex]. */
    private fun removePendingCloseJobsLocked(convoId: String): List<Job> {
        val hits = pendingClose.filterKeys { it.convoId == convoId }.values.toList()
        pendingClose.keys.removeAll { it.convoId == convoId }
        return hits
    }

    suspend fun closeAll() {
        val (all, obs, jobs) = mutex.withLock {
            Triple(
                convos.values.toList().also { convos.clear() },
                observes.values.toList().also { observes.clear() },
                pendingClose.values.toList().also { pendingClose.clear() },
            )
        }
        jobs.forEach { it.cancel() }
        // Close in parallel. The daemon-shutdown hook (Main.kt / DaemonServer.kt) runs this, and each
        // Conversation.close can spend AgentProcess's bounded EOF->SIGTERM->SIGKILL grace on a wedged
        // child (issue #101). Serialised, N wedged sessions would sum past launchd/systemd's stop
        // timeout and get the daemon SIGKILLed mid-flush — the very transcript loss #101 fixes. So the
        // per-session grace stays bounded AND the total stays ~one session's budget, not N.
        // runCatching per close so a single failure (or the scope being cancelled) can't abandon the
        // remaining reaps.
        coroutineScope {
            (all.map { async { runCatching { it.close() } } } +
                obs.map { async { runCatching { it.close() } } }).awaitAll()
        }
    }

    private suspend fun get(id: String): Conversation? = mutex.withLock { convos[id] }

    private companion object {
        // a backgrounded shell silent this long (no started/updated/result event) is treated as dead. Well above
        // any real launch-to-first-update gap, so a genuinely long-running background job is never reaped early.
        const val STALE_JOB_MS = 15 * 60 * 1000L

        // how long a LAN conversation survives a socket drop before being reaped, so a reconnecting phone can
        // reattach the still-warm claude process instead of paying a kill + transcript rewrite + cold resume.
        const val LAN_DISCONNECT_GRACE_MS = 30_000L

        // transcript writes no later than this past our own close are still "our" writes (FS timestamp
        // granularity + the post-exit unhide); anything newer means a real external claude took over.
        const val SELF_CLOSE_SLACK_MS = 1_500L

        // S8: how long a re-open waits for the previous conversation of the same session to finish closing.
        // A close spends at most one process shutdown ladder (EOF 3 s + TERM 2 s + KILL 2 s) plus the S5 drain
        // bound; past this the open proceeds as before rather than wedging every later open of the session.
        const val TOMBSTONE_WAIT_MS = 15_000L
    }
}
