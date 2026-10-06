package dev.ccpocket.daemon.transcribe

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.TextEdit
import dev.ccpocket.protocol.TranscriptRefine
import dev.ccpocket.protocol.TranscriptRefineError
import dev.ccpocket.protocol.TranscriptRefineLimits
import dev.ccpocket.protocol.TranscriptRefined
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Voice input v2's refine service with fake refiners and virtual time — no CLI is started and no test waits out the
 * 12 s limit for real. Pins the one-answer-per-request contract (or none, when the phone cancelled), the
 * agent-selection rule, the length cap and the no-text-in-logs rule.
 */
class TranscriptRefineServiceTest {

    private class Inbox : OutboundSink {
        val frames = CopyOnWriteArrayList<TranscriptRefined>()
        override suspend fun emit(frame: Frame) { frames += frame as TranscriptRefined }
        fun only(): TranscriptRefined = frames.single()
    }

    private val claude = FakeTranscriptRefiner()
    @Volatile private var sessionAgent: AgentKind? = AgentKind.CLAUDE
    @Volatile private var glossary: List<String> = GLOSSARY

    private fun TestScope.service(refiners: TranscriptRefiners = TranscriptRefiners(listOf(claude))) =
        TranscriptRefineService(backgroundScope, refiners, agentOf = { sessionAgent }, glossaryOf = { glossary })

    private fun req(text: String = TEXT, capture: String = "cap-1", hint: String? = null, convo: String = "c-1") =
        TranscriptRefine(convo, capture, text, locale = "zh-Hans", agentHint = hint)

    @Test
    fun valid_edits_come_back_checked_on_the_asking_sink_only() = runTest {
        val s = service()
        val asking = Inbox(); val other = Inbox()
        s.onRefine(req(), asking)
        runCurrent()
        val r = asking.only()
        assertTrue(r.ok)
        assertEquals(CORRECTED, r.text)
        assertEquals(EDITS, r.edits)
        assertEquals("claude", r.agent)
        assertNull(r.error)
        assertEquals("c-1" to "cap-1", r.convoId to r.captureId)
        assertTrue(other.frames.isEmpty())
        // what the refiner was handed: the locale, the glossary and the hard limit as its budget
        assertEquals("zh-Hans", claude.lastLocale)
        assertEquals(GLOSSARY, claude.lastGlossary)
        assertEquals(TranscriptRefineService.HARD_TIMEOUT_MS, claude.lastTimeoutMs)
        assertFalse(s.isRefining())
        // "Claude Code" and "effort" are not in this conversation's glossary: applied, but for the composer
        assertFalse(r.autoSend)
    }

    @Test
    fun the_validator_checks_against_the_glossary_the_refiner_was_handed() = runTest {
        glossary = GLOSSARY + listOf("Claude Code", "effort")
        val s = service(); val inbox = Inbox()
        s.onRefine(req(), inbox)
        runCurrent()
        val r = inbox.only()
        assertEquals(glossary, claude.lastGlossary)
        assertEquals(CORRECTED, r.text)
        assertTrue(r.autoSend)
    }

    @Test
    fun a_hard_blocked_edit_is_dropped_and_the_rest_applied_without_auto_send() = runTest {
        glossary = GLOSSARY + listOf("Claude Code", "effort")
        claude.behavior = { RefineOutcome.Edits(EDITS + TextEdit("有没有", "有")) } // removes a negation
        val s = service(); val inbox = Inbox()
        s.onRefine(req(), inbox)
        runCurrent()
        val r = inbox.only()
        assertTrue(r.ok)
        assertEquals(CORRECTED, r.text)
        assertEquals(EDITS, r.edits)
        assertFalse(r.autoSend)
    }

    @Test
    fun an_empty_list_sends_the_original_back_unchanged() = runTest {
        claude.behavior = { RefineOutcome.Edits(emptyList()) }
        val s = service(); val inbox = Inbox()
        s.onRefine(req(), inbox)
        runCurrent()
        val r = inbox.only()
        assertTrue(r.ok)
        assertEquals(TEXT, r.text)
        assertTrue(r.edits.isEmpty())
        assertTrue(r.autoSend)
    }

    @Test
    fun edits_that_fail_the_checks_are_invalid_and_return_no_text() = runTest {
        // a fragment that is not in the transcript, next to a good one: the whole list goes
        claude.behavior = { RefineOutcome.Edits(EDITS + TextEdit("这句不存在", "随便")) }
        val s = service(); val inbox = Inbox()
        s.onRefine(req(), inbox)
        runCurrent()
        val r = inbox.only()
        assertFalse(r.ok)
        assertEquals(TranscriptRefineError.INVALID, r.error)
        assertEquals("", r.text)
        assertTrue(r.edits.isEmpty())
        assertEquals("claude", r.agent)
    }

    @Test
    fun a_fragment_found_only_in_the_glossary_line_is_invalid() = runTest {
        // the model sees the glossary next to the transcript; the check runs against the transcript alone
        glossary = GLOSSARY + "zz-only-in-the-glossary"
        assertTrue(RefineContract.userMessage(TEXT, glossary).contains("zz-only-in-the-glossary"))
        for (from in listOf("zz-only-in-the-glossary", "Claude, proj", "<transcript>")) {
            claude.behavior = { RefineOutcome.Edits(listOf(TextEdit(from, "x"))) }
            val s = service(); val inbox = Inbox()
            val log = captureStderr {
                s.onRefine(req(), inbox)
                runCurrent()
            }
            assertEquals(TranscriptRefineError.INVALID, inbox.only().error, from)
            assertTrue(log.contains("rule=not_found"), from)
        }
    }

    @Test
    fun refiner_failures_map_to_their_codes() = runTest {
        val s = service()
        val cases = listOf(
            RefineOutcome.Failed to TranscriptRefineError.FAILED,
            RefineOutcome.Unavailable to TranscriptRefineError.UNAVAILABLE,
            RefineOutcome.TimedOut to TranscriptRefineError.TIMEOUT,
        )
        for ((i, case) in cases.withIndex()) {
            claude.behavior = { case.first }
            val inbox = Inbox()
            s.onRefine(req(capture = "cap-$i"), inbox)
            runCurrent()
            assertEquals(case.second, inbox.only().error)
            assertFalse(inbox.only().ok)
        }
        // an adapter that throws is a failed refine, not a crashed service
        claude.behavior = { error("boom") }
        val inbox = Inbox()
        s.onRefine(req(capture = "cap-x"), inbox)
        runCurrent()
        assertEquals(TranscriptRefineError.FAILED, inbox.only().error)
    }

    @Test
    fun a_refiner_that_never_answers_times_out_at_the_hard_limit() = runTest {
        claude.behavior = { awaitCancellation() }
        val s = service(); val inbox = Inbox()
        s.onRefine(req(), inbox)
        runCurrent()
        advanceTimeBy(TranscriptRefineService.HARD_TIMEOUT_MS - 1)
        runCurrent()
        assertTrue(inbox.frames.isEmpty(), "nothing before the limit")
        assertTrue(s.isRefining())
        advanceTimeBy(1)
        runCurrent()
        val r = inbox.only()
        assertFalse(r.ok)
        assertEquals(TranscriptRefineError.TIMEOUT, r.error)
        assertEquals("claude", r.agent)
        assertEquals(1, claude.cancelled.get(), "the refiner's call was cancelled — its process teardown runs from there")
        assertFalse(s.isRefining())
    }

    @Test
    fun a_newer_request_supersedes_the_running_one() = runTest {
        claude.behavior = { text -> if (text == TEXT) awaitCancellation() else RefineOutcome.Edits(emptyList()) }
        val s = service()
        val first = Inbox(); val second = Inbox()
        s.onRefine(req(capture = "cap-1"), first)
        runCurrent()
        s.onRefine(req(text = "$TEXT。", capture = "cap-2"), second)
        // the replaced request is answered at once, not left to its phone's timeout
        val old = first.only()
        assertEquals("cap-1", old.captureId)
        assertFalse(old.ok)
        assertEquals(TranscriptRefineError.SUPERSEDED, old.error)
        assertEquals("claude", old.agent)
        runCurrent()
        assertEquals(1, claude.cancelled.get())
        val now = second.only()
        assertTrue(now.ok)
        assertEquals("cap-2", now.captureId)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, first.frames.size, "the cancelled run never answers on top of the supersede")
    }

    @Test
    fun the_identical_request_re_sent_shares_the_running_refine() = runTest {
        val gate = CompletableDeferred<Unit>()
        claude.behavior = { gate.await(); RefineOutcome.Edits(EDITS) }
        val s = service()
        val a = Inbox(); val b = Inbox()
        s.onRefine(req(), a)
        runCurrent()
        s.onRefine(req(), b) // a reconnect re-sends the same frame
        s.onRefine(req(), b) // the same connection twice is still one waiter
        runCurrent()
        assertTrue(a.frames.isEmpty() && b.frames.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, claude.calls.get(), "the model ran once")
        assertEquals(CORRECTED, a.only().text)
        assertEquals(CORRECTED, b.only().text)
    }

    @Test
    fun a_cancelled_capture_gets_no_answer_at_all() = runTest {
        claude.behavior = { awaitCancellation() }
        val s = service(); val inbox = Inbox()
        s.onRefine(req(capture = "cap-1"), inbox)
        runCurrent()
        // a cancel for some other capture leaves this refine alone
        s.onCancel(AudioCancel("c-1", "cap-other"))
        s.onCancel(AudioCancel("c-2", "cap-1"))
        runCurrent()
        assertTrue(s.isRefining())
        assertEquals(0, claude.cancelled.get())

        s.onCancel(AudioCancel("c-1", "cap-1"))
        runCurrent()
        assertEquals(1, claude.cancelled.get())
        assertFalse(s.isRefining())
        advanceTimeBy(60_000); runCurrent()
        assertTrue(inbox.frames.isEmpty(), "the phone cancelled — nothing is sent, not even a timeout")
    }

    // ── one refine at a time, daemon-wide ──────────────────────────────────────────────────────

    /** Conversation `c-i` dictates `TEXT + i`; [plan] decides each one's answer and [started] records the order. */
    private val started = CopyOnWriteArrayList<Int>()
    private fun planned(plan: Map<Int, suspend () -> RefineOutcome>) {
        claude.behavior = { text ->
            val i = text.removePrefix(TEXT).toInt()
            started += i
            plan.getValue(i)()
        }
    }
    private fun queued(i: Int) = req(text = "$TEXT$i", capture = "cap-$i", convo = "c-$i")
    private val empty: suspend () -> RefineOutcome = { RefineOutcome.Edits(emptyList()) }
    private val forever: suspend () -> RefineOutcome = { awaitCancellation() }

    @Test
    fun a_second_conversation_starts_only_after_the_first_finishes() = runTest {
        val gate1 = CompletableDeferred<Unit>(); val gate2 = CompletableDeferred<Unit>()
        planned(mapOf(1 to { gate1.await(); RefineOutcome.Edits(emptyList()) }, 2 to { gate2.await(); RefineOutcome.Edits(emptyList()) }))
        val s = service(); val a = Inbox(); val b = Inbox()
        s.onRefine(queued(1), a); runCurrent()
        s.onRefine(queued(2), b); runCurrent()
        assertEquals(listOf(1), started, "the second waits for the slot")
        assertTrue(s.isRefining())
        gate1.complete(Unit); runCurrent()
        assertTrue(a.only().ok)
        assertEquals(listOf(1, 2), started)
        assertTrue(b.frames.isEmpty())
        gate2.complete(Unit); runCurrent()
        assertTrue(b.only().ok)
        assertFalse(s.isRefining())
    }

    @Test
    fun the_wait_for_the_slot_counts_against_the_hard_limit() = runTest {
        // c-1 holds the slot for 8 s, so c-2 has only the 4 s left of its 12
        planned(mapOf(1 to { kotlinx.coroutines.delay(8_000); RefineOutcome.Edits(emptyList()) }, 2 to forever))
        val s = service(); val a = Inbox(); val b = Inbox()
        s.onRefine(queued(1), a); s.onRefine(queued(2), b); runCurrent()
        advanceTimeBy(8_000); runCurrent()
        assertTrue(a.only().ok)
        assertEquals(listOf(1, 2), started)
        advanceTimeBy(TranscriptRefineService.HARD_TIMEOUT_MS - 8_000 - 1); runCurrent()
        assertTrue(b.frames.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(TranscriptRefineError.TIMEOUT, b.only().error)
        assertEquals(1, claude.cancelled.get())
        assertFalse(s.isRefining())
    }

    @Test
    fun a_refine_that_runs_out_while_queued_is_a_timeout_and_never_runs() = runTest {
        planned(mapOf(1 to forever, 2 to empty))
        val s = service(); val a = Inbox(); val b = Inbox()
        s.onRefine(queued(1), a); s.onRefine(queued(2), b); runCurrent()
        advanceTimeBy(TranscriptRefineService.HARD_TIMEOUT_MS); runCurrent()
        assertEquals(TranscriptRefineError.TIMEOUT, a.only().error)
        assertEquals(TranscriptRefineError.TIMEOUT, b.only().error)
        assertEquals("claude", b.only().agent)
        assertEquals(listOf(1), started, "the queued one never reached the model")
        assertFalse(s.isRefining())
    }

    @Test
    fun the_fifth_waiter_is_unavailable_at_once() = runTest {
        planned((1..7).associateWith { forever })
        val s = service()
        val inboxes = (1..7).associateWith { Inbox() }
        for (i in 1..5) s.onRefine(queued(i), inboxes.getValue(i)) // one running, four waiting
        runCurrent()
        s.onRefine(queued(6), inboxes.getValue(6)); runCurrent()
        val r = inboxes.getValue(6).only()
        assertEquals(TranscriptRefineError.UNAVAILABLE, r.error)
        assertEquals("claude", r.agent)
        assertTrue((1..5).all { inboxes.getValue(it).frames.isEmpty() })
        assertEquals(listOf(1), started)
        assertEquals(TranscriptRefineService.MAX_WAITING, 4)
        // a waiter that leaves makes room for one more
        s.onCancel(AudioCancel("c-2", "cap-2")); runCurrent()
        s.onRefine(queued(7), inboxes.getValue(7)); runCurrent()
        assertTrue(inboxes.getValue(7).frames.isEmpty(), "queued, not turned away")
        s.close()
    }

    @Test
    fun cancelling_a_queued_refine_lets_the_next_one_run_and_leaks_no_slot() = runTest {
        val gate1 = CompletableDeferred<Unit>()
        planned(mapOf(1 to { gate1.await(); RefineOutcome.Edits(emptyList()) }, 2 to empty, 3 to empty, 4 to empty, 5 to empty))
        val s = service()
        val inboxes = (1..5).associateWith { Inbox() }
        for (i in 1..4) s.onRefine(queued(i), inboxes.getValue(i))
        runCurrent()
        s.onCancel(AudioCancel("c-2", "cap-2")) // cancelled while waiting
        s.onRefine(req(text = "${TEXT}5", capture = "cap-5", convo = "c-3"), inboxes.getValue(5)) // supersedes c-3 while waiting
        runCurrent()
        assertEquals(TranscriptRefineError.SUPERSEDED, inboxes.getValue(3).only().error)
        assertEquals(listOf(1), started)
        gate1.complete(Unit); runCurrent()
        assertEquals(listOf(1, 4, 5), started, "the cancelled and the superseded waiters never ran")
        assertTrue(inboxes.getValue(2).frames.isEmpty())
        assertTrue(inboxes.getValue(4).only().ok && inboxes.getValue(5).only().ok)
        assertFalse(s.isRefining())
        // nothing leaked: a later request runs at once
        s.onRefine(queued(2), Inbox()); runCurrent()
        assertEquals(listOf(1, 4, 5, 2), started)
    }

    @Test
    fun cancelling_the_running_refine_frees_the_slot() = runTest {
        planned(mapOf(1 to forever, 2 to empty))
        val s = service(); val a = Inbox(); val b = Inbox()
        s.onRefine(queued(1), a); s.onRefine(queued(2), b); runCurrent()
        assertEquals(listOf(1), started)
        s.onCancel(AudioCancel("c-1", "cap-1")); runCurrent()
        assertEquals(1, claude.cancelled.get())
        assertEquals(listOf(1, 2), started)
        assertTrue(b.only().ok)
        assertTrue(a.frames.isEmpty())
        assertFalse(s.isRefining())
    }

    @Test
    fun the_conversations_own_agent_wins_over_the_hint() = runTest {
        val codex = FakeTranscriptRefiner(AgentKind.CODEX)
        sessionAgent = AgentKind.CLAUDE
        val s = service(TranscriptRefiners(listOf(claude, codex)))
        val inbox = Inbox()
        s.onRefine(req(hint = "codex"), inbox)
        runCurrent()
        assertEquals("claude", inbox.only().agent)
        assertEquals(0, codex.calls.get())
    }

    @Test
    fun a_known_agent_without_a_refiner_is_unavailable_whatever_the_hint() = runTest {
        sessionAgent = AgentKind.CODEX // no Codex refiner on this daemon
        val s = service(); val inbox = Inbox()
        s.onRefine(req(hint = "claude"), inbox)
        runCurrent()
        val r = inbox.only()
        assertFalse(r.ok)
        assertEquals(TranscriptRefineError.UNAVAILABLE, r.error)
        assertNull(r.agent)
        assertEquals(0, claude.calls.get(), "the hint never overrides the conversation's own agent")
    }

    @Test
    fun the_hint_picks_only_for_a_conversation_with_no_agent_here() = runTest {
        sessionAgent = null // not started (or no longer live) on this daemon
        val s = service(); val inbox = Inbox()
        s.onRefine(req(hint = "claude"), inbox)
        runCurrent()
        val r = inbox.only()
        assertTrue(r.ok)
        assertEquals("claude", r.agent)
        assertEquals(1, claude.calls.get())
        // and without a hint there is nothing to follow
        val none = Inbox()
        s.onRefine(req(capture = "cap-2", hint = null), none)
        runCurrent()
        assertEquals(TranscriptRefineError.UNAVAILABLE, none.only().error)
        assertNull(none.only().agent)
        assertEquals(1, claude.calls.get())
    }

    @Test
    fun no_refiner_for_the_session_or_the_hint_is_unavailable_and_never_borrows_another_agent() = runTest {
        sessionAgent = AgentKind.CODEX
        val s = service()
        for ((i, hint) in listOf("codex", null, "", "gpt-5", "CLAUDE", "dsh").withIndex()) {
            val inbox = Inbox()
            s.onRefine(req(capture = "cap-$i", hint = hint), inbox)
            runCurrent()
            val r = inbox.only()
            assertFalse(r.ok, "hint $hint")
            assertEquals(TranscriptRefineError.UNAVAILABLE, r.error)
            assertNull(r.agent)
            assertEquals("", r.text)
        }
        assertEquals(0, claude.calls.get(), "a Codex user's dictation never runs on their Claude account")

        // an adapter whose CLI is missing is no refiner either
        sessionAgent = AgentKind.CLAUDE
        claude.available = false
        val inbox = Inbox()
        s.onRefine(req(capture = "cap-missing", hint = "claude"), inbox)
        runCurrent()
        assertEquals(TranscriptRefineError.UNAVAILABLE, inbox.only().error)
        assertEquals(0, claude.calls.get())
        assertTrue(service(TranscriptRefiners.EMPTY).advertisedAgents().isEmpty())
        assertTrue(service().advertisedAgents().isEmpty())
        claude.available = true
        assertEquals(listOf("claude"), service().advertisedAgents())
    }

    @Test
    fun text_over_the_limit_is_unavailable_without_starting_a_model() = runTest {
        val s = service()
        val tooLong = Inbox()
        s.onRefine(req(text = "字".repeat(TranscriptRefineLimits.MAX_TEXT_CHARS + 1)), tooLong)
        runCurrent()
        assertEquals(TranscriptRefineError.UNAVAILABLE, tooLong.only().error)
        assertNull(tooLong.only().agent)
        assertEquals(0, claude.calls.get())

        claude.behavior = { RefineOutcome.Edits(emptyList()) }
        val atLimit = Inbox()
        s.onRefine(req(text = "字".repeat(TranscriptRefineLimits.MAX_TEXT_CHARS), capture = "cap-2"), atLimit)
        runCurrent()
        assertTrue(atLimit.only().ok)
        assertEquals(1, claude.calls.get())
    }

    @Test
    fun blank_text_needs_no_model() = runTest {
        val s = service(); val inbox = Inbox()
        s.onRefine(req(text = "  "), inbox)
        runCurrent()
        val r = inbox.only()
        assertTrue(r.ok)
        assertEquals("  ", r.text)
        assertTrue(r.edits.isEmpty())
        assertTrue(r.autoSend)
        assertEquals(0, claude.calls.get())
    }

    @Test
    fun close_cancels_what_is_running() = runTest {
        claude.behavior = { awaitCancellation() }
        val s = service(); val inbox = Inbox()
        s.onRefine(req(), inbox)
        runCurrent()
        s.close()
        assertEquals(1, claude.cancelled.get())
        assertFalse(s.isRefining())
        assertTrue(inbox.frames.isEmpty())
    }

    @Test
    fun the_log_carries_codes_and_counts_but_never_text() = runTest {
        val captured = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(captured, true, Charsets.UTF_8))
        try {
            val s = service()
            s.onRefine(req(capture = "ok"), Inbox()); runCurrent() // ok, 2 edits
            claude.behavior = { RefineOutcome.Edits(listOf(TextEdit("不存在的片段", "替换"))) }
            s.onRefine(req(capture = "invalid"), Inbox()); runCurrent()
            claude.behavior = { awaitCancellation() }
            s.onRefine(req(capture = "superseded"), Inbox()); runCurrent()
            s.onRefine(req(text = "$TEXT。", capture = "timeout"), Inbox()); runCurrent()
            advanceTimeBy(TranscriptRefineService.HARD_TIMEOUT_MS); runCurrent()
        } finally {
            System.setErr(original)
        }
        val log = captured.toString(Charsets.UTF_8)
        // the capture worked: each outcome left its line
        for (code in listOf("result=ok", "rule=not_found", "result=invalid", "result=superseded", "result=timeout")) {
            assertTrue(log.contains(code), "missing '$code' in:\n$log")
        }
        assertTrue(log.contains("agent=claude") && log.contains("edits=2"))
        assertTrue(log.contains("applied=2 dropped=0 autoSend=false"), "missing the refine counts in:\n$log")
        for (secret in listOf("cloud code", "Claude Code", "守护进程", "用功", "effort", "不存在的片段", "替换")) {
            assertFalse(log.contains(secret), "the log leaked '$secret'")
        }
    }

    private inline fun captureStderr(block: () -> Unit): String {
        val captured = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(captured, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return captured.toString(Charsets.UTF_8)
    }

    private companion object {
        const val TEXT = REFINE_TEXT
        const val CORRECTED = REFINE_CORRECTED
        val EDITS = REFINE_EDITS
        val GLOSSARY = listOf("Claude", "proj")
    }
}
