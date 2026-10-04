package dev.ccpocket.app.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.allow
import dev.ccpocket.app.resources.allow_for_task
import dev.ccpocket.app.resources.deny
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.PermissionAsk
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Audit 2026-10-04 (desktop M4): a double click on the inline approval card answers ONLY the card it hit.
 *
 * In a burst the next card renders in exactly the same place with the same buttons. A second click that
 * arrives before the card recomposes still reaches the first card's callbacks, which used to decide
 * "whatever is pending now" — i.e. the next, unread request. Both clicks are delivered here in ONE UI-thread
 * turn, so no frame can recompose the card between them: the second one hits the first card's callback.
 *
 * Not covered (and not claimed): a human double click whose second click lands after the next frame — the
 * card has recomposed by then and the click is a click on the new card. Closing that needs an arming delay.
 */
@OptIn(ExperimentalTestApi::class)
class ApprovalDoubleClickUiTest {

    /** A queue of asks behind the card; every verb records which ask it actually decided. */
    private class QueueModel(vararg asks: PermissionAsk) : SeedDesktopModel() {
        val queue = mutableStateListOf(*asks)
        val decided = mutableListOf<String>()
        override val ask: PermissionAsk? get() = queue.firstOrNull()
        override val askQueuePosition: Pair<Int, Int>? get() = null
        private fun decide() { queue.removeFirstOrNull()?.let { decided += it.askId } }
        override fun resolve(allow: Boolean, remember: Boolean) = decide()
        override fun resolveTaskGrant() = decide()
        override fun retrySafer(constraints: List<String>) = decide()
        override fun dismissAsk() = decide()
    }

    private fun ask(id: String, cmd: String, grants: List<String>? = null) =
        PermissionAsk("c1", id, "Bash", cmd, title = "Run command", timeoutSec = 60, grantOptions = grants)

    /** Two clicks on [label] with no frame between them — the second reaches the stale composition. */
    private fun ComposeUiTest.doubleClick(label: String) {
        val click = onAllNodes(hasText(label)).onFirst().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnUiThread { click(); click() }
        waitForIdle()
    }

    @Test
    fun doubleClickingAllowAnswersOnlyTheCardThatWasClicked() = runComposeUiTest {
        val model = QueueModel(ask("ask-1", "git status"), ask("ask-2", "rm -rf ~/work"))
        setContent { PocketTheme { ChatPane(model) } }
        waitForIdle()

        doubleClick(str(Res.string.allow))

        assertEquals(listOf("ask-1"), model.decided, "the unread second request must still be waiting")
        assertEquals("ask-2", model.ask?.askId)
    }

    @Test
    fun doubleClickingDenyAnswersOnlyTheCardThatWasClicked() = runComposeUiTest {
        val model = QueueModel(ask("ask-1", "git status"), ask("ask-2", "git push"))
        setContent { PocketTheme { ChatPane(model) } }
        waitForIdle()

        doubleClick(str(Res.string.deny))

        assertEquals(listOf("ask-1"), model.decided)
    }

    @Test
    fun doubleClickingAllowForTaskAnswersOnlyTheCardThatWasClicked() = runComposeUiTest {
        val grants = listOf("once", "task", "session")
        val model = QueueModel(ask("ask-1", "pnpm test", grants), ask("ask-2", "pnpm publish", grants))
        setContent { PocketTheme { ChatPane(model) } }
        waitForIdle()

        doubleClick(str(Res.string.allow_for_task))

        assertEquals(listOf("ask-1"), model.decided, "a task grant must not be issued for an unread request")
    }

    @Test
    fun theNextCardIsStillAnswerableOnceItIsOnScreen() = runComposeUiTest {
        val model = QueueModel(ask("ask-1", "git status"), ask("ask-2", "git diff"))
        setContent { PocketTheme { ChatPane(model) } }
        waitForIdle()

        onAllNodes(hasText(str(Res.string.allow))).onFirst().performClick()
        waitForIdle()
        onAllNodes(hasText(str(Res.string.allow))).onFirst().performClick()
        waitForIdle()

        assertEquals(listOf("ask-1", "ask-2"), model.decided, "a deliberate click on the new card still works")
    }
}
