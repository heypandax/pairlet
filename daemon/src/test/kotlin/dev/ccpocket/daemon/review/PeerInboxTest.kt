package dev.ccpocket.daemon.review

import dev.ccpocket.protocol.AcknowledgeReviewRequest
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.DeclineReviewRequest
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListReviewRequests
import dev.ccpocket.protocol.MarkReviewDelivered
import dev.ccpocket.protocol.PairCredential
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.RespondReviewRequest
import dev.ccpocket.protocol.ReviewListing
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewResult
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.ReviewUpdated
import dev.ccpocket.protocol.ReviewVerdict
import dev.ccpocket.protocol.ToDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The RECIPIENT half of the M1 loop (REVIEW-REQUEST.md §9), against an in-process fake transport — no
 * relay, no sockets, no crypto. What is under test is the part that has to be right when the network
 * is not: persist-before-ACK, replay after an outage, a durable outbox, and restart recovery.
 */
class PeerInboxTest {

    // ---- fake transport ---------------------------------------------------

    private class FakeTransport(private val accountId: String = "acctA") : PeerTransport {
        val sent = CopyOnWriteArrayList<ToDaemon>()
        /** What the mirror held at the instant each frame was sent — the persist-before-ACK probe. */
        val mirrorAtSend = CopyOnWriteArrayList<Pair<ToDaemon, ReviewStatus?>>()
        val connects = AtomicInteger(0)
        /** How many times a ticket was actually presented to the relay — a redeem BURNS it, so a
         *  wrong-door invite has to be refused before this ever increments. */
        val redeems = AtomicInteger(0)

        @Volatile var online = true

        /** Eat the next delivery ACK without closing the socket (a relay hiccup, not a disconnect). */
        @Volatile var dropNextDeliveryAck = false
        @Volatile var store: PeerInboxStore? = null
        @Volatile var linkId: String = ""

        @Volatile private var session: PeerSession? = null
        @Volatile private var channel: PeerChannel? = null
        private var hangup = Channel<Unit>(Channel.CONFLATED)

        override fun generateKeys() = PeerKeys("cHJpdg", "cHVi")

        override suspend fun redeem(relay: String, ticket: String, devicePubB64: String): PairCredential? {
            redeems.incrementAndGet()
            return if (online) PairCredential(deviceId = "devB", credential = "bearer-secret", accountId = accountId) else null
        }

        override suspend fun dial(link: PeerLink, secret: PeerLinkSecret, session: PeerSession) {
            if (!online) throw IllegalStateException("peer unreachable")
            connects.incrementAndGet()
            hangup = Channel(Channel.CONFLATED)
            val ch = PeerChannel { frame ->
                // a frame the relay swallows: accepted by the socket, never seen by the peer. The
                // connection stays perfectly healthy, which is what makes this failure invisible.
                if (frame is MarkReviewDelivered && dropNextDeliveryAck) {
                    dropNextDeliveryAck = false
                } else {
                    sent += frame
                    mirrorAtSend += frame to store?.row(linkId, requestIdOf(frame))?.request?.status
                }
            }
            this.channel = ch
            this.session = session
            session.onOpen(ch)
            hangup.receive() // block like a live socket until the test hangs up
            this.session = null
            this.channel = null
        }

        /** Push an inbound frame as the peer daemon would. */
        suspend fun deliver(frame: Frame) {
            val s = session ?: error("not connected")
            s.onFrame(channel!!, frame)
        }

        fun hangUp() { hangup.trySend(Unit) }

        fun connected() = session != null

        private fun requestIdOf(f: ToDaemon): String = when (f) {
            is MarkReviewDelivered -> f.requestId
            is AcknowledgeReviewRequest -> f.requestId
            is DeclineReviewRequest -> f.requestId
            is RespondReviewRequest -> f.requestId
            else -> ""
        }
    }

    // ---- fixture ----------------------------------------------------------

    private class Fixture(
        val scope: CoroutineScope,
        val dir: java.io.File,
        inboxPathOverride: java.io.File? = null,
        /** How fast an open connection re-sends its outbox — production's 30s, shrunk for the test. */
        resendIntervalMs: Long = PeerInboxClient.RESEND_INTERVAL_MS,
    ) {
        val transport = FakeTransport()
        val inboxPath: java.io.File = inboxPathOverride ?: dir.resolve("review-inbox.json")
        val links = PeerLinkStore.load(dir.resolve("peer-links.json"), dir.resolve("peer-secrets.json"))
        val store = PeerInboxStore.load(inboxPath).also { transport.store = it }
        var now = 5_000L
        var seq = 0
        val service = PeerInboxService(
            scope, links, store, transport, clock = { now }, newId = { "id${seq++}" },
            resendIntervalMs = resendIntervalMs,
        )
    }

    /** A REVIEW invite: `review join` accepts nothing else, and [CollaboratorInvite.encodeUri] publishes
     *  it under the review door (REVIEW-REQUEST.md §13.3). */
    private val invite = CollaboratorInvite(
        relay = "wss://relay.example", accountId = "acctA", daemonPub = TestKeys.DAEMON_PUB,
        ticket = "one-time-ticket", ownerLabel = "Panda · MacBook",
        purpose = CollaboratorPurpose.REVIEW,
    )

    private fun request(id: String, status: ReviewStatus, revision: Long = 1) = ReviewRequest(
        id = id, senderDeviceId = "devA", recipientDeviceId = "devB",
        title = "the ACK path", brief = dev.ccpocket.protocol.ReviewBrief(request = "check the retry race"),
        artifacts = listOf(dev.ccpocket.protocol.ArtifactRef(dev.ccpocket.protocol.ArtifactKind.MERGE_REQUEST, url = "https://git.example/mr/1")),
        status = status, revision = revision, createdAt = 1, updatedAt = 1,
        result = if (status == ReviewStatus.RESPONDED || status == ReviewStatus.CLOSED) {
            ReviewResult(ReviewVerdict.APPROVE, "done", respondedByDeviceId = "devB", respondedAt = 1)
        } else null,
    )

    private fun <T> withFixture(block: suspend (Fixture) -> T): T = runBlocking {
        val dir = Files.createTempDirectory("ccp-peer-inbox").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val f = Fixture(scope, dir)
            block(f)
        } finally {
            scope.cancel()
        }
    }

    /** Poll until [check] holds, or fail naming what we were waiting for. */
    private suspend fun await(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            delay(10)
        }
    }

    private suspend fun joinAndConnect(f: Fixture): PeerLink {
        val res = f.service.join(encode(invite), "Panda")
        assertTrue(res is PeerInboxService.JoinResult.Ok, "join must succeed, got $res")
        f.transport.linkId = res.link.id
        await("the inbox to connect") { f.transport.connected() }
        return res.link
    }

    private fun encode(i: CollaboratorInvite): String = i.encodeUri()

    // ---- join -------------------------------------------------------------

    @Test
    fun join_redeems_persists_and_starts_an_inbox_that_lists_first() = withFixture { f ->
        val link = joinAndConnect(f)
        assertEquals("Panda", link.label)
        assertEquals("devB", link.deviceId)
        assertEquals(invite.daemonPub, link.peerDaemonPub, "the peer's key must be pinned at join")
        assertTrue(link.fingerprint.isNotBlank())
        // an explicit list on connect is what makes an offline recipient converge (§9 item 5)
        await("the initial list") { f.transport.sent.any { it is ListReviewRequests } }
    }

    @Test
    fun join_refuses_a_bad_invite_a_duplicate_peer_and_an_account_mismatch() = withFixture { f ->
        val bad = f.service.join("not-an-invite", null)
        assertTrue(bad is PeerInboxService.JoinResult.Refused && bad.code == "invite_invalid", "$bad")

        joinAndConnect(f)
        val dup = f.service.join(encode(invite), null)
        assertTrue(dup is PeerInboxService.JoinResult.Refused && dup.code == "invite_duplicate", "$dup")

        val mismatch = f.service.join(encode(invite.copy(accountId = "someone-else")), null)
        assertTrue(mismatch is PeerInboxService.JoinResult.Refused && mismatch.code == "invite_mismatch", "$mismatch")
    }

    /**
     * The pinned key is validated as a REAL P-256 point before anything is redeemed or stored. Length is
     * not the test: the suite's public key is 65 uncompressed bytes, so a 32-byte blob is a different
     * object entirely, and 65 well-shaped bytes off the curve is a key no handshake can complete —
     * either one would buy a stored link that reconnects and fails until a human removes it.
     */
    @Test
    fun invite_decode_rejects_plaintext_remote_relays_and_unusable_pinned_keys_before_redeem() {
        assertNull(decodeReviewContactInvite(encode(invite.copy(relay = "ws://evil.example"))))
        assertNull(decodeReviewContactInvite(encode(invite.copy(daemonPub = "not-a-key"))))
        // exactly 32 bytes — the size an X25519-shaped assumption would have accepted
        assertNull(decodeReviewContactInvite(encode(invite.copy(daemonPub = b64(ByteArray(32) { 7 })))))
        // 65 bytes, right length, wrong prefix (0x03 = compressed form's marker)
        val real = b64d(TestKeys.DAEMON_PUB)
        assertNull(
            decodeReviewContactInvite(encode(invite.copy(daemonPub = b64(real.copyOf().also { it[0] = 3 })))),
        )
        // 65 bytes, right prefix, coordinates that are not a point on the curve
        assertNull(
            decodeReviewContactInvite(encode(invite.copy(daemonPub = b64(real.copyOf().also { it[40] = (it[40] + 1).toByte() })))),
        )
        assertNotNull(decodeReviewContactInvite(encode(invite)), "a real key still decodes")
        assertNotNull(decodeReviewContactInvite(encode(invite.copy(relay = "ws://127.0.0.1:8787/"))))
    }

    /**
     * THE TWO DOORS (REVIEW-REQUEST.md §13.3). A Session Handoff invite and a Review invite differ by one
     * trailing field, and the ticket inside either is single use — so redeeming one at the other's door
     * does not merely land on the wrong screen, it BURNS the ticket the other side is waiting for.
     *
     * Two independent gates have to hold, because they protect against different peers: the URI HOST
     * (which is all an older app, blind to the trailing `purpose`, can act on) and the embedded PURPOSE
     * (which is all that is left when a human strips the prefix and pastes the bare blob).
     */
    @Test
    fun the_two_invite_doors_never_accept_each_others_tickets() {
        val handoff = invite.copy(purpose = CollaboratorPurpose.SESSION_HANDOFF)

        // the host each purpose is published under — frozen for handoff, distinct for review
        assertTrue(encode(handoff).startsWith(COLLAB_URI_PREFIX), encode(handoff))
        assertTrue(encode(invite).startsWith(REVIEW_CONTACT_URI_PREFIX), encode(invite))

        // …and neither decoder answers for the other's URI
        assertNull(decodeReviewContactInvite(encode(handoff)), "a handoff ticket is not a review peer")
        assertNull(decodeCollaboratorInvite(encode(invite)), "a review ticket is not a phone contact")
        assertNotNull(decodeCollaboratorInvite(encode(handoff)))
        assertNotNull(decodeReviewContactInvite(encode(invite)))

        // the prefix stripped by hand: only the embedded purpose is left, and it still decides
        val bareReview = encode(invite).removePrefix(REVIEW_CONTACT_URI_PREFIX)
        val bareHandoff = encode(handoff).removePrefix(COLLAB_URI_PREFIX)
        assertNotNull(decodeReviewContactInvite(bareReview))
        assertNull(decodeCollaboratorInvite(bareReview), "a bare review blob must not cross to the collab door")
        assertNull(decodeReviewContactInvite(bareHandoff), "…nor the other way round")

        // a purpose only a NEWER peer knows fails closed at BOTH doors rather than defaulting into one
        val future = REVIEW_CONTACT_URI_PREFIX + b64(
            """{"relay":"wss://relay.example","accountId":"acctA","daemonPub":"${TestKeys.DAEMON_PUB}","ticket":"t","purpose":"quantum_handoff"}"""
                .encodeToByteArray(),
        )
        assertNull(decodeReviewContactInvite(future))
        assertNull(decodeCollaboratorInvite(future.replace(REVIEW_CONTACT_URI_PREFIX, COLLAB_URI_PREFIX)))
    }

    /**
     * A v1.6.0-MINTED invite — the exact bytes in the field today: no `purpose` key, no `ttlSec`.
     *
     * Hand-written rather than produced by [encodeUri], because that is the whole point: `encodeUri` now
     * emits `"purpose":"session_handoff"`, so a fixture built through it would only prove this codec
     * agrees with itself. Both new gates ride on this path — the `purpose == want` match (satisfied by
     * the ABSENT-key default) and `validDaemonPub` (strictly tighter than the shipped `isNotBlank`).
     *
     * The daemon's copy and the app's must answer this identically, or one end offers to establish what
     * the other refuses — so the twin of this test lives in the app's `IncomingLinkTest`.
     */
    @Test
    fun a_pre_release_collab_invite_still_decodes_at_its_own_door() {
        val blob = b64(
            ("""{"relay":"wss://relay.example","accountId":"acctA",""" +
                """"daemonPub":"${TestKeys.DAEMON_PUB}","ticket":"one-time-ticket","ownerLabel":"Panda"}""")
                .encodeToByteArray(),
        )

        val decoded = decodeCollaboratorInvite(COLLAB_URI_PREFIX + blob)
        assertNotNull(decoded, "a v1.6.0 invite must keep working — it is printed and pasted in the field")
        assertEquals(CollaboratorPurpose.SESSION_HANDOFF, decoded.purpose, "an ABSENT purpose keeps its historical meaning")
        assertEquals(600, decoded.ttlSec, "…and the pre-existing ttl default is untouched")
        assertNotNull(decodeCollaboratorInvite(blob), "a bare pre-purpose blob still pastes")

        // …and it is still not a review peer, so `review join` refuses it at either form of that door
        assertNull(decodeReviewContactInvite(REVIEW_CONTACT_URI_PREFIX + blob))
        assertNull(decodeReviewContactInvite(blob))
    }

    /** …and the refusal reaches the CALLER of `review join`, before any relay redeem is attempted. */
    @Test
    fun join_refuses_a_session_handoff_ticket_without_burning_it() = withFixture { f ->
        val res = f.service.join(encode(invite.copy(purpose = CollaboratorPurpose.SESSION_HANDOFF)), "Panda")
        assertTrue(res is PeerInboxService.JoinResult.Refused && res.code == "invite_invalid", "$res")
        assertEquals(0, f.transport.redeems.get(), "a wrong-door ticket must not be redeemed — that is what burns it")
        assertEquals(0, f.links.active().size)
    }

    @Test
    fun a_redeemed_link_recovers_from_a_crash_between_secret_and_public_file_writes() {
        val dir = Files.createTempDirectory("ccp-peer-link-journal").toFile()
        val blockedParent = dir.resolve("public-parent").apply { writeText("blocks the public write") }
        val publicPath = blockedParent.resolve("peer-links.json")
        val secretPath = dir.resolve("peer-secrets.json")
        val link = PeerLink("pl_1", "Panda", "wss://relay", "acct", "peer-pub", "devB", "fp", 1)
        val secret = PeerLinkSecret("pl_1", "credential", "private", "public", "ticket")

        val store = PeerLinkStore.load(publicPath, secretPath)
        assertTrue(store.put(link, secret), "the secret-file journal makes the redeemed credential durable")
        assertEquals(link, store.byId(link.id))

        assertTrue(blockedParent.delete())
        assertTrue(blockedParent.mkdirs())
        val recovered = PeerLinkStore.load(publicPath, secretPath)
        assertEquals(link, recovered.byId(link.id), "boot must finish the journaled public-file write")
        assertEquals("credential", recovered.secretOf(link.id)?.credential)
    }

    @Test
    fun an_orphaned_peer_secret_is_deleted_when_its_public_address_book_is_lost() {
        val dir = Files.createTempDirectory("ccp-peer-link-orphan").toFile()
        val publicPath = dir.resolve("peer-links.json")
        val secretPath = dir.resolve("peer-secrets.json")
        val link = PeerLink("pl_1", "Panda", "wss://relay", "acct", "peer-pub", "devB", "fp", 1)
        val secret = PeerLinkSecret("pl_1", "credential", "private", "public")
        assertTrue(PeerLinkStore.load(publicPath, secretPath).put(link, secret))
        assertTrue(publicPath.delete())

        val recovered = PeerLinkStore.load(publicPath, secretPath)
        assertNull(recovered.byId(link.id))
        assertNull(recovered.secretOf(link.id), "unreachable impersonation material must fail closed")
        assertNull(PeerLinkStore.load(publicPath, secretPath).secretOf(link.id), "the cleanup must be durable")
    }

    @Test
    fun the_one_time_ticket_is_burned_after_the_first_authenticated_frame() = withFixture { f ->
        val link = joinAndConnect(f)
        assertEquals("one-time-ticket", f.links.secretOf(link.id)!!.ticket, "the PSK is needed for the FIRST connect")
        f.transport.deliver(ReviewListing(listOf(request("rr_1", ReviewStatus.QUEUED))))
        await("the ticket to be cleared") { f.links.secretOf(link.id)!!.ticket == null }
    }

    // ---- persist before ACK ------------------------------------------------

    @Test
    fun a_delivery_ack_is_only_sent_after_the_mirror_is_on_disk() = withFixture { f ->
        val link = joinAndConnect(f)
        f.transport.deliver(ReviewListing(listOf(request("rr_1", ReviewStatus.QUEUED))))
        await("the delivery ack") { f.transport.sent.any { it is MarkReviewDelivered } }

        val ack = f.transport.mirrorAtSend.first { it.first is MarkReviewDelivered }
        assertEquals(
            ReviewStatus.QUEUED, ack.second,
            "the mirror must already be persisted when the ACK goes out — a relay write is not delivery",
        )
        // and it survives a reload from the same file
        assertNotNull(PeerInboxStore.load(f.inboxPath).row(link.id, "rr_1"), "the mirror must be durable, not in-memory")
        assertEquals(
            PeerInboxClient.DELIVERY_ACK_KEY, (ack.first as MarkReviewDelivered).idempotencyKey,
            "the ACK key must be stable across retries",
        )
        // the ACK is DURABLE INTENT, not one hopeful frame: it is in the outbox before it is sent, and a
        // reload from disk still holds it (nothing has confirmed it yet)
        val queued = PeerInboxStore.load(f.inboxPath).outboxOf(link.id).filter { it.expect == ReviewStatus.DELIVERED }
        assertEquals(1, queued.size, "exactly one persisted delivery ACK, got $queued")
        assertEquals(PeerInboxClient.deliveryAckId(link.id, "rr_1"), queued.single().id)
    }

    /**
     * The failure this replaces: the ACK used to be one best-effort send on a live socket. Lose that
     * single frame — no disconnect, nothing to trigger a reconnect flush — and the colleague reads
     * QUEUED forever while this inbox shows the request. Now it is retried on the open connection until
     * the SENDER's own row says it landed.
     */
    @Test
    fun a_dropped_delivery_ack_is_retried_on_the_live_socket_until_the_sender_confirms() {
        val dir = Files.createTempDirectory("ccp-peer-ack-retry").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val f = Fixture(scope, dir, resendIntervalMs = 40)
                val link = joinAndConnect(f)
                f.transport.dropNextDeliveryAck = true // the relay eats it; the socket stays up
                f.transport.deliver(ReviewListing(listOf(request("rr_1", ReviewStatus.QUEUED))))

                await("a RESENT delivery ack on the same connection") {
                    f.transport.sent.count { it is MarkReviewDelivered } >= 1
                }
                assertEquals(1, f.transport.connects.get(), "the retry must not need a reconnect")

                // it stays queued until the sender's authoritative row proves it landed
                assertTrue(f.store.outboxOf(link.id).any { it.expect == ReviewStatus.DELIVERED })
                f.transport.deliver(ReviewUpdated(request("rr_1", ReviewStatus.DELIVERED, revision = 2)))
                await("the confirmed ACK to leave the outbox") {
                    f.store.outboxOf(link.id).none { it.expect == ReviewStatus.DELIVERED }
                }
                assertFalse(
                    PeerInboxStore.load(f.inboxPath).outbox().any { it.expect == ReviewStatus.DELIVERED },
                    "the drop must be durable too — a restart must not resurrect a confirmed ACK",
                )
            }
        } finally {
            scope.cancel()
        }
    }

    /**
     * The ACK is plumbing, not one of the reader's choices. It shares the outbox because it needs the
     * same durability, but surfacing it as a "queued action" would print `queued: delivered` under every
     * freshly received request — describing machinery nobody asked for and nobody can act on.
     */
    @Test
    fun the_delivery_ack_never_shows_up_as_a_queued_user_action() = withFixture { f ->
        val link = joinAndConnect(f)
        f.transport.deliver(ReviewListing(listOf(request("rr_1", ReviewStatus.QUEUED))))
        await("the ACK to be queued") { f.store.outboxOf(link.id).any { it.expect == ReviewStatus.DELIVERED } }

        val row = f.store.row(link.id, "rr_1")!!
        assertEquals(emptyList(), f.service.pendingActions(row), "the ACK is not a user action")

        // a real one IS shown, so the filter is not just hiding everything
        f.transport.deliver(ReviewUpdated(request("rr_1", ReviewStatus.DELIVERED, revision = 2)))
        await("the mirror to catch up") { f.store.row(link.id, "rr_1")!!.request.status == ReviewStatus.DELIVERED }
        assertTrue(f.service.acknowledge("rr_1") is PeerInboxService.ActionResult.Ok)
        assertEquals(listOf("acknowledged"), f.service.pendingActions(f.store.row(link.id, "rr_1")!!))
    }

    /** …and if the process dies before any of that, the intent is still on disk for the next boot. */
    @Test
    fun a_delivery_ack_survives_a_restart_and_is_resent_on_the_next_connection() {
        val dir = Files.createTempDirectory("ccp-peer-ack-restart").toFile()
        val first = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val f = Fixture(first, dir)
                f.transport.dropNextDeliveryAck = true
                joinAndConnect(f)
                f.transport.deliver(ReviewListing(listOf(request("rr_1", ReviewStatus.QUEUED))))
                await("the ACK to be persisted") {
                    f.store.outbox().any { it.expect == ReviewStatus.DELIVERED }
                }
            }
        } finally {
            first.cancel()
        }

        // a whole new process: same files, fresh stores, fresh transport
        val second = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val f = Fixture(second, dir)
                assertTrue(
                    f.store.outbox().any { it.expect == ReviewStatus.DELIVERED },
                    "the queued ACK must be on disk after the restart",
                )
                f.transport.linkId = f.links.active().single().id
                f.service.start()
                await("the ACK to be resent on the new connection") {
                    f.transport.sent.any { it is MarkReviewDelivered }
                }
            }
        } finally {
            second.cancel()
        }
    }

    @Test
    fun a_malformed_or_misaddressed_row_is_dropped_and_never_acked() = withFixture { f ->
        val link = joinAndConnect(f)
        // addressed to a different device
        f.transport.deliver(ReviewListing(listOf(request("rr_other", ReviewStatus.QUEUED).copy(recipientDeviceId = "devZ"))))
        // and one this build cannot store at all (unknown status = fail closed)
        f.transport.deliver(ReviewListing(listOf(request("rr_bad", ReviewStatus.UNKNOWN))))
        // give the pump a moment, then prove nothing landed
        delay(150)
        assertNull(f.store.row(link.id, "rr_other"))
        assertNull(f.store.row(link.id, "rr_bad"))
        assertTrue(f.transport.sent.none { it is MarkReviewDelivered })
    }

    @Test
    fun a_mirror_write_failure_is_never_acked_or_published_in_memory() = runBlocking {
        val dir = Files.createTempDirectory("ccp-peer-write-fail").toFile()
        val parentIsAFile = dir.resolve("not-a-directory").apply { writeText("x") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val f = Fixture(scope, dir, parentIsAFile.resolve("review-inbox.json"))
            val link = joinAndConnect(f)
            f.transport.deliver(ReviewListing(listOf(request("rr_not_saved", ReviewStatus.QUEUED))))
            delay(150)
            assertNull(f.store.row(link.id, "rr_not_saved"))
            assertTrue(f.transport.sent.none { it is MarkReviewDelivered }, "persist-before-ACK must fail closed")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_replayed_lower_revision_never_regresses_the_mirror() = withFixture { f ->
        val link = joinAndConnect(f)
        f.transport.deliver(ReviewUpdated(request("rr_1", ReviewStatus.ACKNOWLEDGED, revision = 3)))
        await("the mirror") { f.store.row(link.id, "rr_1") != null }
        f.transport.deliver(ReviewUpdated(request("rr_1", ReviewStatus.QUEUED, revision = 1))) // a late replay
        delay(100)
        assertEquals(ReviewStatus.ACKNOWLEDGED, f.store.row(link.id, "rr_1")!!.request.status)
        assertEquals(3, f.store.row(link.id, "rr_1")!!.request.revision)
    }

    // ---- offline behaviour -------------------------------------------------

    @Test
    fun a_request_that_arrived_while_offline_is_picked_up_on_reconnect() = withFixture { f ->
        val link = joinAndConnect(f)
        f.transport.hangUp()
        await("the disconnect") { !f.transport.connected() }
        // the peer kept the request; our next connect re-lists and gets it
        await("a reconnect") { f.transport.connects.get() >= 2 }
        f.transport.deliver(ReviewListing(listOf(request("rr_late", ReviewStatus.QUEUED))))
        await("the late request") { f.store.row(link.id, "rr_late") != null }
        assertTrue(f.transport.sent.count { it is ListReviewRequests } >= 2, "every connection starts with a replay")
    }

    @Test
    fun a_local_action_taken_while_offline_is_persisted_and_flushed_on_reconnect() = withFixture { f ->
        val link = joinAndConnect(f)
        f.transport.deliver(ReviewUpdated(request("rr_1", ReviewStatus.DELIVERED, revision = 2)))
        await("the mirror") { f.store.row(link.id, "rr_1") != null }

        f.transport.online = false
        f.transport.hangUp()
        await("the disconnect") { !f.transport.connected() }

        val out = f.service.acknowledge("rr_1")
        assertTrue(out is PeerInboxService.ActionResult.Ok && out.queued, "$out")
        assertEquals(1, f.store.outboxOf(link.id).size, "the intent must be on disk before anything is sent")
        assertEquals(listOf("acknowledged"), f.service.pendingActions(f.store.row(link.id, "rr_1")!!))

        f.transport.online = true
        await("the acknowledge to be sent") { f.transport.sent.any { it is AcknowledgeReviewRequest } }
    }

    @Test
    fun an_outbox_item_is_only_dropped_once_the_senders_row_confirms_it() = withFixture { f ->
        val link = joinAndConnect(f)
        f.transport.deliver(ReviewUpdated(request("rr_1", ReviewStatus.DELIVERED, revision = 2)))
        await("the mirror") { f.store.row(link.id, "rr_1") != null }

        assertTrue(f.service.respond("rr_1", ReviewResult(ReviewVerdict.APPROVE, "lgtm")) is PeerInboxService.ActionResult.Ok)
        await("the respond to be sent") { f.transport.sent.any { it is RespondReviewRequest } }
        assertEquals(1, f.store.outboxOf(link.id).size, "sent is not confirmed — it stays queued")

        // an unrelated update must NOT clear it
        f.transport.deliver(ReviewUpdated(request("rr_1", ReviewStatus.ACKNOWLEDGED, revision = 3)))
        delay(100)
        assertEquals(1, f.store.outboxOf(link.id).size)

        // …the authoritative RESPONDED row does
        f.transport.deliver(ReviewUpdated(request("rr_1", ReviewStatus.RESPONDED, revision = 4)))
        await("the outbox to drain") { f.store.outboxOf(link.id).isEmpty() }
    }

    @Test
    fun a_reconnect_resends_an_unconfirmed_item_without_duplicating_its_idempotency_key() = withFixture { f ->
        val link = joinAndConnect(f)
        f.transport.deliver(ReviewUpdated(request("rr_1", ReviewStatus.DELIVERED, revision = 2)))
        await("the mirror") { f.store.row(link.id, "rr_1") != null }
        f.service.acknowledge("rr_1")
        await("the first send") { f.transport.sent.any { it is AcknowledgeReviewRequest } }

        f.transport.hangUp()
        await("a reconnect") { f.transport.connects.get() >= 2 }
        await("the resend") { f.transport.sent.count { it is AcknowledgeReviewRequest } >= 2 }
        val keys = f.transport.sent.filterIsInstance<AcknowledgeReviewRequest>().map { it.idempotencyKey }.toSet()
        assertEquals(1, keys.size, "a retry must reuse ONE key so the sender applies it once")
    }

    /** The whole point of persisting the outbox: a daemon restart must not swallow a finished review. */
    @Test
    fun a_restart_recovers_the_outbox_and_flushes_it() = runBlocking {
        val dir = Files.createTempDirectory("ccp-peer-restart").toFile()
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val linkId: String
        try {
            val f = Fixture(scope1, dir)
            val link = joinAndConnect(f)
            linkId = link.id
            f.transport.deliver(ReviewUpdated(request("rr_1", ReviewStatus.DELIVERED, revision = 2)))
            await("the mirror") { f.store.row(link.id, "rr_1") != null }
            f.transport.online = false
            f.transport.hangUp()
            await("the disconnect") { !f.transport.connected() }
            f.service.respond("rr_1", ReviewResult(ReviewVerdict.REQUEST_CHANGES, "found two blockers"))
            assertEquals(1, f.store.outboxOf(link.id).size)
        } finally {
            scope1.cancel()
        }

        // a brand-new daemon over the same files
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val f2 = Fixture(scope2, dir)
            f2.transport.linkId = linkId
            assertEquals(1, f2.store.outboxOf(linkId).size, "the queued result must survive the restart")
            f2.service.start()
            await("the reconnect") { f2.transport.connected() }
            await("the queued result to be resent") { f2.transport.sent.any { it is RespondReviewRequest } }
            val resent = f2.transport.sent.filterIsInstance<RespondReviewRequest>().first()
            assertEquals("found two blockers", resent.result.summary)
        } finally {
            scope2.cancel()
        }
    }

    // ---- local action rules ------------------------------------------------

    @Test
    fun a_local_action_checks_the_last_known_status_and_refuses_the_impossible_ones() = withFixture { f ->
        val link = joinAndConnect(f)
        f.transport.deliver(ReviewUpdated(request("rr_1", ReviewStatus.QUEUED, revision = 1)))
        await("the mirror") { f.store.row(link.id, "rr_1") != null }
        // QUEUED means they don't even know we have it: acknowledging is not a transition that exists
        val bad = f.service.acknowledge("rr_1")
        assertTrue(bad is PeerInboxService.ActionResult.Refused && bad.code == "review_bad_transition", "$bad")

        val missing = f.service.acknowledge("rr_nope")
        assertTrue(missing is PeerInboxService.ActionResult.Refused && missing.code == "review_not_found", "$missing")
    }

    @Test
    fun pending_actions_cannot_be_reordered_or_replaced_by_a_conflicting_final_answer() = withFixture { f ->
        val link = joinAndConnect(f)
        f.transport.deliver(ReviewUpdated(request("rr_1", ReviewStatus.DELIVERED, revision = 2)))
        await("the mirror") { f.store.row(link.id, "rr_1") != null }

        assertTrue(f.service.start("rr_1") is PeerInboxService.ActionResult.Ok)
        val lateAck = f.service.acknowledge("rr_1")
        assertTrue(lateAck is PeerInboxService.ActionResult.Refused && lateAck.code == "review_action_pending", "$lateAck")
        assertTrue(f.service.respond("rr_1", ReviewResult(ReviewVerdict.APPROVE, "done")) is PeerInboxService.ActionResult.Ok)
        val conflicting = f.service.decline("rr_1", "changed my mind")
        assertTrue(conflicting is PeerInboxService.ActionResult.Refused && conflicting.code == "review_action_pending", "$conflicting")
        assertEquals(2, f.store.outboxOf(link.id).size)
    }

    @Test
    fun removing_a_link_stops_the_sync_keeps_the_history_and_refuses_further_actions() = withFixture { f ->
        val link = joinAndConnect(f)
        f.transport.deliver(ReviewUpdated(request("rr_1", ReviewStatus.DELIVERED, revision = 2)))
        await("the mirror") { f.store.row(link.id, "rr_1") != null }
        f.service.acknowledge("rr_1")

        assertTrue(f.service.remove(link.id) is PeerInboxService.RemoveResult.Ok)
        assertNull(f.links.secretOf(link.id), "the key material must be deleted, not merely flagged")
        assertTrue(f.links.byId(link.id)!!.removed)
        assertTrue(f.store.outboxOf(link.id).isEmpty(), "undeliverable intent is cleared")
        assertNotNull(f.store.row(link.id, "rr_1"), "…but the mirrored history stays")

        val after = f.service.acknowledge("rr_1")
        assertTrue(after is PeerInboxService.ActionResult.Refused && after.code == "review_link_removed", "$after")
    }

    @Test
    fun a_full_outbox_refuses_a_new_action_rather_than_forgetting_an_old_one() = withFixture { f ->
        val link = joinAndConnect(f)
        repeat(PeerInboxStore.MAX_OUTBOX) { i ->
            assertEquals(
                PeerInboxStore.EnqueueResult.STORED,
                f.store.enqueue(
                    OutboxItem("o$i", link.id, "rr_$i", "{}", ReviewStatus.ACKNOWLEDGED, "k$i", 0),
                ),
            )
        }
        f.transport.deliver(ReviewUpdated(request("rr_new", ReviewStatus.DELIVERED, revision = 2)))
        await("the mirror") { f.store.row(link.id, "rr_new") != null }
        val out = f.service.acknowledge("rr_new")
        assertTrue(out is PeerInboxService.ActionResult.Refused && out.code == "review_outbox_full", "$out")
        assertEquals(PeerInboxStore.MAX_OUTBOX, f.store.outboxOf(link.id).size)
    }

    @Test
    fun a_peer_error_never_takes_the_inbox_down() = withFixture { f ->
        joinAndConnect(f)
        f.transport.deliver(PocketError("review_not_allowed", "no review request with that id is addressed to you"))
        delay(100)
        assertTrue(f.transport.connected(), "a refusal is data, not a reason to drop the link")
    }

    // ---- pure helpers ------------------------------------------------------

    @Test
    fun satisfied_settles_on_at_or_past_and_on_terminal_but_never_on_unknown() {
        assertTrue(PeerInboxStore.satisfied(ReviewStatus.ACKNOWLEDGED, ReviewStatus.ACKNOWLEDGED))
        assertTrue(PeerInboxStore.satisfied(ReviewStatus.ACKNOWLEDGED, ReviewStatus.RESPONDED), "a later state implies the earlier one landed")
        assertFalse(PeerInboxStore.satisfied(ReviewStatus.RESPONDED, ReviewStatus.ACKNOWLEDGED))
        assertTrue(PeerInboxStore.satisfied(ReviewStatus.RESPONDED, ReviewStatus.CANCELLED), "a terminal row makes the mutation moot")
        assertFalse(PeerInboxStore.satisfied(ReviewStatus.RESPONDED, ReviewStatus.UNKNOWN), "an unreadable state settles nothing")
        assertFalse(PeerInboxStore.satisfied(ReviewStatus.UNKNOWN, ReviewStatus.RESPONDED))
    }

    @Test
    fun mirror_rows_from_two_peers_with_the_same_request_id_do_not_collide() {
        val dir = Files.createTempDirectory("ccp-peer-collide").toFile()
        val store = PeerInboxStore.load(dir.resolve("inbox.json"))
        assertEquals(PeerInboxStore.MirrorResult.STORED, store.mirror("pl_a", request("rr_1", ReviewStatus.QUEUED), 1))
        assertEquals(PeerInboxStore.MirrorResult.STORED, store.mirror("pl_b", request("rr_1", ReviewStatus.DELIVERED, revision = 2), 1))
        assertEquals(ReviewStatus.QUEUED, store.row("pl_a", "rr_1")!!.request.status)
        assertEquals(ReviewStatus.DELIVERED, store.row("pl_b", "rr_1")!!.request.status)
        assertEquals(2, store.byRequestId("rr_1").size, "an ambiguous id must be visible as ambiguous")
    }

    @Test
    fun the_active_mirror_and_failed_outbox_are_bounded_without_evicting_work() {
        val store = PeerInboxStore.inMemory()
        repeat(PeerInboxStore.MAX_ACTIVE_ROWS) { i ->
            assertEquals(
                PeerInboxStore.MirrorResult.STORED,
                store.mirror("pl_a", request("rr_$i", ReviewStatus.DELIVERED, revision = 2), i.toLong()),
            )
        }
        assertEquals(
            PeerInboxStore.MirrorResult.FULL,
            store.mirror("pl_a", request("rr_overflow", ReviewStatus.DELIVERED, revision = 2), 999),
        )
        assertNull(store.row("pl_a", "rr_overflow"), "open work is refused, never silently evicted")

        val dir = Files.createTempDirectory("ccp-outbox-write-fail").toFile()
        val parentIsAFile = dir.resolve("not-a-directory").apply { writeText("x") }
        val failing = PeerInboxStore.load(parentIsAFile.resolve("inbox.json"))
        val item = OutboxItem("o", "pl_a", "rr_1", "{}", ReviewStatus.ACKNOWLEDGED, "k", 0)
        assertEquals(PeerInboxStore.EnqueueResult.PERSIST_FAILED, failing.enqueue(item))
        assertTrue(failing.outbox().isEmpty(), "an action that was not saved must not be reported as queued")
    }

    @Test
    fun an_undecodable_outbox_item_reports_no_frame_so_the_queue_cannot_wedge() {
        val item = OutboxItem("o1", "pl_a", "rr_1", "{\"t\":\"pocket/review.frobnicate\"}", ReviewStatus.ACKNOWLEDGED, "k", 0)
        assertNull(item.frame())
    }
}
