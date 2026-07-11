package dev.ccpocket.daemon.bridge

import dev.ccpocket.protocol.AccessTier

/**
 * A snapshot of a GUEST's folder-share scope (issue #115), taken from its [GuestGuard]+[BridgeSpec] at
 * frame-handling time and threaded into the request router so visibility + the tool path guard enforce the
 * SAME boundary the ingress guard vetted. Deliberately a plain immutable snapshot (not the live guard):
 * the router only READS it, and passing the guard would couple request handling to guest enforcement state.
 *
 *  - [roots] canonical shared roots — the router filters the project list to these, stamps the guest's
 *    shared rows, and (as [pathScope]) hands them to the conversation so its PermissionBridge denies any
 *    Read/Write/Edit whose target lands outside them.
 *  - [ownedSessions] the sessionIds this guest started — the router filters ListSessions to these so an
 *    owner's other sessions under the shared root never surface (visibility "by initiator").
 *  - [label]/[expiresAt]/[tier] decorate the guest's shared project rows ("shared by …", "6d left", badge).
 */
data class GuestScope(
    val roots: List<String>,
    val ownedSessions: Set<String>,
    val label: String,
    val expiresAt: Long?,
    val tier: AccessTier,
)
