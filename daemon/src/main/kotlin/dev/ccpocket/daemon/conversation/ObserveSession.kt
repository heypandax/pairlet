package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.codex.CodexTranscriptReplay
import dev.ccpocket.daemon.codex.CodexTranscriptScanner
import dev.ccpocket.daemon.disk.TranscriptReplay
import dev.ccpocket.daemon.disk.TranscriptScanner
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.SessionLive
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
import kotlin.io.path.getLastModifiedTime

/**
 * A read-only live view of a session running OUTSIDE the daemon (e.g. in a terminal). Tails the
 * transcript file: emits the history, then re-emits it whenever the file is appended to. No claude
 * process is spawned — the phone's "Continue here" resumes a controllable one separately.
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
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob() + CoroutineName("observe-$convoId"))

    // the cursor of the last replay WE sent this observer's client — each tail tick then replays only
    // the appended delta instead of re-sending the whole 100-row window on every write (issue #147)
    private var sentCursor: Long? = sinceSeq?.takeIf { it > 0 }

    // ONE dispatch point (issue #301): slice/page/state/title all route through the reader picked here,
    // instead of four separately-maintained `when (agent)` blocks that had to stay consistent by hand.
    // A future observable backend adds one Reader implementation and one factory arm.
    private val reader: Reader = Reader.forAgent(agent)

    fun start() {
        scope.launch {
            runCatching {
                var lastMtime = -2L // first pass always announces, even when the file is missing (-1)
                while (isActive) {
                    val mtime = if (file.exists()) file.getLastModifiedTime().toMillis() else -1L
                    if (mtime != lastMtime) {
                        lastMtime = mtime
                        emitLive() // model/window/occupancy move as the observed terminal writes turns
                        val slice = reader.slice(file, sentCursor)
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
                        if (sinceSeq != null) sentCursor = slice.lastSeq ?: sentCursor
                    }
                    delay(1500)
                }
            }.onFailure { close() } // any emit/IO failure (e.g. the phone disconnected) -> stop tailing
        }
    }

    /** Older-history page for an observed session (issue #147) — same shape as Conversation's. */
    suspend fun fetchHistoryPage(beforeSeq: Long, limit: Int, to: OutboundSink) {
        val slice = reader.page(file, beforeSeq, limit.coerceIn(1, 200))
        to.emit(dev.ccpocket.protocol.ConvoHistoryPage(convoId, slice.messages, firstSeq = slice.firstSeq, hasMore = slice.hasMore))
    }

    /** Announce with whatever the transcript knows (issue #27's observe gap): the last assistant turn's
     *  model, its usage as occupancy, and the window derived the same way live sessions derive it —
     *  including the observed-usage upgrade for beta-gated 1M models (occupancy > 200k proves 1M). */
    private suspend fun emitLive() {
        val state = reader.state(file)
        val title = titleFor(runCatching { file.getLastModifiedTime().toMillis() }.getOrDefault(-1L))
        val declaredWindow = state.contextWindow ?: state.model?.let(::contextWindowFor)
        val window = dev.ccpocket.protocol.provenWindow(declaredWindow, state.contextUsed)
        sink.emit(
            SessionLive(
                convoId, workdir, sessionId, observing = true,
                model = state.model, contextWindow = window, contextUsed = state.contextUsed, agent = agent, title = title,
            ),
        )
    }

    /**
     * The observed transcript's title, memoized by the file's mtime (PR #296 review). Deriving it re-reads
     * the transcript — a Codex thread with no index title falls all the way back to a full `summarize` — and
     * an announce for an unchanged file must not pay for that twice. Confined to this instance and to the
     * single tail coroutine, so no locking.
     */
    private var titleMemo: Pair<Long, String?>? = null

    private fun titleFor(mtime: Long): String? {
        titleMemo?.let { if (it.first == mtime) return it.second }
        val title = reader.title(file, workdir, sessionId)
        titleMemo = mtime to title
        return title
    }

    /** What one observable backend knows how to read off its transcript file. */
    internal data class ObservedState(val model: String?, val contextUsed: Long?, val contextWindow: Long?)

    internal interface Reader {
        fun slice(file: Path, sinceSeq: Long?): dev.ccpocket.daemon.disk.ReplaySlice
        fun page(file: Path, beforeSeq: Long, limit: Int): dev.ccpocket.daemon.disk.ReplaySlice
        fun state(file: Path): ObservedState
        fun title(file: Path, workdir: String, sessionId: String): String?

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
}
