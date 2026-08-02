package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.bridge.PathScope
import dev.ccpocket.daemon.bridge.TierClamp
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.AcceptHandoff
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.DeclineHandoff
import dev.ccpocket.protocol.FetchHistoryPage
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.ListHandoffs
import dev.ccpocket.protocol.ListSessionFiles
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.ReadFile
import dev.ccpocket.protocol.ReadFileDiff
import dev.ccpocket.protocol.ReturnHandoff
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionHandoff
import dev.ccpocket.protocol.AgentKind

/** What the router needs to know about a vetted COLLABORATOR frame: the transport-proven deviceId
 *  (drives the handoff gates + recipient filtering) and, for an OpenSession, the path scope its
 *  conversation's PermissionBridge must confine file tools to plus the grant's [access] ceiling
 *  (REVIEW_READ_ONLY hard-refuses write tools in the bridge — §8.3). Defaults FAIL CLOSED: an
 *  access nobody set is read-only, never scoped-write. */
data class CollaboratorScope(
    val deviceId: String,
    val pathScope: List<String> = emptyList(),
    val access: HandoffAccess = HandoffAccess.REVIEW_READ_ONLY,
)

/**
 * Per-credential enforcement of the Collaborator Link's ZERO-baseline + temporary Handoff Grant
 * (SESSION-HANDOFF.md §4.1/§8): the collaborator analogue of [dev.ccpocket.daemon.bridge.GuestGuard].
 * One instance per collaborator deviceId (created by [HandoffService.collaboratorGuard]); the relay
 * pumps one device's frames sequentially, so @Synchronized on the mutating methods is enough.
 *
 * The rule, per frame class (after [CollaboratorCaps] admitted the TYPE):
 *  - handoff-plane frames pass through (the ROUTER enforces the own-offer binding + listing filter,
 *    and [HandoffRegistry] the state machine);
 *  - OpenSession is allowed ONLY as a resume of the Source Session of a handoff that is IN_PROGRESS,
 *    lease-held by THIS device — workdir forced to the handoff's, takeOver stripped, mode clamped to
 *    the grant's ceiling. No grant → no session, ever (the zero baseline);
 *  - convo-keyed frames (prompt/cancel/verdict/history/close) act only on convos THIS guard vetted
 *    open, and re-check the grant is STILL IN_PROGRESS on every frame — the instant the handoff
 *    leaves IN_PROGRESS (returned/recalled/completed) every one of them denies, which is what makes
 *    "Grant lifetime == IN_PROGRESS" true without trusting any client state;
 *  - session reads (changed files / file / diff) require the same live grant and must name exactly
 *    the granted (workdir, sessionId).
 *
 * §8.3 enforcement: HandoffAccess.REVIEW_READ_ONLY is enforced as (a) the REVIEW-tier mode clamp,
 * (b) the pathScope confining file tools to the handoff's workdir/allowedRoots, and (c) the grant's
 * [HandoffAccess] riding [Verdict.Allow.access] into the conversation's PermissionBridge, which
 * HARD-REFUSES write tools (Write/Edit/…) before any ask exists — so the recipient, who IS the lease
 * controller and answers its own asks, has no write ask to self-approve.
 */
class CollaboratorGuard(
    private val deviceId: String,
    private val registry: HandoffRegistry,
) {
    sealed interface Verdict {
        /** [frame] may be rewritten (workdir forced, mode clamped, takeOver/force stripped) and MUST
         *  replace the original; [pathScope] + the grant's [access] ceiling ride into the
         *  conversation's PermissionBridge on open ([access] is null for non-open frames). */
        data class Allow(
            val frame: Frame,
            val pathScope: List<String> = emptyList(),
            val access: HandoffAccess? = null,
        ) : Verdict
        data class Deny(val code: String, val message: String) : Verdict
    }

    /** convoId -> the handoffId whose grant opened it — the per-frame re-check key. Bounded. */
    private val convoGrants = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_TRACKED
    }

    /** The handoff the LAST vetted open was granted under — bound to its convoId by [noteOpened]
     *  (the router's onOpened callback fires synchronously inside the same sequential frame pump). */
    private var pendingGrantId: String? = null

    @Synchronized
    fun vet(frame: Frame, now: Long = System.currentTimeMillis()): Verdict = when (frame) {
        // handoff-plane: structural pass — the router + registry enforce the own-offer binding
        is AcceptHandoff, is DeclineHandoff, is ReturnHandoff, is ListHandoffs -> Verdict.Allow(frame)
        is OpenSession -> vetOpen(frame, now)
        is SendPrompt -> grantedConvo(frame.convoId, frame)
        is CancelTurn -> grantedConvo(frame.convoId, frame)
        is PermissionVerdict -> grantedConvo(frame.convoId, frame)
        is FetchHistoryPage -> grantedConvo(frame.convoId, frame)
        // detaching from an owned convo is allowed even after the grant ended (a returned recipient's
        // client closing its pane must not error), but force-close is never a collaborator's to use
        is CloseSession -> if (frame.convoId in convoGrants) Verdict.Allow(frame.copy(force = false)) else notGranted()
        is ListSessionFiles -> grantedRead(frame.workdir, frame.sessionId, frame, now)
        is ReadFile -> grantedRead(frame.workdir, frame.sessionId, frame, now)
        is ReadFileDiff -> grantedRead(frame.workdir, frame.sessionId, frame, now)
        else -> Verdict.Deny("collaborator_forbidden", "that action isn't permitted for a collaborator link")
    }

    /** The one door into a session: resume of the granted handoff's Source Session while IN_PROGRESS. */
    private fun vetOpen(f: OpenSession, now: Long): Verdict {
        val resume = f.resumeId
            ?: return Verdict.Deny("handoff_grant_required", "a collaborator can only open the session of an accepted handoff")
        val h = liveGrantFor(resume, now) ?: return notGranted()
        // OpenCode runs --auto (no enforceable approval channel) — the same fail-closed rule as
        // guests/bridges. TODO: lift when opencode gains an approval protocol.
        if (h.agent == AgentKind.OPENCODE) {
            return Verdict.Deny("handoff_agent_unsupported", "OpenCode sessions can't be handed off over a collaborator link yet")
        }
        val scope = listOfNotNull(PathScope.canonical(h.workdir)) +
            h.allowedRoots.mapNotNull { PathScope.canonical(it) }
        pendingGrantId = h.id
        return Verdict.Allow(
            f.copy(
                workdir = h.workdir,          // the daemon — not the recipient — names the workdir
                agent = h.agent,              // and the backend: exactly the source session's
                mode = TierClamp.clampMode(f.mode, grantTier()), // grant ceiling; never bypass
                takeOver = false,             // a recipient never seizes a session live elsewhere
                permissionMode = null,        // backend-native modes are owner-only (the guest rule)
                serviceTier = null,
            ),
            pathScope = scope,
            // the grant's operation ceiling, for the PermissionBridge's write-tool wall (§8.3).
            // UNKNOWN (a newer peer's value) stays UNKNOWN: the bridge treats anything that is not
            // explicitly CONTINUE_SCOPED as read-only — clamp to the safest, never widen.
            access = h.access,
        )
    }

    /** The IN_PROGRESS handoff on [sessionId] whose lease THIS device holds, or null. Reading through
     *  [HandoffRegistry.activeFor] settles expiry first, so an outrun lease can never be honored. */
    private fun liveGrantFor(sessionId: String, now: Long): SessionHandoff? {
        val (h, lease) = registry.activeFor(sessionId) ?: return null
        if (h.status != HandoffStatus.IN_PROGRESS) return null
        if (h.recipientDeviceId != deviceId) return null
        if (lease == null || lease.handoffId != h.id || lease.controllerDeviceId != deviceId || lease.leaseExpiresAt <= now) return null
        // §5.4 graceful recall in flight: the owner asked for the session back mid-turn and the daemon is
        // interrupting it. The grant is over as far as NEW work goes — prompts, verdicts, opens and reads
        // all deny from this instant, even though the lease is still held until the turn actually stops.
        if (lease.recallRequested) return null
        return h
    }

    /** A convo-keyed frame: the convo must be one this guard opened AND its grant must STILL be
     *  IN_PROGRESS with this device holding the lease — returned/recalled/completed all deny NOW. */
    private fun grantedConvo(convoId: String, frame: Frame): Verdict {
        val handoffId = convoGrants[convoId] ?: return notGranted()
        val h = registry.byId(handoffId) ?: return notGranted()
        val live = liveGrantFor(h.sourceSessionId, System.currentTimeMillis())
        return if (live?.id == handoffId) Verdict.Allow(frame) else Verdict.Deny(
            "handoff_grant_inactive",
            "this handoff is no longer in progress — control is back with the initiator",
        )
    }

    /** A (workdir, sessionId) read must name exactly the LIVE grant's session + workdir. */
    private fun grantedRead(workdir: String, sessionId: String, frame: Frame, now: Long): Verdict {
        val h = liveGrantFor(sessionId, now) ?: return notGranted()
        val wd = PathScope.canonical(workdir)
        val granted = PathScope.canonical(h.workdir)
        return if (wd != null && wd == granted) Verdict.Allow(frame) else Verdict.Deny(
            "handoff_grant_out_of_scope",
            "that folder isn't part of this handoff",
        )
    }

    private fun notGranted() = Verdict.Deny(
        "handoff_grant_required",
        "no accepted handoff grants you this session",
    )

    /** The grant's mode ceiling. v1 grants are REVIEW-tier regardless of access kind — CONTINUE_SCOPED's
     *  ACCEPT_EDITS ceiling arrives with the §8.4 milestone. */
    private fun grantTier(): AccessTier = AccessTier.REVIEW

    /** The router's onOpened callback: bind the convo the vetted open produced to its grant. */
    @Synchronized
    fun noteOpened(convoId: String) {
        pendingGrantId?.let { convoGrants[convoId] = it }
    }

    @Synchronized
    fun ownedConvoIds(): Set<String> = convoGrants.keys.toSet()

    /** The convos this guard opened under ONE grant — the §5.3 item 7 cut set (see
     *  [HandoffService.cutRecipientSinks]). Deliberately NOT [ownedConvoIds]: the same contact may hold
     *  a second, still-IN_PROGRESS grant on another session, and ending THIS handoff must not blind it
     *  there. The binding itself is kept (a returned recipient's CloseSession must still resolve). */
    @Synchronized
    fun convosGrantedBy(handoffId: String): Set<String> =
        convoGrants.entries.filter { it.value == handoffId }.mapTo(HashSet()) { it.key }

    private companion object {
        const val MAX_TRACKED = 64 // a collaborator drives at most one granted session at a time
    }
}
