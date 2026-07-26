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

        assertEquals(ask, repo.pendingApprovals["ask-1"]?.ask)
        assertEquals("/repo", repo.pendingApprovals["ask-1"]?.workdir)
        assertNull(repo.pendingAsk.value, "an account-wide ask must not masquerade as the current chat sheet")

        repo.receiveForTest(PendingApprovals())
        assertTrue(repo.pendingApprovals.isEmpty(), "only a fresh daemon snapshot declares all clear")
        scope.cancel()
    }

    @Test
    fun liveAskAndWithdrawalUpdateTheQueueEvenForAnotherConversationButQuestionsStayScoped() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.convoId.value = "visible-convo"
        val approval = PermissionAsk("other-convo", "ask-2", "Bash", "./gradlew test", timeoutSec = 60)

        repo.receiveForTest(approval)
        assertEquals(approval, repo.pendingApprovals[approval.askId]?.ask)
        assertNull(repo.pendingAsk.value)

        repo.receiveForTest(
            PermissionAsk(
                "other-convo", "question-1", "AskUserQuestion", "Which option?",
                questions = listOf(AskQuestion("Which option?")),
            ),
        )
        assertEquals(setOf("ask-2"), repo.pendingApprovals.keys)

        repo.receiveForTest(AskWithdrawn("other-convo", "ask-2"))
        assertTrue(repo.pendingApprovals.isEmpty())
        scope.cancel()
    }
}
