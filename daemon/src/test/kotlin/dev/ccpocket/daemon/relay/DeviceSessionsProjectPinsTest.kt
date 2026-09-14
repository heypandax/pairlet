package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.pins.MemoryProjectPinStore
import dev.ccpocket.daemon.pins.PinStoreState
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ProjectPinErrors
import dev.ccpocket.protocol.ProjectPinOp
import dev.ccpocket.protocol.ProjectPinsState
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionGone
import dev.ccpocket.protocol.SyncProjectPins
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The RELAY transport's project-pin plane (issue #362), end to end through real Noise sessions: a commit reaches
 * every subscribed owner connection with that connection's own subscription, never a legacy sibling or the
 * requester a second time; a revoked device stops receiving at once; and pin ownership follows ACCEPTED fetches,
 * not generic session promotion — a retired connection whose late frames make its session active again can
 * neither commit, nor re-register, nor read a single pin frame. Every check decrypts with a specific session.
 */
class DeviceSessionsProjectPinsTest {

    private val dir = createTempDirectory("ccp-ds-pins").toFile()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private class Harness(dir: File) {
        val identity = Identity.loadOrCreate(File(dir, "identity.json"))
        val core = DaemonCore(emptyMap(), projectPinStore = MemoryProjectPinStore(PinStoreState(incarnation = INC)))
        private val outbound = ConcurrentHashMap<String, Channel<ByteArray>>()
        fun channel(deviceId: String): Channel<ByteArray> = outbound.computeIfAbsent(deviceId) { Channel(Channel.UNLIMITED) }
        val sessions = DeviceSessions(
            core = core,
            identity = identity,
            store = File(dir, "devices.json"),
            bridges = BridgeRegistry(File(dir, "bridges.json")),
        ) { deviceId, payload -> channel(deviceId).trySend(payload) }
    }

    private suspend fun pair(h: Harness, deviceId: String): E2ECrypto.KeyPair {
        h.sessions.onMintedTicket("ticket-$deviceId")
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired(deviceId, b64.encodeToString(keys.publicRaw))
        return keys
    }

    private suspend fun handshake(h: Harness, deviceId: String, keys: E2ECrypto.KeyPair, psk: String?): E2ESession {
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, h.identity.e2ePubRaw, psk = (psk ?: "").encodeToByteArray())
        h.sessions.onFrame(deviceId, Wire.payload(Wire.HANDSHAKE, init.ephPublic))
        val resp = withTimeout(5_000) { h.channel(deviceId).receive() }
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(resp))
        val session = init.finish(Wire.payloadBody(resp))
        val info = assertNotNull(open(session, withTimeout(5_000) { h.channel(deviceId).receive() }) as? DaemonInfo)
        assertTrue(info.supportsProjectPins, "the relay advertises pin sync after every handshake")
        return session
    }

    /** The frame if [framed] decrypts under [session], else null (it was sealed for another connection). */
    private fun open(session: E2ESession, framed: ByteArray): Frame? {
        if (Wire.payloadType(framed) != Wire.TRANSPORT) return null
        val plain = session.open(Wire.payloadBody(framed)) ?: return null
        return PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body
    }

    private suspend fun send(h: Harness, deviceId: String, session: E2ESession, body: Frame) {
        val env = Envelope("0", 0L, body = body)
        h.sessions.onFrame(deviceId, Wire.payload(Wire.TRANSPORT, session.seal(PocketJson.encodeToString(env).encodeToByteArray())))
    }

    private suspend fun next(h: Harness, deviceId: String, session: E2ESession): Frame =
        assertNotNull(open(session, withTimeout(5_000) { h.channel(deviceId).receive() }), "frame did not decrypt for this connection")

    private suspend fun nothingFor(h: Harness, deviceId: String, ms: Long = 300) = withTimeoutOrNull(ms) { h.channel(deviceId).receive() }

    private fun stream(deviceId: String) = "stream-$deviceId-0123456789".take(40)
    private fun sub(tag: String) = "sub-$tag-0123456789abcdef".take(40)

    /** What a current App sends on connect: its capability declaration, then the fetch that subscribes. */
    private suspend fun subscribe(h: Harness, deviceId: String, session: E2ESession, subscription: String): ProjectPinsState {
        send(h, deviceId, session, ClientCaps(supportsProjectPins = true))
        send(h, deviceId, session, SyncProjectPins("fetch-${subscription.take(8)}", subscription, stream(deviceId)))
        val reply = next(h, deviceId, session) as ProjectPinsState
        assertNull(reply.error)
        assertEquals(subscription, reply.subscriptionId)
        return reply
    }

    private fun op(seq: Long, subscription: String, deviceId: String, path: String) =
        SyncProjectPins("op-$seq", subscription, stream(deviceId), listOf(ProjectPinOp(seq, path, pinned = true)), expectedIncarnation = INC)

    private suspend fun commit(h: Harness, deviceId: String, session: E2ESession, subscription: String, seq: Long, path: String) {
        send(h, deviceId, session, op(seq, subscription, deviceId, path))
        val ack = next(h, deviceId, session) as ProjectPinsState
        assertNull(ack.error)
        assertEquals(seq, ack.ackSeq)
    }

    @Test
    fun a_commit_reaches_subscribed_owners_but_never_a_legacy_sibling_or_the_requester_twice() = runBlocking {
        val h = Harness(dir)
        try {
            val a = handshake(h, "devA", pair(h, "devA"), "ticket-devA")
            subscribe(h, "devA", a, sub("a"))
            // an already-shipped App: no pin capability, but an attached owner device all the same
            val b = handshake(h, "devB", pair(h, "devB"), "ticket-devB")
            send(h, "devB", b, ClientCaps(supportsAgents = listOf("opencode")))
            send(h, "devB", b, SendPrompt("ghost", "hi"))
            assertTrue(next(h, "devB", b) is SessionGone)
            val c = handshake(h, "devC", pair(h, "devC"), "ticket-devC")
            subscribe(h, "devC", c, sub("c"))

            commit(h, "devA", a, sub("a"), 1, "/nonexistent-ccp/x")

            val push = next(h, "devC", c) as ProjectPinsState
            assertEquals(sub("c"), push.subscriptionId, "each connection sees its OWN subscription echoed")
            assertNull(push.requestId, "a push is not a reply")
            assertNull(push.ackSeq, "and never carries another client's cursor")
            assertNull(push.resolutions)
            assertEquals(listOf("/nonexistent-ccp/x"), push.snapshot?.pins?.map { it.path })
            assertNull(nothingFor(h, "devB"), "a legacy sibling on the same daemon gets no pin frame")
            assertNull(nothingFor(h, "devA"), "the requester already had the snapshot in its reply")
        } finally {
            h.core.scope.cancel()
        }
    }

    @Test
    fun a_revoked_device_receives_no_further_pins_and_loses_its_push_slot() = runBlocking {
        val h = Harness(dir)
        try {
            val a = handshake(h, "devA", pair(h, "devA"), "ticket-devA")
            subscribe(h, "devA", a, sub("a"))
            val c = handshake(h, "devC", pair(h, "devC"), "ticket-devC")
            subscribe(h, "devC", c, sub("c"))
            commit(h, "devA", a, sub("a"), 1, "/nonexistent-ccp/one")
            assertTrue(next(h, "devC", c) is ProjectPinsState)
            assertEquals(2, h.core.projectPins.subscriberCount())

            h.sessions.onDeviceRevoked("devC")
            assertEquals(1, h.core.projectPins.subscriberCount())
            commit(h, "devA", a, sub("a"), 2, "/nonexistent-ccp/two")
            assertNull(nothingFor(h, "devC"), "nothing is sealed toward a revoked device")
        } finally {
            h.core.scope.cancel()
        }
    }

    @Test
    fun an_accepted_fetch_retires_the_older_connection_even_when_its_late_frames_promote_it_again() = runBlocking {
        val h = Harness(dir)
        try {
            val keysA = pair(h, "devA")
            val a1 = handshake(h, "devA", keysA, "ticket-devA")
            subscribe(h, "devA", a1, sub("a1"))
            val d = handshake(h, "devD", pair(h, "devD"), "ticket-devD")
            subscribe(h, "devD", d, sub("d"))

            // the App reconnects: a fresh handshake by itself retires nothing — the fetched connection still owns pins
            val a2 = handshake(h, "devA", keysA, psk = null)
            commit(h, "devD", d, sub("d"), 1, "/nonexistent-ccp/one")
            val beforeFetch = withTimeout(5_000) { h.channel("devA").receive() }
            assertNull(open(a2, beforeFetch), "a connection with no accepted fetch cannot read a pin push")
            assertEquals(sub("a1"), (assertNotNull(open(a1, beforeFetch)) as ProjectPinsState).subscriptionId)

            subscribe(h, "devA", a2, sub("a2")) // accepted: a1 is retired for the rest of its lifetime

            // a1's delayed but cryptographically valid frames arrive now and promote its session for ordinary traffic…
            send(h, "devA", a1, op(1, sub("a1"), "devA", "/nonexistent-ccp/stale"))
            send(h, "devA", a1, SyncProjectPins("late-fetch", sub("a1"), stream("devA")))
            assertNull(nothingFor(h, "devA"), "…but a retired connection gets no pin reply and no subscription back")
            send(h, "devA", a1, SendPrompt("ghost", "hi"))
            assertTrue(next(h, "devA", a1) is SessionGone, "generic frames still follow the promoted session")

            commit(h, "devD", d, sub("d"), 2, "/nonexistent-ccp/two")
            val push = withTimeout(5_000) { h.channel("devA").receive() }
            assertNull(open(a1, push), "the promoted, retired connection cannot read the push")
            val state = assertNotNull(open(a2, push), "sealed with the pin owner's own session") as ProjectPinsState
            assertEquals(sub("a2"), state.subscriptionId)
            assertEquals(listOf("/nonexistent-ccp/two", "/nonexistent-ccp/one"), state.snapshot?.pins?.map { it.path }, "the stale batch committed nothing")
            assertNull(nothingFor(h, "devA"))
        } finally {
            h.core.scope.cancel()
        }
    }

    @Test
    fun a_late_fetch_of_the_older_connection_does_not_lock_out_the_newer_one_that_has_not_fetched_yet() = runBlocking {
        val h = Harness(dir)
        try {
            val keysA = pair(h, "devA")
            val s1 = handshake(h, "devA", keysA, "ticket-devA")
            subscribe(h, "devA", s1, sub("s1"))
            val d = handshake(h, "devD", pair(h, "devD"), "ticket-devD")
            subscribe(h, "devD", d, sub("d"))

            // S2 handshakes and declares, but its fetch is still in flight when S1's delayed, valid fetch lands
            val s2 = handshake(h, "devA", keysA, psk = null)
            send(h, "devA", s2, ClientCaps(supportsProjectPins = true))
            send(h, "devA", s1, SyncProjectPins("late-fetch", sub("s1"), stream("devA")))
            val refreshed = next(h, "devA", s1) as ProjectPinsState
            assertNull(refreshed.error, "the still-current owner's repeated fetch is accepted")
            assertEquals(sub("s1"), refreshed.subscriptionId)

            // …and S2's own fetch now succeeds with no further handshake
            send(h, "devA", s2, SyncProjectPins("fetch-s2", sub("s2"), stream("devA")))
            val fetchedFrame = withTimeout(5_000) { h.channel("devA").receive() }
            assertNull(open(s1, fetchedFrame), "the reply is sealed for S2 alone")
            val fetched = assertNotNull(open(s2, fetchedFrame)) as ProjectPinsState
            assertNull(fetched.error, "the newer connection was not retired by the older one's late fetch")
            assertEquals(sub("s2"), fetched.subscriptionId)

            commit(h, "devA", s2, sub("s2"), 1, "/nonexistent-ccp/by-s2")
            assertEquals(sub("d"), (next(h, "devD", d) as ProjectPinsState).subscriptionId)
            commit(h, "devD", d, sub("d"), 1, "/nonexistent-ccp/by-d")
            val push = withTimeout(5_000) { h.channel("devA").receive() }
            assertNull(open(s1, push))
            assertEquals(sub("s2"), (assertNotNull(open(s2, push), "the sibling push is sealed for S2") as ProjectPinsState).subscriptionId)

            // once S2 was accepted, S1 can never take pins back — not by fetching, not by batching
            send(h, "devA", s1, SyncProjectPins("again", sub("s1"), stream("devA")))
            send(h, "devA", s1, op(2, sub("s1"), "devA", "/nonexistent-ccp/stale"))
            assertNull(nothingFor(h, "devA"), "a retired older connection gets no pin reply")
            commit(h, "devD", d, sub("d"), 2, "/nonexistent-ccp/two")
            val after = withTimeout(5_000) { h.channel("devA").receive() }
            assertNull(open(s1, after))
            val state = assertNotNull(open(s2, after)) as ProjectPinsState
            assertEquals(sub("s2"), state.subscriptionId)
            assertEquals(listOf("/nonexistent-ccp/two", "/nonexistent-ccp/by-d", "/nonexistent-ccp/by-s2"), state.snapshot?.pins?.map { it.path }, "the stale batch committed nothing")
        } finally {
            h.core.scope.cancel()
        }
    }

    @Test
    fun an_older_connection_that_never_fetched_cannot_take_pins_after_a_newer_fetch_was_accepted() = runBlocking {
        val h = Harness(dir)
        try {
            val keysA = pair(h, "devA")
            val s1 = handshake(h, "devA", keysA, "ticket-devA")
            send(h, "devA", s1, ClientCaps(supportsProjectPins = true))
            val s2 = handshake(h, "devA", keysA, psk = null)
            subscribe(h, "devA", s2, sub("s2"))
            subscribe(h, "devA", s2, sub("s2")) // the same connection may refresh its own subscription

            send(h, "devA", s1, SyncProjectPins("late-fetch", sub("s1"), stream("devA")))
            assertNull(nothingFor(h, "devA"), "an older candidate gets no reply once a newer fetch was accepted")
            val d = handshake(h, "devD", pair(h, "devD"), "ticket-devD")
            subscribe(h, "devD", d, sub("d"))
            commit(h, "devD", d, sub("d"), 1, "/nonexistent-ccp/one")
            val push = withTimeout(5_000) { h.channel("devA").receive() }
            assertNull(open(s1, push))
            assertEquals(sub("s2"), (assertNotNull(open(s2, push)) as ProjectPinsState).subscriptionId)
        } finally {
            h.core.scope.cancel()
        }
    }

    @Test
    fun a_batch_before_the_fetch_is_refused_to_that_connection_without_registering_it() = runBlocking {
        val h = Harness(dir)
        try {
            val a = handshake(h, "devA", pair(h, "devA"), "ticket-devA")
            send(h, "devA", a, ClientCaps(supportsProjectPins = true))
            send(h, "devA", a, op(1, sub("a"), "devA", "/nonexistent-ccp/early"))
            val refused = next(h, "devA", a) as ProjectPinsState
            assertEquals(ProjectPinErrors.SUBSCRIPTION_STALE, refused.error)
            assertEquals("op-1", refused.requestId)
            assertNull(refused.snapshot)

            val d = handshake(h, "devD", pair(h, "devD"), "ticket-devD")
            subscribe(h, "devD", d, sub("d"))
            commit(h, "devD", d, sub("d"), 1, "/nonexistent-ccp/one")
            assertNull(nothingFor(h, "devA"), "the refusal registered nothing, so nothing is pushed")

            val fetched = subscribe(h, "devA", a, sub("a"))
            assertEquals(listOf("/nonexistent-ccp/one"), fetched.snapshot?.pins?.map { it.path }, "the early batch was never committed")
            assertEquals(0, fetched.ackSeq)
        } finally {
            h.core.scope.cancel()
        }
    }

    @Test
    fun a_pin_owner_that_redeclares_without_the_capability_gets_no_further_pin_frames() = runBlocking {
        val h = Harness(dir)
        try {
            val a = handshake(h, "devA", pair(h, "devA"), "ticket-devA")
            subscribe(h, "devA", a, sub("a"))
            val d = handshake(h, "devD", pair(h, "devD"), "ticket-devD")
            subscribe(h, "devD", d, sub("d"))

            send(h, "devA", a, ClientCaps()) // the same connection now speaks for a build without pin support
            commit(h, "devD", d, sub("d"), 1, "/nonexistent-ccp/one")
            assertNull(nothingFor(h, "devA"), "no push for a connection that stopped declaring the capability")
            send(h, "devA", a, SyncProjectPins("fetch-again", sub("a"), stream("devA")))
            assertNull(nothingFor(h, "devA"), "…and no reply either")
        } finally {
            h.core.scope.cancel()
        }
    }

    private companion object {
        const val INC = "inc-0123456789abcdef"
    }
}
