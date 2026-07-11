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
    fun read_returns_images_and_binaries_as_base64() {
        val png = tmp.resolve("shot.png").also { Files.write(it, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 1, 2)) }
        val bin = tmp.resolve("blob.dat").also { Files.write(it, byteArrayOf(1, 0, 2, 0)) }
        val t = claudeTranscript("Write" to png.toString(), "Write" to bin.toString())

        val img = SessionFilesService.readFileIn(AgentKind.CLAUDE, t, tmp.toString(), "s", png.toString())
        assertTrue(img.ok)
        assertEquals("image/png", img.mediaType)
        assertNull(img.text)

        // unknown-extension binary: exportable via base64 (issue #67), never a dead "can't preview"
        val blob = SessionFilesService.readFileIn(AgentKind.CLAUDE, t, tmp.toString(), "s", bin.toString())
        assertTrue(blob.ok)
        assertEquals("application/octet-stream", blob.mediaType)
        assertNull(blob.text)
        assertFalse(blob.truncated)
    }

    @Test
    fun read_serves_documents_whole_and_fails_oversized_ones_with_a_clear_error() {
        // xlsx is a zip container — starts with PK and full of NULs; must arrive intact, never truncated
        val xlsx = tmp.resolve("report.xlsx").also { Files.write(it, byteArrayOf(0x50, 0x4B, 3, 4, 0, 0, 9)) }
        val huge = tmp.resolve("big.pdf").also { Files.write(it, ByteArray(SessionFilesService.BINARY_CAP_BYTES + 1)) }
        val t = claudeTranscript("Write" to xlsx.toString(), "Write" to huge.toString())

        val doc = SessionFilesService.readFileIn(AgentKind.CLAUDE, t, tmp.toString(), "s", xlsx.toString())
        assertTrue(doc.ok)
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", doc.mediaType)
        assertFalse(doc.truncated)
        assertEquals(7L, doc.totalBytes)

        val over = SessionFilesService.readFileIn(AgentKind.CLAUDE, t, tmp.toString(), "s", huge.toString())
        assertFalse(over.ok)
        assertTrue(over.error!!.contains("too large"))
    }

    @Test
    fun read_reports_a_deleted_file_gracefully() {
        val gone = tmp.resolve("gone.md")
        val t = claudeTranscript("Write" to gone.toString())
        val r = SessionFilesService.readFileIn(AgentKind.CLAUDE, t, tmp.toString(), "s", gone.toString())
        assertFalse(r.ok)
    }

    // ── line-level diffs (changed-files v2) ──────────────────────────────────

    /** An Edit tool_use line plus its toolUseResult line, the way the CLI records them. */
    private fun editWithPatch(path: String, hunkJson: String): List<String> = listOf(
        """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Edit","input":{"file_path":"$path"}}]}}""",
        """{"type":"user","toolUseResult":{"filePath":"$path","oldString":"a","newString":"b","structuredPatch":[$hunkJson]}}""",
    )

    @Test
    fun claude_structured_patch_yields_stats_and_a_unified_diff() {
        val hunk = """{"oldStart":10,"oldLines":3,"newStart":10,"newLines":4,"lines":[" ctx","-old","+new one","+new two"]}"""
        val lines = editWithPatch("/w/a.kt", hunk) + editWithPatch("/w/a.kt", hunk)
        val t = tmp.resolve("s.jsonl").also { Files.write(it, lines) }

        val row = SessionFilesService.changedFilesIn(AgentKind.CLAUDE, t, "/w").single()
        assertEquals(4, row.adds) // two edits × 2 added lines
        assertEquals(2, row.dels)
        assertEquals(2, row.edits)

        val diff = SessionFilesService.fileDiffIn(AgentKind.CLAUDE, t, "/w", "s", "/w/a.kt")
        assertTrue(diff.ok)
        assertEquals(4, diff.adds)
        assertEquals(2, diff.dels)
        // one hunk group per tool call, in transcript order, headers carrying the CLI's line numbers
        assertEquals(2, Regex("""@@ -10,3 \+10,4 @@""").findAll(diff.diff!!).count())
        assertTrue(diff.diff!!.startsWith("@@ -10,3 +10,4 @@\n ctx\n-old\n+new one\n+new two\n"))
        assertFalse(diff.truncated)
    }

    @Test
    fun claude_write_create_synthesizes_an_all_added_hunk() {
        val lines = listOf(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Write","input":{"file_path":"/w/new.md"}}]}}""",
            """{"type":"user","toolUseResult":{"type":"create","filePath":"/w/new.md","content":"one\ntwo","structuredPatch":[]}}""",
        )
        val t = tmp.resolve("s.jsonl").also { Files.write(it, lines) }
        val row = SessionFilesService.changedFilesIn(AgentKind.CLAUDE, t, "/w").single()
        assertEquals(2, row.adds)
        assertEquals(0, row.dels)
        val diff = SessionFilesService.fileDiffIn(AgentKind.CLAUDE, t, "/w", "s", "/w/new.md")
        assertEquals("@@ -0,0 +1,2 @@\n+one\n+two\n", diff.diff)
    }

    @Test
    fun claude_without_patch_data_keeps_null_stats_and_refuses_the_diff() {
        val t = claudeTranscript("Edit" to "/w/b.kt") // tool_use only — no toolUseResult lines
        val row = SessionFilesService.changedFilesIn(AgentKind.CLAUDE, t, "/w").single()
        assertNull(row.adds)
        assertNull(row.dels)
        val diff = SessionFilesService.fileDiffIn(AgentKind.CLAUDE, t, "/w", "s", "/w/b.kt")
        assertFalse(diff.ok)

        // and a path outside the changed set is refused outright
        assertFalse(SessionFilesService.fileDiffIn(AgentKind.CLAUDE, t, "/w", "s", "/w/secret.txt").ok)
    }

    @Test
    fun codex_patches_yield_stats_and_hunks_in_both_call_shapes() {
        // custom_tool_call: raw patch text with real newlines (context line keeps its leading space)
        val raw = "*** Begin Patch\n*** Update File: src/App.kt\n@@ class App\n ctx\n-gone\n+here\n*** Add File: docs/new.md\n+hi\n+yo\n*** Delete File: old.txt\n*** End Patch"
        // function_call: the patch rides inside the arguments JSON document, escaped
        val fnArgs = """{\"command\":[\"apply_patch\",\"*** Begin Patch\\n*** Update File: src/B.kt\\n@@\\n-x\\n+y\\n*** End Patch\"]}"""
        val lines = listOf(
            """{"type":"response_item","payload":{"type":"custom_tool_call","name":"apply_patch","input":"${raw.replace("\n", "\\n")}"}}""",
            """{"type":"response_item","payload":{"type":"function_call","name":"shell","arguments":"$fnArgs"}}""",
        )
        val t = tmp.resolve("rollout.jsonl").also { Files.write(it, lines) }

        val rows = SessionFilesService.changedFilesIn(AgentKind.CODEX, t, "/w").associateBy { it.path }
        assertEquals(1 to 1, rows.getValue("/w/src/App.kt").let { it.adds to it.dels })
        assertEquals(2 to 0, rows.getValue("/w/docs/new.md").let { it.adds to it.dels })
        assertNull(rows.getValue("/w/old.txt").adds) // Delete has no line data — stays honest null
        assertEquals(1 to 1, rows.getValue("/w/src/B.kt").let { it.adds to it.dels })

        val update = SessionFilesService.fileDiffIn(AgentKind.CODEX, t, "/w", "s", "/w/src/App.kt")
        assertEquals("@@ -0,0 +0,0 @@\n ctx\n-gone\n+here\n", update.diff) // locator has no numbers → sentinel header
        val add = SessionFilesService.fileDiffIn(AgentKind.CODEX, t, "/w", "s", "/w/docs/new.md")
        assertEquals("@@ -0,0 +0,0 @@\n+hi\n+yo\n", add.diff)
        val escaped = SessionFilesService.fileDiffIn(AgentKind.CODEX, t, "/w", "s", "/w/src/B.kt")
        assertEquals("@@ -0,0 +0,0 @@\n-x\n+y\n", escaped.diff)
    }

    @Test
    fun file_diff_caps_and_flags_truncation() {
        val bigLine = "x".repeat(1000)
        val manyLines = (1..300).joinToString(",") { """"+$bigLine"""" }
        val hunk = """{"oldStart":1,"oldLines":0,"newStart":1,"newLines":300,"lines":[$manyLines]}"""
        val t = tmp.resolve("s.jsonl").also { Files.write(it, editWithPatch("/w/big.kt", hunk)) }
        val diff = SessionFilesService.fileDiffIn(AgentKind.CLAUDE, t, "/w", "s", "/w/big.kt")
        assertTrue(diff.ok)
        assertTrue(diff.truncated)
        assertTrue(diff.diff!!.length <= SessionFilesService.DIFF_CAP_BYTES)
        assertTrue(diff.diff!!.endsWith("\n")) // cut on a whole-line boundary, not mid-line
    }

    // ── export containment (issue #67 v2 / #79) — the "no arbitrary-path read" red line ──────

    @Test
    fun export_containment_allows_inside_and_refuses_dotdot_and_symlink_escapes() {
        val proj = Files.createDirectories(tmp.resolve("proj"))
        val inside = Files.writeString(proj.resolve("out.csv"), "a,b")
        val secret = Files.writeString(tmp.resolve("secret.txt"), "nope")
        Files.createSymbolicLink(proj.resolve("link.csv"), secret)

        val ok = SessionFilesService.containedForExport(proj.toString(), "out.csv")
        assertTrue(ok is SessionFilesService.ExportGate.Allowed)
        assertEquals(inside.toRealPath(), (ok as SessionFilesService.ExportGate.Allowed).file)

        // lexical `..` escape: refused before the file is even stat'd
        assertEquals(SessionFilesService.ExportGate.Outside, SessionFilesService.containedForExport(proj.toString(), "../secret.txt"))
        // symlink escape: inside lexically, outside canonically — refused
        assertEquals(SessionFilesService.ExportGate.Outside, SessionFilesService.containedForExport(proj.toString(), "link.csv"))
        // absolute path pointing elsewhere resolves outside too
        assertEquals(SessionFilesService.ExportGate.Outside, SessionFilesService.containedForExport(proj.toString(), secret.toString()))
        // gone / a directory → Missing, not Outside (an honest "no such file", not a scary refusal)
        assertEquals(SessionFilesService.ExportGate.Missing, SessionFilesService.containedForExport(proj.toString(), "ghost.csv"))
        assertEquals(SessionFilesService.ExportGate.Missing, SessionFilesService.containedForExport(proj.toString(), "."))
    }

    @Test
    fun serve_export_reads_contained_files_and_gives_readable_refusals() {
        val proj = Files.createDirectories(tmp.resolve("proj"))
        Files.writeString(proj.resolve("report.md"), "# generated")
        Files.writeString(tmp.resolve("secret.txt"), "nope")

        val served = SessionFilesService.serveExport(proj.toString(), "s", "report.md")
        assertTrue(served.ok)
        assertEquals("# generated", served.text)

        val escaped = SessionFilesService.serveExport(proj.toString(), "s", "../secret.txt")
        assertFalse(escaped.ok)
        assertTrue("outside" in escaped.error!!, escaped.error)

        val gone = SessionFilesService.serveExport(proj.toString(), "s", "ghost.md")
        assertFalse(gone.ok)
        assertTrue("no longer exists" in gone.error!!, gone.error)
    }
}
