package dev.ccpocket.app.data

import dev.ccpocket.protocol.ApprovalGrantMutationResult
import dev.ccpocket.protocol.AuthorizedActionRecorded
import dev.ccpocket.protocol.ClearAllowRule
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.RevokeGrant
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApprovalGrantMutationTest {
    @Test
    fun allow_rule_is_removed_only_after_a_success_ack() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply { onSendForTest = { sent += it } }
        try {
            repo.receiveForTest(SessionLive("c1", "/tmp/project", "s1"))
            repo.allowRules += "git status"

            repo.clearRule("git status")
            val first = sent.filterIsInstance<ClearAllowRule>().last()
            assertNotNull(first.requestId)
            assertTrue("git status" in repo.allowRules, "queueing bytes is not authoritative success")

            repo.receiveForTest(ApprovalGrantMutationResult(first.requestId!!, "c1", success = false, error = "failed"))
            assertTrue("git status" in repo.allowRules, "a daemon failure must preserve local authorization state")

            repo.clearRule("git status")
            val second = sent.filterIsInstance<ClearAllowRule>().last()
            repo.receiveForTest(ApprovalGrantMutationResult(second.requestId!!, "c1", success = true))
            assertFalse("git status" in repo.allowRules)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun autorun_chip_shows_pending_then_only_confirms_on_success_ack() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply { onSendForTest = { sent += it } }
        try {
            repo.receiveForTest(SessionLive("c1", "/tmp/project", "s1"))
            repo.receiveForTest(
                AuthorizedActionRecorded(
                    convoId = "c1", eventId = "e1", actionSummary = "git status", basis = "task-grant",
                    decidedAt = 1L, matchedGrantId = "g1", tool = "Bash",
                ),
            )
            repo.tightenAutoRun(repo.messages.filterIsInstance<ChatItem.AutoRun>().single())
            val request = sent.filterIsInstance<RevokeGrant>().single()
            val pending = repo.messages.filterIsInstance<ChatItem.AutoRun>().single()
            assertTrue(pending.tightening)
            assertFalse(pending.tightened)

            repo.receiveForTest(ApprovalGrantMutationResult(request.requestId!!, "c1", success = true))
            val done = repo.messages.filterIsInstance<ChatItem.AutoRun>().single()
            assertFalse(done.tightening)
            assertTrue(done.tightened)
        } finally {
            scope.cancel()
        }
    }
}
