package dev.ccpocket.app.net

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame as WsFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A socket write stalled past [WRITE_TIMEOUT_MS] — the link is a zombie (network switch, NAT rebinding, or a
 * relay idle-drop the OS TCP stack hasn't surfaced yet). Thrown as a NORMAL exception (NOT a
 * CancellationException) so the repo's backoff reconnect fires instead of reading it as an intentional teardown
 * (see `PocketRepository.connectJob`, which returns early on CancellationException).
 */
class DeadLinkException : Exception("socket write stalled — dead link")

internal const val WRITE_TIMEOUT_MS = 10_000L   // a healthy send is instant; 10s stalled means the socket is wedged
internal const val PING_INTERVAL_MS = 15_000L   // WS-ping cadence: keep a write flowing so a dead idle link is caught
internal const val HANDSHAKE_TIMEOUT_MS = 15_000L // attach + Noise handshake cap — pre-heartbeat, nothing else guards it

/**
 * Run [block] (a socket write) under [WRITE_TIMEOUT_MS]; a timeout means the link is dead → [DeadLinkException].
 * This is what lets a network-switch zombie self-heal: on a wedged socket the write STALLS forever (the OS TCP
 * send buffer fills with no ACK and never errors), and ktor's own ping wedges the same way since it shares the
 * outgoing path. Bounding the write turns that silent hang into a throw that tears the connection down and
 * reconnects. Pair with a periodic WS-ping so an *idle* link still exercises a write and gets caught.
 */
internal suspend fun sendOrDie(block: suspend () -> Unit) {
    if (withTimeoutOrNull(WRITE_TIMEOUT_MS) { block() } == null) throw DeadLinkException()
}

/**
 * Launch an idle heartbeat on this socket: every [PING_INTERVAL_MS], send a WS ping under [sendOrDie]. On a
 * wedged link (network switch / NAT drop the OS TCP stack hasn't surfaced) that write stalls and trips
 * [DeadLinkException], tearing the connection down so the repo reconnects — even when nothing else is being sent.
 * Cancel it in the connect loop's `finally` alongside the writer. Shared by both connection classes.
 */
internal fun DefaultClientWebSocketSession.launchHeartbeat(): Job =
    launch { while (true) { delay(PING_INTERVAL_MS); sendOrDie { outgoing.send(WsFrame.Ping(ByteArray(0))) } } }
