package dev.ccpocket.daemon.kimi

import dev.ccpocket.protocol.ChatRole
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fixture-driven replay of an ACP session/update transcript into HistoryMessage rows (issue #206). */
class KimiTranscriptReplayTest {

    private fun wireFile(vararg lines: String) = Files.createTempFile("kimi_wire", ".jsonl").also {
        Files.write(it, lines.toList())
    }

    @Test
    fun `text and tool call with result flatten into rows with a merged tool card`() {
        val file = wireFile(
            """{"update":{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"run echo"}}}""",
            """{"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Sure."}}}""",
            """{"update":{"sessionUpdate":"tool_call","toolCallId":"t1","kind":"execute","rawInput":{"command":"echo hi"}}}""",
            """{"update":{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed","content":[{"type":"text","text":"hi"}]}}""",
            """{"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Done."}}}""",
        )
        val rows = KimiTranscriptReplay.read(file)
        // user, assistant("Sure."), tool card, assistant("Done.")
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT), rows.map { it.role })
        val tool = rows[2]
        assertEquals("Bash", tool.tool)
        assertEquals(true, tool.ok) // merged result: completed, no error
        assertEquals("hi", tool.output)
    }

    @Test
    fun `unknown and unparseable lines produce no rows, never throws`() {
        val file = wireFile(
            "not json",
            """{"update":{"sessionUpdate":"plan","entries":[]}}""",
            """{"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"ok"}}}""",
        )
        val rows = KimiTranscriptReplay.read(file)
        assertEquals(1, rows.size)
        assertEquals(ChatRole.ASSISTANT, rows[0].role)
        assertEquals("ok", rows[0].text)
    }

    @Test
    fun `missing file replays empty`() {
        assertTrue(KimiTranscriptReplay.read(Files.createTempDirectory("x").resolve("nope.jsonl")).isEmpty())
    }
}
