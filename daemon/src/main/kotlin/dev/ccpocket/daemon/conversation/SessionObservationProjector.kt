package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.codex.CodexPaths
import dev.ccpocket.daemon.codex.CodexTranscriptScanner
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ObservedProgress
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime

/**
 * The LIST side of the progress snapshot (docs/design/DOTS-SESSION-OBSERVABILITY.md §4.4): the managed-list
 * projection asks for a bound member's latest turn progress here, and the observe view asks the same reducer, so a
 * row and its detail never disagree. Served from the scanner's (path, mtime) memo — the list scan has just parsed
 * the same file, so this is a stat and a lookup, not a second parse.
 */
object SessionObservationProjector {
    fun progressFor(agent: AgentKind, sessionId: String, now: Long = System.currentTimeMillis()): ObservedProgress? = when (agent) {
        AgentKind.CODEX -> {
            val file = runCatching { CodexPaths.findSession(sessionId) }.getOrNull()
            val exists = file?.exists() == true
            val mtime = if (exists) runCatching { file!!.getLastModifiedTime().toMillis() }.getOrNull() else null
            val turns = if (exists) runCatching { CodexTranscriptScanner.turnEvidence(file!!) }.getOrNull() else null
            ObservedProgressReducer.reduce(turns, mtime, exists, now)
        }
        else -> null // no lifecycle evidence vocabulary verified for other backends: no snapshot, not a guess
    }
}
