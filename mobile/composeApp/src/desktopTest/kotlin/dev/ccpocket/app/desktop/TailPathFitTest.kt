package dev.ccpocket.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.TailPathText
import dev.ccpocket.app.ui.fitTailPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #392: the file viewer's title only ever got shorter. The truncation was a one-way counter that
 * grew on overflow and was reset by nothing but a new path, so a pane that was narrow once kept showing
 * "…e.py" after the user dragged it wide again.
 *
 * These tests pin the two halves of the fix: [fitTailPath] picks the LARGEST tail that fits a given width
 * (so the answer is a function of the width, not of the widths seen before it), and [TailPathText] refits
 * when that width changes — narrow → wide → narrow, with the wide step showing the path in full again.
 */
class TailPathFitTest {

    /** A stand-in for real text metrics: latin cells are 10 wide, CJK ones 20 (they really are ~2×). */
    private fun measure(s: String): Int = s.sumOf { if (it.code > 0x2E80) 20 else 10 }

    private fun fit(full: String, maxWidth: Int) = fitTailPath(full, maxWidth) { measure(it) }

    @Test
    fun whatFitsIsShownWhole() {
        val p = "/srv/app/main.py"
        assertEquals(p, fit(p, measure(p)), "a path that fits must not be trimmed at all")
        assertEquals(p, fit(p, measure(p) + 500), "nor when there is room to spare")
    }

    @Test
    fun keepsTheLongestTailThatFits() {
        val p = "/Users/nobody/work/services/ingest/pipeline/stage.py"
        val width = 200 // 20 cells
        val shown = fit(p, width)
        assertTrue(shown.startsWith("…"), "trimmed text must be marked as trimmed: $shown")
        assertTrue(shown.endsWith("stage.py"), "the tail identifies the file: $shown")
        assertTrue(measure(shown) <= width, "must fit the width it was given: $shown")
        // maximal: one more character of the path would not have fitted
        val oneMore = "…" + p.takeLast(shown.length) // shown.length counts the "…", so this keeps one extra char
        assertTrue(measure(oneMore) > width, "must not leave usable space unused: $shown vs $oneMore")
    }

    @Test
    fun widerWidthGivesThePathBack() {
        val p = "/Users/nobody/work/services/ingest/pipeline/stage.py"
        val narrow = fit(p, 120)
        val wider = fit(p, 300)
        val wide = fit(p, measure(p))
        assertTrue(narrow.length < wider.length, "widening must show MORE: '$narrow' → '$wider'")
        assertEquals(p, wide, "a pane wide enough for the whole path must show the whole path")
        // the regression itself: the width alone decides, never the history of widths
        assertEquals(narrow, fit(p, 120), "the same width must always produce the same text")
    }

    @Test
    fun cjkNamesAreMeasuredNotCounted() {
        val p = "/项目/文档/需求说明.md"
        val shown = fit(p, 200) // 10 latin cells' worth — 5 CJK ones
        assertTrue(measure(shown) <= 200, "a double-width name must not overflow: $shown")
        assertTrue(shown.startsWith("…") && shown.endsWith(".md"), "still ends with the file: $shown")
    }

    @Test
    fun neverSplitsASurrogatePair() {
        val p = "/tmp/a/🙂🙂🙂.txt" // each emoji is two chars in a Kotlin String
        for (w in 40..measure(p) step 10) {
            val shown = fit(p, w)
            assertTrue(shown.none { it.isLowSurrogate() && !shown.hasHighBefore(it) }, "orphaned surrogate in: $shown")
            assertTrue(shown.first() == '…' || shown == p)
        }
    }

    private fun String.hasHighBefore(low: Char): Boolean {
        val i = indexOf(low)
        return i > 0 && this[i - 1].isHighSurrogate()
    }

    @Test
    fun aBareNameTooLongForThePaneKeepsItsEnd() {
        val p = "report.py"
        val shown = fit(p, 30) // not even three cells
        assertTrue(shown.startsWith("…") && shown.endsWith(".py"), "keep the extension visible: $shown")
    }

    /** The end-to-end half: a real [TailPathText] in a pane that narrows, widens and narrows again. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theTitleRecoversWhenThePaneIsWidenedAgain() {
        val path = "/srv/app/services/ingest/pipeline/collector/stage.py"
        runDesktopComposeUiTest(width = 900, height = 200) {
            var width by mutableStateOf(90.dp)
            setContent {
                PocketTheme {
                    Box(Modifier.width(width)) {
                        TailPathText(path, modifier = Modifier.testTag("title"), fontSize = 12.sp)
                    }
                }
            }
            fun shown(): String =
                onNodeWithTag("title").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
                    ?.joinToString("") { it.text } ?: error("no text on the title node")

            waitForIdle()
            val narrow = shown()
            assertTrue(narrow.startsWith("…"), "a 90dp pane cannot show this path whole: $narrow")

            width = 700.dp
            waitForIdle()
            assertEquals(path, shown(), "a pane with room for the whole path must show it (issue #392)")

            width = 90.dp
            waitForIdle()
            assertEquals(narrow, shown(), "narrowing back must return to the same tail")
        }
    }
}
