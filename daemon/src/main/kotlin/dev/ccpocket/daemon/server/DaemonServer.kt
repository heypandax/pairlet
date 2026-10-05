package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.util.logger
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket

/**
 * The E2E direct listener on `/v1/ws`: runs ALONGSIDE the relay client so paired devices on this
 * machine/LAN skip the relay. [gate] is required and authenticates every socket — there is no way to
 * build an unauthenticated listener. [start] returns immediately; the relay client owns the main thread.
 */
class DaemonServer(
    private val core: DaemonCore,
    private val host: String,
    private val port: Int,
    private val gate: LanE2E,
) {
    private val log = logger("DaemonServer")

    fun start() {
        val server = embeddedServer(CIO, host = host, port = port) {
            install(WebSockets) {
                // detect zombie phone sockets (screen-locked / walked-out-of-range): without a transport
                // ping the dead TCP stays ESTABLISHED for minutes and its WsConnection writer wedges
                pingPeriodMillis = 15_000
                timeoutMillis = 30_000
                maxFrameSize = 4L * 1024 * 1024 // big history replays travel this path too (matches relay cap)
            }
            routing {
                webSocket("/v1/ws") {
                    val peer = runCatching { call.request.origin.remoteHost }.getOrDefault("?")
                    log.info("WS connect from $peer")
                    try {
                        WsConnection(this, core.router, core.registry, gate,
                            ownerControls = { Triple(core.shareControl, core.bridgeControl, core.collaboratorControl) }).serve()
                    } finally {
                        log.info("WS disconnect from $peer")
                    }
                }
            }
        }
        log.info("listening on ws://$host:$port/v1/ws (E2E-gated, paired devices only)")
        server.start(wait = false)
    }
}
