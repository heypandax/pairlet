package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ActiveSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * User feedback 2026-10-07, two faces of one project list:
 *
 *  1. Claude Code ≥ 2.1.169 MOVES a session's transcript into the new directory's project folder when the
 *     session changes directory (`/cd`, EnterWorktree) and appends `{"type":"relocated","relocatedCwd":…}`;
 *     the records before the move keep the old cwd. Reading the first cwd filed the worktree's folder under
 *     the main checkout, so the worktree never got a row and the session was listed where it could not be
 *     opened. The row's identity is now the transcript's HOME ([ProjectPaths.homeCwd]).
 *  2. A removed worktree / deleted project keeps its transcripts, and its row stayed until someone deleted
 *     the folder under ~/.claude/projects by hand. A row whose directory is gone is hidden (not deleted —
 *     it returns with the directory) unless the daemon still drives a conversation there.
 */
class DirectoryServiceRelocationTest {

    private val projects = Files.createTempDirectory("ccp-projects")
    private val main = Files.createTempDirectory("ccp-main").toRealPath()
    private val worktree = main.resolve("_local/worktrees/feature").createDirectories()

    @AfterTest
    fun cleanup() {
        projects.toFile().deleteRecursively()
        main.toFile().deleteRecursively()
    }

    private fun service() = DirectoryService(
        projectsRoot = { projects },
        codexCwds = { emptyMap() }, opencodeCwds = { emptyMap() }, kimiCwds = { emptyMap() },
        zcodeCwds = { emptyMap() }, dshCwds = { emptyMap() },
        liveClaudeCwds = { emptySet() }, liveCodexCwds = { emptySet() },
        activeCodexSessions = { emptyMap() },
        tempNoiseRoots = emptyList(), // fixtures live under the real system temp
    )

    private fun project(cwd: Path, vararg lines: String): Path {
        val dir = projects.resolve(ProjectPaths.dirKey(cwd.toString())).createDirectories()
        dir.resolve("s-${dir.fileName}.jsonl").writeText(lines.joinToString("\n") + "\n")
        return dir
    }

    private fun userTurn(cwd: Path, branch: String) =
        """{"type":"user","message":{"role":"user","content":"hi"},"cwd":"$cwd","gitBranch":"$branch"}"""

    @Test
    fun a_session_claude_moved_into_a_worktree_gives_the_worktree_its_own_row() {
        project(main, userTurn(main, "main"))
        // the moved transcript: starts in main, relocated into the worktree, continues there — as claude writes it
        project(
            worktree,
            userTurn(main, "main"),
            """{"type":"relocated","relocatedCwd":"$worktree","sessionId":"s"}""",
            userTurn(worktree, "feature"),
            """{"type":"relocated","relocatedCwd":"$worktree","sessionId":"s"}""", // claude re-stamps it at exit
        )
        val rows = service().listDirectories(null)
        assertEquals(
            setOf(main.toString(), worktree.toString()), rows.map { it.path }.toSet(),
            "main keeps its row AND the worktree gets one — before the fix the worktree folder read as main and was deduped away",
        )
    }

    @Test
    fun a_session_moved_back_out_of_a_worktree_belongs_to_the_main_checkout_again() {
        project(
            main,
            userTurn(main, "main"),
            """{"type":"relocated","relocatedCwd":"$worktree"}""",
            userTurn(worktree, "feature"),
            """{"type":"relocated","relocatedCwd":"$main"}""", // ExitWorktree: the file is back under main's folder
            userTurn(main, "main"),
        )
        assertEquals(listOf(main.toString()), service().listDirectories(null).map { it.path })
    }

    @Test
    fun a_row_whose_directory_is_gone_is_hidden_until_the_directory_returns() {
        project(main, userTurn(main, "main"))
        val removed = project(worktree, userTurn(worktree, "feature"))
        assertEquals(2, service().listDirectories(null).size, "precondition: both rows list while both dirs exist")

        worktree.toFile().deleteRecursively() // `git worktree remove` — the transcripts stay
        assertTrue(Files.isDirectory(removed), "the project folder is untouched")
        assertEquals(listOf(main.toString()), service().listDirectories(null).map { it.path }, "the removed worktree's row is gone")

        worktree.createDirectories()
        assertEquals(2, service().listDirectories(null).size, "nothing was deleted: the row is back with the directory")
    }

    @Test
    fun a_live_conversation_keeps_its_row_even_when_its_directory_vanished() {
        project(worktree, userTurn(worktree, "feature"))
        worktree.toFile().deleteRecursively()
        val live = mapOf(worktree.toString() to listOf(ActiveSession("live-1", "running", executing = true)))
        val rows = service().listDirectories(null, liveByCwd = live)
        assertEquals(listOf(worktree.toString()), rows.map { it.path }, "a running agent's cwd must stay reachable so it can be stopped")
        assertTrue(rows.single().open)
    }
}
