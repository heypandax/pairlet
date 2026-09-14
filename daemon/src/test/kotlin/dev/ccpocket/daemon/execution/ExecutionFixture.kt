package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.review.PeerChannel
import dev.ccpocket.daemon.review.PeerHandshake
import dev.ccpocket.daemon.review.PeerKeys
import dev.ccpocket.daemon.review.PeerLink
import dev.ccpocket.daemon.review.PeerLinkSecret
import dev.ccpocket.daemon.review.PeerSession
import dev.ccpocket.daemon.review.PeerTransport
import dev.ccpocket.daemon.review.b64
import dev.ccpocket.daemon.review.b64d
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PairCredential
import dev.ccpocket.protocol.PairTicket
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * The TARGET daemon as a source dials it (#367 G1): the PRODUCTION credential chain — relay announce,
 * Noise handshake and sealed transport frames all enter through
 * [dev.ccpocket.daemon.relay.DeviceSessions], never through anything execution-specific. G0's private
 * responder is gone, and this seam is what proves there is no second handshake path left.
 */
internal interface TargetPump {
    suspend fun devicePaired(deviceId: String, devicePubB64: String)
    /** msg1 in, msg2 out; null when this device gets no handshake at all. */
    suspend fun handshake(deviceId: String, initiatorEph: ByteArray): ByteArray?
    /** One sealed transport frame in, the sealed replies out (possibly none). */
    suspend fun transport(deviceId: String, sealed: ByteArray): List<ByteArray>
}

/**
 * An in-process relay: one-time tickets, bearer credentials, device revoke, and the `DevicePaired`
 * announce toward the target. It can also ACT as a compromised relay: it knows every raw ticket it minted
 * ([lastMinted]) and can issue credentials for keys of its choosing ([issue]).
 */
internal class FakeRelay(private val accountId: String, private val clock: () -> Long) {
    var target: TargetPump? = null
    private val tickets = HashMap<String, Long>()
    private val used = HashSet<String>()
    val credentials = HashMap<String, String>() // credential -> deviceId
    val revoked = HashSet<String>()
    var mints = 0
    var redeems = 0
    var lastMinted: String? = null
    /** What this relay reports as the ticket lifetime (a compromised relay can stretch it). */
    var mintTtlSec = 600
    /** Simulate a lagging/compromised relay that still honours an expired ticket. */
    var ignoreExpiry = false
    private var n = 0

    suspend fun mint(): PairTicket {
        mints++
        val tk = "tk_${++n}_" + b64(E2ECrypto.generateKeyPair().publicRaw.copyOf(12))
        tickets[tk] = clock() + mintTtlSec * 1000L
        lastMinted = tk
        return PairTicket(tk, mintTtlSec, "000000")
    }

    suspend fun redeem(ticket: String, devicePubB64: String): PairCredential? {
        redeems++
        val exp = tickets[ticket] ?: return null
        if (ticket in used || (!ignoreExpiry && exp <= clock())) return null
        used += ticket
        return issue(devicePubB64)
    }

    /** A credential the relay issues WITHOUT any ticket (compromised relay / forged announce). */
    suspend fun issue(devicePubB64: String): PairCredential {
        val dev = "dev_${++n}"
        val cred = "cred_$n"
        credentials[cred] = dev
        target?.devicePaired(dev, devicePubB64)
        return PairCredential(dev, cred, accountId)
    }
}

/**
 * Source-side [PeerTransport] over [FakeRelay] with REAL Noise: the initiator mixes exactly the PSK
 * [PeerHandshake.psk] picks, pins the invite's daemon key, and seals real envelopes. Faults are injected
 * at the points the G0 matrix names. [lastFailure] records why the last dial ended without replies.
 */
internal class FixtureTransport(
    private val relay: FakeRelay,
    private val target: () -> TargetPump?,
) : PeerTransport {
    val psksOffered = mutableListOf<String>()
    var dropNextReplies = 0
    var cutAfterHandshake = 0
    var lastFailure: String? = null

    override fun generateKeys(): PeerKeys {
        val kp = E2ECrypto.generateKeyPair()
        return PeerKeys(b64(kp.privateRaw), b64(kp.publicRaw))
    }

    override suspend fun redeem(relay: String, ticket: String, devicePubB64: String): PairCredential? =
        this.relay.redeem(ticket, devicePubB64)

    private fun fail(code: String): Nothing { lastFailure = code; error(code) }

    override suspend fun dial(link: PeerLink, secret: PeerLinkSecret, session: PeerSession) {
        lastFailure = null
        val dev = relay.credentials[secret.credential] ?: fail("relay_rejected")
        if (dev != link.deviceId || dev in relay.revoked) fail("relay_rejected")
        val t = target() ?: fail("target_offline")
        val psk = PeerHandshake.psk(secret)
        psksOffered += psk.decodeToString()
        val init = E2ESession.initiator(b64d(secret.privateKeyB64), b64d(secret.publicKeyB64), b64d(link.peerDaemonPub), psk)
        val msg2 = t.handshake(dev, init.ephPublic) ?: fail("no_handshake")
        val e2e = init.finish(msg2)
        if (cutAfterHandshake > 0) { cutAfterHandshake--; fail("connection_cut") }
        val replies = ArrayList<ByteArray>()
        var seq = 0L
        val channel = PeerChannel { frame ->
            val json = PocketJson.encodeToString(Envelope((seq++).toString(), 0L, body = frame))
            replies += t.transport(dev, e2e.seal(json.encodeToByteArray()))
        }
        session.onOpen(channel)
        if (dropNextReplies > 0) { dropNextReplies--; fail("reply_lost") }
        if (replies.isEmpty()) lastFailure = "no_replies"
        for (r in replies) {
            val plain = e2e.open(r) ?: continue
            session.onFrame(channel, PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body)
        }
    }
}

internal fun sealedLeaves(k: KClass<*>): List<KClass<*>> =
    if (k.sealedSubclasses.isEmpty()) listOf(k) else k.sealedSubclasses.flatMap { sealedLeaves(it) }

/** Build a frame from its required ctor params (same approach as CollaboratorCapsTest). */
internal fun instantiateFrame(cls: KClass<*>): Any {
    cls.objectInstance?.let { return it }
    val ctor = cls.primaryConstructor ?: cls.constructors.first()
    val provided = ctor.parameters.filterNot { it.isOptional }.associateWith { p ->
        val t = p.type.classifier as? KClass<*>
        when {
            t == String::class -> "x"
            t == Int::class -> 0
            t == Long::class -> 0L
            t == Boolean::class -> false
            t == Double::class -> 0.0
            t == List::class -> emptyList<Any>()
            t == Map::class -> emptyMap<Any, Any>()
            t == Set::class -> emptySet<Any>()
            t?.java?.isEnum == true -> t.java.enumConstants.first()
            // a sealed parameter type (e.g. a chunk's piece): any concrete leaf is enough for a type-only predicate
            t?.isSealed == true -> instantiateFrame(sealedLeaves(t).first())
            t?.isData == true -> instantiateFrame(t)
            t != null && !t.isAbstract && t.java.isInterface.not() -> runCatching { instantiateFrame(t) }.getOrNull()
            else -> null
        }
    }
    return ctor.callBy(provided)
}

internal fun Frame.name(): String = this::class.simpleName ?: "?"
