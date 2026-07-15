package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.LanHello
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import io.ktor.websocket.Frame as WsFrame

/**
 * The direct listener's E2E gate: with this installed every connection must open with a [LanHello]
 * naming an already-paired device, then complete the same Noise handshake as the relay data plane
 * (mutual static-key auth — an unpaired or impersonating client can't finish it). [lanUrl] is what
 * we advertise back in [DaemonInfo] so the device keeps its stored direct address fresh.
 * [firstContactPending] refuses devices whose ticket-PSK-bound FIRST handshake hasn't happened over
 * the relay yet, keeping first contact bound to the pairing ceremony. [gateSlots] caps concurrent
 * un-authenticated handshakes so a LAN scanner can't exhaust FDs/coroutines with stalled sockets.
 */
class LanE2E(
    val identity: Identity,
    val lanUrl: () -> String?,
    val hostname: () -> String? = { null }, // OS computer name advertised in DaemonInfo (client's default binding name — #62)
    val gatewayBaseUrl: () -> String? = { null }, // third-party ANTHROPIC_BASE_URL in DaemonInfo (issue #139; null = official endpoint)
    val firstContactPending: suspend (String) -> Boolean = { false },
) {
    val gateSlots = kotlinx.coroutines.sync.Semaphore(MAX_PENDING_HANDSHAKES)

    private companion object {
        const val MAX_PENDING_HANDSHAKES = 8 // paired devices per account are ≤10; scanners queue behind this
    }
}

/**
 * One client socket. An outbound write actor serializes all daemon->client sends; the inbound read
 * pump decodes envelopes and dispatches them without blocking. On disconnect, every conversation
 * this connection opened is reaped (no orphaned claude trees).
 *
 * Two transport flavors on the same routing core:
 *  - plaintext TEXT JSON — the legacy `--local` mode, loopback dev use only;
 *  - E2E ([e2e] != null) — relay-mode daemons expose this alongside the relay so paired devices on
 *    the same machine/LAN can skip the relay entirely. Sealed BINARY frames, identical Wire format
 *    to the relay data plane. A plaintext frame on a gated socket is simply never dispatched.
 */
class WsConnection(
    private val session: WebSocketSession,
    private val router: RequestRouter,
    private val registry: SessionRegistry,
    private val e2e: LanE2E? = null,
) {
    private val outbox = Channel<Envelope>(Channel.BUFFERED)
    private val nextId = AtomicLong(0)
    private val owned: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var gatedDeviceId: String? = null // which paired device this gated socket authenticated as
    private var allowlistEpoch = PairedDevices.epoch

    private val log = logger("WsConnection")

    private val sink = OutboundSink { frame ->
        outbox.send(Envelope(nextId.getAndIncrement().toString(), System.currentTimeMillis(), body = frame))
    }

    suspend fun serve() = coroutineScope {
        registry.onLanConnect() // while any LAN socket lives, the idle reaper holds off (like relay peerOnline)
        try {
            val crypto: E2ESession? = if (e2e != null) {
                // hard cap on concurrent UN-authenticated handshakes: a LAN scanner opening sockets and
                // stalling would otherwise hold an FD + coroutine for the full timeout, times thousands
                if (!e2e.gateSlots.tryAcquire()) { log.warn("direct connect rejected (handshake slots exhausted)"); return@coroutineScope }
                val established = try {
                    withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { gateHandshake(e2e) }
                } finally {
                    e2e.gateSlots.release()
                }
                if (established == null) { log.info("direct connect rejected (bad/expired handshake)"); return@coroutineScope }
                established
            } else null
            pump(crypto)
        } finally {
            registry.onLanDisconnect()
        }
    }

    /**
     * The gate: TEXT [LanHello] (who is this?) -> allow-list lookup -> Noise handshake, daemon as
     * responder. Always an EMPTY psk: the ticket-PSK exists only to bind the very first (relay)
     * handshake to the pairing ceremony; on the LAN path the device is already allow-listed and
     * Noise KK's mutual static-key auth carries the trust. Null = reject (caller closes the socket).
     */
    private suspend fun gateHandshake(gate: LanE2E): E2ESession? {
        var deviceId: String? = null
        for (frame in session.incoming) {
            when (frame) {
                is WsFrame.Text -> {
                    val body = runCatching { PocketJson.decodeFromString<Envelope>(frame.readText()).body }.getOrNull()
                    deviceId = (body as? LanHello)?.deviceId ?: return null // first frame MUST be the hello
                }
                is WsFrame.Binary -> {
                    val id = deviceId ?: return null // handshake before hello — protocol violation
                    if (Wire.payloadType(frame.data) != Wire.HANDSHAKE) return null
                    // re-read per handshake: a device paired over the relay minutes ago must work here now
                    val devicePub = PairedDevices.load()[id]
                    if (devicePub == null) { log.warn("direct connect from unpaired device ${id.take(8)}…"); return null }
                    // a freshly paired device must prove ticket knowledge over the relay FIRST — the LAN
                    // handshake deliberately runs PSK-less and can't provide that pairing-ceremony binding
                    if (gate.firstContactPending(id)) { log.warn("direct connect from ${id.take(8)}… before its first relay handshake — refused"); return null }
                    val (crypto, responderEph) = E2ESession.responder(
                        gate.identity.e2ePrivRaw, gate.identity.e2ePubRaw, devicePub, ByteArray(0), Wire.payloadBody(frame.data),
                    )
                    session.outgoing.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, responderEph)))
                    gatedDeviceId = id
                    // freshly gated: hand the device our current direct address (IP may have changed since it
                    // stored it). outbox is buffered, so this queues until pump()'s writer starts draining.
                    sink.emit(DaemonInfo(gate.lanUrl(), gate.hostname(), gate.gatewayBaseUrl()))
                    log.info("direct E2E session established with ${id.take(8)}…")
                    return crypto
                }
                else -> {}
            }
        }
        return null // socket closed mid-handshake
    }

    private suspend fun pump(crypto: E2ESession?) = coroutineScope {
        val writer = launch {
            for (env in outbox) {
                val text = PocketJson.encodeToString(env)
                // the writer is the ONLY sealer — the GCM send counter advances strictly in order
                val ws: WsFrame = if (crypto != null) {
                    WsFrame.Binary(true, Wire.payload(Wire.TRANSPORT, crypto.seal(text.encodeToByteArray())))
                } else WsFrame.Text(text)
                // bounded write: on a zombie phone socket a send stalls forever (TCP buffer fills, no error),
                // wedging this writer and, once outbox fills, every pump feeding it. Stalled → tear down.
                if (withTimeoutOrNull(WRITE_TIMEOUT_MS) { session.outgoing.send(ws) } == null) {
                    error("socket write stalled — dead LAN link")
                }
            }
        }
        try {
            for (frame in session.incoming) {
                // revocation cuts LIVE sockets too: PairedDevices.save() bumps the epoch, so the next frame
                // re-verifies membership instead of grandfathering this connection until it disconnects
                val gated = gatedDeviceId
                if (gated != null && allowlistEpoch != PairedDevices.epoch) {
                    allowlistEpoch = PairedDevices.epoch
                    if (gated !in PairedDevices.load()) error("device revoked — closing live direct link")
                }
                val text = when {
                    crypto != null && frame is WsFrame.Binary && Wire.payloadType(frame.data) == Wire.TRANSPORT ->
                        crypto.open(Wire.payloadBody(frame.data))?.decodeToString()
                            ?: run { log.warn("decrypt failed on direct link"); null }
                    crypto == null && frame is WsFrame.Text -> frame.readText()
                    else -> null // plaintext on a gated socket / binary on a plaintext one — never dispatched
                }
                if (text == null) continue
                val env = runCatching { PocketJson.decodeFromString<Envelope>(text) }.getOrNull()
                if (env != null) {
                    // transport-layer frame: consumed by the E2E gate above; landing here means a plaintext
                    // (--local) socket received a client mid-probe — drop rather than route (the client falls
                    // back to the relay on its own). Keeps the router transport-agnostic.
                    if (env.body is LanHello) continue
                    log.info("recv ${env.body::class.simpleName}")
                    launch {
                        try {
                            router.handle(env.body, sink) { owned.add(it) }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            log.warn("handle ${env.body::class.simpleName} failed: ${e.message}")
                            runCatching { sink.emit(PocketError("internal", e.message ?: "request failed")) }
                        }
                    }
                } else {
                    // length only, never a prefix: a SavePreset that failed to decode (e.g. a future
                    // field reorder) must not spill its plaintext token into the daemon log
                    log.warn("undecodable frame (${text.length}B)")
                }
            }
        } finally {
            outbox.close()
            writer.cancel()
            withContext(NonCancellable) {
                // grace-close, not immediate: a flaky LAN socket / backgrounded phone can reconnect and reattach
                // the still-warm session instead of paying a kill + transcript rewrite + cold resume every blip.
                // Scoped to this connection's sink: if a newer connection reattached meanwhile, expiry is a no-op.
                owned.toList().forEach { runCatching { registry.scheduleClose(it, sink) } }
            }
        }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 10_000L // a healthy loopback/LAN write is instant; stalled this long = zombie
        const val HANDSHAKE_TIMEOUT_MS = 10_000L // hello + Noise on loopback/LAN is instant; a silent socket is a probe
    }
}
