package dev.ccpocket.daemon.conversation

import java.nio.file.Path

/**
 * Invoked when a BRIDGE-ORIGIN conversation (issue #91) raises a permission ask/question — the frame
 * itself is structurally undeliverable to the bridge (egress whitelist), so this hook is how the OWNER
 * finds out: the relay client wires it to a [dev.ccpocket.protocol.NotifyPush], the relay pushes it
 * only while no interactive device socket is live, and the tapped notification deep-links into the
 * session where the existing reattach → resurfacePending path re-shows the actual ask card. Null (the
 * default) in local-server mode. Interactive conversations never fire it — their ask reaches the
 * attached client directly, exactly as today.
 *
 * [origin] is the bridge credential's name (for the alert title); [tool] a short human label of what
 * is waiting ("Run command", "Edit file", …) — no input preview crosses this hook, the lock screen
 * doesn't need the command line.
 */
fun interface AskPushHook {
    suspend fun onAskPending(workdir: Path, sessionId: String?, origin: String, tool: String)
}
