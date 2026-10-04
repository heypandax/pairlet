package dev.ccpocket.app.data

import dev.ccpocket.protocol.AskQuestion
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit 2026-10-04 (mobile H1): a decision must answer the card the user SAW.
 *
 * The sheet's callbacks used to call `resolve(decision)` with no ask, and [PocketRepository.resolve] read
 * whatever was pending at click time — then advanced the queue synchronously. In a burst ("1 / 3") the next
 * card appears in exactly the same place, so a double tap (or a second click delivered before the sheet
 * recomposed) approved a command the user never read. Here both clicks reach the callback the FIRST card
 * was composed with, which is precisely what a stale tap does.
 */
class ApprovalClickBindingTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val repo = PocketRepository(scope)
    private val sent = mutableListOf<PermissionVerdict>()

    init {
        repo.onSendForTest = { if (it is PermissionVerdict) sent += it }
        repo.convoId.value = "c1"
    }

    @AfterTest fun tearDown() = scope.cancel()

    private val first = PermissionAsk("c1", "ask-1", "Bash", "git status", timeoutSec = 60, rule = "Bash(git status)")
    private val second = PermissionAsk("c1", "ask-2", "Bash", "rm -rf build", timeoutSec = 60, rule = "Bash(rm:*)")

    @Test
    fun aSecondClickOnTheSameCardNeverDecidesTheNextQueuedAsk() {
        repo.receiveForTest(first)
        repo.receiveForTest(second)
        val rendered = repo.pendingAsk.value!!

        repo.resolve(Decision.ALLOW, ask = rendered)
        repo.resolve(Decision.ALLOW, ask = rendered)

        assertEquals(listOf("ask-1"), sent.map { it.askId }, "only the card on screen is answered")
        assertEquals("ask-2", repo.pendingAsk.value?.askId, "the next card waits for its own decision")
        assertEquals(2 to 2, repo.askQueueProgress.value, "the burst advanced exactly once")
    }

    @Test
    fun aStaleAlwaysAllowWritesNoRuleForTheNextAsk() {
        repo.receiveForTest(first)
        repo.receiveForTest(second)
        val rendered = repo.pendingAsk.value!!

        repo.resolve(Decision.ALLOW, remember = true, grantScope = "session", ask = rendered)
        repo.resolve(Decision.ALLOW, remember = true, grantScope = "session", ask = rendered)

        assertEquals(listOf("Bash(git status)"), repo.allowRules.toList(), "no standing rule for an unread command")
        assertEquals(listOf("ask-1"), sent.map { it.askId })
    }

    @Test
    fun aStaleDismissDoesNotRetireTheNextCard() {
        repo.receiveForTest(first)
        repo.receiveForTest(second)
        val rendered = repo.pendingAsk.value!!

        repo.dismissAsk(rendered)
        repo.dismissAsk(rendered)

        assertEquals("ask-2", repo.pendingAsk.value?.askId, "a late Dismiss must not silently drop the next ask")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aStaleQuestionAnswerOrSkipDoesNotLandOnTheNextAsk() {
        val question = PermissionAsk(
            "c1", "q-1", "AskUserQuestion", "Which?", questions = listOf(AskQuestion("Which?")),
        )
        repo.receiveForTest(question)
        repo.receiveForTest(second)
        val rendered = repo.pendingAsk.value!!

        repo.answerQuestions(mapOf("Which?" to "A"), ask = rendered)
        repo.answerQuestions(mapOf("Which?" to "A"), ask = rendered)
        repo.resolve(Decision.DENY, message = "skipped", ask = rendered)

        assertEquals(listOf("q-1"), sent.map { it.askId }, "the approval behind the question is untouched")
        assertEquals("ask-2", repo.pendingAsk.value?.askId)
    }

    @Test
    fun aBoundClickStillAnswersTheCardWhenItIsCurrent() {
        repo.receiveForTest(first)
        // a re-emitted frame refreshes the card in place: same ids, new object — still the same request
        repo.receiveForTest(first.copy(timeoutSec = 90))

        repo.resolve(Decision.ALLOW, ask = first)

        assertEquals(listOf("ask-1"), sent.map { it.askId })
    }
}
