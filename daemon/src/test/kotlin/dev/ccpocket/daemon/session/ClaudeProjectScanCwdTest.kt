package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.disk.ProjectPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #360 security review L3: Claude's project directory name is a lossy encoding of the path — `/x/a_b` and
 * `/x/a.b` land in ONE directory — so the managed scan must attribute rows by the cwd each transcript recorded, never
 * by the directory alone. A row without a recorded cwd is kept but the scan can no longer claim completeness.
 */
class ClaudeProjectScanCwdTest {
    private val tmp: Path = Files.createTempDirectory("ccp-claude-cwd").toRealPath()

    @AfterTest
    fun cleanup() { tmp.toFile().deleteRecursively() }

    private fun transcript(dir: Path, id: String, cwd: String?) {
        val cwdField = cwd?.let { ""","cwd":"$it"""" } ?: ""
        dir.resolve("$id.jsonl").writeText("""{"type":"user","message":{"role":"user","content":"hi $id"}$cwdField}""" + "\n")
    }

    @Test
    fun projects_sharing_one_claude_directory_never_see_each_others_sessions() {
        val underscore = Files.createDirectories(tmp.resolve("x/a_b")).toString()
        val dot = Files.createDirectories(tmp.resolve("x/a.b")).toString()
        assertEquals(ProjectPaths.dirKey(underscore), ProjectPaths.dirKey(dot), "precondition: the encoding really collides")
        val shared = Files.createDirectories(tmp.resolve("projects").resolve(ProjectPaths.dirKey(underscore)))
        transcript(shared, "mine", underscore)
        transcript(shared, "theirs", dot)

        val scanA = claudeProjectScan(shared, underscore)
        assertEquals(listOf("mine"), scanA.items.map { it.sessionId })
        assertEquals(ScanCompleteness.COMPLETE, scanA.completeness)
        val scanB = claudeProjectScan(shared, dot)
        assertEquals(listOf("theirs"), scanB.items.map { it.sessionId })

        val alias = Files.createSymbolicLink(tmp.resolve("alias"), Path.of(underscore)).toString()
        assertEquals(listOf("mine"), claudeProjectScan(shared, alias).items.map { it.sessionId }, "a path alias is still the same project")
    }

    @Test
    fun a_row_without_a_recorded_cwd_is_kept_but_the_scan_is_no_longer_complete() {
        val project = Files.createDirectories(tmp.resolve("p")).toString()
        val dir = Files.createDirectories(tmp.resolve("projects/p"))
        transcript(dir, "known", project)
        transcript(dir, "unknown", null)
        val scan = claudeProjectScan(dir, project)
        assertEquals(setOf("known", "unknown"), scan.items.map { it.sessionId }.toSet())
        assertEquals(ScanCompleteness.PARTIAL, scan.completeness, "migration must not treat it as proof")
        assertTrue(scan.items.all { it.agent == dev.ccpocket.protocol.AgentKind.CLAUDE })
    }
}
