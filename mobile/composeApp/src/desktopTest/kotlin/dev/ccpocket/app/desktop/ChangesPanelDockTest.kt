package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Changes browser DOCKS beside the chat instead of floating over it: the conversation stays on
 * screen and usable while a diff is open, and the model does not count the panel as a modal overlay
 * (so Esc from the composer, drag-to-split and the terminal interop keep working).
 */
@OptIn(ExperimentalTestApi::class)
class ChangesPanelDockTest {
    @Test
    fun changesPanelDocksBesideChatAndIsNotAnOverlay() {
        val model = object : SeedDesktopModel() {
            override val watch: DkWatch? = null
            override val messages = listOf(ChatItem.Assistant("改完了，看看 diff"))
            override val ask = null
            override val streaming = false
        }
        runDesktopComposeUiTest(width = 1180, height = 798) {
            setContent { PocketTheme { DesktopApp(model) } }
            waitForIdle()
            onNodeWithTag("changes-panel").assertDoesNotExist()
            model.toggleChanges()
            waitForIdle()
            onNodeWithTag("changes-panel").assertIsDisplayed()
            onNodeWithTag("changes-divider").assertIsDisplayed()
            assertFalse(model.anyOverlayOpen, "a docked panel is not a modal overlay")
            // the chat is still there and still takes input
            onAllNodes(hasSetTextAction()).onFirst().performTextInput("继续讨论")
            waitForIdle()
            assertEquals("继续讨论", model.composer)
            // the pill is a toggle now
            model.toggleChanges()
            waitForIdle()
            onNodeWithTag("changes-panel").assertDoesNotExist()
            assertTrue(model.composer.isNotEmpty(), "closing the dock must not reset the composer")
        }
    }
}
