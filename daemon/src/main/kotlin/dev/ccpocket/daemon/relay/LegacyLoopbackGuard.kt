package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.control.LocalControlToken
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext

/**
 * The gate in front of the LEGACY loopback routes (`/pair`, `/pair/headless`, `/bridges`, `/bridge/revoke`,
 * `/status`) — audit 2026-10-04 H2.
 *
 * Two layers, checked in this order:
 *
 *  1. Anti-browser. An `Origin` header (any value, including `null`) means a browser sent the request — the
 *     CLI never sets one; this also stops a DNS-rebound page's POSTs, which browsers stamp with an Origin.
 *     `Host` must name loopback on the port the connection actually landed on (`127.0.0.1:<port>` or
 *     `localhost:<port>`), which refuses a DNS-rebound page reading back GET responses. Refusal: 403.
 *  2. The local control token (pairing security phase 0). Reaching 127.0.0.1 is something EVERY local process
 *     can do, including other OS users' — and `/pair` hands out a full-power pairing ticket. So these routes
 *     now demand the same token as `/v1/local/…` ([LocalControlToken], a file only this OS user can read).
 *     Refusal: 401. A request with NO token is almost always an older `pairlet` CLI talking to a newer daemon,
 *     and its reply says so in words that CLI prints verbatim. There is deliberately no tokenless fallback.
 *
 * No Content-Type demand: the shipped CLI's `setBody(String)` sends `text/plain`, and the token already
 * decides who may call. When the daemon could not set up its token ([routes] gets null) every legacy request
 * is refused (503) rather than served unauthenticated.
 */
internal object LegacyLoopbackGuard {
    /** Why the request is refused, or null when it may proceed. [hosts] = every `Host` header value. */
    fun rejection(origin: String?, hosts: List<String>, localPort: Int): String? {
        if (origin != null) return "forbidden_origin"
        val host = hosts.singleOrNull()?.trim()?.lowercase() ?: return "forbidden_host"
        return if (host in allowedHosts(localPort)) null else "forbidden_host"
    }

    /** A refusal of the token layer: HTTP status, machine code, the sentence a CLI shows. */
    data class TokenRefusal(val status: HttpStatusCode, val code: String, val message: String)

    /** The token layer: null when [presented] matches [expected] (constant-time); [expected] null = no token. */
    fun tokenRejection(expected: String?, presented: String?): TokenRefusal? = when {
        expected == null -> TokenRefusal(
            HttpStatusCode.ServiceUnavailable, "local_control_unavailable",
            "the daemon could not set up its local control token, so its loopback API is off — see the daemon log",
        )
        presented == null -> TokenRefusal(HttpStatusCode.Unauthorized, CLI_OUTDATED, CLI_OUTDATED_MESSAGE)
        !LocalControlToken.matches(expected, presented) -> TokenRefusal(
            HttpStatusCode.Unauthorized, "unauthorized",
            "wrong local control token — run pairlet as the same OS user as the daemon " +
                "(and with the same CC_POCKET_IDENTITY, if you set one)",
        )
        else -> null
    }

    /** What a tokenless caller is told — in practice a `pairlet` older than the running daemon. */
    const val CLI_OUTDATED = "cli_outdated"
    const val CLI_OUTDATED_MESSAGE =
        "this pairlet CLI is older than the running daemon and cannot use its loopback API any more " +
            "(it now needs the local control token). Use the pairlet that came with the daemon: open a new " +
            "terminal, or update it (pairlet update, brew upgrade, scoop update), then run the command again."

    // An HTTP client omits the port from Host only when it is the scheme default (80) — accept the bare
    // form exactly then, so a `--pair-port 80` CLI keeps working while any other port must match.
    private fun allowedHosts(port: Int): Set<String> = buildSet {
        add("127.0.0.1:$port")
        add("localhost:$port")
        if (port == 80) { add("127.0.0.1"); add("localhost") }
    }

    class Config {
        /** The daemon's local control token; null = it could not be set up, and every request is refused. */
        var token: String? = null
    }

    val Plugin = createRouteScopedPlugin("LegacyLoopbackGuard", ::Config) {
        val token = pluginConfig.token
        onCall { call ->
            rejection(
                origin = call.request.headers[HttpHeaders.Origin],
                hosts = call.request.headers.getAll(HttpHeaders.Host).orEmpty(),
                localPort = call.request.local.localPort,
            )?.let { why ->
                call.respondText(
                    """{"error":"$why","message":"this loopback API only answers the local pairlet CLI"}""",
                    ContentType.Application.Json, HttpStatusCode.Forbidden,
                )
                return@onCall
            }
            val refusal = tokenRejection(token, call.request.headers[LocalControlToken.HEADER]) ?: return@onCall
            call.respondText(
                """{"error":"${refusal.code}","message":"${refusal.message}"}""",
                ContentType.Application.Json, refusal.status,
            )
        }
    }

    /** Matches every path without consuming a segment — a node whose only job is to carry [Plugin]. */
    private object Selector : RouteSelector() {
        override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent
        override fun toString() = "(legacy loopback guard)"
    }

    /** A child of [parent] that guards every route declared on it; routes declared beside it are untouched.
     *  [token] is the daemon's local control token — null refuses everything (fail closed). */
    fun routes(parent: Route, token: String?): Route =
        parent.createChild(Selector).apply { install(Plugin) { this.token = token } }
}
