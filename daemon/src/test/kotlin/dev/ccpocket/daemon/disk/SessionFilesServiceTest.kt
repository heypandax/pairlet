package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.AgentKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionFilesServiceTest {

    @TempDir
    lateinit var tmp: Path

    private fun claudeTranscript(vararg toolUses: Pair<String, String>): Path {
        // (toolName, file_path) pairs, one assistant line each, plus noise lines the scan must skip
        val lines = buildList {
            add("""{"type":"user","cwd":"/w","message":{"content":"do it"}}""")
            toolUses.forEach { (name, p) ->
                val inputKey = if (name == "NotebookEdit") "notebook_path" else "file_path"
                add("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"$name","input":{"$inputKey":"$p","content":"x"}},{"type":"text","text":"done"}]}}""")
            }
            add("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"/w/ignored.txt"}}]}}""")
            add("not json at all")
        }
        return tmp.resolve("session.jsonl").also { Files.write(it, lines) }
    }

    @Test
    fun claude_changed_files_extracts_write_edit_notebook_and_skips_reads() {
        val t = claudeTranscript("Write" to "/w/a.md", "Edit" to "/w/b.kt", "NotebookEdit" to "/w/n.ipynb")
        val files = SessionFilesService.changedFilesIn(AgentKind.CLAUDE, t, "/w")
        assertEquals(listOf("/w/n.ipynb", "/w/b.kt", "/w/a.md"), files.map { it.path }) // newest first
        assertEquals(listOf("notebook", "edit", "write"), files.map { it.op })
        assertFalse(files.any { it.path.endsWith("ignored.txt") }) // Read is not a change
    }

    @Test
    fun claude_repeat_touches_collapse_with_last_op_and_count() {
        val t = claudeTranscript("Write" to "/w/a.md", "Edit" to "/w/a.md", "Edit" to "/w/a.md")
        val files = SessionFilesService.changedFilesIn(AgentKind.CLAUDE, t, "/w")
        assertEquals(1, files.size)
        assertEquals("edit", files[0].op)
        assertEquals(3, files[0].edits)
    }

    @Test
    fun codex_changed_files_parses_patch_envelopes_in_both_call_shapes() {
        // function_call carries the patch nested inside a JSON string (escaped \n); custom_tool_call raw
        val fnArgs = """{\"command\":[\"apply_patch\",\"*** Begin Patch\\n*** Update File: src/App.kt\\n@@\\n*** End Patch\"]}"""
        val lines = listOf(
            """{"type":"session_meta","payload":{"cwd":"/w"}}""",
            """{"type":"response_item","payload":{"type":"function_call","name":"shell","arguments":"$fnArgs"}}""",
            """{"type":"response_item","payload":{"type":"custom_tool_call","name":"apply_patch","input":"*** Begin Patch\n*** Add File: docs/new.md\n+hi\n*** Delete File: old.txt\n*** End Patch"}}""",
        )
        val t = tmp.resolve("rollout.jsonl").also { Files.write(it, lines) }
        val files = SessionFilesService.changedFilesIn(AgentKind.CODEX, t, "/w")
        assertEquals(
            mapOf("/w/src/App.kt" to "edit", "/w/docs/new.md" to "write", "/w/old.txt" to "delete"),
            files.associate { it.path to it.op },
        )
    }

    @Test
    fun read_serves_only_paths_the_session_changed() {
        val target = tmp.resolve("a.md").also { Files.writeString(it, "# hello") }
        val secret = tmp.resolve("secret.txt").also { Files.writeString(it, "nope") }
        val t = claudeTranscript("Write" to target.toString())

        val ok = SessionFilesService.readFileIn(AgentKind.CLAUDE, t, tmp.toString(), "s", target.toString())
        assertTrue(ok.ok)
        assertEquals("# hello", ok.text)
        assertFalse(ok.truncated)

        val denied = SessionFilesService.readFileIn(AgentKind.CLAUDE, t, tmp.toString(), "s", secret.toString())
        assertFalse(denied.ok)
        assertNull(denied.text)
    }

    @Test
    fun read_caps_text_and_flags_truncation() {
        val big = tmp.resolve("big.txt").also { Files.writeString(it, "x".repeat(SessionFilesService.TEXT_CAP_BYTES + 500)) }
        val t = claudeTranscript("Write" to big.toString())
        val r = SessionFilesService.readFileIn(AgentKind.CLAUDE, t, tmp.toString(), "s", big.toString())
        assertTrue(r.ok)
        assertTrue(r.truncated)
        assertEquals(SessionFilesService.TEXT_CAP_BYTES, r.text!!.length)
        assertEquals((SessionFilesService.TEXT_CAP_BYTES + 500).toLong(), r.totalBytes)
    }

    @Test
    fun read_rejects_binary_and_returns_images_as_base64() {
        val png = tmp.resolve("shot.png").also { Files.write(it, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 1, 2)) }
        val bin = tmp.resolve("blob.dat").also { Files.write(it, byteArrayOf(1, 0, 2, 0)) }
        val t = claudeTranscript("Write" to png.toString(), "Write" to bin.toString())

        val img = SessionFilesService.readFileIn(AgentKind.CLAUDE, t, tmp.toString(), "s", png.toString())
        assertTrue(img.ok)
        assertEquals("image/png", img.mediaType)
        assertNull(img.text)

        val blob = SessionFilesService.readFileIn(AgentKind.CLAUDE, t, tmp.toString(), "s", bin.toString())
        assertFalse(blob.ok)
    }

    @Test
    fun read_reports_a_deleted_file_gracefully() {
        val gone = tmp.resolve("gone.md")
        val t = claudeTranscript("Write" to gone.toString())
        val r = SessionFilesService.readFileIn(AgentKind.CLAUDE, t, tmp.toString(), "s", gone.toString())
        assertFalse(r.ok)
    }
}
