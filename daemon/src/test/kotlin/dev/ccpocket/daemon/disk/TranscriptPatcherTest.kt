package dev.ccpocket.daemon.disk

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptPatcherTest {

    private fun tmpFile(name: String) = Files.createTempDirectory("ccp-patch").resolve(name)

    @Test
    fun rewrites_sdk_entrypoint_to_cli_drops_queue_ops_and_is_idempotent() {
        val f = tmpFile("sess-1.jsonl")
        f.writeText(
            listOf(
                """{"type":"queue-operation","operation":"enqueue","sessionId":"sess-1"}""",
                """{"type":"user","entrypoint":"sdk-cli","promptSource":"sdk","message":{"role":"user","content":"hi"}}""",
                """{"type":"assistant","entrypoint":"sdk-cli","message":{"content":[{"type":"text","text":"ok"}]}}""",
            ).joinToString("\n"),
        )

        assertTrue(TranscriptPatcher.unhide(f))
        val patched = f.readText().trimEnd().lines()
        // queue-operation bookkeeping is dropped; the two real turns survive with entrypoint rewritten
        assertEquals(2, patched.size)
        assertEquals("""{"type":"user","entrypoint":"cli","promptSource":"sdk","message":{"role":"user","content":"hi"}}""", patched[0])
        assertEquals("""{"type":"assistant","entrypoint":"cli","message":{"content":[{"type":"text","text":"ok"}]}}""", patched[1])

        // second pass finds nothing to change and must not rewrite the file
        assertFalse(TranscriptPatcher.unhide(f))
        assertEquals(patched, f.readText().trimEnd().lines())
        assertFalse(Files.exists(f.resolveSibling("sess-1.jsonl.pocket-tmp")))
    }

    @Test
    fun drops_task_notification_turn_and_relinks_child_to_grandparent() {
        val f = tmpFile("sess-tn.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","uuid":"a","parentUuid":null,"message":{"role":"user","content":"deploy please"}}""",
                """{"type":"user","uuid":"n","parentUuid":"a","message":{"role":"user","content":"<task-notification>\n<task-id>x</task-id>\n</task-notification>"}}""",
                """{"type":"assistant","uuid":"b","parentUuid":"n","message":{"content":[{"type":"text","text":"on it"}]}}""",
            ).joinToString("\n"),
        )

        assertTrue(TranscriptPatcher.unhide(f))
        val patched = f.readText().trimEnd().lines()
        assertEquals(2, patched.size) // the notification turn is gone
        assertTrue(patched.none { it.contains("task-notification") })
        // the assistant turn re-points past the dropped notification, onto its grandparent 'a'
        assertTrue(patched[1].contains(""""uuid":"b""""))
        assertTrue(patched[1].contains(""""parentUuid":"a""""))
    }

    @Test
    fun drops_consecutive_notifications_and_relinks_to_surviving_ancestor() {
        val f = tmpFile("sess-cc.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","uuid":"a","parentUuid":null,"message":{"role":"user","content":"hi"}}""",
                """{"type":"user","uuid":"n1","parentUuid":"a","message":{"role":"user","content":"<task-notification>\n</task-notification>"}}""",
                """{"type":"user","uuid":"n2","parentUuid":"n1","message":{"role":"user","content":"<task-notification>\n</task-notification>"}}""",
                """{"type":"assistant","uuid":"b","parentUuid":"n2","message":{"content":[{"type":"text","text":"ok"}]}}""",
            ).joinToString("\n"),
        )

        assertTrue(TranscriptPatcher.unhide(f))
        val patched = f.readText().trimEnd().lines()
        assertEquals(2, patched.size)
        assertTrue(patched.none { it.contains("task-notification") })
        assertTrue(patched[1].contains(""""parentUuid":"a"""")) // re-linked across BOTH dropped turns
    }

    @Test
    fun dropping_a_root_notification_nulls_the_child_parent() {
        val f = tmpFile("sess-root.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","uuid":"n","parentUuid":null,"message":{"role":"user","content":"<task-notification>\n</task-notification>"}}""",
                """{"type":"user","uuid":"a","parentUuid":"n","message":{"role":"user","content":"real msg"}}""",
            ).joinToString("\n"),
        )

        assertTrue(TranscriptPatcher.unhide(f))
        val patched = f.readText().trimEnd().lines()
        assertEquals(1, patched.size)
        assertTrue(patched[0].contains(""""uuid":"a""""))
        assertTrue(patched[0].contains(""""parentUuid":null""")) // root child becomes a new root
    }

    @Test
    fun keeps_a_turn_with_real_text_after_the_notification() {
        val f = tmpFile("sess-mixed.jsonl")
        val line = """{"type":"user","uuid":"a","parentUuid":null,"message":{"role":"user","content":"<task-notification>\n</task-notification>\nplease deploy"}}"""
        f.writeText(line)

        // real text remains after the notification block -> not pure noise, nothing to do
        assertFalse(TranscriptPatcher.unhide(f))
        assertEquals(line, f.readText().trimEnd())
    }

    @Test
    fun ignores_tag_escaped_inside_message_content() {
        val f = tmpFile("sess-2.jsonl")
        // prompt text QUOTING the tag carries escaped quotes — must survive untouched
        val line = """{"type":"user","entrypoint":"cli","message":{"role":"user","content":"why is \"entrypoint\":\"sdk-cli\" hidden?"}}"""
        f.writeText(line)

        assertFalse(TranscriptPatcher.unhide(f))
        assertEquals(line, f.readText().trimEnd())
    }

    @Test
    fun missing_file_is_a_quiet_noop() {
        val dir = Files.createTempDirectory("ccp-patch")
        assertFalse(TranscriptPatcher.unhide(dir.resolve("nope.jsonl")))
    }
}
