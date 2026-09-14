package dev.ccpocket.daemon.execution

import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ToDaemon

/**
 * Issue #367 G1 — the seam between the transport (DeviceSessions / RequestRouter, which only know that a
 * frame arrived on an authenticated EXECUTION credential) and the run service that owns grants, journal
 * and sessions. Transport code depends on this interface only; [DaemonCore] injects the implementation.
 *
 * Contract: [handle] is called with the frame ALREADY admitted by [ExecutionCaps.ingressAllowed] and the
 * deviceId ALREADY proven by the Noise static key. It must re-authorize on every call (grant state,
 * revision, budget, byte limits) and reply through [reply] only with frames [ExecutionCaps.egressAllowed].
 *
 * [linkPubB64] is that PROVEN static key, passed in rather than looked up. Reading the pin out of the grant
 * store instead and handing it to [ExecutionAuthorizer] compares the stored value with itself — a tautology
 * that would pass for any caller the transport let through. The transport is the only layer that knows what
 * key actually decrypted the frame, so it is the only layer that can supply this.
 *
 * [handle] runs INLINE on the relay receive loop. It must return promptly (accept + ACK); real work belongs
 * on a background scope. [reply] may be retained and called later — it resolves the live session at seal
 * time — and re-applies [ExecutionCaps.egressAllowed], dropping anything outside it.
 */
interface ExecutionRunPlane {
    suspend fun handle(deviceId: String, linkPubB64: String, frame: ToDaemon, reply: suspend (Frame) -> Unit)
    /** Recovery/maintenance tick: journal recovery, timeouts, retention, pending revokes. */
    suspend fun maintain()
}
