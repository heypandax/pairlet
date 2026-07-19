package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.context_critical_caption
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
    private val midTurnReserve = 96.dp // ■ stop + gap + send
    private val idleReserve = 44.dp

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

    /** Nothing to say before the first turn lands (or against an older daemon). */
    @Test
    fun noUsageYetRendersNothing() = runComposeUiTest {
        setContent {
            PocketTheme {
                Box(Modifier.width(wide)) {
                    ContextGauge(used = null, window = 200_000, reserveEnd = idleReserve) {}
                }
            }
        }
        waitForIdle()
        assertTrue(onAllNodes(hasContentDescription(gaugeDesc())).fetchSemanticsNodes().isEmpty())
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
                // 375pt screen, row insets start 8 / end 10; attach 44 + chip ~135 + stack ~50 + gaps 18
                Row(Modifier.width(357.dp)) {
                    Box(Modifier.width(44.dp))
                    Box(Modifier.width(141.dp))
                    Box(Modifier.width(56.dp))
                    ContextGauge(used = 176_000, window = 200_000, reserveEnd = midTurnReserve) {}
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(96.dp))
                }
            }
        }
        waitForIdle()
        assertFalse(present("88%"), "the tightest real row must shed the number")

        setContent {
            PocketTheme {
                // 390pt idle: no stack chip, no stop button — the number has all the room it needs
                Row(Modifier.width(372.dp)) {
                    Box(Modifier.width(44.dp))
                    Box(Modifier.width(76.dp))
                    ContextGauge(used = 176_000, window = 200_000, reserveEnd = idleReserve) {}
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(44.dp))
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
