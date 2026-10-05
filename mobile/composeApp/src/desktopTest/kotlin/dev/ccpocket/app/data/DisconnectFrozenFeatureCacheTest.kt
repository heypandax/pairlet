package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.BridgeInfo
import dev.ccpocket.protocol.BridgeListing
import dev.ccpocket.protocol.Collaborator
import dev.ccpocket.protocol.CollaboratorListing
import dev.ccpocket.protocol.HandoffListing
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.SessionHandoff
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.ShareInfo
import dev.ccpocket.protocol.ShareListing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The frozen features' CLIENT CACHES of one computer's daemon truth (handoffs, collaborators, bridges, shares).
 * Their logic is untouched; only what disconnect() leaves behind is pinned here — each list is re-pulled from the
 * next daemon when its surface opens, so dropping it costs nothing, and keeping it hurt the main path:
 * [PocketRepository.activeHandoff] is NOT scoped to the chat on screen, and a WAITING handoff on computer A locked
 * the composer (and refused memo / voice-setup sends) in a brand-new session on computer B, where the scoped
 * ListHandoffs never goes out (a new session has no id yet) to replace it.
 */
class DisconnectFrozenFeatureCacheTest {

    private fun repo() = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        paired.value = PairedDaemon(relay = "wss://test", accountId = "acct-a", daemonPub = "pk", deviceId = "dev", credential = "cred")
    }

    @Test
    fun aWaitingHandoffOnAMustNotLockANewSessionOnB() {
        val r = repo()
        r.receiveForTest(SessionLive("c-a", "/a", "sid-a", executing = false))
        r.receiveForTest(HandoffListing(listOf(SessionHandoff("ho-1", "sid-a", "/a", status = HandoffStatus.WAITING, initiatorDeviceId = "dev"))))
        assertEquals(HandoffStatus.WAITING, r.activeHandoff.value?.status, "precondition: A's session is handoff-locked")

        r.disconnect()
        // computer B: a brand-new session (no session id yet, so listHandoffs() sends nothing to refresh the lock)
        r.receiveForTest(Directories(listOf(DirectoryEntry(path = "/b", name = "b", isDir = true))))
        assertTrue(r.openSession("/b", null, agent = AgentKind.CLAUDE))
        r.receiveForTest(SessionLive("c-b", "/b", null, executing = false))
        r.connected.value = true // the link is up; the gate under test is the handoff one
        assertEquals("c-b", r.convoId.value)

        assertNull(r.activeHandoff.value, "A's handoff must not gate B's composer")
        assertNotEquals("handoff", r.memoSendRefusal("c-b"))
    }

    @Test
    fun disconnectDropsTheFrozenFeaturesDaemonListings() {
        val r = repo()
        r.receiveForTest(SessionLive("c-a", "/a", "sid-a", executing = false))
        r.receiveForTest(HandoffListing(listOf(SessionHandoff("ho-1", "sid-a", "/a", status = HandoffStatus.WAITING))))
        r.receiveForTest(CollaboratorListing(listOf(Collaborator("dev-colleague", "Lin"))))
        r.receiveForTest(BridgeListing(listOf(BridgeInfo("feishu-a"))))
        r.receiveForTest(ShareListing(listOf(ShareInfo("guest-1", "/a", AccessTier.REVIEW, 1, 2))))
        assertTrue(r.handoffs.isNotEmpty(), "handoffs"); assertTrue(r.collaborators.isNotEmpty(), "collaborators")
        assertTrue(r.bridges.isNotEmpty(), "bridges"); assertTrue(r.shares.isNotEmpty(), "shares")
        assertTrue(r.collaboratorsLoaded.value, "collaboratorsLoaded"); assertTrue(r.bridgesLoaded.value, "bridgesLoaded")
        assertTrue(r.sharesLoaded.value, "sharesLoaded")

        r.disconnect()

        assertTrue(r.handoffs.isEmpty())
        assertNull(r.activeHandoff.value)
        assertTrue(r.collaborators.isEmpty())
        assertFalse(r.collaboratorsLoaded.value, "B's contacts are 'still loading', not A's 'loaded'")
        assertTrue(r.bridges.isEmpty(), "revoking from this list would name A's bridges to B")
        assertFalse(r.bridgesLoaded.value)
        assertFalse(r.bridgesUnavailable.value)
        assertTrue(r.shares.isEmpty(), "revoking from this list would name A's guests to B")
        assertFalse(r.sharesLoaded.value)
    }
}
