package dev.ccpocket.daemon.handoff

import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.isTerminal

/**
 * The pure "who may drive this session" gate (SESSION-HANDOFF.md §5.3 items 2/3): given a sessionId
 * and the TRANSPORT-derived deviceId of the sender, decide whether a SendPrompt / CancelTurn /
 * question answer / permission verdict may proceed. Stateless over [HandoffRegistry] truth — it never
 * mutates, so it is safe to consult on every inbound frame.
 *
 * The whole table:
 *  - no non-terminal handoff       → [Verdict.Allow] (handoff machinery is inert; existing owner/guest
 *                                    gates still apply upstream);
 *  - WAITING                       → deny EVERYONE (invariant 2: no lease exists, the initiator must
 *                                    cancel/recall before typing again);
 *  - IN_PROGRESS                   → allow ONLY the unexpired lease's controller (the recipient);
 *                                    a missing/expired/mismatched lease denies everyone — fail closed
 *                                    until the registry sweep settles it to RECALLED; a lease marked
 *                                    recallRequested (§5.4 graceful recall in flight) denies EVERYONE,
 *                                    the controller included, until the recall settles;
 *  - RETURNED                      → control is back with the initiator's side: deny only the
 *                                    recipient device; every owner device may drive again;
 *  - DRAFT / UNKNOWN (non-terminal)→ deny everyone — a state this build can't read keeps the session
 *                                    conservatively locked (never fall open on version skew).
 *
 * WIRING TODO (this class is complete; the call sites are not):
 *  - SessionRegistry / RequestRouter: call [canDrive] with the session's persistent sessionId and the
 *    sending device's id before SendPrompt, CancelTurn, PermissionVerdict (answers included) and any
 *    other input-shaped frame; map a [Verdict.Deny] to a PocketError carrying [Verdict.Deny.message];
 *  - the pre-first-turn window (no sessionId yet) cannot carry a handoff — gate on the resumed/known
 *    sessionId only;
 *  - HANDOFF-credential capability caps (frame whitelist) are a SEPARATE, additional layer
 *    (HandoffCaps.kt, §9.2) — this gate only arbitrates control among already-admitted devices.
 */
class HandoffGuard(private val registry: HandoffRegistry) {

    /** Why a [Verdict.Deny] happened — machine-readable for PocketError codes + client copy. */
    enum class DenyReason {
        /** WAITING: nobody may type; the initiator must cancel (or the invite expire) first. */
        WAITING_LOCKED,

        /** IN_PROGRESS and the sender is not the lease-holding recipient. */
        NOT_CONTROLLER,

        /** IN_PROGRESS but the lease is missing/expired/mismatched — locked until the sweep recalls. */
        LEASE_INVALID,

        /** A graceful recall is in flight (§5.4): the initiator asked for control back mid-turn, the
         *  daemon interrupted it and is waiting for the stable point. NOBODY drives in that window —
         *  not the recipient (no new work under a lease that is being taken away) and not the
         *  initiator (its input must not race the dying turn). */
        RECALL_PENDING,

        /** RETURNED: the recipient's control ended; only the initiator's side may drive. */
        RETURNED_TO_INITIATOR,

        /** DRAFT/UNKNOWN non-terminal state — fail closed on anything this build can't read. */
        STATE_UNREADABLE,
    }

    sealed interface Verdict {
        data object Allow : Verdict
        data class Deny(val reason: DenyReason, val message: String) : Verdict
    }

    /**
     * May [deviceId] send input (prompt / cancel / answer / verdict) into [sessionId] right now?
     * [now] is injectable for tests; production callers use the registry's own clock via the default.
     */
    fun canDrive(sessionId: String, deviceId: String, now: Long = System.currentTimeMillis()): Verdict {
        val (handoff, lease) = registry.activeFor(sessionId) ?: return Verdict.Allow
        return when (handoff.status) {
            HandoffStatus.WAITING -> Verdict.Deny(
                DenyReason.WAITING_LOCKED,
                "This session is waiting for a handoff to be accepted — cancel the handoff to type again.",
            )
            HandoffStatus.IN_PROGRESS -> when {
                lease == null || lease.handoffId != handoff.id || lease.leaseExpiresAt <= now -> Verdict.Deny(
                    DenyReason.LEASE_INVALID,
                    "The handoff's controller lease is no longer valid — control is being returned to the owner.",
                )
                // §5.4: recall armed mid-turn — the window between "interrupt sent" and "turn stopped"
                // belongs to nobody. Checked BEFORE the controller match so the lease holder is refused too.
                lease.recallRequested -> Verdict.Deny(
                    DenyReason.RECALL_PENDING,
                    "This handoff is being recalled — waiting for the current turn to stop.",
                )
                deviceId == lease.controllerDeviceId -> Verdict.Allow
                else -> Verdict.Deny(
                    DenyReason.NOT_CONTROLLER,
                    "${handoff.recipientLabel ?: "The recipient"} is controlling this session — recall the handoff to take over.",
                )
            }
            HandoffStatus.RETURNED ->
                if (deviceId == handoff.recipientDeviceId) Verdict.Deny(
                    DenyReason.RETURNED_TO_INITIATOR,
                    "You returned this handoff — control is back with ${handoff.initiatorLabel ?: "the initiator"}.",
                ) else Verdict.Allow
            else ->
                // DRAFT should never be persisted and UNKNOWN is a newer peer's state; a terminal
                // status can't reach here (activeFor filters them). Any of them: fail closed.
                if (handoff.status.isTerminal) Verdict.Allow else Verdict.Deny(
                    DenyReason.STATE_UNREADABLE,
                    "This session's handoff is in a state this daemon cannot interpret — update the daemon.",
                )
        }
    }
}
