package dev.ccpocket.daemon.handoff

import dev.ccpocket.protocol.AcceptHandoff
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.ConvoHistoryPage
import dev.ccpocket.protocol.DeclineHandoff
import dev.ccpocket.protocol.FetchHistoryPage
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.FileContentChunk
import dev.ccpocket.protocol.FileDiff
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HandoffListing
import dev.ccpocket.protocol.HandoffUpdated
import dev.ccpocket.protocol.ListHandoffs
import dev.ccpocket.protocol.ListSessionFiles
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PromptAck
import dev.ccpocket.protocol.ReadFile
import dev.ccpocket.protocol.ReadFileDiff
import dev.ccpocket.protocol.ReturnHandoff
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionFiles
import dev.ccpocket.protocol.SessionGone
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.TurnDone

/**
 * The capability firewall for a COLLABORATOR link credential (SESSION-HANDOFF.md §4.1/§8.1). Like
 * [dev.ccpocket.daemon.bridge.BridgeCaps]/[dev.ccpocket.daemon.bridge.GuestCaps], both directions are
 * WHITELISTS — anything not listed is denied, so protocol additions are denied-by-default until
 * consciously admitted here.
 *
 * The BASELINE of a Collaborator Link is ZERO session access: the ingress list admits only the
 * handoff-plane frames (accept/decline/return/list its own offers) plus the session frame TYPES a live
 * Handoff Grant needs (open/prompt/answer/read). Frame ADMISSION here never grants session access by
 * itself — [CollaboratorGuard] additionally requires an IN_PROGRESS handoff bound to this exact device
 * for every session-shaped frame, so outside a grant the whole session surface fails closed.
 *
 * Deliberately DENIED ingress (the management + discovery plane a contact must never reach):
 * ListDirectories / ListSessions / ListPathEntries (the owner's disk layout), CreateHandoff /
 * CancelHandoff / RecallHandoff / CompleteHandoff (initiator-side transitions), every collaborator/
 * share/bridge management frame (re-inviting = the core escalation), auth/usage/push/shell/schedule/
 * presets/rename/groups/switch-dir, and SwitchMode (the grant's mode ceiling is the owner's decision).
 * TODO(SESSION-HANDOFF §7/§9, later milestones — each stays fail-closed until admitted): AudioChunk
 * voice capture, StopBackgroundJob on the granted session, ClientCaps declaration (a collaborator app
 * currently gets the conservative undeclared filtering).
 */
object CollaboratorCaps {

    /** May this decoded inbound [frame] TYPE be considered from a collaborator? (Constraint checks —
     *  own-offer binding, IN_PROGRESS grant, workdir/session match — live in [CollaboratorGuard] and
     *  the router's recipient filters; this is structural admission only.) */
    fun ingressAllowed(frame: Frame): Boolean = when (frame) {
        // ---- the handoff plane: the baseline capability (own offers only — router/registry-enforced) ----
        is AcceptHandoff -> true
        is DeclineHandoff -> true
        is ReturnHandoff -> true
        is ListHandoffs -> true // the router filters the listing to recipientDeviceId == this device
        // ---- the granted Source Session, ONLY while a bound handoff is IN_PROGRESS (guard-enforced) ----
        is OpenSession -> true      // resume of the handoff's source session, nothing else
        is SendPrompt -> true
        is CancelTurn -> true
        is PermissionVerdict -> true // the recipient answers its own granted session's asks/questions
        is CloseSession -> true      // detach from its own granted convo
        is FetchHistoryPage -> true  // older-history paging of the granted convo
        is ListSessionFiles -> true  // review surfaces: the granted session's changed files + diffs
        is ReadFile -> true
        is ReadFileDiff -> true
        // Everything else — discovery, management, mode/tier changes, re-invites — is denied.
        else -> false
    }

    /** Whether a decoded outbound [frame] may be delivered to a collaborator. Its own handoff updates
     *  (fan-out ALSO filters by recipient before sealing — this is the type gate), plus the granted
     *  session's data plane. NEVER the daemon's management/identity frames: DaemonInfo (LAN address),
     *  Usage/AuthState, Directories/Sessions (discovery), and the Collaborator/Share/Bridge owner-plane
     *  replies. */
    fun egressAllowed(frame: Frame): Boolean = when (frame) {
        is HandoffUpdated -> true
        is HandoffListing -> true
        is PocketError -> true
        // ---- granted-session data plane (mirrors the guest set, minus discovery/voice/task panel) ----
        is SessionLive -> true
        is ConvoHistory -> true
        is ConvoHistoryPage -> true
        is AssistantChunk -> true
        is ToolEvent -> true
        is TurnDone -> true
        is PromptAck -> true
        is SessionGone -> true
        is PermissionAsk -> true
        is AskWithdrawn -> true
        is SessionFiles -> true
        is FileContent -> true
        is FileContentChunk -> true
        is FileDiff -> true
        else -> false
    }
}
