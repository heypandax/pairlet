package dev.ccpocket.app.ui.fleet

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.runtime.mutableStateOf
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.approval_fab_a11y
import dev.ccpocket.app.resources.approval_fab_label
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ApprovalQueueFabTest {
    @Test
    fun hiddenAtZeroAndVisibleWithStableCappedCount() = runComposeUiTest {
        val count = mutableStateOf(0)
        var clicked = false
        setContent { PocketTheme { ApprovalQueueFab(count.value, refreshing = false, onClick = { clicked = true }) } }
        val label = str(Res.string.approval_fab_label)
        assertTrue(!present(label))

        runOnIdle { count.value = 12 }
        mainClock.advanceTimeBy(200)
        waitForIdle()
        assertTrue(present(label))
        assertTrue(present("9+"))
        onAllNodes(hasContentDescription(str(Res.string.approval_fab_a11y, 12))).onFirst().performClick()
        assertTrue(clicked)
    }
}
