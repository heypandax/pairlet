package dev.ccpocket.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.compact_summary
import dev.ccpocket.app.theme.PocketTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class CompactSummaryCardTest {
    @Test fun summaryStartsCollapsedAndCanBeExpandedAndCollapsed() = runComposeUiTest {
        val label = runBlocking { getString(Res.string.compact_summary) }
        setContent { PocketTheme { CompactSummaryCard("complete summary fixture") } }
        waitForIdle()
        assertFalse(present("complete summary fixture"))
        onNodeWithText("▸ $label").performClick()
        assertPresent("complete summary fixture")
        onNodeWithText("▾ $label").performClick()
        assertFalse(present("complete summary fixture"))
    }
}
