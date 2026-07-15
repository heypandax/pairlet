package dev.ccpocket.daemon.conversation

import java.nio.file.Path

/**
 * Invoked when a conversation raises a permission ask/question that may need a push to reach a human.
 * Two flavors share this hook:
 *
 *  - BRIDGE-ORIGIN conversations (issue #91): the ask frame itself is structurally undeliverable to the
 *    bridge (egress whitelist), so this hook is how the OWNER finds out. [origin] is the bridge
 *    credential's name (for the alert title); the relay pushes it urgently regardless of presence.
 *  - The owner's OWN interactive conversations (issue #138): the ask fans out to attached clients as
 *    always, but with the phone locked/offline (or nobody attached to THIS conversation) the card goes
 *    unseen and the ask times out to a safe deny. [origin] is null then; the relay client gates the
 *    push on presence ([watched] + peer online) so an in-app user is never double-alerted.
 *
 * Either way the tapped notification deep-links into the session, where the existing reattach →
 * resurfacePending path re-shows the actual ask card (an ask answered elsewhere in the meantime is
 * simply not resurfaced — the pending map already dropped it). Null (the default) in local-server mode.
 * GUEST conversations (issue #115 pathScope) never fire it — the guest answers its own asks and the
 * owner must not be nudged for them.
 *
 * [tool] is a short human label of what is waiting ("Run command", "Edit file", …) — no input preview
 * crosses this hook, the lock screen doesn't need the command line. [watched] = at least one client
 * sink is currently attached to the conversation (someone received the ask frame on the data plane).
 *
 * Returns true when a push was actually queued — the conversation only "spends" its per-conversation
 * coalesce window on real pushes, so a suppressed attempt (phone was in the session) doesn't mute the
 * next ask's push after the user walks away (issue #138).
 */
fun interface AskPushHook {
    suspend fun onAskPending(workdir: Path, sessionId: String?, origin: String?, tool: String, watched: Boolean): Boolean
}
