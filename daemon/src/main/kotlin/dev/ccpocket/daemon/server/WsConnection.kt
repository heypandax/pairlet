package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.memo.withVoiceMemo
import dev.ccpocket.daemon.relay.dispatchOwnerControl
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.DAEMON_SUPPORTED_AGENT_WIRES
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
    /** The allow-list lookup the gate consults, re-read PER handshake (see [PairedDevices]) so a device
     *  paired over the relay a moment ago is accepted here without a restart. A parameter only so tests
     *  can supply a fixture instead of the real ~/.cc-pocket/devices.json. */
    val pairedDevices: () -> Map<String, ByteArray> = { PairedDevices.load() },
    /**
     * Is [String] a RESTRICTED credential (bridge #91 / guest #115 / collaborator / execution #367)?
     * Such a key is structurally barred from this gate already — it lives in its own credential file and
     * never in devices.json, which is the only allow-list [pairedDevices] reads — so this is a SECOND,
     * explicit refusal, and it exists because of #367.
     *
     * An execution link is the credential class where an implicit guarantee is not good enough: it is the
     * only one held by another DAEMON on the same LAN, so it is the only one for which "it happens to be
     * in the wrong file" would be a reachable mistake rather than a theoretical one — and the LAN path
     * runs PSK-less, with no capability whitelist and no per-frame grant check anywhere on it. A refusal
     * here means the execution surface exists on exactly one transport, which is the property the design
     * asks for. Defaults to "not restricted" so a LAN-only fixture needs no wiring.
     */
    val restrictedCredential: (String) -> Boolean = { false },
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
 * The transport is E2E only: relay-mode daemons expose it alongside the relay so paired devices on the
 * same machine/LAN can skip the relay entirely. [e2e] gates every socket; after it, frames are sealed
 * BINARY, identical Wire format to the relay data plane. A plaintext frame is simply never dispatched.
 */
class WsConnection(
    private val session: WebSocketSession,
    private val router: RequestRouter,
    private val registry: SessionRegistry,
    private val e2e: LanE2E,
    /** The owner control planes (share #115 / bridge #91) — served on
     *  the LAN transport too, because the desktop app on the daemon's own machine arrives HERE, not over
     *  the relay, and every LAN peer is a full-power owner by construction (restricted credentials can't
     *  pass the LAN gate). Null while the relay link is still coming up. */
    private val ownerControls: (() -> Pair<dev.ccpocket.daemon.relay.ShareControl?, dev.ccpocket.daemon.relay.BridgeControl?>)? = null,
) {
    private val outbox = Channel<Envelope>(Channel.BUFFERED)
    private val nextId = AtomicLong(0)
    private val owned: MutableList<String> = Collections.synchronizedList(mutableListOf())
    // this socket's declared wire vocabulary (ClientCaps) — one holder per connection, upgraded in place
    private val caps = RequestRouter.ClientCapsHolder()
    private var gatedDeviceId: String? = null // which paired device this gated socket authenticated as
    private var allowlistEpoch = PairedDevices.epoch

    private val log = logger("WsConnection")

    private val sink = OutboundSink { frame ->
        // §18.2 P2-3: V2 approval frames only reach clients that declared the capability
        if (!RequestRouter.allowedForCaps(frame, caps)) return@OutboundSink
        outbox.send(Envelope(nextId.getAndIncrement().toString(), System.currentTimeMillis(), body = frame))
    }

    /** #362: is this gated socket's device STILL allow-listed? Read through the gate's own lookup (re-read per
     *  call, exactly like the handshake), so a revoke bites at the next pin frame even on an idle socket. */
    private fun deviceStillAllowListed(): Boolean {
        val id = gatedDeviceId ?: return false
        val lookup = e2e.pairedDevices
        return runCatching { lookup().containsKey(id) }.getOrDefault(false)
    }

    /** #362: this socket's own pin facts for the router — one holder per socket, so nothing here can speak for
     *  another connection. Current while the gated device is still allow-listed, this connection still declares
     *  pin support, and the socket has not closed; a subscription is recorded only once the store accepted the
     *  fetch that names it. */
    private val pinConnection = object : dev.ccpocket.daemon.pins.ProjectPinConnection {
        override suspend fun isCurrent(): Boolean =
            caps.supportsProjectPins && !caps.pinRetired && deviceStillAllowListed()

        override suspend fun currentSubscription(): String? = if (isCurrent()) caps.pinSubscriptionId else null

        override suspend fun acceptFetch(subscriptionId: String): Boolean {
            if (!isCurrent()) return false
            caps.pinSubscriptionId = subscriptionId
            return true
        }
    }

    suspend fun serve() = coroutineScope {
        registry.onLanConnect() // while any LAN socket lives, the idle reaper holds off (like relay peerOnline)
        try {
            // hard cap on concurrent UN-authenticated handshakes: a LAN scanner opening sockets and
            // stalling would otherwise hold an FD + coroutine for the full timeout, times thousands
            if (!e2e.gateSlots.tryAcquire()) { log.warn("direct connect rejected (handshake slots exhausted)"); return@coroutineScope }
            val established = try {
                withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { gateHandshake(e2e) }
            } finally {
                e2e.gateSlots.release()
            }
            if (established == null) { log.info("direct connect rejected (bad/expired handshake)"); return@coroutineScope }
            pump(established)
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
                    // an empty BINARY frame has no type byte to read (DeviceSessions.onFrame guards the
                    // relay path the same way); indexing it would throw out of the whole connection
                    if (frame.data.isEmpty()) return null
                    if (Wire.payloadType(frame.data) != Wire.HANDSHAKE) return null
                    // re-read per handshake: a device paired over the relay minutes ago must work here now
                    val devicePub = gate.pairedDevices()[id]
                    if (devicePub == null) { log.warn("direct connect from unpaired device ${id.take(8)}…"); return null }
                    // a freshly paired device must prove ticket knowledge over the relay FIRST — the LAN
                    // handshake deliberately runs PSK-less and can't provide that pairing-ceremony binding
                    if (gate.firstContactPending(id)) { log.warn("direct connect from ${id.take(8)}… before its first relay handshake — refused"); return null }
                    // #367: a restricted credential (execution link above all) never gets a LAN socket,
                    // even if it somehow reached the allow-list — its enforcement lives on the relay path
                    if (gate.restrictedCredential(id)) { log.warn("direct connect from restricted credential ${id.take(8)}… — refused"); return null }
                    // The allow-list authenticates the device's STATIC key, but the ephemeral bytes in
                    // this frame are still whatever the socket sent. A short, wrong-format, or off-curve
                    // P-256 point makes the crypto provider throw — mirror of the relay path's fix
                    // (DeviceSessions.handshake): reject this connection rather than let untrusted input
                    // escape as an exception. Cancellation is not a crypto failure and must propagate.
                    val (crypto, responderEph) = try {
                        E2ESession.responder(
                            gate.identity.e2ePrivRaw, gate.identity.e2ePubRaw, devicePub, ByteArray(0), Wire.payloadBody(frame.data),
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("malformed direct handshake from ${id.take(8)}… (${e::class.simpleName}) — refused")
                        return null
                    }
                    session.outgoing.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, responderEph)))
                    gatedDeviceId = id
                    // freshly gated: hand the device our current direct address (IP may have changed since it
                    // stored it). outbox is buffered, so this queues until pump()'s writer starts draining.
                    sink.emit(
                        dev.ccpocket.daemon.update.UpdateState.stamp( // version visibility (issue #200)
                            DaemonInfo(
                                gate.lanUrl(), gate.hostname(), gate.gatewayBaseUrl(), bridgeControl = true,
                                supportedAgents = DAEMON_SUPPORTED_AGENT_WIRES,
                                supportsUsageAgentFilter = true, // issue #258: this build honors FetchUsage.agent
                                supportsPromptRecovery = true,
                                supportsDiagnostics = true, // #122: acked prompts stay ledgered until agent consumption
                                supportsProjectPins = true, // #362: this build owns the per-computer project-pin list
                                // #360: managed session list, for the agents the router can actually serve
                                supportsManagedSessions = router.managedSessionAgentWires().isNotEmpty(),
                                managedAgents = router.managedSessionAgentWires(),
                                // #348: which backends' SUBSCRIPTION allowance this daemon can read. The
                                // router owns the answer because it owns the readers; absent (an older
                                // daemon) decodes to empty = "Claude only, legacy behaviour".
                                quotaAgents = router.quotaAgentWires(),
                            ).withVoiceMemo(router.voiceMemoCapability()),
                        ),
                    )
                    log.info("direct E2E session established with ${id.take(8)}…")
                    return crypto
                }
                else -> {}
            }
        }
        return null // socket closed mid-handshake
    }

    private suspend fun pump(crypto: E2ESession) = coroutineScope {
        // project-pin pushes (issue #362) for this gated socket's device. Resolved at emission, and re-checked
        // by the writer.
        val pins = router.projectPinService
        pins?.attach(sink) { snapshot ->
            val subscription = caps.pinSubscriptionId
            if (caps.supportsProjectPins && !caps.pinRetired && subscription != null && deviceStillAllowListed()) {
                sink.emit(dev.ccpocket.protocol.ProjectPinsState(subscriptionId = subscription, snapshot = snapshot))
            }
        }
        // managed session list pushes (issue #360): every LAN peer is an owner by construction (see the handoff
        // attach above). Resolved at emission against THIS socket's current declaration and agent vocabulary, and
        // the sink's own allowedForCaps gate re-checks the frame type.
        // #360 security review M2: a GATED socket whose device was revoked while idle must not receive a push. The frame
        // is still handed to the writer, which re-checks the allow-list right before sealing, drops it and closes the
        // socket (the #362 pin rule) — so an idle revoked link is cut by the push itself.
        val managed = router.managedSessionService
        managed?.attach(
            sink,
            // security review R2: the registration notice, like every managed frame, only to a declared connection
            onRegisterError = { notice -> if (caps.supportsManagedSessions) sink.emit(notice) },
        ) { state ->
            if (caps.supportsManagedSessions) {
                sink.emit(dev.ccpocket.daemon.session.ManagedSessionService.filterAgents(state) { a -> RequestRouter.capsAllow(caps, a) })
            }
        }
        val writer = launch {
            for (env in outbox) {
                val body = env.body
                // #362: a pin frame is re-checked right before it is sealed, INCLUDING one that was queued earlier.
                // A device revoked while idle is cut here without waiting for an inbound frame; a closed connection
                // or one that no longer declares the capability gets nothing; a push or a successful reply must
                // still carry the current subscription. A refusal answers a request this very socket sent, so it
                // may reach a connection whose fetch was never accepted — without that registering anything.
                // #360 security review M2: the same rule for managed session frames (replies, pushes and the
                // registration notice): a revoked device's socket never gets one sealed, and is closed.
                if (body is dev.ccpocket.protocol.ManagedSessionsState || body is dev.ccpocket.protocol.DiscoveredSessions ||
                    (body is PocketError && body.code == dev.ccpocket.daemon.session.ManagedSessionService.REGISTER_FAILED)
                ) {
                    if (!deviceStillAllowListed()) error("device revoked — closing live direct link")
                }
                // voice memo → tasks: a snapshot carries a transcript. The job may have been registered in the
                // instant before its device was revoked; the socket that is still open must not be handed the result.
                if (body is dev.ccpocket.protocol.VoiceMemoState) {
                    if (!deviceStillAllowListed()) error("device revoked — closing live direct link")
                }
                if (body is dev.ccpocket.protocol.ProjectPinsState) {
                    if (!deviceStillAllowListed()) error("device revoked — closing live direct link")
                    if (!caps.supportsProjectPins || caps.pinRetired) continue
                    val refusal = body.requestId != null && body.error != null
                    if (!refusal && body.subscriptionId != caps.pinSubscriptionId) continue
                }
                // KTOR-6963: a shipped iOS build drops the whole link on any message over 1 MiB, whatever the
                // relay allows. Shrink what can be shrunk (history windows, tool images, file bodies) to THIS
                // connection's declared cap right before sealing — the writer is where the size is final.
                val bytes = FrameFitter.encodeWithin(env, caps.maxFrameBytes) { log.warn("frame cap: $it") }
                // the writer is the ONLY sealer — the GCM send counter advances strictly in order
                val ws: WsFrame = WsFrame.Binary(true, Wire.payload(Wire.TRANSPORT, crypto.seal(bytes)))
                // bounded write: on a zombie phone socket a send stalls forever (TCP buffer fills, no error),
                // wedging this writer and, once outbox fills, every pump feeding it. Stalled → tear down.
                if (withTimeoutOrNull(WRITE_TIMEOUT_MS) { session.outgoing.send(ws) } == null) {
                    error("socket write stalled — dead LAN link")
                }
            }
        }
        // A revoke cuts a gated socket the moment it is written (audit 2026-10-04): the epoch check in the read
        // loop below only ran when THIS device sent a frame, so a silent revoked device kept receiving every
        // session stream, approval card and handoff/review row meanwhile. Throwing fails this scope — reader
        // and writer with it — exactly like the writer's own "device revoked" refusal.
        val revokeWatch = if (gatedDeviceId != null) launch {
            PairedDevices.epochChanges.collect { epoch ->
                if (epoch != allowlistEpoch && !deviceStillAllowListed()) error("device revoked — closing live direct link")
            }
        } else null
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
                    // isNotEmpty() before payloadType() for the same reason as in the gate above: a zero-byte
                    // BINARY frame has no type byte, and this loop is the whole connection
                    frame is WsFrame.Binary && frame.data.isNotEmpty() &&
                        Wire.payloadType(frame.data) == Wire.TRANSPORT ->
                        crypto.open(Wire.payloadBody(frame.data))?.decodeToString()
                            ?: run { log.warn("decrypt failed on direct link"); null }
                    else -> null // a TEXT or empty frame after the gate — never dispatched
                }
                if (text == null) continue
                val env = runCatching { PocketJson.decodeFromString<Envelope>(text) }.getOrNull()
                if (env != null) {
                    // transport-layer frame: the E2E gate above consumes the real one, so a LanHello arriving
                    // sealed after it is never routed. Keeps the router transport-agnostic.
                    if (env.body is LanHello) continue
                    // Apply connection vocabulary in receive order before spawning business work.
                    if (env.body is dev.ccpocket.protocol.ClientCaps) {
                        router.handle(env.body, sink, caps = caps, deviceId = gatedDeviceId) { owned.add(it) }
                        continue
                    }
                    // #362: pin requests run in receive order too, like the relay's inline route: this connection's
                    // fetch (which registers its subscription once accepted) and its operation batches commit in send
                    // order.
                    if (env.body is dev.ccpocket.protocol.SyncProjectPins) {
                        try {
                            router.handle(
                                env.body, sink, caps = caps, deviceId = gatedDeviceId,
                                pinConnection = pinConnection,
                            )
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            log.warn("handle SyncProjectPins failed: ${e::class.simpleName}")
                        }
                        continue
                    }
                    // voice memo → tasks: in receive order as well, like the relay's inline route — a start must be
                    // registered before its first chunk, or that chunk is answered "unknown job", dropped, and the
                    // upload then waits out its idle timeout holding the device's only slot.
                    if (env.body is dev.ccpocket.protocol.VoiceMemoStart || env.body is dev.ccpocket.protocol.VoiceMemoAudio ||
                        env.body is dev.ccpocket.protocol.VoiceMemoGet || env.body is dev.ccpocket.protocol.VoiceMemoCancel
                    ) {
                        try {
                            router.handle(env.body, sink, caps = caps, deviceId = gatedDeviceId)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            log.warn("handle ${env.body::class.simpleName} failed: ${e::class.simpleName}")
                        }
                        continue
                    }
                    log.info("recv ${env.body::class.simpleName}")
                    launch {
                        try {
                            // owner control planes first (share #115 / bridge #91) — the same dispatcher the
                            // relay transport uses, so the two paths can't drift. Falls through to the router
                            // for everything else (and when the controls aren't up yet).
                            val (sc, bc) = ownerControls?.invoke() ?: (null to null)
                            if (dispatchOwnerControl(env.body, sc, bc) { sink.emit(it) }) return@launch
                            // gatedDeviceId = the LAN-gate-authenticated paired device (same identity space
                            // as the relay's)
                            router.handle(env.body, sink, caps = caps, deviceId = gatedDeviceId) { owned.add(it) }
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
            pins?.detach(sink)            // #362: same per-connection slot for pin pushes
            managed?.detach(sink)           // #360: …and for managed session list pushes
            caps.pinRetired = true          // …and a closed connection can never hold a pin subscription again
            caps.pinSubscriptionId = null
            outbox.close()
            writer.cancel()
            revokeWatch?.cancel()
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
