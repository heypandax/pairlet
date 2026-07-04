package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.conversation.Conversation
import dev.ccpocket.daemon.conversation.ObserveSession
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.conversation.PushHook
import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.daemon.disk.TranscriptScanner
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.ClearAllowRule
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.SwitchDirectory
import dev.ccpocket.protocol.SwitchMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime

/** convoId -> live [Conversation]. Create on open (picking the backend by [OpenSession.agent]),
 *  relaunch on switch-dir, reap on close. */
class SessionRegistry(
    private val scope: CoroutineScope,
    private val backends: Map<AgentKind, AgentBackendFactory>,
) {
    private val mutex = Mutex()
    private val log = dev.ccpocket.daemon.util.logger("SessionRegistry")
    private val convos = mutableMapOf<String, Conversation>()
    private val observes = mutableMapOf<String, ObserveSession>()
    private val pendingClose = mutableMapOf<String, Job>() // convoId -> delayed-close job during a LAN disconnect grace

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
     *  merely the tail of a session we closed ourselves (writes after our close mean a real foreign claude). */
    private suspend fun externallyActive(sessionId: String, file: Path): Boolean {
        // one stat serves both the freshness gate and the ownership checks below
        val mtime = runCatching { if (file.exists()) file.getLastModifiedTime().toMillis() else null }.getOrNull() ?: return false
        if (System.currentTimeMillis() - mtime >= TranscriptScanner.LIVE_WINDOW_MS) return false
        // Restart amnesia: a write that predates this daemon's boot came from our PREVIOUS instance's own
        // claude (children die with the daemon, and the restart wiped [selfClosed], which would otherwise
        // prove ownership). Never read it as a foreign writer — the app auto-reopens its session seconds
        // after a daemon update, and landed in read-only observe with a spurious "Continue here" banner.
        // A real terminal claude keeps writing, so its mtime moves past our boot within one turn.
        if (mtime < startedAt) return false
        val closedAt = mutex.withLock { selfClosed[sessionId] }
        val verdict = closedAt == null || mtime > closedAt + SELF_CLOSE_SLACK_MS
        val now = System.currentTimeMillis()
        log.info(
            "externallyActive(${sessionId.take(8)}…): mtime ${now - mtime}ms ago, " +
                "selfClosed ${closedAt?.let { "${now - it}ms ago" } ?: "absent"} → $verdict",
        )
        return verdict
    }

    /** Installed by the relay client; null in local-server mode. Read per turn so a conversation opened
     *  before the relay attached still sees it. */
    @Volatile
    var pushHook: PushHook? = null

    /** Returns the opened convoId, or "" if the requested backend is unavailable (a PocketError is emitted). */
    suspend fun open(open: OpenSession, sink: OutboundSink): String {
        val resume = open.resumeId
        if (resume != null) {
            // re-attach to a session the daemon is already running (a cc-pocket background session).
            // Pre-first-turn the agent hasn't reported a sessionId yet — match the resume anchor too,
            // else a reconnect re-open spawns a second Conversation onto the same transcript.
            val live = mutex.withLock {
                convos.values.firstOrNull { it.sessionId == resume || (it.sessionId == null && it.resumeAnchor == resume) }
            }
            if (live != null) {
                log.info("open ${resume.take(8)}… → reattach ${live.convoId.take(8)}…")
                cancelPendingClose(live.convoId); live.reattach(sink); return live.convoId
            }
            // observe a Claude session running OUTSIDE the daemon (e.g. a terminal) — read-only, no spawn.
            // Claude-transcript specific; Codex resume falls through to a controlled thread/resume below.
            if (open.agent == AgentKind.CLAUDE && !open.takeOver) {
                val file = ProjectPaths.dirFor(open.workdir).resolve("$resume.jsonl")
                val recent = externallyActive(resume, file)
                if (recent) {
                    val convoId = UUID.randomUUID().toString()
                    log.info("open ${resume.take(8)}… → OBSERVE ${convoId.take(8)}… (foreign writer suspected)")
                    val obs = ObserveSession(convoId, open.workdir, resume, file, sink, scope)
                    mutex.withLock { observes[convoId] = obs }
                    obs.start()
                    return convoId
                }
            }
        }
        // resume + control: an idle session, or an explicit "Continue here" take-over
        val factory = backends[open.agent]
        if (factory == null) {
            sink.emit(PocketError("agent_unavailable", "no backend registered for ${open.agent}"))
            return ""
        }
        // create() is cheap + never throws (the binary resolves lazily on first launch); the real "CLI not
        // installed" failure surfaces synchronously from c.open() below, so one guard there covers it.
        val convoId = UUID.randomUUID().toString()
        val c = Conversation(convoId, Path.of(open.workdir), open.mode, sink, scope, factory.create(), pushHookProvider = { pushHook })
        mutex.withLock { convos[convoId] = c }
        // For an explicit take-over we bypassed the ObserveSession guard above, so a desktop `claude --resume`
        // MIGHT still be writing this transcript. Fork (branch to a fresh id, dodging a two-writer clobber) ONLY
        // when it was touched very recently — the desktop is actively writing. Otherwise resume IN PLACE on the
        // same sessionId: the phone truly takes over (issue #18 — no duplicate session) and the desktop picks up
        // the phone's turns on its next --resume (issue #22 — sync). Ordinary cold/idle resume already appends in
        // place. mtime is the same cross-platform liveness signal the ObserveSession guard above uses.
        val forkForTakeOver = open.takeOver && resume != null &&
            externallyActive(resume, ProjectPaths.dirFor(open.workdir).resolve("$resume.jsonl"))
        log.info(
            "open ${resume?.take(8) ?: "new"}${if (open.takeOver) " (take-over)" else ""} → " +
                "convo ${convoId.take(8)}… agent=${open.agent}${if (forkForTakeOver) " FORK" else ""}",
        )
        val started = runCatching { c.open(open.resumeId, open.model, open.effort, fork = forkForTakeOver) }
        if (started.isFailure) {
            mutex.withLock { convos.remove(convoId) }
            runCatching { c.close() }
            sink.emit(PocketError("agent_unavailable", "${open.agent} CLI not found — is it installed? (${started.exceptionOrNull()?.message})"))
            return ""
        }
        return convoId
    }

    /** Resumable sessions for [workdir] across every agent backend (each tags its summaries with its kind), newest-first. */
    fun listSessions(workdir: String): List<SessionSummary> =
        backends.values.flatMap { runCatching { it.create().listSessions(workdir) }.getOrDefault(emptyList()) }
            .sortedByDescending { it.lastModified }

    /**
     * Close conversations with no agent activity for longer than [idleMs]. Returns the reap count.
     * A conversation with running background work is NEVER reaped — killing it would take its still-running
     * background shells / sub-agents with it (the "I left it running" case this is meant to preserve).
     */
    suspend fun reapIdle(idleMs: Long): Int {
        // first settle background jobs whose completion event never arrived — otherwise their forever-RUNNING
        // status keeps hasBackgroundWork() true and the session can never be reaped (and the phone's "N running"
        // count never clears). Snapshot outside the lock so the per-conversation emit doesn't hold the mutex.
        mutex.withLock { convos.values.toList() }.forEach { runCatching { it.reapStaleJobs(STALE_JOB_MS) } }
        val now = System.currentTimeMillis()
        val stale = mutex.withLock {
            val s = convos.filterValues { now - it.lastActivityMs > idleMs && !it.hasBackgroundWork() }
            convos.keys.removeAll(s.keys)
            s.values.toList()
        }
        stale.forEach { it.close(); noteSelfClosed(it) }
        return stale.size
    }

    /** cwds of live conversations with running background work — kept "active" in the project list even when idle. */
    suspend fun busyCwds(): Set<String> =
        mutex.withLock { convos.values.filter { it.hasBackgroundWork() }.map { it.workdir.toString() }.toSet() }

    /** sessionIds of live conversations with running background work — keep their session row's "running" badge on. */
    suspend fun busySessionIds(): Set<String> =
        mutex.withLock { convos.values.filter { it.hasBackgroundWork() }.mapNotNull { it.sessionId }.toSet() }

    /** Routes a prompt into its conversation. False = the convo is gone (idle-reaped / daemon restarted):
     *  the router answers [dev.ccpocket.protocol.SessionGone] so the phone can re-open + resend instead of
     *  the prompt vanishing into silence (the root of "sent a message, nothing happened"). */
    suspend fun sendPrompt(p: SendPrompt): Boolean {
        val convo = get(p.convoId) ?: return false
        convo.sendPrompt(p.text, p.images)
        return true
    }
    suspend fun verdict(v: PermissionVerdict) = get(v.convoId)?.submitVerdict(v) ?: Unit
    suspend fun switchDir(s: SwitchDirectory) = get(s.convoId)?.switchDirectory(Path.of(s.workdir)) ?: Unit
    suspend fun switchMode(s: SwitchMode) = get(s.convoId)?.switchMode(s.mode) ?: Unit
    suspend fun clearRule(c: ClearAllowRule) = get(c.convoId)?.clearAllowRule(c.rule) ?: Unit
    suspend fun cancelTurn(c: CancelTurn) = get(c.convoId)?.cancelTurn() ?: Unit

    /** Workdir of a live conversation — used by voice transcription for term injection. */
    suspend fun workdirOf(convoId: String): Path? = get(convoId)?.workdir

    /** The conversation's current permission mode — the authoritative input to the shell approval gate (issue #3). */
    suspend fun modeOf(convoId: String): PermissionMode? = get(convoId)?.currentMode()

    suspend fun close(convoId: String) {
        val (job, convo, obs) = mutex.withLock {
            Triple(pendingClose.remove(convoId), convos.remove(convoId), observes.remove(convoId))
        }
        job?.cancel(); convo?.close(); obs?.close()
        if (convo != null || obs != null) log.info("close ${convoId.take(8)}… (sid=${convo?.sessionId?.take(8) ?: "-"}, observe=${obs != null})")
        convo?.let { noteSelfClosed(it) }
    }

    /** Remember that WE closed this session just now — see [selfClosed]/[externallyActive]. Call AFTER
     *  [Conversation.close] returns: the dying process's final transcript flush must predate closedAt. */
    private suspend fun noteSelfClosed(convo: Conversation) {
        val sid = convo.sessionId ?: return // pre-first-turn: our silent process never wrote the transcript
        mutex.withLock { selfClosed[sid] = System.currentTimeMillis() }
    }

    /**
     * Close [convoId] after [graceMs] UNLESS a reconnect reattaches first (which calls [cancelPendingClose]).
     * The LAN server uses this on socket drop instead of closing immediately: a flaky link / backgrounded phone
     * would otherwise instantly kill the claude process and rewrite the transcript, forcing the reconnect into a
     * cold `--resume` (issue #24's amplifier) and losing warm session state. Relay drops have their own grace
     * (reaperLoop's 90s idle window); this is the LAN equivalent. A second schedule replaces the first.
     *
     * [owner] is the scheduling connection's sink: at expiry the close only fires if the conversation is STILL
     * attached to it. A zombie socket's late `finally` (TCP can take minutes to give up) must not kill a
     * conversation a newer connection has since reattached — and the same check closes the reattach-vs-expiry
     * race (a reattach re-points the sink before cancelPendingClose lands).
     */
    suspend fun scheduleClose(convoId: String, owner: OutboundSink, graceMs: Long = LAN_DISCONNECT_GRACE_MS) {
        val job = scope.launch {
            delay(graceMs)
            // deregister BEFORE closing: otherwise close() below would cancel this very job mid-flight
            mutex.withLock { pendingClose.remove(convoId) }
            // A conversation still WORKING (streaming turn / running background jobs) survives its owner's
            // disconnect: re-arm and check again next window, close only once truly idle. Killing in-flight
            // work because the app quit (update/relaunch/crash) is exactly what the task-complete push
            // promises never happens — the relay path's idle reaper already spares busy convos this way.
            val busy = mutex.withLock {
                convos[convoId]?.takeIf { it.isAttachedTo(owner) }?.let { it.isExecuting() || it.hasBackgroundWork() } == true
            }
            if (busy) {
                log.info("grace expiry ${convoId.take(8)}… still working → re-armed ${graceMs}ms")
                scheduleClose(convoId, owner, graceMs); return@launch
            }
            val (convo, obs) = mutex.withLock {
                val c = convos[convoId]
                val o = observes[convoId]
                when {
                    c != null && c.isAttachedTo(owner) -> { convos.remove(convoId); c to null }
                    o != null && o.isAttachedTo(owner) -> { observes.remove(convoId); null to o }
                    else -> null to null // reattached elsewhere (or already gone) — not ours to kill
                }
            }
            if (convo != null || obs != null) {
                log.info("grace expiry closed ${convoId.take(8)}… (sid=${convo?.sessionId?.take(8) ?: "-"}, observe=${obs != null})")
            }
            convo?.close(); obs?.close()
            convo?.let { noteSelfClosed(it) }
        }
        mutex.withLock { pendingClose.put(convoId, job) }?.cancel()
    }

    private suspend fun cancelPendingClose(convoId: String) {
        mutex.withLock { pendingClose.remove(convoId) }?.cancel()
    }

    suspend fun closeAll() {
        val all = mutex.withLock { convos.values.toList().also { convos.clear() } }
        val obs = mutex.withLock { observes.values.toList().also { observes.clear() } }
        all.forEach { it.close() }
        obs.forEach { it.close() }
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
    }
}
