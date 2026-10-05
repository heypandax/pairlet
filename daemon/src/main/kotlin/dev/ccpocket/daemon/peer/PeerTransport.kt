package dev.ccpocket.daemon.peer

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.AuthError
import dev.ccpocket.protocol.DeviceHello
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PairCredential
import dev.ccpocket.protocol.PairRedeem
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.Route
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import io.ktor.websocket.readText
import kotlinx.io.readByteArray
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import io.ktor.websocket.Frame as WsFrame

/** A live, authenticated frame channel to a PEER daemon (we are the restricted device in their account). */
fun interface PeerChannel {
    suspend fun send(frame: ToDaemon)
}

/** What [PeerTransport.dial] reports back while a channel is alive. */
interface PeerSession {
    /** The channel is usable. Runs once per connection, before any inbound frame is delivered. */
    suspend fun onOpen(channel: PeerChannel)

    /** One decoded inbound frame. Delivered sequentially, in arrival order. */
    suspend fun onFrame(channel: PeerChannel, frame: Frame)
}

/** A freshly generated per-link E2E keypair, base64url raw. */
data class PeerKeys(val privateKeyB64: String, val publicKeyB64: String)

/**
 * Which PSK this side mixes into the next handshake: the one-time ticket while we still hold it, empty
 * once the first authenticated frame has burned it.
 *
 * ### Why this does NOT alternate to an empty PSK to escape a lost first response
 *
 * There is a real deadlock here — the sender burns its armed ticket-PSK the instant it decrypts our
 * first frame, so if its reply is lost we keep offering a ticket it will no longer accept — and the
 * obvious recipient-side fix is to alternate ticket → empty until one lands. That fix is UNSAFE, and the
 * reason is worth keeping next to the code that would otherwise grow it back:
 *
 * The sender arms its ticket-PSK in memory ([dev.ccpocket.daemon.relay.DeviceSessions] `pskFor`/`psks`).
 * When that anchor is missing at redeem time — the daemon restarted between the owner minting the invite
 * and us redeeming it, which `update-local-daemon.sh` makes routine — it falls back to allow-listing our
 * key as a FULL-POWER device and arming an EMPTY psk. Today that is harmless precisely BECAUSE we keep
 * offering the ticket: nothing ever decrypts, the link is visibly dead, and a human re-invites. An
 * empty-PSK attempt would decrypt instead — and with an empty `confirmedPsk` the sender never runs
 * `BridgeRegistry.finalize`, so no collaborator classification happens, and the connection routes
 * through the OWNER branch: sessions, shell, every other colleague's brief.
 *
 * A recipient cannot tell that case apart from the lost-response case (both look like "my ticket
 * attempt produced no frames"), so the fix does not belong on this side. It belongs on the sender:
 * either keep the ticket as a second handshake candidate until the peer demonstrably stops offering it
 * (persisted, so it survives a restart), or stop allow-listing an unanchored `DevicePaired` as a
 * full-power device. Both touch the pairing core and are deliberately not attempted here.
 */
object PeerHandshake {
    fun psk(secret: PeerLinkSecret): ByteArray = secret.ticket?.encodeToByteArray() ?: ByteArray(0)
}

/**
 * The RECIPIENT-side transport seam (REVIEW-REQUEST.md §9): everything about "how do I reach the peer's
 * daemon" behind one interface, so [PeerInboxClient]'s persistence/retry/ACK logic can be tested against
 * an in-process fake with no relay, no sockets and no crypto.
 */
interface PeerTransport {
    fun generateKeys(): PeerKeys

    /** Redeem a one-time connect ticket at [relay]; null when the relay refuses it (expired, reused). */
    suspend fun redeem(relay: String, ticket: String, devicePubB64: String): PairCredential?

    /** Establish a channel, run it until it closes, then return. Throws on any failure (the caller
     *  backs off and retries); [PeerCredentialRejected] when the relay refused the credential itself. */
    suspend fun dial(link: PeerLink, secret: PeerLinkSecret, session: PeerSession)
}

/**
 * The peer's relay answered our `DeviceHello` with an [AuthError] — the credential was refused BEFORE any
 * E2E exchange, so this is not the peer being offline. Told apart from a generic failure so the caller
 * can react to a [terminal] refusal: the peer's owner removing the contact revokes our credential at
 * THEIR relay, and nothing ever tells this side. Re-dialling it on the ordinary reconnect ladder is a
 * busy loop against a dead credential (observed in the field: one warning every ~25 s, ~3,500 a day,
 * for weeks).
 */
class PeerCredentialRejected(val code: String) : IllegalStateException("peer relay rejected our credential: $code") {
    /** The credential itself is dead — revoked, unknown, or its secret no longer matches. Any other code
     *  (`rate_limited`, `too_many_connections`, a code only a newer relay knows) is treated as transient. */
    val terminal: Boolean get() = code in TERMINAL_CODES

    companion object {
        /** The [dev.ccpocket.relay.auth.DeviceAuthenticator] refusals that no retry can change. */
        val TERMINAL_CODES: Set<String> = setOf("revoked", "unknown_device", "bad_credential")
    }
}

/**
 * The production transport: the exact device connect sequence the mobile app and `test-client` already
 * prove in the field — redeem once over HTTP, then `DeviceHello` → `Attached` → Noise INITIATOR pinned
 * to the invite's daemon key → sealed [Envelope]s on the binary data plane.
 *
 * Two deliberate differences from a phone's connection:
 *  - the PSK is the one-time ticket on the FIRST connect only; every later connect uses an EMPTY psk
 *    with the persisted static key, which is what makes reconnects survive a burned ticket (#161's
 *    lesson, applied from the start rather than retrofitted);
 *  - there is no control-plane traffic at all — no push registration, no presence. A review inbox is a
 *    listener, and anything it does not need to send is something the peer's daemon cannot be asked to
 *    handle on its behalf.
 */
class RelayPeerTransport : PeerTransport {
    private val log = logger("RelayPeerTransport")

    private fun newClient() = HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = 20_000            // matches the daemon's own relay leg
            maxFrameSize = 4L * 1024 * 1024        // matches the relay cap
        }
    }

    override fun generateKeys(): PeerKeys {
        val kp = E2ECrypto.generateKeyPair()
        return PeerKeys(privateKeyB64 = b64(kp.privateRaw), publicKeyB64 = b64(kp.publicRaw))
    }

    override suspend fun redeem(relay: String, ticket: String, devicePubB64: String): PairCredential? {
        val http = HttpClient(CIO)
        return try {
            val httpBase = relay.replace("wss://", "https://").replace("ws://", "http://")
            val body = withTimeout(REDEEM_TIMEOUT_MS) {
                val response = http.post("$httpBase/v1/pair/redeem") {
                    // headless is advisory only — the relay derives the credential class from the TICKET
                    // the owner minted (PairingService.redeem), so we simply match what the app sends.
                    contentType(ContentType.Application.Json)
                    setBody(PocketJson.encodeToString(PairRedeem.serializer(), PairRedeem(ticket, devicePubB64)))
                }
                val bytes = response.bodyAsChannel().readRemaining(MAX_REDEEM_BYTES + 1L).readByteArray()
                require(bytes.size <= MAX_REDEEM_BYTES) { "peer redeem reply too large" }
                bytes.toString(Charsets.UTF_8)
            }
            runCatching { PocketJson.decodeFromString(PairCredential.serializer(), body) }.getOrNull()
                // NEVER log the body: it carries the issued bearer credential on success, and echoes the
                // ticket on some failures
                ?: null.also { log.warn("peer redeem refused by $relay") }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            log.warn("peer redeem failed: ${t::class.simpleName}")
            null
        } finally {
            http.close()
        }
    }

    override suspend fun dial(link: PeerLink, secret: PeerLinkSecret, session: PeerSession) {
        val keys = E2ECrypto.KeyPair(b64d(secret.privateKeyB64), b64d(secret.publicKeyB64))
        val peerPub = b64d(link.peerDaemonPub) // PINNED at join — a different daemon key simply won't decrypt
        val nextId = AtomicLong(0)
        newClient().use { client ->
            client.webSocket(urlString = "${link.relay}/v1/device") {
                // Bound the whole prelude: a socket that connects but never answers would otherwise hang
                // this link forever with no guard armed yet (the app's RelayE2EConnection lesson).
                val e2e = withTimeout(HANDSHAKE_TIMEOUT_MS) {
                    outgoing.send(WsFrame.Text(controlText(DeviceHello(link.deviceId, secret.credential))))
                    awaitAttached()
                    val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, peerPub, PeerHandshake.psk(secret))
                    outgoing.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, init.ephPublic)))
                    awaitHandshake(init)
                }
                // One seal at a time: the E2E send counter is the frame's nonce, and two coroutines
                // sealing concurrently (the outbox flusher and its retry timer both write on a live
                // socket) could hand out the same counter — the peer's replay guard then drops the
                // second frame, silently, forever.
                val sealLock = Mutex()
                val channel = PeerChannel { frame ->
                    val json = PocketJson.encodeToString(Envelope(nextId.getAndIncrement().toString(), 0L, body = frame))
                    val sealed = sealLock.withLock { Wire.payload(Wire.TRANSPORT, e2e.seal(json.encodeToByteArray())) }
                    outgoing.send(WsFrame.Binary(true, sealed))
                }
                session.onOpen(channel)
                for (frame in incoming) {
                    if (frame !is WsFrame.Binary) continue
                    if (Wire.payloadType(frame.data) != Wire.TRANSPORT) continue
                    val plain = e2e.open(Wire.payloadBody(frame.data)) ?: continue
                    val body = runCatching { PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body }.getOrNull()
                        ?: continue
                    session.onFrame(channel, body)
                }
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.awaitAttached() {
        while (true) {
            val f = incoming.receive() as? WsFrame.Text ?: continue
            when (val b = runCatching { PocketJson.decodeFromString<Envelope>(f.readText()).body }.getOrNull()) {
                is Attached -> return
                is AuthError -> throw PeerCredentialRejected(b.code)
                else -> {}
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.awaitHandshake(init: E2ESession.Initiator): E2ESession {
        while (true) {
            val f = incoming.receive() as? WsFrame.Binary ?: continue
            if (Wire.payloadType(f.data) == Wire.HANDSHAKE) return init.finish(Wire.payloadBody(f.data))
        }
    }

    private fun controlText(frame: dev.ccpocket.protocol.ToRelay): String =
        PocketJson.encodeToString(Envelope("h", 0L, to = Route.RELAY, body = frame))

    private companion object {
        const val REDEEM_TIMEOUT_MS = 20_000L
        const val HANDSHAKE_TIMEOUT_MS = 20_000L
        const val MAX_REDEEM_BYTES = 64 * 1024
    }
}

/**
 * The invite's `daemonPub` is the peer daemon's STATIC E2E key, and joining PINS it forever: every
 * later reconnect derives its session from exactly these bytes.
 *
 * So it is validated as a real key, synchronously, before anything is redeemed or stored — not by
 * length. The suite is P-256 ([E2ECrypto]), whose raw public key is 65 bytes with an `0x04` prefix; a
 * 32-byte blob is not a short key, it is a different thing entirely, and an off-curve 65-byte blob is a
 * key no handshake can ever complete. Accepting either buys a stored link that reconnects and fails for
 * as long as the user leaves it there.
 */
internal fun validDaemonPub(daemonPubB64: String): Boolean {
    if (daemonPubB64.length > MAX_DAEMON_PUB_B64) return false
    val raw = runCatching { b64d(daemonPubB64) }.getOrNull() ?: return false
    return E2ECrypto.isValidPublicKey(raw)
}

/** 65 raw bytes is 88 base64url characters; the cap only keeps a pathological blob out of the decoder. */
private const val MAX_DAEMON_PUB_B64 = 128

private val B64E: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
private val B64D: Base64.Decoder = Base64.getUrlDecoder()

internal fun b64(b: ByteArray): String = B64E.encodeToString(b)
internal fun b64d(s: String): ByteArray = B64D.decode(s)
