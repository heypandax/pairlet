package dev.ccpocket.daemon.relay

import dev.ccpocket.observability.Diagnostics
import dev.ccpocket.observability.ErrorPath
import dev.ccpocket.observability.ErrorCode
import dev.ccpocket.observability.Stage as DiagnosticStage
import dev.ccpocket.observability.SafeMetrics

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeCaps
import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeVerdict
import dev.ccpocket.daemon.bridge.CredentialKind
import dev.ccpocket.daemon.server.RequestRouter
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.memo.withVoiceMemo
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.ConfigureBridgeRunner
import dev.ccpocket.protocol.ControlBridgeRunner
import dev.ccpocket.protocol.CreateBridge
import dev.ccpocket.protocol.DetachBridgeRunner
import dev.ccpocket.protocol.ListBridges
import dev.ccpocket.protocol.RevokeBridge
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.DAEMON_SUPPORTED_AGENT_WIRES
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
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
    /** The restricted-credential authority (issue #91 bridges, #367 execution links, the retired kinds): classification, constraints,
     *  capability gates. */
    val bridges: BridgeRegistry = BridgeRegistry(),
    /** Wall clock for the armed interactive tickets' local expiry ([onMintedTicket]); a parameter only so
     *  tests can step past it without sleeping. */
    private val clock: () -> Long = System::currentTimeMillis,
    private val send: suspend (deviceId: String, payload: ByteArray) -> Unit,
) {
    private val log = logger("DeviceSessions")

    /** The OWNER bridge control plane (bridge #91 follow-up) lives on [DaemonCore] — the LAN transport
     *  serves it too, so it can't be relay-local state. This is a convenience view. */
    var bridgeControl: dev.ccpocket.daemon.relay.BridgeControl?
        get() = core.bridgeControl
        set(v) { core.bridgeControl = v }
    /** #367: the execution-credential bind hook (see [DaemonCore.executionControl]). */
    var executionControl: dev.ccpocket.daemon.execution.ExecutionControl?
        get() = core.executionControl
        set(v) { core.executionControl = v }
    private val mutex = Mutex()
    private val devicePubs = HashMap<String, ByteArray>(loadPersisted())
    private val psks = ArrayDeque<ArmedPsk>()               // minted tickets, oldest first
    private val pskFor = HashMap<String, ByteArray>()       // deviceId -> first-handshake PSK
    private val sessions = HashMap<String, DeviceLink>()    // deviceId -> its live E2E session(s); see DeviceLink (#146)
    private val owned = HashMap<String, MutableList<String>>()
    private val nextId = AtomicLong(0)
    private val seenThisAttach = HashSet<String>()          // devices the relay re-announced since the last attach
    // #362: relay handshake order for pin ownership (guarded by [mutex]); see [allocatePinOrder]
    private var nextPinHandshakeOrder = 1L
    private var pinOrdersExhausted = false

    @Volatile
    private var lastInteractiveMintAt = 0L // serializes interactive vs headless pairing (issue #91)

    /**
     * One armed first-contact PSK. [expiresAt] is set for INTERACTIVE (owner) tickets only: past it the entry
     * can neither anchor an announced device into the full-power allow-list nor be bound as its first-contact
     * PSK. Restricted mints (bridge #91, execution #367) keep their existing lifetime rules — their intents
     * carry their own TTL (see [BridgeRegistry.recordIntent]) — so they stay null here.
     */
    private class ArmedPsk(val bytes: ByteArray, val expiresAt: Long?, val pairingId: String? = null)

    /** Outcomes of interactive pairings, for `pairlet pair` to wait on ([awaitOwnerPairing]). */
    private val ownerPairings = OwnerPairingWatch(clock)

    // In-memory, since this process started: when each full-power device was anchored here, and its last
    // relay handshake. Display only (`pairlet devices`); never consulted for authority.
    private val anchoredAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Full-power devices the OWNER revoked (`pairlet devices revoke`) are tombstoned on disk in the registry
    // ([BridgeRegistry.tombstoneRevokedDevice]) until the relay confirms — see [revokeOwnerDevice].
    init {
        // A revoke interrupted between its tombstone and the devices.json rewrite (a crash, a kill): finish it now,
        // before the relay link or the direct-LAN listener — which reads devices.json — can serve the device.
        val interrupted = devicePubs.keys.filter { bridges.isRetiredCredential(it) }
        if (interrupted.isNotEmpty()) {
            interrupted.forEach { devicePubs.remove(it) }
            PairedDevices.save(HashMap(devicePubs), store)
            log.info("finished ${interrupted.size} interrupted device revoke(s): pruned from the allow-list, relay revoke pending")
        }
    }

    /** A freshly minted pairing ticket becomes a candidate PSK for the next device that pairs.
     *  Only INTERACTIVE mints stamp the exclusion clock — see [interactivePairingPending] — and only they
     *  expire locally, [armedTicketLifetimeMs] after arming ([ttlSec] = the relay's own ticket TTL).
     *  Returns the pairing id an interactive mint's outcome can be awaited under ([awaitOwnerPairing]). */
    fun onMintedTicket(ticket: String, headless: Boolean = false, ttlSec: Int = RELAY_TICKET_TTL_SEC): String? {
        val now = clock()
        if (!headless) lastInteractiveMintAt = now
        val expiresAt = if (headless) null else now + armedTicketLifetimeMs(ttlSec)
        val pairingId = if (headless) null else newPairingId()
        if (pairingId != null && expiresAt != null) ownerPairings.open(pairingId, expiresAt)
        val evicted = ArrayList<ArmedPsk>()
        synchronized(psks) {
            evicted += dropExpiredArmed(now)
            psks.addLast(ArmedPsk(ticket.encodeToByteArray(), expiresAt, pairingId))
            while (psks.size > 8) evicted += psks.removeFirst()
        }
        evicted.forEach { e -> e.pairingId?.let { ownerPairings.resolve(it, OwnerPairingWatch.Outcome.Expired) } }
        return pairingId
    }

    /** Under `synchronized(psks)`: forget every interactive ticket whose local lifetime is over. An expired
     *  owner ticket must not linger as the LIFO candidate a late (or relay-forged) announce could pop. */
    private fun dropExpiredArmed(now: Long): List<ArmedPsk> {
        val gone = psks.filter { it.expiresAt != null && it.expiresAt <= now }
        if (gone.isNotEmpty()) psks.removeAll(gone.toSet())
        return gone
    }

    /** `pairlet pair` waiting on its pairing: see [OwnerPairingWatch.await]. */
    suspend fun awaitOwnerPairing(pairingId: String, waitMs: Long): OwnerPairingWatch.Outcome =
        ownerPairings.await(pairingId, waitMs)

    fun ownerPairingRemainingMs(pairingId: String): Long = ownerPairings.remainingMs(pairingId)

    /** One full-power device as `pairlet devices` lists it. */
    class OwnerDevice(
        val deviceId: String,
        val pub: ByteArray,
        /** When it was anchored here — known only for devices paired since this daemon started. */
        val pairedAt: Long?,
        /** Its first post-pairing contact over the relay has not completed yet. */
        val firstContactPending: Boolean,
    )

    /** Every device in the FULL-POWER allow-list (devices.json) — never a bridge, execution link or guest. */
    suspend fun ownerDevices(): List<OwnerDevice> = mutex.withLock {
        devicePubs.map { (id, pub) -> OwnerDevice(id, pub.copyOf(), anchoredAt[id], pskFor.containsKey(id)) }
    }

    /**
     * The owner revokes a FULL-POWER device (`pairlet devices revoke`): the local half happens here, at once, in
     * this order —
     *
     *  1. the id is tombstoned on disk ([BridgeRegistry.tombstoneRevokedDevice]), so from now until the relay
     *     confirms the revoke no replayed announce can re-anchor it, across any number of restarts;
     *  2. its key leaves devices.json (which also cuts a live direct-LAN socket via the allow-list epoch), with its
     *     relay session and its first-contact PSK.
     *
     * The caller then sends the relay's `RevokeDevice`; the relay client also re-sends it after every attach while
     * the tombstone stands ([pendingRetiredRevocations]). A crash after 1 is finished at the next start (see
     * `init`); a crash after 2 just leaves the revoke to that re-send. False when [deviceId] is not a full-power
     * device (restricted credentials have their own commands).
     */
    suspend fun revokeOwnerDevice(deviceId: String): Boolean {
        if (bridges.isRestricted(deviceId) || !mutex.withLock { devicePubs.containsKey(deviceId) }) return false
        bridges.tombstoneRevokedDevice(deviceId) // a failed write is logged there; the local cut goes ahead
        onDeviceRevoked(deviceId)
        anchoredAt.remove(deviceId)
        return true
    }

    /** How many revoked devices the relay has not confirmed yet — every tombstoned id, whatever retired it. */
    fun revocationsPendingCount(): Int = bridges.retiredCredentialIds().size

    private fun newPairingId(): String =
        B64enc.encodeToString(ByteArray(12).also { java.security.SecureRandom().nextBytes(it) })

    /** True while an interactive pairing ticket could still be redeemed — a headless mint must wait.
     *  Mint serialization (issue #91): with both ticket classes outstanding, the LIFO PSK-arming in
     *  [onDevicePaired] could cross-bind them. Classification itself stays exact regardless (it hashes
     *  the CONFIRMED handshake PSK — [BridgeRegistry.finalize]), but a cross-armed PSK fails BOTH
     *  devices' first handshakes, a pointless outage; refusing the overlap removes the window. */
    fun interactivePairingPending(now: Long = clock()): Boolean =
        interactivePairingRemainingMs(now) > 0

    /** How much longer an interactive pairing blocks a headless mint (0 = not blocking). Lets a refused
     *  caller be told when to retry rather than made to poll (#367). */
    fun interactivePairingRemainingMs(now: Long = clock()): Long =
        (lastInteractiveMintAt + TICKET_EXCLUSION_MS - now).coerceAtLeast(0)

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
     *  announce is unsolicited) the intent that says "this is a bridge / a guest / an execution link"
     *  is gone with it: promoting the key would silently hand someone a full owner slot, past every
     *  restricted capability gate. Such a key is
     *  held provisional instead — never persisted, never allow-listed — so its first frame hits the same
     *  fail-closed `recognized` check in [transport] and the owner mints a fresh invite. */
    suspend fun onDevicePaired(deviceId: String, devicePubB64: String) {
        val pub = runCatching { B64dec.decode(devicePubB64) }.getOrNull() ?: return
        // confirmed bridge/guest: replay must not leak the key into devices.json. A retired credential's id (a
        // Collaborator Link, a folder-share guest, or a full-power device the owner revoked — tombstoned until the
        // relay confirms its revoke) is held off the same way: its key is gone, so without this it would look like
        // a brand-new device and could be armed with someone's pairing ticket. Never a fresh pairing: the relay
        // mints a new random device id on every redeem, so a re-paired phone arrives under a new id.
        if (bridges.isRestricted(deviceId) || bridges.isRetiredCredential(deviceId)) {
            mutex.withLock { seenThisAttach.add(deviceId) }
            return
        }
        var provisionalBridge = false
        var unanchored = false
        var pairingId: String? = null
        val known = mutex.withLock {
            seenThisAttach.add(deviceId)
            val already = devicePubs[deviceId]?.contentEquals(pub) == true ||
                bridges.pubOf(deviceId)?.contentEquals(pub) == true // provisional re-announce: no PSK re-arm
            if (!already) {
                // LIFO: the device scanned the most recently minted link. Attach-replays of an
                // already-known key must NOT re-arm a PSK (that would lock its next LAN connect out).
                // `armed` is the ARMING FACT and is what decides authority below; the empty fallback is
                // only the byte value the responder handshake needs.
                // An interactive ticket past its local lifetime is gone before the pop: a late announce —
                // or one the relay forges long after the owner ran `pairlet pair` — finds nothing to anchor on.
                val popped = synchronized(psks) { dropExpiredArmed(clock()); psks.removeLastOrNull() }
                // …and an owner ticket at the very edge must also claim its pairing: the watch decides expiry
                // for this announce and for a waiting `pairlet pair` under one lock, so the CLI can never report
                // "expired" for a ticket that went on to anchor a key. A failed claim = the ticket is gone.
                val usable = popped != null && (popped.pairingId == null || ownerPairings.tryClaim(popped.pairingId))
                pairingId = popped?.pairingId?.takeIf { usable }
                val armed = popped?.takeIf { usable }?.bytes?.takeIf { it.isNotEmpty() }
                pskFor[deviceId] = armed ?: ByteArray(0)
                provisionalBridge = armed != null && bridges.looksHeadless(armed)
                // issue #207: an armed ticket that is NOT itself a pending restricted intent, while such
                // an intent IS outstanding, means overlapping mints mis-armed the LIFO stack — the mint
                // serialization refuses every interactive mint for as long as an intent pends, so this
                // announce cannot be an ordinary interactive pairing. Anchoring it would write what is
                // really a restricted credential's key into the full-power allow-list; park it instead.
                // Only an INTERACTIVE ticket (`pairlet pair`, the one flow where the owner is present and it expires
                // locally) may anchor a full-power key. A headless-armed ticket anchors nothing but its own pending
                // restricted intent: once that intent lapsed the ticket still sits here (it has no local expiry), and
                // a late or relay-forged announce popping it must not be written into devices.json.
                unanchored = armed == null || (!provisionalBridge && (popped?.pairingId == null || bridges.intentPending()))
                if (unanchored || provisionalBridge) bridges.holdProvisional(deviceId, pub)
                else devicePubs[deviceId] = pub
            }
            already
        }
        if (!known) {
            val owner = !provisionalBridge && !unanchored
            if (owner) {
                persist() // nothing provisional ever touches devices.json
                anchoredAt[deviceId] = clock()
            }
            // the `pairlet pair` that minted this ticket learns who joined on it (or that it was refused)
            pairingId?.let {
                ownerPairings.resolve(it, if (owner) OwnerPairingWatch.Outcome.Paired(deviceId, pub.copyOf()) else OwnerPairingWatch.Outcome.Refused)
            }
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
        val (stale, staleBridges, goneRetired) = mutex.withLock {
            if (seenThisAttach.isEmpty() && !authoritativeEmpty) return
            val s = (devicePubs.keys - seenThisAttach).toList().onEach {
                devicePubs.remove(it); sessions.remove(it)?.let { link -> retirePins(link) }; pskFor.remove(it)
            }
            // bridges revoked while we were offline are pruned the same way (their rows vanish from the
            // replay). A NEW relay replays headless rows to us (we announce PROTO_V_HEADLESS); an OLD
            // relay has no headless column and replays them as ordinary devices — either way a live
            // bridge is in the set and survives.
            val sb = bridges.ids().filter { it !in seenThisAttach }.onEach { sessions.remove(it); pskFor.remove(it) }
            // a retired credential the replay no longer carries is already revoked at the relay
            val gr = bridges.retiredCredentialIds().filter { it !in seenThisAttach }
            Triple(s, sb, gr)
        }
        goneRetired.forEach { bridges.confirmRetired(it) }
        staleBridges.forEach { bridges.remove(it) }
        // #362: a device the replay no longer announces loses its pin push slot with its session
        (stale + staleBridges).forEach { core.projectPins.detach("${dev.ccpocket.daemon.conversation.DEVICE_SINK_KEY_PREFIX}$it") }
        (stale + staleBridges).forEach { core.router.managedSessionService?.detach("${dev.ccpocket.daemon.conversation.DEVICE_SINK_KEY_PREFIX}$it") } // #360
        if (stale.isNotEmpty()) {
            persist()
            log.info("pruned ${stale.size} revoked device(s) after attach replay")
        }
        if (staleBridges.isNotEmpty()) log.info("pruned ${staleBridges.size} revoked bridge(s) after attach replay")
    }

    /** The relay says this device was just revoked: cut key + live E2E session immediately. The persist
     *  bumps [PairedDevices.epoch], which also severs any LIVE direct-LAN socket on its next frame. For any
     *  RESTRICTED credential (a BRIDGE #91, an EXECUTION link #367) this ALSO ends its running sessions now
     *  — the owner's "revoke" promise is "their sessions end", not merely "their link drops". A retired
     *  folder-share guest still loaded is cut the same way, minus the close-by-label (below);
     *  its normal path out is [retireLegacyGuests]. */
    suspend fun onDeviceRevoked(deviceId: String) {
        val wasGuest = bridges.isGuest(deviceId)
        // every restricted kind — a revoke must end its sessions (issue #91: a bridge's live Claude turn
        // otherwise keeps editing files until the idle reaper claims it)
        val wasRestricted = bridges.isRestricted(deviceId)
        // read BEFORE bridges.remove. Never for a guest (folder sharing is retired): its label is free text in the
        // same namespace as a bridge's origin, so closing by it could end a same-named bridge's sessions. A guest's
        // own conversations are the ones this connection opened ([owned]), closed below by id.
        val revokedOrigin = if (wasRestricted && !wasGuest) bridges.specOf(deviceId)?.name else null
        val revokedConvos = mutex.withLock {
            devicePubs.remove(deviceId); sessions.remove(deviceId)?.let { retirePins(it) }; pskFor.remove(deviceId)
            // an owner revoke during an attach replay: the relay DID carry the id, so the replay barrier must not
            // read its absence as "already revoked there" and drop the tombstone ([reconcileReplay])
            if (!bridges.isRetiredCredential(deviceId)) seenThisAttach.remove(deviceId)
            if (wasRestricted) owned.remove(deviceId).orEmpty() else emptyList()
        }
        bridges.remove(deviceId) // a revoked credential loses its entry (and live guard) the same instant
        // #362: its pin push slot too — delivery already re-checks membership, this keeps the table bounded
        core.projectPins.detach("${dev.ccpocket.daemon.conversation.DEVICE_SINK_KEY_PREFIX}$deviceId")
        core.router.managedSessionService?.detach("${dev.ccpocket.daemon.conversation.DEVICE_SINK_KEY_PREFIX}$deviceId") // #360
        // its memo jobs stop and its cached transcripts go: a re-paired device is a new owner, not this one
        runCatching { core.router.revokeVoiceMemoDevice(deviceId) }
        persist()
        // force-close the revoked credential's convos NOW (kills their process trees) — the owner's revoke
        // promise is "their sessions end", not "their link drops": a bridge's running Claude turn must not
        // outlive the revoke (#91). The per-connection `owned` list covers this connection; closeByOrigin ALSO
        // reaps convos opened on an EARLIER connection (which `owned` cleared on disconnect) so nothing keeps
        // running past the revoke (issue #115 crypto review L1).
        if (wasRestricted) {
            revokedConvos.forEach { runCatching { core.registry.close(it, force = true) } }
            revokedOrigin?.let { runCatching { core.registry.closeByOrigin(it) } }
        }
        log.info("device revoked: ${deviceId.take(8)}… — pruned from allow-list${if (wasRestricted) " (${if (wasGuest) "guest " else ""}sessions ended)" else ""}")
    }

    /**
     * The relay's `DeviceRevoked` control. For a retired credential's tombstoned id (a Collaborator Link, a
     * folder-share guest, or a full-power device the owner revoked) this is the confirmation that its credential
     * is dead at the relay: the tombstone goes,
     * and — since this daemon holds nothing else for that id — that is all (no allow-list rewrite, so no live LAN
     * socket is cut for it). Every other id takes [onDeviceRevoked], exactly as before.
     */
    suspend fun onRelayDeviceRevoked(deviceId: String) {
        val retired = bridges.confirmRetired(deviceId)
        if (retired && !mutex.withLock { devicePubs.containsKey(deviceId) } && bridges.pubOf(deviceId) == null) return
        onDeviceRevoked(deviceId)
    }

    /** One relay revoke per retired credential still tombstoned — what the relay client sends once the
     *  relay's device set is known (after the replay barrier, or right after attach on a relay without one).
     *  Repeats on every attach until each is confirmed; a revoke the relay has already applied is a no-op. */
    fun pendingRetiredRevocations(): List<dev.ccpocket.protocol.RevokeDevice> =
        bridges.retiredCredentialIds().map { dev.ccpocket.protocol.RevokeDevice(it) }

    /**
     * Folder sharing (#115) was retired (2026-10): retire every GUEST credential this daemon still holds. Called
     * by the relay client once the relay's device set is known, right before it asks for
     * [pendingRetiredRevocations] — which then include these ids. In this order:
     *
     *  1. tombstone the ids ([BridgeRegistry.tombstoneGuests]); if that write fails nothing happens at all and the
     *     next attach tries again;
     *  2. tell a guest that is online right now that its access ended — the one [ShareEnded] this daemon still
     *     sends, sealed with the E2E session it already holds and passed by the GUEST line of the egress gate;
     *  3. clear the keys ([BridgeRegistry.forgetRetiredGuests] — only guests.json is rewritten), drop the live
     *     session and close the conversations THIS credential opened, by deviceId ([owned]) — never by label: a
     *     guest's label is free text in the same namespace as a bridge's origin.
     *
     * Every other credential, the full-power allow-list and the files behind them are left alone. Returns true
     * when a notice was sealed, so the caller can give it a head start before the relay cuts the socket.
     */
    suspend fun retireLegacyGuests(): Boolean {
        val retired = bridges.tombstoneGuests(bridges.guestIds())
        if (retired.isEmpty()) return false
        var noticed = false
        for (id in retired) {
            if (mutex.withLock { sessions.containsKey(id) }) {
                runCatching { sealAndSend(id, ShareEnded(ShareEnded.REASON_REVOKED, hostname())) }.onSuccess { noticed = true }
            }
        }
        bridges.forgetRetiredGuests(retired)
        for (id in retired) {
            val convos = mutex.withLock {
                sessions.remove(id)?.let { retirePins(it) }; pskFor.remove(id)
                owned.remove(id).orEmpty()
            }
            convos.forEach { runCatching { core.registry.close(it, force = true) } }
        }
        log.info("retired ${retired.size} folder-share guest credential(s)${if (noticed) " — access-ended notice sent" else ""}")
        return noticed
    }

    /**
     * #367: does this daemon already know [deviceId] under an identity OTHER than a just-confirmed
     * execution credential? The union the execution bind hook must never collide with:
     *
     *  - the FULL-POWER allow-list ([devicePubs] / devices.json);
     *  - a confirmed restricted credential of any other kind ([BridgeRegistry.ids] minus the execution row);
     *  - a key still held PROVISIONAL (announced, not yet classified);
     *  - a retired credential's tombstoned id (the relay may still honour it until its revoke is confirmed).
     *
     * The credential being bound right now is excluded by construction, not by a special case:
     * [BridgeRegistry.finalize] has already moved it out of `provisionalPub` into `bridgePubs` with an
     * EXECUTION spec, so it matches none of the clauses — while every other collision does.
     */
    suspend fun isKnownDevice(deviceId: String): Boolean =
        mutex.withLock { devicePubs.containsKey(deviceId) } ||
            bridges.isBridge(deviceId) || bridges.isGuest(deviceId) || bridges.isRetiredCredential(deviceId) ||
            (bridges.pubOf(deviceId) != null && !bridges.isRestricted(deviceId))

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
     *  bookkeeping for the restricted-credential revoke path and still resets; owned conversations keep running in the
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
            Diagnostics.report(ErrorPath.HANDSHAKE, DiagnosticStage.HANDSHAKE, ErrorCode.REJECTED,
                metrics = SafeMetrics(byteCount = deviceEphPub.size.toLong()))
            log.warn("malformed handshake from ${deviceId.take(8)}… (${e::class.simpleName}) — dropped")
            return
        }
        val (derived, responderEph) = response
        val session = derived.first()
        mutex.withLock {
            val link = sessions[deviceId]
            if (link == null) sessions[deviceId] = DeviceLink(session, pskShadow = derived.getOrNull(1)).also {
                it.pinOrders[it.activeCaps] = allocatePinOrder()
            }
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
                // A new connection has declared NOTHING yet: start it at the baseline vocabulary instead
                // of inheriting the previous connection's bits, so a rolled-back App is not fed rows its
                // build cannot decode (see [DeviceLink]). The demoted holder rides the fallback so the
                // supersede-overlap promote below hands the surviving socket its own caps back.
                link.fallbackCaps = link.activeCaps
                link.activeCaps = RequestRouter.ClientCapsHolder()
                // #362: the new connection's handshake order, fixed for its lifetime; orders of holders the link no
                // longer retains are dropped so the table stays bounded
                link.pinOrders.keys.retainAll { it === link.fallbackCaps || it === link.pinOwnerCaps }
                link.pinOrders[link.activeCaps] = allocatePinOrder()
            }
        }
        preHandshakeWarnAt.remove(deviceId) // #298 hygiene: the zombie healed, drop its rate-limit slot
        log.info("handshake from ${deviceId.take(8)}… (psk ${psk.size}B${if (twinned) " + empty-PSK twin" else ""}) → session established")
        dev.ccpocket.daemon.identity.DeviceActivity.noteHandshake(deviceId, dev.ccpocket.daemon.identity.DeviceActivity.VIA_RELAY, clock())
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
                supportsPromptRecovery = true,
                                supportsDiagnostics = true, // #122: acked prompts stay ledgered until agent consumption
                supportsProjectPins = true, // #362: this build owns the per-computer project-pin list
                // #360: managed session list — same source as the LAN transport's copy
                supportsManagedSessions = core.router.managedSessionAgentWires().isNotEmpty(),
                managedAgents = core.router.managedSessionAgentWires(),
                // #348: the backends whose subscription allowance this daemon can read. Same source as the
                // LAN transport's copy (WsConnection) — the router owns the readers, so it owns the answer.
                quotaAgents = core.router.quotaAgentWires(),
            ).withVoiceMemo(core.router.voiceMemoCapability()),
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

    /** Test seam: the wire vocabulary the device's CURRENT connection has declared (null = no live link).
     *  Its lifecycle — blank on every fresh handshake, restored by a supersede promote — is the whole
     *  point of [DeviceLink.activeCaps], and nothing else observes it from outside the class. */
    internal suspend fun declaredCapsForTest(deviceId: String): RequestRouter.ClientCapsHolder? =
        mutex.withLock { sessions[deviceId]?.activeCaps }

    // #298: a zombied peer (it holds an E2E session we no longer have) retries every few seconds until its
    // process restarts — one WARN per minute per device keeps the log legible without hiding the loop.
    // Security review (2026-08-25): entries ONLY for identifiable devices — the deviceId is the relay's
    // PLAINTEXT routing field, and an attacked relay can mint unlimited fresh ids; an unbounded per-id map
    // keyed on it was a straight memory-DoS. The cap is a second belt for the same reason.
    private val preHandshakeWarnAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun transport(deviceId: String, body: ByteArray) {
        val link = mutex.withLock { sessions[deviceId] }
        if (link == null) {
            // unknown ids are dropped without a trace: they have no diagnostic value (the #298 zombie is
            // by definition an already-paired peer) and logging them hands the relay a log-spam lever
            val known = mutex.withLock { devicePubs.containsKey(deviceId) } || bridges.pubOf(deviceId) != null
            if (!known) return
            val now = System.currentTimeMillis()
            if (preHandshakeWarnAt.size >= PRE_HANDSHAKE_WARN_CAP) preHandshakeWarnAt.clear()
            val last = preHandshakeWarnAt[deviceId] ?: 0L
            if (now - last >= 60_000) {
                preHandshakeWarnAt[deviceId] = now
                log.warn("transport before handshake from ${deviceId.take(8)}… (repeats suppressed for 60s; the peer should self-heal via silence-deafness #298)")
            }
            return
        }
        // Trial-decrypt newest-first (open() only advances its receive counter on SUCCESS, so probing the
        // wrong session is side-effect free; frames arrive sequentially from the relay loop, so opens
        // never race each other). A FALLBACK hit means the older connection instance is the one actually
        // alive — its rival's late handshake stole `active` (#146) — so promote it back under the mutex,
        // and outbound seals follow the proven-alive instance again.
        var plaintext = link.active.open(body)
        if (plaintext == null) {
            val fb = link.fallback
            plaintext = fb?.open(body)
            // the caps holders swap WITH the sessions: the promoted connection gets its own declared
            // vocabulary back, and the late handshake's blank one follows its session into the fallback
            if (plaintext != null && fb != null) mutex.withLock {
                link.fallback = link.active; link.active = fb
                val demoted = link.activeCaps; link.activeCaps = link.fallbackCaps; link.fallbackCaps = demoted
            }
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
                // twin and active came out of the SAME handshake, i.e. the same connection — so its caps
                // holder is valid for both and travels with it rather than being swapped away
                mutex.withLock {
                    link.fallback = link.active; link.active = tw; link.pskShadow = null
                    link.fallbackCaps = link.activeCaps
                }
            }
        }
        if (plaintext == null) { log.warn("decrypt failed from ${deviceId.take(8)}…"); return }
        // #362: the holder of the connection that sent THIS frame, captured once after any promote above. A pin
        // request keeps exactly this holder for its context and its reply, even if a later frame moves `active`.
        val inboundCaps = mutex.withLock { link.activeCaps }
        // PSK settled either way — reconnects use authenticated statics; a still-armed twin dies with it
        // (once ANY frame proves a session, the phone provably keyed the other way)
        val confirmedPsk = mutex.withLock { link.pskShadow = null; pskFor.remove(deviceId) }
        // FIRST successful decrypt after pairing: the PSK (the exact pairing ticket) is now PROVEN to be
        // held by this device. If it matches a pending intent, finalize the restricted classification here
        // (bridge #91 OR execution link #367) — the one moment the binding is cryptographically exact.
        if (confirmedPsk != null && confirmedPsk.isNotEmpty()) {
            if (viaTwin) {
                log.info("first-contact PSK abandoned for ${deviceId.take(8)}… — device handshook without its ticket (#161)")
                // the post-handshake DaemonInfo sealed under the ticket-bound session this device can't
                // read; re-send it under the just-proven twin so this connect still learns the LAN address
                sealAndSend(deviceId, daemonInfo())
            } else {
                bridges.finalize(deviceId, confirmedPsk)?.let { spec ->
                    log.info("${spec.kind.name.lowercase()} \"${spec.name}\" confirmed on ${deviceId.take(8)}…")
                    // #367 execution link: the redeem proved the DERIVED first-contact PSK
                    // (HKDF(ticket ‖ inviteSecret)) — something the relay, which only ever saw the raw
                    // ticket, cannot compute. Bind the proven deviceId + static key into the grant row NOW.
                    // A refusal (unknown/expired/already-bound grant, a deviceId this daemon knows under
                    // another identity, a store that cannot persist) means no grant names this device, so
                    // the credential must not survive the frame that created it: drop key, spec and session
                    // and let the owner re-approve. Nothing is routed either way.
                    if (spec.kind == CredentialKind.EXECUTION) {
                        // the bind hook lives on the execution planes; load them if this process has not
                        // yet (no-op when loaded, false with no relay leg → hook null → refused as before)
                        core.ensureExecution()
                        val pubB64 = bridges.pubOf(deviceId)?.let { B64enc.encodeToString(it) } ?: ""
                        val bound = runCatching { executionControl?.onRedeemed(deviceId, pubB64, spec.grantId) }
                            .getOrElse { if (it is CancellationException) throw it else null } == true
                        if (!bound) {
                            log.warn("execution credential ${deviceId.take(8)}… could not be bound to a grant — refused")
                            bridges.remove(deviceId)
                            mutex.withLock { sessions.remove(deviceId)?.let { link -> retirePins(link) } }
                            return
                        }
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
        val env = runCatching { PocketJson.decodeFromString<Envelope>(plaintext.decodeToString()) }
            .onFailure { Diagnostics.protocolDecodeFailed(it, plaintext.size.toLong()) }.getOrNull() ?: return
        log.info("← ${env.body::class.simpleName} from ${deviceId.take(8)}…")

        // keyed: relay sinks are minted per frame — the deviceId key makes every frame from this device
        // read as the SAME attached client in a conversation's fan-out set (issue #47).
        // §18.2 P2-3: V2 approval frames only reach devices whose ClientCaps declared the capability.
        // Resolved at EMIT time off the link, never captured: a conversation sink outlives the reconnect
        // that re-keys the device (same argument as [sealAndSend] resolving the session late), and after a
        // supersede promote the winning connection's holder is a different object than the one in hand.
        val capsNow = { link.activeCaps }
        val sink = dev.ccpocket.daemon.conversation.KeyedSink(
            "${dev.ccpocket.daemon.conversation.DEVICE_SINK_KEY_PREFIX}$deviceId",
            OutboundSink { frame ->
                if (!RequestRouter.allowedForCaps(frame, capsNow())) return@OutboundSink
                sealAndSend(deviceId, frame)
            },
        )

        // ---- restricted INGRESS gates: both checks live HERE, on the only path where deviceId is
        // authenticated (proven by the Noise static key that just decrypted the frame). A bridge (#91) gets its
        // capability whitelist + guard, an execution link (#367) its own plane; the retired kinds (collaborator,
        // folder-share guest) are refused outright; a full-power owner device additionally drives the owner
        // control planes. ----
        var toRoute: Frame = env.body
        var origin: String? = null
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
            bridges.isExecution(deviceId) -> {
                // #367 EXECUTION link: a PEER DAEMON's run link. ZERO-baseline, and it
                // does not reach the router AT ALL — the whitelist admits only the execution frames, the
                // guard re-authorises the grant behind them on every single frame, and the plane is the
                // only thing they are ever handed to. It gets no sink attach of any kind (no pins, no
                // managed-session slot), so no fan-out can select it as a target.
                val request = env.body as? dev.ccpocket.protocol.ToDaemon
                // A confirmed execution credential is itself evidence of use (ExecutionUsage), so the
                // planes are already loaded by the time the relay delivers this frame. Defensive anyway:
                // if they are not, load them now rather than refuse a link the owner approved. With nothing
                // to load them with (LAN-only `serve`, a test core) this is a no-op and the refusal below
                // is exactly what it was.
                core.ensureExecution()
                val guard = core.router.executionGuard
                val plane = core.router.executionPlane
                // the static key that ACTUALLY decrypted this frame. The plane must be handed it rather
                // than read the pin out of the grant store: comparing the stored pin with itself is a
                // tautology that would pass for anyone the transport let through.
                val linkPub = bridges.pubOf(deviceId)?.let { B64enc.encodeToString(it) }
                if (request == null || guard == null || plane == null || linkPub == null) {
                    // fail CLOSED: a daemon with no execution plane wired admits no execution frame
                    log.warn("execution link ${deviceId.take(8)}… sent ${env.body::class.simpleName} with no plane wired — refused")
                    runCatching { sealAndSend(deviceId, PocketError("execution_unavailable", "this daemon has no execution plane")) }
                    return
                }
                // ONE gate, in this order: byte budget on the DECODED PAYLOAD (what the peer really sent,
                // before any of it becomes work) → [ExecutionCaps.ingressAllowed] → the grant behind the
                // frame. The whitelist call lives INSIDE the guard on purpose: a second copy of it here
                // would be a second thing to keep in step, and its refusal would miss the shared ledger.
                when (val v = guard.vet(deviceId, request, plaintext.size, firstContact = confirmedPsk?.isNotEmpty() == true)) {
                    is dev.ccpocket.daemon.execution.ExecutionGuard.Verdict.Deny -> {
                        log.warn("execution link ${deviceId.take(8)}… ${env.body::class.simpleName} denied: ${v.code}")
                        runCatching { sealAndSend(deviceId, PocketError(v.code, "execution request refused")) }
                        return
                    }
                    is dev.ccpocket.daemon.execution.ExecutionGuard.Verdict.Allow -> {
                        // the reply path is the egress whitelist and NOTHING else: no ClientCaps holder is
                        // involved (a peer daemon never declares one) and no conversation sink is created
                        plane.handle(deviceId, linkPub, request) { out ->
                            if (dev.ccpocket.daemon.execution.ExecutionCaps.egressAllowed(out)) sealAndSend(deviceId, out)
                        }
                        return
                    }
                }
            }
            bridges.kindOf(deviceId) == CredentialKind.COLLABORATOR -> {
                // A Collaborator Link (session handoff / review contacts, retired 2026-10). Its keys are no longer
                // loaded from disk, so this only ever catches one bound in this process. Refused outright and
                // answered with nothing — [sealAndSend] drops every frame toward this kind.
                log.warn("retired collaborator credential ${deviceId.take(8)}… sent ${env.body::class.simpleName} — refused")
                return
            }
            bridges.kindOf(deviceId) == CredentialKind.GUEST -> {
                // A folder-share guest (#115, retired 2026-10). Its key is still loaded until [retireLegacyGuests]
                // clears it once the relay link is up, so the kind is recognised — and refused outright, every frame.
                // Nothing is answered: [sealAndSend] lets only the access-ended notice through to this kind.
                log.warn("retired folder-share guest ${deviceId.take(8)}… sent ${env.body::class.simpleName} — refused")
                return
            }
            bridges.isRestricted(deviceId) -> {
                // A CONFIRMED restricted credential whose kind none of the branches above claims — i.e. a
                // [CredentialKind] this build has no capability policy for, loaded from a file a NEWER
                // daemon wrote. Falling through to the owner branch (which is what happened before #367)
                // would hand it the full management plane on the strength of "we don't recognise it".
                // Refuse instead: an unknown restricted kind is the least trusted thing here, not the most.
                log.warn("credential ${deviceId.take(8)}… of unsupported kind ${bridges.kindOf(deviceId)} sent ${env.body::class.simpleName} — refused")
                runCatching { sealAndSend(deviceId, PocketError("credential_unsupported", "this daemon cannot police that credential kind")) }
                return
            }
            else -> {
                // FULL-POWER owner device: the bridge control plane (mint / list / revoke) needs handles the
                // router lacks, so it's intercepted here — via the SAME dispatcher the LAN transport uses. A
                // restricted credential never reaches this branch (its own whitelist denies these frames), so
                // minting another bridge is structurally impossible.
                // project-pin pushes (issue #362): ONE subscriber per device key, idempotent across frames, and
                // resolved entirely at emission by [deliverProjectPins]. Attaching delivers nothing by itself —
                // the device's CURRENT connection must also have declared the capability and fetched.
                core.projectPins.attach("${dev.ccpocket.daemon.conversation.DEVICE_SINK_KEY_PREFIX}$deviceId") { snapshot ->
                    deliverProjectPins(deviceId, snapshot)
                }
                // managed session list pushes (issue #360): one slot per owner device, keyed like [sink] so the
                // requester's own reply excludes it. Resolved at emission: the device's CURRENT connection must have
                // declared the capability, rows are cut to its agent vocabulary, and [sink] re-gates the frame type
                // and runs the restricted egress whitelist before sealing.
                core.router.managedSessionService?.attach(
                    sink.key,
                    // registration notices: owner devices only, never a device later reclassified as restricted
                    // …and only to a connection that declared the managed-session capability (security review R2)
                    onRegisterError = { notice -> if (capsNow().supportsManagedSessions && !bridges.isBridgeCandidate(deviceId)) sink.emit(notice) },
                ) { state ->
                    val now = capsNow()
                    if (now.supportsManagedSessions && !bridges.isBridgeCandidate(deviceId)) {
                        sink.emit(dev.ccpocket.daemon.session.ManagedSessionService.filterAgents(state) { a -> RequestRouter.capsAllow(now, a) })
                    }
                }
                if (env.body is dev.ccpocket.protocol.SyncProjectPins) {
                    // inline, in receive order, and bound to the connection that sent it: its context answers for that
                    // holder alone, and its pin reply is sealed for that connection — never re-routed through a session
                    // a later frame made active. Anything else the router emits takes the ordinary path.
                    val pinSink = dev.ccpocket.daemon.conversation.KeyedSink(
                        "${dev.ccpocket.daemon.conversation.DEVICE_SINK_KEY_PREFIX}$deviceId",
                        OutboundSink { frame ->
                            if (frame is dev.ccpocket.protocol.ProjectPinsState) sealPinReply(deviceId, link, inboundCaps, frame)
                            else sink.emit(frame)
                        },
                    )
                    route(env.body, pinSink, origin, deviceId, { inboundCaps }, RelayPinConnection(deviceId, link, inboundCaps))
                    return
                }
                if (isOwnerControlFrame(env.body)) {
                    // OFF the reader loop: a mint suspends ~10s waiting for the relay's PairTicket reply,
                    // which arrives through the SAME single ws reader that called us — dispatching inline
                    // deadlocks the mint into its own timeout (and starves every device for the duration).
                    // The direct-ws leg masked this for bridges; the relay leg hits it every time.
                    val body = env.body
                    core.scope.launch {
                        val handled = dispatchOwnerControl(body, bridgeControl) { sink.emit(it) }
                        // null control plane (daemon still wiring up / LAN-only serve) — surface it rather
                        // than vanish, mirroring what the router's fall-through used to produce
                        if (!handled) runCatching { sink.emit(PocketError("unsupported", "the daemon isn't ready for ${body::class.simpleName}", null)) }
                    }
                    return
                }
            }
        }
        route(toRoute, sink, origin, deviceId, capsNow)
    }

    /** The router hand-off, extracted so a frame can take it either inline or off the reader loop.
     *  [caps] is resolved lazily for the same reason the sink resolves it lazily: an off-reader dispatch
     *  can run after a promote swapped which connection's holder is the live one. */
    private suspend fun route(
        frame: Frame,
        sink: OutboundSink,
        origin: String?,
        deviceId: String,
        caps: () -> RequestRouter.ClientCapsHolder,
        /** #362: the pin context of the connection that sent a project-pin request; null for every other frame. */
        pin: dev.ccpocket.daemon.pins.ProjectPinConnection? = null,
    ) {
        try {
            // deviceId is the Noise-authenticated transport identity — never a frame field
            // listingLane: this reader serves EVERY device, so a session-list reply (a transcript scan) is produced on
            // the device's own lane instead of here — in order with that device's other listings, behind nobody else's
            core.router.handle(frame, sink, origin, caps = caps(), deviceId = deviceId, pinConnection = pin, listingLane = deviceId) { convoId ->
                mutex.withLock { owned.getOrPut(deviceId) { mutableListOf() }.add(convoId) }
                bridges.guardOf(deviceId)?.noteOpened(convoId)     // bridge (#91)
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

    /**
     * One project-pin push toward [deviceId] (issue #362), with every authority fact resolved NOW and under the
     * lock that guards the allow-list and the live sessions: the device must still be a full-power allow-listed
     * owner (a revoke removes it from both in one step), and the connection whose fetch was accepted last
     * ([DeviceLink.pinOwnerCaps]) must still be retained, not retired, still declare pin support and hold its
     * subscription — which is what gets echoed. The frame is sealed with THAT connection's session, not with
     * [DeviceLink.active]: a late frame of a retired connection may promote its session for ordinary traffic, but
     * never takes pin pushes back. A connection that has not had a fetch accepted gets nothing.
     */
    private suspend fun deliverProjectPins(deviceId: String, snapshot: dev.ccpocket.protocol.ProjectPinsSnapshot) {
        if (bridges.isBridgeCandidate(deviceId) || bridges.isRestricted(deviceId)) return
        val payload = mutex.withLock {
            if (!devicePubs.containsKey(deviceId)) return
            val link = sessions[deviceId] ?: return
            val owner = link.pinOwnerCaps ?: return
            val session = pinSessionOf(link, owner)
            if (session == null) {
                // displaced by further handshakes: nothing to seal for until a newer connection's fetch is accepted
                owner.pinRetired = true
                owner.pinSubscriptionId = null
                link.pinOwnerCaps = null
                return
            }
            val subscription = owner.pinSubscriptionId
            if (owner.pinRetired || !owner.supportsProjectPins || subscription == null) return
            val frame = dev.ccpocket.protocol.ProjectPinsState(subscriptionId = subscription, snapshot = snapshot)
            val json = PocketJson.encodeToString(Envelope(nextId.getAndIncrement().toString(), 0L, body = frame))
            Wire.payload(Wire.TRANSPORT, session.seal(json.encodeToByteArray()))
        }
        send(deviceId, payload)
    }

    /**
     * The reply to one pin request (issue #362), sealed for the connection that sent it and no other. A successful
     * reply needs that connection to be the device's pin owner under the subscription it echoes; a refusal may also
     * answer a connection whose fetch was never accepted — registering nothing — while it is still retained, not
     * retired, and declares pin support. Anything else is dropped: an old reply is never re-routed through a
     * session that a later handshake or frame made active.
     */
    private suspend fun sealPinReply(
        deviceId: String,
        link: DeviceLink,
        holder: RequestRouter.ClientCapsHolder,
        frame: dev.ccpocket.protocol.ProjectPinsState,
    ) {
        if (bridges.isBridgeCandidate(deviceId) || bridges.isRestricted(deviceId)) return
        val json = PocketJson.encodeToString(Envelope(nextId.getAndIncrement().toString(), 0L, body = frame))
        val payload = mutex.withLock {
            if (!pinEligible(deviceId, link, holder)) return
            val session = pinSessionOf(link, holder) ?: return
            val owned = link.pinOwnerCaps === holder && frame.subscriptionId == holder.pinSubscriptionId
            if (frame.error == null && !owned) return
            Wire.payload(Wire.TRANSPORT, session.seal(json.encodeToByteArray()))
        }
        send(deviceId, payload)
    }

    /** Under [mutex]: [holder] is still a connection of [deviceId]'s live [link] that may use the pin plane — and its
     *  handshake is not older than the connection whose fetch was accepted last ([DeviceLink.highestAcceptedPinOrder]). */
    private fun pinEligible(deviceId: String, link: DeviceLink, holder: RequestRouter.ClientCapsHolder): Boolean {
        val order = link.pinOrders[holder] ?: 0L
        return devicePubs.containsKey(deviceId) && sessions[deviceId] === link &&
            !holder.pinRetired && holder.supportsProjectPins && pinSessionOf(link, holder) != null &&
            order > 0 && order >= link.highestAcceptedPinOrder
    }

    /** Under [mutex]: the next relay handshake's pin order. Never wraps or repeats: once positive orders run out every
     *  later handshake gets 0, which fails pin eligibility closed (generic traffic is unaffected). */
    private fun allocatePinOrder(): Long {
        if (pinOrdersExhausted) return 0L
        val order = nextPinHandshakeOrder
        if (order == Long.MAX_VALUE) pinOrdersExhausted = true else nextPinHandshakeOrder = order + 1
        return order
    }

    /** Under [mutex]: the retained session [holder] rides with, or null once further handshakes displaced it. */
    private fun pinSessionOf(link: DeviceLink, holder: RequestRouter.ClientCapsHolder): E2ESession? = when {
        link.activeCaps === holder -> link.active
        link.fallbackCaps === holder -> link.fallback
        else -> null
    }

    /** Under [mutex]: a link leaving [sessions] (revoke, replay prune) takes every pin registration with it. */
    private fun retirePins(link: DeviceLink) {
        for (holder in listOfNotNull(link.activeCaps, link.fallbackCaps, link.pinOwnerCaps)) {
            holder.pinRetired = true
            holder.pinSubscriptionId = null
        }
        link.pinOwnerCaps = null
    }

    /**
     * The relay's pin context for one inbound request (issue #362): bound to the exact connection holder that sent
     * it and answered under [mutex] against the live allow-list and sessions, so it can neither speak for another
     * connection nor outlive a revoke. The pin store's lock may be held while it is asked; nothing here ever
     * waits for that lock while holding [mutex].
     */
    private inner class RelayPinConnection(
        private val deviceId: String,
        private val link: DeviceLink,
        private val holder: RequestRouter.ClientCapsHolder,
    ) : dev.ccpocket.daemon.pins.ProjectPinConnection {
        override suspend fun isCurrent(): Boolean = mutex.withLock { pinEligible(deviceId, link, holder) }

        override suspend fun currentSubscription(): String? = mutex.withLock {
            holder.pinSubscriptionId.takeIf { pinEligible(deviceId, link, holder) && link.pinOwnerCaps === holder }
        }

        /** The accepted fetch makes this connection the device's pin owner: every OLDER connection the link still
         *  holds — and an earlier owner already displaced — is retired for good, and none older can be accepted
         *  again. A NEWER handshake that has not fetched yet stays a candidate: a late fetch of the old connection
         *  must not lock out the one that replaces it. A newer handshake alone retires nothing; only its own
         *  accepted fetch does. The same connection fetching again just refreshes its subscription. */
        override suspend fun acceptFetch(subscriptionId: String): Boolean = mutex.withLock {
            if (!pinEligible(deviceId, link, holder)) return@withLock false
            val order = link.pinOrders.getValue(holder)
            for (other in listOfNotNull(link.activeCaps, link.fallbackCaps, link.pinOwnerCaps)) {
                if (other === holder || (link.pinOrders[other] ?: 0L) >= order) continue
                other.pinRetired = true
                other.pinSubscriptionId = null
            }
            holder.pinSubscriptionId = subscriptionId
            link.highestAcceptedPinOrder = order
            link.pinOwnerCaps = holder
            true
        }
    }

    private suspend fun sealAndSend(deviceId: String, frame: Frame) {
        // ---- restricted EGRESS gate (issue #91 bridges, #367 execution links, the retired kinds): this is the
        // ONLY place frames are sealed toward a relay device, so filtering here covers every source — conversation
        // fan-out, handshake DaemonInfo, resurfaced asks, router errors. A bridge can never receive a PermissionAsk
        // nor any management/identity frame.
        // Keyed on isBridgeCandidate so the provisional window (pre-first-transport handshake) is covered.
        if (bridges.isBridgeCandidate(deviceId)) {
            // learn the sessionIds minted for this credential's convos (SessionLive backfills them) so a
            // later open(resumeId=…)/read is recognized as OWN
            if (frame is SessionLive) frame.sessionId?.let { sid ->
                bridges.guardOf(deviceId)?.noteSession(frame.convoId, sid)
            }
            // egress whitelist by kind. A provisional (kind not yet confirmed) candidate only ever has the
            // handshake DaemonInfo in flight, which every whitelist drops — so fall back to the stricter
            // BRIDGE whitelist until the first transport frame confirms the kind (fail closed).
            val allowed = when (bridges.kindOf(deviceId)) {
                // a retired folder-share guest (2026-10) gets the access-ended notice and nothing else
                CredentialKind.GUEST -> frame is ShareEnded
                // #367: the run plane's own reply path already filters, but this is the ONE place a frame is
                // sealed toward a relay device, so an execution credential is filtered here too — that is
                // what keeps a resurfaced ask, a router error or any future fan-out from reaching it.
                CredentialKind.EXECUTION -> dev.ccpocket.daemon.execution.ExecutionCaps.egressAllowed(frame)
                // a retired Collaborator Link (2026-10) is sent nothing at all
                CredentialKind.COLLABORATOR -> false
                else -> BridgeCaps.egressAllowed(frame)
            }
            if (!allowed) return
        }
        // KTOR-6963: a shipped iOS build drops the whole link on any message over 1 MiB, whatever the relay
        // allows. The frame is sized to the LIVE connection's declared cap (the newest handshake's holder —
        // no link means the frame is undeliverable anyway, see below) before it is sealed.
        val cap = mutex.withLock { sessions[deviceId]?.activeCaps?.maxFrameBytes } ?: return
        var shrunk = false
        val json = try {
            dev.ccpocket.daemon.server.FrameFitter.encodeWithin(
                Envelope(nextId.getAndIncrement().toString(), 0L, body = frame), cap,
            ) { shrunk = true; log.warn("frame cap for ${deviceId.take(8)}…: $it") }
        } catch (error: Exception) {
            Diagnostics.report(ErrorPath.PAYLOAD_SEND, DiagnosticStage.ENCODE, ErrorCode.UNEXPECTED, error)
            throw error
        }
        // the open's history log line wants the size that actually ships (a no-op outside a metered emit)
        dev.ccpocket.daemon.diagnostics.HistoryFrameMeter.record(frame, json.size, shrunk)
        // serialize seals per session (the GCM counter must advance atomically). Resolve the live session
        // at seal time rather than capturing one in the sink: conversation sinks outlive a phone reconnect,
        // and a re-handshake re-keys — a stale session would seal frames the device can't decrypt. No link
        // means this device never handshook (or was revoked/pruned) — the frame is undeliverable, drop it.
        val payload = mutex.withLock {
            val live = sessions[deviceId]?.active ?: return
            Wire.payload(Wire.TRANSPORT, live.seal(json))
        }
        try { send(deviceId, payload) } catch (error: Exception) {
            Diagnostics.report(ErrorPath.PAYLOAD_SEND, DiagnosticStage.WRITE, ErrorCode.SEND_FAILED, error,
                SafeMetrics(byteCount = payload.size.toLong()))
            throw error
        }
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
     *
     * [activeCaps]/[fallbackCaps] ride ALONGSIDE their session because the declared wire vocabulary
     * ([dev.ccpocket.protocol.ClientCaps]) is a property of one CONNECTION, not of the device — the LAN
     * leg already models it that way ([dev.ccpocket.daemon.server.WsConnection] holds one holder per
     * socket). Keying it by deviceId instead made an App DOWNGRADE undetectable: the holder was created
     * once and never cleared, so a phone that had ever declared ZCODE/KIMI/DSH kept those bits after
     * being rolled back to a build that cannot decode them — the old build then hard-failed every
     * Envelope carrying such a row and simply went blank, until an unrelated daemon restart. A fresh
     * handshake now starts fail-closed (baseline CLAUDE/CODEX only) and re-opens the moment that
     * connection's own ClientCaps lands, which every connect volley sends FIRST (`PocketRepository`'s
     * launchTransport — including the deaf-link forced re-handshake, which re-enters the same path).
     * The holders must MOVE WITH the sessions through the promotes below: otherwise a dying socket's
     * late handshake would strip the SURVIVING socket's vocabulary for the rest of its connection,
     * re-breaking exactly what [fallback] exists to protect.
     *
     * [pinOwnerCaps] is the ONE holder whose project-pin fetch was accepted last (issue #362) — deliberately not
     * read off [activeCaps]. Generic frames still promote whichever session proves itself, but such a promote
     * must never hand the pin subscription back to a connection a newer accepted fetch retired. Pin pushes and
     * replies are sealed with the session that holder rides with, found by holder identity, never with [active].
     *
     * [pinOrders] maps each retained relay holder (by identity) to its handshake order — daemon-internal, assigned
     * once per handshake, and it travels with the holder through every promote. [highestAcceptedPinOrder] is the
     * in-memory fence it is compared with: an accepted fetch retires only older connections, and a connection
     * older than the last accepted one can never own pins again. Neither is wire identity nor any authority on
     * its own; every authenticated check above still applies.
     */
    private class DeviceLink(
        var active: E2ESession,
        var fallback: E2ESession? = null,
        var pskShadow: E2ESession? = null,
        var activeCaps: RequestRouter.ClientCapsHolder = RequestRouter.ClientCapsHolder(),
        var fallbackCaps: RequestRouter.ClientCapsHolder = RequestRouter.ClientCapsHolder(),
        var pinOwnerCaps: RequestRouter.ClientCapsHolder? = null,
    ) {
        val pinOrders = java.util.IdentityHashMap<RequestRouter.ClientCapsHolder, Long>()
        var highestAcceptedPinOrder: Long = 0L
    }

    // ---- persistence of paired device public keys (shared with the direct-LAN gate) ----

    /** Snapshot AND write under [mutex]: [devicePubs] is mutated under it from other coroutines, so a
     *  lock-free iteration could throw (and the swallowed failure skip the write) or an older snapshot land
     *  on disk after a newer one — a revoked device written back into the LAN allow-list. Never call this
     *  while holding [mutex] (not reentrant). */
    private suspend fun persist() = mutex.withLock { PairedDevices.save(HashMap(devicePubs), store) }

    private fun loadPersisted(): Map<String, ByteArray> = PairedDevices.load(store)

    private companion object {
        val B64dec: Base64.Decoder = Base64.getUrlDecoder()
        val B64enc: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        // ticket TTL (120s at the relay) + slack: how long after an interactive mint a headless mint
        // is refused (and PairLoopback refuses the reverse via BridgeRegistry.intentPending)
        const val TICKET_EXCLUSION_MS = 130_000L

        /** The relay's ticket TTL (relay `PairingService.TTL_MS`, and the 6-digit code's in `CodeStore`), the
         *  default when a caller has no [dev.ccpocket.protocol.PairTicket.expiresInSec] at hand. */
        const val RELAY_TICKET_TTL_SEC = 120

        /** Local ceiling on the relay-announced TTL: a relay must not be able to stretch an armed owner
         *  ticket's life by announcing a huge `expiresInSec` (same cap #367 applies to its own tickets). */
        const val MAX_ARMED_TICKET_TTL_SEC = 120

        /** Slack beyond the relay TTL. The relay refuses a redeem once its ticket expired and announces the
         *  device inside that same redeem request, and this daemon starts its clock only when the ticket
         *  REACHES it — after the relay started its own — so 10s covers delivery of the announce. Same
         *  130s total as [TICKET_EXCLUSION_MS]. */
        const val ARMED_TICKET_GRACE_MS = 10_000L

        /** How long an INTERACTIVE ticket stays armed here after it arrived: min(relay TTL, 120s) + 10s. */
        fun armedTicketLifetimeMs(ttlSec: Int): Long =
            ttlSec.coerceIn(1, MAX_ARMED_TICKET_TTL_SEC) * 1000L + ARMED_TICKET_GRACE_MS

        /** #298: hard ceiling on the pre-handshake WARN rate-limit map. Known devices number in the tens;
         *  hitting this means something is minting identities and the honest answer is to start over. */
        const val PRE_HANDSHAKE_WARN_CAP = 512
    }
}
