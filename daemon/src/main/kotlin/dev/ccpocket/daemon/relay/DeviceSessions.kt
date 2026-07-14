package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeCaps
import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeVerdict
import dev.ccpocket.daemon.bridge.CredentialKind
import dev.ccpocket.daemon.bridge.GuestCaps
import dev.ccpocket.daemon.bridge.GuestScope
import dev.ccpocket.daemon.bridge.PathScope
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.CreateShare
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListShares
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.RevokeShare
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.ShareEnded
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
 * Manages the end-to-end-encrypted [E2ESession]s per paired device (one active + one reconnect-overlap
 * fallback — see [DeviceLink], issue #146) and bridges decrypted frames into the shared [DaemonCore]
 * router. The daemon is the Noise responder; the device initiates. Paired device public keys are
 * persisted so reconnects survive a daemon restart; the pairing-ticket PSK is kept in memory only for
 * the brief first handshake. Sessions survive the daemon's OWN relay reconnects (issue #145) — they are
 * bound to the device handshake, not to the relay leg.
 *
 * @param send delivers an inner E2E payload to a device (the caller wraps it for the relay).
 */
class DeviceSessions(
    private val core: DaemonCore,
    private val identity: Identity,
    private val store: File = PairedDevices.file(),
    private val lanUrl: () -> String? = { null }, // advertised in DaemonInfo after each handshake (null = direct listener off)
    private val hostname: () -> String? = { null }, // OS computer name advertised in DaemonInfo (client's default binding name)
    private val gatewayBaseUrl: () -> String? = { null }, // third-party ANTHROPIC_BASE_URL in DaemonInfo (issue #139; null = official endpoint)
    /** The restricted-credential authority (issue #91 bridges + #115 guests): classification, constraints,
     *  capability gates. */
    val bridges: BridgeRegistry = BridgeRegistry(),
    private val send: suspend (deviceId: String, payload: ByteArray) -> Unit,
) {
    private val log = logger("DeviceSessions")

    /** The OWNER folder-share control plane (issue #115), installed by [RelayClient] after construction
     *  (it needs the relay's mint/revoke). Null on the local-server path — share creation needs the relay. */
    @Volatile
    var shareControl: dev.ccpocket.daemon.relay.ShareControl? = null
    private val mutex = Mutex()
    private val devicePubs = HashMap<String, ByteArray>(loadPersisted())
    private val psks = ArrayDeque<ByteArray>()              // minted tickets, oldest first
    private val pskFor = HashMap<String, ByteArray>()       // deviceId -> first-handshake PSK
    private val sessions = HashMap<String, DeviceLink>()    // deviceId -> its live E2E session(s); see DeviceLink (#146)
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
        if (bridges.isRestricted(deviceId)) { // confirmed bridge/guest: replay must not leak the key into devices.json
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
     *  bumps [PairedDevices.epoch], which also severs any LIVE direct-LAN socket on its next frame. For a
     *  GUEST (issue #115) this ALSO ends its running sessions now — the owner's "revoke" promise is "their
     *  sessions end", not merely "their link drops".
     *
     *  Returns true when a guest-facing [ShareEnded] notice was actually sealed toward the guest (the
     *  #115 follow-up: the precise "revoked"/"expired" ending for its terminal card). The notice rides
     *  BEFORE the prune below — the last frame the dying E2E session can still seal — and is pure
     *  best-effort: everything security-relevant (key death, session cut, convo force-close) is
     *  unchanged and unconditional right after. */
    suspend fun onDeviceRevoked(deviceId: String, reason: String = ShareEnded.REASON_REVOKED): Boolean {
        val wasGuest = bridges.isGuest(deviceId)
        var noticed = false
        if (wasGuest) {
            // sealAndSend silently no-ops without a live session — only report a notice that could seal
            noticed = mutex.withLock { sessions.containsKey(deviceId) }
            // ownerLabel = the computer name the guest already learned from its invite (leaks nothing new)
            runCatching { sealAndSend(deviceId, ShareEnded(reason, hostname())) }
        }
        val guestOrigin = if (wasGuest) bridges.specOf(deviceId)?.name else null // read BEFORE bridges.remove
        val guestConvos = mutex.withLock {
            devicePubs.remove(deviceId); sessions.remove(deviceId); pskFor.remove(deviceId)
            seenThisAttach.remove(deviceId)
            if (wasGuest) owned.remove(deviceId).orEmpty() else emptyList()
        }
        bridges.remove(deviceId) // a revoked credential loses its entry (and live guard) the same instant
        persist()
        // force-close the guest's convos NOW (kills their process trees) — the owner's revoke promise is
        // "their sessions end", not "their link drops". The per-connection `owned` list covers this
        // connection; closeByOrigin ALSO reaps convos the guest opened on an EARLIER connection (which
        // `owned` cleared on disconnect) so nothing keeps running past the revoke (issue #115 crypto review L1).
        if (wasGuest) {
            guestConvos.forEach { runCatching { core.registry.close(it, force = true) } }
            guestOrigin?.let { runCatching { core.registry.closeByOrigin(it) } }
        }
        log.info("device revoked: ${deviceId.take(8)}… — pruned from allow-list${if (wasGuest) " (guest sessions ended)" else ""}")
        return noticed
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

    /** The daemon's OWN relay leg dropped. Device E2E sessions are deliberately KEPT (issues #145/#146):
     *  they bind to the device HANDSHAKE, not to this relay socket — after our reconnect, a phone whose
     *  own socket stayed healthy keeps talking over the same Noise session with zero re-handshake (its
     *  PeerPresence(true) edge just re-syncs the page; clearing here was what turned every daemon-side
     *  relay blip into a phone-side full teardown + supersede storm). Sessions still die on revoke, on
     *  the attach-replay reconcile, and when a newer handshake displaces them. [owned] is per-connection
     *  bookkeeping for the guest-revoke path and still resets; owned conversations keep running in the
     *  background — the idle reaper reclaims them once truly abandoned. */
    suspend fun onDisconnect() = mutex.withLock {
        owned.clear()
    }

    private suspend fun handshake(deviceId: String, deviceEphPub: ByteArray) {
        // bridge keys (confirmed or provisional) live in the BridgeRegistry, never in devicePubs —
        // the lookup order is irrelevant (a deviceId is only ever in one of the two stores)
        val devicePub = mutex.withLock { devicePubs[deviceId] } ?: bridges.pubOf(deviceId)
        if (devicePub == null) { log.warn("handshake from unknown device ${deviceId.take(8)}…"); return }
        val psk = mutex.withLock { pskFor[deviceId] ?: ByteArray(0) }
        val (session, responderEph) = E2ESession.responder(identity.e2ePrivRaw, identity.e2ePubRaw, devicePub, psk, deviceEphPub)
        mutex.withLock {
            val link = sessions[deviceId]
            if (link == null) sessions[deviceId] = DeviceLink(session)
            else {
                // Keep the PREVIOUS session as the overlap fallback instead of overwriting it (#146): the
                // relay's supersede kick races the dying socket's last frames, so that socket's LATE
                // handshake can land AFTER the surviving socket's — a wholesale overwrite deafened the
                // live one ("transport before handshake" → the phone's 6s list timeout → relaunch →
                // another supersede: self-heal turned self-harm). The newest handshake seals outbound
                // (the common case: it IS the live socket); an inbound frame only the fallback can
                // decrypt promotes that session back (see [transport]).
                link.fallback = link.active
                link.active = session
            }
        }
        log.info("handshake from ${deviceId.take(8)}… (psk ${psk.size}B) → session established")
        send(deviceId, Wire.payload(Wire.HANDSHAKE, responderEph))
        // teach the device where this daemon lives on the LAN so its next connect can skip the relay;
        // null actively clears a stale stored address (listener since disabled / no usable interface).
        // A bridge (issue #91) never gets this: it can't use the direct-LAN path (its key isn't in
        // devices.json, so the LAN gate refuses it) and shouldn't learn the host's LAN address. The
        // sealAndSend egress filter would drop it anyway; skipping avoids a pointless sealed frame.
        if (!bridges.isBridgeCandidate(deviceId)) sealAndSend(deviceId, DaemonInfo(lanUrl(), hostname(), gatewayBaseUrl()))
    }

    private suspend fun transport(deviceId: String, body: ByteArray) {
        val link = mutex.withLock { sessions[deviceId] }
        if (link == null) { log.warn("transport before handshake from ${deviceId.take(8)}…"); return }
        // Trial-decrypt newest-first (open() only advances its receive counter on SUCCESS, so probing the
        // wrong session is side-effect free; frames arrive sequentially from the relay loop, so opens
        // never race each other). A FALLBACK hit means the older connection instance is the one actually
        // alive — its rival's late handshake stole `active` (#146) — so promote it back under the mutex,
        // and outbound seals follow the proven-alive instance again.
        var plaintext = link.active.open(body)
        if (plaintext == null) {
            val fb = link.fallback
            plaintext = fb?.open(body)
            if (plaintext != null && fb != null) mutex.withLock { link.fallback = link.active; link.active = fb }
        }
        if (plaintext == null) { log.warn("decrypt failed from ${deviceId.take(8)}…"); return }
        val confirmedPsk = mutex.withLock { pskFor.remove(deviceId) } // PSK confirmed; reconnects use authenticated statics
        // FIRST successful decrypt after pairing: the PSK (the exact pairing ticket) is now PROVEN to be
        // held by this device. If it matches a pending intent, finalize the restricted classification here
        // (bridge #91 OR guest #115) — the one moment the binding is cryptographically exact.
        if (confirmedPsk != null && confirmedPsk.isNotEmpty()) {
            bridges.finalize(deviceId, confirmedPsk)?.let { log.info("${it.kind.name.lowercase()} \"${it.name}\" confirmed on ${deviceId.take(8)}…") }
        }
        // FAIL CLOSED: a device that is neither a confirmed RESTRICTED credential (bridge/guest) nor in the
        // full-power allow-list is a provisional credential whose intent lapsed before this first frame
        // (slow pairing near the ticket TTL edge, or a daemon restart that wiped the in-memory
        // provisional/PSK maps). It must NOT route as an ungated full-power device — drop it; the owner
        // re-issues the invite. (isRestricted covers guests too — else a just-confirmed guest is dropped.)
        val recognized = bridges.isRestricted(deviceId) || mutex.withLock { devicePubs.containsKey(deviceId) }
        if (!recognized) {
            log.warn("frame from unbound device ${deviceId.take(8)}… (provisional credential never confirmed) — refused")
            mutex.withLock { sessions.remove(deviceId) }
            bridges.dropProvisional(deviceId)
            return
        }
        val env = runCatching { PocketJson.decodeFromString<Envelope>(plaintext.decodeToString()) }.getOrNull() ?: return
        log.info("← ${env.body::class.simpleName} from ${deviceId.take(8)}…")

        // keyed: relay sinks are minted per frame — the deviceId key makes every frame from this device
        // read as the SAME attached client in a conversation's fan-out set (issue #47)
        val sink = dev.ccpocket.daemon.conversation.KeyedSink("dev:$deviceId", OutboundSink { frame -> sealAndSend(deviceId, frame) })

        // ---- restricted INGRESS gates: both checks live HERE, on the only path where deviceId is
        // authenticated (proven by the Noise static key that just decrypted the frame). Bridge (#91) and
        // guest (#115) each get their own capability whitelist + guard; a full-power owner device is
        // additionally allowed to drive the folder-share control plane. ----
        var toRoute: Frame = env.body
        var origin: String? = null
        var guestScope: GuestScope? = null
        when {
            bridges.isBridge(deviceId) -> {
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
            bridges.isGuest(deviceId) -> {
                val guard = bridges.startGuestGuard(deviceId)
                if (guard == null || !GuestCaps.ingressAllowed(env.body)) {
                    log.warn("guest ${deviceId.take(8)}… sent forbidden ${env.body::class.simpleName} — refused")
                    runCatching { sink.emit(PocketError("share_forbidden", "not permitted for a folder-share guest: ${env.body::class.simpleName}", convoIdOf(env.body))) }
                    return
                }
                val liveOwned = if (env.body is OpenSession) core.registry.liveCountOf(guard.ownedConvoIds()) else 0
                when (val v = guard.vet(env.body, System.currentTimeMillis(), liveOwned)) {
                    is BridgeVerdict.Deny -> {
                        log.warn("guest ${deviceId.take(8)}… ${env.body::class.simpleName} denied: ${v.code.guestWire}")
                        runCatching { sink.emit(PocketError(v.code.guestWire, v.code.guestMessage, convoIdOf(env.body))) }
                        return
                    }
                    is BridgeVerdict.Allow -> {
                        toRoute = v.frame // canonicalized workdir, tier-clamped mode, stripped takeOver/force
                        origin = guard.spec.name
                        val spec = bridges.specOf(deviceId)
                        guestScope = GuestScope(
                            roots = spec?.workdirs?.mapNotNull { PathScope.canonical(it) } ?: emptyList(),
                            ownedSessions = bridges.guestSessionIds(deviceId),
                            label = spec?.name ?: "guest",
                            expiresAt = spec?.expiresAt,
                            tier = spec?.tier ?: AccessTier.REVIEW,
                        )
                    }
                }
            }
            else -> {
                // FULL-POWER owner device: the folder-share control plane (mint / list / revoke) needs the
                // relay handle the router lacks, so it's intercepted here. A guest/bridge never reaches this
                // branch (its own whitelist denies these frames), so re-sharing is structurally impossible.
                if (handleOwnerShare(env.body, sink)) return
            }
        }
        try {
            core.router.handle(toRoute, sink, origin, guestScope) { convoId ->
                mutex.withLock { owned.getOrPut(deviceId) { mutableListOf() }.add(convoId) }
                bridges.guardOf(deviceId)?.noteOpened(convoId)     // bridge (#91)
                bridges.guestGuardOf(deviceId)?.noteOpened(convoId) // guest (#115)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.warn("handle ${env.body::class.simpleName} failed: ${e.message}")
            runCatching { sink.emit(PocketError("internal", e.message ?: "request failed")) }
        }
    }

    /** OWNER-only folder-share control plane (issue #115). Returns true when [frame] was a share frame and
     *  was handled (the caller returns); false lets an ordinary owner frame fall through to the router.
     *  Null [shareControl] (local-server path) → false, so CreateShare there surfaces the router's
     *  "unsupported" rather than silently vanishing. */
    private suspend fun handleOwnerShare(frame: Frame, sink: OutboundSink): Boolean {
        val sc = shareControl ?: return false
        when (frame) {
            is CreateShare -> sink.emit(sc.create(frame))
            is ListShares -> sink.emit(sc.list())
            is RevokeShare -> sink.emit(sc.revoke(frame.deviceId))
            else -> return false
        }
        return true
    }

    /** The convoId an inbound frame targets, for error attribution (bridge denials). */
    private fun convoIdOf(frame: Frame): String? = when (frame) {
        is SendPrompt -> frame.convoId
        is CloseSession -> frame.convoId
        is dev.ccpocket.protocol.CancelTurn -> frame.convoId
        else -> null
    }

    private suspend fun sealAndSend(deviceId: String, frame: Frame) {
        // ---- restricted EGRESS gate (issue #91 bridges + #115 guests): this is the ONLY place frames are
        // sealed toward a relay device, so filtering here covers every source — conversation fan-out,
        // handshake DaemonInfo, resurfaced asks, router errors. A bridge can never receive a PermissionAsk;
        // a guest CAN (it answers its own), but neither ever receives the management/identity frames.
        // Keyed on isBridgeCandidate so the provisional window (pre-first-transport handshake) is covered.
        if (bridges.isBridgeCandidate(deviceId)) {
            // learn the sessionIds minted for this credential's convos (SessionLive backfills them) so a
            // later open(resumeId=…)/read is recognized as OWN; the guest guard also persists to the ledger
            if (frame is SessionLive) frame.sessionId?.let { sid ->
                bridges.guardOf(deviceId)?.noteSession(frame.convoId, sid)
                bridges.guestGuardOf(deviceId)?.noteSession(frame.convoId, sid)
            }
            // egress whitelist by kind. A provisional (kind not yet confirmed) candidate only ever has the
            // handshake DaemonInfo in flight, which BOTH whitelists drop — so fall back to the stricter
            // BRIDGE whitelist until the first transport frame confirms the kind (fail closed).
            val allowed = when (bridges.kindOf(deviceId)) {
                CredentialKind.GUEST -> GuestCaps.egressAllowed(frame)
                else -> BridgeCaps.egressAllowed(frame)
            }
            if (!allowed) return
        }
        val json = PocketJson.encodeToString(Envelope(nextId.getAndIncrement().toString(), 0L, body = frame))
        // serialize seals per session (the GCM counter must advance atomically). Resolve the live session
        // at seal time rather than capturing one in the sink: conversation sinks outlive a phone reconnect,
        // and a re-handshake re-keys — a stale session would seal frames the device can't decrypt. No link
        // means this device never handshook (or was revoked/pruned) — the frame is undeliverable, drop it.
        val payload = mutex.withLock {
            val live = sessions[deviceId]?.active ?: return
            Wire.payload(Wire.TRANSPORT, live.seal(json.encodeToByteArray()))
        }
        send(deviceId, payload)
    }

    /**
     * The live E2E sessions of ONE device — at most the two ends of a reconnect overlap (issue #146).
     * [active] seals every outbound frame and is the session that last PROVED itself: it completed the
     * most recent handshake, or successfully decrypted the most recent inbound frame that [active]
     * couldn't. [fallback] is the previous handshake's session, retained because the relay's per-device
     * supersede kick races the dying socket's late frames — its late handshake must not clobber the
     * surviving socket's session (the "僵会话" deafness loop). Each handshake displaces the fallback, so
     * a device never holds more than two sessions.
     */
    private class DeviceLink(var active: E2ESession, var fallback: E2ESession? = null)

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
