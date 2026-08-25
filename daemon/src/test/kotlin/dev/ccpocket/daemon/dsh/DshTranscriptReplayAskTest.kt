package dev.ccpocket.daemon.dsh

import dev.ccpocket.protocol.ChatRole
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Replaying the two human-decision cards out of a dsh transcript (issue #291).
 *
 * The records below are the real on-disk shapes (dsh rc.6 `dsh-session` / `dsh-user-approval` types,
 * cross-checked against `--probe-ask`'s disk tier). Three details are the whole test:
 *  - `tool/call.arguments` is a raw JSON **string**, not an object, and spells the flag `multi_select`;
 *  - the answer sits two levels down, in `data.message.content[].content[].text`;
 *  - an approval's id is `id` on disk (it is `approvalId` on the wire).
 */
class DshTranscriptReplayAskTest {

    /** `'` stands in for `\"` so the deeply-escaped fixtures stay readable in a raw string. */
    private fun transcript(vararg lines: String): Path {
        val file = Files.createTempDirectory("dsh-replay-ask").resolve("session.jsonl")
        file.writeText(lines.joinToString("\n", postfix = "\n").replace("'", "\\\""))
        return file
    }

    private val header = """{"type":"session","version":0,"id":"s1","cwd":"/tmp/proj","createdAt":1}"""

    private val askCall =
        """{"type":"tool/call","seq":2,"time":2,"data":{"turn":1,"step":1,"callId":"call-1",""" +
            """"name":"ask_user_question","arguments":"{'questions':[""" +
            """{'id':'color','question':'Which color?','options':[{'label':'Red'},{'label':'Blue'}]},""" +
            """{'id':'size','question':'Which size?','multi_select':true,""" +
            """'options':[{'label':'Small'},{'label':'Large'}]}]}"}}"""

    private fun askResult(answersJson: String) =
        """{"type":"tool/result","seq":3,"time":3,"data":{"turn":1,"step":1,"message":{"content":[""" +
            """{"type":"tool-result","toolCallId":"call-1","content":[{"type":"text","text":"$answersJson"}]}]}}}"""

    @Test
    fun an_answered_question_replays_as_the_answered_card() {
        val file = transcript(
            header,
            """{"type":"user/message","seq":1,"time":1,"data":{"content":[{"type":"text","text":"hi"}]}}""",
            askCall,
            askResult("{'answers':[{'id':'color','selected':['Red']},{'id':'size','selected':['Small','Large']}]}"),
        )
        val messages = DshTranscriptReplay.read(file)
        assertEquals(2, messages.size)
        assertEquals(ChatRole.USER, messages[0].role)

        val card = messages[1]
        assertEquals(ChatRole.TOOL, card.role)
        // the CLAUDE spelling, because that is the renderer key the app's #110 question card switches on
        assertEquals("AskUserQuestion", card.tool)
        assertEquals("Which color?\nWhich size?", card.text)
        val answers = assertNotNull(card.answers)
        assertEquals(2, answers.size)
        assertEquals("Which color?" to "Red", answers[0].question to answers[0].answer)
        assertEquals("Which size?" to "Small, Large", answers[1].question to answers[1].answer)
    }

    /** A free-text "Other…" answer replays through `custom`. */
    @Test
    fun a_custom_answer_replays_too() {
        val file = transcript(
            header,
            askCall,
            askResult("{'answers':[{'id':'color','selected':[],'custom':'Teal'},{'id':'size','selected':['Small']}]}"),
        )
        val answers = assertNotNull(DshTranscriptReplay.read(file).single().answers)
        assertEquals("Teal", answers[0].answer)
        assertEquals("Small", answers[1].answer)
    }

    /** A question the turn was cancelled on has NO tool/result at all — the row stays unanswered, which
     *  the existing card already renders. It must not disappear and must not show raw JSON. */
    @Test
    fun a_cancelled_question_replays_unanswered() {
        val file = transcript(header, askCall)
        val card = DshTranscriptReplay.read(file).single()
        assertEquals("AskUserQuestion", card.tool)
        assertEquals("Which color?\nWhich size?", card.text)
        assertNull(card.answers)
    }

    @Test
    fun an_approval_pair_replays_as_a_decided_tool_row() {
        val file = transcript(
            header,
            """{"type":"approval/asked","seq":4,"time":4,"data":{"id":"apr-1","toolName":"bash",""" +
                """"callId":"c2","reason":"needs to delete build/ outside the sandbox"}}""",
            """{"type":"approval/decided","seq":5,"time":5,"data":{"id":"apr-1","outcome":"allowed-once"}}""",
            """{"type":"assistant/message","seq":6,"time":6,"data":{"message":{"content":[{"type":"text","text":"done"}]}}}""",
        )
        val messages = DshTranscriptReplay.read(file)
        assertEquals(2, messages.size)
        val card = messages[0]
        assertEquals(ChatRole.TOOL, card.role)
        assertEquals("bash", card.tool)
        assertEquals("needs to delete build/ outside the sandbox", card.text)
        assertEquals(true, card.ok)
        assertEquals(ChatRole.ASSISTANT, messages[1].role)
    }

    @Test
    fun every_non_allow_outcome_reads_as_not_run() {
        listOf("rejected", "cancelled", "unavailable").forEach { outcome ->
            val file = transcript(
                header,
                """{"type":"approval/asked","seq":1,"time":1,"data":{"id":"a","toolName":"bash","reason":"why"}}""",
                """{"type":"approval/decided","seq":2,"time":2,"data":{"id":"a","outcome":"$outcome"}}""",
            )
            assertEquals(false, DshTranscriptReplay.read(file).single().ok, outcome)
        }
    }

    /** An approval still awaiting its decision leaves an undecided row rather than claiming an outcome. */
    @Test
    fun an_undecided_approval_has_no_verdict() {
        val file = transcript(
            header,
            """{"type":"approval/asked","seq":1,"time":1,"data":{"id":"a","toolName":"bash","reason":"why"}}""",
        )
        assertNull(DshTranscriptReplay.read(file).single().ok)
    }

    @Test
    fun ordinary_tool_call_and_result_replay_as_a_structured_card() {
        val file = transcript(
            header,
            """{"type":"tool/call","seq":1,"time":1,"data":{"turn":1,"step":1,"callId":"c9","name":"bash","arguments":"{'command':'ls'}"}}""",
            """{"type":"tool/result","seq":2,"time":2,"data":{"turn":1,"step":1,"message":{"content":[""" +
                """{"type":"tool-result","toolCallId":"c9","content":[{"type":"text","text":"a.txt"}]}]}}}""",
        )
        val card = DshTranscriptReplay.read(file).single()
        assertEquals(ChatRole.TOOL, card.role)
        assertEquals("Bash", card.tool)
        assertEquals("ls", card.text)
        assertEquals("a.txt", card.output)
        assertEquals(true, card.ok)
    }

    @Test
    fun ordinary_tool_without_a_result_stays_visible_without_inventing_an_outcome() {
        val file = transcript(
            header,
            """{"type":"tool/call","seq":1,"time":1,"data":{"callId":"c9","name":"read","arguments":"{'file_path':'/repo/a.txt'}"}}""",
        )
        val card = DshTranscriptReplay.read(file).single()
        assertEquals("Read", card.tool)
        assertEquals("/repo/a.txt", card.text)
        assertNull(card.ok)
        assertNull(card.output)
    }

    @Test
    fun a_truncated_argument_string_is_still_visible_instead_of_becoming_only_the_tool_name() {
        val file = transcript(
            header,
            """{"type":"tool/call","seq":1,"time":1,"data":{"callId":"c9","name":"bash","arguments":"partial command tail"}}""",
        )
        assertEquals("partial command tail", DshTranscriptReplay.read(file).single().text)
    }

    @Test
    fun an_explicit_failed_tool_result_marks_the_card_failed() {
        val file = transcript(
            header,
            """{"type":"tool/call","seq":1,"time":1,"data":{"callId":"c9","name":"bash","arguments":"{'command':'false'}"}}""",
            """{"type":"tool/result","seq":2,"time":2,"data":{"message":{"content":[""" +
                """{"type":"tool-result","toolCallId":"c9","isError":true,"content":[{"type":"text","text":"exit 1"}]}]}}}""",
        )
        val card = DshTranscriptReplay.read(file).single()
        assertEquals(false, card.ok)
        assertEquals("exit 1", card.output)
    }


    /** The delta cursor must still advance to the file's line count, and a patched row must report the
     *  line that patched it (issue #147) — otherwise a reattach after the answer misses it. */
    @Test
    fun the_answer_line_is_the_rows_patch_cursor() {
        val file = transcript(
            header,
            askCall,
            askResult("{'answers':[{'id':'color','selected':['Red']},{'id':'size','selected':[]}]}"),
        )
        val full = DshTranscriptReplay.slice(file, sinceSeq = null)
        assertEquals(3L, full.lastSeq)
        // asking for "everything after the tool/call" must NOT hand back a stale unanswered card
        val delta = DshTranscriptReplay.slice(file, sinceSeq = 2L)
        assertTrue(delta.messages.isNotEmpty())
        assertEquals("Red", assertNotNull(delta.messages.last().answers).first().answer)
    }
}
