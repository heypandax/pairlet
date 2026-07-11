package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.PocketJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

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

/** CLI -> daemon (loopback): mint a HEADLESS bridge credential (issue #91). */
@Serializable
data class LoopbackHeadlessReq(
    val name: String,
    val workdirs: List<String>,
    val maxSessions: Int? = null,
    val opensPerMin: Int? = null,
    val promptsPerMin: Int? = null,
)

/** daemon -> CLI: everything a bridge adapter needs to redeem + connect. The CLI prints it as one
 *  JSON blob the user hands to the adapter; the ticket is single-use and expires in [ttlSec]. */
@Serializable
data class LoopbackHeadlessCred(
    val kind: String = "cc-pocket-bridge-credential",
    val name: String,
    val accountId: String,
    val daemonPub: String,
    val ticket: String,
    val ttlSec: Int,
    val relay: String,
    val workdirs: List<String>,
)

/** daemon -> CLI: one row of the `bridges` listing. */
@Serializable
data class LoopbackBridge(
    val deviceId: String,
    val name: String,
    val workdirs: List<String>,
    val maxSessions: Int,
    val opensPerMin: Int,
    val promptsPerMin: Int,
)

/** CLI -> daemon (loopback): revoke a bridge by deviceId or by its unique name. */
@Serializable
data class LoopbackRevokeReq(val idOrName: String)

/**
 * A loopback-only helper so the `pair` CLI can ask the ALREADY-RUNNING daemon to mint a pairing
 * ticket over its single authenticated relay connection — instead of opening a second daemon
 * connection (which would supersede the live one). Binds 127.0.0.1 only; never exposed off-host.
 * Also serves the headless-bridge management surface (issue #91): mint / list / revoke. Loopback
 * reachability == local-user authority, the same trust the interactive `pair` already rides.
 */
class PairLoopback(
    private val relay: RelayClient,
    private val relayWsBase: String,
    private val daemonPubB64: String,
    private val port: Int,
) {
    private val log = logger("PairLoopback")

    private companion object {
        // grace beyond the 120s ticket TTL for the daemon's headless intent to stay bindable — covers
        // redeem→connect→first-frame latency + modest clock skew so a bridge is never mis-classified as
        // a full-power device (issue #91). An abandoned headless mint blocks re-mint for at most this long.
        const val INTENT_GRACE_MS = 120_000L
    }

    fun start() {
        embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                post("/pair") {
                    // mint serialization (issue #91): while a headless pairing is pending, an interactive
                    // mint could LIFO-cross the PSK binding — refuse for the ticket's short TTL instead
                    if (relay.bridges.intentPending()) {
                        call.respondText(
                            """{"error":"headless_pairing_pending","message":"a bridge pairing is in progress — retry in ~2 minutes"}""",
                            ContentType.Application.Json, HttpStatusCode.Conflict,
                        )
                        return@post
                    }
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

                // ---- headless bridge management (issue #91) ----

                post("/pair/headless") {
                    val req = runCatching { PocketJson.decodeFromString<LoopbackHeadlessReq>(call.receiveText()) }.getOrNull()
                    if (req == null || req.name.isBlank() || req.workdirs.isEmpty()) {
                        call.respondText("""{"error":"bad_request","message":"name and at least one --workdir are required"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    if (relay.bridges.list().any { it.second.name == req.name }) {
                        call.respondText("""{"error":"name_taken","message":"a bridge named \"${req.name}\" already exists — revoke it first or pick another name"}""", ContentType.Application.Json, HttpStatusCode.Conflict)
                        return@post
                    }
                    // every allow-listed root must EXIST as a directory — a typo'd root would mint a
                    // credential that can open sessions somewhere unintended once the path appears later
                    val roots = req.workdirs.map { File(it) }
                    val bad = roots.firstOrNull { !it.isAbsolute || !it.isDirectory }
                    if (bad != null) {
                        call.respondText("""{"error":"bad_workdir","message":"not an existing absolute directory: $bad"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    // the reverse half of mint serialization: a phone ticket may still be outstanding
                    if (relay.interactivePairingPending()) {
                        call.respondText("""{"error":"interactive_pairing_pending","message":"a phone pairing ticket is still valid — retry in ~2 minutes"}""", ContentType.Application.Json, HttpStatusCode.Conflict)
                        return@post
                    }
                    val spec = BridgeSpec.clamped(
                        req.name.trim(),
                        roots.map { runCatching { it.canonicalFile.path }.getOrDefault(it.path) },
                        req.maxSessions, req.opensPerMin, req.promptsPerMin,
                    )
                    val ticket = relay.mintTicket(headless = true)
                    if (ticket == null) {
                        call.respondText("""{"error":"relay_offline","attached":${relay.attached}}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                        return@post
                    }
                    // bindable window = ticket TTL + a grace margin (issue #91 LOW): the daemon records the
                    // intent AFTER the mint round-trip, so it already outlives the relay ticket slightly;
                    // the grace makes classification robust to pairing latency near the ticket-expiry edge
                    // and modest clock skew, so a slow-to-first-frame bridge is never mis-promoted to a
                    // full-power device (onDevicePaired then reliably sees looksHeadless == true).
                    if (!relay.bridges.recordIntent(ticket.ticket, spec, ttlMs = ticket.expiresInSec * 1000L + INTENT_GRACE_MS)) {
                        call.respondText("""{"error":"headless_pairing_pending","message":"another bridge pairing is already in progress"}""", ContentType.Application.Json, HttpStatusCode.Conflict)
                        return@post
                    }
                    log.info("headless credential minted for \"${spec.name}\" (workdirs=${spec.workdirs})")
                    call.respondText(
                        PocketJson.encodeToString(
                            LoopbackHeadlessCred(
                                name = spec.name, accountId = relay.accountId, daemonPub = daemonPubB64,
                                ticket = ticket.ticket, ttlSec = ticket.expiresInSec, relay = relayWsBase,
                                workdirs = spec.workdirs,
                            ),
                        ),
                        ContentType.Application.Json,
                    )
                }

                get("/bridges") {
                    val rows = relay.bridges.list().map { (id, spec) ->
                        LoopbackBridge(id, spec.name, spec.workdirs, spec.maxSessions, spec.opensPerMin, spec.promptsPerMin)
                    }
                    call.respondText(PocketJson.encodeToString(rows), ContentType.Application.Json)
                }

                post("/bridge/revoke") {
                    val req = runCatching { PocketJson.decodeFromString<LoopbackRevokeReq>(call.receiveText()) }.getOrNull()
                    if (req == null || req.idOrName.isBlank()) {
                        call.respondText("""{"error":"bad_request"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    val match = relay.bridges.list().filter { (id, spec) -> id == req.idOrName || spec.name == req.idOrName }
                    when {
                        match.isEmpty() -> call.respondText("""{"error":"not_found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                        match.size > 1 -> call.respondText("""{"error":"ambiguous","message":"multiple bridges match — use the deviceId"}""", ContentType.Application.Json, HttpStatusCode.Conflict)
                        else -> {
                            val (id, spec) = match.single()
                            relay.revokeBridge(id) // local prune is immediate; relay revoke is best-effort
                            log.info("bridge \"${spec.name}\" (${id.take(8)}…) revoked via loopback")
                            call.respondText("""{"revoked":"${spec.name}","deviceId":"$id"}""", ContentType.Application.Json)
                        }
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
        log.info("pair loopback on http://127.0.0.1:$port (POST /pair, POST /pair/headless, GET /bridges, POST /bridge/revoke, GET /status)")
    }
}
