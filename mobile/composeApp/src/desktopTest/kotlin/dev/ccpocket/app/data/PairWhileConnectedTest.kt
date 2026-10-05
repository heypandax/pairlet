package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.Pairing
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A pair link for computer B opened (system camera / tapped URL) while the phone is live on computer A.
 *
 * The bug: doPair re-pointed `paired` at B, but its startRelay() no-oped on the live link — A's socket, chat and
 * approvals stayed up under B's name, the fleet then dialed A a second time as a satellite (same deviceId, two
 * sockets kicking each other), and the next reconnect dialed B and replayed A's OpenSession at it. The fix routes
 * the pairing through the user's own switch path ([PocketRepository.switchDaemon]).
 */
class PairWhileConnectedTest {

    private lateinit var scope: CoroutineScope
    private var savedActive: String? = null

    private val a = binding("pwc-acct-a")
    private val b = binding("pwc-acct-b")

    private fun binding(id: String) = PairedDaemon(
        // a closed loopback port: the fleet's real satellite dial fails fast instead of hanging
        relay = "wss://127.0.0.1:9", accountId = id, daemonPub = "pk-$id", deviceId = "dev", credential = "c-$id",
    )

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        savedActive = Pairing.activeAccount()
    }

    @AfterTest
    fun tearDown() {
        Pairing.remove(a.accountId); Pairing.remove(b.accountId)
        Pairing.setActive(savedActive)
        scope.cancel()
    }

    /** Every dial the primary makes (account, first-pair ticket), and which of them still hold their socket. */
    private val dials = mutableListOf<Pair<String, String?>>()
    private val live = mutableListOf<String>()

    private fun connectedToA(): PocketRepository {
        Pairing.upsert(a); Pairing.setActive(a.accountId)
        val r = PocketRepository(scope).apply {
            dialForTest = { p, ticket ->
                dials += p.accountId to ticket; live += p.accountId
                try { awaitCancellation() } finally { live -= p.accountId }
            }
            redeemForTest = { info -> check(info.accountId == b.accountId); b }
            paired.value = a
            pairedList.add(a)
        }
        r.startRelay()
        r.receiveForTest(Directories(listOf(DirectoryEntry(path = "/a/proj", name = "proj", isDir = true))))
        r.openSession("/a/proj", "sid-a", agent = AgentKind.CLAUDE)
        r.receiveForTest(SessionLive("c-a", "/a/proj", "sid-a", executing = false))
        r.receiveForTest(PermissionAsk("c-a", "ask-a", "Bash", "ls"))
        // preconditions: a live chat on A, one link to A
        assertEquals("c-a", r.convoId.value)
        assertEquals(listOf(a.accountId), live)
        return r
    }

    /** The primary's held sockets to [id] plus the fleet's live satellite for it. */
    private fun linksTo(id: String, fleet: FleetCoordinator) =
        live.count { it == id } + (if (fleet.satellites[id]?.sessionActive?.value == true) 1 else 0)

    @Test
    fun pairingBWhileLiveOnASwitchesThroughTheColdPath() {
        val r = connectedToA()
        val fleet = FleetCoordinator(scope, r)
        r.onBeforeSwitch = fleet::retireSatellite // what fleet.start() wires, without its collectors
        fleet.sync()
        assertTrue(fleet.satellites.isEmpty())

        val sent = mutableListOf<Frame>()
        r.onSendForTest = { sent += it }
        r.pair("ccpocket://pair?relay=wss%3A%2F%2F127.0.0.1%3A9&acct=${b.accountId}&dpk=pk-${b.accountId}&ticket=tkt-b")
        fleet.sync() // what the snapshot collector runs after the pairing settles

        // exactly one primary link, and it is to B — with B's first-pair ticket as its PSK
        assertEquals(listOf(b.accountId), live, "the primary holds one link, to B; A's socket was torn down")
        assertEquals(b.accountId to "tkt-b", dials.last(), "B is dialed with the ticket of this pairing")
        assertEquals(b.accountId, r.paired.value?.accountId)
        assertEquals(b.accountId, Pairing.activeAccount())
        assertEquals(1, linksTo(a.accountId, fleet), "A is reached by exactly one link: the fleet's satellite")

        // A's chat state left with A
        assertNull(r.convoId.value)
        assertNull(r.workdir.value)
        assertNull(r.pendingAsk.value)
        assertTrue(r.pendingApprovals.isEmpty())
        assertTrue(r.messages.isEmpty())
        assertTrue(r.directories.isEmpty())

        // the reconnect restore on B names nothing of A
        runBlocking { r.restoreAfterReconnectForTest() }
        val leaked = sent.filter { (it is OpenSession && (it.resumeId == "sid-a" || it.workdir == "/a/proj")) || (it is ListSessions && it.workdir == "/a/proj") }
        assertTrue(leaked.isEmpty(), "A's session requests must never be sent to B: $leaked")

        // A is still bound and one switch away
        assertTrue(r.pairedList.any { it.accountId == a.accountId })
        assertTrue(fleet.satellites.containsKey(a.accountId), "A stays in the fleet")
        fleet.switchTo(a) // mobile: promoteHotSatellites off → the cold path
        assertEquals(a.accountId, r.paired.value?.accountId)
        assertEquals(listOf(a.accountId), live, "switching back holds one link again, to A")
        assertNull(dials.last().second, "an already-paired computer dials without a ticket")
    }

    @Test
    fun pairingWhileNotConnectedStillStartsTheRelayOnce() {
        Pairing.upsert(a); Pairing.setActive(a.accountId)
        val r = PocketRepository(scope).apply {
            dialForTest = { p, ticket -> dials += p.accountId to ticket; live += p.accountId; try { awaitCancellation() } finally { live -= p.accountId } }
            redeemForTest = { b }
        }
        r.pair("ccpocket://pair?relay=wss%3A%2F%2F127.0.0.1%3A9&acct=${b.accountId}&dpk=pk-${b.accountId}&ticket=tkt-b")
        assertEquals(listOf<Pair<String, String?>>(b.accountId to "tkt-b"), dials.toList())
        assertEquals(listOf(b.accountId), live)
        assertEquals(b.accountId, r.paired.value?.accountId)
    }
}
