package dev.ccpocket.relay

import dev.ccpocket.relay.push.LoggingPushService
import dev.ccpocket.relay.push.NotifyRoute
import dev.ccpocket.relay.push.PushConfig
import dev.ccpocket.relay.push.PushSender
import dev.ccpocket.relay.push.SendResult
import dev.ccpocket.relay.push.StorePushService
import dev.ccpocket.relay.store.Db
import dev.ccpocket.relay.store.Device
import dev.ccpocket.relay.store.InMemoryRelayStore
import dev.ccpocket.relay.store.RelayStore
import dev.ccpocket.relay.store.SqliteRelayStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PushTest {
    private fun device(id: String, account: String) =
        Device(id, account, ByteArray(1), ByteArray(1), createdAt = 1, lastSeen = null, revoked = false)

    @Test fun storesAndClearsToken_inMemory() = runBlocking { roundTrip(InMemoryRelayStore()) }

    @Test fun storesAndClearsToken_sqlite() = runBlocking { roundTrip(SqliteRelayStore(Db.open(":memory:"))) }

    /** register → pushTargets sees it; blank token de-registers → pushTargets drops it. */
    private suspend fun roundTrip(store: RelayStore) {
        store.insertAccount("acct", ByteArray(32), 1)
        store.insertDevice(device("dev1", "acct"))
        assertTrue(store.pushTargets("acct").isEmpty()) // no token yet

        store.setPushToken("dev1", "fcm", "tok-123", 2)
        val targets = store.pushTargets("acct")
        assertEquals(1, targets.size)
        assertEquals("fcm", targets[0].platform)
        assertEquals("tok-123", targets[0].token)

        store.setPushToken("dev1", "fcm", "", 3) // opt-out clears it
        assertTrue(store.pushTargets("acct").isEmpty())
    }

    @Test fun repeatedRegistrationIsIdempotent_inMemory() = runBlocking { idempotentRegistration(InMemoryRelayStore()) }

    @Test fun repeatedRegistrationIsIdempotent_sqlite() = runBlocking { idempotentRegistration(SqliteRelayStore(Db.open(":memory:"))) }

    /** The phone re-sends its token on every cold start / reconnect (issue #114 fix ①). Re-registering the
     *  same token is a no-op; a rotated token replaces in place — never a duplicate row or a stale leftover. */
    private suspend fun idempotentRegistration(store: RelayStore) {
        store.insertAccount("acct", ByteArray(32), 1)
        store.insertDevice(device("d", "acct"))
        store.setPushToken("d", "apns", "tok", 2)
        store.setPushToken("d", "apns", "tok", 3) // cold-start refresh with the same token
        assertEquals(listOf("tok"), store.pushTargets("acct").map { it.token })
        store.setPushToken("d", "apns", "rotated", 4) // a genuinely rotated token
        assertEquals(listOf("rotated"), store.pushTargets("acct").map { it.token })
    }

    @Test fun clearPushTokenIsConditional_inMemory() = runBlocking { conditionalClear(InMemoryRelayStore()) }

    @Test fun clearPushTokenIsConditional_sqlite() = runBlocking { conditionalClear(SqliteRelayStore(Db.open(":memory:"))) }

    /** 410-driven cleanup must not wipe a token the device re-registered between send and prune, and must be
     *  idempotent (clearing an already-cleared token is a harmless no-op). */
    private suspend fun conditionalClear(store: RelayStore) {
        store.insertAccount("acct", ByteArray(32), 1)
        store.insertDevice(device("d", "acct"))
        store.setPushToken("d", "apns", "old", 2)
        store.setPushToken("d", "apns", "new", 3) // device rotated to a fresh token
        assertFalse(store.clearPushToken("d", "apns", "old", 4)) // stale prune request — must NOT clear
        assertEquals("new", store.pushTargets("acct").single().token)

        assertTrue(store.clearPushToken("d", "apns", "new", 5)) // pruning the current token succeeds
        assertTrue(store.pushTargets("acct").isEmpty())
        assertFalse(store.clearPushToken("d", "apns", "new", 6)) // idempotent — nothing left to clear
    }

    @Test fun routesEachTokenToItsPlatformSender() = runBlocking {
        val store = InMemoryRelayStore()
        store.insertAccount("acct", ByteArray(32), 1)
        store.insertDevice(device("a", "acct")); store.setPushToken("a", "apns", "tokA", 2)
        store.insertDevice(device("b", "acct")); store.setPushToken("b", "fcm", "tokB", 2)
        val apns = RecordingSender(); val fcm = RecordingSender()

        StorePushService(store, mapOf("apns" to apns, "fcm" to fcm)) {}.notify("acct", "title", "body")

        assertEquals(listOf("tokA" to ("title" to "body")), apns.sent)
        assertEquals(listOf("tokB" to ("title" to "body")), fcm.sent)
    }

    @Test fun forwardsSessionRouteToSenders() = runBlocking {
        val store = InMemoryRelayStore()
        store.insertAccount("acct", ByteArray(32), 1)
        store.insertDevice(device("a", "acct")); store.setPushToken("a", "apns", "tokA", 2)
        val apns = RecordingSender()
        val route = NotifyRoute("/Users/x/proj", "sess-1")

        StorePushService(store, mapOf("apns" to apns)) {}.notify("acct", "title", "body", route)

        assertEquals(route, apns.routes.single()) // deep-link routing reaches the sender intact
    }

    @Test fun skipsTokensWithNoSenderForPlatform() = runBlocking {
        val store = InMemoryRelayStore()
        store.insertAccount("acct", ByteArray(32), 1)
        store.insertDevice(device("a", "acct")); store.setPushToken("a", "vivo", "tokV", 2)
        val fcm = RecordingSender()

        StorePushService(store, mapOf("fcm" to fcm)) {}.notify("acct", "t", "b")

        assertTrue(fcm.sent.isEmpty()) // "vivo" has no configured sender — skipped, not crashed
    }

    @Test fun prunesInvalidTokenButKeepsAccepted() = runBlocking {
        val store = InMemoryRelayStore()
        store.insertAccount("acct", ByteArray(32), 1)
        store.insertDevice(device("dead", "acct")); store.setPushToken("dead", "apns", "dead-tok", 2)
        store.insertDevice(device("live", "acct")); store.setPushToken("live", "apns", "live-tok", 2)
        val logs = mutableListOf<String>()
        val sender = FnSender { token -> if (token == "dead-tok") SendResult.INVALID_TOKEN else SendResult.ACCEPTED }

        StorePushService(store, mapOf("apns" to sender), now = { 9 }) { logs += it }.notify("acct", "t", "b")

        // the 410'd token is gone; the accepted one stays — no more pushing into the void
        assertEquals(listOf("live-tok"), store.pushTargets("acct").map { it.token })
        assertTrue(logs.any { it.contains("dropped invalid token") })
        assertFalse(logs.any { it.contains("WARN") }) // at least one device accepted → not a full failure
    }

    @Test fun keepsTokenOnTransientFailure() = runBlocking {
        val store = InMemoryRelayStore()
        store.insertAccount("acct", ByteArray(32), 1)
        store.insertDevice(device("a", "acct")); store.setPushToken("a", "apns", "tokA", 2)
        val sender = FnSender { SendResult.FAILED }

        StorePushService(store, mapOf("apns" to sender)) {}.notify("acct", "t", "b")

        assertEquals(1, store.pushTargets("acct").size) // a network blip must NOT drop the token
    }

    @Test fun treatsThrownIoAsTransientAndKeepsToken() = runBlocking {
        val store = InMemoryRelayStore()
        store.insertAccount("acct", ByteArray(32), 1)
        store.insertDevice(device("a", "acct")); store.setPushToken("a", "apns", "tokA", 2)
        val logs = mutableListOf<String>()
        val sender = FnSender { throw RuntimeException("Connection reset") }

        StorePushService(store, mapOf("apns" to sender)) { logs += it }.notify("acct", "t", "b")

        assertEquals(1, store.pushTargets("acct").size) // thrown I/O is transient — keep the token
        assertTrue(logs.any { it.contains("send failed") })
    }

    @Test fun warnsAndCountsConsecutiveFullFailures() = runBlocking {
        val store = InMemoryRelayStore()
        store.insertAccount("acct", ByteArray(32), 1)
        store.insertDevice(device("a", "acct")); store.setPushToken("a", "apns", "tokA", 2)
        val logs = mutableListOf<String>()
        var outcome = SendResult.FAILED // transient so the token survives each round
        val sender = FnSender { outcome }
        val svc = StorePushService(store, mapOf("apns" to sender)) { logs += it }

        svc.notify("acct", "t", "b") // full failure #1
        svc.notify("acct", "t", "b") // full failure #2 (streak climbs)
        outcome = SendResult.ACCEPTED
        svc.notify("acct", "t", "b") // success resets the streak
        outcome = SendResult.FAILED
        svc.notify("acct", "t", "b") // full failure — streak restarts at 1

        val warns = logs.filter { it.contains("WARN") }
        assertTrue(warns.any { it.contains("consecutive=2") }, "streak should climb across back-to-back failures")
        // reset proven: "consecutive=1" appears both at the start and again after the success
        assertEquals(2, warns.count { it.contains("consecutive=1") })
    }

    @Test fun configFallsBackToLoggingWithoutCredentials() {
        assertIs<LoggingPushService>(PushConfig.load(InMemoryRelayStore()) { null })
    }

    private class RecordingSender : PushSender {
        val sent = mutableListOf<Pair<String, Pair<String, String>>>()
        val routes = mutableListOf<NotifyRoute?>()
        override suspend fun send(token: String, title: String, body: String, route: NotifyRoute?): SendResult {
            sent += token to (title to body); routes += route; return SendResult.ACCEPTED
        }
    }

    /** A sender whose per-token outcome is decided by [decide] (may also throw to simulate an I/O error). */
    private class FnSender(private val decide: (String) -> SendResult) : PushSender {
        override suspend fun send(token: String, title: String, body: String, route: NotifyRoute?): SendResult =
            decide(token)
    }
}
