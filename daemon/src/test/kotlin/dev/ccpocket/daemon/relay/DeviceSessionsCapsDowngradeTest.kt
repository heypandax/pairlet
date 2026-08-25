package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.protocol.AGENT_WIRE_DSH
import dev.ccpocket.protocol.AGENT_WIRE_KIMI
import dev.ccpocket.protocol.AGENT_WIRE_OPENCODE
import dev.ccpocket.protocol.AGENT_WIRE_ZCODE
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionGone
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ClientCaps is a property of a CONNECTION, not of a device.
 *
 * The relay leg used to keep one holder per deviceId, created on first sight and never cleared, so the
 * declared vocabulary outlived the build that declared it. Downgrade the App on the same phone — same
 * deviceId, same pairing — and the daemon still believed it could decode ZCODE/KIMI/DSH: every list row
 * and usage bar carrying a post-baseline `AgentKind` made the old build hard-fail the WHOLE Envelope
 * (pinned in `SerializationRoundTripTest`), so those screens went blank with no error anywhere, and
 * nothing healed it short of a daemon restart.
 *
 * The fix must be fail-closed but must NOT collide with the #146 supersede overlap: a dying socket's
 * LATE handshake is not a new client, and blanking the live socket's vocabulary because of it would
 * re-break the very case [DeviceSessionsOverlapTest] exists to protect. Both halves are pinned here.
 */
class DeviceSessionsCapsDowngradeTest {

    private val dir = createTempDirectory("ccp-ds-caps").toFile()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private class Harness(dir: File) {
        val identity = Identity.loadOrCreate(File(dir, "identity.json"))
        val outbound = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)
        val sessions = DeviceSessions(
            core = DaemonCore(emptyMap()),
            identity = identity,
            store = File(dir, "devices.json"),
            bridges = BridgeRegistry(File(dir, "bridges.json")),
        ) { deviceId, payload -> outbound.trySend(deviceId to payload) }
    }

    /** One device-side handshake; consumes the HANDSHAKE reply and the DaemonInfo that follows. */
    private suspend fun handshake(h: Harness, deviceId: String, keys: E2ECrypto.KeyPair, psk: String?): E2ESession {
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, h.identity.e2ePubRaw, psk = (psk ?: "").encodeToByteArray())
        h.sessions.onFrame(deviceId, Wire.payload(Wire.HANDSHAKE, init.ephPublic))
        val (_, resp) = h.outbound.receive()
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(resp))
        val session = init.finish(Wire.payloadBody(resp))
        assertTrue(decode<Frame>(session, h.outbound.receive().second) is DaemonInfo)
        return session
    }

    private inline fun <reified T> decode(session: E2ESession, framed: ByteArray): T {
        assertEquals(Wire.TRANSPORT, Wire.payloadType(framed))
        val plain = session.open(Wire.payloadBody(framed)) ?: throw AssertionError("frame did not decrypt")
        return PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body as T
    }

    private suspend fun send(h: Harness, deviceId: String, session: E2ESession, body: Frame) {
        val env = Envelope("0", 0L, body = body)
        h.sessions.onFrame(deviceId, Wire.payload(Wire.TRANSPORT, session.seal(PocketJson.encodeToString(env).encodeToByteArray())))
    }

    /** A prompt into an unknown convo answers SessionGone — proves the frame decrypted AND routed. */
    private suspend fun roundTrip(h: Harness, deviceId: String, session: E2ESession, convo: String) {
        send(h, deviceId, session, SendPrompt(convo, "hi"))
        assertEquals(convo, decode<SessionGone>(session, h.outbound.receive().second).convoId)
    }

    private suspend fun pairedDevice(h: Harness, deviceId: String, ticket: String): E2ECrypto.KeyPair {
        h.sessions.onMintedTicket(ticket)
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired(deviceId, b64.encodeToString(keys.publicRaw))
        return keys
    }

    /** What a current App declares on every connect (PocketRepository sends this FIRST in the volley). */
    private fun modernCaps() = ClientCaps(
        supportsAgents = listOf(AGENT_WIRE_OPENCODE, AGENT_WIRE_KIMI, AGENT_WIRE_ZCODE, AGENT_WIRE_DSH),
        supportsApprovalV2 = true,
    )

    private suspend fun caps(h: Harness, deviceId: String) =
        assertNotNull(h.sessions.declaredCapsForTest(deviceId), "device has no live link")

    @Test
    fun a_reconnect_starts_fail_closed_so_a_downgraded_app_is_never_fed_a_vocabulary_it_lost() = runBlocking {
        val h = Harness(dir)
        val keys = pairedDevice(h, "devDowngrade", "ticket-downgrade")

        val modern = handshake(h, "devDowngrade", keys, "ticket-downgrade")
        send(h, "devDowngrade", modern, modernCaps())
        roundTrip(h, "devDowngrade", modern, "ghost-1") // ordering barrier: ClientCaps has been routed
        caps(h, "devDowngrade").let {
            assertTrue(it.supportsZcode && it.supportsKimi && it.supportsDsh && it.supportsOpencode, "modern build declared the full vocabulary")
            assertTrue(it.supportsApprovalV2)
        }

        // the user rolls the App back to a build that predates ClientCaps: same deviceId, same keys, it
        // just never sends the frame. Nothing but the handshake distinguishes it from the modern build.
        handshake(h, "devDowngrade", keys, null)

        caps(h, "devDowngrade").let {
            assertFalse(it.supportsZcode, "a connection that declared nothing must not inherit ZCODE")
            assertFalse(it.supportsKimi)
            assertFalse(it.supportsDsh)
            assertFalse(it.supportsOpencode)
            assertFalse(it.supportsApprovalV2)
        }
    }

    @Test
    fun a_dying_sockets_late_handshake_must_not_strip_the_live_sockets_declared_vocabulary() = runBlocking {
        val h = Harness(dir)
        val keys = pairedDevice(h, "devOverlap", "ticket-overlap")

        val live = handshake(h, "devOverlap", keys, "ticket-overlap")
        send(h, "devOverlap", live, modernCaps())
        roundTrip(h, "devOverlap", live, "ghost-1")
        assertTrue(caps(h, "devOverlap").supportsZcode)

        // issue #146: the superseded socket's handshake lands LAST. It is not a new client — blanking the
        // caps and leaving them blank would hide every ZCODE/KIMI/DSH row from the socket that is actually
        // alive, for the rest of its connection, with no frame able to restore them (the App sends
        // ClientCaps only in its connect volley).
        handshake(h, "devOverlap", keys, null)

        // the live socket keeps talking on its own session: the promote hands it back its own vocabulary
        roundTrip(h, "devOverlap", live, "ghost-2")
        caps(h, "devOverlap").let {
            assertTrue(it.supportsZcode, "the promoted (surviving) connection keeps what IT declared")
            assertTrue(it.supportsKimi && it.supportsDsh && it.supportsOpencode)
            assertTrue(it.supportsApprovalV2)
        }
    }
}
