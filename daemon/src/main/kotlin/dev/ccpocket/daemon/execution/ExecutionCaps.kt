package dev.ccpocket.daemon.execution

import dev.ccpocket.protocol.ExecutionGrantInfo
import dev.ccpocket.protocol.ExecutionGrantQuery
import dev.ccpocket.protocol.ExecutionRunAccepted
import dev.ccpocket.protocol.ExecutionRunCancel
import dev.ccpocket.protocol.ExecutionRunOutput
import dev.ccpocket.protocol.ExecutionRunResult
import dev.ccpocket.protocol.ExecutionRunState
import dev.ccpocket.protocol.ExecutionRunStatus
import dev.ccpocket.protocol.ExecutionRunSubmit
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PocketError

/**
 * The capability firewall for an EXECUTION link credential (#367). Same shape as
 * [dev.ccpocket.daemon.handoff.CollaboratorCaps] / [dev.ccpocket.daemon.bridge.GuestCaps]: both directions
 * are WHITELISTS and everything unlisted is denied, so a protocol addition reaches an execution caller only
 * after someone consciously admits it here.
 *
 * The baseline is ZERO of the existing surface: no session open/prompt/verdict, no discovery
 * (directories/sessions/files), no review plane, no handoff plane, no owner management, no shell. G0 admits
 * exactly one request — the grant-state query — so the link has something to prove itself with. G1 adds
 * the typed run frames; they are classified here AND authorised per frame by [ExecutionGuard] at the
 * transport boundary AND re-authorised by [ExecutionAuthorizer] inside the run plane.
 *
 * Isolation is two-sided and does not rest on this object alone: the Collaborator/Bridge/Guest
 * whitelists deny the execution frames by their own `else -> false`, which `ExecutionCapsTest` pins.
 */
object ExecutionCaps {

    fun ingressAllowed(frame: Frame): Boolean = when (frame) {
        is ExecutionGrantQuery -> true
        // G1 run plane (source -> target). Nothing here reaches the router's owner surface: the transport
        // hands these to [ExecutionRunPlane] and to nothing else.
        is ExecutionRunSubmit -> true
        is ExecutionRunStatus -> true
        is ExecutionRunResult -> true
        is ExecutionRunCancel -> true
        else -> false
    }

    fun egressAllowed(frame: Frame): Boolean = when (frame) {
        is ExecutionGrantInfo -> true
        // G1 run plane (target -> source). Deliberately NOT the session/transcript frames: a remote caller
        // reads results out of the run journal through these three, never off a live conversation fan-out.
        is ExecutionRunAccepted -> true
        is ExecutionRunState -> true
        is ExecutionRunOutput -> true
        is PocketError -> true
        else -> false
    }
}
