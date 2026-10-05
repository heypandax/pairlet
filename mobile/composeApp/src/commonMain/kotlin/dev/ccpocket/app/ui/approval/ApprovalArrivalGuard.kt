package dev.ccpocket.app.ui.approval

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import dev.ccpocket.app.data.ApprovalKey
import dev.ccpocket.app.data.FleetRuntime
import dev.ccpocket.app.data.PocketRepository
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/**
 * How long the decision controls of a card that has just landed under the user's finger stay inert.
 *
 * Origin: the follow-up to audit 2026-10-04 H1/M4. Binding every verdict to the request its card was composed
 * with stops a second click that arrives BEFORE the card recomposes. A human double tap/click is slower than
 * that: its second press lands a frame or more later, on the NEXT card of a burst, which by then sits in the
 * same place with the same buttons — a valid click approving a command nobody read. 400 ms outlasts the second
 * press of a double tap or double click and is still shorter than anyone takes to actually read a request, so a
 * deliberate answer never waits on it.
 */
const val APPROVAL_ARRIVAL_GUARD_MS: Long = 400

/**
 * When does a request's decision become clickable on a given surface? One answer for the phone and the
 * desktop — pure bookkeeping, no Compose, driven by an injectable [clock] (tests pass the virtual UI clock).
 *
 * A *surface* is one place on screen where an actionable card sits: the phone's focused ask (Secure Approval
 * sheet or question card — they replace each other), one desktop chat pane's tail card, one row slot of the
 * tray / bell list. The rules:
 *
 *  1. A request that becomes the card on a surface is guarded for [windowMs] from that instant — whether it
 *     arrived just now, was promoted after the previous card was decided or withdrawn, or replaced another card
 *     because the user switched elsewhere.
 *  2. The window is timed per (surface, request), never per composition: the same request re-rendered on the
 *     same surface (recomposition, scrolling it away and back, rotation) keeps its original instant.
 *  3. The one exemption — an approval the user deliberately goes to: when the surface held NO card just before
 *     and the app has known the request for at least [windowMs] (counted from [noteKnown], i.e. when the app
 *     first learned of it, not when it was first drawn), the card is armed at once. Opening a long-waiting
 *     approval from a push, the inbox or a session switch is not a card jumping under the finger. A card that
 *     REPLACES another card is never exempt, however long it has been queued: that is exactly the burst case.
 *
 * Single-threaded by construction: [ObserveApprovalArrivals] feeds [noteKnown] from a collector on the UI
 * dispatcher and every other call comes from composition.
 */
class ApprovalArrivalGuard(
    private val clock: () -> Long = monotonicMillis(),
    private val windowMs: Long = APPROVAL_ARRIVAL_GUARD_MS,
) {
    private class Slot(val key: ApprovalKey, val armedAt: Long, var vacant: Boolean = false)

    private val knownAt = LinkedHashMap<ApprovalKey, Long>()
    private val slots = HashMap<String, Slot>()

    fun now(): Long = clock()

    /** The app has learned of [key] (it entered the repository's pending state — the focused ask or the fleet's
     *  pending list). The first call wins: a re-emitted frame (reattach resurface) is not a new arrival. */
    fun noteKnown(key: ApprovalKey) {
        if (key in knownAt) return
        knownAt[key] = clock()
        // bounded: only the arrival time of requests still plausibly on screen matters
        if (knownAt.size > MAX_KNOWN) knownAt.remove(knownAt.keys.first())
    }

    /**
     * [surface] now shows [key] as its card. Returns the instant (in [clock] time) from which its decisions are
     * accepted. Idempotent for the same request on the same surface, so it may be called on every composition.
     */
    fun show(surface: String, key: ApprovalKey): Long {
        val slot = slots[surface]
        if (slot != null && slot.key == key) {
            slot.vacant = false
            return slot.armedAt
        }
        val t = clock()
        noteKnown(key) // a request first learned of by being drawn counts as known from now
        val known = knownAt[key] ?: t
        val emptyBefore = slot == null || slot.vacant
        val armedAt = if (emptyBefore && t - known >= windowMs) t else t + windowMs
        slots[surface] = Slot(key, armedAt)
        return armedAt
    }

    /** [surface] stopped showing [key]. Only that request's slot is vacated, so a card replaced in the same
     *  frame (its successor already [show]n) keeps the successor's window. */
    fun leave(surface: String, key: ApprovalKey) {
        slots[surface]?.takeIf { it.key == key }?.vacant = true
    }

    /** Test seam: the instant [key]'s card arms on the surface currently showing it (null when none does). */
    internal fun armedAt(key: ApprovalKey): Long? = slots.values.filter { it.key == key && !it.vacant }.maxOfOrNull { it.armedAt }

    private companion object {
        const val MAX_KNOWN = 512
    }
}

/** A monotonic millisecond clock: a wall clock stepped backwards would hold the decisions dead for the gap. */
private fun monotonicMillis(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}

/**
 * The app's one guard. Provided at the phone and desktop roots ([ProvideApprovalArrivalGuard]); when absent (a
 * card composed on its own, e.g. a showcase or a unit test) each call site keeps a private guard of its own.
 */
val LocalApprovalArrivalGuard = staticCompositionLocalOf<ApprovalArrivalGuard?> { null }

/**
 * Root wiring: feed [guard] the app's arrival times ([ObserveApprovalArrivals]) and provide it to [content].
 * The guard lives here, with the UI, rather than in the repository: it is presentation state.
 */
@Composable
fun ProvideApprovalArrivalGuard(
    repo: PocketRepository,
    guard: ApprovalArrivalGuard = remember { ApprovalArrivalGuard() },
    content: @Composable () -> Unit,
) {
    ObserveApprovalArrivals(guard, repo)
    CompositionLocalProvider(LocalApprovalArrivalGuard provides guard, content = content)
}

/**
 * Record when each request first reaches the app: the focused ask (approval or question) and every row of the
 * fleet's pending list, across all linked computers. Observed off snapshot state, so it is the moment the
 * repository took the frame (one main-loop turn later at most) — independent of frames, of what is on screen and
 * of whether any card is drawn yet. That is what the opened-on-purpose exemption counts from.
 */
@Composable
fun ObserveApprovalArrivals(guard: ApprovalArrivalGuard, repo: PocketRepository) {
    LaunchedEffect(guard, repo) {
        snapshotFlow {
            // every live link: the primary and the satellites of the other paired computers
            val links = FleetRuntime.forPrimary(repo)?.repos() ?: listOf(repo)
            buildList {
                links.forEach { link ->
                    link.pendingAsk.value?.let { add(ApprovalKey(it.convoId, it.askId)) }
                    addAll(link.pendingApprovals.keys)
                }
            }
        }.collect { keys -> keys.forEach(guard::noteKnown) }
    }
}

/** The phone's focused ask: the Secure Approval sheet and the docked question card take each other's place. */
const val FOCUSED_ASK_SURFACE = "ask:focused"

/**
 * Whether the decision controls of the card showing [key] on [surface] accept input yet. False for the guard
 * window (see [ApprovalArrivalGuard]), then true for good; recomposition never re-arms it. A null [key] (no
 * card) is armed: there is nothing to guard.
 *
 * Callers pass the result to `clickable(enabled = …)` — which is also what a screen reader reports and what
 * keyboard activation honours — and draw the control with that surface's existing disabled look.
 */
@Composable
fun rememberApprovalArmed(surface: String, key: ApprovalKey?): Boolean {
    val guard = LocalApprovalArrivalGuard.current ?: remember { ApprovalArrivalGuard() }
    if (key == null) return true
    val armedAt = remember(guard, surface, key) { guard.show(surface, key) }
    var armed by remember(guard, surface, key) { mutableStateOf(guard.now() >= armedAt) }
    if (!armed) {
        LaunchedEffect(guard, surface, key) {
            delay((armedAt - guard.now()).coerceAtLeast(0))
            armed = true
        }
    }
    DisposableEffect(guard, surface, key) { onDispose { guard.leave(surface, key) } }
    return armed
}
