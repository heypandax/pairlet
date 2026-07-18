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
import kotlinx.coroutines.runBlocking

/**
 * Local WebSocket server on `/v1/ws`. Two roles, same code:
 *  - `--local` mode: the only transport (plaintext, loopback dev use) — [e2e] null, [run] blocks.
 *  - relay mode's direct listener: runs ALONGSIDE the relay client so paired devices on this
 *    machine/LAN skip the relay — [e2e] gates every socket, [run]`(wait=false)` keeps main free.
 */
class DaemonServer(
    private val core: DaemonCore,
    private val host: String,
    private val port: Int,
    private val e2e: LanE2E? = null,
) {
    private val log = logger("DaemonServer")

    fun run(wait: Boolean = true) {
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
                        WsConnection(this, core.router, core.registry, e2e,
                            ownerControls = { core.shareControl to core.bridgeControl }).serve()
                    } finally {
                        log.info("WS disconnect from $peer")
                    }
                }
            }
        }
        log.info("listening on ws://$host:$port/v1/ws${if (e2e != null) " (E2E-gated, paired devices only)" else ""}")
        // owns process shutdown only when it IS the process (--local); in relay mode RelayClient's loop does
        if (wait) Runtime.getRuntime().addShutdownHook(Thread { runBlocking { core.shutdown() } })
        server.start(wait = wait)
    }
}
