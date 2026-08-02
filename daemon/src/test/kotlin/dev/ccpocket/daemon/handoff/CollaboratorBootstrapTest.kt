package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.relay.DeviceSessions
import dev.ccpocket.daemon.schedule.ScheduleStore
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CreateHandoff
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.HandoffKind
import dev.ccpocket.protocol.HandoffListing
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.HandoffUpdated
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListHandoffs
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RECIPIENT BOOTSTRAP over the real transport (SESSION-HANDOFF.md §3.2.3/§3.2.4 of the implementation
 * review): everything a collaborator App does between "redeemed the invite" and "sees the offer",
 * driven through [DeviceSessions] — a real Noise handshake with a COLLABORATOR credential, real sealed
 * frames, the production ingress gates — instead of a fixture calling `routeAsCollaborator` by hand.
 *
 * What must hold for the inbox connection to work at all:
 *  1. the connection's FIRST frame is an UNFILTERED `ListHandoffs()` (the App has no sessionId/workdir
 *     yet) and it is ANSWERED — a collaborator never asks for directories, so it must not have to;
 *  2. the listing carries only the offers addressed to THIS device;
 *  3. the same connection becomes a recipient-filtered FAN-OUT target: an offer created later is
 *     PUSHED to it unsolicited (that is how an offer arrives while the App just sits in its inbox),
 *     and another contact's offer never is;
 *  4. no DaemonInfo is ever sealed toward it (the App must not classify it as a "computer" and start
 *     waiting for `Directories` — the "your computer is offline" bug);
 *  5. the discovery plane stays refused.
 */
class CollaboratorBootstrapTest {

    private val dir = createTempDirectory("ccp-collab-bootstrap").toFile()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private class Harness(dir: File) {
        val identity = Identity.loadOrCreate(File(dir, "identity.json"))
        val bridges = BridgeRegistry(File(dir, "bridges.json"))
        val outbound = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)
        val handoffs = HandoffService(HandoffRegistry(HandoffStore.load(File(dir, "handoffs.json"))))
        val core = DaemonCore(
            emptyMap(), // no backends: this test never opens a session, only the handoff/inbox plane
            prefs = DaemonPrefs.load(File(dir, "prefs.json")),
            presetStore = PresetStore.load(File(dir, "presets.json")),
            scheduleStore = ScheduleStore.load(File(dir, "schedules.json")),
            handoffs = handoffs,
        )
        val sessions = DeviceSessions(
            core = core,
            identity = identity,
            store = File(dir, "devices.json"),
            bridges = bridges,
        ) { deviceId, payload -> outbound.trySend(deviceId to payload) }
    }

    private suspend fun handshake(h: Harness, deviceId: String, keys: E2ECrypto.KeyPair, ticket: String): E2ESession {
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, h.identity.e2ePubRaw, psk = ticket.encodeToByteArray())
        h.sessions.onFrame(deviceId, Wire.payload(Wire.HANDSHAKE, init.ephPublic))
        val (_, resp) = h.outbound.receive()
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(resp))
        return init.finish(Wire.payloadBody(resp))
    }

    private fun decode(session: E2ESession, framed: ByteArray): Frame {
        assertEquals(Wire.TRANSPORT, Wire.payloadType(framed))
        val plain = assertNotNull(session.open(Wire.payloadBody(framed)), "the frame must decrypt for this device")
        return PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body
    }

    private suspend fun send(h: Harness, deviceId: String, session: E2ESession, body: Frame) {
        val env = Envelope("0", 0L, body = body)
        h.sessions.onFrame(deviceId, Wire.payload(Wire.TRANSPORT, session.seal(PocketJson.encodeToString(env).encodeToByteArray())))
    }

    /** Bind a COLLABORATOR credential exactly the way CollaboratorService.createTicket does. */
    private fun bindCollaborator(h: Harness, deviceId: String, ticket: String, label: String): E2ECrypto.KeyPair {
        h.bridges.recordIntent(ticket, BridgeSpec.collaborator(label), ttlMs = 240_000)
        h.sessions.onMintedTicket(ticket, headless = true) // a contact link is a headless-class ticket
        val keys = E2ECrypto.generateKeyPair()
        runBlocking { h.sessions.onDevicePaired(deviceId, h.b64().encodeToString(keys.publicRaw)) }
        return keys
    }

    private fun Harness.b64() = Base64.getUrlEncoder().withoutPadding()

    /** The owner's side of an offer — created through the registry (the owner device has no transport
     *  in this test), which is the same entity the router's CreateHandoff writes. */
    private fun offer(h: Harness, sessionId: String, recipient: String) =
        (
            h.handoffs.registry.create(
                sourceSessionId = sessionId, workdir = "/w", agent = AgentKind.CLAUDE,
                initiatorDeviceId = "devOwner", kind = HandoffKind.REVIEW, access = HandoffAccess.REVIEW_READ_ONLY,
                brief = HandoffBrief(request = "review the recall path"), recipientDeviceId = recipient,
            ) as HandoffRegistry.HandoffOutcome.Ok
            ).handoff

    /** Next frame sealed toward [deviceId], decoded. */
    private suspend fun next(h: Harness, deviceId: String, session: E2ESession): Frame = withTimeout(5_000) {
        val (to, payload) = h.outbound.receive()
        assertEquals(deviceId, to)
        decode(session, payload)
    }

    @Test
    fun a_collaborator_connects_lists_its_offers_and_then_receives_them_pushed() = runBlocking {
        val h = Harness(dir)
        val keys = bindCollaborator(h, "devFrank", "collab-ticket-1", "Frank")
        // a contact key never lands in the full-power allow-list
        assertTrue("devFrank" !in PairedDevices.load(File(dir, "devices.json")).keys)

        // an offer is already waiting for Frank when he connects, plus one for someone else entirely
        val mine = offer(h, "sess-frank", "devFrank")
        offer(h, "sess-alex", "devAlex")

        val session = handshake(h, "devFrank", keys, "collab-ticket-1")
        // (4) the handshake seals NOTHING toward a collaborator — no DaemonInfo, so the App can't mistake
        // this binding for a "computer" and start waiting on Directories
        assertTrue(h.outbound.tryReceive().isFailure, "a collaborator must not receive the LAN/DaemonInfo handshake frame")

        // (1)+(2) the inbox connection's FIRST frame: an unfiltered ListHandoffs()
        send(h, "devFrank", session, ListHandoffs())
        val listing = assertNotNull(next(h, "devFrank", session) as? HandoffListing, "an unfiltered ListHandoffs must be answered")
        assertEquals(listOf(mine.id), listing.items.map { it.id }, "only offers addressed to this device are visible")
        assertTrue(h.bridges.isCollaborator("devFrank"), "the first transport frame confirmed the credential")

        // (3) that same connection is now a fan-out target: a LATER offer arrives unsolicited…
        val fresh = offer(h, "sess-frank-2", "devFrank")
        h.handoffs.broadcast(listOf(fresh))
        val pushed = assertNotNull(next(h, "devFrank", session) as? HandoffUpdated, "a new offer must be PUSHED to the inbox")
        assertEquals(fresh.id, pushed.handoff.id)
        assertEquals(HandoffStatus.WAITING, pushed.handoff.status)

        // …while another contact's offer never reaches this device
        h.handoffs.broadcast(listOf(offer(h, "sess-alex-2", "devAlex")))
        assertTrue(h.outbound.tryReceive().isFailure, "another collaborator's offer must never be sealed toward this one")
    }

    @Test
    fun the_inbox_connection_never_opens_the_discovery_or_owner_plane() = runBlocking {
        val h = Harness(dir)
        val keys = bindCollaborator(h, "devFrank2", "collab-ticket-2", "Frank")
        val session = handshake(h, "devFrank2", keys, "collab-ticket-2")

        // (5) the folder/session discovery plane is refused at the capability whitelist…
        send(h, "devFrank2", session, ListDirectories())
        assertEquals("collaborator_forbidden", (next(h, "devFrank2", session) as PocketError).code)
        send(h, "devFrank2", session, ListSessions("/w"))
        assertEquals("collaborator_forbidden", (next(h, "devFrank2", session) as PocketError).code)
        // …and so is the initiator plane (a collaborator can never create a handoff of its own)
        send(h, "devFrank2", session, CreateHandoff("/w", "sess-x", HandoffBrief(request = "do my work")))
        assertEquals("collaborator_forbidden", (next(h, "devFrank2", session) as PocketError).code)

        // but its OWN inbox pull still works after those refusals (the connection is not poisoned)
        send(h, "devFrank2", session, ListHandoffs())
        assertTrue(next(h, "devFrank2", session) is HandoffListing)
    }
}
