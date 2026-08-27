package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.QuestionAnswer
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Issue #321: a replayed AskUserQuestion must read as what it is.
 *
 * The report was a DeepSeek Harness question you could see and not complete, on a turn that then sat
 * there. Half of that is the daemon's (see `DshAskLedgerTest`); this is the client half, and it was the
 * more misleading one: an UNANSWERED question row fell through every question branch and landed on the
 * generic tool row, so it rendered as a card-ish thing titled "AskUserQuestion" showing the question
 * text — visually a question, with nothing to tap and nothing saying why.
 *
 * The three states are now distinct, and the mapping is backend-agnostic on purpose: every replay path
 * (Claude's `TranscriptReplay`, dsh's `DshTranscriptReplay`) files the row under the same tool name, and
 * an interrupted Claude turn leaves exactly the same residue.
 */
class ReplayedQuestionRowTest {

    private fun repo() = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        convoId.value = "c1"
        receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false))
    }

    /** The row the daemon replays for a question: tool name + the question text, answers patched in only
     *  when a matching tool_result existed. */
    private fun questionRow(answers: List<QuestionAnswer>? = null) =
        HistoryMessage(ChatRole.TOOL, "Ship it?", tool = "AskUserQuestion", answers = answers)

    @Test
    fun an_unanswered_question_is_labelled_unanswered_not_dressed_up_as_a_tool_call() {
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", listOf(questionRow()), lastSeq = 2, firstSeq = 1))

        val row = assertIs<ChatItem.QuestionsUnanswered>(
            r.messages.single(),
            "an AskUserQuestion with no answers must not fall through to the generic tool row",
        )
        assertEquals("Ship it?", row.text)
    }

    @Test
    fun an_answered_question_still_replays_as_the_answered_row() {
        val r = repo()
        val answered = questionRow(listOf(QuestionAnswer("Ship it?", "Yes")))
        r.receiveForTest(ConvoHistory("c1", listOf(answered), lastSeq = 2, firstSeq = 1))

        val row = assertIs<ChatItem.QuestionsAnswered>(r.messages.single())
        assertEquals(listOf("Ship it?" to "Yes"), row.items)
    }

    /** The new branch keys on the ask tool ALONE, so an ordinary tool row is untouched by it. */
    @Test
    fun an_ordinary_tool_row_is_unaffected() {
        val r = repo()
        r.receiveForTest(
            ConvoHistory("c1", listOf(HistoryMessage(ChatRole.TOOL, "ls -la", tool = "Bash")), lastSeq = 2, firstSeq = 1),
        )
        assertIs<ChatItem.Tool>(r.messages.single())
    }
}
