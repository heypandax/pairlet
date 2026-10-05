package dev.ccpocket.daemon.relay

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
 * Anti-browser gate for the LEGACY loopback routes (`/pair`, `/pair/headless`, `/bridges`, `/bridge/revoke`,
 * `/status`) — audit 2026-10-04 H2, the compatible half.
 *
 * Those routes carry no token (shipped `pairlet` CLIs call them bare), so "reaching loopback" is their
 * whole authority. A web page must not borrow that authority:
 *  - an `Origin` header (any value, including `null`) means a browser sent the request — the CLI never
 *    sets one. This also stops a DNS-rebound page's POSTs, which browsers stamp with an Origin.
 *  - `Host` must name loopback on the port the connection actually landed on (`127.0.0.1:<port>` or
 *    `localhost:<port>`). A DNS-rebound page reads back GET responses (`/bridges`, `/status`) with
 *    `Host: attacker.example:<port>`, which this refuses.
 *
 * What it deliberately does NOT do: require a token or a Content-Type. The shipped CLI sends neither
 * (`setBody(String)` with no content type), so either would break `pair` / `bridges` / `status`.
 * Keeping other local users / processes out needs the token scheme — a separate change.
 */
internal object LegacyLoopbackGuard {
    /** Why the request is refused, or null when it may proceed. [hosts] = every `Host` header value. */
    fun rejection(origin: String?, hosts: List<String>, localPort: Int): String? {
        if (origin != null) return "forbidden_origin"
        val host = hosts.singleOrNull()?.trim()?.lowercase() ?: return "forbidden_host"
        return if (host in allowedHosts(localPort)) null else "forbidden_host"
    }

    // An HTTP client omits the port from Host only when it is the scheme default (80) — accept the bare
    // form exactly then, so a `--pair-port 80` CLI keeps working while any other port must match.
    private fun allowedHosts(port: Int): Set<String> = buildSet {
        add("127.0.0.1:$port")
        add("localhost:$port")
        if (port == 80) { add("127.0.0.1"); add("localhost") }
    }

    val Plugin = createRouteScopedPlugin("LegacyLoopbackGuard") {
        onCall { call ->
            val why = rejection(
                origin = call.request.headers[HttpHeaders.Origin],
                hosts = call.request.headers.getAll(HttpHeaders.Host).orEmpty(),
                localPort = call.request.local.localPort,
            ) ?: return@onCall
            call.respondText(
                """{"error":"$why","message":"this loopback API only answers the local pairlet CLI"}""",
                ContentType.Application.Json, HttpStatusCode.Forbidden,
            )
        }
    }

    /** Matches every path without consuming a segment — a node whose only job is to carry [Plugin]. */
    private object Selector : RouteSelector() {
        override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent
        override fun toString() = "(legacy loopback guard)"
    }

    /** A child of [parent] that guards every route declared on it; routes declared beside it are untouched. */
    fun routes(parent: Route): Route = parent.createChild(Selector).apply { install(Plugin) }
}
