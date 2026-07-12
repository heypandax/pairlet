package dev.ccpocket.daemon.bridge

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import dev.ccpocket.daemon.relay.DeviceSessions
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The end-to-end enforcement path (issue #91): a real Noise handshake as a bridge device, then the
 * two security guarantees observed on the wire —
 *   1. a bridge that sends a verdict is REFUSED (bridge_forbidden), the verdict never routes;
 *   2. non-whitelisted egress (the handshake DaemonInfo) is DROPPED — a bridge can't be handed the
 *      LAN address, and the same filter drops a PermissionAsk (the reason the guarantee matters).
 * A normal device receives that DaemonInfo, proving the filter is bridge-specific, not a bug.
 */
class DeviceSessionsBridgeTest {

    private val dir = createTempDirectory("ccp-ds-bridge").toFile()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private class Harness(dir: File) {
        val identity = Identity.loadOrCreate(File(dir, "identity.json"))
        val bridges = BridgeRegistry(File(dir, "bridges.json"))
        val outbound = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)
        val sessions = DeviceSessions(
            core = DaemonCore(emptyMap()),
            identity = identity,
            store = File(dir, "devices.json"),
            bridges = bridges,
        ) { deviceId, payload -> outbound.trySend(deviceId to payload) }
    }

    /** Run the initiator (device) side of the handshake against [h] and return the live device session. */
    private suspend fun handshake(h: Harness, deviceId: String, keys: E2ECrypto.KeyPair, ticket: String): E2ESession {
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, h.identity.e2ePubRaw, psk = ticket.encodeToByteArray())
        h.sessions.onFrame(deviceId, Wire.payload(Wire.HANDSHAKE, init.ephPublic))
        // the daemon's first reply is the HANDSHAKE responder-eph (DaemonInfo, if any, comes after)
        val (_, resp) = h.outbound.receive()
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(resp))
        return init.finish(Wire.payloadBody(resp))
    }

    private inline fun <reified T> decode(session: E2ESession, framed: ByteArray): T {
        assertEquals(Wire.TRANSPORT, Wire.payloadType(framed))
        val plain = session.open(Wire.payloadBody(framed))!!
        return PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body as T
    }

    private suspend fun send(h: Harness, deviceId: String, session: E2ESession, body: Frame) {
        val env = Envelope("0", 0L, body = body)
        h.sessions.onFrame(deviceId, Wire.payload(Wire.TRANSPORT, session.seal(PocketJson.encodeToString(env).encodeToByteArray())))
    }

    @Test
    fun bridge_verdict_is_refused_and_daemonInfo_egress_is_dropped() = runBlocking {
        val allow = File(dir, "workspace").apply { mkdirs() }
        val h = Harness(dir)
        val spec = BridgeSpec("feishu-bot", listOf(allow.canonicalFile.path))
        val ticket = "bridge-ticket-1"
        // owner mints a headless credential; the device redeems it and the relay announces the pubkey
        h.bridges.recordIntent(ticket, spec, ttlMs = 120_000)
        h.sessions.onMintedTicket(ticket, headless = true)
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired("devBridge", b64.encodeToString(keys.publicRaw))
        // a provisional bridge key must NEVER be written into the full-power allow-list
        assertTrue("devBridge" !in PairedDevices.load(File(dir, "devices.json")).keys)

        val session = handshake(h, "devBridge", keys, ticket)

        // first transport confirms the bridge binding AND is a forbidden frame (a verdict): refused
        send(h, "devBridge", session, PermissionVerdict("c", "a", Decision.ALLOW))
        val err = decode<PocketError>(session, h.outbound.receive().second)
        assertEquals("bridge_forbidden", err.code)
        assertTrue(h.bridges.isBridge("devBridge")) // and it IS now a confirmed bridge

        // the handshake DaemonInfo (non-whitelisted egress) was DROPPED — nothing else was sent to the
        // bridge before the refusal. If DaemonInfo had leaked it would sit ahead of the PocketError.
        assertTrue(h.outbound.tryReceive().isFailure)

        // a session.open outside the allow-listed root is refused by the constraint gate
        send(h, "devBridge", session, OpenSession(File(dir, "elsewhere").apply { mkdirs() }.path))
        assertEquals("bridge_forbidden_workdir", decode<PocketError>(session, h.outbound.receive().second).code)

        // a prompt for a convo it never opened is refused (ownership)
        send(h, "devBridge", session, SendPrompt("not-mine", "hi"))
        assertEquals("bridge_not_own_session", decode<PocketError>(session, h.outbound.receive().second).code)
    }

    @Test
    fun a_provisional_bridge_whose_intent_lapsed_is_refused_not_treated_as_full_power() = runBlocking {
        // fail-closed: the intent is VALID at pairing (so the key is held provisional, kept OUT of
        // devices.json) but LAPSES before the first transport frame (a slow pairing near the ticket TTL
        // edge). finalize then returns null → the device is in NEITHER store → its frame must be DROPPED,
        // never routed as an ungated full-power device.
        val h = Harness(dir)
        val spec = BridgeSpec("late", listOf(dir.canonicalFile.path))
        val ticket = "late-ticket"
        h.bridges.recordIntent(ticket, spec, ttlMs = 60) // valid now, expires in 60ms
        h.sessions.onMintedTicket(ticket, headless = true)
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired("devLate", b64.encodeToString(keys.publicRaw)) // provisional (intent still valid)
        assertTrue("devLate" !in PairedDevices.load(File(dir, "devices.json")).keys) // never in the allow-list
        val session = handshake(h, "devLate", keys, ticket)
        kotlinx.coroutines.delay(120) // let the intent lapse before the first frame's finalize
        // a plain prompt: were it treated as a full device it would route to the registry (SessionGone/error);
        // fail-closed means NOTHING comes back and it is not a bridge
        send(h, "devLate", session, SendPrompt("c", "hi"))
        assertTrue(h.outbound.tryReceive().isFailure) // dropped silently
        assertTrue(!h.bridges.isBridge("devLate"))
    }

    @Test
    fun a_normal_device_still_receives_daemonInfo_over_the_same_path() = runBlocking {
        // the contrast case: a plain interactive device is NOT filtered — it gets its DaemonInfo, proving
        // the egress drop above is bridge-specific behavior, not a broken handshake
        val h = Harness(dir)
        val ticket = "phone-ticket-1"
        h.sessions.onMintedTicket(ticket, headless = false)
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired("devPhone", b64.encodeToString(keys.publicRaw))
        assertTrue("devPhone" in PairedDevices.load(File(dir, "devices.json")).keys) // normal → in devices.json
        val session = handshake(h, "devPhone", keys, ticket)
        // the DaemonInfo transport frame follows the handshake for a normal device
        val info = h.outbound.receive().second
        assertEquals(Wire.TRANSPORT, Wire.payloadType(info))
        val body = PocketJson.decodeFromString<Envelope>(session.open(Wire.payloadBody(info))!!.decodeToString()).body
        assertTrue(body is dev.ccpocket.protocol.DaemonInfo)
        assertTrue(!h.bridges.isBridge("devPhone"))
    }
}
