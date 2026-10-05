package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.control.LOCAL_CONTROL_PREFIX
import dev.ccpocket.daemon.control.LocalControlToken
import dev.ccpocket.daemon.control.executionControlDepsOf
import dev.ccpocket.daemon.control.installExecutionControl
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.CreateBridge
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
    /** granted permission-mode ceiling; absent (an older CLI) → the strictest, same as the flag's default */
    val tier: AccessTier = AccessTier.REVIEW,
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

/** daemon -> CLI (loopback): why a headless mint was refused, in the service's own words. */
@Serializable
data class LoopbackHeadlessErr(val message: String, val error: String = "headless_mint_refused")

/**
 * A loopback-only helper so the `pair` CLI can ask the ALREADY-RUNNING daemon to mint a pairing
 * ticket over its single authenticated relay connection — instead of opening a second daemon
 * connection (which would supersede the live one). Binds 127.0.0.1 only; never exposed off-host.
 * Also serves the headless-bridge management surface (issue #91): mint / list / revoke. Every route
 * requires the local control token ([LocalControlToken], readable only by this OS user): reaching
 * loopback alone is NOT local-user authority — other users and sandboxed processes can do that too.
 */
class PairLoopback(
    private val relay: RelayClient,
    private val relayWsBase: String,
    private val daemonPubB64: String,
    private val port: Int,
    /** The daemon's services, for the #367 execution half of the local control API. Null leaves those
     *  routes out (the legacy routes and the owner-device routes need only [relay]). */
    private val core: dev.ccpocket.daemon.DaemonCore? = null,
) {
    private val log = logger("PairLoopback")

    private companion object {
        // grace beyond the 120s ticket TTL for the daemon's headless intent to stay bindable — covers
        // redeem→connect→first-frame latency + modest clock skew so a bridge is never mis-classified as
        // a full-power device (issue #91). An abandoned headless mint blocks re-mint for at most this long.
        const val INTENT_GRACE_MS = 120_000L
    }

    fun start() {
        val localControlToken = runCatching { LocalControlToken.loadOrCreate() }.getOrElse { failure ->
            // Serving anything without a token would turn a disk-permission problem into an unauthenticated
            // control plane (and a free pairing ticket for every local process), so every route — legacy and
            // `/v1/local` alike — stays shut, and the reason is visible in the daemon log.
            log.warn("loopback API disabled: local control token setup failed (${failure::class.simpleName})")
            null
        }
        embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                // audit 2026-10-04 H2: every legacy route hangs off this guarded child — a request with an
                // Origin (a browser) or a non-loopback Host (DNS rebinding) is refused before any handler, and
                // (pairing security phase 0) so is one without the local control token: other OS users and
                // processes can reach 127.0.0.1 too, and `/pair` mints a full-power pairing ticket.
                val legacy = LegacyLoopbackGuard.routes(this, localControlToken)
                legacy.post("/pair") {
                    // mint serialization (issue #91): while a headless pairing is pending, an interactive
                    // mint could LIFO-cross the PSK binding — refuse for the ticket's short TTL instead
                    if (relay.bridges.intentPending()) {
                        call.respondText(
                            """{"error":"headless_pairing_pending","message":"a bridge pairing is in progress — retry in ~2 minutes"}""",
                            ContentType.Application.Json, HttpStatusCode.Conflict,
                        )
                        return@post
                    }
                    // issue #207: hold the ONE mint slot across the suspending mint round-trip, so a
                    // restricted mint can't interleave into THIS mint's suspension window either — the
                    // mirror image of the intentPending() gate above
                    if (!relay.bridges.reserveMint()) {
                        call.respondText(
                            """{"error":"headless_pairing_pending","message":"another pairing is in progress — retry shortly"}""",
                            ContentType.Application.Json, HttpStatusCode.Conflict,
                        )
                        return@post
                    }
                    try {
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
                    } finally {
                        relay.bridges.releaseMint()
                    }
                }

                // ---- headless bridge management (issue #91) ----

                // Delegates to the SAME BridgeService the app drives over the wire — no re-implemented mint
                // logic, so `pair --headless` and the app's "New bridge" apply one name check, one
                // workdir-must-exist rule, and one mint-serialization dance. A drift between two copies of
                // that would mis-classify a credential's power, which is the one thing #91 must never get
                // wrong.
                legacy.post("/pair/headless") {
                    val req = runCatching { PocketJson.decodeFromString<LoopbackHeadlessReq>(call.receiveText()) }.getOrNull()
                    if (req == null || req.name.isBlank() || req.workdirs.isEmpty()) {
                        call.respondText("""{"error":"bad_request","message":"name and at least one --workdir are required"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    val bc = relay.bridgeControl
                    if (bc == null) {
                        // no relay-side control plane yet: daemon still wiring up, or a LAN-only `run` with
                        // no relay link to mint a redeem ticket over
                        call.respondText(
                            """{"error":"relay_offline","message":"minting needs the relay link — the daemon is still starting, or it's running LAN-only","attached":${relay.attached}}""",
                            ContentType.Application.Json, HttpStatusCode.ServiceUnavailable,
                        )
                        return@post
                    }
                    val res = bc.create(
                        CreateBridge(
                            name = req.name, workdirs = req.workdirs,
                            maxSessions = req.maxSessions, opensPerMin = req.opensPerMin, promptsPerMin = req.promptsPerMin,
                            tier = req.tier,
                        ),
                    )
                    val cred = res.credential
                    if (!res.ok || cred == null) {
                        // Conflict is the honest status for every refusal here: they are all "some other
                        // pairing/bridge holds the slot or the name" or a bad input the CLI already screened.
                        call.respondText(
                            PocketJson.encodeToString(LoopbackHeadlessErr(res.error ?: "headless pairing failed")),
                            ContentType.Application.Json, HttpStatusCode.Conflict,
                        )
                        return@post
                    }
                    call.respondText(
                        PocketJson.encodeToString(
                            LoopbackHeadlessCred(
                                name = cred.name, accountId = cred.accountId, daemonPub = cred.daemonPub,
                                ticket = cred.ticket, ttlSec = cred.ttlSec, relay = cred.relay,
                                workdirs = cred.workdirs,
                            ),
                        ),
                        ContentType.Application.Json,
                    )
                }

                legacy.get("/bridges") {
                    val rows = relay.bridges.list().map { (id, spec) ->
                        LoopbackBridge(id, spec.name, spec.workdirs, spec.maxSessions, spec.opensPerMin, spec.promptsPerMin)
                    }
                    call.respondText(PocketJson.encodeToString(rows), ContentType.Application.Json)
                }

                legacy.post("/bridge/revoke") {
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

                legacy.get("/status") {
                    call.respondText(
                        PocketJson.encodeToString(LoopbackStatus(relay.accountId, relayWsBase, relay.attached, relay.lastPongAgeMs())),
                        ContentType.Application.Json,
                    )
                }

                // ---- the TOKEN-AUTHENTICATED local control API ----
                // Own prefix, own rules (token + JSON Content-Type + no browser Origin + body cap — the shared
                // gate in LocalControlGate.kt). The legacy routes above keep their request shape (no JSON
                // Content-Type demand) and share only the token.
                core?.let { c -> localControlToken?.let { token ->
                    // #367: the remote-execution surface, behind the shared gate (installExecutionControl
                    // calls `authorize` in LocalControlGate.kt) — creating an execution grant is a new
                    // permission on this computer, so it must never be reachable from the wire router.
                    // The deps load the execution planes on first authorised use (a machine that had
                    // never used remote execution does not load them at attach — see ExecutionUsage).
                    installExecutionControl(executionControlDepsOf(c), token)
                } }
            }
        }.start(wait = false)
        log.info("pair loopback on http://127.0.0.1:$port (POST /pair, POST /pair/headless, GET /bridges, POST /bridge/revoke, GET /status; token-authenticated)")
        if (localControlToken != null) log.info("local control API on http://127.0.0.1:$port$LOCAL_CONTROL_PREFIX (token-authenticated)")
    }
}
