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

    fun start() {
        scope.launch {
            runCatching {
                var lastMtime = -2L // first pass always announces, even when the file is missing (-1)
                while (isActive) {
                    val mtime = if (file.exists()) file.getLastModifiedTime().toMillis() else -1L
                    if (mtime != lastMtime) {
                        lastMtime = mtime
                        emitLive() // model/window/occupancy move as the observed terminal writes turns
                        val slice = when (agent) {
                            AgentKind.CODEX -> CodexTranscriptReplay.slice(file, sinceSeq = sentCursor)
                            else -> TranscriptReplay.slice(file, sinceSeq = sentCursor)
                        }
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
        val slice = when (agent) {
            AgentKind.CODEX -> CodexTranscriptReplay.page(file, beforeSeq, limit.coerceIn(1, 200))
            else -> TranscriptReplay.page(file, beforeSeq, limit.coerceIn(1, 200))
        }
        to.emit(dev.ccpocket.protocol.ConvoHistoryPage(convoId, slice.messages, firstSeq = slice.firstSeq, hasMore = slice.hasMore))
    }

    /** Announce with whatever the transcript knows (issue #27's observe gap): the last assistant turn's
     *  model, its usage as occupancy, and the window derived the same way live sessions derive it —
     *  including the observed-usage upgrade for beta-gated 1M models (occupancy > 200k proves 1M). */
    private suspend fun emitLive() {
        val codex = if (agent == AgentKind.CODEX) runCatching { CodexTranscriptScanner.runtimeState(file) }.getOrNull() else null
        val title = when (agent) {
            AgentKind.CODEX -> CodexTranscriptScanner.threadNames()[sessionId]?.takeIf { it.isNotBlank() }
                ?: runCatching { CodexTranscriptScanner.summarize(file, workdir)?.title }.getOrNull()
            AgentKind.CLAUDE -> runCatching { TranscriptScanner.summarize(file)?.title }.getOrNull()
            else -> null // OpenCode/Kimi/ZCode/DSH sessions are never opened through ObserveSession.
        }
        val model = codex?.model
            ?: if (agent == AgentKind.CLAUDE) runCatching { TranscriptScanner.lastModel(file) }.getOrNull() else null
        val used = codex?.contextUsed
            ?: if (agent == AgentKind.CLAUDE) runCatching { TranscriptScanner.lastContextTokens(file) }.getOrNull() else null
        val declaredWindow = codex?.contextWindow ?: model?.let(::contextWindowFor)
        val window = dev.ccpocket.protocol.provenWindow(declaredWindow, used)
        sink.emit(
            SessionLive(
                convoId, workdir, sessionId, observing = true,
                model = model, contextWindow = window, contextUsed = used, agent = agent, title = title,
            ),
        )
    }

    /** True while this observer still streams to [s] — key identity, like Conversation.isAttachedTo:
     *  the relay mints a fresh [KeyedSink] per inbound frame, so instance identity never matches a
     *  reconnected client's fresh sink (that mismatch let re-opens stack duplicate observers, issue
     *  #107). LAN sinks carry no key and keep the old instance-identity behavior. */
    fun isAttachedTo(s: OutboundSink): Boolean = sinkKey(sink) == sinkKey(s)

    fun close() = scope.cancel()
}
