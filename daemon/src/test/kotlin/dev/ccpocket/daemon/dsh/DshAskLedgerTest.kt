package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AskQuestions
import dev.ccpocket.daemon.approval.ApprovalCoordinator
import dev.ccpocket.daemon.agent.PermissionBridge
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dsh ask/approval bridge (issue #291).
 *
 * Every mux frame below is the REAL shape, taken from `scripts/probe-dsh-api.py --probe-ask` and from
 * dsh rc.6's own schemas (`dsh-host-apiproxy/lib/types/api/{events,questions,approvals}.schema.js`) —
 * including the two spellings that are easy to get backwards: the rpcId rides the ENVELOPE (never the
 * payload), and `approval/resolved` echoes `approvalId` while `question/resolved` echoes `questionRpcId`.
 */
class DshAskLedgerTest {

    private val askRpc = "0f0d6a1e-7c2f-4f0e-9b1a-2f7d5c3e91aa"
    private val approvalRpc = "b41c8f2d-55aa-4a3b-8f60-9c0e1d2a3b4c"

    /** `{rpcId, payload}` as `question/requested` really arrives (probe: method mirrors payload.type). */
    private fun questionFrame(multi: Boolean = false): JsonObject = json(
        """
        {"type":"server-request","rpcId":"$askRpc","method":"question/requested",
         "payload":{"type":"question/requested","sessionId":"session-abc","questions":[
           {"id":"color","question":"Which color do you prefer?","header":"Color",
            "options":[{"label":"Red","description":"warm"},{"label":"Blue"}]},
           {"id":"size","question":"Which sizes should I build?","header":"Size",
            "multiSelect":$multi,"options":[{"label":"Small"},{"label":"Medium"},{"label":"Large"}]}]}}
        """,
    )

    private fun approvalFrame(): JsonObject = json(
        """
        {"type":"server-request","rpcId":"$approvalRpc","method":"approval/requested",
         "payload":{"type":"approval/requested","sessionId":"session-abc","approvalId":"apr-77",
                    "toolName":"bash","callId":"call-9",
                    "reason":"needs to delete build/ which is outside the sandbox"}}
        """,
    )

    private fun json(text: String): JsonObject = DshTranscript.parseLine(text.trimIndent().replace("\n", " "))!!

    private fun payload(frame: JsonObject): JsonObject = frame.obj("payload")!!

    private class Sent(val rpcId: String, val value: JsonObject)

    private class Fixture(
        private val ourSession: String? = "session-abc", // the sessionId every fixture frame names
        private val result: (String) -> DshRespond = { DshRespond(true, null) },
    ) {
        val sent = mutableListOf<Sent>()
        val reported = mutableListOf<String>()
        val ledger = DshAskLedger(
            ourSession = { ourSession },
            send = { rpcId, value -> sent += Sent(rpcId, value); result(rpcId) },
            report = { reported += it },
        )
    }

    // ---- 1. the pending table is idempotent under a mux replay ----

    @Test
    fun a_replayed_rpcId_does_not_deal_a_second_card() {
        val f = Fixture()
        val frame = questionFrame()
        val first = assertIs<AgentEvent.ControlRequest>(
            f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(frame)),
        )
        assertEquals(DshAsk.ASK_ID_PREFIX + askRpc, first.requestId)
        assertEquals(AskQuestions.TOOL, first.toolName)

        // a reconnecting mux replays every undecided server-request with the SAME rpcId
        assertNull(f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(frame)))
        assertNull(f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(frame)))

        // …and the ONE card still answers exactly once
        runBlocking { assertTrue(f.ledger.answer(first.requestId, allow = true, updatedInput = null)) }
        assertEquals(1, f.sent.size)
    }

    /**
     * No rpcId = nothing to answer against, so no card. But NOT silence (issue #321): dsh has no timeout,
     * so the turn behind that ask now waits forever, and a daemon log line is invisible from a phone.
     * The drop reaches the chat as a notice instead.
     */
    @Test
    fun a_frame_without_an_rpcId_deals_no_card_but_says_so() {
        val f = Fixture()
        assertIs<AgentEvent.AssistantText>(f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, null, payload(questionFrame())))
        assertIs<AgentEvent.AssistantText>(f.ledger.requested(DshAskLedger.APPROVAL_REQUESTED, "", payload(approvalFrame())))
        assertTrue(f.sent.isEmpty(), "nothing may be answered on dsh's side")
    }

    /**
     * The mux is shared across every session the host holds, sub-agents included. Claiming a foreign
     * session's ask would put a card in front of the user whose one tap sends `allowed-once` for a
     * `(sessionId, approvalId)` pair they never saw.
     */
    @Test
    fun an_ask_belonging_to_another_session_is_never_claimed() {
        val f = Fixture(ourSession = "session-mine")
        assertNull(f.ledger.requested(DshAskLedger.APPROVAL_REQUESTED, approvalRpc, payload(approvalFrame())))
        assertNull(f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(questionFrame())))
    }

    /** Fail CLOSED in the boot window: the mux is live before `session.create` returns, and until we know
     *  our own id we cannot prove an ask is ours — so nothing is ever claimed OR answered there. */
    @Test
    fun no_ask_is_claimed_before_our_session_is_open() {
        val f = Fixture(ourSession = null)
        assertIs<AgentEvent.AssistantText>(f.ledger.requested(DshAskLedger.APPROVAL_REQUESTED, approvalRpc, payload(approvalFrame())))
        assertIs<AgentEvent.AssistantText>(f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(questionFrame())))
        assertTrue(f.sent.isEmpty(), "an unprovable ask is never answered on dsh's side")
        // and nothing was put in the pending table either — a verdict for it finds nothing to send
        runBlocking { assertFalse(f.ledger.answer(DshAsk.ASK_ID_PREFIX + askRpc, allow = true, updatedInput = null)) }
    }

    /**
     * The fresh-create half of #321: an ask that beats our own `session.create` to the mux.
     *
     * #321 seeded the RESUMED session id into [DshAskLedger.ourSession] so a resume stopped landing in the
     * pre-create window — but a fresh conversation has no id to seed, so it still lands there, and until
     * now that was the one drop in this file that said NOTHING. dsh has no timeout, so the turn behind it
     * waits forever: from the phone it is indistinguishable from thinking. Announce it like every other
     * unanswerable ask.
     */
    @Test
    fun a_pre_create_ask_is_announced_instead_of_dropped_silently() {
        val f = Fixture(ourSession = null)
        val notice = assertIs<AgentEvent.AssistantText>(
            f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(questionFrame())),
        )
        // the notice must name what happened and what to do — the same shape the other hang notices use
        assertTrue("asked a question" in notice.text, notice.text)
        assertTrue("created" in notice.text, notice.text)
        assertTrue("send the prompt again" in notice.text, notice.text)
    }

    /**
     * …and the contrast that keeps the notice from becoming noise: a DIFFERENT session's ask is normal
     * multiplexing (the mux carries every session the host holds, sub-agents included), it is not lost —
     * its own mux sees it — so it stays silent. Only "probably ours and unanswerable" is announced.
     */
    @Test
    fun an_ask_for_a_foreign_session_stays_silent_unlike_the_pre_create_one() {
        val f = Fixture(ourSession = "session-mine")
        assertNull(f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(questionFrame())))
        assertNull(f.ledger.requested(DshAskLedger.APPROVAL_REQUESTED, approvalRpc, payload(approvalFrame())))
        assertTrue(f.sent.isEmpty())
    }

    /** The response echoes the session that asked — which the claim check has already proven is ours. */
    @Test
    fun the_response_carries_our_own_session_id() {
        val f = Fixture()
        val ask = assertIs<AgentEvent.ControlRequest>(f.ledger.requested(DshAskLedger.APPROVAL_REQUESTED, approvalRpc, payload(approvalFrame())))
        runBlocking { f.ledger.answer(ask.requestId, allow = true, updatedInput = null) }
        assertEquals("session-abc", f.sent.single().value.str("sessionId"))
    }

    // ---- 2. resolved elsewhere → withdraw the card AND clear the table ----

    @Test
    fun question_resolved_withdraws_the_card_and_clears_the_entry() {
        val f = Fixture()
        val ask = assertIs<AgentEvent.ControlRequest>(f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(questionFrame())))
        val resolved = json(
            """{"method":"question/resolved","payload":{"type":"question/resolved",
                "sessionId":"session-abc","questionRpcId":"$askRpc","outcome":"cancelled"}}""",
        )
        val cancel = f.ledger.resolved(DshAskLedger.QUESTION_RESOLVED, payload(resolved))
        assertIs<AgentEvent.ControlCancel>(cancel)
        assertEquals(ask.requestId, cancel.requestId)
        // table cleared: a late verdict finds nothing and sends nothing
        runBlocking { assertFalse(f.ledger.answer(ask.requestId, allow = true, updatedInput = null)) }
        assertTrue(f.sent.isEmpty())
        // and the resolution is not replayed into a second withdrawal
        assertNull(f.ledger.resolved(DshAskLedger.QUESTION_RESOLVED, payload(resolved)))
    }

    /** `approval/resolved` carries `approvalId`, NOT the rpcId — the reverse index is what makes it work. */
    @Test
    fun approval_resolved_matches_on_approvalId_not_rpcId() {
        val f = Fixture()
        val ask = assertIs<AgentEvent.ControlRequest>(f.ledger.requested(DshAskLedger.APPROVAL_REQUESTED, approvalRpc, payload(approvalFrame())))
        val resolved = json(
            """{"method":"approval/resolved","payload":{"type":"approval/resolved",
                "sessionId":"session-abc","approvalId":"apr-77","outcome":"cancelled"}}""",
        )
        val cancel = f.ledger.resolved(DshAskLedger.APPROVAL_RESOLVED, payload(resolved))
        assertIs<AgentEvent.ControlCancel>(cancel)
        assertEquals(ask.requestId, cancel.requestId)
        runBlocking { assertFalse(f.ledger.answer(ask.requestId, allow = true, updatedInput = null)) }
    }

    @Test
    fun a_resolution_for_someone_elses_ask_is_ignored() {
        val f = Fixture()
        f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(questionFrame()))
        val other = json(
            """{"method":"question/resolved","payload":{"type":"question/resolved",
                "sessionId":"session-abc","questionRpcId":"not-ours","outcome":"answered"}}""",
        )
        assertNull(f.ledger.resolved(DshAskLedger.QUESTION_RESOLVED, payload(other)))
    }

    // ---- 3. question text ↔ id translation ----

    @Test
    fun answers_are_translated_by_text_to_id_positionally_for_every_question() {
        val f = Fixture()
        val ask = assertIs<AgentEvent.ControlRequest>(f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(questionFrame(multi = true))))
        // the phone answers by question TEXT and in whatever order it likes; dsh needs question ORDER + ids
        val updated = AskQuestions.answeredInput(
            ask.input,
            mapOf(
                "Which sizes should I build?" to "Small, Large",
                "Which color do you prefer?" to "Red",
            ),
            null,
        )
        runBlocking { f.ledger.answer(ask.requestId, allow = true, updatedInput = updated) }

        val sent = f.sent.single()
        assertEquals(askRpc, sent.rpcId)
        assertEquals("session-abc", sent.value.str("sessionId"))
        val answers = answersOf(sent.value)
        assertEquals(2, answers.size)
        assertEquals("color", answers[0].str("id"))
        assertEquals(listOf("Red"), selected(answers[0]))
        assertEquals("size", answers[1].str("id"))
        assertEquals(listOf("Small", "Large"), selected(answers[1]))
        // no `custom` when every part matched a real option label
        assertNull(answers[0].str("custom"))
        assertNull(answers[1].str("custom"))
    }

    /** dsh rejects a partial batch (`answers.length == questions.length`, matched by index AND id), so an
     *  unanswered question must ride as the empty `selected` its own web UI sends for a skip. */
    @Test
    fun an_unanswered_question_still_gets_an_entry_with_empty_selected() {
        val f = Fixture()
        val ask = assertIs<AgentEvent.ControlRequest>(f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(questionFrame())))
        val updated = AskQuestions.answeredInput(ask.input, mapOf("Which color do you prefer?" to "Blue"), null)
        runBlocking { f.ledger.answer(ask.requestId, allow = true, updatedInput = updated) }

        val answers = answersOf(f.sent.single().value)
        assertEquals(2, answers.size)
        assertEquals(listOf("Blue"), selected(answers[0]))
        assertEquals("size", answers[1].str("id"))
        assertEquals(emptyList(), selected(answers[1]))
        assertNull(answers[1].str("custom"))
    }

    // ---- 4. free text falls back to `custom` ----

    @Test
    fun a_freeform_response_becomes_custom_on_every_unpicked_question() {
        val f = Fixture()
        val ask = assertIs<AgentEvent.ControlRequest>(f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(questionFrame())))
        val updated = AskQuestions.answeredInput(ask.input, null, "neither — use whatever the theme says")
        runBlocking { f.ledger.answer(ask.requestId, allow = true, updatedInput = updated) }

        val answers = answersOf(f.sent.single().value)
        assertEquals(2, answers.size)
        answers.forEach {
            // single-select: `custom` and `selected` are mutually exclusive, so `selected` must be empty
            assertEquals(emptyList(), selected(it))
            assertEquals("neither — use whatever the theme says", it.str("custom"))
        }
    }

    /** An "Other…" answer the phone appends after the real labels: the labels ride in `selected`, the
     *  human's own words in `custom`. Legal together only because this question is multi-select. */
    @Test
    fun an_other_text_rides_in_custom_beside_the_matched_labels() {
        val f = Fixture()
        val ask = assertIs<AgentEvent.ControlRequest>(f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(questionFrame(multi = true))))
        val updated = AskQuestions.answeredInput(
            ask.input,
            mapOf("Which sizes should I build?" to "Small, XXL please"),
            null,
        )
        runBlocking { f.ledger.answer(ask.requestId, allow = true, updatedInput = updated) }

        val size = answersOf(f.sent.single().value)[1]
        assertEquals(listOf("Small"), selected(size))
        assertEquals("XXL please", size.str("custom"))
    }

    /** A single-select "Other…" must NOT ship a label beside the custom text — dsh refuses that pairing. */
    @Test
    fun single_select_other_text_drops_the_selected_array() {
        val f = Fixture()
        val ask = assertIs<AgentEvent.ControlRequest>(f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(questionFrame())))
        val updated = AskQuestions.answeredInput(ask.input, mapOf("Which color do you prefer?" to "Teal"), null)
        runBlocking { f.ledger.answer(ask.requestId, allow = true, updatedInput = updated) }

        val color = answersOf(f.sent.single().value)[0]
        assertEquals(emptyList(), selected(color))
        assertEquals("Teal", color.str("custom"))
    }

    // ---- 5. approvals ----

    @Test
    fun an_approval_allow_sends_allowed_once_with_both_ids() {
        val f = Fixture()
        val frame = approvalFrame()
        val ask = assertIs<AgentEvent.ControlRequest>(f.ledger.requested(DshAskLedger.APPROVAL_REQUESTED, approvalRpc, payload(frame)))
        assertEquals("bash", ask.toolName)
        // the model's own escalation reason is what the human reads
        assertEquals("needs to delete build/ which is outside the sandbox", ask.input?.str("description"))

        runBlocking { f.ledger.answer(ask.requestId, allow = true, updatedInput = null) }
        val value = f.sent.single().value
        assertEquals("session-abc", value.str("sessionId"))
        assertEquals("apr-77", value.str("approvalId"))
        assertEquals(DshAsk.OUTCOME_ALLOW, value.str("outcome"))
    }

    @Test
    fun an_approval_deny_sends_rejected() {
        val f = Fixture()
        val ask = assertIs<AgentEvent.ControlRequest>(f.ledger.requested(DshAskLedger.APPROVAL_REQUESTED, approvalRpc, payload(approvalFrame())))
        runBlocking { f.ledger.answer(ask.requestId, allow = false, updatedInput = null) }
        assertEquals(DshAsk.OUTCOME_REJECT, f.sent.single().value.str("outcome"))
    }

    // ---- 6. the refusal is reported, the benign race is not ----

    @Test
    fun a_bad_response_is_surfaced_in_the_chat() {
        val f = Fixture(result = { DshRespond(false, "bad-response") })
        val ask = assertIs<AgentEvent.ControlRequest>(f.ledger.requested(DshAskLedger.APPROVAL_REQUESTED, approvalRpc, payload(approvalFrame())))
        runBlocking { f.ledger.answer(ask.requestId, allow = true, updatedInput = null) }
        assertEquals(1, f.reported.size)
        assertTrue("bad-response" in f.reported.single(), f.reported.single())
    }

    @Test
    fun not_pending_is_a_benign_race_and_stays_quiet() {
        val f = Fixture(result = { DshRespond(false, DshRespond.NOT_PENDING) })
        val ask = assertIs<AgentEvent.ControlRequest>(f.ledger.requested(DshAskLedger.APPROVAL_REQUESTED, approvalRpc, payload(approvalFrame())))
        runBlocking { f.ledger.answer(ask.requestId, allow = true, updatedInput = null) }
        assertTrue(f.reported.isEmpty())
    }

    @Test
    fun an_askId_that_was_never_ours_is_refused_without_sending() {
        val f = Fixture()
        runBlocking {
            assertFalse(f.ledger.answer("r1", allow = true, updatedInput = null))
            assertFalse(f.ledger.answer("dsh-", allow = true, updatedInput = null))
        }
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun reset_drops_every_pending_entry() {
        val f = Fixture()
        val ask = assertIs<AgentEvent.ControlRequest>(f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(questionFrame())))
        f.ledger.reset()
        runBlocking { assertFalse(f.ledger.answer(ask.requestId, allow = true, updatedInput = null)) }
        // …and the rpcId is claimable again (a fresh host process may legitimately reuse nothing, but the
        // table must not keep refusing cards after a relaunch)
        assertNotNull(f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(questionFrame())))
    }

    // ---- 7. end to end: the coordinator's budget turns dsh's fail-HANG into a real fail-closed ----

    @Test
    fun an_unanswered_approval_times_out_into_respond_rejected() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        val coord = ApprovalCoordinator(scope)
        val f = Fixture()
        val emitted = CopyOnWriteArrayList<Frame>()
        val bridge = PermissionBridge(
            "c-dsh", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { askId, allow, _, _, updated, _ -> f.ledger.answer(askId, allow, updated) },
            verdictTimeoutMs = 60, questionTimeoutMs = 60,
            forceNeverRemember = true, // what Conversation sets for AgentKind.DSH
        )
        val ask = assertIs<AgentEvent.ControlRequest>(
            f.ledger.requested(DshAskLedger.APPROVAL_REQUESTED, approvalRpc, payload(approvalFrame())),
        )
        bridge.onControlRequest(ask)

        val card = emitted.filterIsInstance<PermissionAsk>().single()
        assertEquals("dsh-$approvalRpc", card.askId)
        assertEquals("bash", card.tool)
        // dsh has no "always allow" — the card must never offer one
        assertTrue(card.neverRemember)
        assertEquals(listOf("once"), card.grantOptions)

        // nobody answers. dsh itself would hang here forever; the coordinator must not.
        repeat(60) { if (f.sent.isEmpty()) delay(50) }
        assertEquals(DshAsk.OUTCOME_REJECT, f.sent.single().value.str("outcome"))
        assertTrue(emitted.filterIsInstance<AskWithdrawn>().isNotEmpty(), "the card must be retired too")
        scope.cancel()
    }

    @Test
    fun a_phone_verdict_flows_through_the_bridge_into_a_label_answer() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        val coord = ApprovalCoordinator(scope)
        val f = Fixture()
        val emitted = CopyOnWriteArrayList<Frame>()
        val bridge = PermissionBridge(
            "c-dsh", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { askId, allow, _, _, updated, _ -> f.ledger.answer(askId, allow, updated) },
            verdictTimeoutMs = 30_000, questionTimeoutMs = 30_000, forceNeverRemember = true,
        )
        // a well-formed frame yields a real CARD, never the #321 "cannot answer this" chat notice
        val ask = assertIs<AgentEvent.ControlRequest>(
            f.ledger.requested(DshAskLedger.QUESTION_REQUESTED, askRpc, payload(questionFrame())),
        )
        bridge.onControlRequest(ask)

        val card = emitted.filterIsInstance<PermissionAsk>().single()
        val questions = assertNotNull(card.questions)
        assertEquals(2, questions.size)
        assertEquals("Which color do you prefer?", questions[0].question)
        assertEquals("Color", questions[0].header)
        assertEquals(listOf("Red", "Blue"), questions[0].options.map { it.label })
        // dsh DOES carry per-option descriptions (the design draft assumed it did not)
        assertEquals("warm", questions[0].options[0].description)

        coord.onVerdict(
            PermissionVerdict(
                "c-dsh", card.askId, Decision.ALLOW,
                answers = mapOf("Which color do you prefer?" to "Blue", "Which sizes should I build?" to "Medium"),
            ),
        )
        val answers = answersOf(f.sent.single().value)
        assertEquals(listOf("Blue"), selected(answers[0]))
        assertEquals(listOf("Medium"), selected(answers[1]))
        scope.cancel()
    }

    // ---- helpers ----

    private fun answersOf(value: JsonObject): List<JsonObject> =
        (value.obj("answer")?.get("answers") as JsonArray).map { it as JsonObject }

    private fun selected(answer: JsonObject): List<String> =
        (answer["selected"] as JsonArray).map { (it as JsonPrimitive).content }
}
