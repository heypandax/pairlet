package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.conversation.AskPushHook
import dev.ccpocket.daemon.conversation.PushHook
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.PROTO_V_HEADLESS
import dev.ccpocket.protocol.AuthError
import dev.ccpocket.protocol.Challenge
import dev.ccpocket.protocol.DaemonAuth
import dev.ccpocket.protocol.DaemonHello
import dev.ccpocket.protocol.DevicePaired
import dev.ccpocket.protocol.DeviceRevoked
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.NotifyPush
import dev.ccpocket.protocol.PairBegin
import dev.ccpocket.protocol.PairTicket
import dev.ccpocket.protocol.PeerPresence
import dev.ccpocket.protocol.Ping
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.Pong
import dev.ccpocket.protocol.Route
import dev.ccpocket.protocol.ToRelay
import dev.ccpocket.protocol.e2e.Wire
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.readText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import io.ktor.websocket.Frame as WsFrame

/**
 * The daemon's outbound connection to the cloud relay. Authenticates by signing the relay's challenge
 * with its Ed25519 static key, then runs end-to-end-encrypted [DeviceSessions] over the opaque BINARY
 * data plane (the relay only routes ciphertext) and a small TEXT control plane for pairing.
 * Reconnects with exponential backoff.
 */
class RelayClient(
    private val relayWsBase: String, // e.g. ws://127.0.0.1:9000
    private val identity: Identity,
    private val core: DaemonCore,
    private val lanUrl: () -> String? = { null }, // direct-listener address advertised to devices (DaemonInfo)
    private val hostname: () -> String? = { null }, // OS computer name advertised to devices (DaemonInfo; lazy — first use may resolve DNS)
) {
    private val log = logger("RelayClient")

    // A FRESH client (and CIO selector) per connection attempt: a selector that lived through an interface
    // change (VPN/TUN flip, network switch) can keep dialing a dead path forever. Rebuilding is cheap at
    // reconnect cadence and guarantees every attempt starts from the current network state.
    private fun newClient() = HttpClient(CIO) {
        install(WebSockets) {
            // keepalive ping well under Cloudflare's ~100s idle WS timeout, so the relay link stays up
            pingIntervalMillis = 20_000
            maxFrameSize = 4L * 1024 * 1024 // accept big frames forwarded from the phone (matches relay cap)
        }
    }

    private val controlOutbox = Channel<ToRelay>(Channel.BUFFERED)
    private val inboundControl = MutableSharedFlow<ToRelay>(extraBufferCapacity = 32)
    private val ctrlId = AtomicLong(0)

    @Volatile private var dataOut: Channel<ByteArray>? = null
    @Volatile private var peerOnline = false
    @Volatile private var lastPongAt = 0L  // last app-level Pong from the relay (heartbeat liveness; baselined at attach)
    @Volatile private var sawPong = false  // logging only: notes when this relay first proves it speaks Pong
    private val sessions = DeviceSessions(core, identity, lanUrl = lanUrl, hostname = hostname) { deviceId, payload ->
        dataOut?.send(Wire.wrapDevice(deviceId, payload))
    }

    val accountId: String get() = identity.accountId

    /** Loopback diagnostics (`pair`/`status`): is a relay session currently attached… */
    val attached: Boolean get() = dataOut != null

    /** The headless-bridge authority (issue #91) — PairLoopback serves list/revoke/mint from it. */
    val bridges: dev.ccpocket.daemon.bridge.BridgeRegistry get() = sessions.bridges

    /** True while an interactive pairing ticket could still be redeemed (headless mint must wait). */
    fun interactivePairingPending(): Boolean = sessions.interactivePairingPending()

    /** Revoke a bridge credential: prune locally NOW (the security anchor — its handshake key dies with
     *  the bridges.json entry) and best-effort tell the relay so its row is revoked + socket closed. */
    suspend fun revokeBridge(deviceId: String) {
        sessions.onDeviceRevoked(deviceId)
        controlOutbox.send(dev.ccpocket.protocol.RevokeDevice(deviceId))
    }

    /** Exposes the pairing-ceremony gate to the direct-LAN listener (see DeviceSessions.firstContactPending). */
    suspend fun deviceFirstContactPending(deviceId: String): Boolean = sessions.firstContactPending(deviceId)

    /** …and how stale is its liveness signal (ms since the last Pong, or since attach before the first one). */
    fun lastPongAgeMs(): Long? = lastPongAt.takeIf { it != 0L }?.let { System.currentTimeMillis() - it }

    suspend fun run() = coroutineScope {
        // wake an offline phone when a turn completes. peerOnline gates the relay-attached case and
        // lanConnected() the direct-LAN one (an attached phone — either transport — got the TurnDone over
        // the data plane already). The LAN check matters once LAN-resident phones carry LIVE relay tokens
        // (the #114 follow-up dial): the relay re-checks deviceCount before actually pushing, but it can't
        // see LAN attachment — without this gate every turn watched over the LAN doubles as a lock-screen
        // push. Mirrors the reaper's gate below.
        core.registry.pushHook = PushHook { workdir, sessionId, finalText ->
            if (!peerOnline && !core.registry.lanConnected() && core.prefs.pushEnabled) controlOutbox.send(
                NotifyPush(
                    title = workdir.fileName?.toString() ?: "CC Pocket",
                    body = finalText?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(140) ?: "Turn complete",
                    workdir = workdir.toString(),
                    sessionId = sessionId,
                ),
            )
        }
        // issue #91: a BRIDGE conversation's permission ask can't reach the bridge (egress whitelist) —
        // push the owner instead. Deliberately NOT gated on peerOnline: the phone being online does not
        // mean it is attached to the bridge's conversation (unlike TurnDone, which an attached client
        // already received on the data plane). The relay still suppresses the push while any interactive
        // device socket is live, so an in-app owner isn't double-alerted.
        core.registry.askPushHook = AskPushHook { workdir, sessionId, origin, tool ->
            if (core.prefs.pushEnabled) controlOutbox.send(
                NotifyPush(
                    title = "Approval needed — $origin",
                    body = "${workdir.fileName ?: "session"}: $tool is waiting for your decision",
                    workdir = workdir.toString(),
                    sessionId = sessionId,
                    urgent = true, // deliver even if a phone is attached elsewhere — the ask isn't on its data plane
                ),
            )
        }
        // issue #115: the OWNER folder-share control plane. Installed on the relay path (minting needs the
        // relay link; the LAN path can't mint). DeviceSessions dispatches CreateShare/ListShares/RevokeShare
        // to it for a full-power owner device only.
        sessions.shareControl = ShareService(
            accountId = identity.accountId,
            daemonPubB64 = identity.e2ePubB64,
            relayWsBase = relayWsBase,
            ownerLabel = hostname,
            registry = sessions.bridges,
            mintTicket = { headless -> mintTicket(headless) },
            interactivePairingPending = { sessions.interactivePairingPending() },
            revokeCredential = { deviceId -> revokeBridge(deviceId) },
            liveSessions = { core.registry.liveByCwd().entries.flatMap { (cwd, list) -> list.map { cwd to it } } },
        )
        launch { reaperLoop() } // reclaim sessions abandoned while the phone is offline
        launch { guestExpiryLoop() } // cut + purge folder shares the instant they expire (issue #115 §6)
        var backoff = 1_000L
        while (true) {
            try {
                connectOnce()
                backoff = 1_000L
            } catch (t: Throwable) {
                log.warn("relay connection lost (${t.message}); retry in ${backoff}ms")
            }
            val jittered = backoff / 2 + Random.nextLong(backoff / 2 + 1) // equal jitter: 50–100% of backoff, decorrelates herd reconnects
            delay(jittered)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    /**
     * While no phone is attached, reclaim conversations idle longer than [IDLE_REAP_MS]. Reaping stops the
     * claude process and unhides its (possibly forked) transcript, so a session the phone is done with
     * surfaces in the desktop `claude --resume` picker promptly instead of staying hidden behind a warm
     * process. Gated on `!peerOnline` AND no live LAN socket: an actively-attached phone — via relay or
     * LAN — is never reaped, so its live session stays warm and its id stable (only sessions whose phone
     * has already left are handed back).
     */
    private suspend fun reaperLoop() {
        while (true) {
            delay(REAP_SCAN_MS)
            if (!peerOnline && !core.registry.lanConnected()) {
                val n = core.registry.reapIdle(IDLE_REAP_MS)
                if (n > 0) log.info("reaped $n idle session(s) — transcripts unhidden for desktop resume")
            }
        }
    }

    /** Cut + purge expired folder shares (issue #115 §6). Runs regardless of phone presence — an expired
     *  guest must drop immediately, whether or not the owner is online. [revokeBridge] prunes the guest key
     *  locally NOW (its handshake dies) and best-effort tells the relay to force-close its socket, so the
     *  guest is severed and its credential can't be reused. */
    private suspend fun guestExpiryLoop() {
        while (true) {
            delay(GUEST_EXPIRY_SCAN_MS)
            for (id in sessions.bridges.expiredGuestIds()) {
                log.info("folder share ${id.take(8)}… expired — revoking")
                runCatching { revokeBridge(id) }
            }
        }
    }

    /** Queue a plain notification for the phone (e.g. "new daemon version"). The relay only actually
     *  pushes when no device is attached — an attached user doesn't need APNs to hear from us. */
    suspend fun notifyPhone(title: String, body: String) {
        controlOutbox.send(NotifyPush(title = title, body = body, workdir = "", sessionId = null))
    }

    /** Ask the relay to mint a pairing ticket, and remember it as the next device's handshake PSK.
     *  [headless] mints a BRIDGE ticket (issue #91): it skips the interactive-mint exclusion stamp so
     *  only real phone pairings block a headless mint — the caller records the intent right after. */
    suspend fun mintTicket(headless: Boolean = false): PairTicket? {
        // relay needs our E2E pub to serve the code path; headless is the authoritative bridge marker the
        // relay stamps onto the ticket (issue #91) so a lying redeem can't dodge presence/push/replay
        controlOutbox.send(PairBegin(identity.e2ePubB64, headless = headless))
        return withTimeoutOrNull(10_000) { inboundControl.filterIsInstance<PairTicket>().first() }
            ?.also { sessions.onMintedTicket(it.ticket, headless) }
    }

    private suspend fun connectOnce() {
        newClient().use { client ->
            client.webSocket(urlString = "$relayWsBase/v1/daemon") {
                // Bound the pre-attach handshake: the socket can connect yet the relay/network stay silent (the
                // half-open path a heartbeat-forced reconnect lands back on), and authenticate() would otherwise
                // block on incoming.receive() forever — before the heartbeat below is even armed — wedging the
                // daemon until a manual restart. A timeout throws instead, so run()'s loop backs off and retries.
                if (withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { authenticate() } == null) error("relay handshake timed out")
                log.info("attached to relay as daemon (account=${identity.accountId})")
                // the relay re-announces every non-revoked device right after attach; once that replay
                // settles, prune local keys it didn't include (devices revoked while we were away) so the
                // direct-LAN gate stops honoring them. Cancelled harmlessly if the link drops first.
                sessions.beginAttachReplay()
                val reconcile = launch { delay(ATTACH_REPLAY_SETTLE_MS); sessions.reconcileReplay() }

                val outbox = Channel<ByteArray>(Channel.BUFFERED)
                dataOut = outbox
                // Every steady-state write is bounded (sendOrDie): on a wedged socket a write never errors —
                // the TCP send buffer just fills silently (network switch, NAT rebind) — and it would otherwise
                // hang this writer, back up the outbox, and wedge the pumps feeding it. Same pattern as the
                // phone's LinkHealth. The throw hard-cancels the whole session; run()'s loop reconnects.
                val dataWriter = launch { for (bytes in outbox) sendOrDie { outgoing.send(WsFrame.Binary(true, bytes)) } }
                val ctrlWriter = launch { for (c in controlOutbox) sendOrDie { outgoing.send(WsFrame.Text(controlText(c))) } }
                // App-level heartbeat: a half-open/zombie link keeps the TCP socket ESTABLISHED and Ktor's
                // transport ping satisfied, yet no Pong returns through the dead relay app. The deadline runs
                // from ATTACH, not from the first Pong — a link that wedges before ever ponging must die too
                // (the old sawPong gate left that window undetectable). And it THROWS instead of close():
                // a graceful close writes a close frame down the same wedged path and can itself hang for
                // minutes (probed on ktor 3.1.3); a thrown exception tears the session down without writing.
                sawPong = false; lastPongAt = System.currentTimeMillis()
                val heartbeat = launch {
                    while (true) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        controlOutbox.send(Ping(System.currentTimeMillis()))
                        if (System.currentTimeMillis() - lastPongAt > HEARTBEAT_DEAD_MS) {
                            log.warn("relay heartbeat timeout (no pong in ${HEARTBEAT_DEAD_MS}ms${if (!sawPong) ", none since attach — relay predates Pong?" else ""}); forcing reconnect")
                            throw DeadLinkException("no relay pong in ${HEARTBEAT_DEAD_MS}ms")
                        }
                    }
                }
                try {
                    for (frame in incoming) when (frame) {
                        is WsFrame.Binary -> Wire.unwrapDevice(frame.data)?.let { (deviceId, payload) ->
                            sessions.onFrame(deviceId, payload) // sequential: preserves per-device frame order
                        }
                        is WsFrame.Text -> onControl(runCatching { PocketJson.decodeFromString<Envelope>(frame.readText()).body }.getOrNull())
                        else -> {}
                    }
                } finally {
                    dataOut = null
                    outbox.close(); dataWriter.cancel(); ctrlWriter.cancel(); heartbeat.cancel(); reconcile.cancel()
                    withContext(NonCancellable) { sessions.onDisconnect() }
                }
            }
        }
    }

    /** Run [block] (a socket write) under [WRITE_TIMEOUT_MS]; a stall means the link is a zombie → throw. */
    private suspend fun sendOrDie(block: suspend () -> Unit) {
        if (withTimeoutOrNull(WRITE_TIMEOUT_MS) { block() } == null) throw DeadLinkException("socket write stalled")
    }

    /** A dead relay link, detected by a stalled write or a missed Pong deadline. A plain Exception (not a
     *  CancellationException) so it fails the session scope and surfaces to run()'s retry loop. */
    private class DeadLinkException(msg: String) : Exception(msg)

    /** DaemonHello -> Challenge -> DaemonAuth -> Attached. Throws on rejection (caller retries). */
    private suspend fun DefaultClientWebSocketSession.authenticate() {
        // protoV = PROTO_V_HEADLESS: tells the relay we understand headless bridge devices, so it may
        // replay their DevicePaired rows to us (it withholds them from older daemons — issue #91)
        outgoing.send(WsFrame.Text(controlText(DaemonHello(identity.accountId, identity.ed25519PubB64, protoV = PROTO_V_HEADLESS))))
        val challenge = nextControl() as? Challenge ?: error("expected challenge")
        outgoing.send(WsFrame.Text(controlText(DaemonAuth(identity.signChallenge(challenge.nonce)))))
        when (val r = nextControl()) {
            is Attached -> {}
            is AuthError -> error("relay rejected auth: ${r.code}")
            else -> error("expected attached, got ${r?.let { it::class.simpleName }}")
        }
    }

    private suspend fun DefaultClientWebSocketSession.nextControl(): ToRelay? {
        val frame = incoming.receive() as? WsFrame.Text ?: return null
        return runCatching { PocketJson.decodeFromString<Envelope>(frame.readText()).body }.getOrNull() as? ToRelay
    }

    private suspend fun onControl(body: Any?) {
        when (body) {
            is PairTicket -> inboundControl.emit(body)
            is DevicePaired -> sessions.onDevicePaired(body.deviceId, body.devicePubKey)
            is DeviceRevoked -> sessions.onDeviceRevoked(body.deviceId)
            is PeerPresence -> { peerOnline = body.online; log.info("peer ${if (body.online) "online" else "offline"}") }
            is Pong -> { if (!sawPong) log.info("relay heartbeat armed (pong received)"); sawPong = true; lastPongAt = System.currentTimeMillis() }
            else -> {}
        }
    }

    private fun controlText(frame: ToRelay): String =
        PocketJson.encodeToString(Envelope(ctrlId.getAndIncrement().toString(), 0L, to = Route.RELAY, body = frame))

    private companion object {
        // Once the phone detaches, hand an idle (no background work) session back to the desktop quickly:
        // stop + unhide so it surfaces in `claude --resume`. Short enough to feel prompt, long enough to
        // ride out brief app-backgrounding / network blips — a reaped session re-opened later resumes in
        // place on the same id (see Conversation.open), so too-short here mostly costs process churn.
        const val IDLE_REAP_MS = 90 * 1000L       // 90s idle (phone offline) -> reclaim + unhide
        const val GUEST_EXPIRY_SCAN_MS = 30 * 1000L // how often to sweep for expired folder shares (issue #115)
        const val ATTACH_REPLAY_SETTLE_MS = 3_000L // relay device re-announce rides the attach; settled well within this
        const val REAP_SCAN_MS = 20 * 1000L       // reaper wake cadence while the phone is offline
        const val HEARTBEAT_INTERVAL_MS = 20_000L // app-level Ping cadence (relay echoes Pong)
        const val HEARTBEAT_DEAD_MS = 45_000L     // no Pong within this of attach/the last one -> reconnect
        const val HANDSHAKE_TIMEOUT_MS = 15_000L  // pre-attach (connect→auth→attached) cap, else assume a wedged link
        const val WRITE_TIMEOUT_MS = 10_000L      // a healthy socket write is instant; stalled this long = zombie link
    }
}
