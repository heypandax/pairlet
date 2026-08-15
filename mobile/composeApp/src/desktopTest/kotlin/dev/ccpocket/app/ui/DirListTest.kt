package dev.ccpocket.app.ui

import dev.ccpocket.app.data.ALL_AGENTS
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AgentKind
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

    @Test
    fun agent_filter_hides_projects_without_that_backends_history_but_keeps_legacy_unknowns() {
        val dirs = listOf(
            d("/p/claude").copy(sessionAgents = listOf(AgentKind.CLAUDE)),
            d("/p/opencode").copy(sessionAgents = listOf(AgentKind.OPENCODE)),
            d("/p/mixed").copy(sessionAgents = listOf(AgentKind.CLAUDE, AgentKind.OPENCODE)),
            d("/p/old-daemon"), // no sessionAgents field on the wire → fail open until daemon upgrade
            DirectoryEntry("/shared/new", "new", isDir = true, hasSessions = false, sharedBy = "owner"),
        )

        assertEquals(
            listOf("/p/opencode", "/p/mixed", "/p/old-daemon", "/shared/new"),
            filterDirectoriesByAgent(dirs, setOf(AgentKind.OPENCODE)).map { it.path },
        )
        assertEquals(dirs, filterDirectoriesByAgent(dirs, ALL_AGENTS))
    }

    @Test
    fun agent_filter_removes_other_backends_live_rows_and_recomputes_legacy_scalars() {
        val claude = ActiveSession("c1", "Claude turn", executing = true, agent = AgentKind.CLAUDE)
        val opencode = ActiveSession("o1", "OpenCode turn", busy = true, agent = AgentKind.OPENCODE)
        val row = d("/p/mixed").copy(
            sessionAgents = listOf(AgentKind.CLAUDE, AgentKind.OPENCODE),
            open = true, executing = true, busy = true,
            activeSessionId = claude.sessionId, activeSessionTitle = claude.title,
            activeSessions = listOf(claude, opencode),
        )

        val filtered = filterDirectoriesByAgent(listOf(row), setOf(AgentKind.OPENCODE)).single()
        assertEquals(listOf(opencode), filtered.activeSessions)
        assertEquals("o1", filtered.activeSessionId)
        assertEquals("OpenCode turn", filtered.activeSessionTitle)
        assertEquals(false, filtered.executing)
        assertEquals(true, filtered.busy)
    }

    // ── search as a mode of the Projects screen (issue #260) ─────────────────────────────────────────

    /**
     * Collapsing the search field CLEARS the query. This is the invariant that keeps the #250 empty states
     * honest: a collapsed field holding a live term would filter the list from a control that is not on
     * screen, and would be the one way to reach «no project matches "…"» with no search to explain it.
     */
    @Test
    fun collapsing_the_search_field_always_clears_the_query() {
        val typed = ProjectSearch().expanded().typed("relay")
        assertEquals(true, typed.open)
        assertEquals("relay", typed.query)

        val collapsed = typed.collapsed()
        assertEquals(false, collapsed.open)
        assertEquals("", collapsed.query, "a closed field never keeps a term the user cannot see")

        // …and re-opening starts clean rather than restoring the last search
        assertEquals("", collapsed.expanded().query)
    }

    @Test
    fun the_collapsed_default_is_closed_and_empty() {
        assertEquals(ProjectSearch(open = false, query = ""), ProjectSearch())
    }
}
