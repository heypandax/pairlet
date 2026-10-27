package dev.ccpocket.app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.quota_title_generic
import dev.ccpocket.app.theme.PocketTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class DesktopUsageSheetTest {
    @Test fun usage_opens_with_the_sidebar_collapsed() = checkSheet(collapsed = true)
    @Test fun usage_stays_open_when_the_sidebar_is_collapsed() = checkSheet(collapsed = false)

    private fun checkSheet(collapsed: Boolean) = runDesktopComposeUiTest(1180, 798) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = PocketRepository(scope)
            val model = object : SeedDesktopModel() {
                override val usageRepo = repo
                private var collapsedState by mutableStateOf(collapsed)
                override val sidebarCollapsed: Boolean get() = collapsedState
                override fun setSidebarCollapsed(v: Boolean) { collapsedState = v }
            }
            setContent { PocketTheme { DesktopApp(model) } }
            val title = runBlocking { getString(Res.string.quota_title_generic) }
            runOnIdle { repo.usageSheetRequested.value = true }
            onAllNodesWithText(title).assertCountEquals(1)
            onNodeWithText(title).assertIsDisplayed()
            if (!collapsed) {
                runOnIdle { model.setSidebarCollapsed(true) }
                mainClock.advanceTimeBy(SIDEBAR_ANIM_MS.toLong() + 100)
                waitForIdle()
                onAllNodesWithText(title).assertCountEquals(1)
                onNodeWithText(title).assertIsDisplayed()
            }
            onRoot().performMouseInput { click(Offset(center.x, 10f)) }
            runOnIdle { assertFalse(repo.usageSheetRequested.value) }
            onNodeWithText(title).assertDoesNotExist()
        } finally { scope.cancel() }
    }
}
