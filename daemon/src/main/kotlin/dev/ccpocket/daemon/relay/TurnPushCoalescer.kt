package dev.ccpocket.daemon.relay

import dev.ccpocket.protocol.NotifyPush
import java.nio.file.Path

/** A turn end's flavor, ordered by severity (COMPLETE < ERROR < LIMIT). The [wire] name is what logs print. */
enum class TurnKind(val wire: String) { COMPLETE("complete"), ERROR("error"), LIMIT("limit") }

/** What [TurnPushCoalescer.decide] concluded for one turn end. */
sealed interface TurnPushDecision {
    val kind: TurnKind

    /** Put [push] on the control outbox. */
    data class Queued(val push: NotifyPush, override val kind: TurnKind) : TurnPushDecision

    /** The desktop's "notify my phone" switch is off — nothing sent, window not armed. */
    data class Disabled(override val kind: TurnKind) : TurnPushDecision

    /** An equal-or-more-severe push for the same session went out within the window. */
    data class Coalesced(override val kind: TurnKind) : TurnPushDecision
}

/**
 * Short-window merge of turn-end pushes (issue #382). Now that presence no longer suppresses them, a session
 * that finishes several quick turns (queued prompts, auto-continue, a crash right after a reply) would ring
 * the phone for each one.
 *
 * Rule, keyed by workdir + sessionId:
 *  - the first turn end pushes and arms a window of [windowMs] from THAT push;
 *  - within the window, a turn end whose severity is ≤ the last PUSHED one is coalesced (not sent);
 *  - a MORE severe one (complete → error → usage limit) is always sent and re-arms the window at its severity,
 *    so an error is never swallowed by an earlier "done";
 *  - once the window has elapsed, the next turn end pushes again regardless of severity;
 *  - a coalesced or disabled turn does not extend or arm the window;
 *  - no sessionId → never merged or tracked (workdir alone doesn't identify a conversation);
 *  - the clock is monotonic by default; an injected clock that steps backwards counts as "window elapsed".
 *
 * Pure apart from the injected [clock]; thread-safe (hooks fire from different conversation coroutines).
 */
class TurnPushCoalescer(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    /** Milliseconds from a MONOTONIC source — a wall-clock step (NTP, manual change) must not stretch a window. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Last(val atMs: Long, val kind: TurnKind)

    private val last = HashMap<String, Last>()

    @Synchronized
    fun decide(pushEnabled: Boolean, workdir: Path, sessionId: String?, finalText: String?, error: String?): TurnPushDecision {
        val kind = PushPolicy.turnKindOf(error)
        val push = PushPolicy.turnPushFor(pushEnabled, workdir, sessionId, finalText, error)
            ?: return TurnPushDecision.Disabled(kind)
        // no session id (before the first turn mints one) → nothing to key on: never merge, never track, so two
        // different not-yet-materialized conversations in one workdir can't swallow each other
        if (sessionId.isNullOrEmpty()) return TurnPushDecision.Queued(push, kind)
        val now = clock()
        val key = "$workdir\u0000$sessionId"
        val prev = last[key]
        val age = prev?.let { now - it.atMs }
        // age < 0 = the clock stepped backwards (only possible with an injected wall clock) → treat as elapsed
        if (prev != null && age != null && age in 0 until windowMs && kind.ordinal <= prev.kind.ordinal) {
            return TurnPushDecision.Coalesced(kind)
        }
        last[key] = Last(now, kind)
        if (last.size > PRUNE_AT) last.entries.removeIf { (now - it.value.atMs).let { a -> a < 0 || a >= windowMs } }
        return TurnPushDecision.Queued(push, kind)
    }

    /** How many session entries are tracked (tests: pruning / sid-less pushes). */
    @Synchronized
    internal fun trackedCount(): Int = last.size

    companion object {
        const val DEFAULT_WINDOW_MS = 30_000L
        private const val PRUNE_AT = 64

        /**
         * The one daemon-side log line per turn end — the counterpart of `ask-push …`, so "why didn't my phone
         * buzz" is answerable from daemon.err.log. Redacted: kind + first 8 chars of the session id only — never
         * title/body/final text/error text or the workdir.
         */
        fun logLine(decision: TurnPushDecision, sessionId: String?): String {
            val outcome = when (decision) {
                is TurnPushDecision.Queued -> "queued to relay"
                is TurnPushDecision.Disabled -> "not pushed (pushEnabled=false)"
                is TurnPushDecision.Coalesced -> "coalesced"
            }
            return "turn-push kind=${decision.kind.wire} sid=${shortSid(sessionId)} → $outcome"
        }

        fun shortSid(sessionId: String?): String = sessionId?.takeIf { it.isNotEmpty() }?.take(8) ?: "-"
    }
}
