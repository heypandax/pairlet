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
 *
 * Expiry is decided HERE, under one lock, for both sides: an announce must [tryClaim] the pairing before its
 * ticket may anchor anything, and a waiting CLI only declares it expired if nothing claimed it in time. So an
 * announce landing at the expiry edge either anchors AND is reported, or is refused — never "the CLI said
 * expired while a key went into devices.json".
 */
class OwnerPairingWatch(private val clock: () -> Long) {

    sealed interface Outcome {
        class Paired(val deviceId: String, val pub: ByteArray) : Outcome
        data object Pending : Outcome
        data object Expired : Outcome
        data object Refused : Outcome
        data object Unknown : Outcome
    }

    private enum class State { OPEN, CLAIMED, DONE }

    private class Entry(val openedAt: Long, val expiresAt: Long) {
        var state = State.OPEN // guarded by the watch's lock
        val result = CompletableDeferred<Outcome>()
    }

    private val entries = LinkedHashMap<String, Entry>()

    fun open(pairingId: String, expiresAt: Long) = synchronized(entries) {
        val now = clock()
        entries.entries.removeAll { (_, e) -> now - e.openedAt > RETAIN_MS && e.state != State.CLAIMED }
        while (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
        entries[pairingId] = Entry(now, expiresAt)
    }

    /**
     * An announce popped this pairing's ticket: may it still anchor? True — and the pairing is now waiting for
     * that announce's [resolve] — while it is open and inside its lifetime. False once it expired (or was
     * already resolved): the ticket must then be treated as gone. A pairing this watch no longer tracks (pruned)
     * is left to the ticket's own lifetime, which the caller has already checked.
     */
    fun tryClaim(pairingId: String): Boolean = synchronized(entries) {
        val e = entries[pairingId] ?: return true
        expireIfDue(e)
        if (e.state != State.OPEN) return false
        e.state = State.CLAIMED
        true
    }

    /** First outcome wins; later calls for the same pairing are ignored. */
    fun resolve(pairingId: String, outcome: Outcome) = synchronized(entries) {
        entries[pairingId]?.let { complete(it, outcome) }
    }

    /** The outcome, waiting up to [waitMs] for one; [Outcome.Pending] when the wait ends first. */
    suspend fun await(pairingId: String, waitMs: Long): Outcome {
        val (e, budget) = synchronized(entries) {
            val e = entries[pairingId] ?: return Outcome.Unknown
            expireIfDue(e)
            // a claimed pairing has an announce in flight: wait for ITS answer, whatever the clock says
            e to if (e.state == State.CLAIMED) waitMs else minOf(waitMs, e.expiresAt - clock())
        }
        if (!e.result.isCompleted && budget > 0) withTimeoutOrNull(budget) { e.result.await() }
        synchronized(entries) { expireIfDue(e) }
        return if (e.result.isCompleted) e.result.await() else Outcome.Pending
    }

    /** Milliseconds until [pairingId] stops waiting for an announce (0 when unknown, claimed or over). */
    fun remainingMs(pairingId: String): Long = synchronized(entries) {
        entries[pairingId]?.takeIf { it.state == State.OPEN }?.let { (it.expiresAt - clock()).coerceAtLeast(0) } ?: 0
    }

    /** Under the lock. */
    private fun expireIfDue(e: Entry) {
        if (e.state == State.OPEN && clock() >= e.expiresAt) complete(e, Outcome.Expired)
    }

    /** Under the lock. */
    private fun complete(e: Entry, outcome: Outcome) {
        if (e.state == State.DONE) return
        e.state = State.DONE
        e.result.complete(outcome)
    }

    private companion object {
        const val MAX_ENTRIES = 32
        const val RETAIN_MS = 10 * 60_000L
    }
}
