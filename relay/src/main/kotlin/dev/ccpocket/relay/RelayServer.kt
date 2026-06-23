package dev.ccpocket.relay

import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.AuthError
import dev.ccpocket.protocol.DaemonAuth
import dev.ccpocket.protocol.DaemonHello
import dev.ccpocket.protocol.DevicePaired
import dev.ccpocket.protocol.DeviceHello
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.NotifyPush
import dev.ccpocket.protocol.PairBegin
import dev.ccpocket.protocol.PairCodePayload
import dev.ccpocket.protocol.PairCodeResolve
import dev.ccpocket.protocol.PairCredential
import dev.ccpocket.protocol.PairRedeem
import dev.ccpocket.protocol.PairTicket
import dev.ccpocket.protocol.PeerPresence
import dev.ccpocket.protocol.Ping
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.Pong
import dev.ccpocket.protocol.RegisterPush
import dev.ccpocket.protocol.RevokeDevice
import dev.ccpocket.protocol.Role
import dev.ccpocket.protocol.Route
import dev.ccpocket.protocol.e2e.Wire
import dev.ccpocket.relay.push.LoggingPushService
import dev.ccpocket.relay.push.NotifyRoute
import dev.ccpocket.relay.push.PushService
import dev.ccpocket.relay.auth.Codec
import dev.ccpocket.relay.auth.DaemonAuthenticator
import dev.ccpocket.relay.auth.DeviceAuthenticator
import dev.ccpocket.relay.net.RateLimiter
import dev.ccpocket.relay.net.clientIp
import dev.ccpocket.relay.pairing.CodeStore
import dev.ccpocket.relay.pairing.PairingService
import dev.ccpocket.relay.store.RelayStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import dev.ccpocket.protocol.Frame as PocketFrame

/**
 * The cloud broker. Daemons and devices connect outbound over WSS; the relay authenticates each
 * (daemon by Ed25519 signed challenge, device by bearer credential), pairs them by account, and
 * forwards the opaque end-to-end-encrypted BINARY data plane without ever decoding it. Control is a
 * small typed TEXT plane the relay terminates. Durable state lives in [store]; this class is wiring.
 */
class RelayServer(
    private val host: String,
    private val port: Int,
    private val store: RelayStore,
    private val pushService: PushService = LoggingPushService(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val broker = Broker()
    private val limiter = RateLimiter(clock)
    // off-loop fan-out: a slow APNs/FCM round-trip must not block the daemon socket's control loop
    private val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val daemonAuth = DaemonAuthenticator(store, clock)
    private val deviceAuth = DeviceAuthenticator(store, clock)
    private val pairing = PairingService(store, clock)
    private val codeStore = CodeStore(clock)

    fun run() {
        embeddedServer(CIO, host = host, port = port) {
            install(WebSockets) {
                maxFrameSize = MAX_FRAME
                pingPeriodMillis = 20_000
                timeoutMillis = 60_000
            }
            install(XForwardedHeaders) // honor Caddy's X-Forwarded-For (we bind loopback; only Caddy reaches us)

            launch {
                while (isActive) {
                    delay(60_000)
                    daemonAuth.sweep(); limiter.sweep(); codeStore.sweep(); runCatching { store.sweepExpired(clock()) }
                }
            }

            routing {
                get("/healthz") { call.respondText("ok") }

                post("/v1/pair/redeem") {
                    val ip = call.clientIp()
                    if (!limiter.check("redeem:ip:$ip", 5, 60_000, lockoutOnBreach = true)) {
                        call.respondError(HttpStatusCode.TooManyRequests, "rate_limited")
                        return@post
                    }
                    val req = runCatching { PocketJson.decodeFromString<PairRedeem>(call.receiveText()) }.getOrNull()
                    if (req == null) {
                        call.respondError(HttpStatusCode.BadRequest, "bad_request")
                        return@post
                    }
                    when (val r = pairing.redeem(req.ticket, req.devicePubKey)) {
                        is PairingService.RedeemResult.Err ->
                            call.respondError(HttpStatusCode.BadRequest, r.code)
                        is PairingService.RedeemResult.Ok -> {
                            // hint the daemon (advisory; it allow-lists only after the ticket-PSK handshake succeeds)
                            broker.controlToDaemon(r.accountId, controlText(DevicePaired(r.deviceId, r.devicePubKey)))
                            call.respondText(
                                PocketJson.encodeToString(PairCredential(r.deviceId, r.secret, r.accountId)),
                                ContentType.Application.Json,
                            )
                        }
                    }
                }

                post("/v1/pair/code") {
                    val ip = call.clientIp()
                    if (!limiter.check("paircode:ip:$ip", 10, 60_000, lockoutOnBreach = true)) {
                        call.respondError(HttpStatusCode.TooManyRequests, "rate_limited")
                        return@post
                    }
                    val req = runCatching { PocketJson.decodeFromString<PairCodeResolve>(call.receiveText()) }.getOrNull()
                    if (req == null) {
                        call.respondError(HttpStatusCode.BadRequest, "bad_request")
                        return@post
                    }
                    when (val payload = codeStore.take(req.code.trim())) {
                        null -> call.respondError(HttpStatusCode.BadRequest, "invalid_or_expired")
                        else -> call.respondText(PocketJson.encodeToString(payload), ContentType.Application.Json)
                    }
                }
                webSocket("/v1/daemon") { handleDaemon() }
                webSocket("/v1/device") { handleDevice() }
            }
        }.start(wait = true)
    }

    // ---- daemon socket: signed-challenge login, then control TEXT + opaque BINARY ----

    private suspend fun DefaultWebSocketServerSession.handleDaemon() {
        val ip = call.clientIp()
        if (!limiter.check("ws:ip:$ip", 10, 60_000)) return closeWith("rate_limited")

        val hello = receiveControl<DaemonHello>() ?: return closeWith("expected_hello")
        val challenge = daemonAuth.issueChallenge()
        sendControl(challenge)
        val auth = receiveControl<DaemonAuth>() ?: return closeWith("expected_auth")

        val account = when (val r = daemonAuth.verify(hello, auth, challenge.nonce)) {
            is DaemonAuthenticator.Result.Err -> {
                limiter.check("auth:ip:$ip", 5, 60_000, lockoutOnBreach = true)
                limiter.check("auth:acct:${hello.accountId}", 5, 60_000, lockoutOnBreach = true)
                sendControl(AuthError(r.code))
                return closeWith("auth_failed")
            }
            is DaemonAuthenticator.Result.Ok -> r.accountId
        }

        val conn = conn(account, Role.DAEMON, null)
        broker.attachDaemon(conn)?.let { old -> runCatching { old.close("superseded") } }
        sendControl(Attached(Role.DAEMON, account))
        // re-announce known devices so a daemon that missed a DevicePaired (e.g. offline at redeem) re-learns them
        store.devicesForAccount(account).forEach { d ->
            broker.controlToDaemon(account, controlText(DevicePaired(d.deviceId, Codec.b64uEnc(d.devicePubkey))))
        }
        broker.controlToDevices(account, controlText(PeerPresence(true)))
        try {
            for (frame in incoming) when (frame) {
                // daemon addresses a specific device: [deviceId][payload] -> route payload to it
                is Frame.Binary -> Wire.unwrapDevice(frame.data)?.let { (deviceId, payload) -> broker.toDevice(account, deviceId, payload) }
                is Frame.Text -> handleDaemonControl(account, frame.readText())
                else -> {}
            }
        } finally {
            broker.detachDaemon(conn)
            broker.controlToDevices(account, controlText(PeerPresence(false)))
        }
    }

    private suspend fun handleDaemonControl(account: String, text: String) {
        when (val body = runCatching { PocketJson.decodeFromString<Envelope>(text).body }.getOrNull()) {
            is PairBegin -> {
                if (!limiter.check("pairbegin:acct:$account", 10, 3_600_000)) {
                    broker.controlToDaemon(account, controlText(AuthError("rate_limited"))); return
                }
                when (val m = pairing.mint(account)) {
                    is PairingService.MintResult.Ok -> {
                        val code = codeStore.put(account, body.e2ePub, m.ticket) // 6-digit code resolves to this payload
                        broker.controlToDaemon(account, controlText(PairTicket(m.ticket, m.ttlSec, code)))
                    }
                    is PairingService.MintResult.Err -> broker.controlToDaemon(account, controlText(AuthError(m.code)))
                }
            }
            is RevokeDevice -> if (store.revokeDevice(account, body.deviceId)) broker.closeDevice(account, body.deviceId)
            is Ping -> broker.controlToDaemon(account, controlText(Pong(body.ts))) // app-level liveness echo
            // wake an offline phone: push only when no device socket is live (an online phone got TurnDone already)
            is NotifyPush -> if (broker.deviceCount(account) == 0) pushScope.launch {
                val route = body.workdir?.let { wd -> body.sessionId?.let { sid -> NotifyRoute(wd, sid) } }
                pushService.notify(account, body.title, body.body, route)
            }
            else -> {} // daemons send no other control
        }
    }

    /** device control TEXT plane: only push-token (de)registration; everything else rides the data plane. */
    private suspend fun handleDeviceControl(deviceId: String, text: String) {
        when (val body = runCatching { PocketJson.decodeFromString<Envelope>(text).body }.getOrNull()) {
            is RegisterPush -> runCatching { store.setPushToken(deviceId, body.platform, body.token, clock()) }
            else -> {}
        }
    }

    // ---- device socket: bearer-credential login, then opaque BINARY ----

    private suspend fun DefaultWebSocketServerSession.handleDevice() {
        val ip = call.clientIp()
        if (!limiter.check("ws:ip:$ip", 10, 60_000)) return closeWith("rate_limited")

        val hello = receiveControl<DeviceHello>() ?: return closeWith("expected_hello")
        val account = when (val r = deviceAuth.verify(hello)) {
            is DeviceAuthenticator.Result.Err -> {
                limiter.check("auth:ip:$ip", 5, 60_000, lockoutOnBreach = true)
                sendControl(AuthError(r.code))
                return closeWith("auth_failed")
            }
            is DeviceAuthenticator.Result.Ok -> r.accountId
        }
        if (broker.deviceCount(account) >= MAX_LIVE_DEVICES) {
            sendControl(AuthError("too_many_connections")); return closeWith("too_many_connections")
        }

        val conn = conn(account, Role.DEVICE, hello.deviceId)
        broker.attachDevice(conn)
        sendControl(Attached(Role.DEVICE, account))
        broker.controlToDaemon(account, controlText(PeerPresence(true)))
        try {
            for (frame in incoming) when (frame) {
                is Frame.Binary -> broker.toDaemonFrom(account, hello.deviceId, frame.data)
                is Frame.Text -> handleDeviceControl(hello.deviceId, frame.readText())
                else -> {}
            }
        } finally {
            broker.detachDevice(conn)
            broker.controlToDaemon(account, controlText(PeerPresence(false)))
        }
    }

    // ---- control-frame codec (TEXT plane) + helpers ----

    private fun DefaultWebSocketServerSession.conn(account: String, role: Role, deviceId: String?) = Conn(
        account, role, deviceId,
        sendText = { outgoing.send(Frame.Text(it)) },
        sendBinary = { outgoing.send(Frame.Binary(true, it)) },
        close = { reason -> runCatching { close(CloseReason(CloseReason.Codes.NORMAL, reason)) } },
    )

    private fun controlText(frame: PocketFrame): String =
        PocketJson.encodeToString(Envelope(id = "r", ts = clock(), to = Route.RELAY, body = frame))

    private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String) =
        respondText("""{"error":"$code"}""", ContentType.Application.Json, status)

    private suspend fun DefaultWebSocketServerSession.sendControl(frame: PocketFrame) =
        outgoing.send(Frame.Text(controlText(frame)))

    private suspend inline fun <reified T> DefaultWebSocketServerSession.receiveControl(): T? {
        val frame = runCatching { incoming.receive() }.getOrNull() as? Frame.Text ?: return null
        return runCatching { PocketJson.decodeFromString<Envelope>(frame.readText()).body }.getOrNull() as? T
    }

    private suspend fun DefaultWebSocketServerSession.closeWith(reason: String) =
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason))

    private companion object {
        const val MAX_FRAME = 256L * 1024
        const val MAX_LIVE_DEVICES = 10
    }
}
