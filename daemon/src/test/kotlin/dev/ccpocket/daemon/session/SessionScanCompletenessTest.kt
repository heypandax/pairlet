package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.codex.CodexPaths
import dev.ccpocket.daemon.codex.CodexTranscriptScanner
import dev.ccpocket.daemon.disk.TranscriptScanner
import dev.ccpocket.protocol.AgentKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Issue #360: a scan that errored, was truncated or lacked permission must be distinguishable from a project that
 * really has no sessions, while the legacy display list keeps its exact behaviour.
 */
class SessionScanCompletenessTest {
    private val tmp: Path = Files.createTempDirectory("ccp-scan-complete")

    @BeforeTest
    fun reset() {
        CodexTranscriptScanner.clearForTest()
        CodexPaths.clearForTest()
    }

    @AfterTest
    fun cleanup() {
        tmp.toFile().walkTopDown().forEach { it.setReadable(true); it.setExecutable(true); it.setWritable(true) }
        tmp.toFile().deleteRecursively()
        CodexTranscriptScanner.clearForTest()
        CodexPaths.clearForTest()
    }

    /** Whether this user is actually denied by a 000 directory (root and some CI sandboxes are not). */
    private fun permissionsEnforced(): Boolean {
        val probe = Files.createDirectories(tmp.resolve("perm-probe"))
        probe.toFile().setReadable(false, false); probe.toFile().setExecutable(false, false)
        val denied = runCatching { Files.list(probe).use { it.count() } }.isFailure
        probe.toFile().setReadable(true); probe.toFile().setExecutable(true)
        return denied
    }

    private fun claudeTranscript(dir: Path, id: String) {
        dir.resolve("$id.jsonl").writeText("""{"type":"user","message":{"role":"user","content":"hello $id"},"cwd":"/repo"}""" + "\n")
    }

    // ── Claude ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun claude_missing_and_empty_project_dirs_are_complete_empty_scans() {
        val missing = claudeProjectScan(tmp.resolve("no-such-project"), "/repo")
        assertEquals(ScanCompleteness.COMPLETE, missing.completeness)
        assertTrue(missing.items.isEmpty())
        val empty = claudeProjectScan(Files.createDirectories(tmp.resolve("empty")), "/repo")
        assertEquals(ScanCompleteness.COMPLETE, empty.completeness)
    }

    @Test
    fun claude_rows_are_complete_and_tagged() {
        val dir = Files.createDirectories(tmp.resolve("p"))
        claudeTranscript(dir, "s1"); claudeTranscript(dir, "s2")
        val scan = claudeProjectScan(dir, "/repo")
        assertEquals(ScanCompleteness.COMPLETE, scan.completeness)
        assertEquals(setOf("s1", "s2"), scan.items.map { it.sessionId }.toSet())
        assertTrue(scan.items.all { it.agent == AgentKind.CLAUDE })
        assertEquals(TranscriptScanner.scan(dir).map { it.sessionId }, scan.items.map { it.sessionId }, "same rows as the legacy scan")
    }

    @Test
    fun claude_an_unreadable_transcript_makes_the_scan_partial() {
        val dir = Files.createDirectories(tmp.resolve("p"))
        claudeTranscript(dir, "good")
        Files.createDirectories(dir.resolve("broken.jsonl")) // matches the glob, cannot be read as a file
        val scan = claudeProjectScan(dir, "/repo")
        assertEquals(ScanCompleteness.PARTIAL, scan.completeness)
        assertEquals(1, scan.failedCount)
        assertEquals(listOf("good"), scan.items.map { it.sessionId })
    }

    @Test
    fun claude_a_non_directory_is_an_error_not_an_empty_project() {
        val file = Files.writeString(tmp.resolve("not-a-dir"), "x")
        assertEquals(ScanCompleteness.ERROR, claudeProjectScan(file, "/repo").completeness)
    }

    @Test
    fun claude_permission_failures_are_not_empty_projects_and_the_legacy_scan_is_unchanged() {
        if (!permissionsEnforced()) return
        val locked = Files.createDirectories(tmp.resolve("locked"))
        claudeTranscript(locked, "s1")
        locked.toFile().setReadable(false, false)
        assertEquals(ScanCompleteness.PERMISSION_DENIED, claudeProjectScan(locked, "/repo").completeness)
        assertFailsWith<Exception>("the legacy scan still throws on a failed listing") { TranscriptScanner.scan(locked) }
        locked.toFile().setReadable(true)

        val parent = Files.createDirectories(tmp.resolve("parent"))
        val child = Files.createDirectories(parent.resolve("child"))
        parent.toFile().setExecutable(false, false); parent.toFile().setReadable(false, false)
        val scan = claudeProjectScan(child, "/repo")
        assertTrue(scan.completeness != ScanCompleteness.COMPLETE, "an undeterminable dir must not read as a project without history: $scan")
    }

    // ── Codex ───────────────────────────────────────────────────────────────────────────────────────────

    private fun codexRollout(dir: Path, id: String, cwd: String, mtime: Long): Path {
        val f = Files.createDirectories(dir).resolve("rollout-2026-09-14T00-00-00-$id.jsonl")
        f.writeText(
            """
            {"timestamp":"t0","type":"session_meta","payload":{"id":"$id","cwd":"$cwd","cli_version":"0.124.0"}}
            {"timestamp":"t2","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"prompt $id"}]}}
            """.trimIndent(),
        )
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtime))
        return f
    }

    @Test
    fun codex_missing_root_is_complete_empty_and_matching_rollouts_are_complete() {
        val project = Files.createDirectories(tmp.resolve("project")).toString()
        val missing = CodexTranscriptScanner.scanDetailed(project, tmp.resolve("no-codex"), titles = emptyMap())
        assertEquals(ScanCompleteness.COMPLETE, missing.completeness)
        assertTrue(missing.items.isEmpty())

        val root = tmp.resolve("sessions")
        codexRollout(root.resolve("2026/09/14"), "t-mine", project, 2_000)
        codexRollout(root.resolve("2026/09/13"), "t-other", tmp.resolve("elsewhere").toString(), 1_000)
        val scan = CodexTranscriptScanner.scanDetailed(project, root, titles = emptyMap())
        assertEquals(ScanCompleteness.COMPLETE, scan.completeness)
        assertEquals(listOf("t-mine"), scan.items.map { it.sessionId })
        assertTrue(scan.items.all { it.agent == AgentKind.CODEX })
    }

    @Test
    fun codex_a_capped_walk_is_truncated_not_complete() {
        val project = Files.createDirectories(tmp.resolve("project")).toString()
        val root = tmp.resolve("sessions")
        codexRollout(root.resolve("a"), "t-new", project, 3_000)
        codexRollout(root.resolve("b"), "t-old", project, 1_000)
        val scan = CodexTranscriptScanner.scanDetailed(project, root, limit = 1, titles = emptyMap())
        assertEquals(ScanCompleteness.TRUNCATED, scan.completeness)
        assertEquals(listOf("t-new"), scan.items.map { it.sessionId })
    }

    @Test
    fun codex_unlistable_directories_and_unreadable_rollouts_are_reported() {
        if (!permissionsEnforced()) return
        val project = Files.createDirectories(tmp.resolve("project")).toString()
        val root = tmp.resolve("sessions")
        codexRollout(root.resolve("ok"), "t-ok", project, 2_000)
        val locked = Files.createDirectories(root.resolve("locked"))
        codexRollout(locked, "t-hidden", project, 1_000)
        locked.toFile().setReadable(false, false)
        val denied = CodexTranscriptScanner.scanDetailed(project, root, titles = emptyMap())
        assertEquals(ScanCompleteness.PERMISSION_DENIED, denied.completeness)
        // the legacy walk silently skips it — which is exactly why it cannot be used as proof
        assertEquals(listOf("t-ok"), denied.items.map { it.sessionId })
        locked.toFile().setReadable(true)

        CodexTranscriptScanner.clearForTest(); CodexPaths.clearForTest()
        val unreadable = codexRollout(root.resolve("ok"), "t-unreadable", project, 1_500)
        unreadable.toFile().setReadable(false, false)
        assertEquals(ScanCompleteness.PARTIAL, CodexTranscriptScanner.scanDetailed(project, root, titles = emptyMap()).completeness)
    }
}
