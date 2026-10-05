package dev.ccpocket.daemon.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The outcome of each interactive `pairlet pair` (pairing security phase 0), so the CLI can WAIT for it and
 * show the owner which device actually joined — its fingerprint, to compare with the phone.
 *
 * In-memory only, keyed by a random pairing id the daemon hands the CLI with the QR. An entry resolves once:
 * [Outcome.Paired] when an announce was anchored on this pairing's ticket (the device is in the full-power
 * allow-list from that moment), [Outcome.Refused] when one popped it but could not be anchored, or
 * [Outcome.Expired] when the ticket's local lifetime ran out first. Bounded: old entries are pruned.
 */
class OwnerPairingWatch(private val clock: () -> Long) {

    sealed interface Outcome {
        class Paired(val deviceId: String, val pub: ByteArray) : Outcome
        data object Pending : Outcome
        data object Expired : Outcome
        data object Refused : Outcome
        data object Unknown : Outcome
    }

    private class Entry(val openedAt: Long, val expiresAt: Long) {
        val result = CompletableDeferred<Outcome>()
    }

    private val entries = LinkedHashMap<String, Entry>()

    fun open(pairingId: String, expiresAt: Long) = synchronized(entries) {
        val now = clock()
        entries.entries.removeAll { (_, e) -> now - e.openedAt > RETAIN_MS }
        while (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
        entries[pairingId] = Entry(now, expiresAt)
    }

    /** First outcome wins; later calls for the same pairing are ignored. */
    fun resolve(pairingId: String, outcome: Outcome) {
        synchronized(entries) { entries[pairingId] }?.result?.complete(outcome)
    }

    /** The outcome, waiting up to [waitMs] for one; [Outcome.Pending] when the wait ends first. */
    suspend fun await(pairingId: String, waitMs: Long): Outcome {
        val e = synchronized(entries) { entries[pairingId] } ?: return Outcome.Unknown
        if (e.result.isCompleted) return e.result.await()
        val remaining = e.expiresAt - clock()
        if (remaining <= 0) return Outcome.Expired.also { e.result.complete(it) }
        val got = withTimeoutOrNull(minOf(waitMs, remaining).coerceAtLeast(1)) { e.result.await() }
        return got ?: if (clock() >= e.expiresAt) Outcome.Expired.also { e.result.complete(it) } else Outcome.Pending
    }

    /** Milliseconds until [pairingId] stops waiting (0 when unknown or over). */
    fun remainingMs(pairingId: String): Long =
        synchronized(entries) { entries[pairingId] }?.let { (it.expiresAt - clock()).coerceAtLeast(0) } ?: 0

    private companion object {
        const val MAX_ENTRIES = 32
        const val RETAIN_MS = 10 * 60_000L
    }
}
