package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.handoff.CollaboratorService
import dev.ccpocket.daemon.handoff.CollaboratorStore
import dev.ccpocket.protocol.CreateBridge
import dev.ccpocket.protocol.CreateShare
import dev.ccpocket.protocol.PairTicket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The issue #207 mint race, closed: every ticket-backed restricted mint (collaborator link, folder
 * share, bridge credential) must hold the ONE mint slot ACROSS its suspending relay round-trip.
 *
 * The broken shape these tests were first proven RED against: `intentPending()` checked before the
 * suspending `mintTicket()`, the intent recorded only after — so two overlapping mints both passed the
 * check and both burned a relay ticket. Both tickets got PSK-armed (LIFO) while only ONE intent could
 * be recorded; the redeeming colleague's announce then popped the OTHER, intentless ticket, and the
 * colleague's key was written into devices.json as a full owner device (the #207 escalation).
 *
 * Every test drives a REAL overlap: the first mint suspends inside its relay round-trip (a gated
 * mintTicket) while a second mint is attempted. The second must be refused BEFORE burning a ticket —
 * observable as "the relay minted exactly once".
 */
class MintReservationRaceTest {

    private val dir = createTempDirectory("ccp-mint-race").toFile()
    private val sharedRoot = File(dir, "repo").apply { mkdirs() }.canonicalFile

    /** A relay stub whose FIRST mint parks inside the round-trip until [answer] completes. */
    private class GatedRelay {
        val answer = CompletableDeferred<Unit>()
        var mintCalls = 0
        suspend fun mint(): PairTicket {
            val n = ++mintCalls
            if (n == 1) answer.await()
            return PairTicket("ticket-$n", expiresInSec = 120, code = "111111")
        }
    }

    private fun registry(tag: String) = BridgeRegistry(
        File(dir, "bridges-$tag.json"), File(dir, "guests-$tag.json"), File(dir, "gs-$tag.json"),
    )

    private fun collaboratorService(reg: BridgeRegistry, relay: GatedRelay) = CollaboratorService(
        accountId = "acct", daemonPubB64 = "pub", relayWsBase = "wss://relay",
        ownerLabel = { null }, registry = reg,
        store = CollaboratorStore.load(File(dir, "collab-${System.nanoTime()}.json")),
        mintTicket = { relay.mint() },
        interactivePairingPending = { false },
        revokeCredential = {},
    )

    @Test
    fun an_overlapping_collaborator_mint_is_refused_while_the_first_round_trip_is_in_flight() = runBlocking {
        val relay = GatedRelay()
        val service = collaboratorService(registry("collab"), relay)

        val first = async { service.createTicket("Frank") }
        while (relay.mintCalls == 0) yield() // first is now parked inside its relay round-trip

        val second = service.createTicket("Alex")
        assertFalse(second.ok, "the overlapped mint must be refused, not raced")
        assertTrue(second.error!!.contains("another pairing"), "got: ${second.error}")

        relay.answer.complete(Unit)
        val f = first.await()
        assertTrue(f.ok, f.error)
        assertEquals(1, relay.mintCalls, "the refused mint must never have burned a second relay ticket")
    }

    @Test
    fun an_overlapping_share_mint_is_refused_while_the_first_round_trip_is_in_flight() = runBlocking {
        val relay = GatedRelay()
        val service = ShareService(
            accountId = "acct", daemonPubB64 = "pub", relayWsBase = "wss://relay",
            ownerLabel = { null }, registry = registry("share"),
            mintTicket = { _ -> relay.mint() },
            interactivePairingPending = { false },
            revokeCredential = {},
            liveSessions = { emptyList() },
        )

        val first = async { service.create(CreateShare(sharedRoot.path)) }
        while (relay.mintCalls == 0) yield()

        val second = service.create(CreateShare(sharedRoot.path))
        assertFalse(second.ok, "the overlapped mint must be refused, not raced")
        assertTrue(second.error!!.contains("another pairing"), "got: ${second.error}")

        relay.answer.complete(Unit)
        val f = first.await()
        assertTrue(f.ok, f.error)
        assertEquals(1, relay.mintCalls, "the refused mint must never have burned a second relay ticket")
    }

    @Test
    fun an_overlapping_bridge_mint_is_refused_while_the_first_round_trip_is_in_flight() = runBlocking {
        val relay = GatedRelay()
        val service = BridgeService(
            accountId = "acct", daemonPubB64 = "pub", relayWsBase = "wss://relay",
            registry = registry("bridge"),
            mintTicket = { _ -> relay.mint() },
            interactivePairingPending = { false },
            revokeCredential = {},
            liveSessions = { emptyList() },
        )

        val first = async { service.create(CreateBridge("bot-a", listOf(sharedRoot.path))) }
        while (relay.mintCalls == 0) yield()

        val second = service.create(CreateBridge("bot-b", listOf(sharedRoot.path)))
        assertFalse(second.ok, "the overlapped mint must be refused, not raced")
        assertTrue(second.error!!.contains("another pairing"), "got: ${second.error}")

        relay.answer.complete(Unit)
        val f = first.await()
        assertTrue(f.ok, f.error)
        assertEquals(1, relay.mintCalls, "the refused mint must never have burned a second relay ticket")
    }

    /** Overlaps ACROSS mint classes must serialize on the same slot — a share mint during a
     *  collaborator's round-trip is exactly the two-button shape v1.6.1 added. */
    @Test
    fun a_share_mint_during_a_collaborator_round_trip_is_refused_too() = runBlocking {
        val reg = registry("cross")
        val relay = GatedRelay()
        val collaborator = collaboratorService(reg, relay)
        val share = ShareService(
            accountId = "acct", daemonPubB64 = "pub", relayWsBase = "wss://relay",
            ownerLabel = { null }, registry = reg,
            mintTicket = { _ -> relay.mint() },
            interactivePairingPending = { false },
            revokeCredential = {},
            liveSessions = { emptyList() },
        )

        val first = async { collaborator.createTicket("Frank") }
        while (relay.mintCalls == 0) yield()

        assertFalse(share.create(CreateShare(sharedRoot.path)).ok)

        relay.answer.complete(Unit)
        assertTrue(first.await().ok)
        assertEquals(1, relay.mintCalls)
    }

    /** The slot is a WINDOW, not a lock-out: a mint that fails (relay unreachable) must release it in
     *  `finally`, or one dropped connection would wedge minting until the safety expiry. */
    @Test
    fun a_failed_mint_releases_the_slot_for_the_next_attempt() = runBlocking {
        val reg = registry("release")
        var relayUp = false
        val service = CollaboratorService(
            accountId = "acct", daemonPubB64 = "pub", relayWsBase = "wss://relay",
            ownerLabel = { null }, registry = reg,
            store = CollaboratorStore.load(File(dir, "collab-release.json")),
            mintTicket = { if (relayUp) PairTicket("ticket-ok", 120, "111111") else null },
            interactivePairingPending = { false },
            revokeCredential = {},
        )
        assertFalse(service.createTicket("Frank").ok, "relay down: the mint fails")
        assertFalse(reg.intentPending(), "…and the slot must not stay claimed")
        relayUp = true
        assertTrue(service.createTicket("Frank").ok, "the next attempt goes through")
    }

    // ---- the slot's own semantics (BridgeRegistry.reserveMint / releaseMint) ----

    @Test
    fun the_mint_slot_is_exclusive_reads_as_pending_and_survives_until_released() {
        val reg = registry("slot")
        val t = 1_000_000L
        assertTrue(reg.reserveMint(now = t))
        assertFalse(reg.reserveMint(now = t + 1), "one slot: a second claim is refused")
        assertTrue(reg.intentPending(now = t + 1), "a claimed slot reads as a pending pairing")
        reg.releaseMint()
        assertFalse(reg.intentPending(now = t + 2))
        assertTrue(reg.reserveMint(now = t + 3), "released: the next mint can claim")
        reg.releaseMint()
        reg.releaseMint() // idempotent
        assertTrue(reg.reserveMint(now = t + 4))
    }

    @Test
    fun an_orphaned_claim_expires_on_its_own_and_a_recorded_intent_blocks_new_claims() {
        val reg = registry("expiry")
        val t = 1_000_000L
        assertTrue(reg.reserveMint(now = t))
        // never released (a caller that died before `finally`): the safety expiry unblocks minting
        assertFalse(reg.reserveMint(now = t + BridgeRegistry.MINT_RESERVE_MS - 1))
        assertTrue(reg.reserveMint(now = t + BridgeRegistry.MINT_RESERVE_MS + 1))
        reg.releaseMint()

        // a RECORDED intent (the post-mint state) blocks a new claim exactly like the old check did
        assertTrue(reg.recordIntent("tkt", BridgeSpec("b", emptyList()), ttlMs = 240_000, now = t))
        assertFalse(reg.reserveMint(now = t + 1))
    }
}
