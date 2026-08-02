package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.BindingRole
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.encode
import dev.ccpocket.protocol.AcceptHandoff
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.HandoffKind
import dev.ccpocket.protocol.HandoffListing
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.HandoffUpdated
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListHandoffs
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.Role
import dev.ccpocket.protocol.SessionHandoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recipient half of the handoff loop (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §3.2.3 / §3.2.6-7):
 * a link speaking a COLLABORATOR credential is an offer INBOX, not a computer.
 *
 * What used to happen, and what each test pins down:
 *  - the app sent ListDirectories, the daemon's collaborator whitelist refused it, and ~6s later the
 *    screen said "computer offline" — now HandoffListing is the readiness proof and the offline claim is
 *    never made on an inbox link;
 *  - accepting only sent a frame and hoped — now the button waits for daemon truth and says so when the
 *    answer is "no";
 *  - an accepted handoff left the recipient on a dead-end card — now the IN_PROGRESS addressed to this
 *    device opens the source session, exactly once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollaboratorInboxRepoTest {

    private lateinit var scope: CoroutineScope
    private lateinit var scheduler: TestCoroutineScheduler
    private var savedLinks: String? = null

    @BeforeTest
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        // the redeem tests write the REAL store (one file shared by every desktop test class) — snapshot it
        savedLinks = dev.ccpocket.app.secure.SecureStore.getString("collab_links")
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        savedLinks
            ?.let { dev.ccpocket.app.secure.SecureStore.putString("collab_links", it) }
            ?: dev.ccpocket.app.secure.SecureStore.remove("collab_links")
    }

    private fun repo(role: BindingRole, sent: MutableList<Frame> = CopyOnWriteArrayList()) =
        PocketRepository(
            scope,
            pinnedTo = PairedDaemon(
                relay = "wss://127.0.0.1:9", accountId = "acct-colleague", daemonPub = "pk",
                deviceId = "dev-me", credential = "cred", role = role,
            ),
        ).apply {
            useRelay = true
            sessionActive.value = true
            onSendForTest = { sent += it }
        }

    private fun offer(
        status: HandoffStatus = HandoffStatus.WAITING,
        recipient: String? = "dev-me",
        id: String = "h1",
    ) = SessionHandoff(
        id = id, sourceSessionId = "sid-src", workdir = "/w/proj", agent = AgentKind.CODEX,
        initiatorDeviceId = "dev-panda", initiatorLabel = "Panda",
        recipientDeviceId = recipient, recipientLabel = "Frank",
        kind = HandoffKind.REVIEW, access = HandoffAccess.REVIEW_READ_ONLY, status = status,
        createdAt = 1000, expiresAt = 9_000_000,
    )

    // ── §3.2.3: readiness comes from HandoffListing, not Directories ──────────────────────────────

    @Test
    fun anInboxLinkIsReadyOnTheFirstHandoffListing() {
        val r = repo(BindingRole.COLLABORATOR)
        assertTrue(r.isCollaboratorInbox)
        r.receiveControlForTest(Attached(Role.DEVICE, "acct-colleague"))
        assertFalse(r.phase.value == ConnPhase.Ready, "attach alone is not ready")

        r.receiveForTest(HandoffListing(listOf(offer())))

        assertEquals(ConnPhase.Ready, r.phase.value, "the collaborator channel answered — that IS ready here")
        assertTrue(r.handoffsLoaded.value)
        assertFalse(r.directoriesLoaded.value, "a collaborator credential never gets a directory list at all")
    }

    @Test
    fun anInboxLinkNeverClaimsTheComputerIsOffline() = runBlocking {
        val r = repo(BindingRole.COLLABORATOR)
        r.receiveControlForTest(Attached(Role.DEVICE, "acct-colleague"))
        // the exact window that used to escalate to ComputerOffline on a link that is working fine
        scheduler.advanceTimeBy(PocketRepository.LIST_WAIT_MS + 100)
        scheduler.runCurrent()
        assertFalse(r.phase.value == ConnPhase.ComputerOffline, "a quiet colleague's machine is not an app error (§3.2.3)")
    }

    @Test
    fun anOrdinaryLinkKeepsItsDirectoryBasedReadinessAndOfflineEscalation() = runBlocking {
        val r = repo(BindingRole.OWNER)
        assertFalse(r.isCollaboratorInbox)
        r.receiveControlForTest(Attached(Role.DEVICE, "acct-colleague"))
        r.receiveForTest(HandoffListing(emptyList()))
        assertFalse(r.phase.value == ConnPhase.Ready, "a handoff listing must NOT stand in for the project list")

        r.receiveForTest(Directories(emptyList()))
        assertEquals(ConnPhase.Ready, r.phase.value)
    }

    @Test
    fun theInboxPullsUnfilteredWhileAnOrdinaryLinkScopesToItsSession() {
        val inboxSent = CopyOnWriteArrayList<Frame>()
        val inbox = repo(BindingRole.COLLABORATOR, inboxSent)
        inbox.listHandoffs()
        val pull = inboxSent.filterIsInstance<ListHandoffs>().single()
        assertNull(pull.sessionId, "the inbox has no session to scope by — the daemon fans out by device id")
        assertNull(pull.workdir)

        // an owner link with no open session simply doesn't ask
        val ownerSent = CopyOnWriteArrayList<Frame>()
        repo(BindingRole.OWNER, ownerSent).listHandoffs()
        assertTrue(ownerSent.filterIsInstance<ListHandoffs>().isEmpty())
    }

    @Test
    fun foregroundRePullsTheOffersInsteadOfTheProjectList() {
        val sent = CopyOnWriteArrayList<Frame>()
        val r = repo(BindingRole.COLLABORATOR, sent)
        r.receiveControlForTest(Attached(Role.DEVICE, "acct-colleague"))
        r.receiveForTest(HandoffListing(emptyList()))
        sent.clear()

        r.onAppForeground() // §3.2.3: a missed offer push heals on the next foreground

        assertTrue(sent.any { it is ListHandoffs })
        assertTrue(sent.none { it is ListDirectories }, "the one frame this credential may send is the listing")
    }

    // ── §3.2.5: the offer list is pure daemon truth ───────────────────────────────────────────────

    @Test
    fun onlyWaitingOffersAddressedToThisDeviceCount() {
        val r = repo(BindingRole.COLLABORATOR)
        r.receiveForTest(
            HandoffListing(
                listOf(
                    offer(id = "mine"),
                    offer(id = "someone-else", recipient = "dev-other"),
                    offer(id = "already-running", status = HandoffStatus.IN_PROGRESS),
                    offer(id = "gone", status = HandoffStatus.EXPIRED),
                ),
            ),
        )
        assertEquals(listOf("mine"), r.incomingOffers().map { it.id })
    }

    @Test
    fun aWithdrawnOfferSimplyLeavesOnTheNextListing() {
        val r = repo(BindingRole.COLLABORATOR)
        r.receiveForTest(HandoffListing(listOf(offer())))
        assertEquals(1, r.incomingOffers().size)
        r.receiveForTest(HandoffListing(emptyList())) // reconnect truth: the initiator cancelled meanwhile
        assertTrue(r.incomingOffers().isEmpty(), "offers are recovered from the daemon, never cached locally (§3.2.8)")
    }

    // ── §3.2.7: accept waits for daemon truth ─────────────────────────────────────────────────────

    @Test
    fun acceptHoldsItsWaitingStateUntilTheDaemonAnswers() {
        val sent = CopyOnWriteArrayList<Frame>()
        val r = repo(BindingRole.COLLABORATOR, sent)
        r.receiveForTest(HandoffListing(listOf(offer())))

        r.acceptHandoff("h1")
        assertEquals("h1", r.handoffAccepting.value, "the button must not pretend it succeeded")
        assertEquals("h1", sent.filterIsInstance<AcceptHandoff>().single().handoffId)
        r.acceptHandoff("h1") // a double-tap is not a second accept
        assertEquals(1, sent.filterIsInstance<AcceptHandoff>().size)

        r.receiveForTest(HandoffUpdated(offer(status = HandoffStatus.IN_PROGRESS)))
        assertNull(r.handoffAccepting.value)
        assertNull(r.handoffAcceptError.value)
    }

    @Test
    fun aLostRaceEndsTheWaitWithAnExplicitRefusal() {
        val r = repo(BindingRole.COLLABORATOR)
        r.receiveForTest(HandoffListing(listOf(offer())))
        r.acceptHandoff("h1")
        // the other device won the compare-and-set: same handoff, IN_PROGRESS, someone else's lease
        r.receiveForTest(HandoffUpdated(offer(status = HandoffStatus.IN_PROGRESS, recipient = "dev-other")))
        assertNull(r.handoffAccepting.value)
        assertNotNull(r.handoffAcceptError.value, "\"accepted\" would be a lie here")
    }

    @Test
    fun anExpiredOfferEndsTheWaitToo() {
        val r = repo(BindingRole.COLLABORATOR)
        r.acceptHandoff("h1")
        r.receiveForTest(HandoffUpdated(offer(status = HandoffStatus.EXPIRED)))
        assertNull(r.handoffAccepting.value)
        assertNotNull(r.handoffAcceptError.value)
    }

    @Test
    fun anOldDaemonThatDropsTheAcceptFrameTimesOutInsteadOfSpinningForever(): Unit = runBlocking {
        val r = repo(BindingRole.COLLABORATOR)
        r.acceptHandoff("h1")
        scheduler.advanceTimeBy(PocketRepository.ACCEPT_TIMEOUT_MS + 100)
        scheduler.runCurrent()
        assertNull(r.handoffAccepting.value)
        assertNotNull(r.handoffAcceptError.value, "silence is a failure, not a success")
    }

    // ── §3.2.6: IN_PROGRESS for this device walks straight into the source session ────────────────

    @Test
    fun acceptedHandoffAutoOpensTheSourceSessionExactlyOnce() {
        val sent = CopyOnWriteArrayList<Frame>()
        val r = repo(BindingRole.COLLABORATOR, sent)
        r.receiveForTest(HandoffListing(listOf(offer())))
        r.acceptHandoff("h1")

        r.receiveForTest(HandoffUpdated(offer(status = HandoffStatus.IN_PROGRESS)))

        val open = sent.filterIsInstance<OpenSession>().single()
        assertEquals("/w/proj", open.workdir)
        assertEquals("sid-src", open.resumeId, "resume the SOURCE session — v1 never forks")
        assertFalse(open.takeOver, "mode/takeOver/pathScope are the daemon's to clamp from the Grant (§3.2.6)")
        assertEquals(AgentKind.CODEX, open.agent, "the handoff's backend, not this device's default")

        // a replayed update (reconnect listing, a second fan-out) must not churn the session
        r.receiveForTest(HandoffUpdated(offer(status = HandoffStatus.IN_PROGRESS)))
        r.receiveForTest(HandoffListing(listOf(offer(status = HandoffStatus.IN_PROGRESS))))
        assertEquals(1, sent.filterIsInstance<OpenSession>().size)
    }

    @Test
    fun anInProgressHandoffForSomeoneElseNeverOpensAnything() {
        val sent = CopyOnWriteArrayList<Frame>()
        val r = repo(BindingRole.COLLABORATOR, sent)
        r.receiveForTest(HandoffUpdated(offer(status = HandoffStatus.IN_PROGRESS, recipient = "dev-other")))
        assertTrue(sent.filterIsInstance<OpenSession>().isEmpty())
    }

    @Test
    fun anOwnerLinkIsNotYankedIntoAHandoffItMerelyObserves() {
        val sent = CopyOnWriteArrayList<Frame>()
        val r = repo(BindingRole.OWNER, sent)
        // this device IS the recipient, but it never asked to accept in this process (e.g. a restart
        // replaying an old handoff while the user is somewhere else entirely)
        r.receiveForTest(HandoffListing(listOf(offer(status = HandoffStatus.IN_PROGRESS))))
        assertTrue(sent.filterIsInstance<OpenSession>().isEmpty())

        // …but the device that actually tapped Accept does walk in
        r.acceptHandoff("h1")
        r.receiveForTest(HandoffUpdated(offer(status = HandoffStatus.IN_PROGRESS)))
        assertEquals(1, sent.filterIsInstance<OpenSession>().size)
    }

    // ── §7: a link only ever ARMS a trust screen; the redeem is the user's, and it can fail ───────

    @Test
    fun aScannedCollaboratorLinkParksInTheConfirmScreenInsteadOfRedeeming() {
        val r = repo(BindingRole.OWNER)
        val invite = dev.ccpocket.protocol.CollaboratorInvite(
            relay = "wss://127.0.0.1:9", accountId = "acct-colleague", daemonPub = "pk", ticket = "t", ownerLabel = "Panda",
        )
        r.handleIncomingLink(invite.encode())
        assertEquals("acct-colleague", r.pendingCollabInvite.value?.accountId)
        assertTrue(r.collaboratorLinks.isEmpty(), "scanning is not consenting — the fingerprint screen decides")
        assertFalse(r.collabRedeeming.value)
    }

    @Test
    fun anExpiredOrRefusedTicketStoresNothingAndSaysSo(): Unit = runBlocking {
        val r = repo(BindingRole.OWNER)
        // a refused relay redeem stands in for the daemon burning an expired/used ticket: what matters is
        // that a FAILED redeem leaves no link behind and doesn't report itself as connected
        r.redeemCollaboratorInvite(
            dev.ccpocket.protocol.CollaboratorInvite(
                relay = "wss://127.0.0.1:9", accountId = "acct-colleague", daemonPub = "pk", ticket = "expired",
            ),
        )
        assertTrue(r.collabRedeeming.value, "the confirm button enters its waiting state immediately")
        // the redeem is a REAL http call on ktor's own dispatcher, so this waits in real time (a refused
        // loopback connect returns at once) rather than on the virtual clock
        withTimeout(15_000) { while (r.collabRedeeming.value) delay(20) }
        assertTrue(dev.ccpocket.app.pairing.Pairing.collaboratorLinks().none { it.accountId == "acct-colleague" })
        assertTrue(r.collaboratorLinks.none { it.accountId == "acct-colleague" })
        assertNotNull(r.collabRedeemError.value, "a failed connect must not read as \"connected\"")
    }

    // ── §6: the daemon's "not supported" refusal is shown, not swallowed ──────────────────────────

    @Test
    fun handoffNotSupportedSurfacesOnTheHandoffSurfacesInsteadOfTheTranscript() {
        val r = repo(BindingRole.COLLABORATOR)
        r.acceptHandoff("h1")
        r.receiveForTest(PocketError("handoff_not_supported", "only review is available"))
        assertNull(r.handoffAccepting.value)
        assertEquals("only review is available", r.handoffUnsupported.value)
        assertTrue(r.messages.isEmpty(), "it must not land as an \"error:\" line in an unrelated chat")
    }
}
