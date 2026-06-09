package dev.ccpocket.relay.net

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

/**
 * The client IP used for rate limiting. With XForwardedHeaders installed, [origin].remoteHost
 * reflects Caddy's X-Forwarded-For. Trusting it is safe ONLY because the relay binds 127.0.0.1, so
 * the immediate peer is always the loopback Caddy — no untrusted party can spoof the header to us.
 */
fun ApplicationCall.clientIp(): String = request.origin.remoteHost
