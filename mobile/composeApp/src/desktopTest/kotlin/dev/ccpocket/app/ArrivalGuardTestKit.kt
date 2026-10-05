package dev.ccpocket.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import kotlin.test.assertTrue

/*
 * Driving the approval double-tap guard window on the frozen test clock.
 *
 * A pointer click is not instantaneous here: performClick's down → up gesture advances the test clock (60 ms on
 * this toolchain), and the click fires on the up. So "a click inside the window" is asserted on the WHOLE gesture
 * — the clock reading after the click must still be before the window closes — never on where it started.
 */

/** Let a card that has just appeared pass its double-tap guard window, so the next click is a deliberate one. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.passArrivalGuard() {
    mainClock.advanceTimeBy(dev.ccpocket.app.ui.approval.APPROVAL_ARRIVAL_GUARD_MS)
    mainClock.advanceTimeByFrame()
    waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.advanceTo(t: Long) {
    val d = t - mainClock.currentTime
    if (d > 0) mainClock.advanceTimeBy(d, ignoreFrameDuration = true)
    waitForIdle()
}

/** Activate a NON-decision control (a disclosure, a checkbox, an option) through its semantics action, which costs
 *  no test-clock time — so setting up a guarded card does not eat the window the decisions are tested in. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.tapSetup(label: String) {
    onAllNodes(hasText(label)).onFirst().performSemanticsAction(SemanticsActions.OnClick)
    advanceFrameAndWait()
}

/** Click [label] (the first node carrying it), asserting it is disabled and that the gesture ended before [end].
 *  Returns the gesture's length on the test clock. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.clickDisabledInside(label: String, end: Long): Long {
    val node = onAllNodes(hasText(label)).onFirst()
    node.assertIsNotEnabled()
    val start = mainClock.currentTime
    node.performClick()
    val stop = mainClock.currentTime
    assertTrue(stop < end, "the click on '$label' ended ${stop - end} ms after the window closed — not a click inside it")
    return stop - start
}

/**
 * Click every one of [labels] across the window that closes at [end]: each asserted disabled first, one full pass,
 * then more clicks while they fit, and a last one whose gesture ends on the window's final millisecond (end - 1).
 * Finally every label is asserted disabled at end - 1 itself.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.clickThroughGuardWindow(end: Long, labels: List<String>) {
    var span = 0L
    labels.forEach { span = maxOf(span, clickDisabledInside(it, end)) }
    var i = 0
    while (mainClock.currentTime + 2 * span < end) span = maxOf(span, clickDisabledInside(labels[i++ % labels.size], end))
    if (mainClock.currentTime <= end - 1 - span) {
        advanceTo(end - 1 - span)
        clickDisabledInside(labels.last(), end)
    }
    advanceTo(end - 1)
    labels.forEach { onAllNodes(hasText(it)).onFirst().assertIsNotEnabled() }
}
