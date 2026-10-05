package dev.ccpocket.daemon.conversation

import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A settings change whose relaunch is held back by running background work or an unanswered question
 * (lifecycle design S7, [ConversationRelaunchDeferralTest]) used to be silent: the badge showed the new
 * value while the message actually went out on the old process. The deferral now leaves one line in the
 * chat saying what is not applied yet, why, and when it will be — and another when it finally applies.
 * Normal switches (nothing holding the relaunch) stay frame-for-frame as before.
 */
class ConversationDeferredSettingsNoticeTest {

    private fun <T> withGrace(ms: String, body: () -> T): T {
        System.setProperty(Conversation.RELAUNCH_GRACE_PROP, ms)
        return try { body() } finally { System.clearProperty(Conversation.RELAUNCH_GRACE_PROP) }
    }

    // same line protocol as ConversationRelaunchDeferralTest; prompts below avoid the trigger words
    private val script =
        "while IFS= read -r line; do case \"\$line\" in " +
            "*bg*) printf 'user:%s\\nbg-start\\nresult\\n' \"\$line\" ;; " +
            "*finish*) printf 'user:%s\\nbg-done\\nresult\\n' \"\$line\" ;; " +
            "*ask*) printf 'user:%s\\nresult\\nask:q1\\n' \"\$line\" ;; " +
            "*) printf 'user:%s\\nresult\\n' \"\$line\" ;; esac; done"

    private fun LifecycleHarness.texts(): List<String> =
        framesOf<AssistantChunk>().mapNotNull { (it.piece as? StreamPiece.Text)?.text }

    private fun LifecycleHarness.deferredNotices() = texts().filter { it.startsWith("⏳") }
    private fun LifecycleHarness.appliedNotices() = texts().filter { it.startsWith("✓ Now using") }

    @Test
    fun background_work_deferral_is_announced_once_and_its_application_too() = withGrace("0") {
        runBlocking {
            if (LifecycleHarness.isWindows()) return@runBlocking
            val backend = LifecycleBackend { _, _ -> script }
            val h = LifecycleHarness(backend, "cNoticeBg", continuationGraceMs = 50)
            try {
                h.convo.open(resumeId = null, model = "opus")
                h.convo.sendPrompt("start bg", promptId = "1")
                h.await(what = "turn one") { h.framesOf<TurnDone>().size == 1 }
                h.convo.switchModel("sonnet")

                h.convo.sendPrompt("hello", promptId = "2")
                h.await(what = "turn two") { h.framesOf<TurnDone>().size == 2 }
                assertEquals(1, backend.specs.size, "still deferred: ${backend.specs}")
                val first = h.deferredNotices()
                assertEquals(1, first.size, "one notice for the deferred switch: ${h.texts()}")
                assertTrue("Model change to sonnet" in first[0], first[0])
                assertTrue("background task" in first[0], first[0])
                assertTrue("sent with opus" in first[0], first[0])
                // the notice precedes the message's own turn, never trails its TurnDone
                assertTrue(
                    h.frames.indexOfFirst { it is AssistantChunk } < h.frames.indexOfLast { it is TurnDone },
                    "the notice leads the turn",
                )

                h.convo.sendPrompt("again", promptId = "3") // same deferred batch → no repeat
                h.await(what = "turn three") { h.framesOf<TurnDone>().size == 3 }
                assertEquals(1, h.deferredNotices().size, "no repeat for the same batch: ${h.texts()}")

                h.convo.switchEffort("low") // a NEW setting deferred meanwhile → announced, alone
                h.convo.sendPrompt("more", promptId = "4")
                h.await(what = "turn four") { h.framesOf<TurnDone>().size == 4 }
                val notices = h.deferredNotices()
                assertEquals(2, notices.size, "the newly deferred setting is announced: ${h.texts()}")
                assertTrue("Reasoning effort change to low" in notices[1], notices[1])
                assertTrue("sonnet" !in notices[1], "the already-announced model is not repeated: ${notices[1]}")

                h.convo.sendPrompt("finish it", promptId = "5") // still running at send time → still deferred
                h.await(what = "turn five") { h.framesOf<TurnDone>().size == 5 }
                assertEquals(2, h.deferredNotices().size)
                assertEquals(1, backend.specs.size)
                delay(150) // past the continuation grace the completion armed

                h.convo.sendPrompt("after", promptId = "6")
                h.await(what = "the deferred relaunch") { backend.specs.size == 2 }
                h.await(what = "turn six") { h.framesOf<TurnDone>().size == 6 }
                assertEquals("sonnet", backend.specs[1].model)
                assertEquals("low", backend.specs[1].effort)
                val applied = h.appliedNotices()
                assertEquals(listOf("✓ Now using sonnet, reasoning effort low.\n\n"), applied, h.texts().toString())
                assertEquals(2, h.deferredNotices().size, "no deferral notice once it applies")
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun a_failed_relaunch_claims_nothing_and_the_next_successful_launch_confirms() = withGrace("0") {
        runBlocking {
            if (LifecycleHarness.isWindows()) return@runBlocking
            // launch #1 is the deferred settings relaunch: its attach throws, so the process never goes live
            val backend = LifecycleBackend(attachThrowsAt = setOf(1)) { _, _ -> script }
            val h = LifecycleHarness(backend, "cNoticeFail", continuationGraceMs = 50)
            try {
                h.convo.open(resumeId = null, model = "opus")
                h.convo.sendPrompt("start bg", promptId = "1")
                h.await(what = "turn one") { h.framesOf<TurnDone>().size == 1 }
                h.convo.switchModel("sonnet")
                h.convo.sendPrompt("hello", promptId = "2")
                h.await(what = "turn two") { h.framesOf<TurnDone>().size == 2 }
                h.convo.sendPrompt("finish it", promptId = "3")
                h.await(what = "turn three") { h.framesOf<TurnDone>().size == 3 }
                assertEquals(1, h.deferredNotices().size)
                delay(150) // past the continuation grace the completion armed

                h.convo.sendPrompt("after", promptId = "4") // the deferred relaunch — fails
                h.await(what = "the relaunch failure") { h.framesOf<PocketError>().isNotEmpty() }
                assertEquals(2, backend.specs.size)
                assertEquals(emptyList(), h.appliedNotices(), "a failed launch must not claim the new settings: ${h.texts()}")

                h.convo.sendPrompt("retry", promptId = "5") // the next launch succeeds and bakes them
                h.await(what = "the successful launch") { backend.specs.size == 3 }
                h.await(what = "its turn") { h.framesOf<TurnDone>().size == 4 }
                assertEquals("sonnet", backend.specs[2].model)
                assertEquals(listOf("✓ Now using sonnet.\n\n"), h.appliedNotices(), h.texts().toString())
                val notice = h.frames.indexOfFirst { it is AssistantChunk && (it.piece as? StreamPiece.Text)?.text?.startsWith("✓ Now using") == true }
                assertTrue(notice > h.frames.indexOfFirst { it is PocketError }, "confirmed only after the failure")
                assertTrue(notice < h.frames.indexOfLast { it is TurnDone }, "and ahead of the turn it explains")
                assertEquals(1, h.deferredNotices().size)
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun pending_question_deferral_is_announced_with_its_own_reason() = withGrace("0") {
        runBlocking {
            if (LifecycleHarness.isWindows()) return@runBlocking
            val backend = LifecycleBackend { _, _ -> script }
            val h = LifecycleHarness(backend, "cNoticeAsk")
            try {
                h.convo.open(resumeId = null, model = null)
                h.convo.sendPrompt("ask me", promptId = "1")
                h.await(what = "the pending ask") { h.convo.hasPendingAsk() }
                h.convo.switchEffort("low")

                h.convo.sendPrompt("hello", promptId = "2")
                h.await(what = "turn two") { h.framesOf<TurnDone>().size == 2 }
                assertEquals(1, backend.specs.size)
                val notices = h.deferredNotices()
                assertEquals(1, notices.size, h.texts().toString())
                assertTrue("Reasoning effort change to low" in notices[0], notices[0])
                assertTrue("question is answered" in notices[0], notices[0])
                assertTrue("background" !in notices[0], notices[0])
                assertTrue("sent with reasoning effort default" in notices[0], notices[0])

                assertTrue(h.approvals.onVerdict(PermissionVerdict("cNoticeAsk", "q1", Decision.DENY)))
                h.await(what = "the answer") { !h.convo.hasPendingAsk() }
                h.convo.sendPrompt("after", promptId = "3")
                h.await(what = "the deferred relaunch") { backend.specs.size == 2 }
                assertEquals(listOf("✓ Now using reasoning effort low.\n\n"), h.appliedNotices())
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun an_undeferred_switch_emits_nothing_new() = withGrace("0") {
        runBlocking {
            if (LifecycleHarness.isWindows()) return@runBlocking
            val backend = LifecycleBackend { _, _ -> script }
            val h = LifecycleHarness(backend, "cNoticeNone")
            try {
                h.convo.open(resumeId = null, model = "opus")
                h.convo.sendPrompt("hello", promptId = "1")
                h.await(what = "turn one") { h.framesOf<TurnDone>().size == 1 }
                h.convo.switchModel("sonnet")
                h.convo.sendPrompt("again", promptId = "2")
                h.await(what = "the relaunch") { backend.specs.size == 2 }
                h.await(what = "turn two") { h.framesOf<TurnDone>().size == 2 }
                assertEquals("sonnet", backend.specs[1].model)
                // the fake agent never speaks, so before this change no AssistantChunk existed here at all
                assertEquals(emptyList(), h.framesOf<AssistantChunk>(), "no extra line on a normal switch")
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun clear_during_a_deferral_resets_it_without_a_stray_line() = withGrace("0") {
        runBlocking {
            if (LifecycleHarness.isWindows()) return@runBlocking
            val backend = LifecycleBackend { _, _ -> script }
            val h = LifecycleHarness(backend, "cNoticeClear", continuationGraceMs = 50)
            try {
                h.convo.open(resumeId = null, model = "opus")
                h.convo.sendPrompt("start bg", promptId = "1")
                h.await(what = "turn one") { h.framesOf<TurnDone>().size == 1 }
                h.convo.switchModel("sonnet")
                h.convo.sendPrompt("hello", promptId = "2")
                h.await(what = "turn two") { h.framesOf<TurnDone>().size == 2 }
                assertEquals(1, h.deferredNotices().size)

                h.convo.sendPrompt("/clear", promptId = "3")
                h.await(what = "the clear relaunch") { backend.specs.size == 2 }
                assertEquals("sonnet", backend.specs[1].model, "the clear bakes the current settings")

                // a fresh, ordinary switch afterwards: relaunches silently — nothing left over from the old batch
                h.convo.sendPrompt("plain", promptId = "4")
                h.await(what = "a turn on the cleared session") { backend.sends.any { it == 1 to "plain" } }
                h.await(what = "its turn end") { h.framesOf<TurnDone>().count() >= 3 }
                delay(100)
                h.convo.switchEffort("high")
                h.convo.sendPrompt("again", promptId = "5")
                h.await(what = "the ordinary relaunch") { backend.specs.size == 3 }
                assertEquals(1, h.deferredNotices().size, h.texts().toString())
                assertEquals(emptyList(), h.appliedNotices(), "a cleared deferral never claims to apply")
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun close_during_a_deferral_emits_nothing_more() = withGrace("0") {
        runBlocking {
            if (LifecycleHarness.isWindows()) return@runBlocking
            val backend = LifecycleBackend { _, _ -> script }
            val h = LifecycleHarness(backend, "cNoticeClose", continuationGraceMs = 50)
            try {
                h.convo.open(resumeId = null, model = "opus")
                h.convo.sendPrompt("start bg", promptId = "1")
                h.await(what = "turn one") { h.framesOf<TurnDone>().size == 1 }
                h.convo.switchModel("sonnet")
                h.convo.sendPrompt("hello", promptId = "2")
                h.await(what = "turn two") { h.framesOf<TurnDone>().size == 2 }
                assertEquals(1, h.deferredNotices().size)

                h.convo.close()
                h.convo.sendPrompt("after", promptId = "3") // a closed conversation runs nothing
                h.await(what = "the refusal") { h.framesOf<PocketError>().isNotEmpty() }
                assertEquals(1, h.deferredNotices().size, h.texts().toString())
                assertEquals(emptyList(), h.appliedNotices())
                assertEquals(1, backend.specs.size)
            } finally {
                h.close()
            }
        }
    }
}
