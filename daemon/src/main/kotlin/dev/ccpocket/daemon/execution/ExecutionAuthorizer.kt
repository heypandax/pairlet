package dev.ccpocket.daemon.execution

import dev.ccpocket.protocol.AgentKind

/**
 * The per-request authorisation decision for an execution link (#367 G0). Pure over the target's store
 * and its clock HIGH-WATER ([ExecutionGrantStore.observeClock]): it never consults anything the source
 * sent except the ids it names, and it is re-run for EVERY request.
 *
 * Deny codes are stable and log-safe.
 */
object ExecutionAuthorizer {

    sealed interface Decision {
        data class Allow(val grant: ExecutionGrant, val workdir: String? = null) : Decision
        data class Deny(val code: String) : Decision
    }

    /**
     * Is the link credential [deviceId] (proven by static key [linkPubB64]) still attached to a live grant?
     * AWAITING_OWNER_CONFIRM counts as live for the LINK (so the source can learn it is waiting); it grants
     * no run authority — see [run].
     */
    fun link(store: ExecutionGrantStore, deviceId: String, linkPubB64: String, now: Long): Decision {
        store.unavailableReason?.let { return Decision.Deny(it) }
        val t = store.observeClock(now)
        val g = store.boundTo(deviceId) ?: return Decision.Deny("grant_unknown")
        if (g.sourceLinkPub != linkPubB64) return Decision.Deny("key_mismatch")
        lifecycleDeny(g, t)?.let { return Decision.Deny(it) }
        return when (g.state) {
            ExecutionGrantState.AWAITING_OWNER_CONFIRM, ExecutionGrantState.ACTIVE -> Decision.Allow(g)
            else -> Decision.Deny("grant_not_established")
        }
    }

    fun query(store: ExecutionGrantStore, deviceId: String, linkPubB64: String, grantId: String, now: Long): Decision {
        val d = link(store, deviceId, linkPubB64, now)
        if (d is Decision.Allow && d.grant.grantId != grantId) return Decision.Deny("grant_mismatch")
        return d
    }

    /**
     * The G1-facing check a run submission (and a queued run just before it starts) must pass. G0 has no
     * run frames; this is proven by fixture only.
     */
    fun run(
        store: ExecutionGrantStore,
        deviceId: String,
        linkPubB64: String,
        grantId: String,
        revision: Long,
        workspaceAlias: String,
        agent: AgentKind,
        now: Long,
        relativePath: String? = null,
    ): Decision {
        val d = query(store, deviceId, linkPubB64, grantId, now)
        if (d !is Decision.Allow) return d
        val g = d.grant
        if (g.state != ExecutionGrantState.ACTIVE) return Decision.Deny("grant_awaiting_owner_confirm")
        if (revision != g.revision) return Decision.Deny("revision_changed")
        if (agent !in g.allowedAgents) return Decision.Deny("agent_not_allowed")
        return when (val r = ExecutionWorkspaces.resolve(g, workspaceAlias, relativePath)) {
            is ExecutionWorkspaces.Resolution.Ok -> Decision.Allow(g, r.dir)
            is ExecutionWorkspaces.Resolution.Deny -> Decision.Deny(r.code)
        }
    }

    /** Revoked / expired (explicitly or by [effectiveNow]) — checked before any state-specific rule.
     *  Callers pass the store's clock high-water, never a raw wall clock. */
    fun lifecycleDeny(g: ExecutionGrant, effectiveNow: Long): String? = when {
        g.state == ExecutionGrantState.REVOKED -> "grant_revoked"
        g.state == ExecutionGrantState.EXPIRED || g.expiresAt <= effectiveNow -> "grant_expired"
        else -> null
    }
}
