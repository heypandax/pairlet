package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.disk.TranscriptReplay
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.SessionLive
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
                sink.emit(SessionLive(convoId, workdir, sessionId, observing = true))
                var lastMtime = -1L
                while (isActive) {
                    val mtime = if (file.exists()) file.getLastModifiedTime().toMillis() else -1L
                    if (mtime != lastMtime) {
                        lastMtime = mtime
                        sink.emit(ConvoHistory(convoId, TranscriptReplay.read(file)))
                    }
                    delay(1500)
                }
            }.onFailure { close() } // any emit/IO failure (e.g. the phone disconnected) -> stop tailing
        }
    }

    fun close() = scope.cancel()
}
