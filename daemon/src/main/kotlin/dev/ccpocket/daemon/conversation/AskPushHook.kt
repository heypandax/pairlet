package dev.ccpocket.daemon.conversation

import java.nio.file.Path

/**
 * Invoked when a conversation raises a permission ask/question that may need a push to reach a human.
 * Two flavors share this hook:
 *
 *  - BRIDGE-ORIGIN conversations (issue #91): the ask frame itself is structurally undeliverable to the
 *    bridge (egress whitelist), so this hook is how the OWNER finds out. [origin] is the bridge
 *    credential's name (for the alert title).
 *  - The owner's OWN interactive conversations (issue #138): the ask fans out to attached clients as
 *    always, but a locked / pocketed phone sees no card and the ask times out to a safe deny. [origin] is null.
 *
 * Either way the relay client pushes whenever the daemon's pushEnabled switch is on, and only then (2026-10,
 * issue #382 applied to asks). Presence used to gate the owner flavor ([watched] + relay peer online + LAN
 * client), but the owner's desktop App is attached around the clock, so that gate muted every owner ask; a
 * phone showing the very session in the foreground now hides the banner itself. The tapped notification
 * deep-links into the session, where the existing reattach → resurfacePending path re-shows the actual ask
 * card (an ask answered elsewhere in the meantime is simply not resurfaced — the pending map already dropped
 * it). Null (the default) in local-server mode. GUEST conversations (issue #115 pathScope) never fire it —
 * the guest answers its own asks and the owner must not be nudged for them.
 *
 * [tool] is a short human label of what is waiting ("Run command", "Edit file", …) — no input preview
 * crosses this hook, the lock screen doesn't need the command line. [watched] = at least one client
 * sink is currently attached to the conversation (someone received the ask frame on the data plane) —
 * informational now: it rides into the conversation's `ask-push …` log line, not into the decision.
 *
 * Returns true when a push was actually queued — the conversation only "spends" its per-conversation
 * coalesce window on real pushes, so an attempt while the switch is off doesn't mute the next ask's push
 * once it is back on (issue #138).
 */
fun interface AskPushHook {
    suspend fun onAskPending(workdir: Path, sessionId: String?, origin: String?, tool: String, watched: Boolean): Boolean
}
