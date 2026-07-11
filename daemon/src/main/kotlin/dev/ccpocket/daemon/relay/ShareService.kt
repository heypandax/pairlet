package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.bridge.PathScope
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.CreateShare
import dev.ccpocket.protocol.PairTicket
import dev.ccpocket.protocol.ShareCreated
import dev.ccpocket.protocol.ShareInfo
import dev.ccpocket.protocol.ShareInvite
import dev.ccpocket.protocol.ShareListing
import dev.ccpocket.protocol.ShareRevoked
import java.io.File

/**
 * The OWNER-side folder-share control plane (issue #115): mint a scoped invite, list active shares +
 * activity, revoke. Handled ONLY for a full-power owner device (a guest's capability whitelist denies
 * these frames, and [DeviceSessions] only reaches this for a non-restricted device), so re-sharing the
 * owner's machine — the core escalation the scope exists to prevent — is structurally impossible.
 *
 * Minting reuses #91's binding chain UNCHANGED: mint a ticket over the relay (headless=true, so the relay
 * treats a guest exactly like a bridge — presence-invisible, push-refused, replay-gated), then record a
 * GUEST intent binding the ticket to the folder scope + tier + expiry. The relay never learns the folder
 * (the invite travels out-of-band via QR/copy, like today's pairing QR) — zero relay schema change.
 */
interface ShareControl {
    suspend fun create(req: CreateShare): ShareCreated
    suspend fun list(): ShareListing
    suspend fun revoke(deviceId: String): ShareRevoked
}

class ShareService(
    private val accountId: String,
    private val daemonPubB64: String,
    private val relayWsBase: String,
    private val ownerLabel: () -> String?,
    private val registry: BridgeRegistry,
    private val mintTicket: suspend (headless: Boolean) -> PairTicket?,
    private val interactivePairingPending: () -> Boolean,
    private val revokeCredential: suspend (deviceId: String) -> Unit,
    /** live daemon conversations as (cwd, session) pairs — the owner's activity signal. */
    private val liveSessions: suspend () -> List<Pair<String, ActiveSession>>,
    private val now: () -> Long = System::currentTimeMillis,
) : ShareControl {
    private val log = logger("ShareService")

    override suspend fun create(req: CreateShare): ShareCreated {
        val root = PathScope.canonical(req.path)?.let { File(it) }
        if (root == null || !root.isAbsolute || !root.isDirectory) {
            return ShareCreated(ok = false, error = "not an existing folder: ${req.path}")
        }
        // mint serialization (issue #91): a share mint is a headless mint — refuse while a phone pairing
        // ticket could still be redeemed, so the LIFO PSK arming can't cross-bind them
        if (interactivePairingPending()) {
            return ShareCreated(ok = false, error = "a phone pairing is still valid — try again in ~2 minutes")
        }
        if (registry.intentPending()) {
            return ShareCreated(ok = false, error = "another pairing is in progress — try again shortly")
        }
        val lifetime = req.expiresInSec.coerceIn(MIN_SHARE_SEC, MAX_SHARE_SEC)
        val expiresAt = now() + lifetime * 1000
        val spec = BridgeSpec.guest(
            name = req.label?.trim()?.takeIf { it.isNotEmpty() } ?: "guest",
            root = root.path,
            tier = req.tier.takeUnless { it == AccessTier.UNKNOWN } ?: AccessTier.REVIEW, // unknown → safest
            expiresAt = expiresAt,
        )
        val ticket = mintTicket(true) ?: return ShareCreated(ok = false, error = "can't reach the relay — check the connection")
        // bindable window = the redeem ticket's TTL + grace (mirrors PairLoopback's headless path), so a
        // slow-to-redeem guest is still classified as a guest, never mis-promoted to a full device
        if (!registry.recordIntent(ticket.ticket, spec, ttlMs = ticket.expiresInSec * 1000L + INTENT_GRACE_MS)) {
            return ShareCreated(ok = false, error = "another pairing is in progress — try again shortly")
        }
        log.info("folder share minted for \"${root.name}\" (tier=${req.tier}, ${lifetime}s)")
        return ShareCreated(
            ok = true,
            invite = ShareInvite(
                relay = relayWsBase, accountId = accountId, daemonPub = daemonPubB64, ticket = ticket.ticket,
                folderName = root.name, tier = spec.tier, expiresAt = expiresAt, ttlSec = ticket.expiresInSec,
                ownerLabel = ownerLabel(),
            ),
        )
    }

    override suspend fun list(): ShareListing {
        val live = liveSessions()
        val t = now()
        val items = registry.guests().map { (deviceId, spec, createdAt) ->
            val root = spec.workdirs.firstOrNull().orEmpty()
            // a guest's live sessions carry a non-null origin (the share label) and sit under the root —
            // that distinguishes them from the OWNER's own sessions that happen to live under the same folder
            val active = live.filter { (cwd, s) -> s.origin != null && PathScope.contains(listOf(root), cwd) }
            ShareInfo(
                deviceId = deviceId,
                path = root,
                tier = spec.tier,
                createdAt = createdAt,
                expiresAt = spec.expiresAt ?: 0,
                guestLabel = spec.name,
                lastActiveAt = null, // v1: no persisted last-activity clock; online/activeSessions carry the pulse
                online = active.isNotEmpty(),
                activeSessions = active.size,
                expired = spec.expired(t),
            )
        }
        return ShareListing(items)
    }

    override suspend fun revoke(deviceId: String): ShareRevoked {
        if (!registry.isGuest(deviceId)) return ShareRevoked(deviceId, ok = false, error = "not an active share")
        revokeCredential(deviceId) // local prune (key dies) + relay RevokeDevice + force-close the socket
        log.info("folder share ${deviceId.take(8)}… revoked by owner")
        return ShareRevoked(deviceId, ok = true)
    }

    private companion object {
        const val MIN_SHARE_SEC = 5 * 60L          // 5 minutes floor
        const val MAX_SHARE_SEC = 90L * 24 * 3600  // 90 days ceiling
        const val INTENT_GRACE_MS = 120_000L
    }
}
