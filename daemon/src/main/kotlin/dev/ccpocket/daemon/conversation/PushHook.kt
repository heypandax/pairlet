package dev.ccpocket.daemon.conversation

import java.nio.file.Path

/**
 * Invoked when a conversation finishes a turn. The relay client wires this to send a [NotifyPush]
 * control frame so the relay can wake an offline phone. Null (the default) in local-server mode —
 * a LAN client has no relay to push through.
 */
fun interface PushHook {
    suspend fun onTurnComplete(workdir: Path, finalText: String?)
}
