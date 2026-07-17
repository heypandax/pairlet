package dev.ccpocket.daemon.disk

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Locks the composer @-file listing (issue #75): dirs-first ordering, the sub-path drill-in, the cap,
 *  and — the security-relevant part — that a `..` sub-path can never escape the session's cwd. */
class DirectoryServicePathEntriesTest {

    private fun tempTree(): java.nio.file.Path {
        val root = Files.createTempDirectory("ccp-atlist")
        root.resolve("src/app").createDirectories()
        root.resolve("src/app/Main.kt").writeText("fun main() {}")
        root.resolve("zeta.txt").writeText("z")
        root.resolve("README.md").writeText("r")
        root.resolve("Alpha").createDirectories()
        return root
    }

    @Test
    fun root_lists_dirs_first_then_files_case_insensitively() {
        val root = tempTree()
        val (entries, truncated) = DirectoryService().listPathEntries(root.toString(), "", 500)!!
        assertEquals(listOf("Alpha", "src", "README.md", "zeta.txt"), entries.map { it.name })
        assertEquals(listOf(true, true, false, false), entries.map { it.isDir })
        assertTrue(!truncated)
    }

    @Test
    fun sub_path_drills_into_a_child_directory() {
        val root = tempTree()
        val (entries, _) = DirectoryService().listPathEntries(root.toString(), "src", 500)!!
        assertEquals(listOf("app"), entries.map { it.name })
        val (nested, _) = DirectoryService().listPathEntries(root.toString(), "src/app", 500)!!
        assertEquals(listOf("Main.kt"), nested.map { it.name })
    }

    @Test
    fun limit_caps_and_flags_truncated() {
        val root = tempTree()
        val (entries, truncated) = DirectoryService().listPathEntries(root.toString(), "", 2)!!
        assertEquals(2, entries.size)
        assertTrue(truncated)
    }

    @Test
    fun dotdot_escape_is_refused() {
        val root = tempTree()
        // ".." would climb above the cwd — must never resolve outside the project subtree
        assertNull(DirectoryService().listPathEntries(root.toString(), "..", 500))
        assertNull(DirectoryService().listPathEntries(root.toString(), "src/../..", 500))
    }

    @Test
    fun missing_or_file_target_returns_null() {
        val root = tempTree()
        assertNull(DirectoryService().listPathEntries(root.toString(), "nope", 500))
        assertNull(DirectoryService().listPathEntries(root.toString(), "README.md", 500)) // a file, not a dir
    }

    @Test
    fun tilde_workdir_anchors_the_listing_at_the_daemon_home() {
        // the phone's folder browser (issue #152) lists with workdir == "~": the daemon owns the
        // expansion (only it knows the remote home), so the anchor must stay listable here
        assertTrue(DirectoryService().listPathEntries("~", "", 500) != null, "the home anchor must be listable")
    }
}
