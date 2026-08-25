package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.action_retry
import dev.ccpocket.app.resources.chat_no_session
import dev.ccpocket.app.resources.chat_open_failed_hint
import dev.ccpocket.app.resources.chat_open_failed_named
import dev.ccpocket.app.resources.chat_opening_named
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Issue #235 — the desktop's side of "clicking a session gives no answer".
 *
 * Two claims, both about the click the user actually makes:
 *  · the sidebar's main session row is one live hit target — the FIRST click selects, exactly once (the
 *    rows are wrapped in a [androidx.compose.foundation.ContextMenuArea] whenever the project has groups,
 *    a rename-capable connection, or archiving, so this pins that the wrapper doesn't swallow the press);
 *  · the main pane never falls back to the blank "No session open" state while an open is in flight or
 *    after it failed — that empty state reads as "your click never happened", which is the report.
 */
@OptIn(ExperimentalTestApi::class)
class SessionOpenFeedbackUiTest {

    /** A seed whose chat is CLOSED, so [ChatPane] renders its no-chat branch. */
    private class NoChatModel(
        override val opening: Boolean = false,
        override val openFailed: Boolean = false,
    ) : SeedDesktopModel() {
        override val hasChat = false
        var retries = 0
            private set
        override fun retryOpen() { retries++ }
    }

    /** Counts what the sidebar dispatches, without changing what it renders. */
    private class ClickCountingModel : SeedDesktopModel() {
        val picked = mutableListOf<String>()
        override fun selectSession(s: DkSession) {
            picked += s.sessionId
            super.selectSession(s)
        }
    }

    @Test
    fun theFirstClickOnASessionRowSelectsItExactlyOnce() = runComposeUiTest {
        val model = ClickCountingModel()
        setContent { PocketTheme { Sidebar(model) } }
        waitForIdle()

        // "Tidy CI workflow" is a current-project row: it carries the group menu AND the archive entries,
        // so it is rendered through the ContextMenuArea wrapper — the row shape this test exists for.
        onNodeWithTag("sidebar-list").performScrollToNode(hasText("Tidy CI workflow"))
        waitForIdle()
        onAllNodes(hasText("Tidy CI workflow")).onFirst().performClick()
        waitForIdle()

        assertEquals(listOf("s3"), model.picked, "one click on the main row = one selection")
    }

    @Test
    fun anOpenInFlightNamesItsTargetInsteadOfTheEmptyState() = runComposeUiTest {
        val model = NoChatModel(opening = true)
        setContent { PocketTheme { ChatPane(model) } }
        waitForIdle()

        assertPresent(str(Res.string.chat_opening_named, model.chatTitle))
        assertFalse(present(str(Res.string.chat_no_session)), "an open in flight is not an empty pane")
    }

    @Test
    fun aFailedOpenExplainsItselfAndOffersARetry() = runComposeUiTest {
        val model = NoChatModel(openFailed = true)
        setContent { PocketTheme { ChatPane(model) } }
        waitForIdle()

        assertPresent(str(Res.string.chat_open_failed_named, model.chatTitle)) // which session failed
        assertPresent(str(Res.string.chat_open_failed_hint))                   // why nothing happened
        assertFalse(present(str(Res.string.chat_no_session)), "a failed open must not read as 'nothing was asked'")

        onAllNodes(hasText(str(Res.string.action_retry))).onFirst().performClick()
        waitForIdle()
        assertEquals(1, model.retries, "retry must re-issue the open")
    }

    /** The ordinary case is untouched: nothing open, nothing pending → the real empty state. */
    @Test
    fun anIdlePaneStillShowsTheEmptyState() = runComposeUiTest {
        setContent { PocketTheme { ChatPane(NoChatModel()) } }
        waitForIdle()

        assertPresent(str(Res.string.chat_no_session))
        assertFalse(present(str(Res.string.chat_open_failed_hint)))
    }
}
