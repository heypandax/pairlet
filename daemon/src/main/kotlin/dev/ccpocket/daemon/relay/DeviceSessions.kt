package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages one end-to-end-encrypted [E2ESession] per paired device and bridges decrypted frames into
 * the shared [DaemonCore] router. The daemon is the Noise responder; the device initiates. Paired
 * device public keys are persisted so reconnects survive a daemon restart; the pairing-ticket PSK is
 * kept in memory only for the brief first handshake.
 *
 * @param send delivers an inner E2E payload to a device (the caller wraps it for the relay).
 */
class DeviceSessions(
    private val core: DaemonCore,
    private val identity: Identity,
    private val store: File = PairedDevices.file(),
    private val lanUrl: () -> String? = { null }, // advertised in DaemonInfo after each handshake (null = direct listener off)
    private val hostname: () -> String? = { null }, // OS computer name advertised in DaemonInfo (client's default binding name)
    private val send: suspend (deviceId: String, payload: ByteArray) -> Unit,
) {
    private val log = logger("DeviceSessions")
    private val mutex = Mutex()
    private val devicePubs = HashMap<String, ByteArray>(loadPersisted())
    private val psks = ArrayDeque<ByteArray>()              // minted tickets, oldest first
    private val pskFor = HashMap<String, ByteArray>()       // deviceId -> first-handshake PSK
    private val sessions = HashMap<String, E2ESession>()
    private val owned = HashMap<String, MutableList<String>>()
    private val nextId = AtomicLong(0)
    private val seenThisAttach = HashSet<String>()          // devices the relay re-announced since the last attach

    /** A freshly minted pairing ticket becomes a candidate PSK for the next device that pairs. */
    fun onMintedTicket(ticket: String) {
        synchronized(psks) { psks.addLast(ticket.encodeToByteArray()); while (psks.size > 8) psks.removeFirst() }
    }

    /** The relay forwarded a newly-redeemed device's static key; allow-list + bind its PSK. */
    suspend fun onDevicePaired(deviceId: String, devicePubB64: String) {
        val pub = runCatching { B64dec.decode(devicePubB64) }.getOrNull() ?: return
        val known = mutex.withLock {
            seenThisAttach.add(deviceId)
            val already = devicePubs[deviceId]?.contentEquals(pub) == true
            devicePubs[deviceId] = pub
            if (!already) {
                // LIFO: the device scanned the most recently minted link. Attach-replays of an
                // already-known key must NOT re-arm a PSK (that would lock its next LAN connect out).
                pskFor[deviceId] = synchronized(psks) { psks.removeLastOrNull() } ?: ByteArray(0)
            }
            already
        }
        if (!known) {
            persist()
            log.info("device paired: ${deviceId.take(8)}… (e2e pub ${pub.size}B)")
        }
    }

    /** A relay (re)attach begins: reset the replay set — [reconcileReplay] prunes against what follows. */
    suspend fun beginAttachReplay() = mutex.withLock { seenThisAttach.clear() }

    /**
     * The relay re-announces every NON-REVOKED device right after attach — that replay is the
     * authoritative set. Prune anything we still hold that wasn't in it (revoked while we were offline),
     * so the direct-LAN gate stops honoring keys the user already revoked. An EMPTY replay means an
     * older/foreign relay that doesn't re-announce — do nothing rather than brick every binding.
     */
    suspend fun reconcileReplay() {
        val stale = mutex.withLock {
            if (seenThisAttach.isEmpty()) return
            (devicePubs.keys - seenThisAttach).toList().onEach {
                devicePubs.remove(it); sessions.remove(it); pskFor.remove(it)
            }
        }
        if (stale.isNotEmpty()) {
            persist()
            log.info("pruned ${stale.size} revoked device(s) after attach replay")
        }
    }

    /** The relay says this device was just revoked: cut key + live E2E session immediately. The persist
     *  bumps [PairedDevices.epoch], which also severs any LIVE direct-LAN socket on its next frame. */
    suspend fun onDeviceRevoked(deviceId: String) {
        mutex.withLock {
            devicePubs.remove(deviceId); sessions.remove(deviceId); pskFor.remove(deviceId)
            seenThisAttach.remove(deviceId)
        }
        persist()
        log.info("device revoked: ${deviceId.take(8)}… — pruned from allow-list")
    }

    /** True while this device's FIRST post-pairing handshake (the ticket-PSK-bound one) hasn't completed
     *  over the relay. The LAN gate refuses such devices, so first contact stays bound to the pairing
     *  ceremony — the one guarantee the LAN path's deliberate empty-PSK handshake cannot provide. */
    suspend fun firstContactPending(deviceId: String): Boolean = mutex.withLock { pskFor.containsKey(deviceId) }

    /** A device's inner E2E payload arrived (handshake or transport). */
    suspend fun onFrame(deviceId: String, payload: ByteArray) {
        if (payload.isEmpty()) return
        when (Wire.payloadType(payload)) {
            Wire.HANDSHAKE -> handshake(deviceId, Wire.payloadBody(payload))
            Wire.TRANSPORT -> transport(deviceId, Wire.payloadBody(payload))
        }
    }

    /** On disconnect: drop E2E sessions only. Owned conversations keep running in the background so a
     *  long task survives the phone going away; the idle reaper reclaims them once truly abandoned. */
    suspend fun onDisconnect() = mutex.withLock {
        sessions.clear()
        owned.clear()
    }

    private suspend fun handshake(deviceId: String, deviceEphPub: ByteArray) {
        val devicePub = mutex.withLock { devicePubs[deviceId] }
        if (devicePub == null) { log.warn("handshake from unknown device ${deviceId.take(8)}…"); return }
        val psk = mutex.withLock { pskFor[deviceId] ?: ByteArray(0) }
        val (session, responderEph) = E2ESession.responder(identity.e2ePrivRaw, identity.e2ePubRaw, devicePub, psk, deviceEphPub)
        mutex.withLock { sessions[deviceId] = session }
        log.info("handshake from ${deviceId.take(8)}… (psk ${psk.size}B) → session established")
        send(deviceId, Wire.payload(Wire.HANDSHAKE, responderEph))
        // teach the device where this daemon lives on the LAN so its next connect can skip the relay;
        // null actively clears a stale stored address (listener since disabled / no usable interface)
        sealAndSend(deviceId, DaemonInfo(lanUrl(), hostname()))
    }

    private suspend fun transport(deviceId: String, body: ByteArray) {
        val session = mutex.withLock { sessions[deviceId] }
        if (session == null) { log.warn("transport before handshake from ${deviceId.take(8)}…"); return }
        val plaintext = session.open(body)
        if (plaintext == null) { log.warn("decrypt failed from ${deviceId.take(8)}…"); return }
        mutex.withLock { pskFor.remove(deviceId) } // PSK confirmed; reconnects use authenticated statics
        val env = runCatching { PocketJson.decodeFromString<Envelope>(plaintext.decodeToString()) }.getOrNull() ?: return
        log.info("← ${env.body::class.simpleName} from ${deviceId.take(8)}…")

        // keyed: relay sinks are minted per frame — the deviceId key makes every frame from this device
        // read as the SAME attached client in a conversation's fan-out set (issue #47)
        val sink = dev.ccpocket.daemon.conversation.KeyedSink("dev:$deviceId", OutboundSink { frame -> sealAndSend(deviceId, frame) })
        try {
            core.router.handle(env.body, sink) { convoId ->
                mutex.withLock { owned.getOrPut(deviceId) { mutableListOf() }.add(convoId) }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.warn("handle ${env.body::class.simpleName} failed: ${e.message}")
            runCatching { sink.emit(PocketError("internal", e.message ?: "request failed")) }
        }
    }

    private suspend fun sealAndSend(deviceId: String, frame: Frame) {
        val json = PocketJson.encodeToString(Envelope(nextId.getAndIncrement().toString(), 0L, body = frame))
        // serialize seals per session (the GCM counter must advance atomically). Resolve the live session
        // at seal time rather than capturing one in the sink: conversation sinks outlive a phone reconnect,
        // and a re-handshake re-keys — a stale session would seal frames the device can't decrypt. No session
        // means the phone disconnected (sessions cleared on disconnect) and the frame is undeliverable — drop it.
        val payload = mutex.withLock {
            val live = sessions[deviceId] ?: return
            Wire.payload(Wire.TRANSPORT, live.seal(json.encodeToByteArray()))
        }
        send(deviceId, payload)
    }

    // ---- persistence of paired device public keys (shared with the direct-LAN gate) ----

    private fun persist() = PairedDevices.save(devicePubs, store)

    private fun loadPersisted(): Map<String, ByteArray> = PairedDevices.load(store)

    private companion object {
        val B64dec: Base64.Decoder = Base64.getUrlDecoder()
    }
}
