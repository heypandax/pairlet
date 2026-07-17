package dev.ccpocket.protocol.e2e

/**
 * An end-to-end-encrypted channel between a device (initiator) and a daemon (responder), established
 * over the relay's opaque binary data plane. The relay only ever sees ciphertext.
 *
 * Handshake: an X3DH / Noise-KK-style 4-DH agreement. Both ends already know each other's STATIC
 * public key from pairing (the device scanned the daemon's; the daemon allow-listed the device's),
 * and each contributes a fresh EPHEMERAL key. Mixing static×static + static×ephemeral both ways gives
 * mutual authentication; the ephemerals give forward secrecy. The pairing ticket is folded in as a PSK
 * on the first handshake, so a relay that swapped a forwarded static key cannot complete it.
 *
 *   device(initiator)                         daemon(responder)
 *     -- e_i.pub ----------------------------->            (msg1)
 *     <------------------------------ e_r.pub --           (msg2)
 *   both derive: HKDF( es ‖ ss ‖ ee ‖ se ‖ psk,  salt = transcript ) -> k_i2r ‖ k_r2i
 *
 * Transport: AES-256-GCM, separate key per direction, a strictly-increasing 64-bit counter as the
 * nonce (replay/reorder is rejected). Each wire frame = [8-byte BE counter] ‖ ciphertext‖tag.
 */
class E2ESession internal constructor(
    private val sendKey: ByteArray,
    private val recvKey: ByteArray,
) {
    private var sendCtr = 0L
    private var recvCtr = -1L // last accepted counter; the next must be strictly greater

    fun seal(plaintext: ByteArray): ByteArray {
        val ctr = sendCtr++
        return ctrBytes(ctr) + E2ECrypto.seal(sendKey, nonce(ctr), plaintext, AAD)
    }

    /** Returns null on auth failure, malformed frame, or replay/reorder (counter not increasing). */
    fun open(frame: ByteArray): ByteArray? {
        if (frame.size < 8 + 16) return null
        val ctr = readCtr(frame)
        if (ctr <= recvCtr) return null
        val pt = E2ECrypto.open(recvKey, nonce(ctr), frame.copyOfRange(8, frame.size), AAD) ?: return null
        recvCtr = ctr
        return pt
    }

    private fun nonce(ctr: Long): ByteArray = ByteArray(12).also { ctrBytes(ctr).copyInto(it, 4) }
    private fun ctrBytes(ctr: Long) = ByteArray(8) { (ctr ushr (56 - it * 8)).toByte() }
    private fun readCtr(b: ByteArray): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xff)
        return v
    }

    /** The initiator's in-progress handshake: send [ephPublic] (msg1), then [finish] with msg2. */
    class Initiator internal constructor(
        private val staticPriv: ByteArray,
        private val staticPub: ByteArray,
        private val peerStaticPub: ByteArray,
        private val psk: ByteArray,
        private val eph: E2ECrypto.KeyPair,
    ) {
        val ephPublic: ByteArray get() = eph.publicRaw

        fun finish(responderEphPub: ByteArray): E2ESession {
            val es = E2ECrypto.agree(eph.privateRaw, peerStaticPub)    // DH(E_i, S_r)
            val ss = E2ECrypto.agree(staticPriv, peerStaticPub)        // DH(S_i, S_r)
            val ee = E2ECrypto.agree(eph.privateRaw, responderEphPub)  // DH(E_i, E_r)
            val se = E2ECrypto.agree(staticPriv, responderEphPub)      // DH(S_i, E_r)
            val keys = derive(es, ss, ee, se, psk, sInitiator = staticPub, sResponder = peerStaticPub, eInitiator = eph.publicRaw, eResponder = responderEphPub)
            return E2ESession(sendKey = keys.first, recvKey = keys.second) // initiator sends k_i2r, receives k_r2i
        }
    }

    companion object {
        /** Begin a handshake as the initiator (device). Send [Initiator.ephPublic] (msg1), then call finish. */
        fun initiator(staticPriv: ByteArray, staticPub: ByteArray, peerStaticPub: ByteArray, psk: ByteArray): Initiator =
            Initiator(staticPriv, staticPub, peerStaticPub, psk, E2ECrypto.generateKeyPair())

        /** Complete a handshake as the responder (daemon) given the initiator's msg1. Returns the
         *  session plus the responder ephemeral public to send back (msg2). */
        fun responder(
            staticPriv: ByteArray,
            staticPub: ByteArray,
            peerStaticPub: ByteArray,
            psk: ByteArray,
            initiatorEphPub: ByteArray,
        ): Pair<E2ESession, ByteArray> =
            responder(staticPriv, staticPub, peerStaticPub, listOf(psk), initiatorEphPub)
                .let { (sessions, eph) -> sessions.single() to eph }

        /** Complete a handshake as the responder deriving ONE session PER candidate PSK, all sharing a
         *  single responder ephemeral (msg2). The four DH values are PSK-independent — only the final
         *  HKDF mixes the PSK — so one msg2 serves every candidate, and which session the initiator's
         *  first transport frame decrypts under reveals which PSK it actually mixed. This lets a
         *  responder follow a peer that already consumed its pairing ticket (candidates [ticket, empty])
         *  without weakening the ticket-bound path for a peer that still holds it (issue #161). */
        fun responder(
            staticPriv: ByteArray,
            staticPub: ByteArray,
            peerStaticPub: ByteArray,
            psks: List<ByteArray>,
            initiatorEphPub: ByteArray,
        ): Pair<List<E2ESession>, ByteArray> {
            require(psks.isNotEmpty()) { "at least one PSK candidate required" }
            val eph = E2ECrypto.generateKeyPair()
            val es = E2ECrypto.agree(staticPriv, initiatorEphPub)      // DH(S_r, E_i)
            val ss = E2ECrypto.agree(staticPriv, peerStaticPub)        // DH(S_r, S_i)
            val ee = E2ECrypto.agree(eph.privateRaw, initiatorEphPub)  // DH(E_r, E_i)
            val se = E2ECrypto.agree(eph.privateRaw, peerStaticPub)    // DH(E_r, S_i)
            val sessions = psks.map { psk ->
                val keys = derive(es, ss, ee, se, psk, sInitiator = peerStaticPub, sResponder = staticPub, eInitiator = initiatorEphPub, eResponder = eph.publicRaw)
                E2ESession(sendKey = keys.second, recvKey = keys.first) // responder sends k_r2i, receives k_i2r
            }
            return sessions to eph.publicRaw
        }
    }
}

private val AAD = "ccpocket-e2e-v1".encodeToByteArray()
private val PROLOGUE = "ccpocket-e2e-v1".encodeToByteArray()
private val KEYINFO = "ccpocket-e2e-keys-v1".encodeToByteArray()

/** Returns (k_i2r, k_r2i). The transcript orders fields initiator-then-responder on both ends. */
private fun derive(
    es: ByteArray, ss: ByteArray, ee: ByteArray, se: ByteArray, psk: ByteArray,
    sInitiator: ByteArray, sResponder: ByteArray, eInitiator: ByteArray, eResponder: ByteArray,
): Pair<ByteArray, ByteArray> {
    val transcript = PROLOGUE + sInitiator + sResponder + eInitiator + eResponder
    val okm = E2ECrypto.hkdf(ikm = es + ss + ee + se + psk, salt = transcript, info = KEYINFO, length = 64)
    return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
}
