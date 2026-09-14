package dev.ccpocket.daemon.execution

import dev.ccpocket.protocol.EXECUTION_FRAME_BUDGET_BYTES
import dev.ccpocket.protocol.ExecutionGrantQuery
import dev.ccpocket.protocol.ExecutionRunCancel
import dev.ccpocket.protocol.ExecutionRunResult
import dev.ccpocket.protocol.ExecutionRunStatus
import dev.ccpocket.protocol.ExecutionRunSubmit
import dev.ccpocket.protocol.Frame

/**
 * The TRANSPORT-boundary gate for an EXECUTION credential (#367 G1), the twin of
 * [dev.ccpocket.daemon.bridge.BridgeGuard] / [dev.ccpocket.daemon.handoff.CollaboratorGuard]: it runs in
 * [dev.ccpocket.daemon.relay.DeviceSessions] on the only path where the deviceId is proven by the Noise
 * static key, BEFORE anything reaches [ExecutionRunPlane].
 *
 * It is deliberately NOT the only authorisation: the run plane re-runs [ExecutionAuthorizer] per frame
 * (and again for a queued run just before it starts). This gate exists so that a plane that is missing,
 * still starting, or buggy can never be reached by a frame from a dead grant, and so the byte budget is
 * enforced before any payload is parsed into work.
 *
 * What it decides, in order — every refusal a stable, log-safe code, never a path/key/prompt:
 *  1. the credential still points at a grant, and that grant is the one the frame names;
 *  2. the grant is live (not revoked, not expired against the store's clock HIGH-WATER — never a raw
 *     wall clock, so rolling the clock back cannot revive it);
 *  3. the grant is ACTIVE for run frames (a grant still awaiting the owner's fingerprint confirmation may
 *     only be QUERIED), and [ExecutionRunSubmit.revision] matches the grant's current revision;
 *  4. the frame type is on [ExecutionCaps.ingressAllowed] — checked by the caller, re-checked here so the
 *     gate is complete on its own;
 *  5. the ENCODED frame is within [EXECUTION_FRAME_BUDGET_BYTES].
 *
 * @param store the target owner's grant store — the only authority.
 * @param grantIdOf the grantId the CREDENTIAL was bound to (read from the credential's own spec, never
 *   from anything the peer sent), so a frame naming another grant is refused before the store is consulted.
 * @param linkPubOf the source link's static key as this daemon holds it, for the pin check.
 */
class ExecutionGuard(
    private val store: ExecutionGrantStore,
    private val grantIdOf: (deviceId: String) -> String?,
    private val linkPubOf: (deviceId: String) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    /** Shared with [ExecutionTarget]: one ledger for every layer's refusal (see [ExecutionRefusals]). */
    private val refusals: ExecutionRefusals = ExecutionRefusals(),
) {

    sealed interface Verdict {
        data class Allow(val grant: ExecutionGrant) : Verdict
        data class Deny(val code: String) : Verdict
    }

    /**
     * @param firstContact true only for the frame that PROVED the first-contact PSK — i.e. the one that
     *   just bound the credential. Every later frame rode the ordinary empty-PSK handshake, which is what
     *   [ExecutionGrant.sourceLinkConfirmedAt] records: proof the link can come back on its own, and the
     *   difference between "never reconnected since the invite" and "a wrong key on a working link".
     */
    fun vet(deviceId: String, frame: Frame, encodedBytes: Int, firstContact: Boolean = false): Verdict =
        decision(deviceId, frame, encodedBytes).also {
            if (it is Verdict.Deny) refusals.note(deviceId, it.code)
            if (it is Verdict.Allow && !firstContact && it.grant.sourceLinkConfirmedAt == null) {
                // a failed write only loses a diagnostic hint, never authority — never block a frame on it
                store.widen(it.grant.grantId) { g -> if (g.sourceLinkConfirmedAt == null) g.copy(sourceLinkConfirmedAt = now()) else g }
            }
        }

    private fun decision(deviceId: String, frame: Frame, encodedBytes: Int): Verdict {
        if (encodedBytes > EXECUTION_FRAME_BUDGET_BYTES) return Verdict.Deny("frame_too_large")
        if (!ExecutionCaps.ingressAllowed(frame)) return Verdict.Deny("execution_forbidden")
        val boundGrant = grantIdOf(deviceId) ?: return Verdict.Deny("grant_unknown")
        val named = namedGrantId(frame) ?: return Verdict.Deny("execution_forbidden")
        if (named != boundGrant) return Verdict.Deny("grant_mismatch")
        val pin = linkPubOf(deviceId) ?: return Verdict.Deny("key_mismatch")
        val t = now()
        return when (frame) {
            is ExecutionGrantQuery -> decide(ExecutionAuthorizer.query(store, deviceId, pin, named, t))
            is ExecutionRunSubmit -> {
                // an agent name this build cannot resolve is a REFUSAL, not a decode failure — the grant
                // cannot allow a backend nobody here can name (see [ExecutionRunSubmit.agent])
                val agent = dev.ccpocket.protocol.executionAgentOrNull(frame.agent)
                    ?: return Verdict.Deny("agent_not_allowed")
                decide(ExecutionAuthorizer.run(store, deviceId, pin, named, frame.revision, frame.workspaceAlias, agent, t))
            }
            // status / result / cancel name an EXISTING run: they carry no scope of their own, so the gate
            // only proves the grant is ACTIVE. Ownership of the runId is the journal's business (B).
            else -> when (val d = ExecutionAuthorizer.query(store, deviceId, pin, named, t)) {
                is ExecutionAuthorizer.Decision.Allow ->
                    if (d.grant.state != ExecutionGrantState.ACTIVE) Verdict.Deny("grant_awaiting_owner_confirm")
                    else Verdict.Allow(d.grant)
                is ExecutionAuthorizer.Decision.Deny -> Verdict.Deny(d.code)
            }
        }
    }

    private fun decide(d: ExecutionAuthorizer.Decision): Verdict = when (d) {
        is ExecutionAuthorizer.Decision.Allow -> Verdict.Allow(d.grant)
        is ExecutionAuthorizer.Decision.Deny -> Verdict.Deny(d.code)
    }

    /** The grantId a frame names. Null for a frame type that names none — which [ExecutionCaps] already
     *  denies, so reaching this is a whitelist/gate drift and must fail closed. */
    private fun namedGrantId(frame: Frame): String? = when (frame) {
        is ExecutionGrantQuery -> frame.grantId
        is ExecutionRunSubmit -> frame.grantId
        is ExecutionRunStatus -> frame.grantId
        is ExecutionRunResult -> frame.grantId
        is ExecutionRunCancel -> frame.grantId
        else -> null
    }
}
