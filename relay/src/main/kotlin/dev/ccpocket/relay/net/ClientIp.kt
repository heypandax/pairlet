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

/**
 * The subject a per-address limiter should count [ip] under. An IPv6 address collapses to its /64: that is
 * the block one subscriber is handed (a home line, one phone on a mobile network), and every address inside
 * it is theirs to use — keyed per address, one client owns 2^64 fresh buckets and no per-IP limit ever trips.
 * An IPv4-mapped IPv6 address counts as the IPv4 address it carries; IPv4 and anything unparseable are
 * returned unchanged (a hostname is never resolved here).
 */
fun rateLimitSubject(ip: String): String {
    if (':' !in ip) return ip
    val literal = ip.trim().removePrefix("[").substringBefore(']').substringBefore('%')
    val groups = ipv6Groups(literal) ?: return ip
    if (groups.subList(0, 5).all { it == 0 } && groups[5] == 0xffff) {
        return "${groups[6] shr 8}.${groups[6] and 0xff}.${groups[7] shr 8}.${groups[7] and 0xff}"
    }
    return groups.take(4).joinToString(":") { it.toString(16) } + "::/64"
}

/** The eight 16-bit groups of an IPv6 literal (`::` compression and a dotted IPv4 tail allowed), or null. */
private fun ipv6Groups(s: String): List<Int>? {
    if (s.isEmpty() || s.any { !(it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) return null
    val halves = s.split("::")
    if (halves.size > 2) return null
    val head = ipv6Fields(halves[0], dottedTailAllowed = halves.size == 1) ?: return null
    if (halves.size == 1) return head.takeIf { it.size == 8 }
    val tail = ipv6Fields(halves[1], dottedTailAllowed = true) ?: return null
    if (head.size + tail.size > 7) return null
    return head + List(8 - head.size - tail.size) { 0 } + tail
}

/** The groups of one `::`-free run of an IPv6 literal; a dotted IPv4 last field counts as two groups. */
private fun ipv6Fields(part: String, dottedTailAllowed: Boolean): List<Int>? {
    if (part.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    val fields = part.split(':')
    for ((i, f) in fields.withIndex()) {
        if (dottedTailAllowed && i == fields.lastIndex && '.' in f) {
            val octets = f.split('.').map { o -> o.toIntOrNull()?.takeIf { o.length in 1..3 && it in 0..255 } ?: return null }
            if (octets.size != 4) return null
            out += (octets[0] shl 8) or octets[1]
            out += (octets[2] shl 8) or octets[3]
        } else {
            if (f.length !in 1..4 || '.' in f) return null
            out += f.toInt(16)
        }
    }
    return out
}
