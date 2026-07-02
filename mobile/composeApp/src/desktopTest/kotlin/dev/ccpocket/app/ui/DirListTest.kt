package dev.ccpocket.app.ui

import dev.ccpocket.protocol.DirectoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Locks the tree-browse path math for Windows daemons and degenerate roots (issues #19/#22 + edges). */
class DirListTest {

    private fun d(path: String, mtime: Long = 0) =
        DirectoryEntry(path = path, name = path.split('/', '\\').last(), isDir = true, hasSessions = true, lastModified = mtime)

    @Test
    fun windows_home_root_and_drill_in() {
        val dirs = listOf(d("""C:\Users\x\code\app""", 2), d("""C:\Users\x\code\lib""", 1))
        assertEquals("""C:\Users\x""", treeRoot(dirs))
        val atRoot = buildTree(dirs, """C:\Users\x""")
        assertEquals(listOf("code"), atRoot.filterIsInstance<TreeRow.Folder>().map { it.name })
        val inCode = buildTree(dirs, """C:\Users\x\code""")
        assertEquals(listOf("""C:\Users\x\code\app""", """C:\Users\x\code\lib"""), inCode.filterIsInstance<TreeRow.Leaf>().map { it.entry.path })
    }

    @Test
    fun bare_drive_root_still_builds_the_tree() {
        // no project under C:\Users\<u> → the common-prefix walk can degenerate to bare "C:", which has
        // no separator to sniff — the tree used to render empty there
        val dirs = listOf(d("""C:\dev\app""", 2), d("""C:\work\lib""", 1))
        val root = treeRoot(dirs)
        assertEquals("C:", root)
        val rows = buildTree(dirs, root)
        assertEquals(listOf("dev", "work"), rows.filterIsInstance<TreeRow.Folder>().map { it.name })
    }

    @Test
    fun unix_slash_root_is_not_doubled() {
        val dirs = listOf(d("/opt/a/x", 2), d("/srv/b/y", 1))
        assertEquals("/", treeRoot(dirs))
        val rows = buildTree(dirs, "/")
        assertEquals(listOf("opt", "srv"), rows.filterIsInstance<TreeRow.Folder>().map { it.name })
    }

    @Test
    fun projects_outside_the_root_surface_as_orphan_leaves() {
        // home wins the root inference; the D: project would otherwise be unreachable in tree mode
        val dirs = listOf(d("""C:\Users\x\proj""", 2), d("""D:\code\other""", 1))
        assertEquals("""C:\Users\x""", treeRoot(dirs))
        val rows = buildTree(dirs, """C:\Users\x""", includeOrphans = true)
        assertTrue(rows.filterIsInstance<TreeRow.Leaf>().any { it.entry.path == """D:\code\other""" })
        // and NOT when drilled below the root (orphans belong to the root level only)
        val drilled = buildTree(dirs, """C:\Users\x\proj""", includeOrphans = false)
        assertTrue(drilled.filterIsInstance<TreeRow.Leaf>().none { it.entry.path == """D:\code\other""" })
    }

    @Test
    fun crumb_targets_anchor_at_a_multi_segment_root() {
        // root deeper than one segment: the old label-only rebuild composed root+labels[1..] and broke
        val targets = crumbTargets("""C:\dev\app\sub""", """C:\dev""")
        assertEquals(listOf("dev", "app", "sub"), targets.map { it.first })
        assertEquals(listOf("""C:\dev""", """C:\dev\app""", """C:\dev\app\sub"""), targets.map { it.second })
    }

    @Test
    fun crumb_targets_collapse_home_to_tilde() {
        val targets = crumbTargets("/Users/x/code/app", "/Users/x")
        assertEquals(listOf("~", "code", "app"), targets.map { it.first })
        assertEquals(listOf("/Users/x", "/Users/x/code", "/Users/x/code/app"), targets.map { it.second })
    }
}
