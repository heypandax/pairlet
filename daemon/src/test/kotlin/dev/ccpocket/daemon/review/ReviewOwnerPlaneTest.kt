package dev.ccpocket.daemon.review

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.bridge.GuestScope
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.handoff.CollaboratorCaps
import dev.ccpocket.daemon.handoff.CollaboratorControl
import dev.ccpocket.daemon.handoff.CollaboratorDirectory
import dev.ccpocket.daemon.handoff.CollaboratorScope
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.server.RequestRouter
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.ActOnReviewInbox
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ArtifactKind
import dev.ccpocket.protocol.ArtifactRef
import dev.ccpocket.protocol.Collaborator
import dev.ccpocket.protocol.CollaboratorDirection
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorListing
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.CollaboratorTicketCreated
import dev.ccpocket.protocol.CollaboratorUpdated
import dev.ccpocket.protocol.CreateReviewInvite
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.JoinReviewContact
import dev.ccpocket.protocol.ListReviewContacts
import dev.ccpocket.protocol.ListReviewInbox
import dev.ccpocket.protocol.PairCredential
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PrepareReviewRequest
import dev.ccpocket.protocol.RemoveReviewContact
import dev.ccpocket.protocol.ReviewBrief
import dev.ccpocket.protocol.ReviewContactUpdated
import dev.ccpocket.protocol.ReviewContactsListing
import dev.ccpocket.protocol.ReviewInboxActed
import dev.ccpocket.protocol.ReviewInboxAction
import dev.ccpocket.protocol.ReviewInboxListing
import dev.ccpocket.protocol.ReviewInviteCreated
import dev.ccpocket.protocol.ReviewPrepared
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewResult
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.ReviewVerdict
import dev.ccpocket.protocol.ToPhone
import dev.ccpocket.protocol.acceptsReviewRequest
import dev.ccpocket.protocol.acceptsSessionHandoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The OWNER-LOCAL review plane (REVIEW-REQUEST.md §6 + §12): the frames the App/desktop Review Center
 * drives, over the same [ReviewOwnerService] the CLI's local control API drives.
 *
 * Two things are under test and they are not the same thing:
 *  1. the plane WORKS for an owner — contacts, this machine's received inbox, prepare, queued actions;
 *  2. the plane is CLOSED to every restricted credential. That second half is the reason the frames are
 *     a separate family: they expose this machine's whole peer inbox across every colleague, so a
 *     collaborator reaching them would read briefs it was never sent and mint links in its host's name.
 *     A collaborator arrives with `origin == null && guestScope == null`, so the two-field check the
 *     older review frames grew up with would have admitted exactly the weakest credential we issue.
 */
class ReviewOwnerPlaneTest {

    private class StubBackend : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun listSessions(workdir: String) = emptyList<dev.ccpocket.protocol.SessionSummary>()
        override fun processBuilder(spec: AgentSpec) = throw UnsupportedOperationException()
        override suspend fun attach(io: AgentIo, spec: AgentSpec) = throw UnsupportedOperationException()
        override suspend fun parse(line: String): Nothing = throw UnsupportedOperationException()
        override suspend fun sendPrompt(text: String, images: List<dev.ccpocket.protocol.ImageData>) = throw UnsupportedOperationException()
        override suspend fun interrupt() = throw UnsupportedOperationException()
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) = throw UnsupportedOperationException()
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = false
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = throw UnsupportedOperationException()
        override fun replayHistory(workdir: String, sessionId: String) = emptyList<dev.ccpocket.protocol.HistoryMessage>()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    /** A contact ledger that answers both halves the way [dev.ccpocket.daemon.handoff.CollaboratorService]
     *  does, and records what purpose each mint asked for. */
    private class FakeContacts(rows: List<Collaborator>) : CollaboratorControl, CollaboratorDirectory {
        val rows = rows.toMutableList()
        var mintedPurpose: CollaboratorPurpose? = null
        var online = true

        override fun labelOf(deviceId: String) = rows.firstOrNull { it.deviceId == deviceId && !it.removed }?.label
        override fun isActive(deviceId: String) = rows.any { it.deviceId == deviceId && !it.removed }
        override fun acceptsHandoff(deviceId: String) =
            rows.firstOrNull { it.deviceId == deviceId }?.acceptsSessionHandoff == true
        override fun acceptsReview(deviceId: String) =
            rows.firstOrNull { it.deviceId == deviceId }?.acceptsReviewRequest == true
        override suspend fun noteHandoff(deviceId: String, at: Long) {}

        override suspend fun createTicket(label: String?, purpose: CollaboratorPurpose): CollaboratorTicketCreated {
            mintedPurpose = purpose
            if (!online) return CollaboratorTicketCreated(ok = false, error = "can't reach the relay")
            return CollaboratorTicketCreated(
                ok = true,
                invite = CollaboratorInvite(
                    relay = "wss://relay.example", accountId = "acctA",
                    daemonPub = TestKeys.DAEMON_PUB,
                    ticket = "ONE-TIME-TICKET", ownerLabel = "Panda · MacBook", ttlSec = 120,
                    // stamped like the real service: the invite says what it establishes, which is what
                    // decides the URI door it is published under (REVIEW-REQUEST.md §13.3)
                    purpose = purpose,
                ),
            )
        }

        // SESSION HANDOFF only, like the real service: the legacy listing is not a review surface
        override suspend fun list() = CollaboratorListing(rows.filter { it.purpose == CollaboratorPurpose.SESSION_HANDOFF })

        override suspend fun contacts(purpose: CollaboratorPurpose) = rows.filter { it.purpose == purpose }

        override suspend fun remove(deviceId: String): ToPhone = remove(deviceId, CollaboratorPurpose.SESSION_HANDOFF)

        override suspend fun remove(deviceId: String, purpose: CollaboratorPurpose): ToPhone {
            val i = rows.indexOfFirst { it.deviceId == deviceId && it.purpose == purpose }
            if (i < 0) return PocketError("collaborator_not_found", "no such collaborator")
            rows[i] = rows[i].copy(removed = true)
            return CollaboratorUpdated(rows[i])
        }

        override suspend fun onRedeemed(deviceId: String, peerPubB64: String) {}
    }

    /** Redeems anything, dials nothing: this plane's job is the control surface, not the socket. */
    private class OfflineTransport : PeerTransport {
        override fun generateKeys() = PeerKeys("cHJpdg", "cHVi")
        override suspend fun redeem(relay: String, ticket: String, devicePubB64: String) =
            PairCredential(deviceId = "devMe", credential = "bearer-secret", accountId = "acctPeer")
        override suspend fun dial(link: PeerLink, secret: PeerLinkSecret, session: PeerSession) {
            throw IllegalStateException("offline in this test")
        }
    }

    private class Fixture(scope: CoroutineScope) {
        val tmp = Files.createTempDirectory("ccp-review-owner").toFile()
        val contacts = FakeContacts(
            listOf(
                Collaborator(
                    deviceId = DEV_REVIEW, label = "Frank", direction = CollaboratorDirection.OUTBOUND,
                    connectedAt = 1, fingerprint = "tiger-brick", purpose = CollaboratorPurpose.REVIEW,
                ),
                Collaborator(
                    deviceId = DEV_PHONE, label = "Frank's phone", direction = CollaboratorDirection.OUTBOUND,
                    connectedAt = 2, fingerprint = "mango-void", purpose = CollaboratorPurpose.SESSION_HANDOFF,
                ),
            ),
        )
        val links = PeerLinkStore.load(tmp.resolve("peer-links.json"), tmp.resolve("peer-secrets.json"))
        val inbox = PeerInboxStore.load(tmp.resolve("review-inbox.json"))
        var seq = 0
        val peerInbox = PeerInboxService(
            scope, links, inbox, OfflineTransport(), clock = { 5_000L }, newId = { "id${seq++}" },
        )
        val reviews = ReviewService(ReviewRegistry(ReviewStore.load(tmp.resolve("reviews.json")), clock = { 5_000L }))
            .also { it.collaborators = contacts }
        val owner = ReviewOwnerService({ contacts }, reviews, peerInbox)

        val router = RequestRouter(
            registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { StubBackend() })),
            dirs = DirectoryService(),
            transcribe = TranscribeService(scope) { null },
            inbox = FileInboxService { null },
            shell = ShellService(scope),
            exports = FileExportService(scope, { null }),
            scope = scope,
            auth = AuthService(scope, { emptyList() }, { 0 }),
            prefs = DaemonPrefs.load(tmp.resolve("prefs.json")),
            presets = PresetService(PresetStore.load(tmp.resolve("presets.json")), { emptyList() }, { 0 }),
            scheduler = dev.ccpocket.daemon.schedule.SchedulerService(
                dev.ccpocket.daemon.schedule.ScheduleStore.load(tmp.resolve("schedules.json")),
                executor = { null },
            ),
            reviews = reviews,
            reviewOwner = owner,
        )

        suspend fun asOwner(frame: Frame, into: MutableList<Frame>) =
            router.handle(frame, { into += it }, deviceId = "devA")

        suspend fun asCollaborator(frame: Frame, into: MutableList<Frame>) {
            // exactly what DeviceSessions does: the caps whitelist first, the router's own check second
            if (!CollaboratorCaps.ingressAllowed(frame)) {
                into += PocketError("collaborator_forbidden", "not permitted for a collaborator link")
                return
            }
            router.handle(frame, { into += it }, deviceId = DEV_REVIEW, collabScope = CollaboratorScope(DEV_REVIEW))
        }

        suspend fun asBridge(frame: Frame, into: MutableList<Frame>) =
            router.handle(frame, { into += it }, origin = "feishu-bot", deviceId = "devBridge")

        suspend fun asGuest(frame: Frame, into: MutableList<Frame>) = router.handle(
            frame, { into += it },
            guestScope = GuestScope(emptyList(), emptySet(), "guest", null, AccessTier.REVIEW),
            deviceId = "devGuest",
        )

        /** Seed one received request, as [PeerInboxClient] would after mirroring the peer's row. */
        fun receive(id: String, status: ReviewStatus = ReviewStatus.DELIVERED, revision: Long = 2): PeerLink {
            val link = PeerLink(
                id = LINK_ID, label = "Panda", relay = "wss://relay.example", peerAccountId = "acctPeer",
                peerDaemonPub = TestKeys.DAEMON_PUB, deviceId = "devMe",
                fingerprint = "tiger-brick-mango-void · ember-delta-canvas-orbit", joinedAt = 1,
            )
            links.put(link, PeerLinkSecret(LINK_ID, "bearer-secret", "cHJpdg", "cHVi"))
            inbox.mirror(
                LINK_ID,
                ReviewRequest(
                    id = id, senderDeviceId = "devPeer", senderLabel = "Panda", recipientDeviceId = "devMe",
                    title = "the ACK path", brief = ReviewBrief(request = "check the retry race"),
                    artifacts = listOf(ArtifactRef(ArtifactKind.MERGE_REQUEST, url = "https://git.example/mr/1")),
                    status = status, revision = revision, createdAt = 1, updatedAt = 1,
                ),
                5_000L,
            )
            return link
        }

        companion object {
            const val DEV_REVIEW = "devReview"
            const val DEV_PHONE = "devPhone"
            const val LINK_ID = "pl_test"
        }
    }

    private fun <T> fixture(block: suspend (Fixture) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(Fixture(scope))
        } finally {
            scope.cancel()
        }
    }

    private fun forbidden(frames: List<Frame>) =
        frames.filterIsInstance<PocketError>().filter { it.code == "review_forbidden" || it.code == "collaborator_forbidden" }

    // ---- the owner can drive the whole surface ----------------------------

    @Test
    fun an_owner_lists_contacts_with_the_daemons_own_send_eligibility() = fixture { f ->
        val out = mutableListOf<Frame>()
        f.receive("rr_1")
        f.asOwner(ListReviewContacts, out)
        val items = out.filterIsInstance<ReviewContactsListing>().last().items

        val review = assertNotNull(items.firstOrNull { it.id == Fixture.DEV_REVIEW })
        assertEquals(CollaboratorPurpose.REVIEW, review.purpose)
        assertTrue(review.canSend, "a live review peer is a send target")

        // the inbound peer link shows up as the SAME kind of row, in the other direction, and is never
        // a send target: it is a credential we hold in THEIR account
        val inbound = assertNotNull(items.firstOrNull { it.direction == CollaboratorDirection.INBOUND })
        assertEquals(Fixture.LINK_ID, inbound.id)
        assertFalse(inbound.canSend)
        assertTrue(inbound.fingerprint!!.isNotBlank(), "the fingerprint the humans compared is display metadata")
    }

    @Test
    fun the_review_invite_frame_mints_a_review_purpose_link_and_returns_the_uri_once() = fixture { f ->
        val out = mutableListOf<Frame>()
        f.asOwner(CreateReviewInvite(label = "Frank"), out)
        val created = out.filterIsInstance<ReviewInviteCreated>().last()
        assertTrue(created.ok, created.error)
        assertEquals(CollaboratorPurpose.REVIEW, f.contacts.mintedPurpose, "the Review Center mints REVIEW peers")
        // its OWN door (REVIEW-REQUEST.md §13.3): an older app does not know this host, so it cannot
        // redeem — and thereby burn — a ticket minted for a colleague's daemon
        assertTrue(
            created.invite!!.startsWith(dev.ccpocket.daemon.review.REVIEW_CONTACT_URI_PREFIX),
            created.invite!!,
        )
        assertEquals(120, created.ttlSec)
    }

    @Test
    fun a_relay_that_is_down_refuses_the_invite_with_a_code_the_ui_can_read() = fixture { f ->
        val out = mutableListOf<Frame>()
        f.contacts.online = false
        f.asOwner(CreateReviewInvite(label = "Frank"), out)
        val created = out.filterIsInstance<ReviewInviteCreated>().last()
        assertFalse(created.ok)
        assertEquals("invite_refused", created.code)
        assertNull(created.invite, "a failed mint must not carry establishment material")
    }

    @Test
    fun join_persists_the_peer_link_on_this_daemon() = fixture { f ->
        val out = mutableListOf<Frame>()
        val invite = CollaboratorInvite(
            relay = "wss://relay.example", accountId = "acctPeer",
            daemonPub = TestKeys.OTHER_DAEMON_PUB,
            ticket = "peer-ticket", ownerLabel = "Panda",
            purpose = CollaboratorPurpose.REVIEW, // `review join` accepts nothing else (§13.3)
        )
        f.asOwner(JoinReviewContact(invite.encodeUri(), label = "Panda"), out)
        val updated = out.filterIsInstance<ReviewContactUpdated>().last()
        assertTrue(updated.ok, updated.error)
        val contact = assertNotNull(updated.contact)
        assertEquals(CollaboratorDirection.INBOUND, contact.direction)
        assertEquals(CollaboratorPurpose.REVIEW, contact.purpose)
        // the credential belongs to the always-on DAEMON — that is what keeps delivery working with
        // every UI closed. Its secret is never on the wire.
        assertEquals(1, f.links.active().size)
    }

    @Test
    fun remove_disambiguates_the_two_id_spaces_by_direction() = fixture { f ->
        val out = mutableListOf<Frame>()
        f.receive("rr_1")

        f.asOwner(RemoveReviewContact(Fixture.LINK_ID, CollaboratorDirection.INBOUND), out)
        assertTrue(out.filterIsInstance<ReviewContactUpdated>().last().ok)
        assertTrue(f.links.active().isEmpty(), "the inbound link is severed")

        f.asOwner(RemoveReviewContact(Fixture.DEV_REVIEW, CollaboratorDirection.OUTBOUND), out)
        assertTrue(out.filterIsInstance<ReviewContactUpdated>().last().ok)
        assertTrue(f.contacts.rows.first { it.deviceId == Fixture.DEV_REVIEW }.removed)

        // history is kept on both paths: mirrored requests still reference the contact by label
        f.asOwner(ListReviewInbox(), out)
        assertEquals(1, out.filterIsInstance<ReviewInboxListing>().last().items.size)
    }

    @Test
    fun the_inbox_listing_carries_the_peer_identity_and_the_honest_pending_actions() = fixture { f ->
        val out = mutableListOf<Frame>()
        f.receive("rr_1")

        f.asOwner(ActOnReviewInbox("rr_1", ReviewInboxAction.ACKNOWLEDGE), out)
        val acted = out.filterIsInstance<ReviewInboxActed>().last()
        assertTrue(acted.ok)
        // "queued" is the whole point: the daemon recorded it and will retry — the colleague has NOT
        // seen it, and no UI may render this as delivered
        assertTrue(acted.queued)
        assertEquals(ReviewStatus.ACKNOWLEDGED, acted.status)

        f.asOwner(ListReviewInbox(), out)
        val row = out.filterIsInstance<ReviewInboxListing>().last().items.single()
        assertEquals("Panda", row.peerLabel)
        assertTrue(row.peerFingerprint.isNotBlank())
        assertEquals(ReviewStatus.DELIVERED, row.request.status, "the mirror still holds the sender's truth")
        assertEquals(listOf("acknowledged"), row.pending)
    }

    @Test
    fun a_repeat_action_is_ok_but_not_queued_twice() = fixture { f ->
        val out = mutableListOf<Frame>()
        f.receive("rr_1", status = ReviewStatus.ACKNOWLEDGED, revision = 3)
        f.asOwner(ActOnReviewInbox("rr_1", ReviewInboxAction.ACKNOWLEDGE), out)
        val acted = out.filterIsInstance<ReviewInboxActed>().last()
        assertTrue(acted.ok)
        assertFalse(acted.queued, "already in that state — nothing had to be sent")
    }

    @Test
    fun an_unknown_action_verb_fails_closed() = fixture { f ->
        val out = mutableListOf<Frame>()
        f.receive("rr_1")
        f.asOwner(ActOnReviewInbox("rr_1", ReviewInboxAction.UNKNOWN), out)
        val acted = out.filterIsInstance<ReviewInboxActed>().last()
        assertFalse(acted.ok)
        assertEquals("review_unknown_action", acted.code)
        assertTrue(f.inbox.pendingFor(Fixture.LINK_ID, "rr_1").isEmpty(), "a verb we can't read queues nothing")
    }

    @Test
    fun respond_without_a_result_is_refused_rather_than_sent_empty() = fixture { f ->
        val out = mutableListOf<Frame>()
        f.receive("rr_1")
        f.asOwner(ActOnReviewInbox("rr_1", ReviewInboxAction.RESPOND, result = null), out)
        val acted = out.filterIsInstance<ReviewInboxActed>().last()
        assertFalse(acted.ok)
        assertEquals("review_invalid", acted.code)
    }

    @Test
    fun respond_queues_the_structured_result() = fixture { f ->
        val out = mutableListOf<Frame>()
        f.receive("rr_1")
        f.asOwner(
            ActOnReviewInbox(
                "rr_1", ReviewInboxAction.RESPOND,
                result = ReviewResult(verdict = ReviewVerdict.REQUEST_CHANGES, summary = "the ack can land after revoke"),
            ),
            out,
        )
        val acted = out.filterIsInstance<ReviewInboxActed>().last()
        assertTrue(acted.ok, acted.error)
        assertEquals(ReviewStatus.RESPONDED, acted.status)
        assertEquals(1, f.inbox.pendingFor(Fixture.LINK_ID, "rr_1").size)
    }

    @Test
    fun prepare_returns_the_untrusted_bundle_and_never_the_link_secret() = fixture { f ->
        val out = mutableListOf<Frame>()
        f.receive("rr_1")
        f.asOwner(PrepareReviewRequest("rr_1"), out)
        val prepared = out.filterIsInstance<ReviewPrepared>().last()
        assertTrue(prepared.ok, prepared.error)
        val bundle = assertNotNull(prepared.bundle)
        assertTrue(bundle.peerContentIsUntrusted, "the bundle states it explicitly rather than leaving it implicit")
        assertTrue(bundle.recommendedPrompt.contains("UNTRUSTED"))
        assertEquals(Fixture.LINK_ID, bundle.peer.linkId)
        // the whole encoded reply must not contain the credential, the ticket or the private key
        val json = dev.ccpocket.protocol.PocketJson.encodeToString(ToPhone.serializer(), prepared)
        for (secret in listOf("bearer-secret", "cHJpdg", "ONE-TIME-TICKET")) {
            assertFalse(json.contains(secret), "a prepare reply must never carry $secret")
        }
    }

    @Test
    fun preparing_a_request_you_sent_is_a_category_error_not_a_404() = fixture { f ->
        val out = mutableListOf<Frame>()
        val sent = f.reviews.send(
            senderDeviceId = "devA", senderLabel = null, recipientDeviceId = Fixture.DEV_REVIEW,
            title = "t", brief = ReviewBrief(request = "x"),
            artifacts = listOf(ArtifactRef(ArtifactKind.MERGE_REQUEST, url = "https://git.example/mr/2")),
        )
        val id = (sent as ReviewRegistry.Outcome.Ok).request.id
        f.asOwner(PrepareReviewRequest(id), out)
        val prepared = out.filterIsInstance<ReviewPrepared>().last()
        assertFalse(prepared.ok)
        assertEquals("review_not_received", prepared.code)
    }

    // ---- and it is closed to every restricted credential -------------------

    @Test
    fun no_restricted_credential_may_touch_the_owner_local_plane() = fixture { f ->
        f.receive("rr_1")
        val frames: List<Frame> = listOf(
            ListReviewContacts,
            CreateReviewInvite("Mallory"),
            JoinReviewContact("ccpocket://collab#whatever"),
            RemoveReviewContact(Fixture.LINK_ID, CollaboratorDirection.INBOUND),
            ListReviewInbox(),
            PrepareReviewRequest("rr_1"),
            ActOnReviewInbox("rr_1", ReviewInboxAction.ACKNOWLEDGE),
        )
        for (frame in frames) {
            val name = frame::class.simpleName
            for ((who, route) in listOf<Pair<String, suspend (Frame, MutableList<Frame>) -> Unit>>(
                "collaborator" to f::asCollaborator,
                "bridge" to f::asBridge,
                "guest" to f::asGuest,
            )) {
                val out = mutableListOf<Frame>()
                route(frame, out)
                assertEquals(1, forbidden(out).size, "$who must be refused $name, got $out")
                // and nothing may have leaked alongside the refusal
                assertTrue(out.none { it is ReviewContactsListing || it is ReviewInboxListing || it is ReviewPrepared || it is ReviewInviteCreated })
            }
        }
        // the refusals were real: nothing was joined, removed or queued
        assertEquals(1, f.links.active().size)
        assertTrue(f.inbox.pendingFor(Fixture.LINK_ID, "rr_1").isEmpty())
        assertNull(f.contacts.mintedPurpose, "no restricted credential ever reached the mint")
    }

    /**
     * The inbox listing must be bounded IN BYTES, not in rows. The store caps rows, but one request may
     * be [ReviewLimits.MAX_ENCODED_BYTES] — so a row cap alone permits a frame many times the relay's
     * 4 MiB `MAX_FRAME`, and that does not surface as one clean error: the connection dies, the client
     * reconnects, asks again, and loops. This repo has shipped that bug before; this is the guard.
     */
    @Test
    fun the_inbox_listing_stays_under_the_relay_frame_cap() = fixture { f ->
        val link = f.receive("rr_seed")
        // ~160 KiB of brief per row. Terminal rows on purpose: the store caps ACTIVE rows at 24 but
        // keeps up to MAX_MIRRORED_HISTORY, so history is the shape that actually reaches a huge frame.
        val filler = List(20) { "x".repeat(4_000) }
        repeat(60) { i ->
            f.inbox.mirror(
                Fixture.LINK_ID,
                ReviewRequest(
                    id = "rr_big_$i", senderDeviceId = "devPeer", senderLabel = "Panda", recipientDeviceId = "devMe",
                    title = "big one $i",
                    brief = ReviewBrief(request = "x".repeat(4_000), focusAreas = filler, knownRisks = filler),
                    artifacts = listOf(ArtifactRef(ArtifactKind.MERGE_REQUEST, url = "https://git.example/mr/$i")),
                    status = ReviewStatus.CLOSED, revision = 2, createdAt = i.toLong(), updatedAt = 1,
                ),
                5_000L,
            )
        }
        val unbounded = f.inbox.rows().sumOf {
            dev.ccpocket.protocol.PocketJson
                .encodeToString(ReviewRequest.serializer(), it.request).toByteArray(Charsets.UTF_8).size
        }
        assertTrue(unbounded > 4 * 1024 * 1024, "the fixture really would overflow a frame unbounded, was $unbounded")

        val out = mutableListOf<Frame>()
        f.asOwner(ListReviewInbox(), out)
        val listing = out.filterIsInstance<ReviewInboxListing>().last()
        val bytes = dev.ccpocket.protocol.PocketJson
            .encodeToString(ToPhone.serializer(), listing).toByteArray(Charsets.UTF_8).size
        assertTrue(bytes < 4 * 1024 * 1024, "a listing must fit the relay's frame cap, was $bytes bytes")
        assertTrue(listing.items.isNotEmpty(), "bounding must not empty the listing")
        assertEquals(link.id, listing.items.first().linkId)
    }

    @Test
    fun bounding_drops_history_before_it_drops_work_waiting_on_you() = fixture { f ->
        f.receive("rr_open", status = ReviewStatus.DELIVERED)
        val filler = List(20) { "x".repeat(4_000) }
        repeat(60) { i ->
            f.inbox.mirror(
                Fixture.LINK_ID,
                ReviewRequest(
                    id = "rr_done_$i", senderDeviceId = "devPeer", recipientDeviceId = "devMe",
                    title = "closed $i", brief = ReviewBrief(request = "x".repeat(4_000), focusAreas = filler, knownRisks = filler),
                    artifacts = listOf(ArtifactRef(ArtifactKind.MERGE_REQUEST, url = "https://git.example/mr/$i")),
                    // terminal rows are history; they must never be what pushes out a live request
                    status = ReviewStatus.CLOSED, revision = 2, createdAt = 9_000L + i, updatedAt = 1,
                ),
                5_000L,
            )
        }
        val out = mutableListOf<Frame>()
        f.asOwner(ListReviewInbox(), out)
        val ids = out.filterIsInstance<ReviewInboxListing>().last().items.map { it.request.id }
        assertTrue("rr_open" in ids, "the one request still waiting on this machine survived the bound")
        assertEquals("rr_open", ids.first(), "open work is emitted before history, not merely included")
    }

    /**
     * A peer picks its own `ownerLabel` and that becomes the inbound link's label — so a label is NOT
     * owner-controlled. If `remove` resolved ids and labels in one pass, a peer could set its label to
     * ANOTHER contact's id and thereby decide what `remove <that-id>` does: first jamming it (two hits →
     * ambiguous), then, once the real contact is gone, absorbing it (the only remaining hit is the
     * peer's own link, severed under a name the owner meant for someone else).
     *
     * Ids are minted locally and cannot be squatted, so an exact id match must win outright.
     */
    @Test
    fun a_peer_chosen_label_cannot_shadow_another_contacts_id() = fixture { f ->
        f.receive("rr_1")
        // the peer's label is the owner's OTHER contact's id — the only field the peer controls
        f.links.putLink(f.links.byId(Fixture.LINK_ID)!!.copy(label = Fixture.DEV_REVIEW))

        // the id still resolves to the contact that OWNS it, not to the peer squatting on the string
        val byId = f.owner.remove(Fixture.DEV_REVIEW)
        assertTrue(byId is ReviewOwnerService.Outcome.Ok, "an exact id must win outright, got $byId")
        assertEquals(CollaboratorDirection.OUTBOUND, (byId as ReviewOwnerService.Outcome.Ok).value.direction)
        assertTrue(f.contacts.rows.first { it.deviceId == Fixture.DEV_REVIEW }.removed)
        assertEquals(1, f.links.active().size, "the peer's link is untouched")

        // and with the real contact now gone, the same string STILL must not sever the peer's link
        val again = f.owner.remove(Fixture.DEV_REVIEW)
        assertTrue(again is ReviewOwnerService.Outcome.Refused, "a squatted label must not absorb a freed id, got $again")
        assertEquals(1, f.links.active().size)
    }

    @Test
    fun a_genuine_label_collision_fails_closed_instead_of_picking_a_side() = fixture { f ->
        f.receive("rr_1")
        f.links.putLink(f.links.byId(Fixture.LINK_ID)!!.copy(label = "Frank")) // same label as DEV_REVIEW
        val out = f.owner.remove("Frank")
        assertTrue(out is ReviewOwnerService.Outcome.Refused, "two label hits must refuse, got $out")
        assertEquals("contact_ambiguous", (out as ReviewOwnerService.Outcome.Refused).code)
        assertEquals(1, f.links.active().size)
        assertFalse(f.contacts.rows.first { it.deviceId == Fixture.DEV_REVIEW }.removed)
    }

    // ---- purpose separation (§13.3) ----------------------------------------

    @Test
    fun the_two_purposes_are_eligible_for_exactly_one_feature_each() = fixture { f ->
        // the two contacts differ ONLY in purpose, and the two features read opposite answers
        assertFalse(f.contacts.acceptsHandoff(Fixture.DEV_REVIEW), "a review daemon peer is not a runtime recipient")
        assertTrue(f.contacts.acceptsReview(Fixture.DEV_REVIEW))

        assertTrue(f.contacts.acceptsHandoff(Fixture.DEV_PHONE), "an existing Session Handoff contact is unaffected")
        assertFalse(
            f.contacts.acceptsReview(Fixture.DEV_PHONE),
            "a Session Handoff contact is NOT silently widened into a review recipient — make a Review link instead",
        )
    }

    /** A pre-purpose row decodes as SESSION_HANDOFF, so the upgrade must leave it doing exactly what it
     *  always did — and must not hand it the new feature it was never established for. */
    @Test
    fun a_pre_purpose_contact_keeps_its_handoff_meaning_and_gains_nothing() = fixture { f ->
        val legacyJson = """{"deviceId":"devLegacy","label":"from an old build","direction":"outbound","connectedAt":7}"""
        val legacy = dev.ccpocket.protocol.PocketJson.decodeFromString(Collaborator.serializer(), legacyJson)
        assertEquals(CollaboratorPurpose.SESSION_HANDOFF, legacy.purpose, "a missing purpose is the historical one")
        f.contacts.rows += legacy

        assertTrue(f.contacts.acceptsHandoff("devLegacy"))
        assertFalse(f.contacts.acceptsReview("devLegacy"))

        val out = mutableListOf<Frame>()
        f.asOwner(ListReviewContacts, out)
        val items = out.filterIsInstance<ReviewContactsListing>().last().items
        assertTrue(items.none { it.id == "devLegacy" }, "a handoff contact is not a review contact, in either direction")
    }

    /** The Review Center's recipient picker and the send path must agree, and the daemon is the one that
     *  decides — a client re-deriving eligibility is how the two drift apart. */
    @Test
    fun only_review_contacts_are_offered_and_accepted_as_recipients() = fixture { f ->
        val out = mutableListOf<Frame>()
        f.asOwner(ListReviewContacts, out)
        val listing = out.filterIsInstance<ReviewContactsListing>().last().items
        assertEquals(
            listOf(Fixture.DEV_REVIEW),
            listing.filter { it.canSend }.map { it.id },
            "only the REVIEW contact may be picked",
        )
        assertTrue(listing.none { it.id == Fixture.DEV_PHONE }, "a handoff contact never appears in a review list")

        // and the send path refuses the handoff contact even when asked for it directly
        val refused = f.owner.resolveRecipient(Fixture.DEV_PHONE)
        assertTrue(refused is ReviewOwnerService.Outcome.Refused, "got $refused")
        assertEquals("review_no_recipient", (refused as ReviewOwnerService.Outcome.Refused).code)
    }

    /** Mixed versions: an old App drives the legacy frames and must never see, or be able to sever, a
     *  Review credential it cannot even render. */
    @Test
    fun the_legacy_collaborator_frames_never_expose_or_delete_a_review_link() = fixture { f ->
        val legacyListing = f.contacts.list().items
        assertEquals(listOf(Fixture.DEV_PHONE), legacyListing.map { it.deviceId }, "handoff rows only")

        val refused = f.contacts.remove(Fixture.DEV_REVIEW) // the legacy RemoveCollaborator path
        assertTrue(refused is PocketError, "an old App must not delete a review credential, got $refused")
        assertEquals("collaborator_not_found", (refused as PocketError).code)
        assertFalse(
            f.contacts.rows.first { it.deviceId == Fixture.DEV_REVIEW }.removed,
            "nothing may be mutated on the refused path",
        )
    }

    @Test
    fun an_unreadable_purpose_is_eligible_for_neither_feature() = fixture { f ->
        f.contacts.rows += Collaborator(
            deviceId = "devFuture", label = "from a newer peer", direction = CollaboratorDirection.OUTBOUND,
            connectedAt = 3, purpose = CollaboratorPurpose.UNKNOWN,
        )
        assertFalse(f.contacts.acceptsHandoff("devFuture"))
        assertFalse(f.contacts.acceptsReview("devFuture"))

        val out = mutableListOf<Frame>()
        f.asOwner(ListReviewContacts, out)
        val items = out.filterIsInstance<ReviewContactsListing>().last().items
        // a purpose this build cannot read is scoped OUT of both ledgers rather than shown unpickable:
        // there is no honest thing to say about a link whose scope nobody here can enforce
        assertTrue(items.none { it.id == "devFuture" })
        assertTrue(f.contacts.list().items.none { it.deviceId == "devFuture" })
    }
}
