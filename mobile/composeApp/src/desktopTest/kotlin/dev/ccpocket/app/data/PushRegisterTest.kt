package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.push.PushToken
import dev.ccpocket.protocol.RegisterPush
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct-LAN push registration (#114 follow-up): a phone whose daemon is always on the same LAN never
 * relay-attaches, so the old "register on the next real relay attach" deferral never fired — the relay
 * kept a dead (or no) token forever. The repository must instead deposit the token through the one-shot
 * relay control dial: still deduped against redundant re-sends, rolled back when a dial fails so a
 * later attempt isn't skipped, and self-retrying while the direct link stays up.
 */
class PushRegisterTest {

    private fun repo(dial: suspend (PairedDaemon, RegisterPush) -> Unit) =
        PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
            paired.value = PairedDaemon(
                relay = "wss://unit-test", accountId = "acct", daemonPub = "pk", deviceId = "dev-1", credential = "cred",
            )
            useRelay = true
            directLinkUp = { true } // an established direct-LAN link — the transport with no relay control plane
            notificationsOn.value = true // independent of whatever a previous test/user run persisted
            pushDial = dial
            pushDialRetryMs = 5
        }

    @Test fun directActiveDepositsTheTokenInsteadOfSkipping() {
        val calls = CopyOnWriteArrayList<RegisterPush>()
        val r = repo { _, f -> calls += f }
        r.onPushToken(PushToken("ios", "tok-A"))
        assertEquals(listOf(RegisterPush("ios", "tok-A")), calls.toList(), "a direct-LAN transport must dial the relay, not strand the token")
    }

    @Test fun unchangedTokenIsDedupedButAChangedOneGoesThrough() {
        val calls = CopyOnWriteArrayList<RegisterPush>()
        val r = repo { _, f -> calls += f }
        r.onPushToken(PushToken("ios", "tok-A"))
        r.onPushToken(PushToken("ios", "tok-A")) // e.g. the reconnect storm re-delivering the same token
        assertEquals(1, calls.size, "an unchanged token must not redial (no re-registration storm)")
        r.onPushToken(PushToken("ios", "tok-B")) // a real APNs rotation
        assertEquals(listOf(RegisterPush("ios", "tok-A"), RegisterPush("ios", "tok-B")), calls.toList())
    }

    @Test fun failedDialRollsBackTheDedupSoTheNextTriggerRetries() {
        val calls = CopyOnWriteArrayList<RegisterPush>()
        var fail = true
        var attempts = 0
        val r = repo { _, f -> attempts++; if (fail) error("relay unreachable"); calls += f }
        r.onPushToken(PushToken("ios", "tok-A")) // dial throws — the dedup guard must roll back
        assertTrue(calls.isEmpty())
        fail = false
        r.onPushToken(PushToken("ios", "tok-A")) // the SAME token again — must not be skipped as "already sent"
        assertEquals(listOf(RegisterPush("ios", "tok-A")), calls.toList(), "a failed deposit must not satisfy the dedup guard")
        assertEquals(2, attempts)
    }

    @Test fun failedDialAutoRetriesWhileTheDirectLinkStaysUp() = runBlocking {
        val calls = CopyOnWriteArrayList<RegisterPush>()
        var attempts = 0
        val r = repo { _, f -> attempts++; if (attempts == 1) error("blip"); calls += f }
        r.onPushToken(PushToken("android", "tok-B"))
        assertTrue(calls.isEmpty(), "first dial failed — nothing deposited yet")
        // no further external trigger: the 5ms retry timer must redial on its own
        withTimeout(5_000) { while (calls.isEmpty()) delay(10) }
        assertEquals(listOf(RegisterPush("android", "tok-B")), calls.toList())
    }

    @Test fun relayTransportRidesTheLiveControlPlaneNotTheDial() {
        val calls = CopyOnWriteArrayList<RegisterPush>()
        val r = repo { _, f -> calls += f }
        r.directLinkUp = { false } // the main transport IS the relay — its control plane handles registration
        r.onPushToken(PushToken("ios", "tok-C"))
        assertTrue(calls.isEmpty(), "with a live relay control plane there is nothing to dial")
    }

    @Test fun notificationsOffDepositsAnEmptyTokenOverTheDial() {
        val calls = CopyOnWriteArrayList<RegisterPush>()
        val r = repo { _, f -> calls += f }
        r.onPushToken(PushToken("ios", "tok-A"))
        r.notificationsOn.value = false // direct state write — keeps the persisted Settings value untouched
        r.onPushToken(PushToken("ios", "tok-A")) // retrigger: (platform, "") differs from the last send
        assertEquals("", calls.last().token, "turning notifications off on the LAN must still de-register at the relay")
    }
}
