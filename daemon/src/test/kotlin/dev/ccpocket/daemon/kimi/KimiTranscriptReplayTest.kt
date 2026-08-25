package dev.ccpocket.daemon.kimi

import dev.ccpocket.protocol.ChatRole
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fixture-driven replay of a wire.jsonl transcript into HistoryMessage rows (issue #206).
 *  Fixtures mirror the probe-verified 0.34.0 on-disk INTERNAL wire format (2026-08-08) — NOT ACP. */
class KimiTranscriptReplayTest {

    private fun wireFile(vararg lines: String) = Files.createTempFile("kimi_wire", ".jsonl").also {
        Files.write(it, lines.toList())
    }

    @Test
    fun `prompts text parts and tool calls flatten into rows with merged tool cards`() {
        val file = wireFile(
            """{"type":"metadata","protocol_version":"1.5","created_at":1786158160922}""",
            """{"type":"turn.prompt","input":[{"type":"text","text":"run echo"}],"time":1}""",
            """{"type":"context.append_loop_event","event":{"type":"content.part","uuid":"u1","turnId":"0","step":1,"part":{"type":"think","think":"pondering"}},"time":2}""",
            """{"type":"context.append_loop_event","event":{"type":"content.part","uuid":"u2","turnId":"0","step":1,"part":{"type":"text","text":"Sure."}},"time":3}""",
            """{"type":"llm.request","kind":"loop","model":"k3"}""",
            """{"type":"context.append_loop_event","event":{"type":"tool.call","uuid":"u3","turnId":"0","step":1,"toolCallId":"tool_A","name":"Bash","args":{"command":"echo hi"}},"time":4}""",
            """{"type":"context.append_loop_event","event":{"type":"tool.result","parentUuid":"u3","toolCallId":"tool_A","result":{"output":"hi\n"}},"time":5}""",
            """{"type":"context.append_loop_event","event":{"type":"step.end","uuid":"u4","finishReason":"tool_use"},"time":6}""",
            """{"type":"context.append_loop_event","event":{"type":"content.part","uuid":"u5","turnId":"0","step":2,"part":{"type":"text","text":"Done."}},"time":7}""",
            """{"type":"turn.ended","turnId":0,"reason":"completed"}""",
        )
        val rows = KimiTranscriptReplay.read(file)
        // user, assistant("Sure."), tool card, assistant("Done.") — think parts / llm.request / step.* skipped
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT), rows.map { it.role })
        assertEquals("run echo", rows[0].text)
        val tool = rows[2]
        assertEquals("Bash", tool.tool)
        assertEquals(true, tool.ok)
        assertEquals("hi\n", tool.output)
        assertTrue(tool.text.contains("echo hi")) // args preview on the card
    }

    @Test
    fun `failed tool result marks the card not-ok with its output`() {
        val file = wireFile(
            """{"type":"context.append_loop_event","event":{"type":"tool.call","uuid":"u1","toolCallId":"tool_B","name":"Read","args":{"file_path":"README.md"}},"time":1}""",
            """{"type":"context.append_loop_event","event":{"type":"tool.result","parentUuid":"u1","toolCallId":"tool_B","result":{"output":"\"README.md\" does not exist.","isError":true}},"time":2}""",
        )
        val rows = KimiTranscriptReplay.read(file)
        assertEquals(1, rows.size)
        assertEquals("Read", rows[0].tool)
        assertEquals(false, rows[0].ok)
        assertEquals("\"README.md\" does not exist.", rows[0].output)
    }

    @Test
    fun `append_message user lines stay skipped (system-reminder folding)`() {
        val file = wireFile(
            """{"type":"context.append_message","message":{"role":"user","content":[{"type":"text","text":"<system-reminder>\nfolded"}]},"time":1}""",
            """{"type":"turn.prompt","input":[{"type":"text","text":"real prompt"}],"time":2}""",
        )
        val rows = KimiTranscriptReplay.read(file)
        assertEquals(1, rows.size)
        assertEquals("real prompt", rows[0].text)
    }

    @Test
    fun `unknown and unparseable lines produce no rows, never throws`() {
        val file = wireFile(
            "not json",
            """{"type":"usage.record","model":"kimi-code/k3","usage":{"output":76}}""",
            """{"type":"context.append_loop_event","event":{"type":"content.part","uuid":"u","part":{"type":"text","text":"ok"}},"time":1}""",
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
