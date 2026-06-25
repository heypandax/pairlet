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
}
