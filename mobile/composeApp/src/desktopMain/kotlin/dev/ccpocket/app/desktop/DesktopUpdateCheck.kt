package dev.ccpocket.app.desktop

import dev.ccpocket.protocol.update.ReleaseClient
import dev.ccpocket.protocol.update.ReleaseVersions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The bounded, failure-proof half of "Check for updates" (issue #245).
 *
 * The original check ran the GitHub/mirror round-trip in a bare `launch` with no try/catch and no overall
 * deadline: any throw (or a DNS black hole that outlives [ReleaseClient]'s per-request timeouts) left
 * `updateState` parked on `Checking` forever — and since `Checking` is both the re-entry guard AND a
 * spinner-only UI branch, the button never came back until the app restarted. So the rule here is:
 * `Checking` is strictly transient — every path out of this function yields a terminal state.
 */

/** Overall deadline for one check — generous enough for the mirror hop plus the GitHub fallback. */
internal const val UPDATE_CHECK_TIMEOUT_MS = 60_000L

/** Last-resort copy, used when even loading the localized string fails (it must never re-throw). */
internal const val UPDATE_CHECK_FALLBACK_MSG = "Couldn't check for updates — check your network."
internal const val UPDATE_APPLY_FALLBACK_MSG = "Update failed."

/** The check's terminal state plus the release to hand [DesktopUpdater.applyStandalone] (only when newer). */
internal data class UpdateCheckOutcome(
    val state: DkUpdateState,
    val release: ReleaseClient.Release? = null,
)

/** Signals the overall deadline, so the failure copy can say "timed out" rather than a bare network line. */
internal class UpdateCheckTimeoutException(ms: Long) : Exception("timed out after ${ms / 1000}s")

/**
 * Runs one update check and always returns a terminal [DkUpdateState] — never throws except on genuine
 * cancellation of the caller.
 *
 * [probeScope] matters: [latest] is a *blocking* HTTP call, so it is launched in a scope that is NOT a child
 * of this coroutine. Structured concurrency would otherwise make [withTimeoutOrNull] join the stuck child and
 * hang exactly like the bug being fixed; detached, the deadline can abandon the stalled socket (a daemon IO
 * thread that finishes whenever it finishes) and still answer the user.
 */
internal suspend fun resolveUpdateCheck(
    current: String,
    probeScope: CoroutineScope,
    latest: suspend () -> ReleaseClient.Release?,
    source: suspend () -> DkInstallSource,
    failureText: suspend (Throwable?) -> String,
    timeoutMs: Long = UPDATE_CHECK_TIMEOUT_MS,
): UpdateCheckOutcome {
    val probe = probeScope.async { latest() }
    // Result-in-a-box: a null from withTimeoutOrNull then means *only* "deadline hit", which keeps the three
    // failure shapes (timeout / threw / reachable-but-nothing-usable) distinguishable for the message.
    val probed: Result<ReleaseClient.Release?>? = withTimeoutOrNull(timeoutMs) { runCatching { probe.await() } }
    if (probed == null) {
        probe.cancel() // best effort: a blocking read may outlive it, but nothing waits on it any more
        return failedOutcome(UpdateCheckTimeoutException(timeoutMs), failureText)
    }
    probed.exceptionOrNull()?.let { return failedOutcome(it, failureText) }
    val rel = probed.getOrNull() ?: return failedOutcome(null, failureText) // reached, nothing usable
    // even the pure-looking tail can throw (currentSource() walks the filesystem) — it must not strand Checking
    return runCatching {
        if (ReleaseVersions.isNewer(rel.version, current)) {
            UpdateCheckOutcome(DkUpdateState.Available(rel.version, source()), rel)
        } else {
            UpdateCheckOutcome(DkUpdateState.UpToDate(current))
        }
    }.getOrElse { failedOutcome(it, failureText) }
}

private suspend fun failedOutcome(
    cause: Throwable?,
    failureText: suspend (Throwable?) -> String,
): UpdateCheckOutcome {
    // fetching the copy is itself fallible (suspend resource load); a failure there must not re-strand us
    val msg = runCatching { failureText(cause) }.getOrNull()?.takeIf { it.isNotBlank() } ?: UPDATE_CHECK_FALLBACK_MSG
    return UpdateCheckOutcome(DkUpdateState.Failed(msg))
}
