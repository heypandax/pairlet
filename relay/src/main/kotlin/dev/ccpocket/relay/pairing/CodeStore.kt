package dev.ccpocket.relay.pairing

import dev.ccpocket.protocol.PairCodePayload
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Short 6-digit pairing codes -> the payload a phone needs to pair (account id, daemon E2E pubkey,
 * ticket). In-memory, single-use, short-TTL. This is the convenience path; a phone that scans the QR
 * instead gets the daemon pubkey out-of-band (the relay never substitutes it there).
 */
class CodeStore(private val clock: () -> Long = System::currentTimeMillis) {
    private class Entry(val accountId: String, val e2ePub: String, val ticket: String, val expiresAt: Long)

    private val map = ConcurrentHashMap<String, Entry>()
    private val rng = SecureRandom()

    /** Register a fresh code for a just-minted ticket. Returns the 6-digit code to show on the Mac. */
    fun put(accountId: String, e2ePub: String, ticket: String, ttlMs: Long = 120_000): String {
        var code: String
        do { code = (rng.nextInt(900_000) + 100_000).toString() } while (map.putIfAbsent(code, Entry(accountId, e2ePub, ticket, clock() + ttlMs)) != null)
        return code
    }

    /** Single-use resolve: removes the code and returns its payload if still valid. */
    fun take(code: String): PairCodePayload? {
        val e = map.remove(code) ?: return null
        if (e.expiresAt < clock()) return null
        return PairCodePayload(e.accountId, e.e2ePub, e.ticket)
    }

    fun sweep() {
        val now = clock()
        map.entries.removeAll { it.value.expiresAt < now }
    }
}
