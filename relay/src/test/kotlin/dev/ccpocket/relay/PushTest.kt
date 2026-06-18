package dev.ccpocket.relay

import dev.ccpocket.relay.push.LoggingPushService
import dev.ccpocket.relay.push.PushConfig
import dev.ccpocket.relay.push.PushSender
import dev.ccpocket.relay.push.StorePushService
import dev.ccpocket.relay.store.Db
import dev.ccpocket.relay.store.Device
import dev.ccpocket.relay.store.InMemoryRelayStore
import dev.ccpocket.relay.store.RelayStore
import dev.ccpocket.relay.store.SqliteRelayStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test fun skipsTokensWithNoSenderForPlatform() = runBlocking {
        val store = InMemoryRelayStore()
        store.insertAccount("acct", ByteArray(32), 1)
        store.insertDevice(device("a", "acct")); store.setPushToken("a", "vivo", "tokV", 2)
        val fcm = RecordingSender()

        StorePushService(store, mapOf("fcm" to fcm)) {}.notify("acct", "t", "b")

        assertTrue(fcm.sent.isEmpty()) // "vivo" has no configured sender — skipped, not crashed
    }

    @Test fun configFallsBackToLoggingWithoutCredentials() {
        assertIs<LoggingPushService>(PushConfig.load(InMemoryRelayStore()) { null })
    }

    private class RecordingSender : PushSender {
        val sent = mutableListOf<Pair<String, Pair<String, String>>>()
        override suspend fun send(token: String, title: String, body: String): Boolean {
            sent += token to (title to body); return true
        }
    }
}
