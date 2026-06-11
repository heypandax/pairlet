package dev.ccpocket.daemon.disk

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptPatcherTest {

    @Test
    fun rewrites_sdk_entrypoint_to_cli_and_is_idempotent() {
        val dir = Files.createTempDirectory("ccp-patch")
        val f = dir.resolve("sess-1.jsonl")
        f.writeText(
            listOf(
                """{"type":"queue-operation","operation":"enqueue","sessionId":"sess-1"}""",
                """{"type":"user","entrypoint":"sdk-cli","promptSource":"sdk","message":{"role":"user","content":"hi"}}""",
                """{"type":"assistant","entrypoint":"sdk-cli","message":{"content":[{"type":"text","text":"ok"}]}}""",
            ).joinToString("\n"),
        )

        assertTrue(TranscriptPatcher.unhide(f))
        val patched = f.readText().trimEnd().lines()
        assertEquals("""{"type":"queue-operation","operation":"enqueue","sessionId":"sess-1"}""", patched[0])
        assertEquals("""{"type":"user","entrypoint":"cli","promptSource":"sdk","message":{"role":"user","content":"hi"}}""", patched[1])
        assertEquals("""{"type":"assistant","entrypoint":"cli","message":{"content":[{"type":"text","text":"ok"}]}}""", patched[2])

        // second pass finds nothing to change and must not rewrite the file
        assertFalse(TranscriptPatcher.unhide(f))
        assertEquals(patched, f.readText().trimEnd().lines())
        assertFalse(Files.exists(dir.resolve("sess-1.jsonl.pocket-tmp")))
    }

    @Test
    fun ignores_tag_escaped_inside_message_content() {
        val dir = Files.createTempDirectory("ccp-patch")
        val f = dir.resolve("sess-2.jsonl")
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
