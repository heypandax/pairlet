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

/** M0 local WebSocket server on 127.0.0.1:8765 (no relay). */
class DaemonServer(
    private val core: DaemonCore,
    private val host: String,
    private val port: Int,
) {
    private val log = logger("DaemonServer")

    fun run() {
        val server = embeddedServer(CIO, host = host, port = port) {
            install(WebSockets)
            routing {
                webSocket("/v1/ws") {
                    val peer = runCatching { call.request.origin.remoteHost }.getOrDefault("?")
                    log.info("WS connect from $peer")
                    try {
                        WsConnection(this, core.router, core.registry).serve()
                    } finally {
                        log.info("WS disconnect from $peer")
                    }
                }
            }
        }
        log.info("listening on ws://$host:$port/v1/ws")
        Runtime.getRuntime().addShutdownHook(Thread { runBlocking { core.shutdown() } })
        server.start(wait = true)
    }
}
