package dev.ccpocket.daemon.relay

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The expiry edge of an owner pairing (security review of phase 0): the announce that pops the ticket and the
 * CLI waiting on the pairing must agree. Whichever reaches the watch first decides — a claimed pairing is
 * never reported "expired", and an expired one can never be claimed (so its ticket cannot anchor a key).
 */
class OwnerPairingWatchTest {

    private var now = 1_000L
    private val watch = OwnerPairingWatch { now }

    @Test
    fun a_claim_just_before_expiry_is_reported_as_its_outcome_not_as_expired() = runBlocking<Unit> {
        watch.open("p", expiresAt = 2_000)
        now = 1_999
        assertTrue(watch.tryClaim("p"), "inside the lifetime the announce may use the ticket")
        now = 5_000 // the announce is still being processed when the CLI asks again, past the expiry time
        assertIs<OwnerPairingWatch.Outcome.Pending>(watch.await("p", 10), "a claimed pairing waits for its announce")
        watch.resolve("p", OwnerPairingWatch.Outcome.Paired("dev", byteArrayOf(1)))
        assertEquals("dev", assertIs<OwnerPairingWatch.Outcome.Paired>(watch.await("p", 10)).deviceId)
    }

    @Test
    fun once_the_cli_saw_it_expire_no_announce_can_claim_it() = runBlocking<Unit> {
        watch.open("p", expiresAt = 2_000)
        now = 2_000
        assertIs<OwnerPairingWatch.Outcome.Expired>(watch.await("p", 10))
        assertFalse(watch.tryClaim("p"), "an expired pairing's ticket must not anchor anything")
        watch.resolve("p", OwnerPairingWatch.Outcome.Paired("late", byteArrayOf(1)))
        assertIs<OwnerPairingWatch.Outcome.Expired>(watch.await("p", 10), "the first outcome stands")
    }

    @Test
    fun an_expired_pairing_cannot_be_claimed_even_if_nobody_was_waiting() {
        watch.open("p", expiresAt = 2_000)
        now = 2_001
        assertFalse(watch.tryClaim("p"))
        assertEquals(0, watch.remainingMs("p"))
    }

    @Test
    fun unknown_and_untracked_pairings() = runBlocking<Unit> {
        assertIs<OwnerPairingWatch.Outcome.Unknown>(watch.await("nope", 10))
        assertTrue(watch.tryClaim("nope"), "an untracked pairing is left to the ticket's own lifetime check")
        watch.open("p", expiresAt = 2_000)
        assertEquals(1_000, watch.remainingMs("p"))
        assertIs<OwnerPairingWatch.Outcome.Pending>(watch.await("p", 10))
    }
}
