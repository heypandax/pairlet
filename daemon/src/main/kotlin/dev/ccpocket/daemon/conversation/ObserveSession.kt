package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.diagnostics.completeInitialHistory

import dev.ccpocket.daemon.codex.CodexPaths
import dev.ccpocket.daemon.codex.CodexTranscriptReplay
import dev.ccpocket.daemon.codex.CodexTranscriptScanner
import dev.ccpocket.daemon.disk.TranscriptReplay
import dev.ccpocket.daemon.disk.TranscriptScanner
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.ObservationBinding
import dev.ccpocket.protocol.ObservedProgress
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionObservation
import dev.ccpocket.protocol.contextWindowFor
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime

/**
 * A read-only live view of a session running OUTSIDE the daemon (e.g. in a terminal). Tails the
 * transcript file: emits the history, then re-emits it whenever the file is appended to. No claude
 * process is spawned — the phone's "Continue here" resumes a controllable one separately.
 *
 * Read-only observation (docs/design/DOTS-SESSION-OBSERVABILITY.md): with [readOnly] the view is a POLICY, not
 * a writer-detection outcome — the registry refuses take-over for it, and every announce carries the member's
 * [binding] plus the latest proven turn [ObservedProgress] (to a peer that declared the capability). The tail
 * then also follows the session's NEWEST native file ([fileResolver]: a Codex resume continues in a second
 * rollout) and restarts its cursor when the file is switched, truncated or replaced, so a stale line number can
 * never cut the new file's history short.
 */
class ObserveSession(
    val convoId: String,
    private val workdir: String,
    val sessionId: String, // exposed: the registry reaps a client's stale observer of the same session (issue #107)
    private val file: Path,
    private val sink: OutboundSink,
    parentScope: CoroutineScope,
    private val agent: AgentKind = AgentKind.CLAUDE,
    /** The observing client's transcript cursor from its OpenSession (issue #147). NON-NULL also
     *  DECLARES the client understands delta frames (a new client sends 0 when it holds no transcript
     *  yet) — an old client omits the field and keeps today's full-window tick behavior: feeding a
     *  delta to a client that treats every ConvoHistory as a full window would wipe its scrollback. */
    private val sinceSeq: Long? = null,
    /** Read-only by policy (a persisted binding or a one-shot `observeOnly`), not merely "a writer was seen". */
    val readOnly: Boolean = false,
    private val binding: ObservationBinding? = null,
    /** The peer declared [dev.ccpocket.protocol.ClientCaps.supportsSessionObservationV1]: announce the snapshot. */
    private val observationCapable: Boolean = false,
    /** The session's CURRENT native file, re-resolved every tick; null = keep [file]. Defaults to the Codex
     *  logical-id lookup for Codex (a resume writes a new rollout), nothing for other backends. */
    private val fileResolver: (() -> Path?)? = if (agent == AgentKind.CODEX) ({ CodexPaths.findSession(sessionId) }) else null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 1500,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob() + CoroutineName("observe-$convoId"))

    // the cursor of the last replay WE sent this observer's client — each tail tick then replays only
    // the appended delta instead of re-sending the whole 100-row window on every write (issue #147)
    private var sentCursor: Long? = sinceSeq?.takeIf { it > 0 }

    // ONE dispatch point (issue #301): slice/page/state/title all route through the reader picked here,
    // instead of four separately-maintained `when (agent)` blocks that had to stay consistent by hand.
    // A future observable backend adds one Reader implementation and one factory arm.
    private val reader: Reader = Reader.forAgent(agent)

    /** The file being tailed right now; switches when [fileResolver] names a newer one (confined to the tail coroutine). */
    @Volatile
    private var activeFile: Path = file

    /** The last progress announced — a changed freshness (CURRENT → STALE at 60 s) re-announces without a file write. */
    private var lastProgress: ObservedProgress? = null

    fun start() {
        scope.launch {
            runCatching {
                var initialReplay = true
                var lastMtime = -2L // first pass always announces, even when the file is missing (-1)
                var lastSize = -1L
                while (isActive) {
                    // follow the session's newest native file: a Codex resume continues in a NEW rollout and the old
                    // path stops growing. A switch is a new generation — the cursor is a line number in the OLD file.
                    val resolved = fileResolver?.let { runCatching { it() }.getOrNull() }
                    if (resolved != null && resolved != activeFile) {
                        activeFile = resolved
                        sentCursor = null
                        lastMtime = -2L
                        lastSize = -1L
                    }
                    val current = activeFile
                    val mtime = if (current.exists()) current.getLastModifiedTime().toMillis() else -1L
                    val size = if (mtime >= 0) runCatching { current.fileSize() }.getOrDefault(-1L) else -1L
                    // a file that SHRANK was truncated or replaced (not appended): line numbers past the cut would
                    // skip or repeat rows — restart from a full window, like a switch
                    if (lastSize >= 0 && size in 0 until lastSize) sentCursor = null
                    val changed = mtime != lastMtime || size != lastSize
                    if (changed) {
                        lastMtime = mtime
                        lastSize = size
                        emitLive(current) // model/window/occupancy move as the observed terminal writes turns
                        val slice = reader.slice(current, sentCursor)
                        // an empty DELTA = the client is already caught up (noise-only appends) — nothing
                        // to send. An empty FULL still goes out: that's today's "file gone/empty" wipe.
                        if (slice.messages.isNotEmpty() || !slice.delta) {
                            sink.emit(
                                ConvoHistory(
                                    convoId, slice.messages,
                                    lastSeq = slice.lastSeq, firstSeq = slice.firstSeq,
                                    delta = slice.delta, hasMore = slice.hasMore,
                                ),
                            )
                        }
                        // only a delta-capable client (sinceSeq != null, see above) graduates to delta
                        // ticks; an old client keeps receiving the full window on every write
                        if (initialReplay) {
                            initialReplay = false
                            sink.completeInitialHistory(convoId, slice.messages.size, slice.messages.isNotEmpty() || !slice.delta, slice.quality, slice.sourceRows, slice.failedRows)
                        }
                        if (sinceSeq != null) sentCursor = slice.lastSeq ?: sentCursor
                    } else if (observationCapable) {
                        // no new bytes: the evidence is unchanged, but its AGE is not — re-announce only when the
                        // snapshot itself differs (freshness bucket moved), never on every tick. observedAt is the
                        // read clock and moves every tick by definition, so it is left out of the comparison.
                        val progress = progressOf(current)
                        if (progress?.copy(observedAt = 0) != lastProgress?.copy(observedAt = 0)) emitLive(current)
                    }
                    delay(tickMs)
                }
            }.onFailure { close() } // any emit/IO failure (e.g. the phone disconnected) -> stop tailing
        }
    }

    /** Older-history page for an observed session (issue #147) — same shape as Conversation's. */
    suspend fun fetchHistoryPage(beforeSeq: Long, limit: Int, to: OutboundSink) {
        val slice = reader.page(activeFile, beforeSeq, limit.coerceIn(1, 200))
        to.emit(dev.ccpocket.protocol.ConvoHistoryPage(convoId, slice.messages, firstSeq = slice.firstSeq, hasMore = slice.hasMore))
    }

    /** The latest turn progress of [current] as the shared reducer derives it; null for a backend without evidence. */
    private fun progressOf(current: Path): ObservedProgress? {
        val now = clock()
        val exists = current.exists()
        val mtime = if (exists) runCatching { current.getLastModifiedTime().toMillis() }.getOrNull() else null
        val turns = if (exists) reader.turns(current) else null
        // a backend that yields no evidence of any kind has nothing to announce — the row keeps "no snapshot"
        if (turns == null && !reader.hasProgress) return null
        return ObservedProgressReducer.reduce(turns, mtime, exists, now)
    }

    /** Announce with whatever the transcript knows (issue #27's observe gap): the last assistant turn's
     *  model, its usage as occupancy, and the window derived the same way live sessions derive it —
     *  including the observed-usage upgrade for beta-gated 1M models (occupancy > 200k proves 1M). */
    private suspend fun emitLive(current: Path = activeFile) {
        val state = reader.state(current)
        val title = titleFor(current, runCatching { current.getLastModifiedTime().toMillis() }.getOrDefault(-1L))
        val declaredWindow = state.contextWindow ?: state.model?.let(::contextWindowFor)
        val window = dev.ccpocket.protocol.provenWindow(declaredWindow, state.contextUsed)
        val progress = if (observationCapable) progressOf(current) else null
        lastProgress = progress
        sink.emit(
            SessionLive(
                convoId, workdir, sessionId, observing = true,
                model = state.model, contextWindow = window, contextUsed = state.contextUsed, agent = agent, title = title,
                // an old peer still learns the policy from the notice (it cannot decode the snapshot)
                notice = if (readOnly) READ_ONLY_NOTICE else null,
                observation = if (observationCapable && (readOnly || binding != null || progress != null)) {
                    SessionObservation(binding = binding, readOnly = readOnly, progress = progress)
                } else null,
            ),
        )
    }

    /**
     * The observed transcript's title, memoized by the file's mtime (PR #296 review). Deriving it re-reads
     * the transcript — a Codex thread with no index title falls all the way back to a full `summarize` — and
     * an announce for an unchanged file must not pay for that twice. Confined to this instance and to the
     * single tail coroutine, so no locking.
     */
    private var titleMemo: Triple<Path, Long, String?>? = null

    private fun titleFor(current: Path, mtime: Long): String? {
        titleMemo?.let { if (it.first == current && it.second == mtime) return it.third }
        val title = reader.title(current, workdir, sessionId)
        titleMemo = Triple(current, mtime, title)
        return title
    }

    /** What one observable backend knows how to read off its transcript file. */
    internal data class ObservedState(val model: String?, val contextUsed: Long?, val contextWindow: Long?)

    internal interface Reader {
        fun slice(file: Path, sinceSeq: Long?): dev.ccpocket.daemon.disk.ReplaySlice
        fun page(file: Path, beforeSeq: Long, limit: Int): dev.ccpocket.daemon.disk.ReplaySlice
        fun state(file: Path): ObservedState
        fun title(file: Path, workdir: String, sessionId: String): String?

        /** Whether this backend's transcript carries turn-lifecycle records the progress reducer understands. */
        val hasProgress: Boolean get() = false

        /** The latest turn's evidence, or null when the backend has none / the file could not be read. */
        fun turns(file: Path): TurnEvidence? = null

        companion object {
            fun forAgent(agent: AgentKind): Reader = when (agent) {
                AgentKind.CODEX -> CodexReader
                AgentKind.CLAUDE -> ClaudeReader
                else -> InertReader // no per-session file → never admitted to observe (SessionRegistry)
            }
        }
    }

    private object CodexReader : Reader {
        override fun slice(file: Path, sinceSeq: Long?) = CodexTranscriptReplay.slice(file, sinceSeq = sinceSeq)
        override fun page(file: Path, beforeSeq: Long, limit: Int) = CodexTranscriptReplay.page(file, beforeSeq, limit)
        override fun state(file: Path): ObservedState {
            val rt = runCatching { CodexTranscriptScanner.runtimeState(file) }.getOrNull()
            return ObservedState(rt?.model, rt?.contextUsed, rt?.contextWindow)
        }
        override fun title(file: Path, workdir: String, sessionId: String): String? =
            CodexTranscriptScanner.threadNames()[sessionId]?.takeIf { it.isNotBlank() }
                ?: runCatching { CodexTranscriptScanner.summarize(file, workdir)?.title }.getOrNull()
        override val hasProgress: Boolean get() = true
        // served by the same (path, mtime) memo as state(): one read per file version, not one per field
        override fun turns(file: Path): TurnEvidence? = runCatching { CodexTranscriptScanner.turnEvidence(file) }.getOrNull()
    }

    private object ClaudeReader : Reader {
        override fun slice(file: Path, sinceSeq: Long?) = TranscriptReplay.slice(file, sinceSeq = sinceSeq)
        override fun page(file: Path, beforeSeq: Long, limit: Int) = TranscriptReplay.page(file, beforeSeq, limit)
        override fun state(file: Path) = ObservedState(
            model = runCatching { TranscriptScanner.lastModel(file) }.getOrNull(),
            contextUsed = runCatching { TranscriptScanner.lastContextTokens(file) }.getOrNull(),
            contextWindow = null, // derived from the model by the caller
        )
        override fun title(file: Path, workdir: String, sessionId: String): String? =
            runCatching { TranscriptScanner.summarize(file)?.title }.getOrNull()
    }

    private object InertReader : Reader {
        override fun slice(file: Path, sinceSeq: Long?) = dev.ccpocket.daemon.disk.ReplaySlice.EMPTY
        override fun page(file: Path, beforeSeq: Long, limit: Int) = dev.ccpocket.daemon.disk.ReplaySlice.EMPTY
        override fun state(file: Path) = ObservedState(null, null, null)
        override fun title(file: Path, workdir: String, sessionId: String): String? = null
    }

    /** True while this observer still streams to [s] — key identity, like Conversation.isAttachedTo:
     *  the relay mints a fresh [KeyedSink] per inbound frame, so instance identity never matches a
     *  reconnected client's fresh sink (that mismatch let re-opens stack duplicate observers, issue
     *  #107). LAN sinks carry no key and keep the old instance-identity behavior. */
    fun isAttachedTo(s: OutboundSink): Boolean = sinkKey(sink) == sinkKey(s)

    fun close() = scope.cancel()

    companion object {
        /** [SessionLive.notice] of a read-only view: what an OLD peer (no snapshot) sees instead of "Continue here". */
        const val READ_ONLY_NOTICE = "read-only: this session is driven elsewhere and only observed here"
    }
}
