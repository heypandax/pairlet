package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class CodeHorizontalScrollbarTest {

    @Test
    fun overflowingFenceExposesADraggableDesktopScrollbar() = runComposeUiTest {
        setContent {
            PocketTheme {
                Box(Modifier.width(220.dp)) {
                    MarkdownText("```text\n${"column".repeat(80)}\n```", color = androidx.compose.ui.graphics.Color.Black)
                }
            }
        }
        waitForIdle()
        onAllNodes(hasTestTag(CODE_SCROLLBAR_TAG)).assertCountEquals(1)
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
