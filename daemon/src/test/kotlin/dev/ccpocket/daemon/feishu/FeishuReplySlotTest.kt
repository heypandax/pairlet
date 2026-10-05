package dev.ccpocket.daemon.feishu

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Audit F3: the reply slot is what lets a turn's result reach the chat after ask() stopped waiting
 * (NUDGE_MS + TURN_TIMEOUT_MS ≈ 325s). These tests replay ask()'s slot calls in the order it makes them.
 */
class FeishuReplySlotTest {
    private val convo = "convo-1"
    private fun slot(id: String) = FeishuEngine.ReplySlot(FeishuReplyTarget("om_$id", inThread = false), promptId = id)

    /** ask() for a request that WAS handed to the agent but outlived the wait: its slot stays armed. */
    private fun timedOutRequest(slots: MutableMap<String, FeishuEngine.ReplySlot>, s: FeishuEngine.ReplySlot) {
        val displaced = installReplySlot(slots, convo, s)
        releaseReplySlot(slots, convo, s, preserveLateReply = true, displaced = displaced)
    }

    @Test
    fun a_request_refused_because_the_long_turn_is_still_running_keeps_that_turns_late_reply() {
        val slots = HashMap<String, FeishuEngine.ReplySlot>()
        val first = slot("A")
        timedOutRequest(slots, first) // trusted / owner-bypass task running > 325s

        // the same topic sends another message: installed, then handOff() says "busy" → not sent
        val second = slot("B")
        val displaced = installReplySlot(slots, convo, second)
        restoreDisplacedReplySlot(slots, convo, second, displaced)
        releaseReplySlot(slots, convo, second, preserveLateReply = false, displaced = displaced)

        // the first task's TurnDone must still find ITS slot (postTurn + the #285 ownership gate read this)
        assertSame(first, slots[convo], "the long task's result has nowhere to go once its slot is dropped")
        assertFalse(first.done)
    }

    @Test
    fun a_failure_between_install_and_send_also_hands_the_slot_back() {
        val slots = HashMap<String, FeishuEngine.ReplySlot>()
        val first = slot("A")
        timedOutRequest(slots, first)

        val second = slot("B")
        val displaced = installReplySlot(slots, convo, second)
        // e.g. the review/claim threw: only the finally block runs
        releaseReplySlot(slots, convo, second, preserveLateReply = false, displaced = displaced)

        assertSame(first, slots[convo])
    }

    @Test
    fun a_request_that_did_run_takes_over_the_slot_and_a_settled_one_is_never_resurrected() {
        val slots = HashMap<String, FeishuEngine.ReplySlot>()
        val first = slot("A")
        timedOutRequest(slots, first)
        first.done = true // its late TurnDone posted

        val second = slot("B")
        val displaced = installReplySlot(slots, convo, second)
        assertNull(displaced, "a slot that already posted owes nothing")
        releaseReplySlot(slots, convo, second, preserveLateReply = true, displaced = displaced)
        assertSame(second, slots[convo])

        second.done = true // answered inline
        releaseReplySlot(slots, convo, second, preserveLateReply = true, displaced = displaced)
        assertTrue(slots.isEmpty())
    }

    @Test
    fun a_request_never_sent_with_nothing_displaced_leaves_no_slot() {
        val slots = HashMap<String, FeishuEngine.ReplySlot>()
        val only = slot("A")
        val displaced = installReplySlot(slots, convo, only)
        restoreDisplacedReplySlot(slots, convo, only, displaced)
        releaseReplySlot(slots, convo, only, preserveLateReply = false, displaced = displaced)
        assertTrue(slots.isEmpty())
    }
}
