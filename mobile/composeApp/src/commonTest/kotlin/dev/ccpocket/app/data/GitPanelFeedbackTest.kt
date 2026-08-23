package dev.ccpocket.app.data

import dev.ccpocket.protocol.GIT_OP_FETCH
import dev.ccpocket.protocol.GIT_OP_PUSH
import dev.ccpocket.protocol.GitActionResult
import dev.ccpocket.protocol.GitStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two answers the panel owes a user after a remote verb (issue #280 真机反馈 1 + 5): what a fetch
 * just found, and whether a push failure is one we can advise about.
 */
class GitPanelFeedbackTest {

    private fun status(ahead: Int, behind: Int, upstream: String? = "origin/main", ok: Boolean = true) =
        GitStatus("c1", "/w", ok = ok, branch = "main", upstream = upstream, ahead = ahead, behind = behind)

    @Test
    fun a_fetch_that_found_new_commits_says_how_many_and_that_a_merge_is_available() {
        val r = gitFetchReport(status(ahead = 0, behind = 3))
        assertEquals(GitFetchOutcome.BEHIND, r.outcome)
        assertEquals(3, r.behind)
    }

    /** The reported state: ↑1↓1. The receipt must not promise a merge `pull --ff-only` will refuse. */
    @Test
    fun a_fetch_on_a_diverged_branch_reports_divergence_not_merge_available() {
        val r = gitFetchReport(status(ahead = 1, behind = 1))
        assertEquals(GitFetchOutcome.DIVERGED, r.outcome)
        assertEquals(1, r.ahead)
        assertEquals(1, r.behind)
    }

    @Test
    fun a_fetch_that_found_nothing_still_says_so() {
        assertEquals(GitFetchOutcome.UP_TO_DATE, gitFetchReport(status(ahead = 0, behind = 0)).outcome)
        // ahead-only is not "behind": there is nothing to merge, the local side simply has work to push
        assertEquals(GitFetchOutcome.UP_TO_DATE, gitFetchReport(status(ahead = 2, behind = 0)).outcome)
    }

    /** No snapshot (older daemon), no upstream, or a failed read: "done" and no invented numbers. */
    @Test
    fun without_a_usable_snapshot_the_receipt_claims_nothing() {
        assertEquals(GitFetchOutcome.DONE, gitFetchReport(null).outcome)
        assertEquals(GitFetchOutcome.DONE, gitFetchReport(status(0, 0, upstream = null)).outcome)
        assertEquals(GitFetchOutcome.DONE, gitFetchReport(status(0, 4, ok = false)).outcome)
    }

    @Test
    fun a_push_rejected_by_a_moved_remote_earns_our_advice() {
        val rejected = GitActionResult(
            "c1", GIT_OP_PUSH, ok = false,
            stderr = "! [rejected]        main -> main (fetch first)",
            error = "git push failed",
        )
        assertTrue(gitPushBlockedByRemote(rejected))
    }

    @Test
    fun other_push_failures_get_no_advice_and_neither_does_any_other_verb() {
        val auth = GitActionResult("c1", GIT_OP_PUSH, ok = false, stderr = "fatal: Authentication failed")
        assertFalse(gitPushBlockedByRemote(auth), "we do not know how to fix someone's credentials")
        val fetch = GitActionResult("c1", GIT_OP_FETCH, ok = false, stderr = "! [rejected] (fetch first)")
        assertFalse(gitPushBlockedByRemote(fetch), "the advice names push's way out, so it is push-only")
        val ok = GitActionResult("c1", GIT_OP_PUSH, ok = true, stderr = "")
        assertFalse(gitPushBlockedByRemote(ok))
    }
}
