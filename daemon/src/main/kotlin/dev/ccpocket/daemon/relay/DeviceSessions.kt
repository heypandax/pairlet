package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
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
    private val store: File = devicesFile(),
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

    /** A freshly minted pairing ticket becomes a candidate PSK for the next device that pairs. */
    fun onMintedTicket(ticket: String) {
        synchronized(psks) { psks.addLast(ticket.encodeToByteArray()); while (psks.size > 8) psks.removeFirst() }
    }

    /** The relay forwarded a newly-redeemed device's static key; allow-list + bind its PSK. */
    suspend fun onDevicePaired(deviceId: String, devicePubB64: String) {
        val pub = runCatching { B64dec.decode(devicePubB64) }.getOrNull() ?: return
        mutex.withLock {
            devicePubs[deviceId] = pub
            // LIFO: the device scanned the most recently minted link
            pskFor[deviceId] = synchronized(psks) { psks.removeLastOrNull() } ?: ByteArray(0)
        }
        persist()
        log.info("device paired: ${deviceId.take(8)}… (e2e pub ${pub.size}B)")
    }

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
    }

    private suspend fun transport(deviceId: String, body: ByteArray) {
        val session = mutex.withLock { sessions[deviceId] }
        if (session == null) { log.warn("transport before handshake from ${deviceId.take(8)}…"); return }
        val plaintext = session.open(body)
        if (plaintext == null) { log.warn("decrypt failed from ${deviceId.take(8)}…"); return }
        mutex.withLock { pskFor.remove(deviceId) } // PSK confirmed; reconnects use authenticated statics
        val env = runCatching { PocketJson.decodeFromString<Envelope>(plaintext.decodeToString()) }.getOrNull() ?: return
        log.info("← ${env.body::class.simpleName} from ${deviceId.take(8)}…")

        val sink = OutboundSink { frame -> sealAndSend(deviceId, session, frame) }
        core.router.handle(env.body, sink) { convoId ->
            mutex.withLock { owned.getOrPut(deviceId) { mutableListOf() }.add(convoId) }
        }
    }

    private suspend fun sealAndSend(deviceId: String, session: E2ESession, frame: Frame) {
        val json = PocketJson.encodeToString(Envelope(nextId.getAndIncrement().toString(), 0L, body = frame))
        // serialize seals per session (the GCM counter must advance atomically)
        val payload = mutex.withLock { Wire.payload(Wire.TRANSPORT, session.seal(json.encodeToByteArray())) }
        send(deviceId, payload)
    }

    // ---- persistence of paired device public keys ----

    private fun persist() {
        runCatching {
            store.parentFile?.mkdirs()
            store.writeText(PocketJson.encodeToString(devicePubs.mapValues { B64.encodeToString(it.value) }))
        }
    }

    private fun loadPersisted(): Map<String, ByteArray> = runCatching {
        if (!store.exists()) return emptyMap()
        PocketJson.decodeFromString<Map<String, String>>(store.readText()).mapValues { B64dec.decode(it.value) }
    }.getOrDefault(emptyMap())

    private companion object {
        val B64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val B64dec: Base64.Decoder = Base64.getUrlDecoder()
        fun devicesFile(): File {
            val dir = System.getenv("CC_POCKET_IDENTITY")?.let { File(it).parentFile }
                ?: File(System.getProperty("user.home"), ".cc-pocket")
            return File(dir, "devices.json")
        }
    }
}
