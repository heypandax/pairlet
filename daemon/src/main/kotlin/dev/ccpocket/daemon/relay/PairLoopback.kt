package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.PocketJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
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

/** The daemon's loopback /status response — consumed by the `status` CLI (and shown when `pair` fails). */
@Serializable
data class LoopbackStatus(
    val accountId: String,
    val relay: String,
    val attached: Boolean,
    val lastPongAgeMs: Long?,
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
                        // carry the link state so the CLI can say WHY instead of a bare relay_offline:
                        // attached=false → still (re)connecting (backoff reaches 30s, the mint window is 10s);
                        // attached=true with a stale pong → a wedged link the watchdog is about to recycle
                        val age = relay.lastPongAgeMs()
                        call.respondText(
                            """{"error":"relay_offline","attached":${relay.attached},"lastPongAgeMs":${age ?: "null"}}""",
                            ContentType.Application.Json, HttpStatusCode.ServiceUnavailable,
                        )
                    } else {
                        val info = LoopbackPair(relay.accountId, daemonPubB64, ticket.ticket, ticket.code, ticket.expiresInSec, relayWsBase)
                        call.respondText(PocketJson.encodeToString(info), ContentType.Application.Json)
                    }
                }
                get("/status") {
                    call.respondText(
                        PocketJson.encodeToString(LoopbackStatus(relay.accountId, relayWsBase, relay.attached, relay.lastPongAgeMs())),
                        ContentType.Application.Json,
                    )
                }
            }
        }.start(wait = false)
        log.info("pair loopback on http://127.0.0.1:$port (POST /pair, GET /status)")
    }
}
