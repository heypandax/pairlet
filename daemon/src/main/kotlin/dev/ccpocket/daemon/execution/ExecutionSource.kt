package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.review.PeerChannel
import dev.ccpocket.daemon.review.PeerLink
import dev.ccpocket.daemon.review.PeerLinkSecret
import dev.ccpocket.daemon.review.PeerLinkStore
import dev.ccpocket.daemon.review.PeerSession
import dev.ccpocket.daemon.review.PeerTransport
import dev.ccpocket.protocol.ExecutionGrantInfo
import dev.ccpocket.protocol.ExecutionGrantQuery
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PocketError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * #367 G0 PROTOTYPE of the SOURCE side (the machine that would ask for work). Reuses the review link's
 * recipient machinery unchanged — [PeerTransport] (redeem + pinned Noise dial), [PeerLink]/[PeerLinkSecret]
 * and [PeerLinkStore] — but in its OWN files, so the review inbox never dials an execution link.
 *
 * [PeerLinkSecret.ticket] holds the DERIVED first-contact PSK ([ExecutionPsk]), not the raw relay ticket:
 * [dev.ccpocket.daemon.review.PeerHandshake.psk] is unchanged and mixes whatever that field holds. The raw
 * ticket is used exactly once, for the relay redeem.
 *
 * Deliberately NOT here: any fallback to an empty PSK while the first-contact PSK is held, any relay bearer
 * or key in a return value, and any owner credential.
 */
class ExecutionSource(
    private val transport: PeerTransport,
    private val links: PeerLinkStore,
    private val relayPolicy: ExecutionRelayPolicy = ExecutionRelayPolicy.DEFAULT,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Join {
        /**
         * [sourceFingerprint] is what this side displays for the TARGET owner to compare. It is the fingerprint
         * of THIS LINK's freshly generated key (本次链路指纹), not of this machine (不是机器身份) —
         * [fingerprintScope] says so explicitly for every surface that renders it.
         */
        data class Ok(
            val link: PeerLink,
            val sourceFingerprint: String,
            val relay: ExecutionRelayPolicy.Verdict,
            val fingerprintScope: String = ExecutionFingerprint.SOURCE_SCOPE,
        ) : Join
        data class Refused(val code: String) : Join
    }

    sealed interface Query {
        data class Ok(val info: ExecutionGrantInfo) : Query
        data class Failed(val code: String) : Query
    }

    /**
     * Join at the execution door only. Every check runs BEFORE redeeming, so a refused invite never burns
     * its ticket:
     *  - [expectedTargetFingerprint] (what the target owner's screen shows) must match the invite's key under
     *    the strong [ExecutionFingerprint];
     *  - the invite's relay must be known to [relayPolicy], or [confirmedRelayAuthority] must equal the
     *    `host[:port]` the user was shown ([ExecutionInvite.relayAuthority]).
     */
    suspend fun join(
        raw: String,
        expectedTargetFingerprint: String,
        confirmedRelayAuthority: String? = null,
        label: String? = null,
    ): Join {
        val inv = decodeExecutionInvite(raw) ?: return Join.Refused("invite_invalid")
        if (!ExecutionFingerprint.matches(expectedTargetFingerprint, inv.targetDaemonPub)) return Join.Refused("target_fingerprint_mismatch")
        val relayVerdict = relayPolicy.requireKnownRelay(inv, confirmedRelayAuthority)
        if (relayVerdict is ExecutionRelayPolicy.Verdict.Refused) return Join.Refused(relayVerdict.code)
        links.byId(inv.grantId)?.let { if (!it.removed) return Join.Refused("already_joined") }
        val psk = ExecutionPsk.derive(inv.connectTicket, inv.inviteSecret)
        val keys = transport.generateKeys()
        val cred = transport.redeem(inv.relay, inv.connectTicket, keys.publicKeyB64) ?: return Join.Refused("redeem_refused")
        if (cred.accountId != inv.targetAccountId || cred.deviceId.isBlank() || cred.credential.isBlank()) {
            return Join.Refused("redeem_mismatch")
        }
        val link = PeerLink(
            id = inv.grantId,
            label = label ?: inv.targetLabel ?: "execution target",
            relay = inv.relay,
            peerAccountId = inv.targetAccountId,
            peerDaemonPub = inv.targetDaemonPub,
            deviceId = cred.deviceId,
            fingerprint = ExecutionFingerprint.of(inv.targetDaemonPub),
            joinedAt = now(),
        )
        val secret = PeerLinkSecret(
            id = inv.grantId,
            credential = cred.credential,
            privateKeyB64 = keys.privateKeyB64,
            publicKeyB64 = keys.publicKeyB64,
            ticket = psk,
        )
        if (!links.put(link, secret)) return Join.Refused("persist_failed")
        return Join.Ok(link, ExecutionFingerprint.of(keys.publicKeyB64), relayVerdict)
    }

    /** This link's fingerprint (not a machine identity — see [ExecutionFingerprint.SOURCE_SCOPE]). */
    fun sourceFingerprint(grantId: String): String? = links.secretOf(grantId)?.let { ExecutionFingerprint.of(it.publicKeyB64) }

    /**
     * One connect: ask the target what this grant allows. Clears the first-contact PSK on the first
     * authenticated reply. While that PSK is still held and [STUCK_AFTER] attempts have produced nothing, the
     * failure is reported as [FIRST_CONTACT_STUCK] (user decision 09-14: the PSK is not persisted anywhere else,
     * so the remedy is revoke + re-approve, and a future UI should say so).
     */
    suspend fun query(grantId: String, timeoutMs: Long = 20_000L): Query {
        val link = links.byId(grantId)?.takeIf { !it.removed } ?: return Query.Failed("link_unknown")
        val secret = links.beginHandshake(grantId) ?: return Query.Failed("link_unknown")
        val reply = CompletableDeferred<Frame>()
        val session = object : PeerSession {
            override suspend fun onOpen(channel: PeerChannel) = channel.send(ExecutionGrantQuery(grantId))
            override suspend fun onFrame(channel: PeerChannel, frame: Frame) { reply.complete(frame) }
        }
        val frame = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val dial = async { runCatching { transport.dial(link, secret, session) } }
                select<Unit> {
                    reply.onAwait {}
                    dial.onAwait {}
                }
                dial.cancel()
                if (reply.isCompleted) reply.await() else null
            }
        }
        if (frame == null) {
            val stuck = secret.ticket != null && secret.handshakeAttempts >= STUCK_AFTER
            return Query.Failed(if (stuck) FIRST_CONTACT_STUCK else "no_reply")
        }
        // any frame that decrypted proves the target bound THIS key: the first-contact PSK has done its job
        links.clearTicket(grantId)
        return when (frame) {
            is ExecutionGrantInfo -> if (frame.grantId == grantId) Query.Ok(frame) else Query.Failed("grant_mismatch")
            is PocketError -> Query.Failed(frame.code)
            else -> Query.Failed("unexpected_reply")
        }
    }

    companion object {
        const val STUCK_AFTER = 3
        const val FIRST_CONTACT_STUCK = "first_contact_stuck_reapprove_required"
    }
}
