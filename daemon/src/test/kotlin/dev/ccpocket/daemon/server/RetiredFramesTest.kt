package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.relay.isOwnerControlFrame
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AcceptHandoff
import dev.ccpocket.protocol.AcknowledgeReviewRequest
import dev.ccpocket.protocol.ActOnReviewInbox
import dev.ccpocket.protocol.CancelHandoff
import dev.ccpocket.protocol.CancelReviewRequest
import dev.ccpocket.protocol.CloseReviewRequest
import dev.ccpocket.protocol.CollaboratorListing
import dev.ccpocket.protocol.CompleteHandoff
import dev.ccpocket.protocol.CreateCollaboratorTicket
import dev.ccpocket.protocol.CreateHandoff
import dev.ccpocket.protocol.CreateReviewInvite
import dev.ccpocket.protocol.CreateReviewRequest
import dev.ccpocket.protocol.CreateShare
import dev.ccpocket.protocol.DeclineHandoff
import dev.ccpocket.protocol.DeclineReviewRequest
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.GetReviewRequest
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.HandoffListing
import dev.ccpocket.protocol.JoinReviewContact
import dev.ccpocket.protocol.ListCollaborators
import dev.ccpocket.protocol.ListHandoffs
import dev.ccpocket.protocol.ListReviewContacts
import dev.ccpocket.protocol.ListReviewInbox
import dev.ccpocket.protocol.ListReviewRequests
import dev.ccpocket.protocol.ListShares
import dev.ccpocket.protocol.MarkReviewDelivered
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.PrepareReviewRequest
import dev.ccpocket.protocol.RecallHandoff
import dev.ccpocket.protocol.RemoveCollaborator
import dev.ccpocket.protocol.RemoveReviewContact
import dev.ccpocket.protocol.RespondReviewRequest
import dev.ccpocket.protocol.ReturnHandoff
import dev.ccpocket.protocol.ReviewBrief
import dev.ccpocket.protocol.ReviewContactsListing
import dev.ccpocket.protocol.ReviewInboxListing
import dev.ccpocket.protocol.ReviewListing
import dev.ccpocket.protocol.ReviewResult
import dev.ccpocket.protocol.RevokeShare
import dev.ccpocket.protocol.ShareListing
import dev.ccpocket.protocol.StartReviewRequest
import dev.ccpocket.protocol.ToDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Review requests, session handoff, collaborator contacts and folder sharing are retired. An older App or an
 * older peer daemon may still send any `pocket/review.*`, `pocket/handoff.*`, `pocket/collaborator.*` or
 * `pocket/share.*` request (a share request from an owner device — a guest's frames never reach the router); each
 * one must be answered at once — the list requests with an empty list of their own reply type, everything
 * else with the router's ordinary `unsupported` error — so nobody waits out a timeout and an older App's
 * list screens (above all the `ListHandoffs` it sends on every session open) do not turn into chat error rows.
 */
class RetiredFramesTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun router(): RequestRouter {
        val tmp = Files.createTempDirectory("ccp-retired-frames").toFile()
        return RequestRouter(
            registry = SessionRegistry(scope, backends = emptyMap()),
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
        )
    }

    /** Every list request of a retired feature, with the empty reply it must get. */
    private val listRequests: List<Pair<ToDaemon, Frame>> = listOf(
        ListReviewRequests() to ReviewListing(),
        ListReviewInbox() to ReviewInboxListing(),
        ListReviewContacts to ReviewContactsListing(),
        ListHandoffs() to HandoffListing(),
        ListHandoffs(workdir = "/w", sessionId = "s-1") to HandoffListing(),
        ListCollaborators to CollaboratorListing(),
        ListShares to ShareListing(),
    )

    /** One instance of every other retired REQUEST frame the protocol still defines. */
    private val otherRequests: List<ToDaemon> = listOf(
        CreateReviewRequest("dev-1", "title", ReviewBrief("look at this")),
        GetReviewRequest("rr-1"),
        MarkReviewDelivered("rr-1"),
        AcknowledgeReviewRequest("rr-1"),
        StartReviewRequest("rr-1"),
        DeclineReviewRequest("rr-1", "busy"),
        RespondReviewRequest("rr-1", ReviewResult(summary = "fine")),
        CancelReviewRequest("rr-1"),
        CloseReviewRequest("rr-1"),
        CreateReviewInvite("Frank"),
        JoinReviewContact("ccpocket://review-contact#x", "Frank"),
        RemoveReviewContact("c-1"),
        PrepareReviewRequest("rr-1"),
        ActOnReviewInbox("rr-1"),
        CreateHandoff("/w", "s-1", HandoffBrief("take a look"), recipientDeviceId = "dev-c"),
        AcceptHandoff("h-1"),
        DeclineHandoff("h-1", "busy"),
        CancelHandoff("h-1"),
        RecallHandoff("h-1"),
        ReturnHandoff("h-1"),
        CompleteHandoff("h-1"),
        CreateCollaboratorTicket("Frank"),
        RemoveCollaborator("dev-c"),
        CreateShare("/w"),
        RevokeShare("dev-g"),
    )

    private fun wireName(f: Frame): String =
        PocketJson.encodeToJsonElement(Frame.serializer(), f).jsonObject["t"]!!.jsonPrimitive.content

    private val retiredPrefixes = listOf("pocket/review.", "pocket/handoff.", "pocket/collaborator.", "pocket/share.")

    @Test
    fun the_lists_cover_every_retired_request_the_protocol_defines() {
        // a retired request added to the protocol later must be added here too, or this test fails
        val desc = ToDaemon.serializer().descriptor.getElementDescriptor(1)
        val protocolNames = (0 until desc.elementsCount).map { desc.getElementName(it) }
            .filter { name -> retiredPrefixes.any { name.startsWith(it) } }.toSet()
        val covered = (listRequests.map { it.first } + otherRequests).map(::wireName).toSet()
        assertEquals(protocolNames, covered)
        assertEquals(17, covered.count { it.startsWith("pocket/review.") })
        assertEquals(8, covered.count { it.startsWith("pocket/handoff.") })
        assertEquals(3, covered.count { it.startsWith("pocket/collaborator.") })
        assertEquals(3, covered.count { it.startsWith("pocket/share.") })
    }

    @Test
    fun no_retired_request_is_taken_by_the_owner_control_planes_first() {
        // both transports offer an owner's frame to dispatchOwnerControl before the router; none of these may
        // stop there, or the LAN and relay paths would answer them differently from the router below
        for (f in listRequests.map { it.first } + otherRequests) assertFalse(isOwnerControlFrame(f), f::class.simpleName)
    }

    /** Every request is answered with exactly one frame, inline — no reply means a caller-side timeout. */
    private suspend fun replyTo(router: RequestRouter, frame: Frame, origin: String?): Frame {
        val out = mutableListOf<Frame>()
        withTimeout(5_000) { router.handle(frame, { synchronized(out) { out += it } }, origin = origin, deviceId = "dev-o") }
        return synchronized(out) { out.toList() }.single()
    }

    @Test
    fun list_requests_answer_an_empty_list_of_their_own_type(): Unit = runBlocking {
        val r = router()
        for (origin in listOf(null, "bridge-dev")) {
            for ((frame, empty) in listRequests) {
                val reply = replyTo(r, frame, origin)
                assertEquals(empty::class, reply::class, "${frame::class.simpleName}")
                assertEquals(empty, reply, "${frame::class.simpleName}")
            }
        }
    }

    @Test
    fun every_other_retired_request_answers_unsupported(): Unit = runBlocking {
        val r = router()
        for (origin in listOf(null, "bridge-dev")) {
            for (frame in otherRequests) {
                val err = assertIs<PocketError>(replyTo(r, frame, origin), "${frame::class.simpleName}")
                assertEquals("unsupported", err.code)
                assertEquals("frame not handled by daemon: ${frame::class.simpleName}", err.message)
                assertEquals(null, err.convoId)
            }
        }
    }
}
