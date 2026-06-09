package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.PocketJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** The daemon's loopback /pair response — produced here, consumed only by the `pair` CLI in this module. */
@Serializable
data class LoopbackPair(
    val accountId: String,
    val daemonPub: String,
    val ticket: String,
    val code: String,
    val ttlSec: Int,
    val relay: String,
)

/**
 * A loopback-only helper so the `pair` CLI can ask the ALREADY-RUNNING daemon to mint a pairing
 * ticket over its single authenticated relay connection — instead of opening a second daemon
 * connection (which would supersede the live one). Binds 127.0.0.1 only; never exposed off-host.
 */
class PairLoopback(
    private val relay: RelayClient,
    private val relayWsBase: String,
    private val daemonPubB64: String,
    private val port: Int,
) {
    private val log = logger("PairLoopback")

    fun start() {
        embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                post("/pair") {
                    val ticket = relay.mintTicket()
                    if (ticket == null) {
                        call.respondText("""{"error":"relay_offline"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                    } else {
                        val info = LoopbackPair(relay.accountId, daemonPubB64, ticket.ticket, ticket.code, ticket.expiresInSec, relayWsBase)
                        call.respondText(PocketJson.encodeToString(info), ContentType.Application.Json)
                    }
                }
            }
        }.start(wait = false)
        log.info("pair loopback on http://127.0.0.1:$port (POST /pair)")
    }
}
