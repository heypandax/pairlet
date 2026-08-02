package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AcceptHandoff
import dev.ccpocket.protocol.Collaborator
import dev.ccpocket.protocol.CollaboratorConnected
import dev.ccpocket.protocol.CollaboratorDirection
import dev.ccpocket.protocol.CollaboratorListing
import dev.ccpocket.protocol.CollaboratorUpdated
import dev.ccpocket.protocol.CreateHandoff
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.HandoffCreated
import dev.ccpocket.protocol.HandoffFinding
import dev.ccpocket.protocol.HandoffResult
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.HandoffUpdated
import dev.ccpocket.protocol.ReturnHandoff
import dev.ccpocket.protocol.SessionHandoff
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two-role App state machine (SESSION-HANDOFF.md): "two people" are just two [PocketRepository]
 * instances with different device ids, fed the SAME daemon fan-out sequence — each side must light
 * its own role's chrome (initiator locks/spectates, recipient offers/controls) from identical frames.
 * Unconfined makes everything synchronous; no daemon, no network.
 */
class HandoffTwoRoleRepoTest {

    private fun repo(deviceId: String, sent: MutableList<Frame> = CopyOnWriteArrayList()): PocketRepository =
        PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
            paired.value = PairedDaemon(
                relay = "wss://test", accountId = "acct", daemonPub = "pk", deviceId = deviceId, credential = "cred",
            )
            onSendForTest = { sent += it }
            convoId.value = "c1"
            receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false))
        }

    private fun handoff(status: HandoffStatus, result: HandoffResult? = null) = SessionHandoff(
        id = "h1", sourceSessionId = "sid-1", workdir = "/w",
        initiatorDeviceId = "dev-panda", initiatorLabel = "Panda",
        recipientDeviceId = "dev-frank", recipientLabel = "Frank",
        status = status,
        acceptedAt = if (status in setOf(HandoffStatus.IN_PROGRESS, HandoffStatus.RETURNED, HandoffStatus.COMPLETED)) 1000L else null,
        result = result,
    )

    // ── the full serial relay, seen simultaneously by both roles ──

    @Test
    fun bothRolesTrackTheSameFanoutToTheirOwnChrome() {
        val pandaSent = CopyOnWriteArrayList<Frame>()
        val panda = repo("dev-panda", pandaSent) // initiator
        val frank = repo("dev-frank")            // recipient

        // create: the requester's HandoffCreated and everyone's HandoffUpdated carry the same entity
        panda.receiveForTest(HandoffCreated(ok = true, handoff = handoff(HandoffStatus.WAITING)))
        frank.receiveForTest(HandoffUpdated(handoff(HandoffStatus.WAITING)))

        assertEquals(HandoffStatus.WAITING, panda.activeHandoff.value?.status)
        assertTrue(panda.isHandoffInitiator(panda.activeHandoff.value!!))
        assertFalse(panda.isHandoffRecipient(panda.activeHandoff.value!!))
        assertNotNull(panda.lastHandoffInvite.value) // the create reply arms the invite state…
        assertEquals("dev-frank", panda.lastHandoffInvite.value?.recipientDeviceId) // …but bound = no QR sheet (UI gate)

        assertEquals(HandoffStatus.WAITING, frank.activeHandoff.value?.status)
        assertTrue(frank.isHandoffRecipient(frank.activeHandoff.value!!))
        assertFalse(frank.isHandoffInitiator(frank.activeHandoff.value!!))

        // accept → IN_PROGRESS: recipient controls, initiator spectates
        listOf(panda, frank).forEach { it.receiveForTest(HandoffUpdated(handoff(HandoffStatus.IN_PROGRESS))) }
        assertTrue(frank.isHandoffRecipient(frank.activeHandoff.value!!))
        assertFalse(panda.isHandoffRecipient(panda.activeHandoff.value!!))
        assertEquals(HandoffStatus.IN_PROGRESS, panda.activeHandoff.value?.status)

        // return → RETURNED: the initiator's result card has the findings; still non-terminal on both
        val result = HandoffResult(
            summary = "ok", verdict = "Approve with fixes",
            findings = listOf(HandoffFinding(title = "Race in refresh", severity = HandoffFinding.SEVERITY_HIGH, file = "R.kt", line = 88)),
        )
        listOf(panda, frank).forEach { it.receiveForTest(HandoffUpdated(handoff(HandoffStatus.RETURNED, result))) }
        assertEquals("Approve with fixes", panda.activeHandoff.value?.result?.verdict)
        assertEquals(1, panda.activeHandoff.value?.result?.findings?.size)
        assertEquals(HandoffStatus.RETURNED, frank.activeHandoff.value?.status)

        // acknowledge → COMPLETED: terminal — both sides' active slot clears, history keeps the row
        listOf(panda, frank).forEach { it.receiveForTest(HandoffUpdated(handoff(HandoffStatus.COMPLETED, result))) }
        assertNull(panda.activeHandoff.value)
        assertNull(frank.activeHandoff.value)
        assertEquals(HandoffStatus.COMPLETED, panda.handoffs.single().status)
        assertEquals(HandoffStatus.COMPLETED, frank.handoffs.single().status)
    }

    @Test
    fun recallAndDeclineAreTerminalOnBothSides() {
        val panda = repo("dev-panda")
        val frank = repo("dev-frank")
        listOf(panda, frank).forEach { it.receiveForTest(HandoffUpdated(handoff(HandoffStatus.IN_PROGRESS))) }
        listOf(panda, frank).forEach { it.receiveForTest(HandoffUpdated(handoff(HandoffStatus.RECALLED))) }
        assertNull(panda.activeHandoff.value)
        assertNull(frank.activeHandoff.value)
    }

    /** An UNKNOWN status (a newer daemon's value) must NOT read as "session free" — safest reading wins. */
    @Test
    fun unknownStatusNeverActivatesButNeverCrashes() {
        val panda = repo("dev-panda")
        panda.receiveForTest(HandoffUpdated(handoff(HandoffStatus.UNKNOWN)))
        assertNull(panda.activeHandoff.value) // UI shows nothing it can't read; the daemon still refuses input
        assertEquals(1, panda.handoffs.size)
    }

    // ── outbound shapes: what each role actually sends ──

    @Test
    fun createBindsTheGrantToThePickedContact() {
        val sent = CopyOnWriteArrayList<Frame>()
        val panda = repo("dev-panda", sent)
        panda.createHandoff("Frank", 24, request = "review", recipientDeviceId = "dev-frank")
        val create = sent.filterIsInstance<CreateHandoff>().single()
        assertEquals("dev-frank", create.recipientDeviceId)
        assertEquals("Frank", create.recipientLabel)
        assertEquals(24 * 3600L, create.expiresInSec)
        assertEquals(HandoffBrief(request = "review"), create.brief)
    }

    @Test
    fun recipientActionsSendTheirFrames() {
        val sent = CopyOnWriteArrayList<Frame>()
        val frank = repo("dev-frank", sent)
        frank.acceptHandoff("h1")
        frank.returnHandoff("h1", HandoffResult(summary = "done", verdict = "Approve"))
        assertEquals("h1", sent.filterIsInstance<AcceptHandoff>().single().handoffId)
        assertEquals("Approve", sent.filterIsInstance<ReturnHandoff>().single().result?.verdict)
    }

    // ── collaborator links: listing, live connect, remove-in-place ──

    @Test
    fun collaboratorFramesDriveTheContactState() {
        val panda = repo("dev-panda")
        val frank = Collaborator(deviceId = "dev-frank", label = "Frank", direction = CollaboratorDirection.OUTBOUND, connectedAt = 1)
        panda.receiveForTest(CollaboratorListing(listOf(frank)))
        assertEquals(listOf("dev-frank"), panda.collaborators.map { it.deviceId })

        // a redeem flips the connect screen's success state AND upserts the row
        val mei = Collaborator(deviceId = "dev-mei", label = "Mei", direction = CollaboratorDirection.OUTBOUND, connectedAt = 2)
        panda.receiveForTest(CollaboratorConnected(mei))
        assertEquals("dev-mei", panda.lastCollaboratorConnected.value?.deviceId)
        assertEquals(2, panda.collaborators.size)

        // remove marks the row terminal in place — it never disappears (history references it)
        panda.receiveForTest(CollaboratorUpdated(frank.copy(removed = true)))
        assertEquals(2, panda.collaborators.size)
        assertTrue(panda.collaborators.first { it.deviceId == "dev-frank" }.removed)
    }
}
