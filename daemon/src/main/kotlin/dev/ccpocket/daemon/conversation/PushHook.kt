package dev.ccpocket.daemon.conversation

import java.nio.file.Path

/**
 * Invoked when a conversation finishes a turn — normally OR abnormally (issue #138). The relay client
 * wires this to send a [NotifyPush] control frame so the relay can wake an offline phone. Null (the
 * default) in local-server mode — a LAN client has no relay to push through.
 *
 * [sessionId] is the conversation's current (possibly forked) claude session id — routing data so a
 * tapped notification can resume exactly this session. Null only before the first turn materializes one.
 *
 * [error] non-null = the turn did NOT complete cleanly: a turn that ended in an error (synthetic
 * placeholder / is_error result — carries the error summary), or the agent process dying unexpectedly
 * mid-session (then [finalText] is null and [error] is the exit summary). The relay client words these
 * pushes differently from a normal turn-complete — including the usage-limit case it detects from the
 * error text (issue #138) — so a locked phone learns the session is stuck, not merely done.
 */
fun interface PushHook {
    suspend fun onTurnComplete(workdir: Path, sessionId: String?, finalText: String?, error: String?)
}
