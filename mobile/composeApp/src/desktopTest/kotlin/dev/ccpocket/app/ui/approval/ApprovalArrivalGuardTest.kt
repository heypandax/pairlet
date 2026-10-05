package dev.ccpocket.app.ui.approval

import dev.ccpocket.app.data.ApprovalKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The double-tap guard's timing rules, on a hand-driven clock. What the cards do with the answer is pinned by
 * [ApprovalArrivalGuardUiTest] (phone) and the desktop twin; this file is the rule book.
 */
class ApprovalArrivalGuardTest {
    private var now = 0L
    private val guard = ApprovalArrivalGuard(clock = { now })
    private val a = ApprovalKey("c1", "1")
    private val b = ApprovalKey("c1", "2")
    private val window = APPROVAL_ARRIVAL_GUARD_MS

    @Test
    fun theWindowIsTheNamedConstant() {
        assertEquals(400L, APPROVAL_ARRIVAL_GUARD_MS)
    }

    @Test
    fun aFreshArrivalOnAnEmptySurfaceIsGuarded() {
        guard.noteKnown(a)
        now = 30
        assertEquals(30 + window, guard.show("s", a), "the first card of a session is guarded too")
    }

    @Test
    fun aRequestFirstLearnedOfByBeingDrawnIsGuarded() {
        now = 5_000
        assertEquals(5_000 + window, guard.show("s", a))
    }

    @Test
    fun theNextCardOfABurstIsGuardedHoweverLongItWasQueued() {
        guard.noteKnown(a)
        guard.noteKnown(b) // queued behind a at t=0
        guard.show("s", a)
        now = 10_000
        assertEquals(10_000 + window, guard.show("s", b), "replacing a card is the burst case, never exempt")
    }

    @Test
    fun aLongWaitingRequestOpenedOnPurposeIsArmedAtOnce() {
        guard.noteKnown(a)
        now = window // exactly the window: the app has known it long enough
        assertEquals(window, guard.show("s", a))
    }

    @Test
    fun theExemptionCountsFromArrivalNotFromFirstDraw() {
        guard.noteKnown(a)
        now = window - 1
        assertEquals(window - 1 + window, guard.show("s", a), "known for 399 ms: still a card that just arrived")
    }

    @Test
    fun aSurfaceThatWentEmptyCountsAsEmpty() {
        guard.noteKnown(a)
        guard.noteKnown(b)
        guard.show("s", a)
        guard.leave("s", a) // the last card was decided; nothing on screen
        now = 2_000
        assertEquals(2_000, guard.show("s", b), "b was waiting elsewhere and is opened deliberately")
    }

    @Test
    fun theSameRequestOnTheSameSurfaceKeepsItsInstant() {
        now = 100
        val armedAt = guard.show("s", a)
        now = 900
        assertEquals(armedAt, guard.show("s", a), "recomposition never re-times")
        guard.leave("s", a)
        now = 950
        assertEquals(armedAt, guard.show("s", a), "nor does leaving composition and coming back")
    }

    @Test
    fun leavingNamesTheRequestSoASameFrameSuccessorKeepsItsWindow() {
        guard.show("s", a)
        now = 1_000
        val bArmed = guard.show("s", b)
        guard.leave("s", a) // a's card disposes after b was already shown in its place
        now = 1_100
        assertEquals(bArmed, guard.show("s", b))
    }

    @Test
    fun surfacesAreIndependent() {
        guard.show("s1", a)
        now = 5_000
        guard.noteKnown(b)
        now = 6_000
        assertEquals(6_000, guard.show("s2", b), "an empty second surface is not a replacement")
        assertEquals(6_000 + window, guard.show("s1", b), "on s1 b replaces a")
    }

    @Test
    fun aReEmittedFrameIsNotANewArrival() {
        guard.noteKnown(a)
        now = 3_000
        guard.noteKnown(a) // reattach resurface
        assertEquals(3_000, guard.show("s", a))
    }
}
