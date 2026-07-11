package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeCaps
import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeVerdict
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
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
    /** The headless-bridge authority (issue #91): classification, constraints, capability gates. */
    val bridges: BridgeRegistry = BridgeRegistry(),
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

    @Volatile
    private var lastInteractiveMintAt = 0L // serializes interactive vs headless pairing (issue #91)

    /** A freshly minted pairing ticket becomes a candidate PSK for the next device that pairs.
     *  Only INTERACTIVE mints stamp the exclusion clock — see [interactivePairingPending]. */
    fun onMintedTicket(ticket: String, headless: Boolean = false) {
        if (!headless) lastInteractiveMintAt = System.currentTimeMillis()
        synchronized(psks) { psks.addLast(ticket.encodeToByteArray()); while (psks.size > 8) psks.removeFirst() }
    }

    /** True while an interactive pairing ticket could still be redeemed — a headless mint must wait.
     *  Mint serialization (issue #91): with both ticket classes outstanding, the LIFO PSK-arming in
     *  [onDevicePaired] could cross-bind them. Classification itself stays exact regardless (it hashes
     *  the CONFIRMED handshake PSK — [BridgeRegistry.finalize]), but a cross-armed PSK fails BOTH
     *  devices' first handshakes, a pointless outage; refusing the overlap removes the window. */
    fun interactivePairingPending(now: Long = System.currentTimeMillis()): Boolean =
        now - lastInteractiveMintAt < TICKET_EXCLUSION_MS

    /** The relay forwarded a newly-redeemed device's static key; allow-list + bind its PSK.
     *
     *  Bridge classification (issue #91): if the LIFO-armed PSK matches a pending HEADLESS intent, the
     *  key is held as a PROVISIONAL bridge key — deliberately kept OUT of devices.json, so at no point
     *  (not even a crash window) does a would-be bridge key sit in the full-power allow-list the LAN
     *  gate and older daemons trust. The classification is only FINALIZED when the first transport
     *  frame decrypts under that exact ticket-PSK ([transport] → [BridgeRegistry.finalize]) — proof the
     *  device really holds the headless ticket, immune to relay announce-order games. */
    suspend fun onDevicePaired(deviceId: String, devicePubB64: String) {
        val pub = runCatching { B64dec.decode(devicePubB64) }.getOrNull() ?: return
        if (bridges.isBridge(deviceId)) { // confirmed bridge: replay must not leak the key into devices.json
            mutex.withLock { seenThisAttach.add(deviceId) }
            return
        }
        var provisionalBridge = false
        val known = mutex.withLock {
            seenThisAttach.add(deviceId)
            val already = devicePubs[deviceId]?.contentEquals(pub) == true ||
                bridges.pubOf(deviceId)?.contentEquals(pub) == true // provisional re-announce: no PSK re-arm
            if (!already) {
                // LIFO: the device scanned the most recently minted link. Attach-replays of an
                // already-known key must NOT re-arm a PSK (that would lock its next LAN connect out).
                val psk = synchronized(psks) { psks.removeLastOrNull() } ?: ByteArray(0)
                pskFor[deviceId] = psk
                if (psk.isNotEmpty() && bridges.looksHeadless(psk)) {
                    provisionalBridge = true
                    bridges.holdProvisional(deviceId, pub)
                } else {
                    devicePubs[deviceId] = pub
                }
            }
            already
        }
        if (!known) {
            if (!provisionalBridge) persist() // a provisional bridge never touches devices.json
            log.info("device paired: ${deviceId.take(8)}… (e2e pub ${pub.size}B${if (provisionalBridge) ", provisional bridge" else ""})")
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
        val (stale, staleBridges) = mutex.withLock {
            if (seenThisAttach.isEmpty()) return
            val s = (devicePubs.keys - seenThisAttach).toList().onEach {
                devicePubs.remove(it); sessions.remove(it); pskFor.remove(it)
            }
            // bridges revoked while we were offline are pruned the same way (their rows vanish from the
            // replay). A NEW relay replays headless rows to us (we announce PROTO_V_HEADLESS); an OLD
            // relay has no headless column and replays them as ordinary devices — either way a live
            // bridge is in the set and survives.
            val sb = bridges.ids().filter { it !in seenThisAttach }.onEach { sessions.remove(it); pskFor.remove(it) }
            s to sb
        }
        staleBridges.forEach { bridges.remove(it) }
        if (stale.isNotEmpty()) {
            persist()
            log.info("pruned ${stale.size} revoked device(s) after attach replay")
        }
        if (staleBridges.isNotEmpty()) log.info("pruned ${staleBridges.size} revoked bridge(s) after attach replay")
    }

    /** The relay says this device was just revoked: cut key + live E2E session immediately. The persist
     *  bumps [PairedDevices.epoch], which also severs any LIVE direct-LAN socket on its next frame. */
    suspend fun onDeviceRevoked(deviceId: String) {
        mutex.withLock {
            devicePubs.remove(deviceId); sessions.remove(deviceId); pskFor.remove(deviceId)
            seenThisAttach.remove(deviceId)
        }
        bridges.remove(deviceId) // a revoked bridge loses its entry (and live guard) the same instant
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
        // bridge keys (confirmed or provisional) live in the BridgeRegistry, never in devicePubs —
        // the lookup order is irrelevant (a deviceId is only ever in one of the two stores)
        val devicePub = mutex.withLock { devicePubs[deviceId] } ?: bridges.pubOf(deviceId)
        if (devicePub == null) { log.warn("handshake from unknown device ${deviceId.take(8)}…"); return }
        val psk = mutex.withLock { pskFor[deviceId] ?: ByteArray(0) }
        val (session, responderEph) = E2ESession.responder(identity.e2ePrivRaw, identity.e2ePubRaw, devicePub, psk, deviceEphPub)
        mutex.withLock { sessions[deviceId] = session }
        log.info("handshake from ${deviceId.take(8)}… (psk ${psk.size}B) → session established")
        send(deviceId, Wire.payload(Wire.HANDSHAKE, responderEph))
        // teach the device where this daemon lives on the LAN so its next connect can skip the relay;
        // null actively clears a stale stored address (listener since disabled / no usable interface).
        // A bridge (issue #91) never gets this: it can't use the direct-LAN path (its key isn't in
        // devices.json, so the LAN gate refuses it) and shouldn't learn the host's LAN address. The
        // sealAndSend egress filter would drop it anyway; skipping avoids a pointless sealed frame.
        if (!bridges.isBridgeCandidate(deviceId)) sealAndSend(deviceId, DaemonInfo(lanUrl(), hostname()))
    }

    private suspend fun transport(deviceId: String, body: ByteArray) {
        val session = mutex.withLock { sessions[deviceId] }
        if (session == null) { log.warn("transport before handshake from ${deviceId.take(8)}…"); return }
        val plaintext = session.open(body)
        if (plaintext == null) { log.warn("decrypt failed from ${deviceId.take(8)}…"); return }
        val confirmedPsk = mutex.withLock { pskFor.remove(deviceId) } // PSK confirmed; reconnects use authenticated statics
        // FIRST successful decrypt after pairing: the PSK (the exact pairing ticket) is now PROVEN to be
        // held by this device. If it matches a headless intent, finalize the bridge classification here —
        // the one moment the binding is cryptographically exact (issue #91).
        if (confirmedPsk != null && confirmedPsk.isNotEmpty()) {
            bridges.finalize(deviceId, confirmedPsk)?.let { log.info("bridge \"${it.name}\" confirmed on ${deviceId.take(8)}…") }
        }
        // FAIL CLOSED: a device that is neither a confirmed bridge nor in the full-power allow-list is a
        // provisional bridge whose intent lapsed before this first frame (slow pairing near the ticket
        // TTL edge, or a daemon restart that wiped the in-memory provisional/PSK maps). It must NOT route
        // as an ungated full-power device — drop it; the owner re-runs `pair --headless` (issue #91).
        val recognized = bridges.isBridge(deviceId) || mutex.withLock { devicePubs.containsKey(deviceId) }
        if (!recognized) {
            log.warn("frame from unbound device ${deviceId.take(8)}… (provisional bridge never confirmed) — refused")
            mutex.withLock { sessions.remove(deviceId) }
            bridges.dropProvisional(deviceId)
            return
        }
        val env = runCatching { PocketJson.decodeFromString<Envelope>(plaintext.decodeToString()) }.getOrNull() ?: return
        log.info("← ${env.body::class.simpleName} from ${deviceId.take(8)}…")

        // keyed: relay sinks are minted per frame — the deviceId key makes every frame from this device
        // read as the SAME attached client in a conversation's fan-out set (issue #47)
        val sink = dev.ccpocket.daemon.conversation.KeyedSink("dev:$deviceId", OutboundSink { frame -> sealAndSend(deviceId, frame) })

        // ---- headless bridge INGRESS gate (issue #91): both checks live HERE, on the only path where
        // deviceId is authenticated (proven by the Noise static key that just decrypted the frame) ----
        var toRoute: Frame = env.body
        var origin: String? = null
        if (bridges.isBridge(deviceId)) {
            val guard = bridges.startGuard(deviceId)
            if (guard == null || !BridgeCaps.ingressAllowed(env.body)) {
                log.warn("bridge ${deviceId.take(8)}… sent forbidden ${env.body::class.simpleName} — refused")
                runCatching { sink.emit(PocketError("bridge_forbidden", "not permitted for a bridge credential: ${env.body::class.simpleName}", convoIdOf(env.body))) }
                return
            }
            // concurrency counts LIVE conversations only — idle-reaped ones must not eat the budget
            val liveOwned = if (env.body is OpenSession) core.registry.liveCountOf(guard.ownedConvoIds()) else 0
            when (val v = guard.vet(env.body, System.currentTimeMillis(), liveOwned)) {
                is BridgeVerdict.Deny -> {
                    log.warn("bridge ${deviceId.take(8)}… ${env.body::class.simpleName} denied: ${v.code.wire}")
                    runCatching { sink.emit(PocketError(v.code.wire, v.code.message, convoIdOf(env.body))) }
                    return
                }
                is BridgeVerdict.Allow -> {
                    toRoute = v.frame // canonicalized workdir, clamped mode, stripped takeOver/force
                    origin = guard.spec.name
                }
            }
        }
        try {
            core.router.handle(toRoute, sink, origin) { convoId ->
                mutex.withLock { owned.getOrPut(deviceId) { mutableListOf() }.add(convoId) }
                bridges.guardOf(deviceId)?.noteOpened(convoId)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.warn("handle ${env.body::class.simpleName} failed: ${e.message}")
            runCatching { sink.emit(PocketError("internal", e.message ?: "request failed")) }
        }
    }

    /** The convoId an inbound frame targets, for error attribution (bridge denials). */
    private fun convoIdOf(frame: Frame): String? = when (frame) {
        is SendPrompt -> frame.convoId
        is CloseSession -> frame.convoId
        is dev.ccpocket.protocol.CancelTurn -> frame.convoId
        else -> null
    }

    private suspend fun sealAndSend(deviceId: String, frame: Frame) {
        // ---- headless bridge EGRESS gate (issue #91): this is the ONLY place frames are sealed toward a
        // relay device, so filtering here covers every source — conversation fan-out, handshake DaemonInfo,
        // resurfaced asks, router errors. A PermissionAsk is structurally undeliverable to a bridge.
        // Keyed on isBridgeCandidate so the provisional window (pre-first-transport handshake) is covered.
        if (bridges.isBridgeCandidate(deviceId)) {
            // learn the sessionIds minted for this bridge's convos (SessionLive backfills them) so a
            // later open(resumeId=…) can be recognized as the bridge's OWN session
            if (frame is SessionLive) frame.sessionId?.let { bridges.guardOf(deviceId)?.noteSession(frame.convoId, it) }
            if (!BridgeCaps.egressAllowed(frame)) return
        }
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

        // ticket TTL (120s at the relay) + slack: how long after an interactive mint a headless mint
        // is refused (and PairLoopback refuses the reverse via BridgeRegistry.intentPending)
        const val TICKET_EXCLUSION_MS = 130_000L
    }
}
