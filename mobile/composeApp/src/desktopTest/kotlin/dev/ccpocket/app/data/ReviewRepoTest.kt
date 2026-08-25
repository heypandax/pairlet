package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.BindingRole
import dev.ccpocket.app.pairing.encode
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.ArtifactKind
import dev.ccpocket.protocol.ArtifactRef
import dev.ccpocket.protocol.CollaboratorDirection
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.PreparePeer
import dev.ccpocket.protocol.ReviewBrief
import dev.ccpocket.protocol.ReviewContact
import dev.ccpocket.protocol.ReviewContactsListing
import dev.ccpocket.protocol.ReviewExecutionBundle
import dev.ccpocket.protocol.ReviewInboxActed
import dev.ccpocket.protocol.ReviewInboxAction
import dev.ccpocket.protocol.ReviewInboxItem
import dev.ccpocket.protocol.ReviewInboxListing
import dev.ccpocket.protocol.ReviewInviteCreated
import dev.ccpocket.protocol.ReviewListing
import dev.ccpocket.protocol.ReviewPrepared
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewRequestCreated
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.ReviewUpdated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Review Center's repository mirror (REVIEW-REQUEST.md §12). Every list here is a FOLLOWER of a
 * daemon snapshot, and each test pins one of the three rules that keeps it from becoming a second source
 * of truth:
 *
 *  - a listing REPLACES wholesale — that is the only thing a reconnect can heal from, so an append would
 *    resurrect rows the daemon has already forgotten;
 *  - a single-row push UPSERTS under a revision guard — a late replay arriving after a newer transition
 *    must not walk a request backwards into a state the daemon left;
 *  - nothing is smoothed over: `queued` reaches the UI as queued, an unreadable state stays unreadable,
 *    and a refusal is the daemon's own words rather than a silent no-op.
 *
 * Unconfined makes `handle()` synchronous, so no daemon and no clock are needed.
 */
class ReviewRepoTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = scope.cancel()

    /** The role is explicit because it DECIDES what a listing means: only an owner connection's rows are
     *  this machine's sent ledger. `useRelay` routes outbound frames into the relay's buffering outbox
     *  instead of a live socket, and `sessionActive` stays false so a send that goes nowhere reads as an
     *  intentional teardown rather than a connection failure worth retrying. */
    private fun repo(role: BindingRole = BindingRole.OWNER) = PocketRepository(scope).apply {
        paired.value = PairedDaemon(
            relay = "wss://127.0.0.1:9", accountId = "acct-me", daemonPub = "pk",
            deviceId = "dev-me", credential = "cred", role = role,
        )
        useRelay = true
    }

    private fun req(
        id: String,
        status: ReviewStatus = ReviewStatus.QUEUED,
        revision: Long = 1,
        title: String = "Review $id",
        createdAt: Long = 1_000,
    ) = ReviewRequest(
        id = id,
        senderDeviceId = "dev-me", senderLabel = "Panda",
        recipientDeviceId = "dev-frank", recipientLabel = "Frank",
        title = title,
        brief = ReviewBrief(request = "Review the retry race in the ACK path"),
        artifacts = listOf(ArtifactRef(ArtifactKind.MERGE_REQUEST, url = "https://git.example.com/r/-/merge_requests/7")),
        status = status, revision = revision, createdAt = createdAt, updatedAt = createdAt,
    )

    private fun inbox(r: ReviewRequest, pending: List<String> = emptyList()) = ReviewInboxItem(
        linkId = "pl_frank", peerLabel = "Frank", peerFingerprint = "amber — anchor — cedar",
        request = r, pending = pending,
    )

    private fun bundle(requestId: String, prompt: String) = ReviewExecutionBundle(
        requestId = requestId,
        peer = PreparePeer("pl_frank", "Frank", "amber — anchor — cedar"),
        title = "Retry race", status = ReviewStatus.DELIVERED, revision = 1,
        brief = ReviewBrief(request = "Review the retry race in the ACK path"),
        artifacts = emptyList(), recommendedPrompt = prompt,
    )

    // ── §13.3: the two invite doors, at the app's front door ──────────────────────────────────────

    private val reviewInvite = dev.ccpocket.protocol.CollaboratorInvite(
        relay = "wss://relay.test", accountId = "acct-frank", daemonPub = dev.ccpocket.app.TEST_DAEMON_PUB,
        ticket = "ONE-TIME-TICKET", ownerLabel = "Frank", purpose = CollaboratorPurpose.REVIEW,
    )

    /**
     * A review-contact deep link PARKS. It does not redeem, and it does not land in the Session Handoff
     * confirm screen — that screen redeems into a binding on THIS PHONE, which would burn the one-time
     * ticket a colleague's daemon is waiting for and leave the owner with a contact that can never answer.
     */
    @Test
    fun aReviewContactLinkParksForTheReviewCenterAndRedeemsNothing() {
        val r = repo()
        val uri = reviewInvite.encode()

        val link = r.handleIncomingLink(uri)
        assertTrue(link is dev.ccpocket.app.pairing.IncomingLink.ReviewContact, "$link")
        assertEquals(uri, r.pendingReviewInvite.value, "the line is held verbatim — the DAEMON redeems it")
        assertNull(r.pendingCollabInvite.value, "a review ticket must never reach the phone-binding door")
        assertFalse(r.reviewJoining.value, "arriving through a link is not consent — nothing was sent")
    }

    /** …and the Session Handoff door is untouched by the split: an ordinary collab link still parks in
     *  the fingerprint confirm screen it always did. */
    @Test
    fun anOrdinaryCollaboratorLinkStillParksInTheHandoffConfirmScreen() {
        val r = repo()
        val handoff = reviewInvite.copy(ticket = "tkt-h", purpose = CollaboratorPurpose.SESSION_HANDOFF)

        val link = r.handleIncomingLink(handoff.encode())
        assertTrue(link is dev.ccpocket.app.pairing.IncomingLink.Collab, "$link")
        assertEquals("tkt-h", r.pendingCollabInvite.value?.ticket)
        assertNull(r.pendingReviewInvite.value)
    }

    // ── §12: a listing is the whole truth, not a delta ────────────────────────────────────────────

    @Test
    fun aSentListingReplacesTheLedgerRatherThanGrowingIt() {
        val r = repo()
        assertFalse(r.reviewsSentLoaded.value, "\"no rows yet\" and \"never answered\" must not look alike")

        r.receiveForTest(ReviewListing(listOf(req("a"), req("b"))))
        assertTrue(r.reviewsSentLoaded.value)
        assertEquals(setOf("a", "b"), r.reviewsSent.map { it.id }.toSet())

        // a reconnect re-lists: rows the daemon no longer reports are GONE, not merged back in
        r.receiveForTest(ReviewListing(listOf(req("c"))))
        assertEquals(listOf("c"), r.reviewsSent.map { it.id })
    }

    @Test
    fun aStaleRevisionPushCannotWalkARowBackwards() {
        val r = repo()
        r.receiveForTest(ReviewListing(listOf(req("a", ReviewStatus.QUEUED, revision = 1))))

        r.receiveForTest(ReviewUpdated(req("a", ReviewStatus.DELIVERED, revision = 2)))
        assertEquals(ReviewStatus.DELIVERED, r.reviewsSent.single().status)

        // the fan-out is not ordered: an older copy of the same row can land after a newer one
        r.receiveForTest(ReviewUpdated(req("a", ReviewStatus.QUEUED, revision = 1)))
        assertEquals(ReviewStatus.DELIVERED, r.reviewsSent.single().status, "a replay must not undo a transition")
        assertEquals(2L, r.reviewsSent.single().revision)
    }

    @Test
    fun aPushForARowWeDoNotHoldIsAdded() {
        val r = repo()
        r.receiveForTest(ReviewListing(listOf(req("a"))))
        // the row was created on another device of this account — the push is the first we hear of it
        r.receiveForTest(ReviewUpdated(req("b", revision = 1)))
        assertEquals(setOf("a", "b"), r.reviewsSent.map { it.id }.toSet())
    }

    @Test
    fun anInboxLinksRowsAreNeverFiledUnderWhatThisMachineSent() {
        val r = repo(BindingRole.COLLABORATOR)
        assertTrue(r.isCollaboratorInbox)

        // this link speaks somebody else's account: its rows are requests addressed TO this device
        r.receiveForTest(ReviewListing(listOf(req("a"))))
        r.receiveForTest(ReviewUpdated(req("b", ReviewStatus.DELIVERED, revision = 2)))

        assertTrue(r.reviewsSent.isEmpty(), "\"I sent these\" would be a straightforward lie about who asked whom")
        assertFalse(r.reviewsSentLoaded.value, "…and an empty sent tab must not claim it was loaded either")
        assertFalse(r.reviewUnsupported.value, "the daemon DID answer — only the filing is refused")
    }

    // ── §8: only work counts as work ──────────────────────────────────────────────────────────────

    @Test
    fun theBadgeCountsOnlyRequestsStillWaitingOnThisMachine() {
        val r = repo()
        r.receiveForTest(
            ReviewInboxListing(
                listOf(
                    inbox(req("delivered", ReviewStatus.DELIVERED)),
                    inbox(req("running", ReviewStatus.IN_PROGRESS)),
                    // RESPONDED is deliberately non-terminal (the SENDER still closes it) yet it is not
                    // this machine's work any more — counting it would nag the reviewer forever
                    inbox(req("answered", ReviewStatus.RESPONDED)),
                    inbox(req("closed", ReviewStatus.CLOSED)),
                    inbox(req("declined", ReviewStatus.DECLINED)),
                ),
            ),
        )
        assertTrue(r.reviewInboxLoaded.value)
        assertEquals(5, r.reviewsReceived.size, "history stays visible — it just isn't a task")
        assertEquals(2, r.reviewPendingCount)
    }

    @Test
    fun onlyContactsTheDaemonSaysAreSendableReachTheRecipientPicker() {
        val r = repo()
        r.receiveForTest(
            ReviewContactsListing(
                listOf(
                    ReviewContact("dev-frank", "Frank", CollaboratorDirection.OUTBOUND, canSend = true),
                    ReviewContact("pl_aiko", "Aiko", CollaboratorDirection.INBOUND, canSend = false),
                    ReviewContact("dev-gone", "Gone", CollaboratorDirection.OUTBOUND, removed = true, canSend = false),
                    // a SESSION HANDOFF contact should never appear in a review listing at all, but if a
                    // peer ever sends one it is still not a review recipient (REVIEW-REQUEST.md §13.3)
                    ReviewContact(
                        "dev-phone", "Frank's phone", CollaboratorDirection.OUTBOUND,
                        purpose = CollaboratorPurpose.SESSION_HANDOFF, canSend = false,
                    ),
                ),
            ),
        )
        assertTrue(r.reviewContactsLoaded.value)
        assertEquals(4, r.reviewContacts.size, "removed and inbound rows still render — they just can't be picked")
        // canSend is the DAEMON's answer, so the picker can't drift from what the send path accepts
        assertEquals(listOf("dev-frank"), r.reviewRecipients().map { it.id })
    }

    // ── §10: every reply ends its wait, and a refusal is stated ───────────────────────────────────

    @Test
    fun aCreatedReplyEndsTheSendAndLandsTheRowThatProvesIt() {
        val r = repo()
        r.sendReview("dev-frank", "Retry race", ReviewBrief(request = "Look at the ACK path"), emptyList())
        assertTrue(r.reviewSending.value, "the button holds a real waiting state until the daemon answers")

        r.receiveForTest(ReviewRequestCreated(ok = true, request = req("n1")))

        assertFalse(r.reviewSending.value)
        assertEquals("n1", r.reviewLastCreated.value?.id, "the returned id is the receipt the user can quote")
        assertEquals(listOf("n1"), r.reviewsSent.map { it.id })
        assertNull(r.reviewError.value)
    }

    @Test
    fun aRefusedCreateSaysWhyInsteadOfLookingSent() {
        val r = repo()
        r.sendReview("dev-frank", "Retry race", ReviewBrief(request = "Look at the ACK path"), emptyList())

        r.receiveForTest(ReviewRequestCreated(ok = false, error = "no such recipient", code = "review_no_recipient"))

        assertFalse(r.reviewSending.value)
        assertEquals("no such recipient", r.reviewError.value, "the daemon's own words, not a generic failure")
        assertTrue(r.reviewsSent.isEmpty(), "a refused create must not leave a phantom row in the ledger")
        assertNull(r.reviewLastCreated.value)
    }

    @Test
    fun anInviteIsHeldOnlyWhenTheDaemonActuallyMintedOne() {
        val ok = repo()
        ok.createReviewInvite()
        assertTrue(ok.reviewInviteCreating.value)

        ok.receiveForTest(ReviewInviteCreated(ok = true, invite = "ccpocket://review-contact#x", ttlSec = 120))
        assertEquals("ccpocket://review-contact#x", ok.reviewInvite.value)
        assertEquals(120, ok.reviewInviteTtlSec.value)
        assertFalse(ok.reviewInviteCreating.value)

        val refused = repo()
        refused.createReviewInvite()
        refused.receiveForTest(ReviewInviteCreated(ok = false, error = "not allowed", code = "review_forbidden"))
        assertNull(refused.reviewInvite.value, "a screen with no URI must not render a QR of the last one")
        assertEquals("not allowed", refused.reviewError.value)
        assertFalse(refused.reviewInviteCreating.value)
    }

    @Test
    fun prepareHoldsItsRowSpinnerUntilTheBundleOrTheRefusalArrives() {
        val ok = repo()
        ok.prepareReview("a")
        assertEquals("a", ok.reviewPreparing.value, "the spinner is per-row, so a second card isn't frozen too")

        ok.receiveForTest(ReviewPrepared(ok = true, bundle = bundle("a", "Review this MR with your own tools")))
        assertEquals("a", ok.reviewBundle.value?.requestId)
        assertNull(ok.reviewPreparing.value)
        assertNull(ok.reviewError.value)

        val refused = repo()
        refused.prepareReview("a")
        refused.receiveForTest(ReviewPrepared(ok = false, error = "artifact kind unknown", code = "review_bad_artifact"))
        assertNull(refused.reviewBundle.value)
        assertEquals("artifact kind unknown", refused.reviewError.value)
        assertNull(refused.reviewPreparing.value)
    }

    @Test
    fun aQueuedActionReachesTheStateAsQueuedRatherThanAsDelivered() {
        val r = repo()
        r.actOnReview("a", ReviewInboxAction.ACKNOWLEDGE)
        assertEquals("a", r.reviewActing.value)

        r.receiveForTest(
            ReviewInboxActed(ok = true, requestId = "a", queued = true, status = ReviewStatus.ACKNOWLEDGED),
        )

        assertNull(r.reviewActing.value)
        // the honesty invariant (§8): the daemon recorded it, the colleague has NOT necessarily seen it —
        // dropping `queued` on the floor here is exactly how "queued" starts reading as "delivered"
        assertEquals(true, r.reviewLastActed.value?.queued)
        assertEquals(ReviewStatus.ACKNOWLEDGED, r.reviewLastActed.value?.status)

        // …and the other honest answer survives too: ok with nothing queued = it was already in that state
        val settled = repo()
        settled.actOnReview("a", ReviewInboxAction.ACKNOWLEDGE)
        settled.receiveForTest(
            ReviewInboxActed(ok = true, requestId = "a", queued = false, status = ReviewStatus.ACKNOWLEDGED),
        )
        assertEquals(false, settled.reviewLastActed.value?.queued)
        assertNull(settled.reviewError.value, "\"nothing to send\" is not a failure")
    }

    @Test
    fun theUnsupportedClaimIsEarnedBySilenceAndRetiredByAnyReply() {
        val r = repo()
        // it may only ever mean "the bounded wait elapsed with nothing back" — never an opening state
        assertFalse(r.reviewUnsupported.value)

        r.receiveForTest(ReviewListing(listOf(req("a"))))
        assertFalse(r.reviewUnsupported.value, "one listing proves the daemon understands the frame family")
    }

    // ── §12: a ledger belongs to ONE machine ──────────────────────────────────────────────────────

    @Test
    fun disconnectDropsEveryMirrorSoTheNextMachineStartsBlank() {
        val r = repo()
        r.receiveForTest(ReviewListing(listOf(req("a"))))
        r.receiveForTest(ReviewInboxListing(listOf(inbox(req("b", ReviewStatus.DELIVERED)))))
        r.receiveForTest(ReviewContactsListing(listOf(ReviewContact("dev-frank", "Frank", canSend = true))))
        r.receiveForTest(ReviewInviteCreated(ok = true, invite = "ccpocket://review-contact#x", ttlSec = 120))
        r.receiveForTest(ReviewPrepared(ok = true, bundle = bundle("b", "Review this")))
        r.receiveForTest(ReviewInboxActed(ok = true, requestId = "b", queued = true, status = ReviewStatus.ACKNOWLEDGED))

        r.disconnect()

        // showing the previous daemon's inbox after a fleet switch would be a lie about whose work it is
        assertTrue(r.reviewsSent.isEmpty())
        assertTrue(r.reviewsReceived.isEmpty())
        assertTrue(r.reviewContacts.isEmpty())
        assertFalse(r.reviewsSentLoaded.value)
        assertFalse(r.reviewInboxLoaded.value)
        assertFalse(r.reviewContactsLoaded.value)
        assertFalse(r.reviewUnsupported.value)
        assertNull(r.reviewError.value)
        assertNull(r.reviewBundle.value)
        assertNull(r.reviewLastActed.value)
        assertNull(r.reviewLastCreated.value)
        assertNull(r.reviewInvite.value)
        assertEquals(0, r.reviewInviteTtlSec.value)
        assertEquals(0, r.reviewPendingCount)
    }
}
