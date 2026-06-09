package dev.ccpocket.relay.net

import java.util.concurrent.ConcurrentHashMap

/**
 * Fixed-window rate limiter with optional exponential lockout. One instance is shared across all
 * surfaces; callers namespace keys (e.g. "redeem:ip:1.2.3.4", "auth:acct:<id>"). Bounded by sweeping.
 */
class RateLimiter(private val clock: () -> Long = System::currentTimeMillis) {
    private class Bucket(var windowStart: Long, var count: Int, var lockedUntil: Long, var strikes: Int)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    /**
     * @return true if the call is allowed. When [lockoutOnBreach], repeated breaches escalate a
     * lockout (1→2→4→8→15 min cap) during which everything is denied — for failed-auth/redeem abuse.
     */
    fun check(key: String, limit: Int, windowMs: Long, lockoutOnBreach: Boolean = false): Boolean {
        val now = clock()
        var allowed = false
        buckets.compute(key) { _, cur ->
            val b = cur ?: Bucket(now, 0, 0, 0)
            if (now < b.lockedUntil) {
                allowed = false
            } else {
                if (now - b.windowStart >= windowMs) { b.windowStart = now; b.count = 0 }
                if (b.count < limit) {
                    b.count++; allowed = true
                } else {
                    allowed = false
                    if (lockoutOnBreach) { b.strikes++; b.lockedUntil = now + backoffMs(b.strikes) }
                }
            }
            b
        }
        return allowed
    }

    /** Drop idle buckets so the map can't grow without bound. Call periodically. */
    fun sweep(idleMs: Long = 3_600_000) {
        val cutoff = clock() - idleMs
        buckets.entries.removeAll { it.value.windowStart < cutoff && it.value.lockedUntil < clock() }
    }

    private fun backoffMs(strikes: Int): Long {
        val mins = minOf(15L, 1L shl (strikes - 1).coerceIn(0, 4))
        return mins * 60_000
    }
}
