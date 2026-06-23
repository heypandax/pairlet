package dev.ccpocket.daemon.conversation

import java.nio.file.Path

/**
 * Invoked when a conversation finishes a turn. The relay client wires this to send a [NotifyPush]
 * control frame so the relay can wake an offline phone. Null (the default) in local-server mode —
 * a LAN client has no relay to push through.
 *
 * [sessionId] is the conversation's current (possibly forked) claude session id — routing data so a
 * tapped notification can resume exactly this session. Null only before the first turn materializes one.
 */
fun interface PushHook {
    suspend fun onTurnComplete(workdir: Path, sessionId: String?, finalText: String?)
}
