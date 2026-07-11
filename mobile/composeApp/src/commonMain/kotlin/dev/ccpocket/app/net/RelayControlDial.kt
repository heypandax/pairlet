package dev.ccpocket.app.net

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.AuthError
import dev.ccpocket.protocol.DeviceHello
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.Route
import dev.ccpocket.protocol.ToRelay
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import io.ktor.websocket.Frame as WsFrame

/**
 * A one-shot dial of the relay's device control plane, for when the MAIN transport is the direct LAN
 * link. The direct link has no relay control plane, and a phone that always finds its daemon on the
 * LAN never relay-attaches — so "register on the next relay attach" never comes and its push token
 * would rot server-side forever (#114 follow-up). This connects just long enough to deposit a control
 * frame: DeviceHello -> Attached -> frame -> close. Deliberately NO Noise handshake — control frames
 * (e.g. RegisterPush) ride the cleartext TEXT plane, which the relay serves right after the bearer-
 * credential auth; the relay is the frame's terminus, no daemon involvement, so the already-deployed
 * relay accepts this dial as-is.
 */
object RelayControlDial {
    private val client = HttpClient { install(WebSockets) }

    /** Connect, deposit [frame], close. Throws on auth rejection ([RelayAuthException]), transport
     *  failure, or timeout ([DeadLinkException] — NOT a CancellationException, so the caller's
     *  "cancelled = superseded" logic can't misread a wedged dial as a supersession). */
    suspend fun deposit(paired: PairedDaemon, frame: ToRelay) {
        try {
            withTimeout(DIAL_TIMEOUT_MS) {
                client.webSocket(urlString = "${paired.relay}/v1/device") {
                    outgoing.send(WsFrame.Text(control(DeviceHello(paired.deviceId, paired.credential))))
                    while (true) { // await the relay's auth verdict
                        val f = incoming.receive() as? WsFrame.Text ?: continue
                        when (val b = runCatching { PocketJson.decodeFromString<Envelope>(f.readText()).body }.getOrNull()) {
                            is Attached -> break
                            is AuthError -> throw RelayAuthException(b.code)
                            else -> {}
                        }
                    }
                    outgoing.send(WsFrame.Text(control(frame)))
                    // graceful close AFTER the frame — WS ordering means the relay reads the frame first
                    close(CloseReason(CloseReason.Codes.NORMAL, "control deposit"))
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw DeadLinkException()
        }
    }

    private fun control(frame: ToRelay): String =
        PocketJson.encodeToString(Envelope("h", 0L, to = Route.RELAY, body = frame))

    private const val DIAL_TIMEOUT_MS = 15_000L
}
