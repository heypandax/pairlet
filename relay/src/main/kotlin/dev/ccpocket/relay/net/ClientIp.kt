package dev.ccpocket.relay.net

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin

/**
 * Installs `X-Forwarded-For` handling for the relay, pinned to the LAST hop.
 *
 * This pinning is load-bearing, not cosmetic. Ktor's default is `useFirstProxy()`, and Caddy APPENDS
 * to a caller-supplied `X-Forwarded-For` rather than replacing it — so with the default, the address
 * every rate limiter keys on is a string the *caller* wrote. Rotating it per request hands the
 * attacker a fresh bucket each time, which retires `redeem:ip:` / `paircode:ip:` / `ws:ip:` /
 * `auth:ip:` all at once (a 6-digit pair code is brute-forceable inside its 120s window) and lets an
 * unbounded set of forged keys accumulate in [RateLimiter]'s map. Taking the LAST entry instead
 * picks the one hop no caller can write past: whatever our own reverse proxy appended.
 *
 * Defense in depth, and the reason this is only half the fix: `deploy/Caddyfile` also sets
 * `header_up X-Forwarded-For {remote_host}`, so the header is overwritten with the real peer before
 * it ever reaches us. Either measure alone closes the hole; both together mean a regression in one
 * doesn't reopen it. Binding to 127.0.0.1 is NOT such a measure — it constrains who our immediate
 * peer is, not what that peer forwards on a stranger's behalf.
 */
fun Application.installRelayForwardedHeaders() {
    install(XForwardedHeaders) { useLastProxy() }
}

/**
 * The client IP used for rate limiting: the last `X-Forwarded-For` hop, per
 * [installRelayForwardedHeaders] — i.e. the address our reverse proxy observed, not one the caller
 * chose for itself.
 */
fun ApplicationCall.clientIp(): String = request.origin.remoteHost
