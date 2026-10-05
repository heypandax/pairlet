package dev.ccpocket.app.ui.approval

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.allow_for_task
import dev.ccpocket.app.resources.allow_once
import dev.ccpocket.app.resources.allow_session_option
import dev.ccpocket.app.resources.always_allow
import dev.ccpocket.app.resources.ap_more_options
import dev.ccpocket.app.resources.deny
import dev.ccpocket.app.resources.question_answer
import dev.ccpocket.app.resources.question_skip
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.QuestionCard
import dev.ccpocket.app.ui.RepoSecureApprovalSheet
import dev.ccpocket.protocol.AskOption
import dev.ccpocket.protocol.AskQuestion
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.isQuestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone's double-tap guard (APPROVAL_ARRIVAL_GUARD_MS), through the REAL wiring: the root's
 * [ProvideApprovalArrivalGuard] over a [PocketRepository], and [RepoSecureApprovalSheet] on top, so "nothing
 * happened" is asserted on the verdict frames the repository would send and on the standing rules it would write
 * — not on a fake callback.
 *
 * Time is the Compose test clock and nothing else: the guard reads `mainClock.currentTime`, and the card's arming
 * delay runs on the same virtual scheduler, so every instant below is exact.
 */
@OptIn(ExperimentalTestApi::class)
class ApprovalArrivalGuardUiTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val repo = PocketRepository(scope)
    private val sent = mutableListOf<PermissionVerdict>()

    init {
        repo.onSendForTest = { if (it is PermissionVerdict) sent += it }
        repo.convoId.value = "c1"
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun ask(id: String, cmd: String, grants: List<String>?, convo: String = "c1") = PermissionAsk(
        convo, id, "Bash", cmd, title = "Run command", timeoutSec = 600, rule = "Bash($cmd)", grantOptions = grants,
    )

    private lateinit var guard: ApprovalArrivalGuard

    /** Freeze the clock and build the app's guard on it. */
    private fun ComposeUiTest.virtualClock() {
        mainClock.autoAdvance = false
        guard = ApprovalArrivalGuard(clock = { mainClock.currentTime })
    }

    private var mounted by mutableStateOf(true)

    /** The phone root's approval branch: the focused ask, under the root's guard (fed with arrival times). */
    private fun ComposeUiTest.showFocusedSheet() {
        setContent {
            PocketTheme {
                ProvideApprovalArrivalGuard(repo, guard) {
                    if (mounted) repo.pendingAsk.value?.takeIf { !it.isQuestion }?.let { RepoSecureApprovalSheet(repo, it) }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.button(label: String): SemanticsNodeInteraction =
        onAllNodes(hasText(label)).onFirst()

    /** When [id]'s card arms — read off the guard, so "inside the window" is exact, not inferred. */
    private fun windowEnd(id: String, convo: String = "c1"): Long = guard.armedAt(ApprovalKey(convo, id))!!

    /** First card decided after its own window; returns when the second card's window closes. */
    private fun ComposeUiTest.decideFirstCard(): Long {
        mainClock.advanceTimeBy(APPROVAL_ARRIVAL_GUARD_MS + 100)
        waitForIdle()
        val clickedAt = mainClock.currentTime
        button(str(Res.string.allow_once)).performClick()
        advanceFrameAndWait()
        assertEquals("ask-2", repo.pendingAsk.value?.askId, "the burst advanced to the unread card")
        val end = windowEnd("ask-2")
        assertTrue(end - window >= clickedAt, "its window opened when it took the first card's place")
        return end
    }

    private val window = APPROVAL_ARRIVAL_GUARD_MS

    @Test
    fun legacyBurstRefusesEveryDecisionOnTheNextCardForTheWholeWindow() = runComposeUiTest {
        virtualClock()
        // both arrive at t=0: by the time the second shows it has been known for >400 ms — and is STILL guarded,
        // because it replaces a card instead of being opened on purpose
        repo.receiveForTest(ask("ask-1", "git status", grants = null))
        repo.receiveForTest(ask("ask-2", "rm -rf ~/work", grants = null))
        showFocusedSheet()
        val end = decideFirstCard()
        val rulesBefore = repo.allowRules.toList()

        val decisions = listOf(Res.string.deny, Res.string.allow_once, Res.string.always_allow).map { str(it) }
        clickThroughGuardWindow(end, decisions)
        assertEquals(listOf("ask-1"), sent.map { it.askId }, "no verdict frame for the unread card")
        assertEquals(rulesBefore, repo.allowRules.toList(), "no standing rule written for it either")

        advanceTo(end)
        advanceFrameAndWait()
        decisions.forEach { button(it).assertIsEnabled() }
        button(str(Res.string.always_allow)).performClick()
        assertEquals(listOf("ask-1", "ask-2"), sent.map { it.askId }, "a deliberate click after the window works")
        assertTrue(sent.last().remember, "and it is the variant that was clicked")
        assertEquals(rulesBefore + "Bash(rm -rf ~/work)", repo.allowRules.toList())
    }

    @Test
    fun grantAwareBurstRefusesTaskAndSessionGrantsOnTheNextCard() = runComposeUiTest {
        virtualClock()
        val grants = listOf("once", "task", "session")
        repo.receiveForTest(ask("ask-1", "pnpm test", grants))
        repo.receiveForTest(ask("ask-2", "pnpm publish", grants))
        showFocusedSheet()
        val end = decideFirstCard()

        // the deny/once/task grid, held from the instant the card took the place
        clickDisabledInside(str(Res.string.deny), end)
        // revealing the session scope decides nothing, so the disclosure itself stays live …
        tapSetup(str(Res.string.ap_more_options))
        // … and the session grant it reveals is held like every other decision
        val decisions = listOf(Res.string.deny, Res.string.allow_once, Res.string.allow_for_task, Res.string.allow_session_option)
            .map { str(it) }
        clickThroughGuardWindow(end, decisions)
        assertEquals(listOf("ask-1"), sent.map { it.askId })
        assertTrue(repo.allowRules.isEmpty(), "no session rule for an unread command")

        advanceTo(end)
        advanceFrameAndWait()
        decisions.forEach { button(it).assertIsEnabled() }
        button(str(Res.string.allow_for_task)).performClick()
        assertEquals(listOf("ask-1", "ask-2"), sent.map { it.askId })
        assertEquals("task", sent.last().grantScope)
    }

    @Test
    fun theFirstCardOfASessionIsGuardedTooAndARenderedCardIsNeverReGuarded() = runComposeUiTest {
        virtualClock()
        repo.receiveForTest(ask("ask-1", "git status", grants = null))
        showFocusedSheet()
        button(str(Res.string.allow_once)).assertIsNotEnabled()

        mainClock.advanceTimeBy(window)
        advanceFrameAndWait()
        button(str(Res.string.allow_once)).assertIsEnabled()

        // a re-emitted frame refreshes the same card in place (a recomposition with a new value)
        repo.receiveForTest(ask("ask-1", "git status", grants = null).copy(timeoutSec = 900))
        advanceFrameAndWait()
        button(str(Res.string.allow_once)).assertIsEnabled()

        // the sheet leaves composition and comes back (rotation, the app lock gate) — same request, no new window
        mounted = false
        advanceFrameAndWait()
        mounted = true
        advanceFrameAndWait()
        button(str(Res.string.allow_once)).assertIsEnabled()
        button(str(Res.string.allow_once)).performClick()
        assertEquals(listOf("ask-1"), sent.map { it.askId })
    }

    @Test
    fun aLongWaitingAskOpenedOnPurposeIsAnswerableAtOnce() = runComposeUiTest {
        virtualClock()
        val elsewhere = ask("ask-9", "make deploy", grants = null, convo = "c2")
        repo.receiveForTest(elsewhere) // the app learns of it while another session is open
        showFocusedSheet()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()

        // the user opens that session (push tap / inbox): the daemon resurfaces the ask on attach
        repo.convoId.value = "c2"
        repo.receiveForTest(elsewhere)
        advanceFrameAndWait()
        button(str(Res.string.allow_once)).assertIsEnabled()
        button(str(Res.string.allow_once)).performClick()
        assertEquals(listOf("ask-9"), sent.map { it.askId })
    }

    @Test
    fun anAskOpenedBeforeItHasWaitedTheWindowIsStillGuarded() = runComposeUiTest {
        virtualClock()
        val elsewhere = ask("ask-9", "make deploy", grants = null, convo = "c2")
        repo.receiveForTest(elsewhere)
        showFocusedSheet()
        mainClock.advanceTimeBy(100, ignoreFrameDuration = true)
        waitForIdle()

        repo.convoId.value = "c2"
        repo.receiveForTest(elsewhere)
        advanceFrameAndWait()
        val end = windowEnd("ask-9", convo = "c2")
        clickThroughGuardWindow(end, listOf(str(Res.string.allow_once)))
        assertTrue(sent.isEmpty())

        advanceTo(end)
        advanceFrameAndWait()
        button(str(Res.string.allow_once)).performClick()
        assertEquals(listOf("ask-9"), sent.map { it.askId })
    }

    @Test
    fun theQuestionCardThatTakesTheNextPlaceHoldsSkipAndSubmit() = runComposeUiTest {
        virtualClock()
        fun question(id: String) = PermissionAsk(
            "c1", id, "AskUserQuestion", "q",
            questions = listOf(AskQuestion("Which color?", options = listOf(AskOption("Red"), AskOption("Blue")))),
        )
        var current by mutableStateOf(question("q-1"))
        val skipped = mutableListOf<String>()
        val answered = mutableListOf<String>()
        setContent {
            PocketTheme {
                CompositionLocalProvider(LocalApprovalArrivalGuard provides guard) {
                    val shown = current
                    QuestionCard(
                        shown,
                        onAnswer = { _, _ -> answered += shown.askId },
                        onSkip = { skipped += shown.askId; current = question("q-2") },
                    )
                }
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(window + 100)
        waitForIdle()
        button(str(Res.string.question_skip)).performClick()
        advanceFrameAndWait()
        val end = guard.armedAt(ApprovalKey("c1", "q-2"))!!
        assertEquals(listOf("q-1"), skipped)

        clickDisabledInside(str(Res.string.question_skip), end)
        // picking an option decides nothing and stays live; Skip and Submit are held
        tapSetup("Red")
        clickThroughGuardWindow(end, listOf(str(Res.string.question_skip), str(Res.string.question_answer)))
        assertEquals(listOf("q-1"), skipped)
        assertTrue(answered.isEmpty())

        advanceTo(end)
        advanceFrameAndWait()
        button(str(Res.string.question_answer)).assertIsEnabled()
        button(str(Res.string.question_answer)).performClick()
        assertEquals(listOf("q-2"), answered)
    }
}
