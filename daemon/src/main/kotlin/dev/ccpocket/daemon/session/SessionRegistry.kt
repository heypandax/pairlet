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
    private val convos = mutableMapOf<String, Conversation>()
    private val observes = mutableMapOf<String, ObserveSession>()

    /** Installed by the relay client; null in local-server mode. Read per turn so a conversation opened
     *  before the relay attached still sees it. */
    @Volatile
    var pushHook: PushHook? = null

    /** Returns the opened convoId, or "" if the requested backend is unavailable (a PocketError is emitted). */
    suspend fun open(open: OpenSession, sink: OutboundSink): String {
        val resume = open.resumeId
        if (resume != null) {
            // re-attach to a session the daemon is already running (a cc-pocket background session)
            val live = mutex.withLock { convos.values.firstOrNull { it.sessionId == resume } }
            if (live != null) { live.reattach(sink); return live.convoId }
            // observe a Claude session running OUTSIDE the daemon (e.g. a terminal) — read-only, no spawn.
            // Claude-transcript specific; Codex resume falls through to a controlled thread/resume below.
            if (open.agent == AgentKind.CLAUDE && !open.takeOver) {
                val file = ProjectPaths.dirFor(open.workdir).resolve("$resume.jsonl")
                val recent = runCatching {
                    file.exists() && System.currentTimeMillis() - file.getLastModifiedTime().toMillis() < TranscriptScanner.LIVE_WINDOW_MS
                }.getOrDefault(false)
                if (recent) {
                    val convoId = UUID.randomUUID().toString()
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
        // fork only for an explicit take-over (this path bypassed the ObserveSession guard above, so the
        // resumed transcript may still be written by a desktop claude); an ordinary cold-resume appends in place.
        val started = runCatching { c.open(open.resumeId, open.model, open.effort, fork = open.takeOver) }
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
        stale.forEach { it.close() }
        return stale.size
    }

    /** cwds of live conversations with running background work — kept "active" in the project list even when idle. */
    suspend fun busyCwds(): Set<String> =
        mutex.withLock { convos.values.filter { it.hasBackgroundWork() }.map { it.workdir.toString() }.toSet() }

    /** sessionIds of live conversations with running background work — keep their session row's "running" badge on. */
    suspend fun busySessionIds(): Set<String> =
        mutex.withLock { convos.values.filter { it.hasBackgroundWork() }.mapNotNull { it.sessionId }.toSet() }

    suspend fun sendPrompt(p: SendPrompt) = get(p.convoId)?.sendPrompt(p.text, p.images) ?: Unit
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
        mutex.withLock { convos.remove(convoId) }?.close()
        mutex.withLock { observes.remove(convoId) }?.close()
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
    }
}
