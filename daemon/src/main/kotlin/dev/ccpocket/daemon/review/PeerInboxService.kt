package dev.ccpocket.daemon.review

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AcknowledgeReviewRequest
import dev.ccpocket.protocol.DeclineReviewRequest
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.RespondReviewRequest
import dev.ccpocket.protocol.ReviewResult
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.StartReviewRequest
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.collaboratorFingerprint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The RECIPIENT-side ReviewRequest plane (REVIEW-REQUEST.md §4.3/§9): joins inbound collaborator links,
 * keeps one supervised [PeerInboxClient] per active link, and turns local CLI/Skill actions into
 * persisted outbox items.
 *
 * One supervisor job per link, launched on the daemon scope: one unreachable or misbehaving peer must
 * never take the others — or the daemon — down with it.
 *
 * This service is the ONLY writer of the recipient's state and it deliberately holds no authority over
 * status: it queues intent, the peer's daemon decides, and [PeerInboxClient] writes back what the peer
 * decided. That is what keeps "B's mirror is non-authoritative" true in code rather than in a comment.
 */
class PeerInboxService(
    private val scope: CoroutineScope,
    val links: PeerLinkStore = PeerLinkStore.load(),
    val store: PeerInboxStore = PeerInboxStore.load(),
    private val transport: PeerTransport = RelayPeerTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = ::randomToken,
    /** Passed to each [PeerInboxClient]: how often an OPEN connection re-sends its unconfirmed outbox.
     *  Injected only so a test can prove the retry without waiting out the production interval. */
    private val resendIntervalMs: Long = PeerInboxClient.RESEND_INTERVAL_MS,
) {
    private val log = logger("PeerInboxService")

    private class Running(val client: PeerInboxClient, val job: Job)

    private val running = ConcurrentHashMap<String, Running>()

    /** The answer to every local action. [queued] false = nothing was sent because nothing had to be. */
    sealed interface ActionResult {
        data class Ok(val requestId: String, val queued: Boolean) : ActionResult
        data class Refused(val code: String, val message: String) : ActionResult
    }

    /** Bring up every active link's inbox. Idempotent — safe to call at boot and after every join. */
    fun start() {
        // Retry cleanup that may have failed after a prior credential deletion. The link remains
        // security-terminal either way; this only releases undeliverable outbox capacity.
        for (link in links.all().filter { it.removed }) store.onLinkRemoved(link.id)
        for (link in links.active()) ensureRunning(link)
    }

    private fun ensureRunning(link: PeerLink) {
        if (running.containsKey(link.id)) return
        val client = PeerInboxClient(link, links, store, transport, scope, clock, resendIntervalMs = resendIntervalMs)
        // one supervisor per link: a throw inside a peer's loop must not cancel its siblings. The scope
        // is DaemonCore's SupervisorJob scope, so a failed child is already isolated; the explicit
        // runCatching makes that true for the launch body itself as well.
        val job = scope.launch { runCatching { client.run() } }
        running[link.id] = Running(client, job)
        log.info("peer inbox started for \"${link.label}\" (${link.peerAccountId.take(8)}…)")
    }

    // ---- joining / severing -----------------------------------------------

    sealed interface JoinResult {
        data class Ok(val link: PeerLink) : JoinResult
        data class Refused(val code: String, val message: String) : JoinResult
    }

    /**
     * Redeem a one-time collaborator invite and start receiving review requests from that peer.
     *
     * The order matters: generate our own per-link keypair, redeem, and PERSIST BOTH before the first
     * connect. A ticket is single use — losing the resulting credential to a crash between redeem and
     * persist would silently burn the invite and leave the user re-scanning for no reason.
     */
    suspend fun join(rawInvite: String, label: String?): JoinResult {
        ReviewLimits.singleLine(label, ReviewLimits.MAX_LABEL, "label")?.let {
            return JoinResult.Refused("invite_invalid", it)
        }
        // the REVIEW door only (REVIEW-REQUEST.md §13.3): a Session Handoff ticket pasted here would be
        // redeemed into a daemon-held credential its owner minted for a person's App, and burned in the
        // process. The URI host and the embedded purpose both have to say "review".
        val invite = decodeReviewContactInvite(rawInvite)
            ?: return JoinResult.Refused(
                "invite_invalid",
                "that isn't a usable cc-pocket review invite — it should start with $REVIEW_CONTACT_URI_PREFIX " +
                    "(a plain collaborator invite is for the phone app's Session Handoff, not for this)",
            )
        if (links.active().any { it.peerAccountId == invite.accountId }) {
            return JoinResult.Refused(
                "invite_duplicate",
                "you already have a link to that peer — remove it first if you want to re-join",
            )
        }
        val keys = transport.generateKeys()
        val cred = transport.redeem(invite.relay, invite.ticket, keys.publicKeyB64)
            ?: return JoinResult.Refused("invite_refused", "the relay refused that invite — it may have expired or been used already")
        if (cred.accountId != invite.accountId) {
            // the relay answered for a different account than the invite claimed: refuse rather than
            // pin ourselves to a peer identity nobody verified
            return JoinResult.Refused("invite_mismatch", "the relay answered for a different account than the invite named")
        }
        if (cred.deviceId.isBlank() || cred.deviceId.length > ReviewLimits.MAX_ID ||
            cred.credential.isBlank() || cred.credential.length > MAX_CREDENTIAL_LENGTH
        ) {
            return JoinResult.Refused("invite_refused", "the relay returned an unusable credential")
        }
        val id = "pl_" + newId()
        val link = PeerLink(
            id = id,
            label = label?.trim()?.takeIf { it.isNotEmpty() } ?: invite.ownerLabel?.takeIf { it.isNotBlank() } ?: "peer",
            relay = invite.relay,
            peerAccountId = invite.accountId,
            peerDaemonPub = invite.daemonPub,
            deviceId = cred.deviceId,
            fingerprint = collaboratorFingerprint(invite.daemonPub),
            joinedAt = clock(),
        )
        val persisted = links.put(
            link,
            PeerLinkSecret(
                id = id,
                credential = cred.credential,
                privateKeyB64 = keys.privateKeyB64,
                publicKeyB64 = keys.publicKeyB64,
                // kept only until the first authenticated exchange proves the credential (§11.4)
                ticket = invite.ticket,
            ),
        )
        if (!persisted) {
            return JoinResult.Refused(
                "peer_link_persist_failed",
                "the invite was redeemed but its credentials could not be saved — check daemon logs and disk access",
            )
        }
        ensureRunning(link)
        log.info("joined peer link \"${link.label}\" (${invite.accountId.take(8)}…, fp ${link.fingerprint})")
        return JoinResult.Ok(link)
    }

    sealed interface RemoveResult {
        data class Ok(val link: PeerLink) : RemoveResult
        data object NotFound : RemoveResult
        data object PersistFailed : RemoveResult
    }

    /** Sever an inbound link only after the credential deletion is durable. Mirrored history stays. */
    fun remove(linkId: String): RemoveResult {
        val gone = when (val result = links.remove(linkId)) {
            PeerLinkStore.RemoveResult.NotFound -> return RemoveResult.NotFound
            PeerLinkStore.RemoveResult.PersistFailed -> return RemoveResult.PersistFailed
            is PeerLinkStore.RemoveResult.Ok -> result.link
        }
        running.remove(linkId)?.job?.cancel()
        if (!store.onLinkRemoved(linkId)) {
            // The credential is already gone, so retrying these items is impossible. Keep them on disk
            // rather than reporting they were cleared; a later maintenance pass may remove them.
            log.warn("peer link \"${gone.label}\" was removed, but its outbox cleanup was not persisted")
        }
        log.info("peer link \"${gone.label}\" removed — inbox stopped, history kept")
        return RemoveResult.Ok(gone)
    }

    // ---- reads -------------------------------------------------------------

    fun list(): List<PeerLink> = links.all()

    /** The inbox, newest first, optionally filtered by status. */
    fun inbox(status: ReviewStatus? = null): List<MirrorRow> =
        store.rows()
            .filter { status == null || it.request.status == status }
            .sortedByDescending { it.request.createdAt }

    /** Resolve one request id. More than one hit means two peers minted the same id — the caller must
     *  disambiguate rather than guess which colleague they are answering. */
    fun resolve(requestId: String): List<MirrorRow> = store.byRequestId(requestId)

    /**
     * Local action names still waiting to reach the peer, for display.
     *
     * The delivery ACK rides the same outbox (it is durable intent like any other) but is NOT one of
     * these: "queued: delivered" would appear under every freshly received request, describing plumbing
     * the reader never asked for and cannot act on. This list means "something YOU chose has not reached
     * them yet".
     */
    fun pendingActions(row: MirrorRow): List<String> =
        store.pendingFor(row.linkId, row.request.id)
            .filterNot { it.expect == ReviewStatus.DELIVERED }
            .map { it.expect.name.lowercase() }

    // ---- local actions -----------------------------------------------------

    fun acknowledge(requestId: String): ActionResult =
        queue(requestId, ReviewStatus.ACKNOWLEDGED) { id, key -> AcknowledgeReviewRequest(id, key) }

    fun start(requestId: String): ActionResult =
        queue(requestId, ReviewStatus.IN_PROGRESS) { id, key -> StartReviewRequest(id, key) }

    fun decline(requestId: String, reason: String?): ActionResult {
        ReviewLimits.text(reason, ReviewLimits.MAX_TEXT, "reason")?.let { return ActionResult.Refused("review_invalid", it) }
        return queue(requestId, ReviewStatus.DECLINED) { id, key -> DeclineReviewRequest(id, reason, key) }
    }

    fun respond(requestId: String, result: ReviewResult): ActionResult {
        ReviewLimits.result(result)?.let { return ActionResult.Refused("review_invalid", it) }
        return queue(requestId, ReviewStatus.RESPONDED) { id, key -> RespondReviewRequest(id, result, key) }
    }

    /**
     * The one path every local action takes: resolve the row, check the link is still alive, sanity-check
     * the transition against the LAST KNOWN status (fast local feedback — the peer still decides), persist
     * the intent, then nudge the connection.
     *
     * The local check is deliberately advisory. It catches "you already declined this" instantly and
     * offline; it never claims authority, because the mirror may be stale and the sender's row is truth.
     */
    private fun queue(requestId: String, expect: ReviewStatus, build: (String, String) -> ToDaemon): ActionResult {
        val hits = store.byRequestId(requestId)
        val row = when {
            hits.isEmpty() -> return ActionResult.Refused("review_not_found", "no review request with that id is in your inbox")
            hits.size > 1 -> return ActionResult.Refused(
                "review_ambiguous",
                "two peers sent a request with that id — remove one of the links or act from a UI that can pick",
            )
            else -> hits.single()
        }
        val link = links.byId(row.linkId)
            ?: return ActionResult.Refused("review_link_removed", "the link that sent this request is gone")
        if (link.removed) {
            return ActionResult.Refused("review_link_removed", "that link was removed — you can no longer answer its requests")
        }
        val current = row.request.status
        if (current == expect) return ActionResult.Ok(requestId, queued = false)
        if (!ReviewRegistry.canTransition(current, expect)) {
            return ActionResult.Refused(
                "review_bad_transition",
                "this request is ${current.name.lowercase()} — it cannot become ${expect.name.lowercase()}",
            )
        }
        val pending = store.pendingFor(row.linkId, requestId)
        if (pending.any { it.expect == expect }) return ActionResult.Ok(requestId, queued = true)
        if (pending.any { it.expect == ReviewStatus.DECLINED || it.expect == ReviewStatus.RESPONDED }) {
            return ActionResult.Refused(
                "review_action_pending",
                "a final review action is already queued for this request — wait for the sender to confirm it",
            )
        }
        if (pending.any { actionRank(it.expect) > actionRank(expect) }) {
            return ActionResult.Refused(
                "review_action_pending",
                "a later review action is already queued for this request",
            )
        }
        val key = newId()
        val frame = build(requestId, key)
        val item = OutboxItem(
            id = newId(),
            linkId = row.linkId,
            requestId = requestId,
            frameJson = PocketJson.encodeToString(ToDaemon.serializer(), frame),
            expect = expect,
            idempotencyKey = key,
            queuedAt = clock(),
        )
        when (store.enqueue(item)) {
            PeerInboxStore.EnqueueResult.FULL -> {
                // fail closed: forgetting an older, still-undelivered review to make room is never right
                return ActionResult.Refused(
                    "review_outbox_full",
                    "too many unsent review actions (${PeerInboxStore.MAX_OUTBOX}) — reconnect to the peer first",
                )
            }
            PeerInboxStore.EnqueueResult.PERSIST_FAILED -> return ActionResult.Refused(
                "review_persist_failed",
                "the review action could not be saved — nothing was sent; check daemon logs and disk access",
            )
            PeerInboxStore.EnqueueResult.STORED -> Unit
        }
        running[row.linkId]?.client?.kick()
        return ActionResult.Ok(requestId, queued = true)
    }

    companion object {
        private val RNG = SecureRandom()
        private val B64 = Base64.getUrlEncoder().withoutPadding()

        /** 128 random bits, base64url — used for link ids, outbox item ids and idempotency keys. */
        fun randomToken(): String = B64.encodeToString(ByteArray(16).also(RNG::nextBytes))

        private const val MAX_CREDENTIAL_LENGTH = 8 * 1024

        private fun actionRank(status: ReviewStatus): Int = when (status) {
            ReviewStatus.ACKNOWLEDGED -> 1
            ReviewStatus.IN_PROGRESS -> 2
            ReviewStatus.RESPONDED, ReviewStatus.DECLINED -> 3
            else -> 0
        }

        fun inMemory(scope: CoroutineScope): PeerInboxService = PeerInboxService(
            scope = scope,
            links = PeerLinkStore.inMemory(),
            store = PeerInboxStore.inMemory(),
        )
    }
}
