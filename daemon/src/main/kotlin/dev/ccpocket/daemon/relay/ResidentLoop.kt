package dev.ccpocket.daemon.relay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Run [body] every [periodMs], forever — and let no single failed iteration end it.
 *
 * [RelayClient.run] launches its resident loops (idle reaper, guest-expiry sweep) as children of the same
 * coroutineScope as the reconnect loop. An exception escaping one of them failed that child, which cancelled
 * the scope — the reconnect loop included — and run() threw out of Main: a daemon that no longer talks to
 * the relay (audit 2026-10-04). A failed iteration is handed to [onFailure] and the next one runs on time.
 * Cancellation still ends the loop.
 */
internal suspend fun residentLoop(periodMs: Long, onFailure: (Exception) -> Unit, body: suspend () -> Unit): Nothing {
    while (true) {
        delay(periodMs)
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { onFailure(e) }
        }
    }
}
