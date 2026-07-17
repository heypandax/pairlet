package dev.ccpocket.daemon.bridge

import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.PermissionMode

/**
 * The permission-mode clamp shared by BOTH restricted credential kinds — a GUEST's folder share
 * (issue #115) and a headless BRIDGE (issue #91). They are the same thing structurally: a scoped
 * credential acting on the owner's machine, whose autonomy the owner granted at issue time.
 *
 * The invariant, and the reason approvals stay worth anything:
 *  - a scoped credential may always be MORE cautious than its tier, never less;
 *  - [PermissionMode.BYPASS_PERMISSIONS] is unreachable for EVERY tier — neither a guest nor an IM bot
 *    can put the daemon into "approve nothing", so shell / dangerous actions always surface to a human.
 *
 * ONE implementation, deliberately: a guest ceiling and a bridge ceiling that drift apart would silently
 * hand one of them more autonomy than was granted, and that failure is invisible until something
 * destructive runs unprompted.
 */
object TierClamp {

    /** Clamp [requested] to the ceiling [tier] grants. Unknown/newer tiers clamp to the safest behaviour. */
    fun clampMode(requested: PermissionMode, tier: AccessTier): PermissionMode {
        val ceiling = AccessTier.ceiling(tier) // DEFAULT for Review/unknown, ACCEPT_EDITS for Collaborate/Autonomous
        return if (autonomy(requested) > autonomy(ceiling)) ceiling else requested
    }

    /** The autonomy rank used only for the clamp — higher = the agent acts with less human gating.
     *  PLAN (research/plan only) is the most cautious; BYPASS the least (and unreachable when scoped). */
    private fun autonomy(mode: PermissionMode): Int = when (mode) {
        PermissionMode.PLAN -> 0
        PermissionMode.DEFAULT -> 1
        PermissionMode.ACCEPT_EDITS -> 2
        PermissionMode.BYPASS_PERMISSIONS -> 3
    }
}
