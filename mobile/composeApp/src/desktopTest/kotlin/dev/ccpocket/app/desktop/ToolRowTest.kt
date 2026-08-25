package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ToolRowTest {

    @Test
    fun replayedOutputIsAvailableBehindTheToolDisclosure() = runComposeUiTest {
        setContent {
            PocketTheme {
                ToolRow("Bash", "ls", ToolStatus.OK, output = "a.txt")
            }
        }
        onAllNodes(hasText("— output —", substring = true)).assertCountEquals(0)
        onNodeWithTag(TOOL_ROW_TAG).performClick()
        onAllNodes(hasText("— output —", substring = true)).assertCountEquals(1)
        onAllNodes(hasText("a.txt", substring = true)).onFirst().assertIsDisplayed()
    }
}
