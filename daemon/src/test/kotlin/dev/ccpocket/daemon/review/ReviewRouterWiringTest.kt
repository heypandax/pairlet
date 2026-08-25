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
import dev.ccpocket.daemon.handoff.CollaboratorDirectory
import dev.ccpocket.daemon.handoff.CollaboratorGuard
import dev.ccpocket.daemon.handoff.CollaboratorScope
import dev.ccpocket.daemon.handoff.HandoffRegistry
import dev.ccpocket.daemon.handoff.HandoffService
import dev.ccpocket.daemon.handoff.HandoffStore
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.server.RequestRouter
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AcknowledgeReviewRequest
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ArtifactKind
import dev.ccpocket.protocol.ArtifactRef
import dev.ccpocket.protocol.CancelReviewRequest
import dev.ccpocket.protocol.CloseReviewRequest
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.CreateHandoff
import dev.ccpocket.protocol.CreateReviewRequest
import dev.ccpocket.protocol.DeclineReviewRequest
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.GetReviewRequest
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListReviewRequests
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.MarkReviewDelivered
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.RespondReviewRequest
import dev.ccpocket.protocol.ReviewBrief
import dev.ccpocket.protocol.ReviewListing
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewRequestCreated
import dev.ccpocket.protocol.ReviewResult
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.ReviewVerdict
import dev.ccpocket.protocol.ReviewUpdated
import dev.ccpocket.protocol.StartReviewRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The ReviewRequest ingress, end to end through the real gates (REVIEW-REQUEST.md §11.1):
 * [CollaboratorCaps] type admission → [CollaboratorGuard] vet → [RequestRouter] dispatch, exactly as
 * [dev.ccpocket.daemon.relay.DeviceSessions] drives them.
 *
 * What must hold: a collaborator can answer ITS OWN requests and nothing else; it can never create,
 * cancel or close one; and admitting this plane must not have widened its access to sessions, folders
 * or the owner's management surface by one frame.
 */
class ReviewRouterWiringTest {

    private class StubBackend : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun listSessions(workdir: String) = emptyList<dev.ccpocket.protocol.SessionSummary>()
        override fun processBuilder(spec: AgentSpec) = throw UnsupportedOperationException()
        override suspend fun attach(io: AgentIo, spec: AgentSpec) = throw UnsupportedOperationException()
        override suspend fun parse(line: String): Nothing = throw UnsupportedOperationException()
        override suspend fun sendPrompt(text: String, images: List<ImageData>) = throw UnsupportedOperationException()
        override suspend fun interrupt() = throw UnsupportedOperationException()
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) = throw UnsupportedOperationException()
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = false
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = throw UnsupportedOperationException()
        override fun replayHistory(workdir: String, sessionId: String) = emptyList<HistoryMessage>()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    /** Two live REVIEW contacts, so a request can be addressed to one of them. Only a Review-purpose
     *  link is an eligible recipient (REVIEW-REQUEST.md §13.3), which is what [acceptsReview] answers. */
    private class Contacts(private val live: Set<String>) : CollaboratorDirectory {
        override fun labelOf(deviceId: String) = if (deviceId in live) "Frank" else null
        override fun isActive(deviceId: String) = deviceId in live
        override fun acceptsReview(deviceId: String) = deviceId in live
        override fun acceptsHandoff(deviceId: String) = false
        override suspend fun noteHandoff(deviceId: String, at: Long) {}
    }

    private class Fixture(scope: CoroutineScope) {
        var now = 10_000L
        private val tmp = Files.createTempDirectory("ccp-review-wiring").toFile()
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { StubBackend() }))
        val handoffs = HandoffService(HandoffRegistry(HandoffStore.load(tmp.resolve("handoffs.json")), clock = { now }))
        val reviews = ReviewService(
            ReviewRegistry(ReviewStore.load(tmp.resolve("reviews.json")), clock = { now }),
        ).also { it.collaborators = Contacts(setOf(DEV_B, DEV_C)) }
        val router = RequestRouter(
            registry = registry,
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
        )

        init { registry.handoffs = handoffs }

        /** Route as a FULL-POWER owner device. */
        suspend fun asOwner(frame: Frame, into: MutableList<Frame>) =
            router.handle(frame, { into += it }, deviceId = "devA")

        /** Route the way DeviceSessions does for a SESSION HANDOFF collaborator credential. */
        suspend fun asHandoffCollaborator(frame: Frame, deviceId: String, into: MutableList<Frame>) =
            asCollaborator(frame, deviceId, into, CollaboratorPurpose.SESSION_HANDOFF)

        /** Route the way DeviceSessions does for a COLLABORATOR credential: caps gate → guard → router.
         *  Defaults to a REVIEW link, which is what this file is about. */
        suspend fun asCollaborator(
            frame: Frame,
            deviceId: String,
            into: MutableList<Frame>,
            purpose: CollaboratorPurpose = CollaboratorPurpose.REVIEW,
        ) {
            if (!CollaboratorCaps.ingressAllowed(frame, purpose)) {
                into += PocketError("collaborator_forbidden", "not permitted for a collaborator link: ${frame::class.simpleName}")
                return
            }
            val guard = handoffs.collaboratorGuard(deviceId)
            when (val v = guard.vet(frame, now)) {
                is CollaboratorGuard.Verdict.Deny -> into += PocketError(v.code, v.message)
                is CollaboratorGuard.Verdict.Allow ->
                    router.handle(v.frame, { into += it }, deviceId = deviceId, collabScope = CollaboratorScope(deviceId, v.pathScope))
            }
        }

        /** Route as a BRIDGE (origin) and as a GUEST (guestScope) — the other two restricted classes. */
        suspend fun asBridge(frame: Frame, into: MutableList<Frame>) =
            router.handle(frame, { into += it }, origin = "feishu-bot", deviceId = "devBridge")

        suspend fun asGuest(frame: Frame, into: MutableList<Frame>) =
            router.handle(frame, { into += it }, guestScope = GuestScope(emptyList(), emptySet(), "guest", null, dev.ccpocket.protocol.AccessTier.REVIEW), deviceId = "devGuest")

        suspend fun send(into: MutableList<Frame>, to: String = DEV_B): ReviewRequest {
            asOwner(
                CreateReviewRequest(
                    recipientDeviceId = to, title = "the ACK path",
                    brief = ReviewBrief(request = "check the retry race"),
                    artifacts = listOf(ArtifactRef(ArtifactKind.MERGE_REQUEST, url = "https://git.example/mr/1")),
                ),
                into,
            )
            val created = into.filterIsInstance<ReviewRequestCreated>().last()
            assertTrue(created.ok, "create must succeed: ${created.error}")
            return assertNotNull(created.request)
        }

        companion object {
            const val DEV_B = "devB"
            const val DEV_C = "devC"
        }
    }

    private fun <T> fixture(block: suspend (Fixture) -> T): T = runBlocking {
        block(Fixture(this))
    }

    private fun errors(frames: List<Frame>) = frames.filterIsInstance<PocketError>()

    // ---- owner plane ------------------------------------------------------

    @Test
    fun an_owner_creates_lists_and_closes_its_own_requests() = fixture { f ->
        val out = mutableListOf<Frame>()
        val r = f.send(out)
        assertEquals(ReviewStatus.QUEUED, r.status)

        f.asOwner(ListReviewRequests(), out)
        assertEquals(listOf(r.id), out.filterIsInstance<ReviewListing>().last().items.map { it.id })

        // an owner sees a request addressed to ANY of its contacts
        val r2 = f.send(out, to = Fixture.DEV_C)
        f.asOwner(ListReviewRequests(), out)
        assertEquals(setOf(r.id, r2.id), out.filterIsInstance<ReviewListing>().last().items.mapTo(HashSet()) { it.id })

        f.asOwner(CancelReviewRequest(r2.id), out)
        assertEquals(ReviewStatus.CANCELLED, out.filterIsInstance<ReviewUpdated>().last().request.status)
    }

    @Test
    fun create_refuses_a_contact_that_is_not_a_live_link() = fixture { f ->
        val out = mutableListOf<Frame>()
        f.asOwner(
            CreateReviewRequest(
                recipientDeviceId = "devGone", title = "t", brief = ReviewBrief(request = "x"),
                artifacts = listOf(ArtifactRef(ArtifactKind.MERGE_REQUEST, url = "https://git.example/mr/1")),
            ),
            out,
        )
        val created = out.filterIsInstance<ReviewRequestCreated>().last()
        assertFalse(created.ok)
        assertEquals("review_no_recipient", created.code)
    }

    // ---- the recipient plane ----------------------------------------------

    @Test
    fun a_collaborator_drives_only_its_own_request_through_the_whole_lifecycle() = fixture { f ->
        val owner = mutableListOf<Frame>()
        val r = f.send(owner)

        val b = mutableListOf<Frame>()
        f.asCollaborator(MarkReviewDelivered(r.id, "k1"), Fixture.DEV_B, b)
        assertEquals(ReviewStatus.DELIVERED, b.filterIsInstance<ReviewUpdated>().last().request.status)
        f.asCollaborator(AcknowledgeReviewRequest(r.id, "k2"), Fixture.DEV_B, b)
        f.asCollaborator(StartReviewRequest(r.id, "k3"), Fixture.DEV_B, b)
        f.asCollaborator(RespondReviewRequest(r.id, ReviewResult(ReviewVerdict.REQUEST_CHANGES, "two blockers"), "k4"), Fixture.DEV_B, b)
        val responded = b.filterIsInstance<ReviewUpdated>().last().request
        assertEquals(ReviewStatus.RESPONDED, responded.status)
        assertEquals(Fixture.DEV_B, responded.result!!.respondedByDeviceId, "the daemon stamps who answered")
        assertTrue(errors(b).isEmpty(), "the happy path must produce no errors: ${errors(b)}")

        // …and only the OWNER closes it
        f.asCollaborator(CloseReviewRequest(r.id), Fixture.DEV_B, b)
        assertEquals("collaborator_forbidden", errors(b).last().code)
        f.asOwner(CloseReviewRequest(r.id), owner)
        assertEquals(ReviewStatus.CLOSED, owner.filterIsInstance<ReviewUpdated>().last().request.status)
    }

    @Test
    fun a_collaborator_cannot_touch_another_contacts_request() = fixture { f ->
        val owner = mutableListOf<Frame>()
        val forC = f.send(owner, to = Fixture.DEV_C)

        val b = mutableListOf<Frame>()
        f.asCollaborator(MarkReviewDelivered(forC.id, "k"), Fixture.DEV_B, b)
        assertEquals("review_not_allowed", errors(b).last().code)

        // and a get for it is indistinguishable from a get for an id that never existed
        f.asCollaborator(GetReviewRequest(forC.id), Fixture.DEV_B, b)
        val foreign = errors(b).last()
        f.asCollaborator(GetReviewRequest("rr_never_existed"), Fixture.DEV_B, b)
        val missing = errors(b).last()
        assertEquals(foreign.code, missing.code)
        assertEquals(foreign.message, missing.message, "no probe oracle for the owner's ledger")
    }

    @Test
    fun a_collaborators_listing_is_filtered_to_its_own_requests() = fixture { f ->
        val owner = mutableListOf<Frame>()
        val forB = f.send(owner, to = Fixture.DEV_B)
        f.send(owner, to = Fixture.DEV_C)

        val b = mutableListOf<Frame>()
        f.asCollaborator(ListReviewRequests(), Fixture.DEV_B, b)
        assertEquals(listOf(forB.id), b.filterIsInstance<ReviewListing>().last().items.map { it.id })
    }

    @Test
    fun the_initiator_side_operations_are_denied_to_every_restricted_credential() = fixture { f ->
        val owner = mutableListOf<Frame>()
        val r = f.send(owner)

        // a collaborator is refused at the CAPS gate — the frame type never reaches the router
        listOf(
            CreateReviewRequest("devZ", "t", ReviewBrief(request = "x")),
            CancelReviewRequest(r.id),
            CloseReviewRequest(r.id),
        ).forEach { frame ->
            assertFalse(
                CollaboratorCaps.ingressAllowed(frame, CollaboratorPurpose.REVIEW),
                "${frame::class.simpleName} must not be admitted",
            )
            val b = mutableListOf<Frame>()
            f.asCollaborator(frame, Fixture.DEV_B, b)
            assertEquals("collaborator_forbidden", errors(b).last().code)
        }

        // a bridge and a guest are refused by the router itself (defence in depth: their own caps deny
        // these types at the choke point too)
        val bridge = mutableListOf<Frame>()
        f.asBridge(CancelReviewRequest(r.id), bridge)
        assertEquals("review_forbidden", errors(bridge).last().code)
        f.asBridge(MarkReviewDelivered(r.id, "k"), bridge)
        assertEquals("review_forbidden", errors(bridge).last().code)

        val guest = mutableListOf<Frame>()
        f.asGuest(CreateReviewRequest("devB", "t", ReviewBrief(request = "x")), guest)
        assertEquals("review_forbidden", errors(guest).last().code)
        f.asGuest(ListReviewRequests(), guest)
        assertEquals("review_forbidden", errors(guest).last().code)
    }

    /** An OWNER device is not a recipient of its own machine's requests — there is nobody to answer as. */
    @Test
    fun an_owner_device_cannot_answer_a_request_on_the_recipients_behalf() = fixture { f ->
        val out = mutableListOf<Frame>()
        val r = f.send(out)
        f.asOwner(MarkReviewDelivered(r.id, "k"), out)
        assertEquals("review_forbidden", errors(out).last().code)
        assertEquals(ReviewStatus.QUEUED, f.reviews.registry.byId(r.id)!!.status)
    }

    // ---- the plane did not widen anything ---------------------------------

    @Test
    fun admitting_the_review_plane_did_not_grant_a_collaborator_discovery_or_a_session() = fixture { f ->
        val b = mutableListOf<Frame>()
        // discovery + session escalation attempts, all still denied at the caps gate
        listOf(
            ListDirectories(),
            ListSessions("/tmp"),
            CreateHandoff("/tmp", "sess", dev.ccpocket.protocol.HandoffBrief(request = "x")),
        ).forEach { frame ->
            assertFalse(
                CollaboratorCaps.ingressAllowed(frame, CollaboratorPurpose.REVIEW),
                "${frame::class.simpleName} must stay denied",
            )
        }
        // The granted-session surface belongs to the feature that GRANTS a session, and a Review link
        // never gets one — so it is refused at the capability gate, one step before the guard.
        assertFalse(
            CollaboratorCaps.ingressAllowed(OpenSession(workdir = "/tmp", resumeId = "sess-1"), CollaboratorPurpose.REVIEW),
            "a review link must not even be considered for a session frame",
        )
        f.asCollaborator(OpenSession(workdir = "/tmp", resumeId = "sess-1"), Fixture.DEV_B, b)
        assertEquals("collaborator_forbidden", errors(b).last().code)

        // …and for a HANDOFF link the type IS admitted, leaving the guard to demand an actual grant.
        // Both gates are load-bearing; neither is the whole argument.
        assertTrue(CollaboratorCaps.ingressAllowed(OpenSession(workdir = "/tmp", resumeId = "sess-1"), CollaboratorPurpose.SESSION_HANDOFF))
        val handoffLink = mutableListOf<Frame>()
        f.asHandoffCollaborator(OpenSession(workdir = "/tmp", resumeId = "sess-1"), Fixture.DEV_B, handoffLink)
        assertEquals("handoff_grant_required", errors(handoffLink).last().code)
    }

    @Test
    fun the_egress_whitelist_admits_review_replies_and_nothing_new_besides() {
        val review = CollaboratorPurpose.REVIEW
        assertTrue(CollaboratorCaps.egressAllowed(ReviewUpdated(ReviewRequest(id = "rr_1")), review))
        assertTrue(CollaboratorCaps.egressAllowed(ReviewListing(), review))
        // still denied: the owner's identity/management/discovery frames
        assertFalse(CollaboratorCaps.egressAllowed(dev.ccpocket.protocol.DaemonInfo(), review))
        assertFalse(CollaboratorCaps.egressAllowed(dev.ccpocket.protocol.Directories(emptyList()), review))
        assertFalse(CollaboratorCaps.egressAllowed(dev.ccpocket.protocol.CollaboratorListing(), review))
        assertFalse(CollaboratorCaps.egressAllowed(ReviewRequestCreated(ok = true), review))
    }

    /** The fan-out instance filter, not just the type gate: a sink bound to one contact must never see
     *  another contact's brief. */
    @Test
    fun fanout_reaches_only_the_bound_recipient_sink() = fixture { f ->
        val toB = mutableListOf<Frame>()
        val toC = mutableListOf<Frame>()
        val toOwner = mutableListOf<Frame>()
        f.reviews.attach({ toB += it }, recipientDeviceId = Fixture.DEV_B)
        f.reviews.attach({ toC += it }, recipientDeviceId = Fixture.DEV_C)
        f.reviews.attach({ toOwner += it })

        val r = f.send(mutableListOf())
        assertEquals(listOf(r.id), toB.filterIsInstance<ReviewUpdated>().map { it.request.id })
        assertTrue(toC.isEmpty(), "a contact must not learn about another contact's request")
        assertEquals(listOf(r.id), toOwner.filterIsInstance<ReviewUpdated>().map { it.request.id })
    }

    @Test
    fun a_repeat_answers_the_caller_but_is_not_re_announced_as_news() = fixture { f ->
        val watchers = mutableListOf<Frame>()
        val r = f.send(mutableListOf())
        f.reviews.attach({ watchers += it })

        val b = mutableListOf<Frame>()
        f.asCollaborator(MarkReviewDelivered(r.id, "same"), Fixture.DEV_B, b)
        val afterFirst = watchers.size
        f.asCollaborator(MarkReviewDelivered(r.id, "same"), Fixture.DEV_B, b)
        assertEquals(2, b.filterIsInstance<ReviewUpdated>().size, "the caller always gets the authoritative row")
        assertEquals(afterFirst, watchers.size, "…but a replay is not a new transition to broadcast")
    }

    @Test
    fun a_decline_reason_rides_through_the_router_onto_the_authoritative_row() = fixture { f ->
        val r = f.send(mutableListOf())
        val b = mutableListOf<Frame>()
        f.asCollaborator(MarkReviewDelivered(r.id, "k1"), Fixture.DEV_B, b)
        f.asCollaborator(DeclineReviewRequest(r.id, "not my module", "k2"), Fixture.DEV_B, b)
        val row = b.filterIsInstance<ReviewUpdated>().last().request
        assertEquals(ReviewStatus.DECLINED, row.status)
        assertEquals("not my module", row.declineReason)
    }

    /** A router with no review plane wired must answer honestly rather than silently doing nothing. */
    @Test
    fun a_daemon_without_the_review_plane_says_so() = runBlocking {
        val tmp = Files.createTempDirectory("ccp-review-none").toFile()
        val router = RequestRouter(
            registry = SessionRegistry(this, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { StubBackend() })),
            dirs = DirectoryService(),
            transcribe = TranscribeService(this) { null },
            inbox = FileInboxService { null },
            shell = ShellService(this),
            exports = FileExportService(this, { null }),
            scope = this,
            auth = AuthService(this, { emptyList() }, { 0 }),
            prefs = DaemonPrefs.load(tmp.resolve("prefs.json")),
            presets = PresetService(PresetStore.load(tmp.resolve("presets.json")), { emptyList() }, { 0 }),
            scheduler = dev.ccpocket.daemon.schedule.SchedulerService(
                dev.ccpocket.daemon.schedule.ScheduleStore.load(tmp.resolve("schedules.json")),
                executor = { null },
            ),
        )
        val out = mutableListOf<Frame>()
        router.handle(CreateReviewRequest("devB", "t", ReviewBrief(request = "x")), { out += it }, deviceId = "devA")
        assertEquals("review_unavailable", out.filterIsInstance<ReviewRequestCreated>().last().code)
        router.handle(ListReviewRequests(), { out += it }, deviceId = "devA")
        assertTrue(out.filterIsInstance<ReviewListing>().last().items.isEmpty())
    }
}
