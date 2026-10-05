package dev.ccpocket.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ccpocket.app.advanceFrameAndWait
import dev.ccpocket.app.advanceTo
import dev.ccpocket.app.clickDisabledInside
import dev.ccpocket.app.clickThroughGuardWindow
import dev.ccpocket.app.tapSetup
import dev.ccpocket.app.data.ApprovalKey
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.allow
import dev.ccpocket.app.resources.allow_for_task
import dev.ccpocket.app.resources.allow_once
import dev.ccpocket.app.resources.allow_session_option
import dev.ccpocket.app.resources.deny
import dev.ccpocket.app.resources.perm_remember_session
import dev.ccpocket.app.resources.question_answer
import dev.ccpocket.app.resources.question_skip
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.approval.APPROVAL_ARRIVAL_GUARD_MS
import dev.ccpocket.app.ui.approval.ApprovalArrivalGuard
import dev.ccpocket.app.ui.approval.LocalApprovalArrivalGuard
import dev.ccpocket.protocol.AskOption
import dev.ccpocket.protocol.AskQuestion
import dev.ccpocket.protocol.PermissionAsk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop's double-tap guard (APPROVAL_ARRIVAL_GUARD_MS): the inline card in the chat pane and the tray /
 * bell / Windows flyout rows. The companion of [ApprovalDoubleClickUiTest], which covers the OTHER half — a
 * second click delivered before the card recomposes. Here the second click is late enough to hit the new card.
 *
 * Time is the Compose test clock only: the guard reads `mainClock.currentTime` and the arming delay runs on the
 * same virtual scheduler.
 */
@OptIn(ExperimentalTestApi::class)
class ApprovalArrivalGuardDesktopUiTest {

    /** A queue of asks behind the pane's card; every verb records which ask it decided, and how. */
    private class QueueModel(vararg asks: PermissionAsk) : SeedDesktopModel() {
        val queue = mutableStateListOf(*asks)
        val decided = mutableListOf<String>()
        override val ask: PermissionAsk? get() = queue.firstOrNull()
        override val askQueuePosition: Pair<Int, Int>? get() = null
        private fun decide(how: String) { queue.removeFirstOrNull()?.let { decided += "${it.askId}:$how" } }
        override fun resolve(allow: Boolean, remember: Boolean) = decide(if (!allow) "deny" else if (remember) "always" else "allow")
        override fun resolveTaskGrant() = decide("task")
        override fun retrySafer(constraints: List<String>) = decide("safer")
        override fun dismissAsk() = decide("dismiss")
        override fun answerQuestions(answers: Map<String, String>?, response: String?) = decide("answer")
        override fun skipQuestions(message: String) = decide("skip")
    }

    /** The fleet attention list behind the tray / bell / flyout; deciding a row removes it, so the next slides up. */
    private class RowsModel(vararg rows: DkAttention) : SeedDesktopModel() {
        val rows = mutableStateListOf(*rows)
        val decided = mutableListOf<String>()
        override val attention: List<DkAttention> get() = rows.toList()
        override fun resolveAttention(a: DkAttention, allow: Boolean) {
            if (rows.remove(a)) decided += "${a.id}:${if (allow) "allow" else "deny"}"
        }
    }

    private val window = APPROVAL_ARRIVAL_GUARD_MS

    private fun ask(id: String, cmd: String, grants: List<String>? = null) =
        PermissionAsk("c1", id, "Bash", cmd, title = "Run command", timeoutSec = 600, rule = "Bash($cmd)", grantOptions = grants)

    private fun question(id: String) = PermissionAsk(
        "c1", id, "AskUserQuestion", "q",
        questions = listOf(AskQuestion("Which color?", options = listOf(AskOption("Red"), AskOption("Blue")))),
    )

    private fun row(id: String, cmd: String) = DkAttention(
        id = id, accountId = "acct-1", machine = "studio", os = DkOs.MAC, tool = "Bash", preview = cmd,
        seconds = null, live = true, convoId = "c-$id",
    )

    private fun ComposeUiTest.guarded(guard: ApprovalArrivalGuard, content: @Composable () -> Unit) {
        setContent { PocketTheme { CompositionLocalProvider(LocalApprovalArrivalGuard provides guard) { content() } } }
        waitForIdle()
    }

    private fun ComposeUiTest.virtualGuard(): ApprovalArrivalGuard {
        mainClock.autoAdvance = false
        return ApprovalArrivalGuard(clock = { mainClock.currentTime })
    }

    private fun ComposeUiTest.button(label: String): SemanticsNodeInteraction = onAllNodes(hasText(label)).onFirst()

    /**
     * Let the first card's own window pass, decide it with [label], and return when the window of the request that
     * took its place ([next]) closes — read off the guard, so "inside the window" is exact.
     */
    private fun ComposeUiTest.decideFirst(guard: ApprovalArrivalGuard, label: String, next: ApprovalKey): Long {
        mainClock.advanceTimeBy(window + 100)
        waitForIdle()
        val clickedAt = mainClock.currentTime
        button(label).performClick()
        advanceFrameAndWait()
        val end = guard.armedAt(next)!!
        assertTrue(end - window >= clickedAt, "its window opened when it took the first card's place")
        return end
    }

    private fun ComposeUiTest.endWindow(end: Long) {
        advanceTo(end)
        advanceFrameAndWait()
    }

    // ── the inline card in the chat pane ─────────────────────────────────────────────────────────────

    @Test
    fun legacyCardRefusesDenyAllowAndAlwaysAllowOnTheNextCardOfABurst() = runComposeUiTest {
        val guard = virtualGuard()
        val model = QueueModel(ask("ask-1", "git status"), ask("ask-2", "rm -rf ~/work"))
        guarded(guard) { ChatPane(model) }
        val end = decideFirst(guard, str(Res.string.allow), ApprovalKey("c1", "ask-2"))
        assertEquals("ask-2", model.ask?.askId)

        val decisions = listOf(str(Res.string.deny), str(Res.string.allow))
        decisions.forEach { clickDisabledInside(it, end) }
        // ticking "remember" is not a decision (it stays live); Allow with it ticked is the always-allow variant
        tapSetup(str(Res.string.perm_remember_session))
        clickThroughGuardWindow(end, decisions)
        assertEquals(listOf("ask-1:allow"), model.decided, "no verdict of any kind for the unread card")

        endWindow(end)
        decisions.forEach { button(it).assertIsEnabled() }
        button(str(Res.string.allow)).performClick()
        waitForIdle()
        assertEquals("ask-2", model.decided.last().substringBefore(':'), "a deliberate click after the window works")
    }

    @Test
    fun grantAwareCardRefusesOnceTaskAndSessionOnTheNextCardOfABurst() = runComposeUiTest {
        val guard = virtualGuard()
        val grants = listOf("once", "task", "session")
        val model = QueueModel(ask("ask-1", "pnpm test", grants), ask("ask-2", "pnpm publish", grants))
        guarded(guard) { ChatPane(model) }
        val end = decideFirst(guard, str(Res.string.allow_for_task), ApprovalKey("c1", "ask-2"))

        // the ⋯ disclosure and the remember tick decide nothing — they open the session grant, which is held
        tapSetup("⋯")
        tapSetup(str(Res.string.perm_remember_session))
        val decisions = listOf(Res.string.deny, Res.string.allow_once, Res.string.allow_for_task, Res.string.allow_session_option).map { str(it) }
        clickThroughGuardWindow(end, decisions)
        assertEquals(listOf("ask-1:task"), model.decided)

        endWindow(end)
        decisions.forEach { button(it).assertIsEnabled() }
        button(str(Res.string.allow_session_option)).performClick()
        waitForIdle()
        assertEquals(listOf("ask-1:task", "ask-2:always"), model.decided)
    }

    @Test
    fun aQuestionTakingTheCardsPlaceHoldsSkipAndSubmit() = runComposeUiTest {
        val guard = virtualGuard()
        val model = QueueModel(ask("ask-1", "git status"), question("q-2"))
        guarded(guard) { ChatPane(model) }
        val end = decideFirst(guard, str(Res.string.allow), ApprovalKey("c1", "q-2"))
        assertEquals("q-2", model.ask?.askId)

        clickDisabledInside(str(Res.string.question_skip), end)
        tapSetup("Red") // picking an option is not a decision
        clickThroughGuardWindow(end, listOf(str(Res.string.question_skip), str(Res.string.question_answer)))
        assertEquals(listOf("ask-1:allow"), model.decided)

        endWindow(end)
        button(str(Res.string.question_answer)).assertIsEnabled()
        button(str(Res.string.question_answer)).performClick()
        waitForIdle()
        assertEquals(listOf("ask-1:allow", "q-2:answer"), model.decided)
    }

    @Test
    fun aCardRenderedAgainAfterItsWindowIsNotGuardedAgain() = runComposeUiTest {
        val guard = virtualGuard()
        val model = QueueModel(ask("ask-1", "git status"))
        guarded(guard) { ChatPane(model) }
        button(str(Res.string.allow)).assertIsNotEnabled() // the first card of a session is guarded too
        mainClock.advanceTimeBy(window)
        advanceFrameAndWait()
        button(str(Res.string.allow)).assertIsEnabled()

        // the same request re-emitted (new object, same identity): a recomposition, not a new card
        model.queue[0] = ask("ask-1", "git status").copy(timeoutSec = 900)
        advanceFrameAndWait()
        button(str(Res.string.allow)).assertIsEnabled()
        button(str(Res.string.allow)).performClick()
        waitForIdle()
        assertEquals(listOf("ask-1:allow"), model.decided)
    }

    // ── the fleet rows: deciding one slides the next into its place ──────────────────────────────────

    private fun rowsSlideUp(surface: @Composable (RowsModel) -> Unit) = runComposeUiTest {
        val guard = virtualGuard()
        val model = RowsModel(row("r1", "git status"), row("r2", "rm -rf ~/work"))
        guarded(guard) { surface(model) }
        val end = decideFirst(guard, str(Res.string.allow), ApprovalKey("c-r2", "r2"))
        assertEquals(listOf("r2"), model.rows.map { it.id })

        clickThroughGuardWindow(end, listOf(str(Res.string.deny), str(Res.string.allow)))
        assertEquals(listOf("r1:allow"), model.decided, "the row that slid up was not decided unread")

        endWindow(end)
        button(str(Res.string.allow)).assertIsEnabled()
        button(str(Res.string.allow)).performClick()
        waitForIdle()
        assertEquals(listOf("r1:allow", "r2:allow"), model.decided)
    }

    @Test
    fun trayPopoverRowsAreGuardedWhenTheNextSlidesUp() = rowsSlideUp { TrayPopover(it) }

    @Test
    fun bellPopoverRowsAreGuardedWhenTheNextSlidesUp() = rowsSlideUp { AttentionPopover(it) }

    @Test
    fun windowsFlyoutRowsAreGuardedWhenTheNextSlidesUp() = rowsSlideUp { WinTrayFlyout(it) }

    @Test
    fun aPopoverOpenedOnRequestsThatHaveLongBeenWaitingIsAnswerableAtOnce() = runComposeUiTest {
        val guard = virtualGuard()
        val waiting = row("r1", "make deploy")
        guard.noteKnown(waiting.arrivalKey()) // the app learned of it at t=0
        mainClock.advanceTimeBy(1_000)
        val model = RowsModel(waiting)
        guarded(guard) { TrayPopover(model) } // the user opens the tray a second later
        button(str(Res.string.allow)).assertIsEnabled()
        button(str(Res.string.allow)).performClick()
        waitForIdle()
        assertEquals(listOf("r1:allow"), model.decided)
    }
}
