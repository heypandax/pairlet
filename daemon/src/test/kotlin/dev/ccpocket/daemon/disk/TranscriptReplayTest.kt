package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ChatRole
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptReplayTest {

    private fun tmpFile(name: String) = Files.createTempDirectory("ccp-replay").resolve(name)

    @Test
    fun drops_harness_noise_and_keeps_real_turns() {
        val f = tmpFile("sess.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","message":{"role":"user","content":"deploy please"}}""",
                // standalone background-shell notice — pure plumbing, dropped
                """{"type":"user","message":{"role":"user","content":"<task-notification>\n<task-id>x</task-id>\n<status>stopped</status>\n</task-notification>"}}""",
                // bare resume nudge the harness injects on continuation — dropped
                """{"type":"user","message":{"role":"user","content":"Continue from where you left off."}}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"on it"}]}}""",
                // a task-notification PREPENDED to real text keeps the turn — genuine input is never eaten
                """{"type":"user","message":{"role":"user","content":"<task-notification>\n<task-id>y</task-id>\n</task-notification>\nand also bump the version"}}""",
            ).joinToString("\n"),
        )

        val msgs = TranscriptReplay.read(f)

        assertEquals(3, msgs.size) // "deploy please", assistant "on it", and the prepended real turn
        assertEquals(ChatRole.USER, msgs[0].role)
        assertEquals("deploy please", msgs[0].text)
        assertEquals(ChatRole.ASSISTANT, msgs[1].role)
        assertEquals("on it", msgs[1].text)
        assertEquals(ChatRole.USER, msgs[2].role)
        assertTrue(msgs[2].text.contains("bump the version"))
    }

    @Test
    fun synthetic_placeholder_replays_flagged_as_error() {
        // a context-dead session's tail: the CLI's `<synthetic>` placeholders must replay as errors,
        // not as normal assistant replies the user mistakes for answers (issue #65)
        val f = tmpFile("dead.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","message":{"role":"user","content":"hello?"}}""",
                """{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"No response requested."}]}}""",
                """{"type":"user","message":{"role":"user","content":"still there?"}}""",
                """{"type":"assistant","message":{"model":"claude-sonnet-5","content":[{"type":"text","text":"yes"}]}}""",
            ).joinToString("\n"),
        )

        val msgs = TranscriptReplay.read(f)

        assertEquals(4, msgs.size)
        assertTrue(msgs[1].error) // the placeholder
        assertEquals("No response requested.", msgs[1].text)
        assertTrue(!msgs[3].error) // the real reply
    }
}
