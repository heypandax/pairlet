package dev.ccpocket.relay

import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.PushRegistrationOutcome
import dev.ccpocket.protocol.PushRegistrationResult
import dev.ccpocket.protocol.RegisterPush
import dev.ccpocket.protocol.Role
import dev.ccpocket.protocol.Route
import dev.ccpocket.relay.store.Device
import dev.ccpocket.relay.store.InMemoryRelayStore
import dev.ccpocket.relay.store.RelayStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Registration RECEIPTS on the device control plane (PROTO_V_PUSH_ACK).
 *
 * The bug this closes: `runCatching { setPushToken(...) }` discarded its own result, so "stored",
 * "the row is gone" and "storage raised" were indistinguishable — the phone queued the frame, called
 * itself registered, and stayed silently push-less forever. Each verdict below is one of the outcomes
 * the client must be able to tell apart, plus the two invariants that keep old peers safe: a request
 * with no requestId is answered with silence, and a receipt never carries anything but a code from the
 * closed vocabulary.
 */
class PushRegistrationAckTest {

    private val allowedCodes = setOf("forbidden", "no_device", "store_failed", "bad_request")

    private fun device(id: String, headless: Boolean = false, collaborator: Boolean = false) =
        Device(id, "acct", ByteArray(1), ByteArray(1), createdAt = 1, lastSeen = null, revoked = false,
            headless = headless, collaborator = collaborator)

    private fun control(body: dev.ccpocket.protocol.Frame): String =
        PocketJson.encodeToString(Envelope(id = "d", ts = 0, to = Route.RELAY, body = body))

    /** Runs one RegisterPush through the real handler and returns whatever the socket received. */
    private fun register(store: RelayStore, frame: RegisterPush): List<String> {
        val sent = mutableListOf<String>()
        val conn = Conn("acct", Role.DEVICE, "dev1", sendText = { sent += it }, sendBinary = {}, close = {})
        runBlocking { RelayServer("127.0.0.1", 0, store, clock = { 1_000 }).handleDeviceControl(conn, control(frame)) }
        return sent
    }

    private fun receiptOf(sent: List<String>): PushRegistrationResult {
        val body = PocketJson.decodeFromString<Envelope>(sent.single()).body
        val receipt = assertIs<PushRegistrationResult>(body)
        assertTrue(receipt.code == null || receipt.code in allowedCodes, "code outside the closed vocabulary: ${receipt.code}")
        return receipt
    }

    private fun seeded(vararg devices: Device) = InMemoryRelayStore().also { store ->
        runBlocking {
            store.insertAccount("acct", ByteArray(32), 1)
            devices.forEach { store.insertDevice(it) }
        }
    }

    @Test fun a_stored_token_is_acknowledged_with_its_own_requestId() {
        val store = seeded(device("dev1"))
        val receipt = receiptOf(register(store, RegisterPush("fcm", "tok-123", requestId = "rid-1")))

        assertEquals("rid-1", receipt.requestId) // echoed verbatim: the client matches on it
        assertEquals(PushRegistrationOutcome.STORED, receipt.result)
        assertNull(receipt.code)
        assertEquals("tok-123", runBlocking { store.pushTargets("acct") }.single().token)
    }

    @Test fun a_blank_token_is_acknowledged_as_cleared() {
        val store = seeded(device("dev1"))
        runBlocking { store.setPushToken("dev1", "fcm", "tok-123", 2) }

        val receipt = receiptOf(register(store, RegisterPush("fcm", "", requestId = "rid-2")))

        assertEquals(PushRegistrationOutcome.CLEARED, receipt.result)
        assertTrue(runBlocking { store.pushTargets("acct") }.isEmpty())
    }

    @Test fun a_plain_bridge_is_rejected_as_forbidden_and_never_stored() {
        val store = seeded(device("dev1", headless = true))

        val receipt = receiptOf(register(store, RegisterPush("fcm", "bot-tok", requestId = "rid-3")))

        // REJECTED, not FAILED: retrying cannot turn a bridge into a phone, and the client must stop.
        assertEquals(PushRegistrationOutcome.REJECTED, receipt.result)
        assertEquals("forbidden", receipt.code)
        assertNull(runBlocking { store.getDevice("dev1") }?.pushToken)
    }

    @Test fun a_collaborator_inbox_is_still_allowed_to_register() {
        val store = seeded(device("dev1", headless = true, collaborator = true))

        val receipt = receiptOf(register(store, RegisterPush("apns", "inbox-tok", requestId = "rid-4")))

        assertEquals(PushRegistrationOutcome.STORED, receipt.result)
    }

    @Test fun a_missing_device_row_fails_with_no_device() {
        // the credential authenticated but the row is gone (pruned/never inserted) — the old code path
        // silently dropped this and the phone believed it was registered
        val store = seeded()

        val receipt = receiptOf(register(store, RegisterPush("fcm", "tok", requestId = "rid-5")))

        assertEquals(PushRegistrationOutcome.REJECTED, receipt.result) // getDevice()==null → not allowed
        assertEquals("forbidden", receipt.code)
    }

    @Test fun a_store_that_loses_the_row_between_lookup_and_write_fails_with_no_device() {
        val backing = seeded(device("dev1"))
        val store = object : RelayStore by backing {
            override suspend fun setPushToken(deviceId: String, platform: String, token: String, now: Long) = false
        }

        val receipt = receiptOf(register(store, RegisterPush("fcm", "tok", requestId = "rid-6")))

        assertEquals(PushRegistrationOutcome.FAILED, receipt.result) // retryable: the client tries again later
        assertEquals("no_device", receipt.code)
    }

    @Test fun a_raising_store_fails_with_store_failed_and_leaks_nothing() {
        val backing = seeded(device("dev1"))
        val store = object : RelayStore by backing {
            override suspend fun setPushToken(deviceId: String, platform: String, token: String, now: Long): Boolean =
                throw IllegalStateException("SQLITE_BUSY on devices WHERE device_id='dev1'")
        }

        val receipt = receiptOf(register(store, RegisterPush("fcm", "secret-tok", requestId = "rid-7")))

        assertEquals(PushRegistrationOutcome.FAILED, receipt.result)
        assertEquals("store_failed", receipt.code)
        val wire = register(store, RegisterPush("fcm", "secret-tok", requestId = "rid-7")).single()
        assertTrue("SQLITE_BUSY" !in wire && "secret-tok" !in wire, "a receipt must carry no exception text and no token")
    }

    @Test fun a_blank_platform_or_oversized_token_is_a_bad_request() {
        val store = seeded(device("dev1"))

        assertEquals("bad_request", receiptOf(register(store, RegisterPush("", "tok", requestId = "rid-8"))).code)
        val huge = "x".repeat(4097)
        val oversized = receiptOf(register(store, RegisterPush("fcm", huge, requestId = "rid-9")))
        assertEquals(PushRegistrationOutcome.REJECTED, oversized.result)
        assertEquals("bad_request", oversized.code)
        assertNull(runBlocking { store.getDevice("dev1") }?.pushToken) // neither was written
    }

    @Test fun a_request_without_a_requestId_keeps_the_old_silent_behaviour() {
        val store = seeded(device("dev1"))

        // an already-shipped client sends no requestId and parses nothing back; answering it would just be
        // an unknown discriminator it drops — but the contract is silence, and it is worth pinning
        assertTrue(register(store, RegisterPush("fcm", "tok-legacy")).isEmpty())
        assertEquals("tok-legacy", runBlocking { store.pushTargets("acct") }.single().token)
    }

    @Test fun a_rejected_registration_is_still_acknowledged_when_it_asked_for_a_receipt() {
        // the silence-on-refusal of the old code is exactly what left a collaborator inbox guessing
        val store = seeded(device("dev1", headless = true))
        assertTrue(register(store, RegisterPush("fcm", "tok")).isEmpty())        // no requestId → silent
        assertTrue(register(store, RegisterPush("fcm", "tok", "rid-10")).isNotEmpty()) // opted in → answered
    }
}
