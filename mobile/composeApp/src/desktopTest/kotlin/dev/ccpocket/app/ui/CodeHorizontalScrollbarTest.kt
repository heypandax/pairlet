package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import kotlin.test.assertTrue
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class CodeHorizontalScrollbarTest {

    @Test
    fun overflowingFenceWrapsWithinMessageWidth() = runComposeUiTest {
        setContent {
            PocketTheme {
                Box(Modifier.width(220.dp)) {
                    MarkdownText("```text\n${"column".repeat(80)}\n```", color = androidx.compose.ui.graphics.Color.Black)
                }
            }
        }
        waitForIdle()
        onAllNodes(hasTestTag(CODE_SCROLLBAR_TAG)).assertCountEquals(0)
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText("column".repeat(80)).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1, "Long code must wrap across lines")
        assertTrue(!layouts.single().didOverflowWidth, "Wrapped code must fit the available width")
    }

    @Test
    fun shortFenceDoesNotAddScrollbarChrome() = runComposeUiTest {
        setContent {
            PocketTheme {
                Box(Modifier.width(220.dp)) {
                    MarkdownText("```text\nshort\n```", color = androidx.compose.ui.graphics.Color.Black)
                }
            }
        }
        waitForIdle()
        onAllNodes(hasTestTag(CODE_SCROLLBAR_TAG)).assertCountEquals(0)
    }
}
