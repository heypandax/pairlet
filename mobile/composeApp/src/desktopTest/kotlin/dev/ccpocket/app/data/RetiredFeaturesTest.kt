package dev.ccpocket.app.data

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.ccpocket.app.DeepLink
import dev.ccpocket.app.PushRoute
import dev.ccpocket.app.TEST_DAEMON_PUB
import dev.ccpocket.app.advanceFrameAndWait
import dev.ccpocket.app.pairing.IncomingLink
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.RetiredFeature
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.status_feature_retired
import dev.ccpocket.app.telemetry.TelEvent
import dev.ccpocket.app.telemetry.TelKey
import dev.ccpocket.app.telemetry.telemetryTap
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.ChatScreen
import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Collaborator
import dev.ccpocket.protocol.CollaboratorConnected
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorListing
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.CollaboratorTicketCreated
import dev.ccpocket.protocol.CollaboratorUpdated
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HandoffCreated
import dev.ccpocket.protocol.HandoffListing
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.HandoffUpdated
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ReviewContact
import dev.ccpocket.protocol.ReviewContactUpdated
import dev.ccpocket.protocol.ReviewContactsListing
import dev.ccpocket.protocol.ReviewInboxActed
import dev.ccpocket.protocol.ReviewInboxItem
import dev.ccpocket.protocol.ReviewInboxListing
import dev.ccpocket.protocol.ReviewInviteCreated
import dev.ccpocket.protocol.ReviewListing
import dev.ccpocket.protocol.ReviewPrepared
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewRequestCreated
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.ReviewUpdated
import dev.ccpocket.protocol.SessionHandoff
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.inviteUriPrefix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Features the App has retired (2026-10): ReviewRequest, Session Handoff and Collaborator Links. What is left
 * of them on this side is only how the App behaves when the outside world still speaks them:
 *
 *  - a `ccpocket://review-contact#…`, `ccpocket://collab#…` or `ccpocket://handoff?…` link (a QR on a
 *    colleague's screen, an old chat message) says the feature has been retired — and it is NOT a failed
 *    pairing: no failure card, no `pair_failed`;
 *  - a tapped Session Handoff offer push (an older daemon's, carrying only `hid`) lands on the same notice;
 *  - an older daemon may still push those features' frames; they are dropped without a trace;
 *  - this build itself never asks for any of them — opening a session sends no handoff/collaborator request.
 *
 * Unconfined makes `handle()` synchronous, so no daemon and no clock are needed.
 */
class RetiredFeaturesTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val seen = mutableListOf<Pair<TelEvent, Map<TelKey, Any>>>()

    @BeforeTest fun tap() {
        seen.clear()
        telemetryTap = { e, p -> synchronized(seen) { seen += e to p } }
        DeepLink.pending.value = null
    }

    @AfterTest fun tearDown() {
        telemetryTap = null
        DeepLink.pending.value = null
        scope.cancel()
    }

    private fun repo() = PocketRepository(scope)

    /** What an older daemon published for an invite: the purpose's own door + the JSON as base64url. The App
     *  no longer has this codec — the test builds the exact bytes a colleague's screen would still show. */
    private fun CollaboratorInvite.legacyUri(): String =
        inviteUriPrefix(purpose) +
            B64Url.encode(PocketJson.encodeToString(CollaboratorInvite.serializer(), this).encodeToByteArray())

    private val reviewInvite = CollaboratorInvite(
        relay = "wss://relay.test", accountId = "acct-frank", daemonPub = TEST_DAEMON_PUB,
        ticket = "ONE-TIME-TICKET", ownerLabel = "Frank", purpose = CollaboratorPurpose.REVIEW,
    )

    private val collabInvite = reviewInvite.copy(purpose = CollaboratorPurpose.SESSION_HANDOFF)

    /** The shared expectation: the retired notice, and nothing a pairing attempt would leave behind. */
    private fun assertRetiredNotice(r: PocketRepository, raw: String) {
        assertEquals(StatusMsg(Res.string.status_feature_retired), r.status.value, raw)
        assertNull(r.pairFailure.value, "a retired feature is not a failed pairing ($raw)")
        assertEquals(0, r.pairFailureSeq.value, "no failure card was armed ($raw)")
        assertTrue(
            synchronized(seen) { seen.none { it.first == TelEvent.PairFailed || it.first == TelEvent.PairStarted } },
            "no pairing attempt and no pair_failed for a retired link, saw $seen",
        )
        assertNull(r.pendingShareInvite.value, "nothing parks at the share preview ($raw)")
    }

    @Test
    fun aReviewContactLinkSaysRetiredAndIsNotAFailedPairing() {
        // a well-formed invite and a corrupt one alike: the host decides, the payload is never read
        for (raw in listOf(reviewInvite.legacyUri(), "ccpocket://review-contact#!!!not-base64!!!")) {
            synchronized(seen) { seen.clear() }
            val r = repo()

            val link = r.handleIncomingLink(raw)

            assertEquals(IncomingLink.Retired(RetiredFeature.REVIEW), link, raw)
            assertEquals(StatusMsg(Res.string.status_feature_retired), r.status.value, raw)
            assertNull(r.pairFailure.value, "a retired feature is not a failed pairing ($raw)")
            assertEquals(0, r.pairFailureSeq.value, "no failure card was armed ($raw)")
            assertTrue(
                synchronized(seen) { seen.none { it.first == TelEvent.PairFailed || it.first == TelEvent.PairStarted } },
                "no pairing attempt and no pair_failed for a retired link, saw $seen",
            )
            assertNull(r.pendingShareInvite.value, "…or at the share preview ($raw)")
        }

        // control: the same seam DOES see an unroutable link's pair_failed, so the silence above is real
        synchronized(seen) { seen.clear() }
        repo().handleIncomingLink("ccpocket://whatever?x=1")
        assertTrue(synchronized(seen) { seen.any { it.first == TelEvent.PairFailed } }, "control: saw $seen")
    }

    @Test
    fun collaboratorAndHandoffLinksSayRetiredAndAreNotAFailedPairing() {
        val cases = listOf(
            // a well-formed connect ticket and a corrupt one: the host decides, the payload is never read
            collabInvite.legacyUri() to RetiredFeature.COLLABORATOR,
            "ccpocket://collab#!!!not-base64!!!" to RetiredFeature.COLLABORATOR,
            "pairlet://collab#whatever" to RetiredFeature.COLLABORATOR,
            // an offer link with an id, with a garbage id, and with none at all
            "ccpocket://handoff?id=ho_123" to RetiredFeature.HANDOFF,
            "ccpocket://handoff?id=%%%" to RetiredFeature.HANDOFF,
            "ccpocket://handoff" to RetiredFeature.HANDOFF,
        )
        for ((raw, feature) in cases) {
            synchronized(seen) { seen.clear() }
            val r = repo()

            val link = r.handleIncomingLink(raw)

            assertEquals(IncomingLink.Retired(feature), link, raw)
            assertRetiredNotice(r, raw)
        }
        // the explicit paste field included: a pasted collaborator link is still the retired notice
        synchronized(seen) { seen.clear() }
        val pasted = repo()
        assertEquals(
            IncomingLink.Retired(RetiredFeature.COLLABORATOR),
            pasted.handleIncomingLink(collabInvite.legacyUri(), allowBareBlob = true),
        )
        assertRetiredNotice(pasted, "pasted collab link")
    }

    /**
     * The native entry points (Android `MainActivity`, iOS `handlePushOpenHandoff`) still hand an older
     * daemon's offer push — its `hid` — to [PushRoute.openHandoff]. It rides the ordinary OS-link path
     * ([DeepLink.pending], which the consent-gated root effect feeds to `handleIncomingLink`) and ends on
     * the retired notice: nothing opened, nothing paired, no failure recorded.
     */
    @Test
    fun aHandoffOfferPushTapLandsOnTheRetiredNotice() {
        PushRoute.openHandoff("ho_123")
        val routed = DeepLink.pending.value
        assertEquals("ccpocket://handoff", routed, "the tap becomes the retired link, the id is never used")
        assertNull(PushRoute.pending.value, "it never routes into a session")

        // what ConsentGatedLaunchEffects does with a pending link
        val r = repo()
        assertEquals(IncomingLink.Retired(RetiredFeature.HANDOFF), r.handleIncomingLink(routed!!))
        assertRetiredNotice(r, routed)

        // an empty id (a malformed push) routes nowhere at all, exactly as before
        DeepLink.pending.value = null
        PushRoute.openHandoff("")
        assertNull(DeepLink.pending.value)
    }

    /**
     * An older daemon still pushes ReviewRequest frames to its owner devices (a peer's review landing, a
     * listing answering an old client). This build has no review state left, so each one must fall into
     * `handle()`'s silent `else` — no crash, no chat line, no status, no failure card. Every frame goes
     * through the wire codec first: the protocol types are kept precisely so an old daemon's JSON still
     * DECODES here instead of failing the whole inbound stream.
     */
    @Test
    fun reviewFramesFromAnOlderDaemonAreDroppedSilently() {
        val row = ReviewRequest(id = "rr_1", title = "Retry race", status = ReviewStatus.DELIVERED, revision = 2)
        assertDroppedSilently(
            listOf(
                ReviewListing(listOf(row)),
                ReviewUpdated(row),
                ReviewInboxListing(listOf(ReviewInboxItem(linkId = "pl_1", peerLabel = "Frank", request = row))),
                ReviewContactsListing(listOf(ReviewContact(id = "dev-frank", label = "Frank"))),
                ReviewRequestCreated(ok = true, request = row),
                ReviewInviteCreated(ok = true, invite = reviewInvite.legacyUri(), ttlSec = 600),
                ReviewContactUpdated(ok = false, error = "no such contact"),
                ReviewPrepared(ok = false, error = "not found"),
                ReviewInboxActed(ok = true, requestId = "rr_1", queued = true),
            ),
        )
    }

    /**
     * Same rule for Session Handoff and Collaborator Links: an older daemon fans `HandoffUpdated` /
     * `CollaboratorUpdated` out to every owner device, and answers an older client's requests with listings.
     * A WAITING handoff on the open session used to lock the composer — here it must change nothing at all.
     */
    @Test
    fun handoffAndCollaboratorFramesFromAnOlderDaemonAreDroppedSilently() {
        val ho = SessionHandoff("ho_1", "s1", "/w", status = HandoffStatus.WAITING, initiatorDeviceId = "dev-other")
        val frank = Collaborator("dev-frank", "Frank")
        assertDroppedSilently(
            listOf(
                HandoffListing(listOf(ho)),
                HandoffUpdated(ho.copy(status = HandoffStatus.IN_PROGRESS, recipientDeviceId = "dev-frank")),
                HandoffCreated(ok = true, handoff = ho),
                HandoffCreated(ok = false, error = "handoffs are not available on this daemon"),
                CollaboratorListing(listOf(frank)),
                CollaboratorUpdated(frank),
                CollaboratorConnected(frank),
                CollaboratorTicketCreated(ok = true, invite = collabInvite),
            ),
            seed = { receiveForTest(SessionLive("c1", "/w", "s1", executing = false)); connected.value = true },
        )
    }

    private fun assertDroppedSilently(frames: List<Frame>, seed: PocketRepository.() -> Unit = {}) {
        val r = repo().apply(seed)
        val statusBefore = r.status.value
        val messagesBefore = r.messages.toList()
        val sendRefusalBefore = r.convoId.value?.let(r::memoSendRefusal)

        for (f in frames) {
            val wire = PocketJson.decodeFromString(Frame.serializer(), PocketJson.encodeToString(Frame.serializer(), f))
            assertEquals(f, wire, "an old daemon's ${f::class.simpleName} still decodes")
            r.receiveForTest(wire)
        }

        assertEquals(messagesBefore, r.messages.toList(), "no chat line for a retired feature's frame")
        assertEquals(statusBefore, r.status.value, "…and no status line either")
        assertNull(r.pairFailure.value)
        r.convoId.value?.let { assertEquals(sendRefusalBefore, r.memoSendRefusal(it), "…and no send gate") }
    }

    /**
     * The open chat used to send `ListHandoffs(sessionId)` every time a session opened (a `LaunchedEffect` in
     * the chat screen), and the handoff sheets pulled `ListCollaborators`. Compose the real chat screen over a
     * real repository, open a session, and look at every outbound frame: none may belong to a retired feature.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun openingASessionSendsNoHandoffOrCollaboratorRequest() = runDesktopComposeUiTest(390, 844) {
        val sent = CopyOnWriteArrayList<Frame>()
        setContent {
            val uiScope = rememberCoroutineScope()
            val repo = remember {
                PocketRepository(
                    uiScope,
                    PairedDaemon(relay = "wss://test.invalid", accountId = "acct", daemonPub = "pub", deviceId = "dev", credential = "cred"),
                ).apply {
                    onSendForTest = { sent += it }
                    receiveForTest(Directories(listOf(DirectoryEntry(path = "/w", name = "w", isDir = true))))
                    openSession("/w", "s1", agent = AgentKind.CLAUDE)
                    receiveForTest(SessionLive("c1", "/w", "s1", mode = PermissionMode.DEFAULT, executing = false))
                    connected.value = true
                }
            }
            PocketTheme { ChatScreen(repo) }
        }
        advanceFrameAndWait()
        waitForIdle()

        assertTrue(sent.any { it is OpenSession }, "control: the open itself was observed, saw $sent")
        val retired = sent.filter {
            val name = it::class.simpleName.orEmpty()
            "Handoff" in name || "Collaborator" in name
        }
        assertTrue(retired.isEmpty(), "no handoff/collaborator request may leave this build, sent $retired")
    }
}
