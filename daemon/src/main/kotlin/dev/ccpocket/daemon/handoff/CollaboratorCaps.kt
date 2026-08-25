package dev.ccpocket.daemon.handoff

import dev.ccpocket.protocol.AcceptHandoff
import dev.ccpocket.protocol.AcknowledgeReviewRequest
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.ConvoHistoryPage
import dev.ccpocket.protocol.DeclineHandoff
import dev.ccpocket.protocol.DeclineReviewRequest
import dev.ccpocket.protocol.FetchHistoryPage
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.FileContentChunk
import dev.ccpocket.protocol.FileDiff
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.GetReviewRequest
import dev.ccpocket.protocol.HandoffListing
import dev.ccpocket.protocol.HandoffUpdated
import dev.ccpocket.protocol.ListHandoffs
import dev.ccpocket.protocol.ListReviewRequests
import dev.ccpocket.protocol.ListSessionFiles
import dev.ccpocket.protocol.MarkReviewDelivered
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PromptAck
import dev.ccpocket.protocol.ReadFile
import dev.ccpocket.protocol.ReadFileDiff
import dev.ccpocket.protocol.RespondReviewRequest
import dev.ccpocket.protocol.ReturnHandoff
import dev.ccpocket.protocol.ReviewListing
import dev.ccpocket.protocol.ReviewUpdated
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.StartReviewRequest
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

    /**
     * May this decoded inbound [frame] TYPE be considered from a collaborator holding a [purpose] link?
     * (Constraint checks — own-offer binding, IN_PROGRESS grant, workdir/session match — live in
     * [CollaboratorGuard] and the router's recipient filters; this is structural admission only.)
     *
     * The PURPOSE split is the outer gate and comes first: the two feature planes are disjoint, and a
     * credential minted for one has no business being considered for the other's frames even before the
     * per-frame rules run. [CollaboratorPurpose.UNKNOWN] admits neither plane — a link whose scope this
     * build cannot read is a link it cannot police.
     */
    fun ingressAllowed(
        frame: Frame,
        purpose: CollaboratorPurpose = CollaboratorPurpose.SESSION_HANDOFF,
    ): Boolean {
        val plane = planeOf(frame)
        if (plane != null && plane != purpose) return false
        return typeAllowed(frame)
    }

    /**
     * Which feature a frame belongs to, or null for the frames neither owns (an error, say). Used by both
     * directions so ingress and egress cannot disagree about what a frame IS.
     */
    private fun planeOf(frame: Frame): CollaboratorPurpose? = when (frame) {
        is AcceptHandoff, is DeclineHandoff, is ReturnHandoff, is ListHandoffs,
        is HandoffUpdated, is HandoffListing,
        -> CollaboratorPurpose.SESSION_HANDOFF
        is ListReviewRequests, is GetReviewRequest, is MarkReviewDelivered, is AcknowledgeReviewRequest,
        is StartReviewRequest, is DeclineReviewRequest, is RespondReviewRequest,
        is ReviewUpdated, is ReviewListing,
        -> CollaboratorPurpose.REVIEW
        // The granted-session data plane belongs to the feature that GRANTS a session, and only Session
        // Handoff ever does. [CollaboratorGuard] already refuses every one of these without a live
        // IN_PROGRESS grant — but leaving them unclassified would make this gate's promise ("a purpose
        // this build cannot read reaches neither plane") false for the largest and most dangerous frame
        // set here, resting the whole argument on one guard that a future null-grant path could soften.
        is OpenSession, is SendPrompt, is CancelTurn, is PermissionVerdict, is CloseSession,
        is FetchHistoryPage, is ListSessionFiles, is ReadFile, is ReadFileDiff,
        is SessionLive, is ConvoHistory, is ConvoHistoryPage, is AssistantChunk, is ToolEvent,
        is TurnDone, is PromptAck, is SessionGone, is PermissionAsk, is AskWithdrawn,
        is SessionFiles, is FileContent, is FileContentChunk, is FileDiff,
        -> CollaboratorPurpose.SESSION_HANDOFF
        // everything left is owned by neither: an error either side may see, and the frames both
        // whitelists deny anyway
        else -> null
    }

    private fun typeAllowed(frame: Frame): Boolean = when (frame) {
        // ---- the handoff plane: the baseline capability (own offers only — router/registry-enforced) ----
        is AcceptHandoff -> true
        is DeclineHandoff -> true
        is ReturnHandoff -> true
        is ListHandoffs -> true // the router filters the listing to recipientDeviceId == this device
        // ---- the ReviewRequest RECIPIENT plane (REVIEW-REQUEST.md §11.1) ----
        // A task-context handoff needs NO session access at all, so these frames add exactly four powers:
        // see the requests addressed to me, confirm I have one on disk, move my own status, return my
        // result. The router re-checks the recipient binding against the transport-proven deviceId for
        // every one of them — admission here is structural only.
        is ListReviewRequests -> true
        is GetReviewRequest -> true
        is MarkReviewDelivered -> true
        is AcknowledgeReviewRequest -> true
        is StartReviewRequest -> true
        is DeclineReviewRequest -> true
        is RespondReviewRequest -> true
        // NOT admitted, and this is the whole point of the plane: CreateReviewRequest / CancelReviewRequest /
        // CloseReviewRequest are the OWNER's. A contact can answer what it was asked; it can never mint a
        // request in someone else's name, withdraw one, or close one to make a result look acknowledged.
        //
        // Also NOT admitted — the OWNER-LOCAL plane (pocket/review.contacts / contact_invite / contact_join /
        // contact_remove / inbox / prepare / inbox_act): those expose or mutate THIS machine's whole peer
        // inbox and contact ledger across every colleague. They fall through to `else` below; the router's
        // owner check is the second gate. A contact that could call them would read every other contact's
        // brief and mint links in its host's name.
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
        // Everything else — discovery, management, mode/tier changes, re-invites — is denied. That
        // includes presets/rename/groups/switch-dir and, since #201/#202, SetApprovalPrefs and the
        // session-archive pair (a collaborator must not reshape the owner's session lists).
        else -> false
    }

    /**
     * Whether a decoded outbound [frame] may be delivered to a collaborator holding a [purpose] link.
     * Its own handoff updates (fan-out ALSO filters by recipient before sealing — this is the type gate),
     * plus the granted session's data plane. NEVER the daemon's management/identity frames: DaemonInfo
     * (LAN address), Usage/AuthState, Directories/Sessions (discovery), and the Collaborator/Share/Bridge
     * owner-plane replies.
     *
     * Purpose-gated in BOTH directions, which matters more than it looks: a Session Handoff device that
     * received `pocket/review.*` would learn the ids and briefs of work it has no part in, and a
     * misrouted broadcast is exactly the bug this catches — the sender-side recipient filter and this
     * gate would both have to be wrong at once.
     */
    fun egressAllowed(
        frame: Frame,
        purpose: CollaboratorPurpose = CollaboratorPurpose.SESSION_HANDOFF,
    ): Boolean {
        val plane = planeOf(frame)
        if (plane != null && plane != purpose) return false
        return egressTypeAllowed(frame)
    }

    private fun egressTypeAllowed(frame: Frame): Boolean = when (frame) {
        is HandoffUpdated -> true
        is HandoffListing -> true
        // its OWN review requests. The type gate is only half of it: ReviewService's fan-out filters by
        // the sink's bound recipientDeviceId FIRST, so type-allowing these can't broadcast one colleague's
        // brief to another (see ReviewService.broadcast).
        is ReviewUpdated -> true
        is ReviewListing -> true
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
