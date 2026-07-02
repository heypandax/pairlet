package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.disk.TranscriptReplay
import dev.ccpocket.daemon.disk.TranscriptScanner
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.DEFAULT_CONTEXT_WINDOW
import dev.ccpocket.protocol.LARGE_CONTEXT_WINDOW
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
    private val sessionId: String,
    private val file: Path,
    private val sink: OutboundSink,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob() + CoroutineName("observe-$convoId"))

    fun start() {
        scope.launch {
            runCatching {
                var lastMtime = -2L // first pass always announces, even when the file is missing (-1)
                while (isActive) {
                    val mtime = if (file.exists()) file.getLastModifiedTime().toMillis() else -1L
                    if (mtime != lastMtime) {
                        lastMtime = mtime
                        emitLive() // model/window/occupancy move as the observed terminal writes turns
                        sink.emit(ConvoHistory(convoId, TranscriptReplay.read(file)))
                    }
                    delay(1500)
                }
            }.onFailure { close() } // any emit/IO failure (e.g. the phone disconnected) -> stop tailing
        }
    }

    /** Announce with whatever the transcript knows (issue #27's observe gap): the last assistant turn's
     *  model, its usage as occupancy, and the window derived the same way live sessions derive it —
     *  including the observed-usage upgrade for beta-gated 1M models (occupancy > 200k proves 1M). */
    private suspend fun emitLive() {
        val model = runCatching { TranscriptScanner.lastModel(file) }.getOrNull()
        val used = runCatching { TranscriptScanner.lastContextTokens(file) }.getOrNull()
        val window = when {
            (used ?: 0) > DEFAULT_CONTEXT_WINDOW -> LARGE_CONTEXT_WINDOW
            model != null -> contextWindowFor(model)
            else -> null
        }
        sink.emit(
            SessionLive(
                convoId, workdir, sessionId, observing = true,
                model = model, contextWindow = window, contextUsed = used, agent = AgentKind.CLAUDE,
            ),
        )
    }

    /** True while this observer still streams to [s] — the LAN grace-close ownership check. */
    fun isAttachedTo(s: OutboundSink): Boolean = sink === s

    fun close() = scope.cancel()
}
