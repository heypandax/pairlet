package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeCaps
import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeVerdict
import dev.ccpocket.daemon.bridge.CredentialKind
import dev.ccpocket.daemon.bridge.GuestCaps
import dev.ccpocket.daemon.bridge.GuestScope
import dev.ccpocket.daemon.bridge.PathScope
import dev.ccpocket.daemon.server.RequestRouter
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.ConfigureBridgeRunner
import dev.ccpocket.protocol.ControlBridgeRunner
import dev.ccpocket.protocol.CreateBridge
import dev.ccpocket.protocol.CreateShare
import dev.ccpocket.protocol.DetachBridgeRunner
import dev.ccpocket.protocol.ListBridges
import dev.ccpocket.protocol.RevokeBridge
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.DAEMON_SUPPORTED_AGENT_WIRES
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the end-to-end-encrypted [E2ESession]s per paired device (one active + one reconnect-overlap
 * fallback, plus a pre-first-contact empty-PSK twin — see [DeviceLink], issues #146/#161) and bridges
 * decrypted frames into the shared [DaemonCore] router. The daemon is the Noise responder; the device initiates. Paired device public keys are
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

    /** The OWNER control planes (share #115 / bridge #91 follow-up) live on [DaemonCore] — the LAN
     *  transport serves them too, so they can't be relay-local state. These are convenience views. */
    var shareControl: dev.ccpocket.daemon.relay.ShareControl?
        get() = core.shareControl
        set(v) { core.shareControl = v }
    var bridgeControl: dev.ccpocket.daemon.relay.BridgeControl?
        get() = core.bridgeControl
        set(v) { core.bridgeControl = v }
    var collaboratorControl: dev.ccpocket.daemon.handoff.CollaboratorControl?
        get() = core.collaboratorControl
        set(v) { core.collaboratorControl = v }
    private val mutex = Mutex()
    private val devicePubs = HashMap<String, ByteArray>(loadPersisted())
    private val psks = ArrayDeque<ByteArray>()              // minted tickets, oldest first
    private val pskFor = HashMap<String, ByteArray>()       // deviceId -> first-handshake PSK
    private val sessions = HashMap<String, DeviceLink>()    // deviceId -> its live E2E session(s); see DeviceLink (#146)
    private val owned = HashMap<String, MutableList<String>>()
    private val nextId = AtomicLong(0)
    private val seenThisAttach = HashSet<String>()          // devices the relay re-announced since the last attach
    // deviceId -> declared wire vocabulary (ClientCaps): survives reconnects of the same device, bounded
    // by the paired-device count. Concurrent map — route() reads it outside the mutex.
    private val deviceCaps = java.util.concurrent.ConcurrentHashMap<String, RequestRouter.ClientCapsHolder>()

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
     *  device really holds the headless ticket, immune to relay announce-order games.
     *
     *  UNANCHORED announce: entering the full-power allow-list requires that a ticket THIS daemon minted
     *  was still armed here — the arming fact, not the byte value the handshake ends up using. With
     *  nothing armed (we restarted after minting a restricted invite but before it was redeemed, or the
     *  announce is unsolicited) the intent that says "this is a REVIEW collaborator / a bridge / a guest"
     *  is gone with it: promoting the key would silently hand a colleague a full owner slot, past
     *  [dev.ccpocket.protocol.CollaboratorPurpose] and every restricted capability gate. Such a key is
     *  held provisional instead — never persisted, never allow-listed — so its first frame hits the same
     *  fail-closed `recognized` check in [transport] and the owner mints a fresh invite. */
    suspend fun onDevicePaired(deviceId: String, devicePubB64: String) {
        val pub = runCatching { B64dec.decode(devicePubB64) }.getOrNull() ?: return
        if (bridges.isRestricted(deviceId)) { // confirmed bridge/guest: replay must not leak the key into devices.json
            mutex.withLock { seenThisAttach.add(deviceId) }
            return
        }
        var provisionalBridge = false
        var unanchored = false
        val known = mutex.withLock {
            seenThisAttach.add(deviceId)
            val already = devicePubs[deviceId]?.contentEquals(pub) == true ||
                bridges.pubOf(deviceId)?.contentEquals(pub) == true // provisional re-announce: no PSK re-arm
            if (!already) {
                // LIFO: the device scanned the most recently minted link. Attach-replays of an
                // already-known key must NOT re-arm a PSK (that would lock its next LAN connect out).
                // `armed` is the ARMING FACT and is what decides authority below; the empty fallback is
                // only the byte value the responder handshake needs.
                val armed = synchronized(psks) { psks.removeLastOrNull() }?.takeIf { it.isNotEmpty() }
                pskFor[deviceId] = armed ?: ByteArray(0)
                provisionalBridge = armed != null && bridges.looksHeadless(armed)
                // issue #207: an armed ticket that is NOT itself a pending restricted intent, while such
                // an intent IS outstanding, means overlapping mints mis-armed the LIFO stack — the mint
                // serialization refuses every interactive mint for as long as an intent pends, so this
                // announce cannot be an ordinary interactive pairing. Anchoring it would write what is
                // really a restricted credential's key into the full-power allow-list; park it instead.
                unanchored = armed == null || (!provisionalBridge && bridges.intentPending())
                if (unanchored || provisionalBridge) bridges.holdProvisional(deviceId, pub)
                else devicePubs[deviceId] = pub
            }
            already
        }
        if (!known) {
            if (!provisionalBridge && !unanchored) persist() // nothing provisional ever touches devices.json
            val how = when {
                unanchored -> ", unanchored — no anchoring ticket armed here (or a restricted intent pends, #207), its first frame is refused"
                provisionalBridge -> ", provisional bridge"
                else -> ""
            }
            log.info("device paired: ${deviceId.take(8)}… (e2e pub ${pub.size}B$how)")
        }
    }

    /** A relay (re)attach begins: reset the replay set. [reconcileReplay] is called only after the relay's
     * explicit DeviceReplayComplete barrier; an old relay leaves the local allow-list intact until upgraded. */
    suspend fun beginAttachReplay() = mutex.withLock { seenThisAttach.clear() }

    /**
     * The relay re-announces every NON-REVOKED device right after attach — that replay is the
     * authoritative set. Prune anything we still hold that wasn't in it (revoked while we were offline),
     * so the direct-LAN gate stops honoring keys the user already revoked. An EMPTY replay is safe to
     * reconcile only when the v4 relay has explicitly sent the completion barrier; without that marker,
     * it may simply be an older/foreign relay that doesn't re-announce, so retain every local binding.
     */
    suspend fun reconcileReplay(authoritativeEmpty: Boolean = false) {
        val (stale, staleBridges) = mutex.withLock {
            if (seenThisAttach.isEmpty() && !authoritativeEmpty) return
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
     *  bumps [PairedDevices.epoch], which also severs any LIVE direct-LAN socket on its next frame. For any
     *  RESTRICTED credential (a GUEST #115 or a BRIDGE #91) this ALSO ends its running sessions now — the
     *  owner's "revoke" promise is "their sessions end", not merely "their link drops".
     *
     *  Returns true when a guest-facing [ShareEnded] notice was actually sealed toward the guest (the
     *  #115 follow-up: the precise "revoked"/"expired" ending for its terminal card). The notice rides
     *  BEFORE the prune below — the last frame the dying E2E session can still seal — and is pure
     *  best-effort: everything security-relevant (key death, session cut, convo force-close) is
     *  unchanged and unconditional right after. */
    suspend fun onDeviceRevoked(deviceId: String, reason: String = ShareEnded.REASON_REVOKED): Boolean {
        val wasGuest = bridges.isGuest(deviceId)
        // guest OR bridge — a revoke must end the sessions of EITHER, not just a guest's (issue #91: a
        // bridge's live Claude turn otherwise keeps editing files until the idle reaper claims it)
        val wasRestricted = bridges.isRestricted(deviceId)
        var noticed = false
        if (wasGuest) {
            // sealAndSend silently no-ops without a live session — only report a notice that could seal
            noticed = mutex.withLock { sessions.containsKey(deviceId) }
            // ownerLabel = the computer name the guest already learned from its invite (leaks nothing new)
            runCatching { sealAndSend(deviceId, ShareEnded(reason, hostname())) }
        }
        val revokedOrigin = if (wasRestricted) bridges.specOf(deviceId)?.name else null // read BEFORE bridges.remove
        val revokedConvos = mutex.withLock {
            devicePubs.remove(deviceId); sessions.remove(deviceId); pskFor.remove(deviceId)
            seenThisAttach.remove(deviceId)
            if (wasRestricted) owned.remove(deviceId).orEmpty() else emptyList()
        }
        bridges.remove(deviceId) // a revoked credential loses its entry (and live guard) the same instant
        persist()
        // force-close the revoked credential's convos NOW (kills their process trees) — the owner's revoke
        // promise is "their sessions end", not "their link drops". Covers guests (#115) AND bridges (#91):
        // a bridge's running Claude turn must not outlive the revoke. The per-connection `owned` list covers
        // this connection; closeByOrigin ALSO reaps convos opened on an EARLIER connection (which `owned`
        // cleared on disconnect) so nothing keeps running past the revoke (issue #115 crypto review L1).
        if (wasRestricted) {
            revokedConvos.forEach { runCatching { core.registry.close(it, force = true) } }
            revokedOrigin?.let { runCatching { core.registry.closeByOrigin(it) } }
        }
        log.info("device revoked: ${deviceId.take(8)}… — pruned from allow-list${if (wasRestricted) " (${if (wasGuest) "guest " else ""}sessions ended)" else ""}")
        return noticed
    }

    /** True while this device's FIRST post-pairing contact hasn't completed over the relay. The LAN gate
     *  refuses such devices, so first contact stays bound to the pairing ceremony — the one guarantee the
     *  LAN path's deliberate empty-PSK handshake cannot provide. Completion normally proves the ticket
     *  PSK; a device that provably burned its ticket on an interrupted first attempt completes via the
     *  empty-PSK twin instead (#161) — still over the relay, still static-key-authenticated. */
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
        // First-contact PSK deadlock (#161): the device consumes its pairing ticket on its first connect
        // ATTEMPT, we only release ours on its first successful DECRYPT — any interruption in between
        // (supersede kick, fleet cross-kick, network blink) leaves the two ends keyed apart on every
        // retry: "psk 43B" handshakes plus decrypt failures forever, until a daemon restart. For a
        // device already in the FULL-POWER allow-list, additionally derive an EMPTY-PSK twin off the
        // same responder ephemeral; whichever session its first inbound frame decrypts under wins
        // ([transport]). Static-key auth gates both, so the twin only ever trades away the ticket-PSK
        // proof — which the relay's redeem step already verified, and which a daemon restart (in-memory
        // pskFor) never carried anyway. Provisional bridge/guest candidates get NO twin: the exact
        // ticket-PSK decrypt IS their classification proof ([BridgeRegistry.finalize]); they keep
        // failing closed.
        val twinned = psk.isNotEmpty() && mutex.withLock { devicePubs.containsKey(deviceId) }
        val candidates = if (twinned) listOf(psk, ByteArray(0)) else listOf(psk)
        // The relay authenticates the routing deviceId, but the device still controls its inner
        // ephemeral bytes. A short, wrong-format, or off-curve P-256 point makes the crypto provider
        // throw. Never let that untrusted input escape [onFrame]: RelayClient deliberately processes
        // device frames in its single receive loop, so one bad re-handshake would otherwise tear down
        // the account-wide relay link and could repeat forever. Derive before mutating [sessions], then
        // drop only this handshake on any ordinary crypto/format failure; transport/network failures
        // after a valid derivation still propagate and trigger the intended reconnect path.
        val response = try {
            E2ESession.responder(identity.e2ePrivRaw, identity.e2ePubRaw, devicePub, candidates, deviceEphPub)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("malformed handshake from ${deviceId.take(8)}… (${e::class.simpleName}) — dropped")
            return
        }
        val (derived, responderEph) = response
        val session = derived.first()
        mutex.withLock {
            val link = sessions[deviceId]
            if (link == null) sessions[deviceId] = DeviceLink(session, pskShadow = derived.getOrNull(1))
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
                link.pskShadow = derived.getOrNull(1) // the twin always tracks the NEWEST handshake
            }
        }
        log.info("handshake from ${deviceId.take(8)}… (psk ${psk.size}B${if (twinned) " + empty-PSK twin" else ""}) → session established")
        send(deviceId, Wire.payload(Wire.HANDSHAKE, responderEph))
        // teach the device where this daemon lives on the LAN so its next connect can skip the relay;
        // null actively clears a stale stored address (listener since disabled / no usable interface).
        // A bridge (issue #91) never gets this: it can't use the direct-LAN path (its key isn't in
        // devices.json, so the LAN gate refuses it) and shouldn't learn the host's LAN address. The
        // sealAndSend egress filter would drop it anyway; skipping avoids a pointless sealed frame.
        if (!bridges.isBridgeCandidate(deviceId)) sealAndSend(deviceId, daemonInfo())
    }

    /** What every device learns about this daemon after a handshake — version-stamped (issue #200) in one
     *  place so the handshake, the #161 twin re-send and the update re-announce can't drift apart. */
    private fun daemonInfo(): DaemonInfo =
        dev.ccpocket.daemon.update.UpdateState.stamp(
            DaemonInfo(
                lanUrl(), hostname(), gatewayBaseUrl(), bridgeControl = true,
                supportedAgents = DAEMON_SUPPORTED_AGENT_WIRES,
                supportsUsageAgentFilter = true, // issue #258: this build honors FetchUsage.agent
            ),
        )

    /**
     * Re-announce [DaemonInfo] to every device with a live RELAY session — called when the daily check
     * learns a newer release exists (issue #200), so a phone that has been attached for days sees the
     * nudge without waiting for a reconnect. Bridges/guests are excluded exactly as at handshake time.
     *
     * Direct-LAN sessions are NOT covered (they're owned by [dev.ccpocket.daemon.server.WsConnection],
     * which emits its own DaemonInfo at gate time): a LAN device attached across the daily check keeps a
     * stale latestVersion until it reconnects. Degradation only — the version fields are advisory, and
     * "slightly stale" beats reaching across transports for a once-a-day nicety.
     */
    suspend fun reannounceDaemonInfo() {
        val info = daemonInfo()
        val targets = mutex.withLock { sessions.keys.toList() }
        for (deviceId in targets) {
            if (bridges.isBridgeCandidate(deviceId)) continue
            runCatching { sealAndSend(deviceId, info) }
        }
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
        var viaTwin = false
        if (plaintext == null) {
            // #161: the empty-PSK twin decrypting means the device did the newest handshake WITHOUT the
            // armed ticket-PSK — it burned the ticket on an earlier, interrupted first attempt. Its
            // static key still authenticated it (the twin exists only for full-power allow-listed
            // devices); promote the twin and abandon the armed PSK below, WITHOUT the finalize ticket
            // proof (never applicable: a twinned device is not a provisional bridge/guest candidate).
            val tw = link.pskShadow
            plaintext = tw?.open(body)
            if (plaintext != null && tw != null) {
                viaTwin = true
                mutex.withLock { link.fallback = link.active; link.active = tw; link.pskShadow = null }
            }
        }
        if (plaintext == null) { log.warn("decrypt failed from ${deviceId.take(8)}…"); return }
        // PSK settled either way — reconnects use authenticated statics; a still-armed twin dies with it
        // (once ANY frame proves a session, the phone provably keyed the other way)
        val confirmedPsk = mutex.withLock { link.pskShadow = null; pskFor.remove(deviceId) }
        // FIRST successful decrypt after pairing: the PSK (the exact pairing ticket) is now PROVEN to be
        // held by this device. If it matches a pending intent, finalize the restricted classification here
        // (bridge #91 OR guest #115) — the one moment the binding is cryptographically exact.
        if (confirmedPsk != null && confirmedPsk.isNotEmpty()) {
            if (viaTwin) {
                log.info("first-contact PSK abandoned for ${deviceId.take(8)}… — device handshook without its ticket (#161)")
                // the post-handshake DaemonInfo sealed under the ticket-bound session this device can't
                // read; re-send it under the just-proven twin so this connect still learns the LAN address
                sealAndSend(deviceId, daemonInfo())
            } else {
                bridges.finalize(deviceId, confirmedPsk)?.let { spec ->
                    log.info("${spec.kind.name.lowercase()} \"${spec.name}\" confirmed on ${deviceId.take(8)}…")
                    // Collaborator Link (SESSION-HANDOFF.md §4.1 step 5): the redeem just proved the connect
                    // ticket — record the contact (label + word-group fingerprint of the peer's static key)
                    // and tell attached OWNER clients (CollaboratorConnected flips the waiting-for-scan UI).
                    if (spec.kind == CredentialKind.COLLABORATOR) {
                        val pubB64 = bridges.pubOf(deviceId)?.let { B64enc.encodeToString(it) } ?: ""
                        runCatching { collaboratorControl?.onRedeemed(deviceId, pubB64) }
                    }
                }
            }
        }
        // FAIL CLOSED: a device that is neither a confirmed RESTRICTED credential (bridge/guest) nor in the
        // full-power allow-list is a provisional credential whose intent lapsed before this first frame
        // (slow pairing near the ticket TTL edge, or a daemon restart that wiped the in-memory
        // provisional/PSK maps — the UNANCHORED announce [onDevicePaired] deliberately parks here rather
        // than in devices.json). It must NOT route as an ungated full-power device — drop it; the owner
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
        // read as the SAME attached client in a conversation's fan-out set (issue #47).
        // §18.2 P2-3: V2 approval frames only reach devices whose ClientCaps declared the capability.
        val capsForDevice = deviceCaps.computeIfAbsent(deviceId) { RequestRouter.ClientCapsHolder() }
        val sink = dev.ccpocket.daemon.conversation.KeyedSink(
            "${dev.ccpocket.daemon.conversation.DEVICE_SINK_KEY_PREFIX}$deviceId",
            OutboundSink { frame ->
                if (!RequestRouter.allowedForCaps(frame, capsForDevice)) return@OutboundSink
                sealAndSend(deviceId, frame)
            },
        )

        // ---- restricted INGRESS gates: both checks live HERE, on the only path where deviceId is
        // authenticated (proven by the Noise static key that just decrypted the frame). Bridge (#91) and
        // guest (#115) each get their own capability whitelist + guard; a full-power owner device is
        // additionally allowed to drive the folder-share control plane. ----
        var toRoute: Frame = env.body
        var origin: String? = null
        var guestScope: GuestScope? = null
        var collabScope: dev.ccpocket.daemon.handoff.CollaboratorScope? = null
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
            bridges.isCollaborator(deviceId) -> {
                // COLLABORATOR link (SESSION-HANDOFF.md §4.1): ZERO-baseline credential. The type
                // whitelist admits only the handoff plane + the granted-session frames; the guard then
                // requires an IN_PROGRESS handoff bound to THIS device for every session-shaped frame.
                val svc = core.registry.handoffs
                val guard = svc?.collaboratorGuard(deviceId)
                // what the OWNER minted this link for (REVIEW-REQUEST.md §13.3). Read from the credential's
                // own spec, never from anything the peer sends; a spec we cannot read leaves the purpose
                // UNKNOWN, which admits neither plane.
                val purpose = bridges.specOf(deviceId)?.purpose ?: dev.ccpocket.protocol.CollaboratorPurpose.UNKNOWN
                if (guard == null || !dev.ccpocket.daemon.handoff.CollaboratorCaps.ingressAllowed(env.body, purpose)) {
                    log.warn("collaborator ${deviceId.take(8)}… sent forbidden ${env.body::class.simpleName} — refused")
                    runCatching { sink.emit(PocketError("collaborator_forbidden", "not permitted for a collaborator link: ${env.body::class.simpleName}", convoIdOf(env.body))) }
                    return
                }
                // fan-out target for ITS OWN offers only (§4.2 offer delivery): keyed per device like the
                // owner attach; the recipient filter — not just the egress whitelist — keeps every other
                // handoff's updates away from this sink.
                svc.attach(sink, recipientDeviceId = deviceId)
                // …and the same filter for the ReviewRequest plane (REVIEW-REQUEST.md §11.1): this sink
                // only ever receives rows addressed to THIS deviceId. A handoff-purpose link attaches too
                // and is filtered on the way out instead — one gate to reason about, not two.
                core.reviews.attach(sink, recipientDeviceId = deviceId)
                when (val v = guard.vet(env.body)) {
                    is dev.ccpocket.daemon.handoff.CollaboratorGuard.Verdict.Deny -> {
                        log.warn("collaborator ${deviceId.take(8)}… ${env.body::class.simpleName} denied: ${v.code}")
                        runCatching { sink.emit(PocketError(v.code, v.message, convoIdOf(env.body))) }
                        return
                    }
                    is dev.ccpocket.daemon.handoff.CollaboratorGuard.Verdict.Allow -> {
                        toRoute = v.frame // workdir forced to the grant's, mode clamped, takeOver stripped
                        collabScope = dev.ccpocket.daemon.handoff.CollaboratorScope(
                            deviceId, v.pathScope,
                            // the grant's ceiling for the PermissionBridge write wall (§8.3);
                            // absent (non-open frames) defaults fail-closed to read-only
                            access = v.access ?: dev.ccpocket.protocol.HandoffAccess.REVIEW_READ_ONLY,
                        )
                    }
                }
            }
            else -> {
                // FULL-POWER owner device: the share/bridge/collaborator control planes (mint / list /
                // revoke) need handles the router lacks, so they're intercepted here — via the SAME
                // dispatcher the LAN transport uses. A restricted credential never reaches this branch
                // (its own whitelist denies these frames), so re-sharing the machine, minting another
                // bridge, or inviting another collaborator is structurally impossible.
                // Also register as a handoff fan-out target (SESSION-HANDOFF.md): keyed per device, so each
                // frame just refreshes the same slot; owner devices only (a restricted credential's egress
                // whitelist would drop HandoffUpdated anyway — this keeps it out of the target set entirely).
                core.registry.handoffs?.attach(sink)
                core.reviews.attach(sink) // an owner device sees every review request this machine sent
                if (isOwnerControlFrame(env.body)) {
                    // OFF the reader loop: a mint suspends ~10s waiting for the relay's PairTicket reply,
                    // which arrives through the SAME single ws reader that called us — dispatching inline
                    // deadlocks the mint into its own timeout (and starves every device for the duration).
                    // The direct-ws leg masked this for shares/bridges; the relay leg hits it every time.
                    val body = env.body
                    core.scope.launch {
                        val handled = dispatchOwnerControl(body, shareControl, bridgeControl, collaboratorControl) { sink.emit(it) }
                        // null control plane (daemon still wiring up / LAN-only serve) — surface it rather
                        // than vanish, mirroring what the router's fall-through used to produce
                        if (!handled) runCatching { sink.emit(PocketError("unsupported", "the daemon isn't ready for ${body::class.simpleName}", null)) }
                    }
                    return
                }
                if (isOffReaderRouterFrame(env.body)) {
                    // Same head-of-line argument as the branch above, for an owner frame the ROUTER
                    // handles: it reaches a mint that waits on a PairTicket this very reader delivers.
                    // The owner checks live in the router, so the frame still goes through it — just not
                    // on the loop it depends on.
                    val body = env.body
                    core.scope.launch { route(body, sink, origin, guestScope, collabScope, deviceId) }
                    return
                }
            }
        }
        route(toRoute, sink, origin, guestScope, collabScope, deviceId)
    }

    /** The router hand-off, extracted so a frame can take it either inline or off the reader loop. */
    private suspend fun route(
        frame: Frame,
        sink: OutboundSink,
        origin: String?,
        guestScope: GuestScope?,
        collabScope: dev.ccpocket.daemon.handoff.CollaboratorScope?,
        deviceId: String,
    ) {
        try {
            // deviceId is the Noise-authenticated transport identity — the handoff gate's ONLY input for
            // "who is driving" (SESSION-HANDOFF.md §5.3: never a frame field)
            core.router.handle(frame, sink, origin, guestScope, caps = deviceCaps.computeIfAbsent(deviceId) { RequestRouter.ClientCapsHolder() }, deviceId = deviceId, collabScope = collabScope) { convoId ->
                mutex.withLock { owned.getOrPut(deviceId) { mutableListOf() }.add(convoId) }
                bridges.guardOf(deviceId)?.noteOpened(convoId)     // bridge (#91)
                bridges.guestGuardOf(deviceId)?.noteOpened(convoId) // guest (#115)
                if (collabScope != null) core.registry.handoffs?.collaboratorGuard(deviceId)?.noteOpened(convoId) // collaborator grant
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.warn("handle ${frame::class.simpleName} failed: ${e.message}")
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
                CredentialKind.COLLABORATOR -> dev.ccpocket.daemon.handoff.CollaboratorCaps.egressAllowed(
                    frame,
                    // same source of truth as ingress: the credential's own spec, fail-closed when absent
                    bridges.specOf(deviceId)?.purpose ?: dev.ccpocket.protocol.CollaboratorPurpose.UNKNOWN,
                )
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
     * The live E2E sessions of ONE device — at most the two ends of a reconnect overlap (issue #146),
     * plus (only until first contact confirms) the empty-PSK twin of the newest handshake (issue #161).
     * [active] seals every outbound frame and is the session that last PROVED itself: it completed the
     * most recent handshake, or successfully decrypted the most recent inbound frame that [active]
     * couldn't. [fallback] is the previous handshake's session, retained because the relay's per-device
     * supersede kick races the dying socket's late frames — its late handshake must not clobber the
     * surviving socket's session (the "僵会话" deafness loop). Each handshake displaces the fallback, so
     * a device never holds more than two proven sessions. [pskShadow] is the ticket-less twin derived
     * beside a PSK-armed handshake for an already-allow-listed device; it either gets promoted by the
     * first inbound frame (the device provably burned its ticket) or dies with the PSK confirmation.
     */
    private class DeviceLink(var active: E2ESession, var fallback: E2ESession? = null, var pskShadow: E2ESession? = null)

    // ---- persistence of paired device public keys (shared with the direct-LAN gate) ----

    private fun persist() = PairedDevices.save(devicePubs, store)

    private fun loadPersisted(): Map<String, ByteArray> = PairedDevices.load(store)

    private companion object {
        val B64dec: Base64.Decoder = Base64.getUrlDecoder()
        val B64enc: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        // ticket TTL (120s at the relay) + slack: how long after an interactive mint a headless mint
        // is refused (and PairLoopback refuses the reverse via BridgeRegistry.intentPending)
        const val TICKET_EXCLUSION_MS = 130_000L
    }
}
