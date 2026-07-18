package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.CreateBridge
import dev.ccpocket.protocol.CreateShare
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

/** CLI -> daemon (loopback): mint a folder-share invite (issue #115) — the headless sibling of the app's
 *  "Create invite". [tier] is the AccessTier wire string (review|collaborate|autonomous); null/unknown →
 *  the safest (REVIEW). [expiresInSec] is the SHARE lifetime; null → the [CreateShare] default, and the
 *  daemon clamps it to 5min…90day regardless. */
@Serializable
data class LoopbackShareReq(
    val workdir: String,
    val tier: String? = null,
    val expiresInSec: Long? = null,
    val label: String? = null,
)

/** daemon -> CLI: the minted folder share. [code] is byte-identical to the app's "Create invite" code —
 *  the guest pastes it into Connect (single use). [expiresAt] is the share's cut-off (epoch ms); [ttlSec]
 *  is the far shorter window the redeem ticket inside the code stays valid to ACCEPT. On failure
 *  [ok] is false and [error] says why (bad path, a pairing mid-flight, relay offline). */
@Serializable
data class LoopbackShareMinted(
    val ok: Boolean,
    val code: String? = null,
    val folderName: String? = null,
    val tier: String? = null,
    val expiresAt: Long? = null,
    val ttlSec: Int? = null,
    val error: String? = null,
)

/** CLI -> daemon (loopback): revoke a folder share by its guest deviceId (from `share --list`). */
@Serializable
data class LoopbackShareRevokeReq(val deviceId: String)

/** daemon -> CLI (loopback): why a headless mint was refused, in the service's own words. */
@Serializable
data class LoopbackHeadlessErr(val message: String, val error: String = "headless_mint_refused")

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

        // the SHARE lifetime used when `share` is minted without --expires; mirrors CreateShare's default
        // (7 days). ShareService clamps whatever it receives to 5min…90day, so this is only the "no flag" value.
        const val SHARE_DEFAULT_SEC = 7L * 24 * 3600
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

                // Delegates to the SAME BridgeService the app drives over the wire — no re-implemented mint
                // logic, so `pair --headless` and the app's "New bridge" apply one name check, one
                // workdir-must-exist rule, and one mint-serialization dance. A drift between two copies of
                // that would mis-classify a credential's power, which is the one thing #91 must never get
                // wrong. (Same reuse the `share` CLI below already does.)
                post("/pair/headless") {
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

                // ---- owner folder-share (issue #115): mint / list / revoke, headless ----
                // Reuses the SAME ShareService the app drives over the wire — no re-implemented mint logic,
                // so the CLI and in-app "Create invite" bind an identical guest intent and emit an identical
                // code. Loopback reachability == local-user authority, the same trust `pair`/`pair/headless`
                // already ride: the machine's owner is the one entitled to grant a folder from that machine.

                post("/share") {
                    val sc = relay.shareControl
                    if (sc == null) {
                        // no relay-side control plane yet: daemon still wiring up, or a LAN-only `run` that
                        // has no relay link to mint a redeem ticket over
                        call.respondText(
                            PocketJson.encodeToString(LoopbackShareMinted(ok = false, error = "share minting needs the relay link — the daemon is still starting, or it's running LAN-only")),
                            ContentType.Application.Json, HttpStatusCode.ServiceUnavailable,
                        )
                        return@post
                    }
                    val req = runCatching { PocketJson.decodeFromString<LoopbackShareReq>(call.receiveText()) }.getOrNull()
                    if (req == null || req.workdir.isBlank()) {
                        call.respondText(
                            PocketJson.encodeToString(LoopbackShareMinted(ok = false, error = "--workdir <absolute dir> is required")),
                            ContentType.Application.Json, HttpStatusCode.BadRequest,
                        )
                        return@post
                    }
                    // wire string → tier, authoritatively via the tolerant serializer: an unknown token
                    // decodes to UNKNOWN, which ShareService then clamps to REVIEW (never falls OPEN)
                    val tier = req.tier
                        ?.let { runCatching { PocketJson.decodeFromString(AccessTier.serializer(), "\"$it\"") }.getOrNull() }
                        ?: AccessTier.REVIEW
                    val res = sc.create(
                        CreateShare(
                            path = req.workdir,
                            tier = tier,
                            expiresInSec = req.expiresInSec ?: SHARE_DEFAULT_SEC, // ShareService coerces to 5min…90day
                            label = req.label?.trim()?.takeIf { it.isNotEmpty() },
                        ),
                    )
                    val invite = res.invite
                    if (!res.ok || invite == null) {
                        call.respondText(
                            PocketJson.encodeToString(LoopbackShareMinted(ok = false, error = res.error ?: "mint failed")),
                            ContentType.Application.Json,
                        )
                        return@post
                    }
                    call.respondText(
                        PocketJson.encodeToString(
                            LoopbackShareMinted(
                                ok = true,
                                code = encodeShareInvite(invite), // byte-identical to the app's ShareInvite.encode()
                                folderName = invite.folderName,
                                tier = invite.tier.name.lowercase(), // wire == lowercased name; .wire is module-internal
                                expiresAt = invite.expiresAt,
                                ttlSec = invite.ttlSec,
                            ),
                        ),
                        ContentType.Application.Json,
                    )
                }

                get("/shares") {
                    val sc = relay.shareControl
                    if (sc == null) {
                        call.respondText("""{"error":"unavailable"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                        return@get
                    }
                    call.respondText(PocketJson.encodeToString(sc.list()), ContentType.Application.Json)
                }

                post("/share/revoke") {
                    val sc = relay.shareControl
                    if (sc == null) {
                        call.respondText("""{"error":"unavailable"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                        return@post
                    }
                    val req = runCatching { PocketJson.decodeFromString<LoopbackShareRevokeReq>(call.receiveText()) }.getOrNull()
                    if (req == null || req.deviceId.isBlank()) {
                        call.respondText("""{"error":"bad_request"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    call.respondText(PocketJson.encodeToString(sc.revoke(req.deviceId)), ContentType.Application.Json)
                }

                get("/status") {
                    call.respondText(
                        PocketJson.encodeToString(LoopbackStatus(relay.accountId, relayWsBase, relay.attached, relay.lastPongAgeMs())),
                        ContentType.Application.Json,
                    )
                }
            }
        }.start(wait = false)
        log.info("pair loopback on http://127.0.0.1:$port (POST /pair, POST /pair/headless, GET /bridges, POST /bridge/revoke, POST /share, GET /shares, POST /share/revoke, GET /status)")
    }
}
