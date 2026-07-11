package dev.ccpocket.daemon.bridge

import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.BackgroundJobs
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.ClearAllowRule
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.CommandList
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.FileDiff
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListPathEntries
import dev.ccpocket.protocol.ListSessionFiles
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PathEntries
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PromptAck
import dev.ccpocket.protocol.ReadFile
import dev.ccpocket.protocol.ReadFileDiff
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionFiles
import dev.ccpocket.protocol.SessionGone
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.StopBackgroundJob
import dev.ccpocket.protocol.SwitchMode
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.Transcript
import dev.ccpocket.protocol.TurnDone

/**
 * The capability firewall for a GUEST folder-share credential (issue #115). Like [BridgeCaps] both
 * directions are WHITELISTS — anything not listed is denied, so a message type added to the protocol
 * later is denied-by-default for a guest until someone consciously admits it here (pinned by an
 * exhaustive sealed-hierarchy test).
 *
 * A guest is a scoped INTERACTIVE client, so its whitelist is WIDER than a bridge's — crucially it may
 * SEND a [PermissionVerdict] (a guest approves its OWN session's asks) and RECEIVE a [PermissionAsk]
 * (which a bridge never can). But it is narrower than a full-power device: the entire management plane is
 * denied INGRESS (account switching, usage/quota, push prefs, the standalone terminal, and — the key
 * anti-escalation — re-sharing), and the daemon's identity/LAN surface is denied EGRESS.
 *
 * The PER-FRAME scope constraints (workdir under the shared root, own-session-only, the read-frame path
 * clamp, mode ceiling) are applied separately by [GuestGuard] — this object only decides frame ADMISSION.
 */
object GuestCaps {

    /** Whether a decoded outbound [frame] may be delivered to a guest. The data-plane frames + the
     *  interactive surfaces a scoped collaborator needs (asks it answers, its scoped listings/reads,
     *  slash commands, its task panel, voice results). NEVER the daemon's management/identity frames:
     *  Usage / AuthState / DaemonInfo (LAN address) / PushPrefs / the owner's Share* replies. */
    fun egressAllowed(frame: Frame): Boolean = when (frame) {
        // ---- the eight data-plane frames a bridge also gets ----
        is SessionLive -> true
        is ConvoHistory -> true
        is AssistantChunk -> true
        is ToolEvent -> true
        is TurnDone -> true
        is PromptAck -> true
        is PocketError -> true
        is SessionGone -> true
        // ---- guest-only additions: a guest is a human who approves + browses ----
        is PermissionAsk -> true   // the guest answers its OWN session's asks (a bridge never sees these)
        is AskWithdrawn -> true
        is CommandList -> true      // slash-command autocomplete for the guest's composer
        is BackgroundJobs -> true   // the guest's own task panel
        is Directories -> true      // scoped to the shared root by GuestScope (below)
        is Sessions -> true         // scoped to the guest's OWN sessions under the root
        is PathEntries -> true      // @-file completion, anchored to the root
        is SessionFiles -> true     // the guest's own session's changed files
        is FileContent -> true      // read of a file the guest's session touched (transcript-derived)
        is FileDiff -> true
        is Transcript -> true       // voice-capture result for the guest's composer
        // Everything else is dropped — notably Usage, AuthState, DaemonInfo, PushPrefs, ShareCreated,
        // ShareListing, ShareRevoked (owner-only). `else` keeps the whitelist closed by default.
        else -> false
    }

    /** The reason a guest is (or isn't) allowed to send a request TYPE. Structural admission only — the
     *  per-frame CONSTRAINTS (scope, own-session, mode ceiling) are applied by [GuestGuard]. */
    fun ingressAllowed(frame: Frame): Boolean = when (frame) {
        // session lifecycle + turn control (scope/ownership enforced by GuestGuard)
        is OpenSession -> true
        is SendPrompt -> true
        is CancelTurn -> true
        is CloseSession -> true
        // a guest approves its OWN asks — the defining difference from a bridge
        is PermissionVerdict -> true
        // mid-session controls on the guest's own session (mode is clamped to the tier ceiling by GuestGuard)
        is SwitchMode -> true
        is ClearAllowRule -> true
        is StopBackgroundJob -> true
        // scoped read/browse surfaces (all clamped to the shared root by GuestGuard / the router)
        is ListDirectories -> true
        is ListSessions -> true
        is ListPathEntries -> true
        is ListSessionFiles -> true
        is ReadFile -> true
        is ReadFileDiff -> true
        // composer voice capture on the guest's own session
        is AudioChunk -> true
        is AudioCancel -> true
        // DENIED — the management plane a scoped guest must never reach (issue #115 §8):
        //  FetchUsage / FetchAuthStatus / AuthLogin* / AuthLogout (account switching + quota),
        //  SetPushPrefs (owner's global push toggle), RunShellCommand (the standalone terminal power tool),
        //  SwitchDirectory (could move a session out of the shared root — a guest works only in the root),
        //  CreateShare / ListShares / RevokeShare (re-sharing the owner's machine — the core escalation).
        else -> false
    }

    /**
     * Clamp a guest's requested permission mode to its share's tier CEILING (issue #115). A guest may
     * always go MORE cautious than its tier, never less; and [PermissionMode.BYPASS_PERMISSIONS] is
     * unreachable for ANY tier — a scoped guest can never put the daemon into "approve nothing", so even
     * an "Autonomous" share still surfaces shell / dangerous actions for the guest to approve.
     */
    fun clampMode(requested: PermissionMode, tier: AccessTier): PermissionMode {
        val ceiling = AccessTier.ceiling(tier) // DEFAULT for Review, ACCEPT_EDITS for Collaborate/Autonomous
        return if (autonomy(requested) > autonomy(ceiling)) ceiling else requested
    }

    /** The autonomy rank used only for the tier clamp — higher = the agent acts with less human gating.
     *  PLAN (research/plan only) is the most cautious; BYPASS the least (and unreachable for a guest). */
    private fun autonomy(mode: PermissionMode): Int = when (mode) {
        PermissionMode.PLAN -> 0
        PermissionMode.DEFAULT -> 1
        PermissionMode.ACCEPT_EDITS -> 2
        PermissionMode.BYPASS_PERMISSIONS -> 3
    }
}
