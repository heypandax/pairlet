package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.context_critical_caption
import dev.ccpocket.app.resources.context_status_gauge_no_data
import dev.ccpocket.app.resources.qa_context_gauge
import dev.ccpocket.app.theme.PocketTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The occupancy gauge's contract, as the design draws it (`context-occupancy.jsx`, Option C, five
 * states). These pin the parts a screenshot cannot: WHEN the percent earns its width, when it is shed,
 * and that an unknown window never prints a fake one.
 *
 * [WIDE] leaves the number plenty of room; [TIGHT] is the 375pt worst case — long model id, stack chip
 * and a mid-turn stop button all on at once — where the gauge must yield before anything else does.
 */
@OptIn(ExperimentalTestApi::class)
class ContextGaugeUiTest {
    private val wide = 300.dp
    private val tight = 120.dp
    // the action slot's real widths since the accessory row went to 48dp targets (Chat Master v2)
    private val midTurnReserve = 104.dp // ■ stop + gap + send
    private val idleReserve = 48.dp

    private fun gaugeDesc() = runBlocking { getString(Res.string.qa_context_gauge) }

    /** Calm (<80%): a bare ring. Nothing to read unless you look — the whole point of chrome-less. */
    @Test
    fun calmShowsRingWithoutANumber() = runComposeUiTest {
        setContent {
            PocketTheme {
                Box(Modifier.width(wide)) {
                    ContextGauge(used = 84_000, window = 200_000, reserveEnd = idleReserve) {}
                }
            }
        }
        waitForIdle()
        assertFalse(present("42%"), "calm must not print a percent")
        assertEquals(1, onAllNodes(hasContentDescription(gaugeDesc())).fetchSemanticsNodes().size)
    }

    /** >=80%: the ring grows a number. It escalated by growing, not by adding chrome. */
    @Test
    fun warningGrowsItsNumber() = runComposeUiTest {
        setContent {
            PocketTheme {
                Box(Modifier.width(wide)) {
                    ContextGauge(used = 168_000, window = 200_000, reserveEnd = idleReserve) {}
                }
            }
        }
        waitForIdle()
        assertPresent("84%")
    }

    /**
     * The design's floor: under width pressure the NUMBER goes first and the colour-carrying ring stays,
     * so the model chip, the stack chip, stop and send never move (state 5).
     */
    @Test
    fun tightRowShedsTheNumberButKeepsTheRing() = runComposeUiTest {
        setContent {
            PocketTheme {
                Box(Modifier.width(tight)) {
                    ContextGauge(used = 176_000, window = 200_000, reserveEnd = midTurnReserve) {}
                }
            }
        }
        waitForIdle()
        assertFalse(present("88%"), "the number must yield before the row does")
        assertEquals(
            1, onAllNodes(hasContentDescription(gaugeDesc())).fetchSemanticsNodes().size,
            "the ring itself must survive the squeeze",
        )
    }

    /** No denominator (a backend we cannot size): raw occupancy, and never an invented percentage. */
    @Test
    fun unknownWindowShowsRawTokensNeverAPercent() = runComposeUiTest {
        setContent {
            PocketTheme {
                Box(Modifier.width(wide)) {
                    ContextGauge(used = 84_000, window = null, reserveEnd = idleReserve) {}
                }
            }
        }
        waitForIdle()
        assertPresent("~84k")
        assertFalse(present("%", substring = true), "an unknown window must never print a percent")
    }

    /** It is a readout, but it still has to go somewhere: the existing Session Info sheet. */
    @Test
    fun tappingOpensSessionInfo() = runComposeUiTest {
        var opened = 0
        setContent {
            PocketTheme {
                Box(Modifier.width(wide)) {
                    ContextGauge(used = 84_000, window = 200_000, reserveEnd = idleReserve) { opened++ }
                }
            }
        }
        waitForIdle()
        onAllNodes(hasContentDescription(gaugeDesc())).fetchSemanticsNodes() // present before we tap
        onNode(hasContentDescription(gaugeDesc())).performClick()
        assertEquals(1, opened)
    }

    /**
     * Review P2: a MEASURED 0 is a reading, absence is not — assistive tech must hear the difference, and
     * the unknown-window number must match the session sheet's `0` (not `~0`).
     */
    @Test
    fun zeroUsedAndNoUsedSpeakDifferently() = runComposeUiTest {
        val noData = runBlocking { getString(Res.string.context_status_gauge_no_data) }
        var used by mutableStateOf<Long?>(0L)
        setContent {
            PocketTheme {
                Box(Modifier.width(wide)) {
                    ContextGauge(used = used, window = null, reserveEnd = idleReserve) {}
                }
            }
        }
        waitForIdle()
        assertEquals(1, onAllNodes(hasContentDescription(gaugeDesc())).fetchSemanticsNodes().size, "used=0 is a reading")
        assertTrue(onAllNodes(hasContentDescription(noData)).fetchSemanticsNodes().isEmpty())
        assertTrue(present("0"), "unknown window + measured 0 prints plain 0")
        assertFalse(present("~0", substring = true), "must match the session sheet's token string")

        used = null
        waitForIdle()
        assertTrue(onAllNodes(hasContentDescription(gaugeDesc())).fetchSemanticsNodes().isEmpty(), "absence is not a reading")
        assertEquals(1, onAllNodes(hasContentDescription(noData)).fetchSemanticsNodes().size)
    }

    /**
     * #320-A: before the first turn lands (or against an older daemon / a backend that hasn't reported) the
     * gauge used to vanish, which read like "no such feature". It now stays as an empty ring with no number —
     * a missing value is never drawn as 0% — says so to assistive tech, and still opens session info.
     */
    @Test
    fun noUsageYetKeepsAnEmptyRingThatOpensInfo() = runComposeUiTest {
        var opened = 0
        val noData = runBlocking { getString(Res.string.context_status_gauge_no_data) }
        setContent {
            PocketTheme {
                Box(Modifier.width(wide)) {
                    ContextGauge(used = null, window = 200_000, reserveEnd = idleReserve) { opened++ }
                }
            }
        }
        waitForIdle()
        assertTrue(onAllNodes(hasContentDescription(gaugeDesc())).fetchSemanticsNodes().isEmpty(), "must not claim a reading")
        assertFalse(present("%", substring = true), "no percentage without occupancy")
        assertFalse(present("~", substring = true), "no token count without occupancy")
        onNode(hasContentDescription(noData)).performClick()
        assertEquals(1, opened)
    }

    /**
     * The rule above only holds because Row hands a non-weighted child the width its PREDECESSORS
     * already took — that is what lets the gauge see the squeeze at all. If that ever stops being true
     * the number would simply never yield, silently, so the accessory row's real shape is rehearsed
     * here: the 375pt worst case (long model id + stack chip + a mid-turn stop) must shed the number,
     * and a roomy 390pt idle row must keep it. Neighbours are stand-in boxes at their real widths, so
     * this pins the LAYOUT contract without coupling to the chips' internals.
     */
    @Test
    fun accessoryRowShedsTheNumberOnlyWhenItActuallyRunsOut() = runComposeUiTest {
        setContent {
            PocketTheme {
                // 375pt screen, row insets start 6 / end 8; attach 48 + chip ~135 + stack ~50 + gaps 18
                Row(Modifier.width(361.dp)) {
                    Box(Modifier.width(48.dp))
                    Box(Modifier.width(141.dp))
                    Box(Modifier.width(56.dp))
                    ContextGauge(used = 176_000, window = 200_000, reserveEnd = midTurnReserve) {}
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(104.dp))
                }
            }
        }
        waitForIdle()
        assertFalse(present("88%"), "the tightest real row must shed the number")

        setContent {
            PocketTheme {
                // 390pt idle: no stack chip, no stop button — the number has all the room it needs
                Row(Modifier.width(376.dp)) {
                    Box(Modifier.width(48.dp))
                    Box(Modifier.width(76.dp))
                    ContextGauge(used = 176_000, window = 200_000, reserveEnd = idleReserve) {}
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(48.dp))
                }
            }
        }
        waitForIdle()
        assertPresent("88%")
    }

    /** The retired amber strip's one surviving line — and it fires at critical, not at the old 90%. */
    @Test
    fun criticalCaptionCarriesTheDroppedTurnsWarning() = runComposeUiTest {
        setContent { PocketTheme { ContextCriticalCaption() } }
        waitForIdle()
        assertPresent(runBlocking { getString(Res.string.context_critical_caption) })
    }
}
