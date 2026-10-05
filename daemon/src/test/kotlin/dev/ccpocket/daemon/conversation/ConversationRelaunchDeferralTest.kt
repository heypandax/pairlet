package dev.ccpocket.daemon.conversation

import dev.ccpocket.protocol.BackgroundJobs
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.JobStatus
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lifecycle design S7 / audit M1: a settings change (model / mode / effort / …) on a bake-at-launch backend
 * is applied by relaunching the process before the next prompt. That relaunch kills the process tree — and
 * with it any still-running background build or background sub-agent, and any question still waiting for
 * the user — silently. The relaunch now also waits for those, exactly like it already waits for a running
 * turn: the prompt rides the current process and the change stays pending until a send finds none of them.
 */
class ConversationRelaunchDeferralTest {

    private fun <T> withGrace(ms: String, body: () -> T): T {
        System.setProperty(Conversation.RELAUNCH_GRACE_PROP, ms)
        return try { body() } finally { System.clearProperty(Conversation.RELAUNCH_GRACE_PROP) }
    }

    private val script =
        "while IFS= read -r line; do case \"\$line\" in " +
            "*bg*) printf 'user:%s\\nbg-start\\nresult\\n' \"\$line\" ;; " +
            "*finish*) printf 'user:%s\\nbg-done\\nresult\\n' \"\$line\" ;; " +
            "*ask*) printf 'user:%s\\nresult\\nask:q1\\n' \"\$line\" ;; " +
            "*) printf 'user:%s\\nresult\\n' \"\$line\" ;; esac; done"

    @Test
    fun a_settings_relaunch_waits_for_running_background_work() = withGrace("0") {
        runBlocking {
            if (LifecycleHarness.isWindows()) return@runBlocking
            val backend = LifecycleBackend { _, _ -> script }
            // a short continuation grace: the settled background task arms one (issue #105), and this test
            // must get past it without waiting the production five minutes
            val h = LifecycleHarness(backend, "cS7bg", continuationGraceMs = 50)
            try {
                h.convo.open(resumeId = null, model = null)
                h.convo.sendPrompt("start bg", promptId = "1")
                h.await(what = "turn one") { h.framesOf<TurnDone>().size == 1 }
                assertTrue(h.convo.hasBackgroundWork())
                h.convo.switchEffort("low") // arms the next-turn relaunch

                h.convo.sendPrompt("hello", promptId = "2")
                h.await(what = "turn two") { h.framesOf<TurnDone>().size == 2 }
                assertEquals(1, backend.specs.size, "no relaunch while the background build runs: ${backend.specs}")
                assertTrue(backend.sends.any { it == 0 to "hello" }, "the prompt rides the current process")
                assertTrue(h.convo.hasBackgroundWork(), "the background build is still running")
                assertFalse(
                    h.framesOf<BackgroundJobs>().any { it.jobs.isEmpty() },
                    "the job panel was never wiped by a relaunch",
                )

                h.convo.sendPrompt("finish it", promptId = "3") // still running at send time → still deferred
                h.await(what = "turn three") { h.framesOf<TurnDone>().size == 3 }
                assertEquals(1, backend.specs.size)
                assertTrue(h.framesOf<BackgroundJobs>().last().jobs.all { it.status == JobStatus.DONE })
                delay(150) // past the continuation grace the completion armed

                h.convo.sendPrompt("after", promptId = "4") // nothing left running: the change applies now
                h.await(what = "the deferred relaunch") { backend.specs.size == 2 }
                assertEquals("low", backend.specs[1].effort)
                assertEquals("after", backend.specs[1].initialPrompt)
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun a_settings_relaunch_waits_for_a_pending_question() = withGrace("0") {
        runBlocking {
            if (LifecycleHarness.isWindows()) return@runBlocking
            val backend = LifecycleBackend { _, _ -> script }
            val h = LifecycleHarness(backend, "cS7ask")
            try {
                h.convo.open(resumeId = null, model = null)
                h.convo.sendPrompt("ask me", promptId = "1")
                h.await(what = "the pending ask") { h.convo.hasPendingAsk() }
                h.convo.switchEffort("low")

                h.convo.sendPrompt("hello", promptId = "2")
                h.await(what = "turn two") { h.framesOf<TurnDone>().size == 2 }
                assertEquals(1, backend.specs.size, "no relaunch while a question waits for the user")
                assertTrue(h.convo.hasPendingAsk(), "the question is still answerable")

                assertTrue(h.approvals.onVerdict(PermissionVerdict("cS7ask", "q1", Decision.DENY)))
                h.await(what = "the answer") { !h.convo.hasPendingAsk() }
                h.convo.sendPrompt("after", promptId = "3")
                h.await(what = "the deferred relaunch") { backend.specs.size == 2 }
                assertEquals("low", backend.specs[1].effort)
            } finally {
                h.close()
            }
        }
    }
}
