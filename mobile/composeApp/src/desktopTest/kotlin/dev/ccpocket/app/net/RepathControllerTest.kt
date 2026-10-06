package dev.ccpocket.app.net

import dev.ccpocket.app.net.RepathController.Companion.BUSY_RECHECK_MS
import dev.ccpocket.app.net.RepathController.Companion.TIMER_CAP_MS
import dev.ccpocket.app.net.RepathController.Companion.TIMER_START_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** #404 / TRANSPORT-AUTO-REPATH-V1 4.1–4.2 and the controller row of section 5, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class RepathControllerTest {
    private class Rig(scope: TestScope) {
        var enabled = true
        var onRelay = true
        var url: String? = "ws://192.168.1.5:8799"
        var cooling = false
        var eligible = true
        var probeOk = true
        var idle = true
        var probeGate: CompletableDeferred<Unit>? = null
        var probes = 0
        var switches = 0
        val reports = mutableListOf<Pair<RepathTrigger, RepathResult>>()
        val c = RepathController(
            scope = scope.backgroundScope,
            now = { scope.testScheduler.currentTime },
            enabled = { enabled },
            isOnRelay = { onRelay },
            directUrl = { url },
            coolingDown = { cooling },
            eligible = { eligible },
            probe = { _, _ -> probes++; probeGate?.await(); probeOk },
            isIdle = { idle },
            switchNow = { switches++ },
            report = { t, r -> reports += t to r },
        )
        fun results() = reports.map { it.second }
    }

    @Test
    fun eachSignalEvaluatesAfterItsOwnDelay() = runTest {
        val expected = mapOf(
            RepathTrigger.PeerOnline to 5_000L, RepathTrigger.Foreground to 2_000L,
            RepathTrigger.DirectUrlChanged to 1_000L,
        )
        for ((trigger, ms) in expected) {
            val r = Rig(this)
            r.c.request(trigger)
            advanceTimeBy(ms - 1); runCurrent()
            assertEquals(0, r.probes, "$trigger must wait ${ms}ms")
            advanceTimeBy(1); runCurrent()
            assertEquals(1, r.switches, "$trigger evaluates at ${ms}ms")
            r.c.onAttached(direct = true)
            assertEquals(listOf(trigger to RepathResult.SwitchedDirect), r.reports)
            r.c.reset()
        }
    }

    @Test
    fun requestsDuringAnInFlightEvaluationMergeIntoOne() = runTest {
        val r = Rig(this)
        r.probeOk = false
        r.probeGate = CompletableDeferred()
        r.c.request(RepathTrigger.DirectUrlChanged)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, r.probes) // in flight, parked on the gate
        r.c.request(RepathTrigger.Foreground); r.c.request(RepathTrigger.PeerOnline)
        advanceTimeBy(5_000); runCurrent()
        r.probeGate!!.complete(Unit); runCurrent()
        assertEquals(2, r.probes, "two queued requests coalesce into one follow-up evaluation")
    }

    @Test
    fun timerBacksOffToCapAndResetsOnDirect() = runTest {
        val r = Rig(this)
        r.probeOk = false
        r.c.onAttached(direct = false)
        assertTrue(r.c.timerRunningForTest)
        var elapsed = 0L
        var interval = TIMER_START_MS
        repeat(6) {
            advanceTimeBy(interval); runCurrent(); elapsed += interval
            interval = (interval * 2).coerceAtMost(TIMER_CAP_MS)
        }
        assertEquals(6, r.probes)
        assertTrue(r.results().all { it == RepathResult.ProbeFailed })
        assertEquals(TIMER_CAP_MS, r.c.timerIntervalForTest, "60s doubling reaches the 15 min cap")
        r.c.onAttached(direct = true)
        assertFalse(r.c.timerRunningForTest)
        assertEquals(TIMER_START_MS, r.c.timerIntervalForTest)
        r.c.onAttached(direct = false) // back on relay: starts over from 60 s
        advanceTimeBy(TIMER_START_MS); runCurrent()
        assertEquals(7, r.probes)
    }

    @Test
    fun eachEarlyExitReportsItsReasonWithoutSwitching() = runTest {
        fun case(setup: Rig.() -> Unit, expected: RepathResult, probed: Boolean) {
            val r = Rig(this).apply(setup)
            r.c.request(RepathTrigger.DirectUrlChanged)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(listOf(expected), r.results())
            assertEquals(0, r.switches, "$expected must not switch")
            assertEquals(if (probed) 1 else 0, r.probes, "$expected probe count")
            r.c.reset()
        }
        case({ cooling = true }, RepathResult.CoolingDown, probed = false)
        case({ eligible = false }, RepathResult.Ineligible, probed = false)
        case({ url = "ws://192.168.1.5:notaport" }, RepathResult.Ineligible, probed = false)
        case({ probeOk = false }, RepathResult.ProbeFailed, probed = true)
        case({ idle = false }, RepathResult.DeferredBusy, probed = true)
    }

    @Test
    fun notOnRelayNoUrlOrDisabledIsSilent() = runTest {
        for (setup in listOf<Rig.() -> Unit>({ onRelay = false }, { url = null }, { enabled = false })) {
            val r = Rig(this).apply(setup)
            r.c.request(RepathTrigger.Foreground)
            r.c.onAttached(direct = false)
            advanceTimeBy(TIMER_START_MS * 2); runCurrent()
            assertEquals(0, r.probes); assertEquals(0, r.switches); assertTrue(r.reports.isEmpty())
            r.c.reset()
        }
    }

    @Test
    fun recentlySwitchedBlocksASecondSwitchForAMinute() = runTest {
        val r = Rig(this)
        r.c.request(RepathTrigger.DirectUrlChanged)
        advanceTimeBy(1_000); runCurrent()
        r.c.onAttached(direct = false) // switch fell back to relay
        r.c.request(RepathTrigger.Foreground)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(
            listOf(RepathResult.FellBackRelay, RepathResult.RecentlySwitched), r.results(),
        )
        assertEquals(1, r.switches)
        advanceTimeBy(60_000) // past the gap (the timer fires once in here too)
        runCurrent()
        assertEquals(2, r.switches)
    }

    @Test
    fun busyRechecksEvery15sAtMostFourTimes() = runTest {
        val r = Rig(this)
        r.idle = false
        r.c.request(RepathTrigger.DirectUrlChanged)
        advanceTimeBy(1_000); runCurrent()
        repeat(10) { advanceTimeBy(BUSY_RECHECK_MS); runCurrent() }
        assertEquals(5, r.probes, "first evaluation + 4 rechecks, then wait for the next signal")
        assertTrue(r.results().all { it == RepathResult.DeferredBusy })
        r.idle = true
        r.c.request(RepathTrigger.Foreground)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, r.switches)
    }

    @Test
    fun resetVoidsPendingSignalsTimerAndRechecks() = runTest {
        val r = Rig(this)
        r.idle = false
        r.c.request(RepathTrigger.DirectUrlChanged)
        advanceTimeBy(1_000); runCurrent() // busy → recheck armed
        r.c.request(RepathTrigger.PeerOnline)
        r.c.onAttached(direct = false)
        val probesBefore = r.probes
        r.c.reset()
        r.idle = true
        advanceTimeBy(TIMER_CAP_MS * 2); runCurrent()
        assertEquals(probesBefore, r.probes, "nothing armed before reset may fire after it")
        assertEquals(0, r.switches)
        assertFalse(r.c.timerRunningForTest)
    }
}
