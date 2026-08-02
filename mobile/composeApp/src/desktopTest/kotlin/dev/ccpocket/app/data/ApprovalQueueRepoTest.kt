package dev.ccpocket.app.data

import dev.ccpocket.protocol.AskQuestion
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.PendingApproval
import dev.ccpocket.protocol.PendingApprovals
import dev.ccpocket.protocol.PermissionAsk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApprovalQueueRepoTest {
    @Test
    fun accountWideSnapshotIsIndependentOfTheOpenConversationAndFreshEmptyClearsIt() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        val ask = PermissionAsk("bridge-convo", "ask-1", "FeishuRequest", "run tests", timeoutSec = 600)

        repo.receiveForTest(PendingApprovals(listOf(PendingApproval(ask, expiresAt = 123_456, workdir = "/repo"))))

        assertEquals(ask, repo.pendingApprovals[ApprovalKey("bridge-convo", "ask-1")]?.ask)
        assertEquals("/repo", repo.pendingApprovals[ApprovalKey("bridge-convo", "ask-1")]?.workdir)
        assertNull(repo.pendingAsk.value, "an account-wide ask must not masquerade as the current chat sheet")

        repo.receiveForTest(PendingApprovals())
        assertTrue(repo.pendingApprovals.isEmpty(), "only a fresh daemon snapshot declares all clear")
        scope.cancel()
    }

    // ── approval design M1: same-session asks queue behind the current card instead of overwriting ──

    @Test
    fun sameSessionAsksQueueInOrderAndNeverOverwrite() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.convoId.value = "c1"
        val a1 = PermissionAsk("c1", "ask-1", "Bash", "git status", timeoutSec = 60)
        val a2 = PermissionAsk("c1", "ask-2", "Bash", "git diff", timeoutSec = 60)
        val a3 = PermissionAsk("c1", "ask-3", "Write", "notes.md", timeoutSec = 60)

        repo.receiveForTest(a1)
        assertEquals("ask-1", repo.pendingAsk.value?.askId)
        assertNull(repo.askQueueProgress.value, "a single ask renders the plain sheet — no n/m chip")

        repo.receiveForTest(a2)
        repo.receiveForTest(a3)
        assertEquals("ask-1", repo.pendingAsk.value?.askId, "later asks must not overwrite the current card")
        assertEquals(1 to 3, repo.askQueueProgress.value)

        // duplicate delivery (reattach resurface) is idempotent — no double-queue, no burst inflation
        repo.receiveForTest(a2)
        assertEquals(1 to 3, repo.askQueueProgress.value)

        // resolving the head surfaces the next in arrival order
        repo.resolve(dev.ccpocket.protocol.Decision.ALLOW)
        assertEquals("ask-2", repo.pendingAsk.value?.askId)
        assertEquals(2 to 3, repo.askQueueProgress.value)
        repo.resolve(dev.ccpocket.protocol.Decision.DENY)
        assertEquals("ask-3", repo.pendingAsk.value?.askId)
        assertEquals(3 to 3, repo.askQueueProgress.value)
        repo.resolve(dev.ccpocket.protocol.Decision.ALLOW)
        assertNull(repo.pendingAsk.value)
        assertNull(repo.askQueueProgress.value, "draining the burst resets the chip")
        scope.cancel()
    }

    @Test
    fun withdrawingAQueuedAskDropsItSilentlyAndKeepsTheCountHonest() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.convoId.value = "c1"
        repo.receiveForTest(PermissionAsk("c1", "ask-1", "Bash", "git status", timeoutSec = 60))
        repo.receiveForTest(PermissionAsk("c1", "ask-2", "Bash", "git diff", timeoutSec = 60))
        repo.receiveForTest(PermissionAsk("c1", "ask-3", "Write", "notes.md", timeoutSec = 60))

        // the agent cancelled a card the user never saw — it must never surface
        repo.receiveForTest(AskWithdrawn("c1", "ask-2"))
        assertEquals(1 to 2, repo.askQueueProgress.value, "the burst shrinks with the withdrawn card")
        repo.resolve(dev.ccpocket.protocol.Decision.ALLOW)
        assertEquals("ask-3", repo.pendingAsk.value?.askId, "the withdrawn card is skipped")
        repo.resolve(dev.ccpocket.protocol.Decision.ALLOW)
        assertNull(repo.pendingAsk.value)
        scope.cancel()
    }

    @Test
    fun sameAskIdFromTwoSessionsStaysTwoInboxRows() {
        // §18.1 P1-3: askId is only unique per agent connection — Codex mints "1","2",… per session
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.receiveForTest(PermissionAsk("convo-a", "1", "Bash", "ls", timeoutSec = 60))
        repo.receiveForTest(PermissionAsk("convo-b", "1", "Bash", "rm -rf /", timeoutSec = 60))
        assertEquals(2, repo.pendingApprovals.size, "two sessions asking as \"1\" must stay two rows")

        // resolving one composite key never touches the other
        repo.resolvePendingApproval("convo-a", "1", allow = true)
        assertEquals(setOf(ApprovalKey("convo-b", "1")), repo.pendingApprovals.keys)

        // withdraw is composite too
        repo.receiveForTest(AskWithdrawn("convo-b", "1"))
        assertTrue(repo.pendingApprovals.isEmpty())
        scope.cancel()
    }

    @Test
    fun liveAskAndWithdrawalUpdateTheQueueEvenForAnotherConversationButQuestionsStayScoped() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.convoId.value = "visible-convo"
        val approval = PermissionAsk("other-convo", "ask-2", "Bash", "./gradlew test", timeoutSec = 60)

        repo.receiveForTest(approval)
        assertEquals(approval, repo.pendingApprovals[ApprovalKey(approval.convoId, approval.askId)]?.ask)
        assertNull(repo.pendingAsk.value)

        repo.receiveForTest(
            PermissionAsk(
                "other-convo", "question-1", "AskUserQuestion", "Which option?",
                questions = listOf(AskQuestion("Which option?")),
            ),
        )
        assertEquals(setOf(ApprovalKey("other-convo", "ask-2")), repo.pendingApprovals.keys)

        repo.receiveForTest(AskWithdrawn("other-convo", "ask-2"))
        assertTrue(repo.pendingApprovals.isEmpty())
        scope.cancel()
    }
}
