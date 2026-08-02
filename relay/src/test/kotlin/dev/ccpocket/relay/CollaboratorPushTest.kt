package dev.ccpocket.relay

import dev.ccpocket.protocol.NotifyPush
import dev.ccpocket.protocol.Role
import dev.ccpocket.relay.auth.Codec
import dev.ccpocket.relay.pairing.PairingService
import dev.ccpocket.relay.push.NotifyGate
import dev.ccpocket.relay.push.NotifyRoute
import dev.ccpocket.relay.push.PushSender
import dev.ccpocket.relay.push.SendResult
import dev.ccpocket.relay.push.StorePushService
import dev.ccpocket.relay.store.Db
import dev.ccpocket.relay.store.InMemoryRelayStore
import dev.ccpocket.relay.store.RelayStore
import dev.ccpocket.relay.store.SqliteRelayStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Relay-side Collaborator Link inbox behaviour (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §3.4).
 *
 * The property under test is a pair of things that must stay TRUE TOGETHER:
 *  - a contact's inbox can be woken (it registers a token, and a targeted push reaches it), and
 *  - a contact's inbox is still NEVER in the owner's account fan-out (it must not learn that the owner
 *    finished a turn, in which project, saying what).
 * Before §3.4 the second held only because the first was impossible. These tests pin both independently, so
 * a future change that "simplifies" the two markers back into one fails loudly.
 */
class CollaboratorPushTest {

    private fun stores() = listOf<Pair<String, RelayStore>>(
        "in-memory" to InMemoryRelayStore(),
        "sqlite" to SqliteRelayStore(Db.open(":memory:")),
    )

    /** Mint+redeem a device with the given markers, exactly as the wire path does. */
    private suspend fun device(
        store: RelayStore,
        account: String,
        seed: Byte,
        headless: Boolean = false,
        collaborator: Boolean = false,
    ): String {
        val pairing = PairingService(store)
        val mint = assertIs<PairingService.MintResult.Ok>(pairing.mint(account, headless = headless, collaborator = collaborator))
        val red = assertIs<PairingService.RedeemResult.Ok>(pairing.redeem(mint.ticket, Codec.b64uEnc(ByteArray(32) { seed })))
        return red.deviceId
    }

    // ---- authoritative marking ------------------------------------------------

    @Test fun collaborator_marker_comes_from_the_minting_ticket_and_survives_rebuilds() = runBlocking {
        for ((name, store) in stores()) {
            store.insertAccount("acct", ByteArray(32), 0)
            val inbox = device(store, "acct", 1, headless = true, collaborator = true)
            val bridge = device(store, "acct", 2, headless = true)
            val phone = device(store, "acct", 3)

            assertTrue(store.getDevice(inbox)!!.collaborator, "$name: a collaborator ticket must land as a collaborator")
            assertTrue(store.getDevice(inbox)!!.headless, "$name: a collaborator inbox is headless too — presence-invisible")
            assertFalse(store.getDevice(bridge)!!.collaborator, "$name: a plain bridge mint must NOT be marked collaborator")
            assertFalse(store.getDevice(phone)!!.collaborator)

            // a login touch rebuilds the row: dropping the marker here would silently move the inbox INTO
            // the owner's push fan-out (the issue #91 bug, one column over)
            store.touchDevice(inbox, 99)
            assertTrue(store.getDevice(inbox)!!.collaborator, "$name: touch must carry the marker")
            store.setPushToken(inbox, "apns", "tok", 100)
            assertTrue(store.getDevice(inbox)!!.collaborator, "$name: registering a token must carry the marker")
            assertTrue(store.getDevice(inbox)!!.headless, "$name: …and must not resurrect it as an interactive device")
        }
    }

    // ---- token registration is allowed, fan-out membership is not -------------

    @Test fun collaborator_may_register_a_token_but_a_bridge_may_not() = runBlocking {
        val store = InMemoryRelayStore()
        store.insertAccount("acct", ByteArray(32), 0)
        val inbox = device(store, "acct", 1, headless = true, collaborator = true)
        val bridge = device(store, "acct", 2, headless = true)
        val phone = device(store, "acct", 3)

        // this is the predicate RelayServer.handleDeviceControl enforces on RegisterPush
        assertTrue(store.getDevice(inbox)!!.mayRegisterPush, "an inbox must be able to register — else §3.4 is unreachable")
        assertFalse(store.getDevice(bridge)!!.mayRegisterPush, "issue #91: a bridge must never subscribe itself")
        assertTrue(store.getDevice(phone)!!.mayRegisterPush)
    }

    @Test fun collaborator_never_appears_in_the_account_push_fanout() = runBlocking {
        for ((name, store) in stores()) {
            store.insertAccount("acct", ByteArray(32), 0)
            val inbox = device(store, "acct", 1, headless = true, collaborator = true)
            val phone = device(store, "acct", 2)
            store.setPushToken(inbox, "apns", "inbox-tok", 1)
            store.setPushToken(phone, "apns", "phone-tok", 1)

            assertEquals(
                listOf(phone), store.pushTargets("acct").map { it.deviceId },
                "$name: the owner's session pushes must never reach a contact's inbox",
            )
        }
    }

    /** The regression that matters most: the owner's ordinary turn-complete push, end to end through the
     *  service that actually talks to APNs/FCM, must not put a byte on the contact's token. */
    @Test fun account_level_push_is_not_delivered_to_a_collaborator_inbox() = runBlocking {
        val store = InMemoryRelayStore()
        store.insertAccount("acct", ByteArray(32), 0)
        val inbox = device(store, "acct", 1, headless = true, collaborator = true)
        val phone = device(store, "acct", 2)
        store.setPushToken(inbox, "apns", "inbox-tok", 1)
        store.setPushToken(phone, "apns", "phone-tok", 1)
        val apns = RecordingSender()

        StorePushService(store, mapOf("apns" to apns)) {}
            .notify("acct", "cc-pocket", "Turn complete", NotifyRoute("/Users/owner/secret-project", "sess-1"))

        assertEquals(listOf("phone-tok"), apns.tokens, "a contact must not learn the owner finished a turn")
        // and the inbox really is registered — this is an EXCLUSION, not an accident of it having no token
        assertEquals("inbox-tok", store.pushTargetFor("acct", inbox)?.token)
    }

    // ---- targeted delivery ----------------------------------------------------

    @Test fun pushTargetFor_finds_the_inbox_and_refuses_another_accounts_device() = runBlocking {
        for ((name, store) in stores()) {
            store.insertAccount("mine", ByteArray(32), 0)
            store.insertAccount("theirs", ByteArray(32) { 7 }, 0)
            val inbox = device(store, "mine", 1, headless = true, collaborator = true)
            val stranger = device(store, "theirs", 2)
            store.setPushToken(inbox, "apns", "inbox-tok", 1)
            store.setPushToken(stranger, "fcm", "stranger-tok", 1)

            assertEquals("inbox-tok", store.pushTargetFor("mine", inbox)?.token, "$name: an inbox IS addressable by id")
            // the account column in the lookup IS the authorization: a daemon may only wake its own devices
            assertNull(store.pushTargetFor("mine", stranger), "$name: a daemon must not be able to wake another account's device")
            assertNull(store.pushTargetFor("mine", "no-such-device"), "$name")

            store.setPushToken(inbox, "apns", "", 2) // the recipient turned notifications off
            assertNull(store.pushTargetFor("mine", inbox), "$name: de-registration must make it unreachable")
        }
    }

    @Test fun revoked_inbox_is_unreachable_by_targeted_push() = runBlocking {
        for ((name, store) in stores()) {
            store.insertAccount("acct", ByteArray(32), 0)
            val inbox = device(store, "acct", 1, headless = true, collaborator = true)
            store.setPushToken(inbox, "apns", "inbox-tok", 1)
            assertNotNull(store.pushTargetFor("acct", inbox), "$name")

            store.revokeDevice("acct", inbox) // the owner severed the contact link
            assertNull(store.pushTargetFor("acct", inbox), "$name: a severed link must not keep buzzing")
        }
    }

    @Test fun notifyDevice_sends_to_exactly_one_token() = runBlocking {
        val store = InMemoryRelayStore()
        store.insertAccount("acct", ByteArray(32), 0)
        val inbox = device(store, "acct", 1, headless = true, collaborator = true)
        val phone = device(store, "acct", 2)
        store.setPushToken(inbox, "apns", "inbox-tok", 1)
        store.setPushToken(phone, "apns", "phone-tok", 1)
        val apns = RecordingSender()
        val route = NotifyRoute(handoffId = "h-42")

        StorePushService(store, mapOf("apns" to apns)) {}
            .notifyDevice("acct", inbox, "cc-pocket", "You have a new handoff offer. Open the app to see the details.", route)

        assertEquals(listOf("inbox-tok"), apns.tokens, "a targeted push must not fan out to the owner's phone")
        assertEquals(route, apns.routes.single())
    }

    @Test fun notifyDevice_on_a_foreign_or_tokenless_device_is_a_quiet_no_op() = runBlocking {
        val store = InMemoryRelayStore()
        store.insertAccount("mine", ByteArray(32), 0)
        store.insertAccount("theirs", ByteArray(32) { 7 }, 0)
        val stranger = device(store, "theirs", 2)
        store.setPushToken(stranger, "apns", "stranger-tok", 1)
        val apns = RecordingSender()
        val logs = mutableListOf<String>()

        StorePushService(store, mapOf("apns" to apns)) { logs += it }
            .notifyDevice("mine", stranger, "t", "b")

        assertTrue(apns.tokens.isEmpty(), "cross-account targeting must deliver nothing")
        assertTrue(logs.any { it.contains("NO registered token") }, "…and must say so rather than fail silently")
    }

    // ---- the presence gate ----------------------------------------------------

    @Test fun targeted_push_is_gated_on_the_target_alone() {
        val targeted = NotifyPush("cc-pocket", "offer", deviceId = "dev-b", handoffId = "h-1")

        // the target is offline → send, EVEN THOUGH the owner's own phone is attached (which is the normal
        // case: the initiator is on their phone right now, having just sent the offer)
        assertTrue(NotifyGate.shouldSend(targeted, interactiveDevices = 3, targetDeviceSockets = 0))
        // the target is online → don't: it already got HandoffUpdated on its own data plane
        assertFalse(NotifyGate.shouldSend(targeted, interactiveDevices = 0, targetDeviceSockets = 1))
        // urgency is not a lever on this path — the addressed device's own socket is the whole question
        assertFalse(NotifyGate.shouldSend(targeted.copy(urgent = true), interactiveDevices = 0, targetDeviceSockets = 1))
    }

    @Test fun account_fanout_gate_is_unchanged() {
        val plain = NotifyPush("t", "b", workdir = "/w", sessionId = "s")
        assertTrue(NotifyGate.shouldSend(plain, interactiveDevices = 0, targetDeviceSockets = 0))
        assertFalse(NotifyGate.shouldSend(plain, interactiveDevices = 1, targetDeviceSockets = 0))
        // issue #91: an urgent notify still lands with a phone attached elsewhere
        assertTrue(NotifyGate.shouldSend(plain.copy(urgent = true), interactiveDevices = 1, targetDeviceSockets = 0))
    }

    /**
     * The relay owns the copy of anything it puts on a CONTACT's lock screen. Every push before §3.4 went
     * from a person to their own devices, so the daemon writing the text was writing to itself; a targeted
     * push is the first that crosses to someone else's phone, and the sender sits on the far side of that
     * boundary. Without this, an owner who patched their daemon could render arbitrary text on a colleague's
     * lock screen under the cc-pocket app identity.
     */
    @Test fun a_contact_alert_uses_relay_copy_and_never_the_senders() {
        val hostile = NotifyPush(
            title = "cc-pocket Security",
            body = "Session expired — reopen and re-enter your pairing code at evil.example",
            deviceId = "dev-bob",
            handoffId = "h-1",
        )
        val alert = assertNotNull(NotifyGate.contactAlert(hostile))
        assertEquals("cc-pocket", alert.title)
        assertEquals("You have a new handoff offer. Open the app to see the details.", alert.body)
        assertFalse("evil.example" in alert.body)
        assertEquals(NotifyRoute(handoffId = "h-1"), alert.route)
    }

    @Test fun a_contact_alert_without_a_handoff_id_is_dropped_entirely() {
        // the workdir/sessionId shape aimed at a contact would (a) put the owner's project path on that
        // person's phone and (b) deep-link THEIR app at a session id of the owner's choosing
        val sessionShaped = NotifyPush("t", "b", workdir = "/Users/alice/secret", sessionId = "s", deviceId = "dev-bob")
        assertNull(NotifyGate.contactAlert(sessionShaped), "a contact alert must be an offer nudge or nothing")
        assertNull(NotifyGate.contactAlert(NotifyPush("t", "b", deviceId = "dev-bob")))
    }

    @Test fun a_push_to_the_owners_own_device_keeps_the_daemons_copy() {
        // no boundary is crossed there, so nothing has to be laundered
        val own = NotifyPush("Usage limit hit — proj", "Turn couldn't finish", workdir = "/w", sessionId = "s")
        val alert = NotifyGate.ownAlert(own)
        assertEquals("Usage limit hit — proj", alert.title)
        assertEquals(NotifyRoute("/w", "s"), alert.route)
    }

    @Test fun a_pre_issue91_bridge_row_with_a_stale_token_is_not_targetable() = runBlocking {
        for ((name, store) in stores()) {
            store.insertAccount("acct", ByteArray(32), 0)
            val bridge = device(store, "acct", 1, headless = true)
            // #91 closed token registration for bridges, but no migration cleared rows that registered
            // BEFORE it — simulate one by writing the token straight to the store
            store.setPushToken(bridge, "apns", "stale-bot-tok", 1)

            assertTrue(store.pushTargets("acct").isEmpty(), "$name")
            assertNull(store.pushTargetFor("acct", bridge), "$name: a bridge must be unreachable by id too")
        }
    }

    @Test fun offer_route_carries_the_handoff_id_and_nothing_else() {
        val offer = NotifyPush("cc-pocket", "offer", deviceId = "dev-b", handoffId = "h-1")
        val route = NotifyGate.routeOf(offer)
        assertEquals(NotifyRoute(handoffId = "h-1"), route)
        assertNull(route?.workdir, "an offer alert must not carry the initiator's project path")
        assertNull(route?.sessionId)

        // a session push is unchanged
        assertEquals(NotifyRoute("/w", "s"), NotifyGate.routeOf(NotifyPush("t", "b", workdir = "/w", sessionId = "s")))
        // …and if a peer ever sent both, the content-free one wins
        assertEquals(
            NotifyRoute(handoffId = "h"),
            NotifyGate.routeOf(NotifyPush("t", "b", workdir = "/w", sessionId = "s", handoffId = "h")),
        )
        assertNull(NotifyGate.routeOf(NotifyPush("t", "b")))
    }

    @Test fun broker_counts_a_single_devices_sockets() = runBlocking {
        val broker = Broker()
        val a = conn("acct", "dev-a")
        val b1 = conn("acct", "dev-b", headless = true)
        broker.attachDaemon(conn("acct", null, role = Role.DAEMON))
        broker.attachDevice(a); broker.attachDevice(b1)

        assertEquals(1, broker.deviceSocketCount("acct", "dev-b"))
        assertEquals(1, broker.deviceSocketCount("acct", "dev-a"))
        assertEquals(0, broker.deviceSocketCount("acct", "dev-c"))
        // a collaborator socket is presence-invisible at the ACCOUNT level, which is exactly why a targeted
        // push may not consult that counter
        assertEquals(1, broker.interactiveDeviceCount("acct"))

        broker.detachDevice(b1)
        assertEquals(0, broker.deviceSocketCount("acct", "dev-b"))
    }

    // ---- schema migration -----------------------------------------------------

    @Test fun migration_adds_the_column_to_an_old_db_and_is_idempotent() {
        val path = Files.createTempFile("relay-migrate", ".db").also { Files.delete(it) }.toString()
        // a PRE-§3.4 database: the schema as it shipped, with a device row already in it
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE accounts (account_id TEXT PRIMARY KEY, static_pubkey BLOB NOT NULL,
                      created_at INTEGER NOT NULL, last_seen INTEGER)
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE devices (device_id TEXT PRIMARY KEY, account_id TEXT NOT NULL,
                      device_pubkey BLOB NOT NULL, credential_hash BLOB NOT NULL, created_at INTEGER NOT NULL,
                      last_seen INTEGER, revoked INTEGER NOT NULL DEFAULT 0, push_platform TEXT,
                      push_token TEXT, push_updated_at INTEGER, headless INTEGER NOT NULL DEFAULT 0)
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE pairing_tickets (ticket_hash BLOB PRIMARY KEY, account_id TEXT NOT NULL,
                      created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0,
                      used_at INTEGER, headless INTEGER NOT NULL DEFAULT 0)
                    """.trimIndent(),
                )
                st.execute("INSERT INTO accounts VALUES('acct', x'00', 1, 1)")
                st.execute("INSERT INTO devices(device_id, account_id, device_pubkey, credential_hash, created_at) VALUES('legacy','acct',x'00',x'00',1)")
            }
        }

        // first open: the additive migration lands, and the pre-existing row defaults to "not a collaborator"
        runBlocking {
            val store = SqliteRelayStore(Db.open(path))
            val legacy = assertNotNull(store.getDevice("legacy"))
            assertFalse(legacy.collaborator, "every pre-existing device is a phone or a bridge, never an inbox")
            assertFalse(legacy.headless)
            store.setPushToken("legacy", "apns", "legacy-tok", 2)
            assertEquals(listOf("legacy"), store.pushTargets("acct").map { it.deviceId })
        }

        // second open: ALTER TABLE now fails with "duplicate column" and must be swallowed, and no data moves
        runBlocking {
            val store = SqliteRelayStore(Db.open(path))
            assertEquals("legacy-tok", store.pushTargetFor("acct", "legacy")?.token, "a re-run must not disturb existing rows")
            val inbox = device(store, "acct", 5, headless = true, collaborator = true)
            assertTrue(store.getDevice(inbox)!!.collaborator, "the migrated column must be writable after a re-run")
        }
        Files.deleteIfExists(java.nio.file.Path.of(path))
    }

    // ---- helpers --------------------------------------------------------------

    private fun conn(account: String, deviceId: String?, role: Role = Role.DEVICE, headless: Boolean = false) =
        Conn(account, role, deviceId, sendText = {}, sendBinary = {}, close = {}, headless = headless)

    private class RecordingSender : PushSender {
        val tokens = mutableListOf<String>()
        val routes = mutableListOf<NotifyRoute?>()
        override suspend fun send(token: String, title: String, body: String, route: NotifyRoute?): SendResult {
            tokens += token; routes += route; return SendResult.ACCEPTED
        }
    }
}
