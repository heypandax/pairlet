package dev.ccpocket.protocol.e2e

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class E2ESessionTest {

    private fun handshake(psk: ByteArray = ByteArray(32) { 7 }): Pair<E2ESession, E2ESession> {
        val device = E2ECrypto.generateKeyPair()   // initiator static
        val daemon = E2ECrypto.generateKeyPair()   // responder static
        val init = E2ESession.initiator(device.privateRaw, device.publicRaw, daemon.publicRaw, psk)
        val (daemonSession, respEph) = E2ESession.responder(daemon.privateRaw, daemon.publicRaw, device.publicRaw, psk, init.ephPublic)
        val deviceSession = init.finish(respEph)
        return deviceSession to daemonSession
    }

    @Test fun bidirectional_transport_round_trip() {
        val (device, daemon) = handshake()
        val a = "list directories".encodeToByteArray()
        assertContentEquals(a, daemon.open(device.seal(a)))
        val b = "[dirs] /tmp /home".encodeToByteArray()
        assertContentEquals(b, device.open(daemon.seal(b)))
    }

    @Test fun relay_sees_only_ciphertext() {
        val (device, _) = handshake()
        val plain = "secret prompt".encodeToByteArray()
        val frame = device.seal(plain)
        // the on-wire frame must not contain the plaintext anywhere
        assertTrue(frame.size > plain.size)
        assertTrue(!frame.toList().windowed(plain.size).any { it.toByteArray().contentEquals(plain) })
    }

    @Test fun replay_is_rejected() {
        val (device, daemon) = handshake()
        val frame = device.seal("once".encodeToByteArray())
        assertContentEquals("once".encodeToByteArray(), daemon.open(frame))
        assertNull(daemon.open(frame)) // same frame again -> counter not increasing -> rejected
    }

    @Test fun mismatched_psk_breaks_the_channel() {
        // device used the real ticket; a relay/attacker drove the responder with a different PSK
        val device = E2ECrypto.generateKeyPair()
        val daemon = E2ECrypto.generateKeyPair()
        val init = E2ESession.initiator(device.privateRaw, device.publicRaw, daemon.publicRaw, ByteArray(32) { 1 })
        val (daemonSession, respEph) = E2ESession.responder(daemon.privateRaw, daemon.publicRaw, device.publicRaw, ByteArray(32) { 2 }, init.ephPublic)
        val deviceSession = init.finish(respEph)
        // keys diverge -> the daemon cannot open the device's frames
        assertNull(daemonSession.open(deviceSession.seal("hello".encodeToByteArray())))
    }

    @Test fun multi_psk_responder_shares_one_ephemeral_and_each_candidate_pairs_only_with_its_initiator() {
        // #161: the responder derives a session per candidate PSK off ONE msg2; an initiator that mixed
        // the ticket interops with the ticket session, one that mixed nothing with the empty twin — and
        // never the other way around.
        val device = E2ECrypto.generateKeyPair()
        val daemon = E2ECrypto.generateKeyPair()
        val ticket = ByteArray(43) { 9 }
        for (deviceUsedTicket in listOf(true, false)) {
            val init = E2ESession.initiator(device.privateRaw, device.publicRaw, daemon.publicRaw, if (deviceUsedTicket) ticket else ByteArray(0))
            val (candidates, respEph) = E2ESession.responder(daemon.privateRaw, daemon.publicRaw, device.publicRaw, listOf(ticket, ByteArray(0)), init.ephPublic)
            val deviceSession = init.finish(respEph)
            val (match, other) = if (deviceUsedTicket) candidates[0] to candidates[1] else candidates[1] to candidates[0]
            assertNull(other.open(deviceSession.seal("probe".encodeToByteArray()))) // open() is side-effect-free on failure
            assertContentEquals("probe2".encodeToByteArray(), match.open(deviceSession.seal("probe2".encodeToByteArray())))
            assertContentEquals("reply".encodeToByteArray(), deviceSession.open(match.seal("reply".encodeToByteArray())))
        }
    }

    @Test fun wrong_peer_static_breaks_the_channel() {
        val device = E2ECrypto.generateKeyPair()
        val daemon = E2ECrypto.generateKeyPair()
        val impostor = E2ECrypto.generateKeyPair()
        val psk = ByteArray(32) { 3 }
        // device thinks it's talking to `daemon`, but the responder is actually `impostor`
        val init = E2ESession.initiator(device.privateRaw, device.publicRaw, daemon.publicRaw, psk)
        val (impostorSession, respEph) = E2ESession.responder(impostor.privateRaw, impostor.publicRaw, device.publicRaw, psk, init.ephPublic)
        val deviceSession = init.finish(respEph)
        assertNull(impostorSession.open(deviceSession.seal("x".encodeToByteArray())))
    }
}
